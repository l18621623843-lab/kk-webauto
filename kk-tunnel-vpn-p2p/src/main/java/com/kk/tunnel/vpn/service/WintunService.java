package com.kk.tunnel.vpn.service;

import com.kk.p2p.engine.Libp2pEngine;
import com.kk.tunnel.vpn.jna.WintunLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import jakarta.annotation.PostConstruct;
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
 * 运行成功后可以通过 ipconfig 看到名为KK-VPN 的网卡，IP 为 10.8.0.2
 */
@Slf4j
@Lazy
@Service
public class WintunService {

    private static final String ADAPTER_NAME = "KK-VPN";
    private static final String TUNNEL_TYPE = "Tunnel";

    // 虚拟 IP 配置 - 支持从配置文件读取
    @Value("${vpn.ip.address:10.8.0.2}")
    private String VIP_ADDRESS;

    @Value("${vpn.ip.mask:255.255.255.0}")
    private String VIP_MASK;

    // 目标 PeerID - 支持从配置文件读取或动态设置
    @Value("${vpn.target.peerid:}")
    private String targetPeerId;

    // 环形缓冲区容量 (4MB)
    private static final int RING_CAPACITY = 0x400000;

    // 轮询间隔（微秒），用于空闲时减少CPU占用
    private static final long POLL_INTERVAL_MICROS = 100;

    private Pointer adapterHandle;
    private Pointer sessionHandle;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // JDK 21: 虚拟线程执行器
    private final ExecutorService vThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    // 注入 P2P 引擎
    @Autowired
    private Libp2pEngine libp2pEngine;

    @PostConstruct
    public void init() throws IOException, InterruptedException {
        log.info("初始化 Wintun 驱动...");
        try {
            // 1. 创建或打开适配器
            adapterHandle = WintunLibrary.INSTANCE.WintunOpenAdapter(new WString(ADAPTER_NAME));
            if (adapterHandle == null) {
                log.info("适配器不存在，正在创建...");
                adapterHandle = WintunLibrary.INSTANCE.WintunCreateAdapter(
                        new WString(ADAPTER_NAME),
                        new WString(TUNNEL_TYPE),
                        Pointer.NULL // 自动生成 GUID
                );
            }

            if (adapterHandle == null) {
                throw new RuntimeException("无法创建 Wintun 适配器，请检查管理员权限！");
            }
            log.info("Wintun 适配器就绪: {}", ADAPTER_NAME);

            // 2. 启动会话
            sessionHandle = WintunLibrary.INSTANCE.WintunStartSession(adapterHandle, RING_CAPACITY);
            if (sessionHandle == null) {
                throw new RuntimeException("无法启动 Wintun 会话");
            }

            // 3. 配置 IP 地址 (通过 netsh 命令行)
            configureIpAddress();

            // 4. 启动读取循环
            running.set(true);
            startReadLoop();

            log.info("KK-VPN 隧道服务启动成功，IP: {}", VIP_ADDRESS);
            if (targetPeerId != null && !targetPeerId.isEmpty()) {
                log.info("目标 PeerID: {}", targetPeerId);
            } else {
                log.warn("未配置目标 PeerID，请通过配置文件或 API 设置");
            }

        } catch (Exception e) {
            log.error("VPN 初始化失败", e);
            cleanup();
            throw e; // 阻断启动
        }
    }

    /**
     * 使用 Windows netsh 命令配置 IP
     * 生产环境建议使用 Win32 IPHelper API，但 netsh 更简单稳定
     */
    private void configureIpAddress() throws IOException, InterruptedException {
        String cmd = String.format("netsh interface ip set address name=\"%s\" static %s %s",
                ADAPTER_NAME, VIP_ADDRESS, VIP_MASK);
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
     * 核心读取循环：从虚拟网卡读取 IP 包 -> 发送给 P2P
     * 改进版本：
     * 1. 使用虚拟线程，降低线程开销
     * 2. 优化轮询策略，减少CPU占用
     * 3. 增加统计信息
     * 4. 改进错误处理
     */
    private void startReadLoop() {
        vThreadExecutor.submit(() -> {
            log.info("启动虚拟线程监听 TUN 流量...");

            IntByReference packetSizeRef = new IntByReference();
            long packetCount = 0;
            long totalBytes = 0;
            long lastLogTime = System.currentTimeMillis();
            int consecutiveEmptyReads = 0;

            // 自适应轮询间隔
            long currentPollInterval = POLL_INTERVAL_MICROS;
            final long MIN_POLL_INTERVAL = 50;  // 最小 50 微秒
            final long MAX_POLL_INTERVAL = 1000; // 最大 1000 微秒 (1ms)

            while (running.get()) {
                try {
                    // 1. 非阻塞读取数据包
                    Pointer packetPtr = WintunLibrary.INSTANCE.WintunReceivePacket(
                            sessionHandle,
                            packetSizeRef
                    );

                    if (packetPtr != null) {
                        // 重置空读计数
                        consecutiveEmptyReads = 0;

                        // 有数据时降低轮询间隔
                        currentPollInterval = Math.max(MIN_POLL_INTERVAL, currentPollInterval / 2);

                        int size = packetSizeRef.getValue();

                        // 2. 读取数据（零拷贝优化点）
                        byte[] data = new byte[size];
                        packetPtr.read(0, data, 0, size);

                        // 3. 立即释放 Wintun 缓冲区（关键！）
                        WintunLibrary.INSTANCE.WintunReleaseReceivePacket(sessionHandle, packetPtr);

                        // 4. 转发给 P2P 引擎
                        if (targetPeerId != null && !targetPeerId.isEmpty()) {
                            libp2pEngine.sendPacket(targetPeerId, data);

                            // 更新统计
                            packetCount++;
                            totalBytes += size;

                            // 每10秒输出一次统计
                            long now = System.currentTimeMillis();
                            if (now - lastLogTime > 10000) {
                                log.info("TUN 流量统计: 已发送 {} 个包，共 {} 字节 ({} KB)",
                                        packetCount, totalBytes, totalBytes / 1024);
                                lastLogTime = now;
                            }
                        } else {
                            log.debug("捕获出站流量 {} bytes，但未配置目标 PeerID", size);
                        }

                    } else {
                        // 队列为空
                        consecutiveEmptyReads++;

                        // 连续空读时逐步增加轮询间隔，减少 CPU 占用
                        if (consecutiveEmptyReads > 10) {
                            currentPollInterval = Math.min(MAX_POLL_INTERVAL, currentPollInterval * 2);
                        }

                        // 虚拟线程的 sleep 代价很低，不会阻塞平台线程
                        if (currentPollInterval >= 1000) {
                            // 大于等于 1ms，使用 Thread.sleep
                            Thread.sleep(currentPollInterval / 1000);
                        } else {
                            // 小于 1ms，使用 LockSupport.parkNanos 更精确
                            java.util.concurrent.locks.LockSupport.parkNanos(currentPollInterval * 1000);
                        }
                    }

                } catch (InterruptedException e) {
                    log.info("TUN 读取线程被中断");
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("TUN 读取异常", e);
                    // 发生异常后短暂休眠，避免错误循环
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
     * 外部接口：接收来自 P2P 的数据 -> 写入虚拟网卡
     */
    public void writeToTun(byte[] data) {
        if (!running.get() || sessionHandle == null) {
            log.warn("服务未运行或会话无效，丢弃 {} 字节数据", data.length);
            return;
        }

        try {
            // 1. 向 Wintun 申请写缓冲区
            int size = data.length;
            Pointer packetPtr = WintunLibrary.INSTANCE.WintunAllocateSendPacket(sessionHandle, size);

            if (packetPtr == null) {
                log.warn("Wintun 写缓冲区已满，丢弃 {} 字节数据包", size);
                return;
            }

            // 2. 将 Java 数据拷贝到 Wintun 缓冲区
            packetPtr.write(0, data, 0, size);

            // 3. 提交发送
            WintunLibrary.INSTANCE.WintunSendPacket(sessionHandle, packetPtr);

            log.debug("写入 TUN: {} 字节", size);

        } catch (Exception e) {
            log.error("写入 TUN 失败", e);
        }
    }

    /**
     * 动态设置目标 PeerID
     */
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

    @PreDestroy
    public void cleanup() {
        running.set(false);
        log.info("正在关闭 VPN 服务...");

        // 关闭虚拟线程执行器
        vThreadExecutor.shutdown();
        try {
            if (!vThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("虚拟线程执行器未在超时内关闭，强制关闭");
                vThreadExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            vThreadExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // 结束 Wintun 会话
        if (sessionHandle != null) {
            WintunLibrary.INSTANCE.WintunEndSession(sessionHandle);
            sessionHandle = null;
        }

        // 可选：关闭适配器
        // 如果关闭，网卡会从系统中消失；如果不关闭，网卡保留但显示"电缆被拔出"
        // 为了用户体验，通常保留适配器，只结束会话
        // if (adapterHandle != null) {
        //     WintunLibrary.INSTANCE.WintunCloseAdapter(adapterHandle);
        //     adapterHandle = null;
        // }

        log.info("VPN 服务已停止");
    }
}