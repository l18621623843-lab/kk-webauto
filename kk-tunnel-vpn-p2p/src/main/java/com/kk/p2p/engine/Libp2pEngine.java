package com.kk.p2p.engine;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
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
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import kotlin.Pair;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

    private static final long ADDR_TTL_MS = TimeUnit.HOURS.toMillis(6);
    private static final int MAX_FRAME_SIZE = Integer.getInteger("kk.p2p.maxFrameSize", 1024 * 1024);

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

    // 缓存已建立的 VPN 流，Key 为远端 PeerID 的 String
    private final ConcurrentHashMap<String, Stream> activeStreams = new ConcurrentHashMap<>();

    // VPN 流量统计（字节数）
    private final AtomicLong vpnTxBytesTotal = new AtomicLong(0);
    private final AtomicLong vpnRxBytesTotal = new AtomicLong(0);

    // 缓存已建立的 Chat 流，Key 为远端 PeerID 的 String
    private final ConcurrentHashMap<String, Stream> chatStreams = new ConcurrentHashMap<>();

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

            host = new HostBuilder()
                    .protocol(
                            new Ping(),
                            new Identify(),
                            createPexProtocolBinding(),
                            createChatProtocolBinding(),
                            createVpnProtocolBinding()
                    )
                    .listen("/ip4/0.0.0.0/tcp/" + listenPort)
                    .builderModifier(builder -> builder.getIdentity().setFactory(() -> privKey))
                    .build();

            host.start().get();
            running.set(true);

            log.info("Libp2p 节点启动成功！");
            log.info("你的 PeerID: {}", selfPeerId.toBase58());
            log.info("监听地址: {}", host.listenAddresses());
            // 注意：listen 地址可能包含 0.0.0.0 / :: 这类“通配地址”，无法用于对端拨号。
            // 这里输出替换后的“可分享地址”（用于局域网直连）。公网/NAT 情况需由对方提供可达的公网 IP/端口或后续接入 relay/hole-punch。
            log.info("可分享地址(用于直连): {}", getAdvertiseAddrs());

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
                });
    }

    public CompletableFuture<Void> sendChatMessage(String targetPeerIdStr, String message) {
        if (!running.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("engine is not running"));
        }
        if (targetPeerIdStr == null || targetPeerIdStr.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("targetPeerId is blank"));
        }

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
                .exceptionally(ex -> {
                    log.warn("拨号并发送失败: {}", targetPeerIdStr, ex);
                    return null;
                });
    }

    private CompletableFuture<List<Multiaddr>> resolvePeerAddrs(String peerIdStr, PeerId peerId) {
        List<Multiaddr> cached = peerAddrCache.get(peerIdStr);
        if (cached != null && !cached.isEmpty()) {
            return CompletableFuture.completedFuture(cached);
        }
        return host.getAddressBook()
                .getAddrs(peerId)
                .orTimeout(3, TimeUnit.SECONDS)
                .exceptionally(ex -> List.of())
                .thenApply(addrs -> {
                    if (addrs instanceof List) {
                        // noinspection unchecked
                        return (List<Multiaddr>) addrs;
                    }
                    return new ArrayList<>(addrs);
                });
    }

    private CompletableFuture<Connection> connectFirst(PeerId peerId, List<Multiaddr> addrs, int idx) {
        if (idx >= addrs.size()) {
            return CompletableFuture.failedFuture(new IllegalStateException("all dial attempts failed"));
        }

        Multiaddr a = addrs.get(idx);
        CompletableFuture<Connection> attempt = host.getNetwork().connect(peerId, a);
        return attempt.handle((conn, ex) -> {
            if (ex == null) {
                return CompletableFuture.completedFuture(conn);
            }
            log.debug("拨号失败，尝试下一个地址: {} -> {}", a, ex.toString());
            return connectFirst(peerId, addrs, idx + 1);
        }).thenCompose(f -> f);
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