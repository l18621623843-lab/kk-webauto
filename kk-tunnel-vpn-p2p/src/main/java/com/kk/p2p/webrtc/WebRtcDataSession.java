package com.kk.p2p.webrtc;

import dev.onvoid.webrtc.*;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebRTC DataChannel 会话（ICE/STUN/TURN + DTLS + SCTP）。
 * <p>
 * - 信令（SDP offer/answer + ICE candidates）由上层负责交换。
 * - 本类负责建立 PeerConnection，并通过 DataChannel 收发 Chat/VPN 数据。
 */
@Slf4j
public final class WebRtcDataSession implements AutoCloseable {

    public interface Callbacks {
        void onLocalIceCandidate(RTCIceCandidate cand);

        void onChatMessage(String fromPeerId, String message);

        void onVpnPacket(String fromPeerId, byte[] packet);

        void onStateChanged(String fromPeerId, String state, String detail);
    }

    public static final class Config {
        public int minPort = 0;
        public int maxPort = 0;

        public List<String> stunServers = List.of();
        public List<TurnServer> turnServers = List.of();

        public boolean enableTurn = false;

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

    private static final Object FACTORY_LOCK = new Object();
    private static volatile PeerConnectionFactory factory;

    private static PeerConnectionFactory getFactory() {
        PeerConnectionFactory f = factory;
        if (f != null) {
            return f;
        }
        synchronized (FACTORY_LOCK) {
            f = factory;
            if (f != null) {
                return f;
            }
            factory = new PeerConnectionFactory();
            return factory;
        }
    }

    private final String peerId;
    private final RTCPeerConnection pc;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile RTCDataChannel chat;
    private volatile RTCDataChannel vpn;

    private volatile Callbacks callbacks;

    private final CompletableFuture<Void> connected = new CompletableFuture<>();
    private volatile String state = "NEW";
    private volatile String detail = "-";

    private WebRtcDataSession(String peerId, RTCPeerConnection pc) {
        this.peerId = peerId;
        this.pc = pc;
    }

    public static WebRtcDataSession create(String peerId, Config cfg, boolean asOfferer, Callbacks cb) {
        Objects.requireNonNull(peerId, "peerId");
        Objects.requireNonNull(cfg, "cfg");

        PeerConnectionFactory f = getFactory();

        RTCConfiguration c = new RTCConfiguration();
        c.iceServers = buildIceServers(cfg);

        // 端口范围
        if (cfg.minPort > 0 || cfg.maxPort > 0) {
            int min = Math.max(0, cfg.minPort);
            int max = Math.max(0, cfg.maxPort);
            c.portAllocatorConfig = new PortAllocatorConfig(min, max, 0);
        }

        WebRtcDataSession[] holder = new WebRtcDataSession[1];

        PeerConnectionObserver obs = new PeerConnectionObserver() {
            @Override
            public void onIceCandidate(RTCIceCandidate cand) {
                WebRtcDataSession s = holder[0];
                if (s == null) {
                    return;
                }
                Callbacks callbacks = s.callbacks;
                if (callbacks != null && cand != null) {
                    try {
                        callbacks.onLocalIceCandidate(cand);
                    } catch (Exception ignore) {
                    }
                }
            }

            @Override
            public void onConnectionChange(RTCPeerConnectionState newState) {
                WebRtcDataSession s = holder[0];
                if (s == null) {
                    return;
                }

                String st = newState == null ? "UNKNOWN" : newState.name();
                s.state = st;
                s.detail = "pc=" + st + ", ice=" + safeEnum(s.pc.getIceConnectionState());

                if (newState == RTCPeerConnectionState.CONNECTED) {
                    s.connected.complete(null);
                } else if (newState == RTCPeerConnectionState.FAILED || newState == RTCPeerConnectionState.CLOSED) {
                    s.connected.completeExceptionally(new IllegalStateException("webrtc pc state=" + st));
                }

                Callbacks callbacks = s.callbacks;
                if (callbacks != null) {
                    try {
                        callbacks.onStateChanged(s.peerId, "PC_" + st, s.detail);
                    } catch (Exception ignore) {
                    }
                }
            }

            @Override
            public void onDataChannel(RTCDataChannel ch) {
                WebRtcDataSession s = holder[0];
                if (s == null || ch == null) {
                    return;
                }

                String label = ch.getLabel();
                if ("chat".equalsIgnoreCase(label)) {
                    s.chat = ch;
                    s.attachDataChannelObserver(ch, true);
                } else if ("vpn".equalsIgnoreCase(label)) {
                    s.vpn = ch;
                    s.attachDataChannelObserver(ch, false);
                } else {
                    // 未知 channel，仍然注册观察便于 close
                    s.attachDataChannelObserver(ch, false);
                }
            }
        };

        RTCPeerConnection pc = f.createPeerConnection(c, obs);
        WebRtcDataSession sess = new WebRtcDataSession(peerId, pc);
        sess.callbacks = cb;
        holder[0] = sess;

        if (asOfferer) {
            sess.createLocalDataChannels();
        }

        sess.state = "CREATED";
        return sess;
    }

    public String getPeerId() {
        return peerId;
    }

    public boolean isClosed() {
        return closed.get();
    }

    public String getState() {
        return state;
    }

    public String getDetail() {
        return detail;
    }

    public boolean isConnected() {
        return connected.isDone() && !connected.isCompletedExceptionally() && !connected.isCancelled();
    }

    public CompletableFuture<Void> whenConnected() {
        return connected;
    }

    public CompletableFuture<RTCSessionDescription> createOffer() {
        CompletableFuture<RTCSessionDescription> fut = new CompletableFuture<>();
        pc.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription sdp) {
                setLocalDescription(sdp).thenRun(() -> fut.complete(sdp)).exceptionally(ex -> {
                    fut.completeExceptionally(ex);
                    return null;
                });
            }

            @Override
            public void onFailure(String error) {
                fut.completeExceptionally(new IllegalStateException(error == null ? "createOffer failed" : error));
            }
        });
        return fut;
    }

    public CompletableFuture<RTCSessionDescription> createAnswer() {
        CompletableFuture<RTCSessionDescription> fut = new CompletableFuture<>();
        pc.createAnswer(new RTCAnswerOptions(), new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription sdp) {
                setLocalDescription(sdp).thenRun(() -> fut.complete(sdp)).exceptionally(ex -> {
                    fut.completeExceptionally(ex);
                    return null;
                });
            }

            @Override
            public void onFailure(String error) {
                fut.completeExceptionally(new IllegalStateException(error == null ? "createAnswer failed" : error));
            }
        });
        return fut;
    }

    public CompletableFuture<Void> setRemoteDescription(RTCSessionDescription sdp) {
        Objects.requireNonNull(sdp, "sdp");
        return setRemoteDescription0(sdp);
    }

    public void addRemoteIceCandidate(RTCIceCandidate c) {
        if (c == null || closed.get()) {
            return;
        }
        try {
            pc.addIceCandidate(c);
        } catch (Exception e) {
            log.debug("WebRTC addIceCandidate failed: peer={} {}", peerId, e.toString());
        }
    }

    public void sendChat(String text) {
        if (text == null) {
            text = "";
        }
        RTCDataChannel ch = chat;
        if (ch == null) {
            throw new IllegalStateException("chat datachannel not ready");
        }
        try {
            ByteBuffer buf = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
            ch.send(new RTCDataChannelBuffer(buf, false));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void sendVpnPacket(byte[] packet) {
        if (packet == null) {
            return;
        }
        RTCDataChannel ch = vpn;
        if (ch == null) {
            throw new IllegalStateException("vpn datachannel not ready");
        }
        try {
            ch.send(new RTCDataChannelBuffer(ByteBuffer.wrap(packet), true));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void createLocalDataChannels() {
        // chat：可靠、有序
        RTCDataChannelInit chatInit = new RTCDataChannelInit();
        chatInit.ordered = true;
        chatInit.negotiated = false;
        RTCDataChannel chat = pc.createDataChannel("chat", chatInit);
        this.chat = chat;
        attachDataChannelObserver(chat, true);

        // vpn：更偏向低延迟（不保证可靠）
        RTCDataChannelInit vpnInit = new RTCDataChannelInit();
        vpnInit.ordered = false;
        vpnInit.negotiated = false;
        vpnInit.maxRetransmits = 0;
        RTCDataChannel vpn = pc.createDataChannel("vpn", vpnInit);
        this.vpn = vpn;
        attachDataChannelObserver(vpn, false);
    }

    private void attachDataChannelObserver(RTCDataChannel ch, boolean isChat) {
        if (ch == null) {
            return;
        }

        ch.registerObserver(new RTCDataChannelObserver() {
            @Override
            public void onBufferedAmountChange(long prevAmount) {
            }

            @Override
            public void onStateChange() {
                try {
                    RTCDataChannelState st = ch.getState();
                    detail = "pc=" + safeEnum(pc.getConnectionState()) + ", ice=" + safeEnum(pc.getIceConnectionState()) + ", dc(" + ch.getLabel() + ")=" + safeEnum(st);
                    Callbacks cb = callbacks;
                    if (cb != null) {
                        cb.onStateChanged(peerId, "DC_" + ch.getLabel() + "_" + safeEnum(st), detail);
                    }
                } catch (Exception ignore) {
                }
            }

            @Override
            public void onMessage(RTCDataChannelBuffer buffer) {
                if (buffer == null || buffer.data == null) {
                    return;
                }

                ByteBuffer bb = buffer.data;
                byte[] data = new byte[bb.remaining()];
                bb.get(data);

                Callbacks cb = callbacks;
                if (cb == null) {
                    return;
                }

                try {
                    if (isChat && !buffer.binary) {
                        cb.onChatMessage(peerId, new String(data, StandardCharsets.UTF_8));
                    } else {
                        cb.onVpnPacket(peerId, data);
                    }
                } catch (Exception ignore) {
                }
            }
        });
    }

    private CompletableFuture<Void> setLocalDescription(RTCSessionDescription sdp) {
        CompletableFuture<Void> fut = new CompletableFuture<>();
        pc.setLocalDescription(sdp, new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                fut.complete(null);
            }

            @Override
            public void onFailure(String error) {
                fut.completeExceptionally(new IllegalStateException(error == null ? "setLocalDescription failed" : error));
            }
        });
        return fut;
    }

    private CompletableFuture<Void> setRemoteDescription0(RTCSessionDescription sdp) {
        CompletableFuture<Void> fut = new CompletableFuture<>();
        pc.setRemoteDescription(sdp, new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                fut.complete(null);
            }

            @Override
            public void onFailure(String error) {
                fut.completeExceptionally(new IllegalStateException(error == null ? "setRemoteDescription failed" : error));
            }
        });
        return fut;
    }

    private static List<RTCIceServer> buildIceServers(Config cfg) {
        List<RTCIceServer> out = new ArrayList<>();

        if (cfg.stunServers != null) {
            for (String hp : cfg.stunServers) {
                String v = hp == null ? "" : hp.trim();
                if (v.isBlank()) {
                    continue;
                }
                RTCIceServer s = new RTCIceServer();
                s.urls = List.of(v.startsWith("stun:") ? v : ("stun:" + v));
                out.add(s);
            }
        }

        if (cfg.enableTurn && cfg.turnServers != null) {
            for (Config.TurnServer ts : cfg.turnServers) {
                if (ts == null || ts.host == null || ts.host.isBlank()) {
                    continue;
                }
                int port = ts.port <= 0 ? 3478 : ts.port;

                RTCIceServer s = new RTCIceServer();
                // 尽量优先 UDP；必要时可在上层配置 turn:xxx?transport=tcp
                s.urls = List.of("turn:" + ts.host.trim() + ":" + port);
                s.username = ts.username;
                s.password = ts.password;
                out.add(s);
            }
        }

        return out;
    }

    private static String safeEnum(Enum<?> e) {
        return e == null ? "UNKNOWN" : e.name();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            RTCDataChannel c = chat;
            if (c != null) {
                try {
                    c.unregisterObserver();
                } catch (Exception ignore) {
                }
                try {
                    c.close();
                } catch (Exception ignore) {
                }
                try {
                    c.dispose();
                } catch (Exception ignore) {
                }
            }
        } catch (Exception ignore) {
        }

        try {
            RTCDataChannel c = vpn;
            if (c != null) {
                try {
                    c.unregisterObserver();
                } catch (Exception ignore) {
                }
                try {
                    c.close();
                } catch (Exception ignore) {
                }
                try {
                    c.dispose();
                } catch (Exception ignore) {
                }
            }
        } catch (Exception ignore) {
        }

        try {
            pc.close();
        } catch (Exception ignore) {
        }

        state = "CLOSED";
        detail = "-";
    }
}
