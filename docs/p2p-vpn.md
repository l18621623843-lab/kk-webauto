# KK-P2P + 虚拟 VPN 使用文档（直连优先，Relay 兜底）

> 适用：Windows（Wintun）+ Java 21 + 本项目 `kk-platform`。

## 1. 功能概览

本项目提供两类能力：

1) **P2P 通信**（libp2p / TCP）
- 局域网自动发现：mDNS
- 地址交换：PEX（自定义协议，用于交换可拨号 multiaddr）
- 直连优先：优先尝试非 `/p2p-circuit` 地址
- 直连增强（可选）：UPnP IGD 端口映射（家庭路由常见，能显著提高“对端直连你”的概率）
- Relay 兜底：circuit-relay v2（`/p2p-circuit`）
- Bootstrap（可选）：连接公网 bootstrap（主要用于保持公网连接/Identify 辅助判断网络环境；不是“万能发现”）

2) **虚拟 VPN（可选开关）**
- Windows 使用 **Wintun** 创建虚拟网卡 `KK-VPN`
- 从虚拟网卡抓取出站 IP 包 → 通过 P2P 的 `VPN_PROTOCOL` 发送给目标 Peer
- 从 P2P 接收 IP 包 → 写入本机虚拟网卡

> 注意：启用 VPN 通常需要管理员权限（创建网卡/配置 IP）。

---

## 2. 关键概念（必须理解）

### 2.1 multiaddr 与 PeerID
- `PeerID`：节点身份标识（本项目会持久化私钥，保证 PeerID 稳定）
- `multiaddr`：可拨号地址，典型：
  - 直连：`/ip4/192.168.1.10/tcp/4001/p2p/<peerId>`
  - Relay：`/ip4/<relay-ip>/tcp/<port>/p2p/<relayPeerId>/p2p-circuit/p2p/<targetPeerId>`

### 2.2 “为什么直连不行就要 Relay？”
- 两端都在 NAT 后面且无法互相入站时，纯 TCP 直连可能失败。
- Relay 是一种兜底：让对方通过一个“公网可达”的节点把连接转发过来。

本项目策略：
- **能直连就直连**（局域网/mDNS/已缓存地址/UPnP 映射）
- **实在不行**，使用对方提供的 `/p2p-circuit` 地址兜底。

### 2.3 直连是否需要双方同时发起连接？
- **不需要**。
  - 当前实现是标准的 TCP 模式：应用启动后会在本机监听端口，另一端只要拿到你的可拨号地址就可以主动拨号连接你。
- 但如果两端都在严格 NAT 后且都不具备入站能力，则可能出现“谁也打不进来”。这种情况下需要：
  - UPnP/NAT-PMP 端口映射（推荐先试），或
  - 使用 relay 兜底（稳定方案），或
  - 采用基于 UDP 的 WebRTC（ICE/STUN/TURN + DataChannel）打洞方案（见 8.4 说明）。

---

## 3. 界面操作（按标签页）

### 3.1 聊天（第一个标签）
- 上方是聊天历史
- 下方输入框回车发送，或点“发送”按钮

### 3.2 连接
- 填“对方 PeerID”后点“连接”
- “对方地址”可选（推荐填，对直连成功率最高）
- 连接方式：
  - `AUTO`：有地址就用；没地址就仅 PeerID（依赖缓存/发现）
  - `MANUAL_ADDR`：必须填对方地址（推荐）
  - `PEERID_ONLY`：仅 PeerID（需要你已缓存到对方地址）

### 3.3 地址 / Relay
- “可分享地址”：把它发给对方用于直连你（直连地址 + 已生成的 relay 地址）
- 配置/填写 relay 节点后点“预约”，成功后会生成你的 `/p2p-circuit` 地址
- 如果对方无法直连你：把“Relay 地址”发给对方即可

### 3.4 VPN
- “虚拟IP / Mask”：在 UI 里填写并点“应用”（运行中也会重新 `netsh` 配置）
- “目标 PeerID”：默认会放入本机 PeerID（便于测试/自环），也会自动加入“最近连接的对端”，可手动改成任意 PeerID
- 勾选“启用”会启动 Wintun 网卡与读循环（需要管理员权限）
- “指定状态/运行状态”每 1 秒自动刷新

### 3.5 设置
- 展示常用配置片段（可复制到 `application.yml`）

---

## 4. 使用方式（推荐流程）

### 4.1 两端在同一局域网（最简单）
1. 两端都启动应用
2. 在“连接”页填写对方 PeerID → 点“连接”
3. 如果 mDNS/PEX 已学习到对方地址，可直接连上

更稳的方式（推荐）：
- 让对方在“地址 / Relay”页复制“可分享地址”里的一条 **直连地址**，你粘贴到“对方地址”再连接

### 4.2 两端跨公网/复杂 NAT（尽量直连，失败再 Relay）
先尝试“直连增强”：
1. 在 `application.yml` 开启 UPnP：`kk.p2p.nat.upnp.enabled=true`
2. 确保路由器开启 UPnP
3. 重启应用后观察日志（会打印 UPnP 成功/失败）
4. 仍然建议交换直连地址尝试

如果仍失败（稳定兜底）：
1. 准备一个 **公网可达的 Relay 节点**（见第 5 章）
2. 两端都启用 relay 并预约（`kk.p2p.relay.enabled=true`）
3. 两端互相交换各自生成的“Relay 地址（/p2p-circuit）”
4. 连接时把对方的 `/p2p-circuit` 地址填到“对方地址”即可

> 重要：你要连接“对方”，必须拿到“对方的可拨号地址”。如果对方只给你 PeerID，而你本地又没缓存过对方地址，那么仅 PeerID 通常无法完成初始连接。

---

## 5. Relay 服务器如何搭建与使用

本项目自身支持 **HOP 模式**充当 relay 服务端。

### 5.1 在公网机器上运行 Relay（HOP）
1. 选择一台公网 VPS，放行端口（例如 4001/tcp）
2. 修改 `application.yml` 或使用启动参数，至少配置：

```yml
kk:
  p2p:
    relay:
      enabled: true
      mode: HOP
```

> 监听端口目前通过系统属性 `-Dkk.p2p.listenPort=4001` 指定（也可用 0 让系统随机分配，但不推荐做 relay）。

3. 启动应用，确保日志中打印出：本机 PeerID 与可分享地址

### 5.2 Relay 节点地址如何给客户端使用
客户端配置 `kk.p2p.relay.addrs` 需要包含 relay 的 PeerID：

```yml
kk:
  p2p:
    relay:
      enabled: true
      mode: CLIENT
      addrs: "/ip4/<relay公网IP>/tcp/4001/p2p/<relayPeerId>"
```

客户端启动后会自动预约（或在 UI 点“预约”），然后生成自己的 `/p2p-circuit` 地址。

---

## 6. 虚拟 VPN（Wintun）使用说明

### 6.1 前置条件
- Windows
- 需要 `wintun.dll` 可被 JNA 加载：
  - 放在程序工作目录，或
  - 放在 PATH 可搜索到的位置，或
  - 配置 `-Djna.library.path=...`

> 若缺少 DLL/权限不足，UI 会提示“VPN 启动失败”。

### 6.2 开启 VPN 的步骤
1. 打开 UI → `VPN` 标签页
2. 填“虚拟IP / Mask”并点“应用”（也可先不点，启用时会尽量读取 UI 值）
3. 填“目标 PeerID”（抓到的出站包将发送给此节点）
4. 勾选“启用（需要管理员权限）”

### 6.3 路由（让哪些流量走 VPN）
启用 VPN 只会创建网卡并收发包，**系统默认不会自动把流量导入该网卡**。

你需要在 Windows 上设置路由（示例）：

- 假设 A 虚拟 IP 为 `10.8.0.2`，B 虚拟 IP 为 `10.8.0.3`

A 上：
```bat
route add 10.8.0.3 mask 255.255.255.255 10.8.0.2
```

B 上：
```bat
route add 10.8.0.2 mask 255.255.255.255 10.8.0.3
```

> 你也可以按需把某个网段/某个业务 IP 指向虚拟网卡，实现“仅特定流量走 P2P 隧道”。

---

## 7. 配置项说明（application.yml）

### 7.1 连接方式
- `kk.p2p.connect.mode`
  - `AUTO`：有 multiaddr 就用；没有就仅用 PeerID（依赖缓存/发现能力）
  - `MANUAL_ADDR`：必须提供对方 multiaddr（最稳定）
  - `PEERID_ONLY`：只用 PeerID（需要你本地已缓存对方地址）

### 7.2 拨号策略（直连优先 → relay 兜底）
- `kk.p2p.dial.perAddrTimeoutMs`：单地址超时，越小回退越快
- `kk.p2p.dial.totalTimeoutMs`：整体拨号超时
- `kk.p2p.dial.preferDirect`：是否把非 `/p2p-circuit` 地址排前
- `kk.p2p.dial.triggerRelayReserveOnFail`：拨号失败后触发一次本机 relay 预约（帮助他人更容易连到你）

### 7.3 Relay
- `kk.p2p.relay.enabled`：是否启用
- `kk.p2p.relay.mode`：`CLIENT` 或 `HOP`
- `kk.p2p.relay.addrs`：relay 节点地址列表（必须包含 `/p2p/<relayPeerId>`）

### 7.4 NAT 直连增强
- `kk.p2p.nat.upnp.enabled`：是否启用 UPnP IGD 端口映射
- `kk.p2p.nat.upnp.leaseSeconds` / `renewIntervalSeconds`：映射租约与续约间隔
- `kk.p2p.nat.stun.enabled`：STUN UDP 映射探测（用于判断网络环境，不保证 TCP 直连）

### 7.5 VPN
- `vpn.enabled`：是否随 P2P 启动自动启用 VPN
- `vpn.ip.address` / `vpn.ip.mask`：本机虚拟网卡 IP
- `vpn.target.peerid`：把虚拟网卡流量发给谁

---

## 8. 常见问题（FAQ）

### 8.1 只给 PeerID 为什么连不上？
A：libp2p “拨号”需要地址（multiaddr）。PeerID 只是身份，不是地址。除非你已通过 mDNS/PEX/手工输入等方式学到对方地址，否则仅 PeerID 无法初始连接。

### 8.2 启用了 relay 还是连不上？
A：你需要拿到**对方**的 `/p2p-circuit` 地址（或对方能直连的地址）。你本机预约 relay 只能让“别人更容易连到你”，不等同于“你一定能连到别人”。

### 8.3 VPN 开关点了报错？
A：通常是以下原因：
- 没有管理员权限
- `wintun.dll` 不可加载（路径不对）

### 8.4 现有代码是否实现“打洞(hole punching)”？
- **已实现 WebRTC（ICE/STUN/TURN + DataChannel）数据面**：使用 `dev.onvoid.webrtc:webrtc-java`。
- 具体机制：
  - 信令：`/kk-webrtc-signal/1.0.0`（通过 libp2p stream 交换 `offer/answer/cand`）
  - 数据面：WebRTC 建链成功后，Chat/VPN **优先走 WebRTC-UDP**；失败则自动回落到 **libp2p(TCP/Relay)**。
- 重要限制：WebRTC 需要双方在线完成一次 offer/answer + candidate 交换。
  - 这次交换可以走直连，也可以走 relay（如果只能靠 relay 才能先“互相看见”，WebRTC 仍可能随后打洞出一条 UDP 直连通道）。

### 8.5 如何开启 WebRTC（推荐做法）
1) 两端都配置并启用：
```yml
kk:
  p2p:
    webrtc:
      enabled: true
      prefer:
        chat: true
        vpn: true
      connectTimeoutMs: 12000
      stun:
        servers: "stun.l.google.com:19302,stun1.l.google.com:19302"
      # 有条件建议配置固定端口范围，方便放行防火墙
      port:
        min: 0
        max: 0
```
2) 启动两端程序
3) 先在 UI 里正常“连接”（确保双方能交换到地址/建立 libp2p stream）
4) 之后发送消息/开启 VPN 时会自动尝试 WebRTC：
   - 成功：后续数据优先走 UDP
   - 失败：自动回落到 TCP/Relay，不影响功能

### 8.6 TURN 什么时候需要？怎么配？
- 需要 TURN 的典型场景：两端都在严格 NAT/对称 NAT，且 UDP 打洞直连失败。
- 本项目 TURN 配置（逗号/换行分隔）：
```yml
kk:
  p2p:
    webrtc:
      turn:
        enabled: true
        servers: "turn.example.com:3478|username|password"
```
- TURN 服务器推荐使用 `coturn`（公网机器 3478/udp + 3478/tcp 放行，按你的安全策略配置账号/限流）。

---

## 9. 相关代码位置（便于二次开发）

- P2P 引擎：`kk-tunnel-vpn-p2p/src/main/java/com/kk/p2p/engine/Libp2pEngine.java`
- NAT/UPnP：`kk-tunnel-vpn-p2p/src/main/java/com/kk/p2p/nat/UpnpIgdPortMapper.java`
- WebRTC 会话：`kk-tunnel-vpn-p2p/src/main/java/com/kk/p2p/webrtc/WebRtcDataSession.java`
- Wintun 虚拟网卡：`kk-tunnel-vpn-p2p/src/main/java/com/kk/tunnel/vpn/service/WintunService.java`
- UI：`kk-ui/src/main/resources/fxml/chat-view.fxml` + `kk-ui/src/main/java/com/kk/ui/controller/ChatController.java`
