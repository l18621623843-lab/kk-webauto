package com.kk.p2p.engine;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.kk.p2p.webrtc.WebRtcDataSession;
import com.kk.p2p.nat.UpnpIgdPortMapper;
import com.kk.tunnel.vpn.service.WintunService;
import io.libp2p.core.Connection;
import io.libp2p.core.Host;
import io.libp2p.core.P2PChannel;
import io.libp2p.core.PeerId;
import io.libp2p.core.Stream;
import io.libp2p.core.crypto.KeyKt;
import io.libp2p.core.crypto.KeyType;
import io.libp2p.core.crypto.PrivKey;
import io.libp2p.core.dsl.HostBuilder;
import io.libp2p.core.multiformats.Multiaddr;
import io.libp2p.core.multistream.ProtocolBinding;
import io.libp2p.core.multistream.ProtocolDescriptor;
import io.libp2p.protocol.Identify;
import io.libp2p.protocol.Ping;
import io.libp2p.discovery.MDnsDiscovery;
import io.libp2p.protocol.circuit.CircuitHopProtocol;
import io.libp2p.protocol.circuit.CircuitStopProtocol;
import io.libp2p.protocol.circuit.RelayTransport;
import io.libp2p.transport.tcp.TcpTransport;


import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import kotlin.Pair;
import kotlin.Unit;
import lombok.extern.slf4j.Slf4j;

import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.security.SecureRandom;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;


// route add 10.8.0.3 mask 255.255.255.255 10.8.0.2
// route add 10.8.0.2 mask 255.255.255.255 10.8.0.3
@Slf4j
@Service
public class Libp2pEngine {

    private Host host;
    private PeerId selfPeerId;

    private static final String VPN_PROTOCOL_ID = "/kk-vpn/1.0.0";
    private static final String PEX_PROTOCOL_ID = "/kk/pex/1.0.0";
    private static final String CHAT_PROTOCOL_ID = "/kk-chat/1.0.0";
    private static final String WEBRTC_SIGNAL_PROTOCOL_ID = "/kk-webrtc-signal/1.0.0";

    private static final long ADDR_TTL_MS = TimeUnit.HOURS.toMillis(6);
    private static final int MAX_FRAME_SIZE = Integer.getInteger("kk.p2p.maxFrameSize", 1024 * 1024);

    // -------------------- 拨号策略（直连优先，失败再尝试 /p2p-circuit） --------------------

    /**
     * 单个 multiaddr 的拨号超时（毫秒）。
     * 值越小，“直连失败→回退 relay”越快，但弱网下可能误判。
     */
    @Value("${kk.p2p.dial.perAddrTimeoutMs:2500}")
    private long dialPerAddrTimeoutMs;

    /**
     * 一次拨号（包含多地址重试）的总超时（毫秒）。
     */
    @Value("${kk.p2p.dial.totalTimeoutMs:15000}")
    private long dialTotalTimeoutMs;

    /**
     * 是否把“直连地址”排在“/p2p-circuit 地址”之前。
     */
    @Value("${kk.p2p.dial.preferDirect:true}")
    private boolean dialPreferDirect;

    /**
     * 当拨号全部失败且已启用 relay 时，是否触发一次 relayReserveNow() 作为兜底准备。
     * 注意：它只能帮助“让别人更容易连到你”，不能凭空生成“对端的 relay 地址”。
     */
    @Value("${kk.p2p.dial.triggerRelayReserveOnFail:true}")
    private boolean dialTriggerRelayReserveOnFail;

    private static final Path IDENTITY_KEY_PATH = Path.of(
            System.getProperty("user.home"), ".kk-platform", "p2p", "identity.key"
    );

    // 默认的公网 bootstrap（注意：这些节点通常不支持你的自定义 PEX 协议，仅用于保持公网连接/辅助 NAT 识别/后续扩展）
    private static final List<String> DEFAULT_BOOTSTRAP = List.of(
            "/ip4/147.75.83.83/tcp/4001/p2p/QmNnooDu7bfjPFoTZYxMNLWUQJyrVwtbZg5gBMjTezGAJN",
            "/ip4/147.75.109.189/tcp/4001/p2p/QmSoLPppuBtQSGwKDZT2M73ULpjvfd3aZ6ha4oFGL1KrGM",
            "/ip4/147.75.83.83/tcp/4001/p2p/QmSoLueR4xBeUbY9WZ9xGUUxunbKWcrNFTDAadQJmocnWm"
    );

    @Value("${kk.p2p.bootstrap.enabled:false}")
    private boolean bootstrapEnabled;

    /**
     * 自定义 bootstrap 列表（逗号分隔）。为空时使用 DEFAULT_BOOTSTRAP。
     */
    @Value("${kk.p2p.bootstrap.addrs:}")
    private String bootstrapAddrs;

    @Value("${kk.p2p.bootstrap.connectTimeoutSeconds:8}")
    private int bootstrapConnectTimeoutSeconds;

    private final AtomicInteger bootstrapAttempted = new AtomicInteger(0);
    private final AtomicInteger bootstrapConnected = new AtomicInteger(0);
    private volatile String lastBootstrapError;

    // -------------------- Relay / 内网穿透（circuit-relay v2） --------------------

    @Value("${kk.p2p.relay.enabled:false}")
    private boolean relayEnabled;

    /**
     * Relay 模式：
     * - CLIENT：只作为客户端使用 relay（向 relay 预约并生成 /p2p-circuit 地址）
     * - HOP：作为 relay 服务端（允许其他节点预约，并转发连接）
     */
    @Value("${kk.p2p.relay.mode:CLIENT}")
    private String relayMode;

    /**
     * relay 节点地址列表（逗号/换行分隔），每个必须包含 /p2p/<relayPeerId>
     * 例如：/ip4/1.2.3.4/tcp/4001/p2p/12D3KooW...
     */
    @Value("${kk.p2p.relay.addrs:}")
    private String relayAddrs;

    @Value("${kk.p2p.relay.connectTimeoutSeconds:8}")
    private int relayConnectTimeoutSeconds;

    @Value("${kk.p2p.relay.hop.concurrent:128}")
    private int relayHopConcurrent;

    private final AtomicInteger relayAttempted = new AtomicInteger(0);
    private final AtomicInteger relayReserved = new AtomicInteger(0);
    private volatile String lastRelayError;

    private volatile RelayTransport relayTransport;
    private volatile CircuitStopProtocol.Binding circuitStopBinding;
    private volatile CircuitHopProtocol.Binding circuitHopBinding;

    private final ScheduledExecutorService relayScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "kk-p2p-relay");
        t.setDaemon(true);
        return t;
    });

    private final ConcurrentHashMap<String, RelayReservation> relayReservations = new ConcurrentHashMap<>();

    // 避免每次拨号失败都触发 relay 预约（只触发一次）
    private final AtomicBoolean relayReserveTriggered = new AtomicBoolean(false);

    private static final class RelayReservation {
        final Multiaddr relayAddr; // must include /p2p/relayId
        final CircuitHopProtocol.HopController hopController;
        volatile long expiryEpochSeconds;

        private RelayReservation(Multiaddr relayAddr, CircuitHopProtocol.HopController hopController, long expiryEpochSeconds) {
            this.relayAddr = relayAddr;
            this.hopController = hopController;
            this.expiryEpochSeconds = expiryEpochSeconds;
        }
    }

    // -------------------- 发现机制（mDNS：局域网自动发现） --------------------

    @Value("${kk.p2p.discovery.mdns.enabled:true}")
    private boolean mdnsEnabled;

    @Value("${kk.p2p.discovery.mdns.serviceTag:_ipfs-discovery._udp.local.}")
    private String mdnsServiceTag;

    @Value("${kk.p2p.discovery.mdns.queryIntervalSeconds:120}")
    private int mdnsQueryIntervalSeconds;

    private volatile MDnsDiscovery mdnsDiscovery;
    private final AtomicInteger mdnsPeersFound = new AtomicInteger(0);
    private volatile String lastMdnsPeer;
    private final ConcurrentHashMap<String, Long> mdnsSeenAtMs = new ConcurrentHashMap<>();


    // -------------------- NAT 端口映射（UPnP IGD：提高公网直连成功率） --------------------

    @Value("${kk.p2p.nat.upnp.enabled:false}")
    private boolean upnpEnabled;

    @Value("${kk.p2p.nat.upnp.leaseSeconds:3600}")
    private int upnpLeaseSeconds;

    @Value("${kk.p2p.nat.upnp.renewIntervalSeconds:900}")
    private int upnpRenewIntervalSeconds;

    private final AtomicInteger upnpAttempted = new AtomicInteger(0);
    private final AtomicInteger upnpMapped = new AtomicInteger(0);
    private volatile String upnpExternalIp;
    private volatile String upnpLastError;
    private volatile String upnpControlUrl;
    private volatile String upnpServiceType;
    private volatile Integer upnpMappedPort;

    private final UpnpIgdPortMapper upnpPortMapper = new UpnpIgdPortMapper();

    // -------------------- STUN（仅用于探测公网 UDP 映射，辅助判断网络环境） --------------------

    @Value("${kk.p2p.nat.stun.enabled:false}")
    private boolean stunEnabled;

    /**
     * STUN 服务器列表（逗号/换行分隔），形如：stun.l.google.com:19302
     */
    @Value("${kk.p2p.nat.stun.servers:stun.l.google.com:19302,stun1.l.google.com:19302}")
    private String stunServers;

    @Value("${kk.p2p.nat.stun.timeoutMs:1500}")
    private int stunTimeoutMs;

    private final AtomicInteger stunAttempted = new AtomicInteger(0);
    private final AtomicInteger stunSucceeded = new AtomicInteger(0);
    private volatile String lastStunResult;
    private volatile String lastStunError;

    private final SecureRandom stunRandom = new SecureRandom();

    // -------------------- WebRTC（DataChannel + ICE/STUN/TURN：UDP 数据面，失败回落 TCP/Relay） --------------------

    @Value("${kk.p2p.webrtc.enabled:true}")
    private boolean webrtcEnabled;

    @Value("${kk.p2p.webrtc.prefer.chat:true}")
    private boolean webrtcPreferChat;

    @Value("${kk.p2p.webrtc.prefer.vpn:true}")
    private boolean webrtcPreferVpn;

    /**
     * WebRTC 建链总超时（毫秒）。
     */
    @Value("${kk.p2p.webrtc.connectTimeoutMs:12000}")
    private long webrtcConnectTimeoutMs;

    /**
     * WebRTC 本地 UDP 端口范围（0 表示由系统分配）。
     */
    @Value("${kk.p2p.webrtc.port.min:0}")
    private int webrtcPortMin;

    @Value("${kk.p2p.webrtc.port.max:0}")
    private int webrtcPortMax;

    /**
     * STUN 服务器（逗号/换行分隔），形如：stun.l.google.com:19302
     */
    @Value("${kk.p2p.webrtc.stun.servers:stun.l.google.com:19302,stun1.l.google.com:19302}")
    private String webrtcStunServers;

    @Value("${kk.p2p.webrtc.turn.enabled:false}")
    private boolean webrtcTurnEnabled;

    /**
     * TURN 服务器（逗号/换行分隔）：
     * - 无账号：turn.example.com:3478
     * - 带账号：turn.example.com:3478|username|password
     */
    @Value("${kk.p2p.webrtc.turn.servers:}")
    private String webrtcTurnServers;

    // 缓存已建立的 VPN 流，Key 为远端 PeerID 的 String
    private final ConcurrentHashMap<String, Stream> activeStreams = new ConcurrentHashMap<>();



    // VPN 流量统计（字节数）
    private final AtomicLong vpnTxBytesTotal = new AtomicLong(0);
    private final AtomicLong vpnRxBytesTotal = new AtomicLong(0);

    // 缓存已建立的 Chat 流，Key 为远端 PeerID 的 String
    private final ConcurrentHashMap<String, Stream> chatStreams = new ConcurrentHashMap<>();

    // WebRTC 信令流（用于交换 SDP offer/answer + ICE candidates）
    private final ConcurrentHashMap<String, Stream> webrtcSignalStreams = new ConcurrentHashMap<>();

    // WebRTC 会话（DataChannel：chat/vpn）
    private final ConcurrentHashMap<String, RtcNegotiation> webrtcNegotiations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WebRtcDataSession> webrtcSessions = new ConcurrentHashMap<>();

    private static final class RtcNegotiation {
        final CompletableFuture<WebRtcDataSession> future = new CompletableFuture<>();
        volatile WebRtcDataSession session;
        volatile long startedAtMs;
        volatile boolean offerer;
    }


    // Chat 消息回调：参数为 (fromPeerId, message)
    private volatile BiConsumer<String, String> chatMessageListener = (peerId, msg) -> {
    };

    // 通过 PEX 收到的对端可拨号地址（用于 dialAndSend / 自动重连）
    private final ConcurrentHashMap<String, List<Multiaddr>> peerAddrCache = new ConcurrentHashMap<>();

    // 运行状态标志
    private final AtomicBoolean running = new AtomicBoolean(false);

    // 注入 WintunService 用于将收到的包写入网卡
    private final WintunService wintunService;

    public Libp2pEngine(@Lazy WintunService wintunService) {
        this.wintunService = wintunService;
    }

    @PostConstruct
    public void start() {
        try {
            PrivKey privKey = loadOrCreatePrivKey();
            selfPeerId = PeerId.fromPubKey(privKey.publicKey());

            int listenPort = Integer.getInteger("kk.p2p.listenPort", 0);

            String relayModeNorm = relayMode == null ? "CLIENT" : relayMode.trim().toUpperCase();
            boolean hopMode = "HOP".equals(relayModeNorm);

            circuitStopBinding = new CircuitStopProtocol.Binding(new CircuitStopProtocol());

            CircuitHopProtocol.RelayManager relayManager;
            if (hopMode) {
                relayManager = CircuitHopProtocol.RelayManager.limitTo(privKey, selfPeerId, relayHopConcurrent);
                log.info("Relay 模式: HOP（本节点将作为 relay 服务端，允许预约/转发连接）");
            } else {
                relayManager = new CircuitHopProtocol.RelayManager() {
                    @Override
                    public boolean hasReservation(PeerId source) {
                        return false;
                    }

                    @Override
                    public java.util.Optional<CircuitHopProtocol.Reservation> createReservation(PeerId requestor, Multiaddr addr) {
                        return java.util.Optional.empty();
                    }

                    @Override
                    public java.util.Optional<CircuitHopProtocol.Reservation> allowConnection(PeerId target, PeerId initiator) {
                        return java.util.Optional.empty();
                    }
                };
                log.info("Relay 模式: CLIENT（本节点仅使用 relay，不对外提供 hop）");
            }

            circuitHopBinding = new CircuitHopProtocol.Binding(relayManager, circuitStopBinding);

            host = new HostBuilder()
                    .transport(
                            TcpTransport::new,
                            upgrader -> {
                                relayTransport = new RelayTransport(
                                        circuitHopBinding,
                                        circuitStopBinding,
                                        upgrader,
                                        h -> List.of(),
                                        relayScheduler
                                );
                                // 禁用 RelayTransport 自带的自动选 relay/预约逻辑（该版本实现不完整），由本引擎手动控制预约。
                                relayTransport.setRelayCount(0);
                                return relayTransport;
                            }
                    )

                    .protocol(
                            new Ping(),
                            new Identify(),
                            circuitStopBinding,
                            circuitHopBinding,
                            createPexProtocolBinding(),
                            createWebrtcSignalProtocolBinding(),
                            createChatProtocolBinding(),
                            createVpnProtocolBinding()
                    )
                    .listen("/ip4/0.0.0.0/tcp/" + listenPort)
                    .builderModifier(builder -> builder.getIdentity().setFactory(() -> privKey))
                    .build();

            host.start().get();
            running.set(true);

            // WebRTC 数据面（DataChannel + ICE/STUN/TURN）
            if (webrtcEnabled) {
                log.info("WebRTC 数据面已启用（kk.p2p.webrtc.enabled=true）");
            } else {
                log.info("WebRTC 数据面已禁用（kk.p2p.webrtc.enabled=false）");
            }


            // 如果启用了虚拟 VPN，则在 P2P 启动后尝试拉起虚拟网卡（失败不阻断 P2P）
            try {
                wintunService.startIfEnabled();
            } catch (Exception e) {
                log.warn("VPN 未能自动启动: {}", (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }

            // UPnP 端口映射（提高公网直连成功率；失败则仍可用 relay 兜底）
            if (upnpEnabled) {
                startUpnpPortMapping();
            } else {
                log.info("UPnP 端口映射已禁用（kk.p2p.nat.upnp.enabled=false）");
            }

            if (mdnsEnabled) {
                startMdnsDiscovery();
            } else {
                log.info("mDNS 发现已禁用（kk.p2p.discovery.mdns.enabled=false）");
            }

            if (stunEnabled) {
                relayScheduler.scheduleAtFixedRate(this::stunProbeSafely, 2, 30, TimeUnit.SECONDS);
                stunProbeSafely();
            } else {
                log.info("STUN 探测已禁用（kk.p2p.nat.stun.enabled=false）");
            }

            if (relayEnabled) {

                relayScheduler.scheduleAtFixedRate(this::renewRelayReservationsSafely, 15, 15, TimeUnit.SECONDS);
                relayReserveNow();
            } else {
                log.info("relay 已禁用（kk.p2p.relay.enabled=false）");
            }

            log.info("Libp2p 节点启动成功！");
            log.info("你的 PeerID: {}", selfPeerId.toBase58());
            log.info("监听地址: {}", host.listenAddresses());
            // 注意：listen 地址可能包含 0.0.0.0 / :: 这类“通配地址”，无法用于对端拨号。
            // 这里输出替换后的“可分享地址”（用于局域网直连/Relay）。
            log.info("可分享地址(用于直连/Relay): {}", getAdvertiseAddrs());

            if (bootstrapEnabled) {
                bootstrapNow();
            } else {
                log.info("bootstrap 已禁用（kk.p2p.bootstrap.enabled=false）");
            }

        } catch (Exception e) {
            log.error("Libp2p 引擎启动失败", e);
            throw new RuntimeException("Failed to start Libp2p engine", e);
        }
    }

    /**
     * 连接 bootstrap 节点（仅用于辅助保持公网连通性/Identify 交换；自定义 PEX 协议多数公用节点不会支持）
     */
    public void bootstrapNow() {
        if (host == null || !running.get()) {
            return;
        }

        List<String> addrs = resolveBootstrapAddrs();
        if (addrs.isEmpty()) {
            log.info("bootstrap 列表为空，跳过");
            return;
        }

        for (String s : addrs) {
            try {
                Multiaddr addr = Multiaddr.fromString(s);
                String peerComponent = extractPeerIdFromMultiaddr(addr);
                if (peerComponent == null || peerComponent.isBlank()) {
                    log.warn("bootstrap 地址缺少 /p2p: {}", s);
                    continue;
                }

                PeerId peerId = PeerId.fromBase58(peerComponent);
                bootstrapAttempted.incrementAndGet();

                host.getNetwork().connect(peerId, addr)
                        .orTimeout(bootstrapConnectTimeoutSeconds, TimeUnit.SECONDS)
                        .thenAccept(conn -> {
                            bootstrapConnected.incrementAndGet();
                            log.info("已连接 bootstrap {}", s);
                            tryOpenPex(conn);
                        })
                        .exceptionally(ex -> {
                            lastBootstrapError = (ex == null ? "unknown" : (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
                            log.debug("连接 bootstrap 失败: {}", s, ex);
                            return null;
                        });
            } catch (Exception ex) {
                lastBootstrapError = (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
                log.debug("解析 bootstrap 地址失败: {}", s, ex);
            }
        }
    }

    private List<String> resolveBootstrapAddrs() {
        String s = bootstrapAddrs == null ? "" : bootstrapAddrs.trim();
        if (s.isBlank()) {
            return DEFAULT_BOOTSTRAP;
        }

        String[] parts = s.split("[\\r\\n,]+");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            String v = p == null ? "" : p.trim();
            if (!v.isBlank()) {
                out.add(v);
            }
        }
        return out.isEmpty() ? DEFAULT_BOOTSTRAP : out;
    }

    public boolean isBootstrapEnabled() {
        return bootstrapEnabled;
    }

    public int getBootstrapAttempted() {
        return bootstrapAttempted.get();
    }

    public int getBootstrapConnected() {
        return bootstrapConnected.get();
    }

    public String getLastBootstrapError() {
        return lastBootstrapError;
    }

    public boolean isRelayEnabled() {
        return relayEnabled;
    }

    public int getRelayAttempted() {
        return relayAttempted.get();
    }

    public int getRelayReserved() {
        return relayReserved.get();
    }

    public String getLastRelayError() {
        return lastRelayError;
    }

    public List<String> getShareAddrs() {
        if (host == null || selfPeerId == null) {
            return List.of();
        }
        List<Multiaddr> addrs = getAdvertiseAddrs();
        List<String> out = new ArrayList<>(addrs.size());
        for (Multiaddr a : addrs) {
            out.add(a.toString());
        }
        return out;
    }

    public List<String> getRelayShareAddrs() {
        if (host == null || selfPeerId == null) {
            return List.of();
        }
        String self = selfPeerId.toBase58();
        List<String> out = new ArrayList<>();
        for (RelayReservation r : relayReservations.values()) {
            out.add(toCircuitAddr(r.relayAddr.toString(), self));
        }
        return out;
    }

    public boolean isMdnsEnabled() {
        return mdnsEnabled;
    }

    public int getMdnsPeersFound() {
        return mdnsPeersFound.get();
    }

    public String getLastMdnsPeer() {
        return lastMdnsPeer;
    }

    public boolean isStunEnabled() {
        return stunEnabled;
    }

    public int getStunAttempted() {
        return stunAttempted.get();
    }

    public int getStunSucceeded() {
        return stunSucceeded.get();
    }

    public String getLastStunResult() {
        return lastStunResult;
    }

    public String getLastStunError() {
        return lastStunError;
    }

    // -------------------- UI / 状态展示：对端链路模式 & WebRTC 状态 --------------------

    private record RtcFailure(long atMs, String message) {
    }

    private final ConcurrentHashMap<String, RtcFailure> webrtcLastFailures = new ConcurrentHashMap<>();

    public record PeerRealtimeStatus(
            String peerId,
            String linkMode,
            String localAddr,
            String remoteAddr,
            String iceState,
            String iceDetail
    ) {
    }

    public PeerRealtimeStatus getPeerRealtimeStatus(String peerIdStr) {
        String pid = (peerIdStr == null ? "" : peerIdStr.trim());
        if (pid.isBlank()) {
            return new PeerRealtimeStatus("", "未选择", "-", "-", webrtcEnabled ? "未建立" : "禁用", "-");
        }

        // 链路：优先 Chat/VPN，其次 WebRTC 信令流
        Stream s = chatStreams.get(pid);
        if (s == null) {
            s = activeStreams.get(pid);
        }
        if (s == null) {
            s = webrtcSignalStreams.get(pid);
        }

        String local = "-";
        String remote = "-";
        String linkMode;

        if (s == null) {
            linkMode = "未连接";
        } else {
            try {
                Connection c = s.getConnection();
                if (c != null) {
                    Multiaddr la = c.localAddress();
                    Multiaddr ra = c.remoteAddress();
                    if (la != null) {
                        local = la.toString();
                    }
                    if (ra != null) {
                        remote = ra.toString();
                    }
                }
            } catch (Exception ignore) {
            }

            linkMode = classifyLinkMode(pid, remote);
        }

        // WebRTC（通过 record 字段 iceState/iceDetail 复用 UI 展示位）
        String iceState;
        String iceDetail;

        if (!webrtcEnabled) {
            iceState = "禁用";
            iceDetail = "-";
        } else {
            WebRtcDataSession sess = webrtcSessions.get(pid);
            if (sess != null && sess.isConnected()) {
                iceState = "已建立";
                String d = sess.getDetail();
                iceDetail = (d == null || d.isBlank()) ? "-" : d;
            } else {
                RtcNegotiation neg = webrtcNegotiations.get(pid);
                if (neg != null && !neg.future.isDone()) {
                    long dt = Math.max(0, System.currentTimeMillis() - neg.startedAtMs);
                    iceState = "协商中";
                    iceDetail = "已耗时 " + (dt / 1000.0) + "s";
                } else {
                    RtcFailure f = webrtcLastFailures.get(pid);
                    if (f != null && f.message != null && !f.message.isBlank()) {
                        long dt = Math.max(0, System.currentTimeMillis() - f.atMs);
                        iceState = "失败";
                        iceDetail = f.message + " (" + (dt / 1000.0) + "s前)";
                    } else {
                        iceState = "未建立";
                        iceDetail = "-";
                    }
                }
            }
        }

        return new PeerRealtimeStatus(pid, linkMode, local, remote, iceState, iceDetail);
    }

    private String classifyLinkMode(String peerId, String remoteAddrStr) {
        if (remoteAddrStr == null || remoteAddrStr.isBlank() || "-".equals(remoteAddrStr)) {
            return "未知";
        }

        String r = remoteAddrStr;
        if (r.contains("/p2p-circuit")) {
            return "中继(REL)";
        }

        String ip4 = extractIp4(r);
        boolean isPrivate = ip4 != null && isPrivateIpv4(ip4);

        if (isPrivate) {
            Long seenAt = mdnsSeenAtMs.get(peerId);
            if (seenAt != null) {
                long dt = Math.max(0, System.currentTimeMillis() - seenAt);
                // 认为 10 分钟内发现过的，属于“mDNS 内网路径”
                if (dt <= TimeUnit.MINUTES.toMillis(10)) {
                    return "内网(mDNS)";
                }
            }
            return "直连(内网)";
        }

        return "直连(公网)";
    }

    private static String extractIp4(String multiaddr) {
        if (multiaddr == null) {
            return null;
        }
        int idx = multiaddr.indexOf("/ip4/");
        if (idx < 0) {
            return null;
        }
        int start = idx + "/ip4/".length();
        int end = multiaddr.indexOf('/', start);
        if (end < 0) {
            end = multiaddr.length();
        }
        String ip = multiaddr.substring(start, end).trim();
        return ip.isBlank() ? null : ip;
    }

    private static boolean isPrivateIpv4(String ip) {
        try {
            InetAddress a = InetAddress.getByName(ip);
            if (!(a instanceof Inet4Address)) {
                return false;
            }
            return a.isSiteLocalAddress() || a.isLinkLocalAddress();
        } catch (Exception e) {
            return false;
        }
    }

    private static String summarize(Throwable ex) {
        if (ex == null) {
            return "unknown";
        }
        String msg = ex.getMessage();
        String name = ex.getClass().getSimpleName();
        if (msg == null || msg.isBlank()) {
            return name;
        }
        // 避免换行/太长
        String m = msg.replace('\n', ' ').replace('\r', ' ').trim();
        if (m.length() > 180) {
            m = m.substring(0, 180) + "...";
        }
        return name + ": " + m;
    }

    private void startMdnsDiscovery() {
        if (!mdnsEnabled || host == null || !running.get()) {
            return;
        }

        try {
            int interval = Math.max(10, mdnsQueryIntervalSeconds);
            String tag = (mdnsServiceTag == null || mdnsServiceTag.isBlank()) ? "_ipfs-discovery._udp.local." : mdnsServiceTag.trim();

            MDnsDiscovery d = new MDnsDiscovery(host, tag, interval, null);
            d.addHandler(peerInfo -> {
                onMdnsPeerFound(peerInfo);
                return Unit.INSTANCE;
            });
            mdnsDiscovery = d;

            d.start().exceptionally(ex -> {
                log.debug("mDNS 启动失败", ex);
                return null;
            });

            log.info("mDNS 发现已启动: serviceTag={}, interval={}s", tag, interval);
        } catch (Exception e) {
            log.debug("mDNS 初始化失败", e);
        }
    }

    private void stopMdnsDiscovery() {
        MDnsDiscovery d = mdnsDiscovery;
        mdnsDiscovery = null;
        if (d == null) {
            return;
        }
        try {
            d.stop().exceptionally(ex -> null);
        } catch (Exception ignore) {
        }
    }

    private void onMdnsPeerFound(io.libp2p.core.PeerInfo peerInfo) {
        if (peerInfo == null || host == null) {
            return;
        }

        String pid = null;
        try {
            pid = peerInfo.getPeerId().toBase58();
        } catch (Exception ignore) {
        }

        if (pid == null || pid.isBlank() || (selfPeerId != null && pid.equals(selfPeerId.toBase58()))) {
            return;
        }

        try {
            List<Multiaddr> addrs = peerInfo.getAddresses();
            if (addrs == null || addrs.isEmpty()) {
                return;
            }


            peerAddrCache.put(pid, addrs);
            mdnsSeenAtMs.put(pid, System.currentTimeMillis());
            try {
                host.getAddressBook().addAddrs(PeerId.fromBase58(pid), ADDR_TTL_MS, addrs.toArray(new Multiaddr[0])).exceptionally(ex -> null);
            } catch (Exception ignore) {
            }

            lastMdnsPeer = pid + " @ " + addrs.get(0);
            int n = mdnsPeersFound.incrementAndGet();
            log.info("mDNS 发现对等节点({}): {}", n, lastMdnsPeer);

        } catch (Exception e) {
            log.debug("处理 mDNS peer 失败", e);
        }
    }

    private void stunProbeSafely() {
        if (!stunEnabled || !running.get()) {
            return;
        }

        try {
            InetSocketAddress mapped = stunProbeAny();
            if (mapped != null) {
                lastStunResult = mapped.getAddress().getHostAddress() + ":" + mapped.getPort();
                lastStunError = null;
                stunSucceeded.incrementAndGet();
            }
        } catch (Exception e) {
            lastStunError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
    }

    private InetSocketAddress stunProbeAny() throws Exception {
        List<InetSocketAddress> servers = parseStunServers(stunServers);
        if (servers.isEmpty()) {
            throw new IllegalStateException("stun servers is empty");
        }

        Exception last = null;
        for (InetSocketAddress s : servers) {
            stunAttempted.incrementAndGet();
            try {
                InetSocketAddress mapped = stunBindingRequest(s, stunTimeoutMs);
                if (mapped != null) {
                    return mapped;
                }
            } catch (Exception e) {
                last = e;
            }
        }
        if (last != null) {
            throw last;
        }
        return null;
    }

    private static List<InetSocketAddress> parseStunServers(String servers) {
        String s = servers == null ? "" : servers.trim();
        if (s.isBlank()) {
            return List.of();
        }

        String[] parts = s.split("[\\r\\n,]+");
        List<InetSocketAddress> out = new ArrayList<>();
        for (String p : parts) {
            String v = p == null ? "" : p.trim();
            if (v.isBlank()) {
                continue;
            }

            String host;
            int port;
            int idx = v.lastIndexOf(':');
            if (idx > 0 && idx < v.length() - 1) {
                host = v.substring(0, idx).trim();
                port = Integer.parseInt(v.substring(idx + 1).trim());
            } else {
                host = v;
                port = 19302;
            }

            if (!host.isBlank()) {
                out.add(new InetSocketAddress(host, port));
            }
        }
        return out;
    }

    private InetSocketAddress stunBindingRequest(InetSocketAddress server, int timeoutMs) throws Exception {
        // RFC5389: Binding Request over UDP, parse XOR-MAPPED-ADDRESS
        byte[] txId = new byte[12];
        stunRandom.nextBytes(txId);

        byte[] req = new byte[20];
        // type: 0x0001
        req[0] = 0x00;
        req[1] = 0x01;
        // length: 0
        req[2] = 0x00;
        req[3] = 0x00;
        // magic cookie: 0x2112A442
        req[4] = 0x21;
        req[5] = 0x12;
        req[6] = (byte) 0xA4;
        req[7] = 0x42;
        // transaction id
        System.arraycopy(txId, 0, req, 8, 12);

        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setSoTimeout(Math.max(200, timeoutMs));
            DatagramPacket p = new DatagramPacket(req, req.length, server);
            sock.send(p);

            byte[] buf = new byte[512];
            DatagramPacket resp = new DatagramPacket(buf, buf.length);
            sock.receive(resp);

            return parseStunResponse(resp.getData(), resp.getLength(), txId);
        } catch (SocketTimeoutException e) {
            throw new SocketTimeoutException("STUN timeout: " + server);
        }
    }

    private static InetSocketAddress parseStunResponse(byte[] data, int len, byte[] txId) {
        if (data == null || len < 20) {
            return null;
        }

        int msgType = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        if (msgType != 0x0101) { // Binding Success Response
            return null;
        }

        int msgLen = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        if (20 + msgLen > len) {
            return null;
        }

        // magic cookie
        int cookie = ((data[4] & 0xFF) << 24) | ((data[5] & 0xFF) << 16) | ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
        if (cookie != 0x2112A442) {
            return null;
        }

        // transaction id verify
        for (int i = 0; i < 12; i++) {
            if (data[8 + i] != txId[i]) {
                return null;
            }
        }

        int pos = 20;
        while (pos + 4 <= 20 + msgLen) {
            int type = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
            int alen = ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
            pos += 4;
            if (pos + alen > 20 + msgLen) {
                return null;
            }

            if (type == 0x0020 && alen >= 8) { // XOR-MAPPED-ADDRESS
                int family = data[pos + 1] & 0xFF;
                int xPort = ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
                int port = xPort ^ (0x2112);

                if (family == 0x01 && alen >= 8) { // IPv4
                    byte[] ip = new byte[4];
                    ip[0] = (byte) ((data[pos + 4] & 0xFF) ^ 0x21);
                    ip[1] = (byte) ((data[pos + 5] & 0xFF) ^ 0x12);
                    ip[2] = (byte) ((data[pos + 6] & 0xFF) ^ 0xA4);
                    ip[3] = (byte) ((data[pos + 7] & 0xFF) ^ 0x42);
                    try {
                        return new InetSocketAddress(InetAddress.getByAddress(ip), port);
                    } catch (Exception ignore) {
                        return null;
                    }
                }

                if (family == 0x02 && alen >= 20) { // IPv6
                    byte[] ip = new byte[16];
                    // cookie (4 bytes) + txId (12 bytes) = 16 bytes xor pad
                    byte[] pad = new byte[16];
                    pad[0] = 0x21;
                    pad[1] = 0x12;
                    pad[2] = (byte) 0xA4;
                    pad[3] = 0x42;
                    System.arraycopy(txId, 0, pad, 4, 12);
                    for (int i = 0; i < 16; i++) {
                        ip[i] = (byte) ((data[pos + 4 + i] & 0xFF) ^ (pad[i] & 0xFF));
                    }
                    try {
                        return new InetSocketAddress(InetAddress.getByAddress(ip), port);
                    } catch (Exception ignore) {
                        return null;
                    }
                }
            }

            // 4-byte padding
            pos += alen;
            int pad = (4 - (alen % 4)) % 4;
            pos += pad;
        }

        return null;
    }

    private void startUpnpPortMapping() {
        if (!upnpEnabled || host == null || !running.get()) {
            return;
        }

        Integer port = detectTcpListenPort();
        if (port == null || port <= 0) {
            upnpLastError = "no tcp listen port";
            return;
        }

        String internalIp = pickLanIpv4();
        if (internalIp == null) {
            upnpLastError = "no lan ipv4";
            return;
        }

        upnpMappedPort = port;

        // 异步执行并定时续约（不同路由器对 leaseSeconds 行为不同，续约是低成本兜底）
        relayScheduler.execute(() -> upnpMapOnce(internalIp, port));

        int renew = Math.max(60, upnpRenewIntervalSeconds);
        relayScheduler.scheduleAtFixedRate(() -> upnpMapOnce(internalIp, port), renew, renew, TimeUnit.SECONDS);
    }

    private void upnpMapOnce(String internalIp, int port) {
        if (!upnpEnabled || !running.get()) {
            return;
        }
        upnpAttempted.incrementAndGet();

        try {
            String desc = "kk-p2p " + (selfPeerId == null ? "" : selfPeerId.toBase58());
            UpnpIgdPortMapper.MapResult r = upnpPortMapper.mapTcp(internalIp, port, desc, Math.max(0, upnpLeaseSeconds));
            if (r.success) {
                upnpExternalIp = r.externalIp;
                upnpControlUrl = r.igdControlUrl;
                upnpServiceType = r.serviceType;
                upnpLastError = null;
                upnpMapped.set(1);
                log.info("UPnP 端口映射成功: {}:{} -> {} (extIP={})", internalIp, port, port, (r.externalIp == null ? "-" : r.externalIp));
            } else {
                upnpLastError = r.message;
                log.info("UPnP 端口映射失败: {}", r.message);
            }
        } catch (Exception e) {
            upnpLastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.info("UPnP 端口映射异常: {}", upnpLastError);
        }
    }

    private Integer detectTcpListenPort() {
        if (host == null) {
            return null;
        }
        for (Multiaddr a : host.listenAddresses()) {
            if (a == null) {
                continue;
            }
            Integer p = extractTcpPort(a.toString());
            if (p != null && p > 0) {
                return p;
            }
        }
        return null;
    }

    private static String pickLanIpv4() {
        // 选择一个可用于内网映射的 IPv4（非 loopback/link-local）
        List<String> ips = getLocalIpv4Candidates();
        for (String ip : ips) {
            if (ip == null) {
                continue;
            }
            String v = ip.trim();
            if (v.isBlank()) {
                continue;
            }
            if (v.startsWith("127.")) {
                continue;
            }
            return v;
        }
        return null;
    }

    public boolean isUpnpEnabled() {
        return upnpEnabled;
    }

    public int getUpnpAttempted() {
        return upnpAttempted.get();
    }

    public int getUpnpMapped() {
        return upnpMapped.get();
    }

    public String getUpnpExternalIp() {
        return upnpExternalIp;
    }

    public String getUpnpLastError() {
        return upnpLastError;
    }

    public Integer getUpnpMappedPort() {
        return upnpMappedPort;
    }

    public void relayReserveNow() {

        if (!relayEnabled || host == null || !running.get()) {
            return;
        }

        List<String> addrs = resolveRelayAddrs();
        if (addrs.isEmpty()) {
            log.info("relay 列表为空，跳过预约");
            return;
        }

        for (String s : addrs) {
            reserveRelay(s).exceptionally(ex -> null);
        }
    }

    public CompletableFuture<Void> reserveRelay(String relayMultiaddr) {
        if (!running.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("engine is not running"));
        }
        if (relayMultiaddr == null || relayMultiaddr.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("relay multiaddr is blank"));
        }

        final Multiaddr addr;
        try {
            addr = Multiaddr.fromString(relayMultiaddr.trim());
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("invalid relay multiaddr", e));
        }

        String relayPeerIdStr = extractPeerIdFromMultiaddr(addr);
        if (relayPeerIdStr == null || relayPeerIdStr.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("relay multiaddr missing /p2p/<peerId>"));
        }

        final PeerId relayPeerId;
        try {
            relayPeerId = PeerId.fromBase58(relayPeerIdStr);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("invalid relay peerId", e));
        }

        relayAttempted.incrementAndGet();

        return host.getNetwork().connect(relayPeerId, addr)
                .orTimeout(relayConnectTimeoutSeconds, TimeUnit.SECONDS)
                .thenCompose(conn -> conn.muxerSession().createStream(circuitHopBinding).getController())
                .thenCompose(ctrl -> ctrl.reserve().thenAccept(res -> {
                    long expiryEpoch = 0L;
                    try {
                        expiryEpoch = res.expiry.toEpochSecond(java.time.ZoneOffset.UTC);
                    } catch (Exception ignore) {
                    }
                    relayReservations.put(relayPeerId.toBase58(), new RelayReservation(addr, ctrl, expiryEpoch));
                    relayReserved.set(relayReservations.size());
                }))
                .exceptionally(ex -> {
                    lastRelayError = (ex == null ? "unknown" : (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
                    log.debug("relay 预约失败: {}", relayMultiaddr, ex);
                    throw new RuntimeException(ex);
                });
    }

    private List<String> resolveRelayAddrs() {
        String s = relayAddrs == null ? "" : relayAddrs.trim();
        if (s.isBlank()) {
            return List.of();
        }

        String[] parts = s.split("[\\r\\n,]+");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            String v = p == null ? "" : p.trim();
            if (!v.isBlank()) {
                out.add(v);
            }
        }
        return out;
    }

    private void renewRelayReservationsSafely() {
        if (!relayEnabled || host == null || !running.get() || relayReservations.isEmpty()) {
            return;
        }

        long nowEpoch = System.currentTimeMillis() / 1000;
        for (var e : relayReservations.entrySet()) {
            String relayPeerId = e.getKey();
            RelayReservation r = e.getValue();
            if (r == null) {
                continue;
            }

            long expiry = r.expiryEpochSeconds;
            if (expiry <= 0L || (expiry - nowEpoch) > 120) {
                continue;
            }

            try {
                r.hopController.reserve()
                        .orTimeout(relayConnectTimeoutSeconds, TimeUnit.SECONDS)
                        .thenAccept(res -> {
                            try {
                                r.expiryEpochSeconds = res.expiry.toEpochSecond(java.time.ZoneOffset.UTC);
                            } catch (Exception ignore) {
                            }
                        })
                        .exceptionally(ex -> {
                            lastRelayError = (ex == null ? "unknown" : (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
                            relayReservations.remove(relayPeerId);
                            relayReserved.set(relayReservations.size());
                            return null;
                        });
            } catch (Exception ex) {
                lastRelayError = (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
                relayReservations.remove(relayPeerId);
                relayReserved.set(relayReservations.size());
            }
        }
    }

    private static String toCircuitAddr(String relayAddrWithP2p, String targetPeerId) {
        if (relayAddrWithP2p == null) {
            return null;
        }
        String base = relayAddrWithP2p;
        int idx = base.indexOf("/p2p-circuit");
        if (idx >= 0) {
            base = base.substring(0, idx);
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/p2p-circuit/p2p/" + targetPeerId;
    }

    private void tryOpenPex(Connection conn) {

        try {
            conn.muxerSession()
                    .createStream(createPexProtocolBinding())
                    .getController()
                    .exceptionally(ex -> null);
        } catch (Exception ignore) {
        }
    }

    private PrivKey loadOrCreatePrivKey() {
        try {
            if (Files.exists(IDENTITY_KEY_PATH)) {
                byte[] bytes = Files.readAllBytes(IDENTITY_KEY_PATH);
                if (bytes.length > 0) {
                    return KeyKt.unmarshalPrivateKey(bytes);
                }
            }

            Pair<PrivKey, ?> keyPair = KeyKt.generateKeyPair(KeyType.ED25519);
            PrivKey privKey = keyPair.getFirst();

            Files.createDirectories(IDENTITY_KEY_PATH.getParent());
            Path tmp = IDENTITY_KEY_PATH.resolveSibling(IDENTITY_KEY_PATH.getFileName() + ".tmp");
            Files.write(tmp, KeyKt.marshalPrivateKey(privKey));
            Files.move(tmp, IDENTITY_KEY_PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return privKey;
        } catch (Exception e) {
            log.warn("加载/保存节点私钥失败，将使用临时身份（PeerId 将变化）", e);
            Pair<PrivKey, ?> keyPair = KeyKt.generateKeyPair(KeyType.ED25519);
            return keyPair.getFirst();
        }
    }

    private ProtocolBinding<VpnController> createVpnProtocolBinding() {
        return new ProtocolBinding<VpnController>() {
            @NotNull
            @Override
            public ProtocolDescriptor getProtocolDescriptor() {
                return new ProtocolDescriptor(VPN_PROTOCOL_ID);
            }

            @NotNull
            @Override
            public CompletableFuture<VpnController> initChannel(@NotNull P2PChannel ch, @NotNull String selectedProtocol) {
                Stream stream = (Stream) ch;
                String remotePeerId = stream.remotePeerId().toBase58();
                log.info("收到来自 {} 的新隧道流", remotePeerId);
                handleIncomingVpnStream(stream);
                return CompletableFuture.completedFuture(new VpnController(stream));
            }
        };
    }

    private ProtocolBinding<Void> createPexProtocolBinding() {
        return new ProtocolBinding<Void>() {
            @NotNull
            @Override
            public ProtocolDescriptor getProtocolDescriptor() {
                return new ProtocolDescriptor(PEX_PROTOCOL_ID);
            }

            @NotNull
            @Override
            public CompletableFuture<Void> initChannel(@NotNull P2PChannel ch, @NotNull String selectedProtocol) {
                Stream stream = (Stream) ch;
                String remotePeerId = stream.remotePeerId().toBase58();

                // 先发自己的 advert（对端如果实现了 PEX，就能立刻缓存你的可拨号地址）
                sendPexAdvert(stream);

                // length-prefix 方式解包 JSON（避免依赖 LengthFieldBasedFrameDecoder 模块）
                stream.pushHandler(new FramedInboundHandler(MAX_FRAME_SIZE, frame -> {
                    String json = frame.toString(StandardCharsets.UTF_8);
                    onPexAdvert(remotePeerId, json);
                }, (ctx, cause) -> {
                    log.debug("PEX 流异常: {}", remotePeerId, cause);
                    ctx.close();
                }));

                return CompletableFuture.completedFuture(null);
            }
        };
    }






    // -------------------- WebRTC 信令（通过 libp2p 交换 SDP offer/answer + ICE candidates）--------------------

    private ProtocolBinding<WebrtcSignalController> createWebrtcSignalProtocolBinding() {
        return new ProtocolBinding<WebrtcSignalController>() {
            @NotNull
            @Override
            public ProtocolDescriptor getProtocolDescriptor() {
                return new ProtocolDescriptor(WEBRTC_SIGNAL_PROTOCOL_ID);
            }

            @NotNull
            @Override
            public CompletableFuture<WebrtcSignalController> initChannel(@NotNull P2PChannel ch, @NotNull String selectedProtocol) {
                Stream stream = (Stream) ch;
                handleIncomingWebrtcSignalStream(stream);
                return CompletableFuture.completedFuture(new WebrtcSignalController(stream));
            }
        };
    }

    public static final class WebrtcSignalController {
        public final Stream stream;

        public WebrtcSignalController(Stream stream) {
            this.stream = stream;
        }
    }

    private void handleIncomingWebrtcSignalStream(Stream stream) {
        String remotePeerId = stream.remotePeerId().toBase58();

        webrtcSignalStreams.put(remotePeerId, stream);
        stream.closeFuture().thenRun(() -> webrtcSignalStreams.remove(remotePeerId));

        stream.pushHandler(new FramedInboundHandler(MAX_FRAME_SIZE, frame -> {
            String json = frame.toString(StandardCharsets.UTF_8);
            onWebrtcSignal(remotePeerId, json, stream);
        }, (ctx, cause) -> {
            log.debug("WebRTC 信令流异常: {} {}", remotePeerId, (cause == null ? "unknown" : cause.toString()));
            ctx.close();
        }));
    }

    private void onWebrtcSignal(String remotePeerId, String json, Stream signalStream) {
        if (!webrtcEnabled || remotePeerId == null || remotePeerId.isBlank() || json == null || json.isBlank()) {
            return;
        }

        try {
            JSONObject obj = JSONUtil.parseObj(json);
            String t = obj.getStr("t");
            if (t == null) {
                return;
            }

            String type = t.trim().toLowerCase();
            switch (type) {
                case "offer" -> handleWebrtcOffer(remotePeerId, obj, signalStream);
                case "answer" -> handleWebrtcAnswer(remotePeerId, obj);
                case "cand" -> handleWebrtcCandidate(remotePeerId, obj);
                default -> {
                }
            }
        } catch (Exception e) {
            log.debug("WebRTC 信令解析失败: {} {}", remotePeerId, e.toString());
        }
    }

    private void handleWebrtcOffer(String remotePeerId, JSONObject offer, Stream signalStream) {
        if (!webrtcEnabled || !running.get()) {
            return;
        }

        String sdp = offer.getStr("sdp");
        if (sdp == null || sdp.isBlank()) {
            return;
        }

        // 若已建立则忽略
        WebRtcDataSession existing = webrtcSessions.get(remotePeerId);
        if (existing != null && existing.isConnected()) {
            return;
        }

        RtcNegotiation neg = webrtcNegotiations.computeIfAbsent(remotePeerId, k -> {
            RtcNegotiation n = new RtcNegotiation();
            n.startedAtMs = System.currentTimeMillis();
            n.offerer = false;
            return n;
        });

        try {
            WebRtcDataSession sess = WebRtcDataSession.create(remotePeerId, buildWebrtcConfig(), false, newWebrtcCallbacks(remotePeerId, signalStream));
            neg.session = sess;

            sess.setRemoteDescription(new dev.onvoid.webrtc.RTCSessionDescription(dev.onvoid.webrtc.RTCSdpType.OFFER, sdp.trim()))
                    .thenCompose(v -> sess.createAnswer())
                    .thenAccept(ans -> {
                        try {
                            sendWebrtcAnswer(signalStream, ans);
                        } catch (Exception ignore) {
                        }
                        awaitWebrtcConnected(remotePeerId, neg, sess);
                    })
                    .exceptionally(ex -> {
                        onWebrtcFailed(remotePeerId, neg, sess, ex);
                        return null;
                    });

        } catch (Exception e) {
            onWebrtcFailed(remotePeerId, neg, null, e);
        }
    }

    private void handleWebrtcAnswer(String remotePeerId, JSONObject answer) {
        if (!webrtcEnabled || !running.get()) {
            return;
        }

        String sdp = answer.getStr("sdp");
        if (sdp == null || sdp.isBlank()) {
            return;
        }

        RtcNegotiation neg = webrtcNegotiations.get(remotePeerId);
        if (neg == null || neg.future.isDone()) {
            return;
        }

        WebRtcDataSession sess = neg.session;
        if (sess == null) {
            return;
        }

        sess.setRemoteDescription(new dev.onvoid.webrtc.RTCSessionDescription(dev.onvoid.webrtc.RTCSdpType.ANSWER, sdp.trim()))
                .thenAccept(v -> awaitWebrtcConnected(remotePeerId, neg, sess))
                .exceptionally(ex -> {
                    onWebrtcFailed(remotePeerId, neg, sess, ex);
                    return null;
                });
    }

    private void handleWebrtcCandidate(String remotePeerId, JSONObject candObj) {
        if (!webrtcEnabled || !running.get()) {
            return;
        }

        String mid = candObj.getStr("mid");
        Integer mline = candObj.getInt("mline");
        String cand = candObj.getStr("cand");

        if (cand == null || cand.isBlank()) {
            return;
        }

        WebRtcDataSession sess = webrtcSessions.get(remotePeerId);
        if (sess == null) {
            RtcNegotiation neg = webrtcNegotiations.get(remotePeerId);
            sess = (neg == null ? null : neg.session);
        }
        if (sess == null) {
            return;
        }

        try {
            sess.addRemoteIceCandidate(new dev.onvoid.webrtc.RTCIceCandidate(mid, mline == null ? 0 : mline, cand));
        } catch (Exception ignore) {
        }
    }

    private void awaitWebrtcConnected(String peerId, RtcNegotiation neg, WebRtcDataSession sess) {
        if (peerId == null || peerId.isBlank() || neg == null || sess == null) {
            return;
        }

        long totalTimeout = Math.max(3000, webrtcConnectTimeoutMs);
        long dt = Math.max(0, System.currentTimeMillis() - neg.startedAtMs);
        long left = Math.max(1000, totalTimeout - dt);

        sess.whenConnected()
                .orTimeout(left, TimeUnit.MILLISECONDS)
                .thenRun(() -> onWebrtcEstablished(peerId, neg, sess))
                .exceptionally(ex -> {
                    onWebrtcFailed(peerId, neg, sess, ex);
                    return null;
                });
    }

    private void onWebrtcEstablished(String peerId, RtcNegotiation neg, WebRtcDataSession session) {
        webrtcLastFailures.remove(peerId);
        webrtcSessions.put(peerId, session);
        if (!neg.future.isDone()) {
            neg.future.complete(session);
        }
        webrtcNegotiations.remove(peerId, neg);
    }

    private void onWebrtcFailed(String peerId, RtcNegotiation neg, WebRtcDataSession session, Throwable ex) {
        webrtcLastFailures.put(peerId, new RtcFailure(System.currentTimeMillis(), summarize(ex)));
        try {
            if (session != null) {
                session.close();
            }
        } catch (Exception ignore) {
        }
        if (!neg.future.isDone()) {
            neg.future.completeExceptionally(ex == null ? new IllegalStateException("webrtc failed") : ex);
        }
        webrtcNegotiations.remove(peerId);
    }

    private void sendWebrtcOffer(Stream stream, dev.onvoid.webrtc.RTCSessionDescription offer) {
        JSONObject obj = new JSONObject();
        obj.set("t", "offer");
        obj.set("sdp", offer == null ? "" : offer.sdp);
        stream.writeAndFlush(frame(obj.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private void sendWebrtcAnswer(Stream stream, dev.onvoid.webrtc.RTCSessionDescription answer) {
        JSONObject obj = new JSONObject();
        obj.set("t", "answer");
        obj.set("sdp", answer == null ? "" : answer.sdp);
        stream.writeAndFlush(frame(obj.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private void sendWebrtcCandidate(Stream stream, dev.onvoid.webrtc.RTCIceCandidate cand) {
        if (cand == null) {
            return;
        }
        JSONObject obj = new JSONObject();
        obj.set("t", "cand");
        obj.set("mid", cand.sdpMid);
        obj.set("mline", cand.sdpMLineIndex);
        obj.set("cand", cand.sdp);
        stream.writeAndFlush(frame(obj.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private WebRtcDataSession.Callbacks newWebrtcCallbacks(String remotePeerId, Stream signalStream) {
        return new WebRtcDataSession.Callbacks() {
            @Override
            public void onLocalIceCandidate(dev.onvoid.webrtc.RTCIceCandidate cand) {
                try {
                    sendWebrtcCandidate(signalStream, cand);
                } catch (Exception ignore) {
                }
            }

            @Override
            public void onChatMessage(String fromPeerId, String message) {
                BiConsumer<String, String> listener = chatMessageListener;
                if (listener != null) {
                    listener.accept(fromPeerId, message == null ? "" : message);
                }
            }

            @Override
            public void onVpnPacket(String fromPeerId, byte[] packet) {
                if (packet != null && packet.length > 0) {
                    vpnRxBytesTotal.addAndGet(packet.length);
                    try {
                        wintunService.writeToTun(packet);
                    } catch (Exception e) {
                        log.debug("WebRTC 写入 TUN 失败: {}", (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                    }
                }
            }

            @Override
            public void onStateChanged(String fromPeerId, String state, String detail) {
            }
        };
    }

    private CompletableFuture<WebRtcDataSession> ensureWebrtcSession(String peerIdStr) {
        if (!webrtcEnabled) {
            return CompletableFuture.failedFuture(new IllegalStateException("webrtc disabled"));
        }
        if (!running.get() || host == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("engine is not running"));
        }

        WebRtcDataSession existing = webrtcSessions.get(peerIdStr);
        if (existing != null && existing.isConnected()) {
            return CompletableFuture.completedFuture(existing);
        }

        RtcNegotiation ongoing = webrtcNegotiations.get(peerIdStr);
        if (ongoing != null && !ongoing.future.isDone()) {
            return ongoing.future;
        }

        webrtcLastFailures.remove(peerIdStr);

        RtcNegotiation neg = new RtcNegotiation();
        neg.startedAtMs = System.currentTimeMillis();
        neg.offerer = isWebrtcOffererFor(peerIdStr);

        RtcNegotiation prev = webrtcNegotiations.put(peerIdStr, neg);
        if (prev != null && !prev.future.isDone()) {
            webrtcNegotiations.put(peerIdStr, prev);
            return prev.future;
        }

        startWebrtcAsInitiator(peerIdStr, neg);

        long totalTimeout = Math.max(3000, webrtcConnectTimeoutMs);
        relayScheduler.schedule(() -> {
            if (!neg.future.isDone()) {
                onWebrtcFailed(peerIdStr, neg, neg.session, new IllegalStateException("webrtc negotiation timeout"));
            }
        }, totalTimeout, TimeUnit.MILLISECONDS);

        return neg.future;
    }

    private void triggerWebrtcNegotiationAsync(String peerIdStr) {
        if (!webrtcEnabled || !running.get()) {
            return;
        }
        ensureWebrtcSession(peerIdStr).exceptionally(ex -> null);
    }

    private void startWebrtcAsInitiator(String peerIdStr, RtcNegotiation neg) {
        openWebrtcSignalStream(peerIdStr)
                .thenAccept(stream -> {
                    try {
                        WebRtcDataSession sess = WebRtcDataSession.create(peerIdStr, buildWebrtcConfig(), true, newWebrtcCallbacks(peerIdStr, stream));
                        neg.session = sess;

                        sess.createOffer()
                                .thenAccept(offer -> {
                                    try {
                                        sendWebrtcOffer(stream, offer);
                                    } catch (Exception e) {
                                        onWebrtcFailed(peerIdStr, neg, sess, e);
                                    }
                                })
                                .exceptionally(ex -> {
                                    onWebrtcFailed(peerIdStr, neg, sess, ex);
                                    return null;
                                });

                    } catch (Exception e) {
                        onWebrtcFailed(peerIdStr, neg, null, e);
                    }
                })
                .exceptionally(ex -> {
                    onWebrtcFailed(peerIdStr, neg, null, ex);
                    return null;
                });
    }

    private CompletableFuture<Stream> openWebrtcSignalStream(String peerIdStr) {
        Stream existing = webrtcSignalStreams.get(peerIdStr);
        if (existing != null) {
            return CompletableFuture.completedFuture(existing);
        }

        final PeerId peerId;
        try {
            peerId = PeerId.fromBase58(peerIdStr);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("invalid peerId", e));
        }

        return resolvePeerAddrs(peerIdStr, peerId)
                .thenCompose(addrs -> {
                    if (addrs.isEmpty()) {
                        return CompletableFuture.failedFuture(new IllegalStateException("no known multiaddrs for peer"));
                    }
                    return connectFirst(peerId, addrs, 0);
                })
                .thenCompose(conn -> {
                    tryOpenPex(conn);
                    return conn.muxerSession()
                            .createStream(createWebrtcSignalProtocolBinding())
                            .getController();
                })
                .thenApply(controller -> {
                    Stream s = controller.stream;
                    webrtcSignalStreams.put(peerIdStr, s);
                    s.closeFuture().thenRun(() -> webrtcSignalStreams.remove(peerIdStr));
                    return s;
                });
    }

    private boolean isWebrtcOffererFor(String remotePeerId) {
        String self = getSelfPeerId();
        if (self == null || self.isBlank() || remotePeerId == null || remotePeerId.isBlank()) {
            return true;
        }
        // 仍用确定性规则避免 glare：两端对比 PeerID 字符串，较大的作为 offerer
        return self.compareTo(remotePeerId) > 0;
    }

    private WebRtcDataSession.Config buildWebrtcConfig() {
        WebRtcDataSession.Config cfg = new WebRtcDataSession.Config();
        cfg.minPort = Math.max(0, webrtcPortMin);
        cfg.maxPort = Math.max(0, webrtcPortMax);

        cfg.stunServers = new ArrayList<>();
        for (InetSocketAddress s : parseStunServers(webrtcStunServers)) {
            if (s == null) {
                continue;
            }
            String host = s.getHostString();
            int port = s.getPort();
            if (host != null && !host.isBlank() && port > 0) {
                cfg.stunServers.add(host + ":" + port);
            }
        }

        cfg.enableTurn = webrtcTurnEnabled;
        cfg.turnServers = parseWebrtcTurnServers(webrtcTurnServers);
        return cfg;
    }

    private static List<WebRtcDataSession.Config.TurnServer> parseWebrtcTurnServers(String servers) {
        String s = servers == null ? "" : servers.trim();
        if (s.isBlank()) {
            return List.of();
        }

        String[] parts = s.split("[\\r\\n,]+");
        List<WebRtcDataSession.Config.TurnServer> out = new ArrayList<>();

        for (String p : parts) {
            String v = p == null ? "" : p.trim();
            if (v.isBlank()) {
                continue;
            }

            // 格式：host:port|user|pass
            String hostPort = v;
            String user = null;
            String pass = null;

            String[] up = v.split("\\|");
            if (up.length >= 1) {
                hostPort = up[0].trim();
            }
            if (up.length >= 2) {
                user = up[1].trim();
            }
            if (up.length >= 3) {
                pass = up[2].trim();
            }

            String host;
            int port;
            int idx = hostPort.lastIndexOf(':');
            if (idx > 0 && idx < hostPort.length() - 1) {
                host = hostPort.substring(0, idx).trim();
                try {
                    port = Integer.parseInt(hostPort.substring(idx + 1).trim());
                } catch (Exception e) {
                    port = 3478;
                }
            } else {
                host = hostPort.trim();
                port = 3478;
            }

            if (!host.isBlank()) {
                out.add(new WebRtcDataSession.Config.TurnServer(host, port, user, pass));
            }
        }

        return out;
    }


    private ProtocolBinding<ChatController> createChatProtocolBinding() {
        return new ProtocolBinding<ChatController>() {
            @NotNull
            @Override
            public ProtocolDescriptor getProtocolDescriptor() {
                return new ProtocolDescriptor(CHAT_PROTOCOL_ID);
            }

            @NotNull
            @Override
            public CompletableFuture<ChatController> initChannel(@NotNull P2PChannel ch, @NotNull String selectedProtocol) {
                Stream stream = (Stream) ch;
                String remotePeerId = stream.remotePeerId().toBase58();
                log.info("收到来自 {} 的新聊天流", remotePeerId);
                handleIncomingChatStream(stream);
                return CompletableFuture.completedFuture(new ChatController(stream));
            }
        };
    }

    private void handleIncomingChatStream(Stream stream) {
        String remotePeerId = stream.remotePeerId().toBase58();

        chatStreams.put(remotePeerId, stream);

        stream.closeFuture().thenRun(() -> {
            log.info("与 {} 的 Chat 流已关闭", remotePeerId);
            chatStreams.remove(remotePeerId);
        });

        stream.pushHandler(new FramedInboundHandler(MAX_FRAME_SIZE, frame -> {
            int readableBytes = frame.readableBytes();
            if (readableBytes <= 0) {
                return;
            }

            vpnRxBytesTotal.addAndGet(readableBytes);

            byte[] data = new byte[readableBytes];
            frame.readBytes(data);

            String msg = new String(data, StandardCharsets.UTF_8);

            BiConsumer<String, String> listener = chatMessageListener;
            if (listener != null) {
                listener.accept(remotePeerId, msg);
            }
        }, (ctx, cause) -> {
            log.error("Chat 流异常: {}", remotePeerId, cause);
            ctx.close();
        }));
    }

    public void setChatMessageListener(BiConsumer<String, String> listener) {
        this.chatMessageListener = listener;
    }

    public CompletableFuture<Void> connectChatToPeer(String peerIdStr, String multiaddr) {
        if (!running.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("engine is not running"));
        }
        if (peerIdStr == null || peerIdStr.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("peerId is blank"));
        }

        final PeerId peerId;
        try {
            peerId = PeerId.fromBase58(peerIdStr);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("invalid peerId", e));
        }

        if (multiaddr != null && !multiaddr.isBlank()) {
            try {
                Multiaddr addr = Multiaddr.fromString(multiaddr);
                validateDialableAddr(addr);

                host.getAddressBook().addAddrs(peerId, ADDR_TTL_MS, addr).exceptionally(ex -> null);
                peerAddrCache.put(peerIdStr, List.of(addr));
            } catch (IllegalArgumentException e) {
                return CompletableFuture.failedFuture(e);
            } catch (Exception e) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("invalid multiaddr", e));
            }
        }

        return resolvePeerAddrs(peerIdStr, peerId)
                .thenCompose(addrs -> {
                    if (addrs.isEmpty()) {
                        return CompletableFuture.failedFuture(new IllegalStateException("no known multiaddrs for peer"));
                    }
                    return connectFirst(peerId, addrs, 0);
                })
                .thenCompose(conn -> {
                    tryOpenPex(conn);
                    return conn.muxerSession()
                            .createStream(createChatProtocolBinding())
                            .getController();
                })
                .thenAccept(controller -> {
                    chatStreams.put(peerIdStr, controller.stream);
                    controller.stream.closeFuture().thenRun(() -> chatStreams.remove(peerIdStr));
                })
                .orTimeout(Math.max(1000, dialTotalTimeoutMs), TimeUnit.MILLISECONDS)
                .exceptionallyCompose(ex -> {
                    maybeTriggerRelayReserveFallback();
                    return CompletableFuture.failedFuture(ex);
                });
    }

    public CompletableFuture<Void> sendChatMessage(String targetPeerIdStr, String message) {
        if (!running.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("engine is not running"));
        }
        if (targetPeerIdStr == null || targetPeerIdStr.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("targetPeerId is blank"));
        }

        String msg = (message == null ? "" : message);

        // 发送优先：WebRTC DataChannel（优先走 UDP），失败再回落到 libp2p(TCP/Relay)
        if (webrtcEnabled && webrtcPreferChat) {
            return sendChatMessageViaWebrtc(targetPeerIdStr, msg)
                    .exceptionallyCompose(ex -> sendChatMessageOverLibp2p(targetPeerIdStr, msg));
        }

        return sendChatMessageOverLibp2p(targetPeerIdStr, msg);
    }

    private CompletableFuture<Void> sendChatMessageViaWebrtc(String targetPeerIdStr, String msg) {
        return ensureWebrtcSession(targetPeerIdStr)
                .thenAccept(sess -> sess.sendChat(msg));
    }

    private CompletableFuture<Void> sendChatMessageOverLibp2p(String targetPeerIdStr, String message) {
        byte[] payload = (message == null ? "" : message).getBytes(StandardCharsets.UTF_8);

        Stream stream = chatStreams.get(targetPeerIdStr);
        if (stream != null) {
            try {
                stream.writeAndFlush(frame(payload));
                return CompletableFuture.completedFuture(null);
            } catch (Exception e) {
                chatStreams.remove(targetPeerIdStr);
            }
        }

        return dialChatAndSend(targetPeerIdStr, payload);
    }

    private CompletableFuture<Void> dialChatAndSend(String targetPeerIdStr, byte[] payload) {
        final PeerId targetPeerId;
        try {
            targetPeerId = PeerId.fromBase58(targetPeerIdStr);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("invalid peerId", e));
        }

        return resolvePeerAddrs(targetPeerIdStr, targetPeerId)
                .thenCompose(addrs -> {
                    if (addrs.isEmpty()) {
                        return CompletableFuture.failedFuture(new IllegalStateException("no known multiaddrs for peer"));
                    }
                    return connectFirst(targetPeerId, addrs, 0);
                })
                .thenCompose(conn -> {
                    tryOpenPex(conn);
                    return conn.muxerSession()
                            .createStream(createChatProtocolBinding())
                            .getController();
                })
                .thenAccept(controller -> {
                    chatStreams.put(targetPeerIdStr, controller.stream);
                    controller.stream.closeFuture().thenRun(() -> chatStreams.remove(targetPeerIdStr));
                    controller.stream.writeAndFlush(frame(payload));
                })
                .orTimeout(Math.max(1000, dialTotalTimeoutMs), TimeUnit.MILLISECONDS)
                .exceptionallyCompose(ex -> {
                    maybeTriggerRelayReserveFallback();
                    return CompletableFuture.failedFuture(ex);
                });
    }

    public static class ChatController {
        public final Stream stream;

        public ChatController(Stream stream) {
            this.stream = stream;
        }
    }

    private void onPexAdvert(String remotePeerId, String json) {
        try {
            JSONObject obj = JSONUtil.parseObj(json);
            String peerIdStr = obj.getStr("peerId");
            JSONArray arr = obj.getJSONArray("addrs");
            if (peerIdStr == null || peerIdStr.isBlank() || arr == null) {
                return;
            }

            List<Multiaddr> addrs = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                String a = Objects.toString(arr.get(i), "");
                if (a.isBlank()) {
                    continue;
                }
                try {
                    addrs.add(Multiaddr.fromString(a));
                } catch (Exception ignore) {
                }
            }

            if (addrs.isEmpty()) {
                return;
            }

            peerAddrCache.put(peerIdStr, addrs);

            try {
                PeerId pid = PeerId.fromBase58(peerIdStr);
                host.getAddressBook().addAddrs(pid, ADDR_TTL_MS, addrs.toArray(new Multiaddr[0]));
            } catch (Exception ignore) {
            }

            log.info("PEX: 收到 {} 的可拨号地址 {} 条", remotePeerId, addrs.size());
        } catch (Exception e) {
            log.debug("解析 PEX advert 失败: {}", remotePeerId, e);
        }
    }

    private void sendPexAdvert(Stream stream) {
        try {
            String selfPeerIdStr = selfPeerId.toBase58();
            List<Multiaddr> addrs = getAdvertiseAddrs();
            String json = buildAdvertJson(selfPeerIdStr, addrs);
            stream.writeAndFlush(frame(json.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ignore) {
        }
    }

    private List<Multiaddr> getAdvertiseAddrs() {
        List<Multiaddr> raw = new ArrayList<>();
        host.listenAddresses().forEach(raw::add);

        List<String> localIpv4s = getLocalIpv4Candidates();
        List<String> localIpv6s = getLocalIpv6Candidates();

        List<Multiaddr> out = new ArrayList<>();
        for (Multiaddr a : raw) {
            String s = a.toString();
            if (s.contains("/ip4/0.0.0.0/")) {
                for (String ip : localIpv4s) {
                    String replaced = s.replace("/ip4/0.0.0.0/", "/ip4/" + ip + "/");
                    out.add(Multiaddr.fromString(ensureP2pComponent(replaced, selfPeerId.toBase58())));
                }
                continue;
            }

            // 有些环境会监听到 /ip6/::/，:: 是通配地址，不能直接给对端拨号，需要替换成本机实际 IPv6
            if (s.contains("/ip6/::/") || s.contains("/ip6/0:0:0:0:0:0:0:0/")) {
                for (String ip : localIpv6s) {
                    String replaced = s.replace("/ip6/::/", "/ip6/" + ip + "/")
                            .replace("/ip6/0:0:0:0:0:0:0:0/", "/ip6/" + ip + "/");
                    out.add(Multiaddr.fromString(ensureP2pComponent(replaced, selfPeerId.toBase58())));
                }

                // 如果本机没有可分享 IPv6（常见：只有 link-local），则退化输出 IPv4 分享地址（局域网更常用）
                if (out.isEmpty()) {
                    Integer port = extractTcpPort(s);
                    if (port != null) {
                        for (String ip4 : localIpv4s) {
                            String replaced = "/ip4/" + ip4 + "/tcp/" + port;
                            out.add(Multiaddr.fromString(ensureP2pComponent(replaced, selfPeerId.toBase58())));
                        }
                    }
                }
                continue;
            }

            out.add(Multiaddr.fromString(ensureP2pComponent(s, selfPeerId.toBase58())));
        }

        // 追加通过 relay 预约生成的 /p2p-circuit 地址（用于内网穿透）
        for (String s : getRelayShareAddrs()) {
            if (s == null || s.isBlank()) {
                continue;
            }
            try {
                out.add(Multiaddr.fromString(s));
            } catch (Exception ignore) {
            }
        }

        return out;
    }


    private static String ensureP2pComponent(String multiaddr, String peerIdStr) {
        if (multiaddr.contains("/p2p/")) {
            return multiaddr;
        }
        return multiaddr.endsWith("/") ? (multiaddr + "p2p/" + peerIdStr) : (multiaddr + "/p2p/" + peerIdStr);
    }

    private static Integer extractTcpPort(String multiaddrStr) {
        if (multiaddrStr == null) {
            return null;
        }
        int idx = multiaddrStr.indexOf("/tcp/");
        if (idx < 0) {
            return null;
        }
        int start = idx + 5;
        int end = start;
        while (end < multiaddrStr.length()) {
            char c = multiaddrStr.charAt(end);
            if (c < '0' || c > '9') {
                break;
            }
            end++;
        }
        if (end <= start) {
            return null;
        }
        try {
            return Integer.parseInt(multiaddrStr.substring(start, end));
        } catch (Exception ignore) {
            return null;
        }
    }

    private static List<String> getLocalIpv4Candidates() {
        List<String> ips = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface nif = ifaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!(addr instanceof Inet4Address)) {
                        continue;
                    }
                    if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()) {
                        continue;
                    }
                    ips.add(addr.getHostAddress());
                }
            }
        } catch (Exception ignore) {
        }
        if (ips.isEmpty()) {
            ips.add("127.0.0.1");
        }
        return ips;
    }

    private static List<String> getLocalIpv6Candidates() {
        List<String> ips = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface nif = ifaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!(addr instanceof java.net.Inet6Address)) {
                        continue;
                    }
                    if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isMulticastAddress()) {
                        continue;
                    }
                    String ip = addr.getHostAddress();
                    // Windows 可能带 zoneId，例如 fe80::1%12，multiaddr 不接受 '%'，去掉它
                    int zoneIdx = ip.indexOf('%');
                    if (zoneIdx > 0) {
                        ip = ip.substring(0, zoneIdx);
                    }
                    ips.add(ip);
                }
            }
        } catch (Exception ignore) {
        }
        return ips;
    }

    private static String buildAdvertJson(String peerIdStr, List<Multiaddr> listenAddrs) {
        // JSON：{"peerId":"...", "addrs":["/ip4/.../tcp/.../p2p/...","..."]}
        StringBuilder sb = new StringBuilder();
        sb.append("{\"peerId\":\"").append(peerIdStr).append("\",\"addrs\":[");
        for (int i = 0; i < listenAddrs.size(); i++) {
            sb.append("\"").append(listenAddrs.get(i)).append("\"");
            if (i < listenAddrs.size() - 1) {
                sb.append(",");
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    /**
     * 处理传入的 VPN 协议流
     */
    private void handleIncomingVpnStream(Stream stream) {
        String remotePeerId = stream.remotePeerId().toBase58();

        activeStreams.put(remotePeerId, stream);

        stream.closeFuture().thenRun(() -> {
            log.info("与 {} 的 VPN 流已关闭", remotePeerId);
            activeStreams.remove(remotePeerId);
        });

        // 关键修复：TCP 上的 Stream 不保证一个 read 就是一个 IP 包，必须做消息分帧
        // 这里用自实现的 length-prefix decoder，避免引入 netty-codec 依赖
        stream.pushHandler(new FramedInboundHandler(MAX_FRAME_SIZE, frame -> {
            int readableBytes = frame.readableBytes();
            if (readableBytes <= 0) {
                return;
            }

            vpnRxBytesTotal.addAndGet(readableBytes);

            byte[] data = new byte[readableBytes];
            frame.readBytes(data);
            try {
                wintunService.writeToTun(data);
            } catch (Exception e) {
                log.error("写入 TUN 失败: {}", remotePeerId, e);
            }
        }, (ctx, cause) -> {
            log.error("VPN 流异常: {}", remotePeerId, cause);
            ctx.close();
        }));
    }

    /**
     * 发送 IP 包到远端节点
     */
    public void sendPacket(String targetPeerId, byte[] packetData) {
        if (!running.get()) {
            log.warn("引擎未运行，无法发送数据包");
            return;
        }
        if (targetPeerId == null || targetPeerId.isBlank() || packetData == null || packetData.length == 0) {
            return;
        }

        // 发送优先：WebRTC DataChannel（优先走 UDP），失败再回落到 libp2p(TCP/Relay)
        if (webrtcEnabled && webrtcPreferVpn) {
            WebRtcDataSession s = webrtcSessions.get(targetPeerId);
            if (s != null && s.isConnected()) {
                try {
                    s.sendVpnPacket(packetData);
                    vpnTxBytesTotal.addAndGet(packetData.length);
                    return;
                } catch (Exception ignore) {
                }
            } else {
                // 触发后台协商（不阻塞当前包），后续包优先走 UDP
                triggerWebrtcNegotiationAsync(targetPeerId);
            }
        }


        vpnTxBytesTotal.addAndGet(packetData.length);

        Stream stream = activeStreams.get(targetPeerId);
        if (stream != null) {
            try {
                stream.writeAndFlush(frame(packetData));
            } catch (Exception e) {
                log.error("向 {} 发送数据失败，移除失效流并尝试重连", targetPeerId, e);
                activeStreams.remove(targetPeerId);
                dialAndSend(targetPeerId, packetData);
            }
            return;
        }

        dialAndSend(targetPeerId, packetData);
    }

    private static ByteBuf frame(byte[] payload) {
        ByteBuf out = Unpooled.buffer(4 + payload.length);
        out.writeInt(payload.length);
        out.writeBytes(payload);
        return out;
    }

    private static String extractPeerIdFromMultiaddr(Multiaddr addr) {
        if (addr == null) {
            return null;
        }
        String s = addr.toString();
        int idx = s.lastIndexOf("/p2p/");
        if (idx < 0) {
            return null;
        }
        String rest = s.substring(idx + 5);
        int slash = rest.indexOf('/');
        return slash >= 0 ? rest.substring(0, slash) : rest;
    }

    private static void validateDialableAddr(Multiaddr addr) {
        if (addr == null) {
            throw new IllegalArgumentException("multiaddr is null");
        }
        String s = addr.toString();
        // 0.0.0.0 / :: 是监听用的“通配地址”，对端无法拨号到它
        if (s.contains("/ip4/0.0.0.0/") || s.contains("/ip6/::/") || s.contains("/ip6/0:0:0:0:0:0:0:0/")) {
            throw new IllegalArgumentException("对方提供的地址包含通配 IP（0.0.0.0 或 ::），无法拨号。请让对方提供实际可达的 IP/DNS，例如 /ip4/192.168.x.x/tcp/PORT/p2p/PEERID 或 /dns4/xxx/tcp/PORT/p2p/PEERID");
        }
    }

    /**
     * 自实现 4 字节 length-prefix 解包：避免依赖 `LengthFieldBasedFrameDecoder`（netty-codec）。
     *
     * 协议：|len:int32|payload:len bytes|
     */
    private static final class FramedInboundHandler extends SimpleChannelInboundHandler<ByteBuf> {
        private final int maxFrameSize;
        private final Consumer<ByteBuf> onFrame;
        private final BiConsumer<ChannelHandlerContext, Throwable> onError;
        private final ByteBuf cumulation = Unpooled.buffer();

        private FramedInboundHandler(int maxFrameSize, Consumer<ByteBuf> onFrame, BiConsumer<ChannelHandlerContext, Throwable> onError) {
            super(true);
            this.maxFrameSize = maxFrameSize;
            this.onFrame = onFrame;
            this.onError = onError;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
            if (!msg.isReadable()) {
                return;
            }
            cumulation.writeBytes(msg);

            while (cumulation.readableBytes() >= 4) {
                int len = cumulation.getInt(cumulation.readerIndex());
                if (len < 0 || len > maxFrameSize) {
                    onError.accept(ctx, new IllegalStateException("invalid frame length: " + len));
                    return;
                }
                if (cumulation.readableBytes() < 4 + len) {
                    return;
                }
                cumulation.skipBytes(4);
                ByteBuf frame = cumulation.readRetainedSlice(len);
                try {
                    onFrame.accept(frame);
                } finally {
                    frame.release();
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            onError.accept(ctx, cause);
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            cumulation.release();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            try {
                super.channelInactive(ctx);
            } finally {
                if (cumulation.refCnt() > 0) {
                    cumulation.release();
                }
            }
        }
    }

    /**
     * 拨号到远端节点并发送数据：
     * 1) 优先用 PEX/AddressBook 缓存的 Multiaddr
     * 2) 连接成功后建立 VPN 流并发送首包
     */
    private void dialAndSend(String targetPeerIdStr, byte[] packetData) {
        final PeerId targetPeerId;
        try {
            targetPeerId = PeerId.fromBase58(targetPeerIdStr);
        } catch (Exception e) {
            log.warn("非法 PeerId: {}", targetPeerIdStr, e);
            return;
        }

        resolvePeerAddrs(targetPeerIdStr, targetPeerId)
                .thenCompose(addrs -> {
                    if (addrs.isEmpty()) {
                        return CompletableFuture.failedFuture(new IllegalStateException("no known multiaddrs for peer"));
                    }
                    return connectFirst(targetPeerId, addrs, 0);
                })
                .thenCompose(conn -> {
                    tryOpenPex(conn);
                    return conn.muxerSession()
                            .createStream(createVpnProtocolBinding())
                            .getController();
                })
                .thenAccept(controller -> {
                    activeStreams.put(targetPeerIdStr, controller.stream);
                    controller.stream.closeFuture().thenRun(() -> activeStreams.remove(targetPeerIdStr));
                    controller.stream.writeAndFlush(frame(packetData));
                })
                .orTimeout(Math.max(1000, dialTotalTimeoutMs), TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    maybeTriggerRelayReserveFallback();
                    log.warn("拨号并发送失败: {}", targetPeerIdStr, ex);
                    return null;
                });
    }

    private CompletableFuture<List<Multiaddr>> resolvePeerAddrs(String peerIdStr, PeerId peerId) {
        List<Multiaddr> cached = peerAddrCache.get(peerIdStr);
        if (cached != null && !cached.isEmpty()) {
            return CompletableFuture.completedFuture(normalizeDialAddrs(cached));
        }
        return host.getAddressBook()
                .getAddrs(peerId)
                .orTimeout(3, TimeUnit.SECONDS)
                .exceptionally(ex -> List.of())
                .thenApply(addrs -> {
                    List<Multiaddr> list;
                    if (addrs instanceof List) {
                        // noinspection unchecked
                        list = (List<Multiaddr>) addrs;
                    } else {
                        list = new ArrayList<>(addrs);
                    }
                    return normalizeDialAddrs(list);
                });
    }

    /**
     * 归一化拨号地址列表：
     * 1) 过滤掉通配/不可拨号地址
     * 2) 去重
     * 3) 直连地址优先，/p2p-circuit 在后
     */
    private List<Multiaddr> normalizeDialAddrs(List<Multiaddr> addrs) {
        if (addrs == null || addrs.isEmpty()) {
            return List.of();
        }

        List<Multiaddr> filtered = new ArrayList<>(addrs.size());
        for (Multiaddr a : addrs) {
            if (a == null) {
                continue;
            }
            try {
                validateDialableAddr(a);
                filtered.add(a);
            } catch (IllegalArgumentException ignore) {
            }
        }

        if (filtered.isEmpty()) {
            return List.of();
        }

        List<Multiaddr> uniq = new ArrayList<>(filtered.size());
        HashSet<String> seen = new HashSet<>();
        for (Multiaddr a : filtered) {
            String s = a.toString();
            if (seen.add(s)) {
                uniq.add(a);
            }
        }

        if (!dialPreferDirect || uniq.size() <= 1) {
            return uniq;
        }

        List<Multiaddr> direct = new ArrayList<>(uniq.size());
        List<Multiaddr> relay = new ArrayList<>(uniq.size());
        for (Multiaddr a : uniq) {
            String s = a.toString();
            if (s.contains("/p2p-circuit")) {
                relay.add(a);
            } else {
                direct.add(a);
            }
        }
        direct.addAll(relay);
        return direct;
    }

    private void maybeTriggerRelayReserveFallback() {
        if (!dialTriggerRelayReserveOnFail || !relayEnabled || host == null || !running.get()) {
            return;
        }
        if (!relayReservations.isEmpty()) {
            return;
        }
        if (relayReserveTriggered.compareAndSet(false, true)) {
            log.info("拨号全部失败，触发一次 relay 预约兜底（帮助他人通过 /p2p-circuit 连接到你）");
            relayReserveNow();
        }
    }

    private CompletableFuture<Connection> connectFirst(PeerId peerId, List<Multiaddr> addrs, int idx) {
        if (addrs == null || idx >= addrs.size()) {
            return CompletableFuture.failedFuture(new IllegalStateException("all dial attempts failed"));
        }

        Multiaddr a = addrs.get(idx);
        long perAddrTimeout = Math.max(200, dialPerAddrTimeoutMs);

        return host.getNetwork().connect(peerId, a)
                .orTimeout(perAddrTimeout, TimeUnit.MILLISECONDS)
                .handle((conn, ex) -> {
                    if (ex == null) {
                        return CompletableFuture.completedFuture(conn);
                    }
                    log.debug("拨号失败，尝试下一个地址: {} -> {}", a, (ex.getMessage() == null ? ex.toString() : ex.getMessage()));
                    return connectFirst(peerId, addrs, idx + 1);
                })
                .thenCompose(f -> f);
    }

    /**
     * 手动添加对等节点（用于初始连接）
     */
    public CompletableFuture<Object> connectToPeer(String peerIdStr, String multiaddr) {
        try {
            PeerId peerId = PeerId.fromBase58(peerIdStr);
            Multiaddr addr = Multiaddr.fromString(multiaddr);
            validateDialableAddr(addr);

            // 放入地址簿，后续 sendPacket()/dialAndSend() 可直接使用
            host.getAddressBook().addAddrs(peerId, ADDR_TTL_MS, addr).exceptionally(ex -> null);
            peerAddrCache.put(peerIdStr, List.of(addr));

            log.info("连接到对等节点: {} @ {}", peerIdStr, multiaddr);

            return host.getNetwork().connect(peerId, addr)
                    .thenCompose(conn -> {
                        tryOpenPex(conn);
                        return conn.muxerSession()
                                .createStream(createVpnProtocolBinding())
                                .getController();
                    })
                    .thenApply(controller -> {
                        log.info("成功建立到 {} 的 VPN 流", peerIdStr);
                        activeStreams.put(peerIdStr, controller.stream);
                        controller.stream.closeFuture().thenRun(() -> activeStreams.remove(peerIdStr));
                        return null;
                    })
                    .exceptionally(ex -> {
                        log.error("连接到 {} 失败", peerIdStr, ex);
                        return null;
                    });
        } catch (Exception e) {
            log.error("解析对等节点信息失败", e);
            return CompletableFuture.failedFuture(e);
        }
    }

    public String getSelfPeerId() {
        return selfPeerId == null ? null : selfPeerId.toBase58();
    }

    public long getVpnTxBytesTotal() {
        return vpnTxBytesTotal.get();
    }

    public long getVpnRxBytesTotal() {
        return vpnRxBytesTotal.get();
    }

    public Collection<Multiaddr> getListenAddresses() {
        return host == null ? List.of() : host.listenAddresses();
    }

    /**
     * VPN 协议控制器（占位符，实际逻辑在 handleIncomingVpnStream 中）
     */
    public static class VpnController {
        public final Stream stream;

        public VpnController(Stream stream) {
            this.stream = stream;
        }
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        log.info("正在关闭 Libp2p 引擎...");

        activeStreams.values().forEach(stream -> {
            try {
                stream.close().get();
            } catch (Exception e) {
                log.debug("关闭流时出错", e);
            }
        });
        activeStreams.clear();

        chatStreams.values().forEach(stream -> {
            try {
                stream.close().get();
            } catch (Exception e) {
                log.debug("关闭流时出错", e);
            }
        });
        chatStreams.clear();

        stopMdnsDiscovery();

        // 尽力清理 UPnP 端口映射（不保证所有路由器支持 DeletePortMapping）
        try {
            Integer p = upnpMappedPort;
            if (p != null && upnpControlUrl != null && upnpServiceType != null) {
                upnpPortMapper.deleteTcpMapping(upnpControlUrl, upnpServiceType, p);
            }
        } catch (Exception ignore) {
        }

        relayReservations.clear();

        relayReserved.set(0);
        try {
            relayScheduler.shutdownNow();
        } catch (Exception ignore) {
        }

        if (host != null) {

            try {
                host.stop().get();
                log.info("Libp2p 引擎已关闭");
            } catch (Exception e) {
                log.error("关闭 Host 时出错", e);
            }
        }
    }

    public Host getHost() {
        return host;
    }

    public boolean isRunning() {
        return running.get();
    }
}