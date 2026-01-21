package com.kk.p2p.ice;

import lombok.extern.slf4j.Slf4j;
import org.ice4j.Transport;
import org.ice4j.TransportAddress;
import org.ice4j.ice.Agent;
import org.ice4j.ice.Candidate;
import org.ice4j.ice.CandidatePair;
import org.ice4j.ice.CandidateType;
import org.ice4j.ice.Component;
import org.ice4j.ice.IceMediaStream;
import org.ice4j.ice.IceProcessingState;
import org.ice4j.ice.LocalCandidate;
import org.ice4j.ice.RemoteCandidate;
import org.ice4j.ice.harvest.StunCandidateHarvester;
import org.ice4j.ice.harvest.TurnCandidateHarvester;
import org.ice4j.security.LongTermCredential;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一个“ICE 协商成功后的 UDP 数据通道”。
 * <p>
 * - 信令（offer/answer/candidates）不在本类中：由上层（例如 libp2p stream）负责交换。
 * - 本类负责：
 *   1) 根据配置创建 ice4j Agent（STUN/TURN）
 *   2) 应用对端 ICE 参数（ufrag/pwd + candidates）
 *   3) 启动 connectivity checks，等待选出 CandidatePair
 *   4) 通过选中的 pair 的 DatagramSocket 收发加密数据（AES-GCM）
 */
@Slf4j
public final class IceUdpSession implements AutoCloseable {

    /** 数据包头：'K''K' */
    private static final short MAGIC = (short) 0x4B4B;
    private static final byte VERSION = 1;

    public static final byte TYPE_CHAT = 1;
    public static final byte TYPE_VPN = 2;
    public static final byte TYPE_PING = 3;

    private static final int NONCE_LEN = 12;
    private static final int GCM_TAG_BITS = 128;

    private final String peerId;
    private final Agent agent;
    private final IceMediaStream iceStream;
    private final Component component;

    private final boolean controlling;
    private final SecretKey secretKey;
    private final SecureRandom random = new SecureRandom();

    private final AtomicBoolean established = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile DatagramSocket socket;
    private volatile TransportAddress selectedRemote;

    private volatile Thread rxThread;

    public IceUdpSession(String peerId,
                         Agent agent,
                         IceMediaStream iceStream,
                         Component component,
                         boolean controlling,
                         byte[] aesKey32) {
        this.peerId = Objects.requireNonNull(peerId, "peerId");
        this.agent = Objects.requireNonNull(agent, "agent");
        this.iceStream = Objects.requireNonNull(iceStream, "iceStream");
        this.component = Objects.requireNonNull(component, "component");
        this.controlling = controlling;
        if (aesKey32 == null || aesKey32.length != 32) {
            throw new IllegalArgumentException("aesKey must be 32 bytes");
        }
        this.secretKey = new SecretKeySpec(aesKey32, "AES");
    }

    public String getPeerId() {
        return peerId;
    }

    public boolean isControlling() {
        return controlling;
    }

    public boolean isEstablished() {
        return established.get();
    }

    public String getLocalUfrag() {
        return agent.getLocalUfrag();
    }

    public String getLocalPassword() {
        return agent.getLocalPassword();
    }

    public List<String> getLocalCandidateLines() {
        List<String> out = new ArrayList<>();
        for (LocalCandidate c : component.getLocalCandidates()) {
            try {
                String line = toCandidateLine(c);
                if (line != null && !line.isBlank()) {
                    out.add(line);
                }
            } catch (Exception ignore) {
            }
        }
        return out;
    }

    public void applyRemote(String remoteUfrag, String remotePwd, List<String> remoteCandidateLines) {
        if (remoteUfrag == null || remoteUfrag.isBlank()) {
            throw new IllegalArgumentException("remote ufrag is blank");
        }
        if (remotePwd == null || remotePwd.isBlank()) {
            throw new IllegalArgumentException("remote password is blank");
        }

        iceStream.setRemoteUfrag(remoteUfrag.trim());
        iceStream.setRemotePassword(remotePwd.trim());

        if (remoteCandidateLines != null) {
            for (String line : remoteCandidateLines) {
                RemoteCandidate rc = parseRemoteCandidate(line, iceStream, component, remoteUfrag.trim());
                if (rc != null) {
                    component.addUpdateRemoteCandidates(rc);
                }
            }
            component.updateRemoteCandidates();
        }
    }

    public CompletableFuture<Void> start(long connectTimeoutMs) {
        if (connectTimeoutMs <= 0) {
            connectTimeoutMs = 10_000;
        }

        agent.setControlling(controlling);

        CompletableFuture<Void> ready = new CompletableFuture<>();

        PropertyChangeListener listener = new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent evt) {
                if (evt == null) {
                    return;
                }
                if (!"state".equals(evt.getPropertyName())) {
                    return;
                }

                IceProcessingState st = agent.getState();
                if (st == null) {
                    return;
                }

                if (st.isEstablished()) {
                    try {
                        onEstablished();
                        ready.complete(null);
                    } catch (Exception e) {
                        ready.completeExceptionally(e);
                    } finally {
                        try {
                            agent.removeStateChangeListener(this);
                        } catch (Exception ignore) {
                        }
                    }
                } else if (st.isOver()) {
                    ready.completeExceptionally(new IllegalStateException("ICE failed: " + st));
                    try {
                        agent.removeStateChangeListener(this);
                    } catch (Exception ignore) {
                    }
                }
            }
        };

        agent.addStateChangeListener(listener);

        try {
            agent.startConnectivityEstablishment();
        } catch (Exception e) {
            try {
                agent.removeStateChangeListener(listener);
            } catch (Exception ignore) {
            }
            return CompletableFuture.failedFuture(e);
        }

        return ready.orTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS);
    }

    private void onEstablished() {
        CandidatePair pair = component.getSelectedPair();
        if (pair == null) {
            throw new IllegalStateException("ICE established but no selected pair");
        }

        DatagramSocket s = pair.getDatagramSocket();
        if (s == null) {
            throw new IllegalStateException("ICE selected pair has no DatagramSocket");
        }

        this.socket = s;
        this.selectedRemote = pair.getRemoteCandidate().getTransportAddress();
        established.set(true);

        startRxLoop();

        log.info("ICE-UDP 已建立: peer={}, local={}, remote={} (controlling={})",
                peerId,
                pair.getLocalCandidate().getTransportAddress(),
                selectedRemote,
                controlling);
    }

    private void startRxLoop() {
        if (rxThread != null) {
            return;
        }

        Thread t = new Thread(() -> {
            DatagramSocket s = socket;
            if (s == null) {
                return;
            }
            try {
                // 避免永久阻塞，便于退出
                s.setSoTimeout(800);
            } catch (Exception ignore) {
            }

            byte[] buf = new byte[64 * 1024];
            DatagramPacket p = new DatagramPacket(buf, buf.length);

            while (!closed.get()) {
                try {
                    s.receive(p);

                    // 只接收来自 ICE 选中远端地址的数据（防止被随意注入）
                    TransportAddress r = selectedRemote;
                    if (r != null) {
                        if (!Objects.equals(p.getAddress(), r.getAddress()) || p.getPort() != r.getPort()) {
                            continue;
                        }
                    }

                    byte[] data = new byte[p.getLength()];
                    System.arraycopy(p.getData(), p.getOffset(), data, 0, p.getLength());

                    Decoded d = decryptPacket(data);
                    if (d == null) {
                        continue;
                    }

                    // 上层在这里自行接收/分发。为了保持本类可复用，这里不直接回调。
                    // 当前项目由 Libp2pEngine 在上层把数据路由到 Chat/VPN。
                    IceUdpSessionCallbacks.dispatch(peerId, d.type, d.payload);

                } catch (SocketTimeoutException ignore) {
                } catch (Exception e) {
                    if (!closed.get()) {
                        log.debug("ICE-UDP 接收异常: peer={} {}", peerId, e.toString());
                    }
                }
            }
        }, "kk-ice-rx-" + peerId);

        t.setDaemon(true);
        rxThread = t;
        t.start();
    }

    public void sendChat(String text) {
        if (text == null) {
            text = "";
        }
        send(TYPE_CHAT, text.getBytes(StandardCharsets.UTF_8));
    }

    public void sendVpnPacket(byte[] packet) {
        if (packet == null) {
            return;
        }
        send(TYPE_VPN, packet);
    }

    public void sendPing() {
        send(TYPE_PING, new byte[0]);
    }

    private void send(byte type, byte[] payload) {
        if (!established.get()) {
            throw new IllegalStateException("ICE session is not established");
        }
        if (closed.get()) {
            throw new IllegalStateException("ICE session is closed");
        }

        DatagramSocket s = socket;
        TransportAddress r = selectedRemote;
        if (s == null || r == null) {
            throw new IllegalStateException("ICE socket/remote is null");
        }

        byte[] packet = encryptPacket(type, payload == null ? new byte[0] : payload);

        try {
            DatagramPacket p = new DatagramPacket(packet, packet.length, r.getAddress(), r.getPort());
            s.send(p);
        } catch (Exception e) {
            throw (e instanceof RuntimeException) ? (RuntimeException) e : new RuntimeException(e);
        }
    }

    private byte[] encryptPacket(byte type, byte[] plaintext) {
        try {
            byte[] nonce = new byte[NONCE_LEN];
            random.nextBytes(nonce);

            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] ct = c.doFinal(plaintext);

            ByteBuffer out = ByteBuffer.allocate(2 + 1 + 1 + NONCE_LEN + ct.length);
            out.putShort(MAGIC);
            out.put(VERSION);
            out.put(type);
            out.put(nonce);
            out.put(ct);
            return out.array();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Decoded decryptPacket(byte[] packet) {
        if (packet == null || packet.length < (2 + 1 + 1 + NONCE_LEN + 16)) {
            return null;
        }

        ByteBuffer in = ByteBuffer.wrap(packet);
        short magic = in.getShort();
        if (magic != MAGIC) {
            return null;
        }

        byte ver = in.get();
        if (ver != VERSION) {
            return null;
        }

        byte type = in.get();

        byte[] nonce = new byte[NONCE_LEN];
        in.get(nonce);

        byte[] ct = new byte[in.remaining()];
        in.get(ct);

        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] pt = c.doFinal(ct);
            return new Decoded(type, pt);
        } catch (Exception e) {
            return null;
        }
    }

    private record Decoded(byte type, byte[] payload) {
    }

    /**
     * 构造标准 candidate line（不带前缀 a=candidate:）。
     */
    private static String toCandidateLine(LocalCandidate c) {
        if (c == null) {
            return null;
        }
        TransportAddress ta = c.getTransportAddress();
        if (ta == null) {
            return null;
        }

        String ip = ta.getHostAddress();
        int port = ta.getPort();

        String foundation = safeToken(c.getFoundation());
        int componentId = c.getParentComponent() == null ? 1 : c.getParentComponent().getComponentID();
        long priority = c.getPriority();

        String typ = toIceTyp(c.getType());

        StringBuilder sb = new StringBuilder();
        sb.append(foundation).append(' ')
                .append(componentId).append(' ')
                .append("udp").append(' ')
                .append(priority).append(' ')
                .append(ip).append(' ')
                .append(port).append(' ')
                .append("typ").append(' ')
                .append(typ);

        TransportAddress related = c.getRelatedAddress();
        if (related != null) {
            sb.append(' ').append("raddr").append(' ').append(related.getHostAddress());
            sb.append(' ').append("rport").append(' ').append(related.getPort());
        }

        return sb.toString();
    }

    private static String safeToken(String s) {
        if (s == null || s.isBlank()) {
            return "0";
        }
        String v = s.trim();
        // 避免奇怪空白
        return v.replace(' ', '_');
    }

    private static String toIceTyp(CandidateType t) {
        if (t == null) {
            return "host";
        }
        return switch (t) {
            case HOST_CANDIDATE, LOCAL_CANDIDATE -> "host";
            case SERVER_REFLEXIVE_CANDIDATE, STUN_CANDIDATE -> "srflx";
            case RELAYED_CANDIDATE -> "relay";
            case PEER_REFLEXIVE_CANDIDATE -> "prflx";
        };
    }

    private static CandidateType fromIceTyp(String typ) {
        String v = typ == null ? "" : typ.trim().toLowerCase();
        return switch (v) {
            case "host" -> CandidateType.HOST_CANDIDATE;
            case "srflx" -> CandidateType.SERVER_REFLEXIVE_CANDIDATE;
            case "relay" -> CandidateType.RELAYED_CANDIDATE;
            case "prflx" -> CandidateType.PEER_REFLEXIVE_CANDIDATE;
            default -> CandidateType.HOST_CANDIDATE;
        };
    }

    private static RemoteCandidate parseRemoteCandidate(String candidateLine,
                                                        IceMediaStream stream,
                                                        Component component,
                                                        String remoteUfrag) {
        if (candidateLine == null) {
            return null;
        }
        String line = candidateLine.trim();
        if (line.isBlank()) {
            return null;
        }

        // 允许带 "a=candidate:" 前缀
        if (line.startsWith("a=candidate:")) {
            line = line.substring("a=candidate:".length());
        } else if (line.startsWith("candidate:")) {
            line = line.substring("candidate:".length());
        }

        String[] parts = line.trim().split("\\s+");
        if (parts.length < 8) {
            return null;
        }

        String foundation = parts[0];
        int componentId;
        try {
            componentId = Integer.parseInt(parts[1]);
        } catch (Exception e) {
            componentId = 1;
        }

        String transport = parts[2].toLowerCase();
        if (!transport.contains("udp")) {
            // 本项目只用 UDP
            return null;
        }

        long priority;
        try {
            priority = Long.parseLong(parts[3]);
        } catch (Exception e) {
            priority = 0L;
        }

        String ip = parts[4];
        int port;
        try {
            port = Integer.parseInt(parts[5]);
        } catch (Exception e) {
            return null;
        }

        // parts[6] == "typ"
        String typ = parts[7];
        CandidateType ct = fromIceTyp(typ);

        Component compToUse = component;
        try {
            if (component.getComponentID() != componentId) {
                // 当前实现只创建一个 component，忽略不匹配的（避免异常）
                return null;
            }
        } catch (Exception ignore) {
        }

        try {
            TransportAddress ta = new TransportAddress(ip, port, Transport.UDP);
            return new RemoteCandidate(ta, compToUse, ct, foundation, priority, null, remoteUfrag);
        } catch (Exception e) {
            return null;
        }
    }

    public static final class Config {
        public boolean enableStun = true;
        public List<Server> stunServers = new ArrayList<>();

        public boolean enableTurn = false;
        public List<TurnServer> turnServers = new ArrayList<>();

        /**
         * ICE 在本机绑定 UDP 端口范围。
         * 0 表示由系统分配。
         */
        public int minPort = 0;
        public int maxPort = 0;

        public static final class Server {
            public final String host;
            public final int port;

            public Server(String host, int port) {
                this.host = host;
                this.port = port;
            }
        }

        public static final class TurnServer {
            public final String host;
            public final int port;
            public final String username;
            public final String password;

            public TurnServer(String host, int port, String username, String password) {
                this.host = host;
                this.port = port;
                this.username = username;
                this.password = password;
            }
        }
    }

    public static IceUdpSession create(Config cfg, String peerId, boolean controlling, byte[] aesKey32) throws Exception {
        if (cfg == null) {
            cfg = new Config();
        }

        Agent agent = new Agent();

        // harvesters
        if (cfg.enableStun && cfg.stunServers != null) {
            for (Config.Server s : cfg.stunServers) {
                if (s == null) {
                    continue;
                }
                if (s.host == null || s.host.isBlank() || s.port <= 0) {
                    continue;
                }
                agent.addCandidateHarvester(new StunCandidateHarvester(new TransportAddress(s.host.trim(), s.port, Transport.UDP)));
            }
        }

        if (cfg.enableTurn && cfg.turnServers != null) {
            for (Config.TurnServer s : cfg.turnServers) {
                if (s == null) {
                    continue;
                }
                if (s.host == null || s.host.isBlank() || s.port <= 0) {
                    continue;
                }

                TransportAddress ta = new TransportAddress(s.host.trim(), s.port, Transport.UDP);

                if (s.username != null && !s.username.isBlank()) {
                    LongTermCredential cred = new LongTermCredential(s.username.trim(), (s.password == null ? "" : s.password).trim());
                    agent.addCandidateHarvester(new TurnCandidateHarvester(ta, cred));
                } else {
                    agent.addCandidateHarvester(new TurnCandidateHarvester(ta));
                }
            }
        }

        IceMediaStream stream = agent.createMediaStream("data");

        int minPort = cfg.minPort;
        int maxPort = cfg.maxPort;
        if (minPort < 0) minPort = 0;
        if (maxPort < 0) maxPort = 0;
        if (minPort > 0 && maxPort > 0 && maxPort < minPort) {
            int tmp = minPort;
            minPort = maxPort;
            maxPort = tmp;
        }

        // preferredPort=0 让系统选择（但在 NAT 场景下可能不利于固定端口映射；可通过配置限制范围）
        Component component = agent.createComponent(stream, 0, minPort, maxPort);

        return new IceUdpSession(peerId, agent, stream, component, controlling, aesKey32);
    }

    public static byte[] newAesKey32() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    public static String b64(byte[] v) {
        return Base64.getEncoder().encodeToString(v);
    }

    public static byte[] b64d(String v) {
        return Base64.getDecoder().decode(v);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                DatagramSocket s = socket;
                if (s != null) {
                    try {
                        s.close();
                    } catch (Exception ignore) {
                    }
                }
            } catch (Exception ignore) {
            }
        }
    }

    /**
     * 上层回调桥接（避免直接把 Libp2pEngine 依赖塞进本类）。
     */
    public static final class IceUdpSessionCallbacks {
        private static volatile TriConsumer<String, Byte, byte[]> dispatcher = (peerId, type, payload) -> {
        };

        public static void setDispatcher(TriConsumer<String, Byte, byte[]> d) {
            dispatcher = (d == null) ? dispatcher : d;
        }

        static void dispatch(String peerId, byte type, byte[] payload) {
            TriConsumer<String, Byte, byte[]> d = dispatcher;
            if (d != null) {
                d.accept(peerId, type, payload);
            }
        }

        @FunctionalInterface
        public interface TriConsumer<A, B, C> {
            void accept(A a, B b, C c);
        }
    }
}
