package com.kk.tunnel.vpn.service;

import com.kk.p2p.engine.Libp2pEngine;
import com.kk.tunnel.vpn.jna.WintunLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Windows 虚拟网卡（Wintun）服务：
 * - 读取本机虚拟网卡的出站 IP 包，并通过 P2P 发送给对端
 * - 接收来自 P2P 的 IP 包，并写入本机虚拟网卡
 *
 * 说明：
 * 1) 启动该服务通常需要管理员权限（用于创建网卡/配置 IP）。
 * 2) 仅当你希望把系统流量“引导进 P2P 隧道”时才需要开启。
 */
@Slf4j
@Lazy
@Service
public class WintunService {

    private static final String ADAPTER_NAME = "KK-VPN";
    private static final String TUNNEL_TYPE = "Tunnel";

    // 是否自动启用 VPN（由 Libp2pEngine 启动时触发 startIfEnabled()）
    @Value("${vpn.enabled:false}")
    private boolean vpnEnabled;

    // 虚拟 IP 配置
    @Value("${vpn.ip.address:10.8.0.2}")
    private String vipAddress;

    @Value("${vpn.ip.mask:255.255.255.0}")
    private String vipMask;

    // 目标 PeerID（把网卡流量发给谁）
    @Value("${vpn.target.peerid:}")
    private String targetPeerId;

    // 环形缓冲区容量 (4MB)
    private static final int RING_CAPACITY = 0x400000;

    // 轮询间隔（微秒），用于空闲时减少 CPU 占用
    private static final long POLL_INTERVAL_MICROS = 100;

    private Pointer adapterHandle;
    private Pointer sessionHandle;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ExecutorService vThreadExecutor;

    @Autowired
    private Libp2pEngine libp2pEngine;

    /**
     * 由引擎启动时调用：如果配置开启，则自动启动 VPN。
     */
    public void startIfEnabled() {
        if (vpnEnabled) {
            start();
        }
    }

    /**
     * 启动虚拟网卡与读循环。可由 UI 开关触发。
     */
    public synchronized void start() {
        if (running.get()) {
            return;
        }

        try {
            log.info("初始化 Wintun 驱动...");

            // 1) 创建或打开适配器
            adapterHandle = WintunLibrary.INSTANCE.WintunOpenAdapter(new WString(ADAPTER_NAME));
            if (adapterHandle == null) {
                log.info("适配器不存在，正在创建...");
                adapterHandle = WintunLibrary.INSTANCE.WintunCreateAdapter(
                        new WString(ADAPTER_NAME),
                        new WString(TUNNEL_TYPE),
                        Pointer.NULL
                );
            }

            if (adapterHandle == null) {
                throw new RuntimeException("无法创建 Wintun 适配器，请检查管理员权限或 wintun.dll 是否可加载");
            }
            log.info("Wintun 适配器就绪: {}", ADAPTER_NAME);

            // 2) 启动会话
            sessionHandle = WintunLibrary.INSTANCE.WintunStartSession(adapterHandle, RING_CAPACITY);
            if (sessionHandle == null) {
                throw new RuntimeException("无法启动 Wintun 会话");
            }

            // 3) 配置 IP 地址（netsh）
            configureIpAddress();

            // 4) 启动读取循环
            vThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
            running.set(true);
            startReadLoop();

            log.info("KK-VPN 已启动，虚拟 IP: {}/{}", vipAddress, vipMask);
            if (targetPeerId != null && !targetPeerId.isBlank()) {
                log.info("目标 PeerID: {}", targetPeerId);
            } else {
                log.warn("未配置目标 PeerID（vpn.target.peerid），将不会转发出站流量");
            }

        } catch (Exception e) {
            log.error("VPN 启动失败", e);
            stop();
            throw (e instanceof RuntimeException) ? (RuntimeException) e : new RuntimeException(e);
        }
    }

    /**
     * 停止 VPN（结束 session、停止读循环）。适配器默认保留在系统里。
     */
    public synchronized void stop() {
        running.set(false);

        ExecutorService exec = vThreadExecutor;
        vThreadExecutor = null;
        if (exec != null) {
            exec.shutdownNow();
            try {
                exec.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (sessionHandle != null) {
            try {
                WintunLibrary.INSTANCE.WintunEndSession(sessionHandle);
            } catch (Exception ignore) {
            }
            sessionHandle = null;
        }

        log.info("VPN 服务已停止");
    }

    /**
     * 使用 Windows netsh 命令配置 IP。
     * 生产环境建议使用 Win32 IPHelper API，但 netsh 更直观。
     */
    private void configureIpAddress() throws IOException, InterruptedException {
        String cmd = String.format("netsh interface ip set address name=\"%s\" static %s %s",
                ADAPTER_NAME, vipAddress, vipMask);
        log.info("执行 IP 配置: {}", cmd);

        Process process = Runtime.getRuntime().exec(cmd);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            String error = new String(process.getErrorStream().readAllBytes());
            throw new RuntimeException("IP 配置失败 (Code " + exitCode + "): " + error);
        }

        // 等待网卡配置生效
        Thread.sleep(500);
    }

    /**
     * 核心读取循环：从虚拟网卡读取 IP 包 -> 发送给 P2P。
     *
     * 注意：这里读的是“出站包”，要让系统流量走该网卡，需要额外配置路由。
     */
    private void startReadLoop() {
        ExecutorService exec = vThreadExecutor;
        if (exec == null) {
            return;
        }

        exec.submit(() -> {
            log.info("启动虚拟线程监听 TUN 流量...");

            IntByReference packetSizeRef = new IntByReference();
            long packetCount = 0;
            long totalBytes = 0;
            long lastLogTime = System.currentTimeMillis();
            int consecutiveEmptyReads = 0;

            // 自适应轮询间隔
            long currentPollInterval = POLL_INTERVAL_MICROS;
            final long MIN_POLL_INTERVAL = 50;   // 最小 50 微秒
            final long MAX_POLL_INTERVAL = 1000; // 最大 1000 微秒 (1ms)

            while (running.get()) {
                try {
                    Pointer s = sessionHandle;
                    if (s == null) {
                        break;
                    }

                    Pointer packetPtr = WintunLibrary.INSTANCE.WintunReceivePacket(s, packetSizeRef);
                    if (packetPtr != null) {
                        consecutiveEmptyReads = 0;
                        currentPollInterval = Math.max(MIN_POLL_INTERVAL, currentPollInterval / 2);

                        int size = packetSizeRef.getValue();
                        byte[] data = new byte[size];
                        packetPtr.read(0, data, 0, size);

                        // 关键：释放 Wintun 缓冲区
                        WintunLibrary.INSTANCE.WintunReleaseReceivePacket(s, packetPtr);

                        // 转发给 P2P
                        String target = targetPeerId;
                        if (target != null && !target.isBlank()) {
                            libp2pEngine.sendPacket(target, data);

                            packetCount++;
                            totalBytes += size;

                            long now = System.currentTimeMillis();
                            if (now - lastLogTime > 10000) {
                                log.info("TUN 流量统计: 已发送 {} 个包，共 {} 字节 ({} KB)",
                                        packetCount, totalBytes, totalBytes / 1024);
                                lastLogTime = now;
                            }
                        }

                    } else {
                        consecutiveEmptyReads++;
                        if (consecutiveEmptyReads > 10) {
                            currentPollInterval = Math.min(MAX_POLL_INTERVAL, currentPollInterval * 2);
                        }

                        if (currentPollInterval >= 1000) {
                            Thread.sleep(currentPollInterval / 1000);
                        } else {
                            java.util.concurrent.locks.LockSupport.parkNanos(currentPollInterval * 1000);
                        }
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("TUN 读取异常", e);
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            log.info("TUN 读取循环已退出");
        });
    }

    /**
     * 外部接口：接收来自 P2P 的数据 -> 写入虚拟网卡。
     */
    public void writeToTun(byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }

        Pointer s = sessionHandle;
        if (!running.get() || s == null) {
            // VPN 未开启时，允许上层继续建立 P2P 连接，但不落地到网卡
            log.debug("VPN 未运行，丢弃 {} 字节数据", data.length);
            return;
        }

        try {
            int size = data.length;
            Pointer packetPtr = WintunLibrary.INSTANCE.WintunAllocateSendPacket(s, size);
            if (packetPtr == null) {
                log.warn("Wintun 写缓冲区已满，丢弃 {} 字节数据包", size);
                return;
            }

            packetPtr.write(0, data, 0, size);
            WintunLibrary.INSTANCE.WintunSendPacket(s, packetPtr);

        } catch (Exception e) {
            log.error("写入 TUN 失败", e);
        }
    }

    public void setTargetPeerId(String peerId) {
        this.targetPeerId = peerId;
        log.info("目标 PeerID 已更新为: {}", peerId);
    }

    public String getTargetPeerId() {
        return targetPeerId;
    }

    public boolean isRunning() {
        return running.get();
    }

    public String getVipAddress() {
        return vipAddress;
    }

    public String getVipMask() {
        return vipMask;
    }

    /**
     * 应用虚拟网卡 IP 配置：
     * - 未运行：仅更新内存配置，等 start() 时生效
     * - 运行中：立即调用 netsh 重新配置（可能需要管理员权限）
     */
    public synchronized void applyIpConfig(String ip, String mask) {
        if (ip == null || ip.isBlank()) {
            throw new IllegalArgumentException("vip ip is blank");
        }
        if (mask == null || mask.isBlank()) {
            throw new IllegalArgumentException("vip mask is blank");
        }

        this.vipAddress = ip.trim();
        this.vipMask = mask.trim();

        if (running.get()) {
            try {
                configureIpAddress();
            } catch (Exception e) {
                throw (e instanceof RuntimeException) ? (RuntimeException) e : new RuntimeException(e);
            }
        }
    }

    @PreDestroy
    public void cleanup() {
        stop();
    }
}
