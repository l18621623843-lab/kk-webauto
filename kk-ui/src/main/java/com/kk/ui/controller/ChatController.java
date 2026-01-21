package com.kk.ui.controller;

import com.kk.p2p.engine.Libp2pEngine;
import com.kk.tunnel.vpn.service.WintunService;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


@Component
@RequiredArgsConstructor
public class ChatController extends BaseController {

    private final Libp2pEngine libp2pEngine;
    private final WintunService wintunService;

    @Value("${kk.p2p.connect.mode:AUTO}")
    private String connectModeDefault;

    @FXML private TextField selfPeerIdField;
    @FXML private TextField remotePeerIdField;
    @FXML private TextField remoteMultiaddrField;
    @FXML private ComboBox<String> connectModeCombo;
    @FXML private Label bootstrapStatusLabel;
    @FXML private Label statusLabel;
    @FXML private Label googleStatusLabel;
    @FXML private Label vpnTrafficLabel;

    @FXML private CheckBox vpnEnabledCheck;
    @FXML private Label vpnStatusLabel;
    @FXML private TextField vpnIpField;
    @FXML private TextField vpnMaskField;
    @FXML private ComboBox<String> vpnTargetPeerIdCombo;
    @FXML private Label vpnTargetStatusLabel;

    @FXML private TextArea selfAddrsArea;
    @FXML private TextField relayMultiaddrField;
    @FXML private Label relayStatusLabel;
    @FXML private TextArea relayAddrsArea;

    @FXML private Label mdnsStatusLabel;
    @FXML private Label stunStatusLabel;


    @FXML private TextArea chatHistoryArea;
    @FXML private TextField messageInputField;

    @FXML private TextArea settingsArea;


    private volatile String currentRemotePeerId;

    private volatile long lastVpnTxBytes;
    private volatile long lastVpnRxBytes;
    private volatile long lastVpnTrafficTsNanos;

    private final ExecutorService httpExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "kk-ui-http");
        t.setDaemon(true);
        return t;
    });

    private final HttpClient httpClient = HttpClient.newBuilder()
            .executor(httpExecutor)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "kk-ui-scheduler");
        t.setDaemon(true);
        return t;
    });

    @FXML
    public void initialize() {
        if (statusLabel != null) {
            statusLabel.setText("就绪");
        }
        if (chatHistoryArea != null) {
            chatHistoryArea.setEditable(false);
        }

        libp2pEngine.setChatMessageListener((fromPeerId, msg) ->
                appendLog(chatHistoryArea, fromPeerId + ": " + msg)
        );

        initConnectMode();
        refreshBootstrapStatus(false);

        refreshSelfPeerId();
        refreshShareAddrs(false);
        refreshRelayStatus(false);
        refreshMdnsStatus(false);
        refreshStunStatus(false);

        scheduler.scheduleAtFixedRate(() -> refreshShareAddrs(false), 2, 5, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(() -> refreshRelayStatus(false), 2, 5, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(() -> refreshMdnsStatus(false), 2, 5, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(() -> refreshStunStatus(false), 2, 5, TimeUnit.SECONDS);


        // 启动后自动检测一次，并定时刷新状态

        if (googleStatusLabel != null) {
            googleStatusLabel.setText("检测中...");
        }
        checkGoogleConnectivity(false);
        scheduler.scheduleAtFixedRate(() -> checkGoogleConnectivity(false), 10, 15, TimeUnit.SECONDS);

        if (vpnTrafficLabel != null) {
            vpnTrafficLabel.setText("-");
        }
        updateVpnTraffic(false);
        scheduler.scheduleAtFixedRate(() -> updateVpnTraffic(false), 1, 1, TimeUnit.SECONDS);

        initVpnPanel();
        initSettingsPanel();
        scheduler.scheduleAtFixedRate(() -> refreshVpnStatus(false), 1, 1, TimeUnit.SECONDS);

        scheduler.scheduleAtFixedRate(() -> refreshBootstrapStatus(false), 2, 5, TimeUnit.SECONDS);
    }

    private void refreshSelfPeerId() {
        String id = libp2pEngine.getSelfPeerId();
        if (id != null && !id.isBlank()) {
            if (selfPeerIdField != null) {
                selfPeerIdField.setText(id);
            }
            appendLog(chatHistoryArea, "本机 PeerID: " + id);
            return;
        }

        if (selfPeerIdField != null) {
            selfPeerIdField.setText("启动中...");
        }

        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).thenRun(() -> Platform.runLater(this::refreshSelfPeerId));
    }

    @FXML
    private void handleCopySelfPeerId() {
        String id = selfPeerIdField == null ? null : selfPeerIdField.getText();
        if (id == null || id.isBlank() || "启动中...".equals(id)) {
            showWarning("提示", "PeerID 尚未就绪");
            return;
        }

        ClipboardContent content = new ClipboardContent();
        content.putString(id);
        Clipboard.getSystemClipboard().setContent(content);

        appendLog(chatHistoryArea, "已复制本机 PeerID");
    }

    private void initConnectMode() {
        if (connectModeCombo == null) {
            return;
        }

        connectModeCombo.getItems().setAll("AUTO", "MANUAL_ADDR", "PEERID_ONLY");

        String def = connectModeDefault == null ? "AUTO" : connectModeDefault.trim().toUpperCase();
        if (!connectModeCombo.getItems().contains(def)) {
            def = "AUTO";
        }
        connectModeCombo.getSelectionModel().select(def);
    }

    private String getSelectedConnectMode() {
        if (connectModeCombo == null) {
            return (connectModeDefault == null || connectModeDefault.isBlank()) ? "AUTO" : connectModeDefault.trim().toUpperCase();
        }
        String v = connectModeCombo.getValue();
        return (v == null || v.isBlank()) ? "AUTO" : v.trim().toUpperCase();
    }

    @FXML
    private void handleBootstrapNow() {
        libp2pEngine.bootstrapNow();
        refreshBootstrapStatus(true);
        appendLog(chatHistoryArea, "已触发 bootstrap 连接");
    }

    @FXML
    private void handleRefreshBootstrap() {
        refreshBootstrapStatus(true);
    }

    private void refreshBootstrapStatus(boolean writeLog) {
        boolean enabled = libp2pEngine.isBootstrapEnabled();
        int attempted = libp2pEngine.getBootstrapAttempted();
        int connected = libp2pEngine.getBootstrapConnected();
        String lastErr = libp2pEngine.getLastBootstrapError();

        String text = (enabled ? "启用" : "禁用") + " | 已连 " + connected + "/" + attempted + (lastErr == null || lastErr.isBlank() ? "" : (" | 最近错误: " + lastErr));

        runOnUIThread(() -> {
            if (bootstrapStatusLabel != null) {
                bootstrapStatusLabel.setText(text);
            }
            if (writeLog) {
                appendLog(chatHistoryArea, "Bootstrap 状态: " + text);
            }
        });
    }

    private void refreshShareAddrs(boolean writeLog) {
        List<String> addrs = libp2pEngine.getShareAddrs();
        String text = addrs.isEmpty() ? "-" : String.join("\n", addrs);

        runOnUIThread(() -> {
            if (selfAddrsArea != null) {
                selfAddrsArea.setText(text);
            }
            if (writeLog) {
                appendLog(chatHistoryArea, "可分享地址已刷新");
            }
        });
    }

    @FXML
    private void handleCopyShareAddrs() {
        String text = selfAddrsArea == null ? null : selfAddrsArea.getText();
        if (text == null || text.isBlank() || "-".equals(text)) {
            showWarning("提示", "当前没有可分享地址");
            return;
        }

        ClipboardContent content = new ClipboardContent();
        content.putString(text.trim());
        Clipboard.getSystemClipboard().setContent(content);
        appendLog(chatHistoryArea, "已复制可分享地址");
    }

    @FXML
    private void handleRelayReserveNow() {
        String relay = relayMultiaddrField == null ? null : relayMultiaddrField.getText();
        if (relay == null || relay.isBlank()) {
            libp2pEngine.relayReserveNow();
            refreshRelayStatus(true);
            return;
        }

        String relayAddr = relay.trim();
        if (relayStatusLabel != null) {
            relayStatusLabel.setText("预约中...");
        }

        libp2pEngine.reserveRelay(relayAddr)
                .thenRun(() -> runOnUIThread(() -> {
                    refreshRelayStatus(true);
                    appendLog(chatHistoryArea, "Relay 预约成功: " + relayAddr);
                }))
                .exceptionally(ex -> {
                    runOnUIThread(() -> {
                        refreshRelayStatus(false);
                        showError("Relay 预约失败", ex == null ? "unknown" : (ex.getMessage() == null ? String.valueOf(ex) : ex.getMessage()));
                    });
                    return null;
                });
    }

    @FXML
    private void handleRefreshRelay() {
        refreshRelayStatus(true);
    }

    private void refreshRelayStatus(boolean writeLog) {
        boolean enabled = libp2pEngine.isRelayEnabled();
        int attempted = libp2pEngine.getRelayAttempted();
        int reserved = libp2pEngine.getRelayReserved();
        String lastErr = libp2pEngine.getLastRelayError();

        String text = (enabled ? "启用" : "禁用") + " | 已预约 " + reserved + "/" + attempted + (lastErr == null || lastErr.isBlank() ? "" : (" | 最近错误: " + lastErr));

        List<String> relayAddrs = libp2pEngine.getRelayShareAddrs();
        String relayAddrText = relayAddrs.isEmpty() ? "-" : String.join("\n", relayAddrs);

        runOnUIThread(() -> {
            if (relayStatusLabel != null) {
                relayStatusLabel.setText(text);
            }
            if (relayAddrsArea != null) {
                relayAddrsArea.setText(relayAddrText);
            }
            if (writeLog) {
                appendLog(chatHistoryArea, "Relay 状态: " + text);
            }
        });
    }

    @FXML
    private void handleCopyRelayAddrs() {
        String text = relayAddrsArea == null ? null : relayAddrsArea.getText();
        if (text == null || text.isBlank() || "-".equals(text)) {
            showWarning("提示", "当前没有 Relay 地址（请先预约）");
            return;
        }

        ClipboardContent content = new ClipboardContent();
        content.putString(text.trim());
        Clipboard.getSystemClipboard().setContent(content);
        appendLog(chatHistoryArea, "已复制 Relay 地址");
    }

    @FXML
    private void handleRefreshMdns() {
        refreshMdnsStatus(true);
    }

    private void refreshMdnsStatus(boolean writeLog) {
        boolean enabled = libp2pEngine.isMdnsEnabled();
        int found = libp2pEngine.getMdnsPeersFound();
        String last = libp2pEngine.getLastMdnsPeer();

        String text = (enabled ? "启用" : "禁用") + " | 已发现 " + found + (last == null || last.isBlank() ? "" : (" | 最近: " + last));

        runOnUIThread(() -> {
            if (mdnsStatusLabel != null) {
                mdnsStatusLabel.setText(text);
            }
            if (writeLog) {
                appendLog(chatHistoryArea, "mDNS 状态: " + text);
            }
        });
    }

    @FXML
    private void handleRefreshStun() {
        refreshStunStatus(true);
    }

    private void refreshStunStatus(boolean writeLog) {
        boolean enabled = libp2pEngine.isStunEnabled();
        int attempted = libp2pEngine.getStunAttempted();
        int ok = libp2pEngine.getStunSucceeded();
        String last = libp2pEngine.getLastStunResult();
        String lastErr = libp2pEngine.getLastStunError();

        String detail;
        if (last != null && !last.isBlank()) {
            detail = last;
        } else if (lastErr != null && !lastErr.isBlank()) {
            detail = "错误: " + lastErr;
        } else {
            detail = "-";
        }

        String text = (enabled ? "启用" : "禁用") + " | 成功 " + ok + "/" + attempted + " | " + detail;

        runOnUIThread(() -> {
            if (stunStatusLabel != null) {
                stunStatusLabel.setText(text);
            }
            if (writeLog) {
                appendLog(chatHistoryArea, "STUN 状态: " + text);
            }
        });
    }

    @FXML
    private void handleConnect() {


        String peerId = remotePeerIdField == null ? null : remotePeerIdField.getText();
        if (peerId == null || peerId.isBlank()) {
            showWarning("提示", "请先输入对方 PeerID");
            return;
        }

        String addr = remoteMultiaddrField == null ? null : remoteMultiaddrField.getText();
        String mode = getSelectedConnectMode();

        String addrToUse;
        switch (mode) {
            case "MANUAL_ADDR" -> {
                if (addr == null || addr.isBlank()) {
                    showWarning("提示", "当前为 MANUAL_ADDR，需要填写对方 multiaddr");
                    return;
                }
                addrToUse = addr.trim();
            }
            case "PEERID_ONLY" -> addrToUse = null;
            case "AUTO" -> addrToUse = (addr == null || addr.isBlank()) ? null : addr.trim();
            default -> addrToUse = (addr == null || addr.isBlank()) ? null : addr.trim();
        }

        currentRemotePeerId = peerId.trim();
        if (statusLabel != null) {
            statusLabel.setText("连接中... (" + mode + ")");
        }

        libp2pEngine.connectChatToPeer(currentRemotePeerId, addrToUse)
                .thenRun(() -> runOnUIThread(() -> {
                    if (statusLabel != null) {
                        statusLabel.setText("已连接: " + currentRemotePeerId);
                    }
                    appendLog(chatHistoryArea, "已建立连接: " + currentRemotePeerId + " (" + mode + ")");

                    // VPN 目标 PeerID 下拉可快速选择“最近连接的对端”
                    if (vpnTargetPeerIdCombo != null && currentRemotePeerId != null && !currentRemotePeerId.isBlank()) {
                        if (!vpnTargetPeerIdCombo.getItems().contains(currentRemotePeerId)) {
                            vpnTargetPeerIdCombo.getItems().add(currentRemotePeerId);
                        }
                    }
                }))
                .exceptionally(ex -> {
                    runOnUIThread(() -> {
                        if (statusLabel != null) {
                            statusLabel.setText("连接失败");
                        }

                        String msg = ex == null ? null : ex.getMessage();
                        if (msg != null && msg.contains("no known multiaddrs")) {
                            msg = "未发现对端地址：请让对方提供 multiaddr（或先互连一次使地址进入缓存/PEX），否则仅 PeerID 无法直连";
                        } else if (msg != null && msg.contains("all dial attempts failed")) {
                            msg = "直连失败：请确认对方地址可达；若两端处于复杂 NAT 环境，建议开启 Relay，并让对方把 /p2p-circuit 地址发给你作为兜底连接地址";
                        } else if (msg != null && (msg.contains("Timeout") || msg.contains("timed out") || msg.contains("timeout"))) {
                            msg = "连接超时：可尝试调大 kk.p2p.dial.perAddrTimeoutMs / totalTimeoutMs，或使用对方的 /p2p-circuit 地址兜底";
                        }
                        showError("连接失败", msg == null ? String.valueOf(ex) : msg);
                    });
                    return null;
                });
    }

    @FXML
    private void handleCheckGoogle() {
        if (googleStatusLabel != null) {
            googleStatusLabel.setText("检测中...");
        }
        checkGoogleConnectivity(true);
    }

    private static final URI[] GOOGLE_PROBES = new URI[]{
            URI.create("https://www.google.com/generate_204"),
            URI.create("https://www.gstatic.com/generate_204"),
            URI.create("http://www.gstatic.com/generate_204")
    };

    private void checkGoogleConnectivity(boolean writeLog) {
        probeGoogleAny(0, null).thenAccept(text -> runOnUIThread(() -> {
            if (googleStatusLabel != null) {
                googleStatusLabel.setText(text);
            }
            if (writeLog) {
                appendLog(chatHistoryArea, "Google 公网检测: " + text);
            }
        }));
    }

    private CompletableFuture<String> probeGoogleAny(int idx, String lastFailText) {
        if (idx >= GOOGLE_PROBES.length) {
            return CompletableFuture.completedFuture(lastFailText == null ? "不可用" : lastFailText);
        }

        URI uri = GOOGLE_PROBES[idx];
        long start = System.nanoTime();

        HttpRequest req = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        return httpClient.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                .orTimeout(6, TimeUnit.SECONDS)
                .handle((resp, ex) -> {
                    if (ex != null) {
                        String msg = ex.getMessage();
                        String detail = ex.getClass().getSimpleName() + (msg == null || msg.isBlank() ? "" : (": " + msg));
                        return "不可用(" + uri.getHost() + "): " + detail;
                    }

                    long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                    int code = resp.statusCode();
                    boolean ok = (code == 204 || code == 200);
                    return ok ? ("已连接 (" + code + ", " + ms + "ms)") : ("不可用(" + uri.getHost() + "): HTTP " + code);
                })
                .thenCompose(text -> text.startsWith("已连接")
                        ? CompletableFuture.completedFuture(text)
                        : probeGoogleAny(idx + 1, text));
    }

    @FXML
    private void handleRefreshTraffic() {
        updateVpnTraffic(true);
    }

    private void updateVpnTraffic(boolean writeLog) {
        long tx = libp2pEngine.getVpnTxBytesTotal();
        long rx = libp2pEngine.getVpnRxBytesTotal();

        long now = System.nanoTime();
        long lastTs = lastVpnTrafficTsNanos;
        if (lastTs == 0L) {
            lastVpnTrafficTsNanos = now;
            lastVpnTxBytes = tx;
            lastVpnRxBytes = rx;
        }

        long dtNanos = Math.max(1, now - lastVpnTrafficTsNanos);
        double dtSeconds = dtNanos / 1_000_000_000.0;

        long dTx = Math.max(0, tx - lastVpnTxBytes);
        long dRx = Math.max(0, rx - lastVpnRxBytes);

        long txRate = (long) Math.floor(dTx / dtSeconds);
        long rxRate = (long) Math.floor(dRx / dtSeconds);

        lastVpnTrafficTsNanos = now;
        lastVpnTxBytes = tx;
        lastVpnRxBytes = rx;

        String text = "↑ " + formatBytes(txRate) + "/s (总 " + formatBytes(tx) + ")  ↓ " + formatBytes(rxRate) + "/s (总 " + formatBytes(rx) + ")";

        runOnUIThread(() -> {
            if (vpnTrafficLabel != null) {
                vpnTrafficLabel.setText(text);
            }
            if (writeLog) {
                appendLog(chatHistoryArea, "VPN 流量: " + text);
            }
        });
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0) bytes = 0;
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format("%.2f KB", kb);
        }
        double mb = kb / 1024.0;
        return String.format("%.2f MB", mb);
    }

    private void initVpnPanel() {
        runOnUIThread(() -> {
            String ip = wintunService.getVipAddress();
            String mask = wintunService.getVipMask();

            if (vpnIpField != null && ip != null && !ip.isBlank()) {
                vpnIpField.setText(ip);
            }
            if (vpnMaskField != null && mask != null && !mask.isBlank()) {
                vpnMaskField.setText(mask);
            }

            String self = libp2pEngine.getSelfPeerId();
            String configuredTarget = wintunService.getTargetPeerId();

            if (vpnTargetPeerIdCombo != null) {
                vpnTargetPeerIdCombo.getItems().clear();
                if (self != null && !self.isBlank()) {
                    vpnTargetPeerIdCombo.getItems().add(self);
                }
                if (configuredTarget != null && !configuredTarget.isBlank() && !vpnTargetPeerIdCombo.getItems().contains(configuredTarget)) {
                    vpnTargetPeerIdCombo.getItems().add(configuredTarget);
                }

                // 默认目标：优先配置值，其次本机 PeerID（便于测试/自环），也可手动改成其他 peer
                String def = (configuredTarget != null && !configuredTarget.isBlank()) ? configuredTarget : self;
                if (def != null && !def.isBlank()) {
                    vpnTargetPeerIdCombo.setValue(def);
                }
            }
        });

        refreshVpnStatus(false);
    }

    private void refreshVpnStatus(boolean writeLog) {
        boolean running = wintunService.isRunning();
        String ip = wintunService.getVipAddress();
        String mask = wintunService.getVipMask();
        String target = wintunService.getTargetPeerId();

        String text = running
                ? ("运行中 | " + (ip == null ? "" : ip) + "/" + (mask == null ? "" : mask))
                : "未启用";

        String targetText;
        if (target != null && !target.isBlank()) {
            targetText = "已指定: " + target;
        } else {
            targetText = "未指定";
        }

        runOnUIThread(() -> {
            if (vpnEnabledCheck != null) {
                vpnEnabledCheck.setSelected(running);
            }
            if (vpnStatusLabel != null) {
                vpnStatusLabel.setText(text);
            }
            if (vpnTargetStatusLabel != null) {
                vpnTargetStatusLabel.setText(targetText);
            }
            if (writeLog) {
                appendLog(chatHistoryArea, "VPN 状态: " + text + " | " + targetText);
            }
        });
    }

    @FXML
    private void handleApplyVpnIpMask() {
        String ip = vpnIpField == null ? null : vpnIpField.getText();
        String mask = vpnMaskField == null ? null : vpnMaskField.getText();

        if (ip == null || ip.isBlank() || mask == null || mask.isBlank()) {
            showWarning("提示", "请填写虚拟 IP 和 Mask");
            return;
        }

        try {
            wintunService.applyIpConfig(ip.trim(), mask.trim());
            refreshVpnStatus(true);
        } catch (Exception e) {
            showError("应用 IP 配置失败", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    @FXML
    private void handleToggleVpn() {
        boolean enable = vpnEnabledCheck != null && vpnEnabledCheck.isSelected();

        if (enable) {
            try {
                // 若 UI 中填写了 IP/Mask，则先应用再启动
                if (vpnIpField != null && vpnMaskField != null) {
                    String ip = vpnIpField.getText();
                    String mask = vpnMaskField.getText();
                    if (ip != null && !ip.isBlank() && mask != null && !mask.isBlank()) {
                        wintunService.applyIpConfig(ip.trim(), mask.trim());
                    }
                }

                // 若 UI 中填写了目标 PeerID，则先设置
                if (vpnTargetPeerIdCombo != null) {
                    String target = vpnTargetPeerIdCombo.getValue();
                    if (target != null && !target.isBlank()) {
                        wintunService.setTargetPeerId(target.trim());
                    }
                }

                wintunService.start();
                refreshVpnStatus(true);
            } catch (Exception e) {
                runOnUIThread(() -> {
                    if (vpnEnabledCheck != null) {
                        vpnEnabledCheck.setSelected(false);
                    }
                    refreshVpnStatus(false);
                    showError("VPN 启动失败", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                });
            }
            return;
        }

        try {
            wintunService.stop();
        } catch (Exception ignore) {
        }
        refreshVpnStatus(true);
    }

    @FXML
    private void handleApplyVpnTarget() {
        String peerId = vpnTargetPeerIdCombo == null ? null : vpnTargetPeerIdCombo.getValue();
        if (peerId == null || peerId.isBlank()) {
            showWarning("提示", "请填写/选择目标 PeerID（将网卡流量发给谁）");
            return;
        }

        String v = peerId.trim();
        wintunService.setTargetPeerId(v);

        if (vpnTargetPeerIdCombo != null && !vpnTargetPeerIdCombo.getItems().contains(v)) {
            vpnTargetPeerIdCombo.getItems().add(v);
        }

        refreshVpnStatus(true);
        appendLog(chatHistoryArea, "VPN 目标 PeerID 已设置为: " + v);
    }


    private void initSettingsPanel() {
        String text = buildSettingsText();
        runOnUIThread(() -> {
            if (settingsArea != null) {
                settingsArea.setText(text);
            }
        });
    }

    private static String buildSettingsText() {
        return "# ========== 基础 ==========" + "\n" +
                "kk.p2p.connect.mode: AUTO" + "\n" +
                "# AUTO: 有 multiaddr 就用；没有则仅用 PeerID（依赖已缓存地址/发现能力）" + "\n" +
                "# MANUAL_ADDR: 必须提供对方 multiaddr（最稳定）" + "\n" +
                "# PEERID_ONLY: 只用 PeerID（需要你已缓存到对端地址）" + "\n\n" +
                "# ========== 拨号策略（直连优先，失败再尝试 /p2p-circuit） ==========" + "\n" +
                "kk.p2p.dial.perAddrTimeoutMs: 2500" + "\n" +
                "kk.p2p.dial.totalTimeoutMs: 15000" + "\n" +
                "kk.p2p.dial.preferDirect: true" + "\n" +
                "kk.p2p.dial.triggerRelayReserveOnFail: true" + "\n\n" +
                "# ========== Relay（内网穿透兜底） ==========" + "\n" +
                "kk.p2p.relay.enabled: false" + "\n" +
                "kk.p2p.relay.mode: CLIENT   # CLIENT / HOP" + "\n" +
                "kk.p2p.relay.addrs: \"/ip4/<relay-ip>/tcp/<port>/p2p/<relayPeerId>\"" + "\n" +
                "# 预约成功后，会生成 /p2p-circuit 地址，复制发给对方即可连接你。" + "\n\n" +
                "# ========== 发现机制 ==========" + "\n" +
                "kk.p2p.discovery.mdns.enabled: true" + "\n\n" +
                "# ========== NAT 直连增强（优先减少对 Relay 的依赖） ==========" + "\n" +
                "kk.p2p.nat.upnp.enabled: false" + "\n" +
                "kk.p2p.nat.upnp.leaseSeconds: 3600" + "\n" +
                "kk.p2p.nat.upnp.renewIntervalSeconds: 900" + "\n" +
                "# UPnP 是端口映射：路由器支持且开启时，能显著提高‘对端直连你’的概率。" + "\n\n" +
                "kk.p2p.nat.stun.enabled: false" + "\n" +
                "# STUN 仅用于探测公网 UDP 映射（帮助判断网络环境），不保证 TCP 直连。" + "\n\n" +
                "# ========== ICE/STUN/TURN（UDP 打洞数据面） ==========" + "\n" +
                "kk.p2p.ice.enabled: true" + "\n" +
                "kk.p2p.ice.prefer.chat: true" + "\n" +
                "kk.p2p.ice.prefer.vpn: true" + "\n" +
                "kk.p2p.ice.gatherTimeoutMs: 2500" + "\n" +
                "kk.p2p.ice.connectTimeoutMs: 9000" + "\n" +
                "kk.p2p.ice.port.min: 0" + "\n" +
                "kk.p2p.ice.port.max: 0" + "\n" +
                "kk.p2p.ice.stun.servers: \"stun.l.google.com:19302\"" + "\n" +
                "kk.p2p.ice.turn.enabled: false" + "\n" +
                "kk.p2p.ice.turn.servers: \"turn.example.com:3478|user|pass\"" + "\n" +
                "# ICE 需要双方在线交换 offer/answer；成功后 Chat/VPN 会优先走 UDP（更像‘打洞直连’）。" + "\n\n" +
                "# ========== 虚拟 VPN（Windows/Wintun） ==========" + "\n" +
                "vpn.enabled: false" + "\n" +
                "vpn.ip.address: 10.8.0.3" + "\n" +
                "vpn.ip.mask: 255.255.255.0" + "\n" +
                "vpn.target.peerid: \"\"" + "\n\n" +
                "# Windows 路由示例（让特定目标走 KK-VPN 网卡）：" + "\n" +
                "#   route add <对端虚拟IP> mask 255.255.255.255 <本机虚拟IP>" + "\n" +
                "# 例如两端分别是 10.8.0.2 与 10.8.0.3：" + "\n" +
                "#   A(10.8.0.2) 上: route add 10.8.0.3 mask 255.255.255.255 10.8.0.2" + "\n" +
                "#   B(10.8.0.3) 上: route add 10.8.0.2 mask 255.255.255.255 10.8.0.3" + "\n";
    }

    @FXML
    private void handleCopySettings() {
        String text = settingsArea == null ? null : settingsArea.getText();
        if (text == null || text.isBlank()) {
            showWarning("提示", "没有可复制的配置内容");
            return;
        }

        ClipboardContent content = new ClipboardContent();
        content.putString(text.trim());
        Clipboard.getSystemClipboard().setContent(content);
        appendLog(chatHistoryArea, "已复制配置说明");
    }

    @FXML
    private void handleSend() {
        String peerId = (currentRemotePeerId == null || currentRemotePeerId.isBlank())
                ? (remotePeerIdField == null ? null : remotePeerIdField.getText())
                : currentRemotePeerId;

        if (peerId == null || peerId.isBlank()) {
            showWarning("提示", "请先输入对方 PeerID 或先连接");
            return;
        }

        String msg = messageInputField == null ? null : messageInputField.getText();
        if (msg == null || msg.isBlank()) {
            return;
        }

        String trimmedPeerId = peerId.trim();
        String trimmedMsg = msg.trim();

        if (messageInputField != null) {
            messageInputField.clear();
        }

        appendLog(chatHistoryArea, "我: " + trimmedMsg);

        libp2pEngine.sendChatMessage(trimmedPeerId, trimmedMsg)
                .exceptionally(ex -> {
                    runOnUIThread(() -> showError("发送失败", ex.getMessage() == null ? String.valueOf(ex) : ex.getMessage()));
                    return null;
                });
    }

    @PreDestroy
    public void shutdown() {
        try {
            scheduler.shutdownNow();
        } catch (Exception ignore) {
        }

        try {
            httpExecutor.shutdownNow();
        } catch (Exception ignore) {
        }
    }
}
