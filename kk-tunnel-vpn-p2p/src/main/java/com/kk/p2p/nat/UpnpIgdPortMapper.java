package com.kk.p2p.nat;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 一个“够用且不引入第三方依赖”的 UPnP IGD 端口映射实现（IPv4）。
 *
 * 目的：在家庭路由/公司路由环境中，通过 UPnP IGD 自动把本机的 libp2p TCP 监听端口映射到公网，
 * 从而提高“对端可以直连你”的概率（减少对 Relay 的依赖）。
 *
 * 重要说明：
 * 1) UPnP 是“端口映射”，不是严格意义的“打洞(hole punching)”。
 * 2) 是否生效取决于路由器是否支持且开启 UPnP。
 * 3) 本实现尽量兼容常见 IGD，但不同路由器差异较大，失败时应降级到 relay。
 */
@Slf4j
public final class UpnpIgdPortMapper {

    public static final class MapResult {
        public final boolean success;
        public final String externalIp;
        public final String igdControlUrl;
        public final String serviceType;
        public final String message;

        private MapResult(boolean success, String externalIp, String igdControlUrl, String serviceType, String message) {
            this.success = success;
            this.externalIp = externalIp;
            this.igdControlUrl = igdControlUrl;
            this.serviceType = serviceType;
            this.message = message;
        }

        public static MapResult ok(String externalIp, String controlUrl, String serviceType, String message) {
            return new MapResult(true, externalIp, controlUrl, serviceType, message);
        }

        public static MapResult fail(String message) {
            return new MapResult(false, null, null, null, message);
        }
    }

    private static final String SSDP_ADDR = "239.255.255.250";
    private static final int SSDP_PORT = 1900;

    /**
     * 尝试为 TCP 端口做 UPnP 映射。
     *
     * @param internalClient 本机 LAN IP（例如 192.168.1.23）。必须是路由可达的内网地址
     * @param port           要映射的 TCP 端口
     * @param description    映射描述
     * @param leaseSeconds   租约（秒）。部分路由器忽略该值
     */
    public MapResult mapTcp(String internalClient, int port, String description, int leaseSeconds) {
        if (internalClient == null || internalClient.isBlank()) {
            return MapResult.fail("internalClient is blank");
        }
        if (port <= 0 || port > 65535) {
            return MapResult.fail("invalid port: " + port);
        }

        try {
            String location = discoverIgdLocation(1800);
            if (location == null) {
                return MapResult.fail("no IGD device found via SSDP");
            }

            IgdService svc = parseIgdService(location);
            if (svc == null) {
                return MapResult.fail("IGD device description parsed but no WANIPConnection/WANPPPConnection service");
            }

            // 1) 先获取公网 IP（可选，但便于展示）
            String externalIp = null;
            try {
                externalIp = getExternalIp(svc.controlUrl, svc.serviceType);
            } catch (Exception e) {
                log.debug("UPnP GetExternalIPAddress failed", e);
            }

            // 2) AddPortMapping
            String addResp = addPortMapping(
                    svc.controlUrl,
                    svc.serviceType,
                    port,
                    internalClient,
                    description == null ? "kk-p2p" : description,
                    leaseSeconds
            );

            return MapResult.ok(externalIp, svc.controlUrl, svc.serviceType, addResp);

        } catch (Exception e) {
            return MapResult.fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /**
     * 删除映射（尽力而为）。
     */
    public void deleteTcpMapping(String controlUrl, String serviceType, int port) {
        if (controlUrl == null || controlUrl.isBlank() || serviceType == null || serviceType.isBlank()) {
            return;
        }
        if (port <= 0 || port > 65535) {
            return;
        }

        try {
            deletePortMapping(controlUrl, serviceType, port);
        } catch (Exception e) {
            log.debug("UPnP DeletePortMapping failed", e);
        }
    }

    // -------------------- SSDP 发现 --------------------

    private static String discoverIgdLocation(int timeoutMs) throws Exception {
        String search = "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: " + SSDP_ADDR + ":" + SSDP_PORT + "\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 1\r\n" +
                "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n" +
                "\r\n";

        byte[] req = search.getBytes(StandardCharsets.UTF_8);
        InetSocketAddress dst = new InetSocketAddress(InetAddress.getByName(SSDP_ADDR), SSDP_PORT);

        long deadline = System.currentTimeMillis() + Math.max(500, timeoutMs);

        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setSoTimeout(600);

            DatagramPacket p = new DatagramPacket(req, req.length, dst);
            sock.send(p);

            byte[] buf = new byte[2048];
            DatagramPacket resp = new DatagramPacket(buf, buf.length);

            while (System.currentTimeMillis() < deadline) {
                try {
                    sock.receive(resp);
                } catch (SocketTimeoutException e) {
                    continue;
                }

                String s = new String(resp.getData(), resp.getOffset(), resp.getLength(), StandardCharsets.UTF_8);
                Map<String, String> headers = parseSsdpHeaders(s);
                String location = headers.get("location");
                if (location != null && !location.isBlank()) {
                    return location.trim();
                }
            }
        }

        return null;
    }

    private static Map<String, String> parseSsdpHeaders(String resp) {
        Map<String, String> out = new HashMap<>();
        if (resp == null) {
            return out;
        }

        String[] lines = resp.split("\\r?\\n");
        for (String line : lines) {
            int idx = line.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            String k = line.substring(0, idx).trim().toLowerCase(Locale.ROOT);
            String v = line.substring(idx + 1).trim();
            if (!k.isBlank() && !v.isBlank()) {
                out.put(k, v);
            }
        }
        return out;
    }

    // -------------------- 解析设备描述，定位 controlURL --------------------

    private static final class IgdService {
        final String controlUrl;
        final String serviceType;

        private IgdService(String controlUrl, String serviceType) {
            this.controlUrl = controlUrl;
            this.serviceType = serviceType;
        }
    }

    private static IgdService parseIgdService(String locationUrl) throws Exception {
        String xml = httpGet(locationUrl, 2500);
        if (xml == null || xml.isBlank()) {
            return null;
        }

        // 不引入 XML 解析器（避免额外依赖），用“足够稳”的字符串查找：
        // 兼容 WANIPConnection:1/2 与 WANPPPConnection:1。
        IgdService svc = findService(xml, locationUrl, "urn:schemas-upnp-org:service:WANIPConnection:");
        if (svc != null) {
            return svc;
        }
        return findService(xml, locationUrl, "urn:schemas-upnp-org:service:WANPPPConnection:");
    }

    private static IgdService findService(String xml, String locationUrl, String serviceTypePrefix) throws Exception {
        int idx = 0;
        while (true) {
            int st = xml.indexOf("<service>", idx);
            if (st < 0) {
                return null;
            }
            int ed = xml.indexOf("</service>", st);
            if (ed < 0) {
                return null;
            }

            String block = xml.substring(st, ed);
            String serviceType = extractTag(block, "serviceType");
            if (serviceType != null && serviceType.startsWith(serviceTypePrefix)) {
                String controlUrl = extractTag(block, "controlURL");
                if (controlUrl != null && !controlUrl.isBlank()) {
                    String abs = toAbsoluteUrl(locationUrl, controlUrl.trim());
                    return new IgdService(abs, serviceType.trim());
                }
            }

            idx = ed + 9;
        }
    }

    private static String extractTag(String xml, String tag) {
        String open = "<" + tag + ">";
        String close = "</" + tag + ">";
        int a = xml.indexOf(open);
        if (a < 0) {
            return null;
        }
        int b = xml.indexOf(close, a + open.length());
        if (b < 0) {
            return null;
        }
        return xml.substring(a + open.length(), b).trim();
    }

    private static String toAbsoluteUrl(String base, String maybeRelative) throws Exception {
        URL b = new URL(base);
        URL u = new URL(b, maybeRelative);
        return u.toString();
    }

    // -------------------- SOAP 调用 --------------------

    private static String getExternalIp(String controlUrl, String serviceType) throws Exception {
        String body = "<?xml version=\"1.0\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                "<s:Body>" +
                "<u:GetExternalIPAddress xmlns:u=\"" + serviceType + "\"/>" +
                "</s:Body>" +
                "</s:Envelope>";

        String resp = soap(controlUrl, serviceType, "GetExternalIPAddress", body);
        // 极简解析：找 NewExternalIPAddress
        String ip = findBetween(resp, "<NewExternalIPAddress>", "</NewExternalIPAddress>");
        return (ip == null || ip.isBlank()) ? null : ip.trim();
    }

    private static String addPortMapping(String controlUrl, String serviceType, int port, String internalClient, String description, int leaseSeconds) throws Exception {
        String body = "<?xml version=\"1.0\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                "<s:Body>" +
                "<u:AddPortMapping xmlns:u=\"" + serviceType + "\">" +
                "<NewRemoteHost></NewRemoteHost>" +
                "<NewExternalPort>" + port + "</NewExternalPort>" +
                "<NewProtocol>TCP</NewProtocol>" +
                "<NewInternalPort>" + port + "</NewInternalPort>" +
                "<NewInternalClient>" + escapeXml(internalClient) + "</NewInternalClient>" +
                "<NewEnabled>1</NewEnabled>" +
                "<NewPortMappingDescription>" + escapeXml(description) + "</NewPortMappingDescription>" +
                "<NewLeaseDuration>" + Math.max(0, leaseSeconds) + "</NewLeaseDuration>" +
                "</u:AddPortMapping>" +
                "</s:Body>" +
                "</s:Envelope>";

        return soap(controlUrl, serviceType, "AddPortMapping", body);
    }

    private static String deletePortMapping(String controlUrl, String serviceType, int port) throws Exception {
        String body = "<?xml version=\"1.0\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                "<s:Body>" +
                "<u:DeletePortMapping xmlns:u=\"" + serviceType + "\">" +
                "<NewRemoteHost></NewRemoteHost>" +
                "<NewExternalPort>" + port + "</NewExternalPort>" +
                "<NewProtocol>TCP</NewProtocol>" +
                "</u:DeletePortMapping>" +
                "</s:Body>" +
                "</s:Envelope>";

        return soap(controlUrl, serviceType, "DeletePortMapping", body);
    }

    private static String soap(String controlUrl, String serviceType, String action, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(controlUrl).openConnection();
        conn.setConnectTimeout(2500);
        conn.setReadTimeout(3500);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
        conn.setRequestProperty("SOAPAction", "\"" + serviceType + "#" + action + "\"");

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));

        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String resp = readAll(is);

        if (code < 200 || code >= 300) {
            throw new RuntimeException("UPnP SOAP failed: HTTP " + code + ": " + resp);
        }

        return resp;
    }

    private static String httpGet(String url, int timeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(Math.max(300, timeoutMs));
        conn.setReadTimeout(Math.max(500, timeoutMs));
        conn.setRequestMethod("GET");
        int code = conn.getResponseCode();
        if (code != 200) {
            throw new RuntimeException("UPnP device description HTTP " + code);
        }
        try (InputStream is = conn.getInputStream()) {
            return readAll(is);
        }
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) {
            return "";
        }
        try (InputStream in = is; ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toString(StandardCharsets.UTF_8);
        }
    }

    private static String findBetween(String s, String a, String b) {
        if (s == null) {
            return null;
        }
        int i = s.indexOf(a);
        if (i < 0) {
            return null;
        }
        int j = s.indexOf(b, i + a.length());
        if (j < 0) {
            return null;
        }
        return s.substring(i + a.length(), j);
    }

    private static String escapeXml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
