# luciola 数据面接线（设计说明）

> 这是**设计说明**，不是工作笔记：不写日期、不写行号、不写"下一步由谁做"。
> 要复现某个历史判据请看 `design/runtime-evidence/` 与 `docs/adr/`。

## 1. 注入缝：传工厂，不传实例

```kotlin
NodeRuntime(messengerTransportFactory = { registry -> factory.createTransport(registry) })
```

**registry 必须先于 transport 创建**：luciola 的入站回调只报对端**指纹**，不报 socket
地址。`SecureChannelTransport` 用帧里的 `from` 查 registry 得到 `(ip, udpPort)`，
把入站信道登记到该端点名下，再以该地址把帧递给 Messenger —— 否则 `registry.touch` 的
源地址校验会整帧丢弃；该端点之后的 ACK/回复优先走这条入站信道（手机不监听，拨回去必然失败）。

发现面的接缝是 `DiscoveryEngine`：成员覆盖 `NodeRuntime` 生命周期的全部触点
（构造期注入 transport/registry/selfProfile/timings/manualPeers/onGossip/onPeerPin/
subnetBroadcasts；`start`/`stop`/`announce`/`announceTo`/`announceExit`），
外加一条入站帧流。legacy 实现（`Discovery`）保留给未升级路径。

## 2. 端口：发现与信道解耦

- 加密信道是 TCP，`listen(udpPort)`；发现走 UDP。**同号不同协议**，所以对端档案里的
  `luciolaPort` 才是该拨的端口。
- 发现引擎上报的 `udpPort` 可能是引擎的**临时端口**，不能直接拿来拨号 —— 档案里有
  `luciolaPort` 时优先用它，没有才回退。两个可选键（`luciolaPort` / `screenPort`）
  都是**拨号目标**，因此必须做 `1..65535` 范围校验：越界值会被 `toUShort()` 静默截断成
  另一个端口，表现为"发不出去、也没有任何错误"。
- 同机多实例（桌面 App + iOS 模拟器）会"互发现成功、拨号永远打到自己"，因为两者
  "自己的 IP"相同而信道端口被硬绑成发现端口。`LuciolaTransportFactory` 的
  `channelListenPort` 就是为此存在的接缝（默认等于 `udpPort`，行为不变）。
- 画面信道另开一条会话（`screenPort = udpPort + 10`），这样投屏不挤占消息与文件；
  刻意避开 legacy 的 17879/17881/17882。

## 3. 信任：deny-by-default 双侧生效

- 状态持久化在一个 JSON 封套里（`SecureStateStore`）：endpoints（`host:port` → 公钥）、
  luciola TrustStore JSON、只有指纹的 TOFU 集、以及二维码里带出去的那把配对密钥。
- `pairPeer(host, port, pk, secret)` 是扫码后的**唯一**入口。旧的 `trustDevice(nodeId)`
  键与 `peerKey(host,port)` 对不上，已改为按端点绑定。
- 未配对不外拨；未受信入站直接断并计数。

## 4. 配对准入：自证公钥的传输层控制帧

主机收到陌生入站时只拿到指纹。直接按指纹 TOFU 等于「谁先连进来谁可信」，所以加了一条
**传输层控制帧**（不进 Messenger）：

```
TLPAIR1 {"nodeId":"...","pk":"<base64 32B>","secret":"<base64 16B>","name":"可选"}
```

三关都过才放行，缺一即 deny（回 `{"ok":false,"reason":...}` 后断开）：

1. `identityFingerprint(pk)` 与会话握手指纹相等 —— 防冒名；
2. `secret` 与主机二维码里那把 128 位随机数**常量时间**相等 —— 证明扫过码；
3. 落信任成功（失败则不放行）。

配套行为：

- 陌生会话不立刻断：给 `pendingPairingTimeoutMs`（默认 30s）窗口等配对帧，窗口内业务帧
  一律丢弃；超时断开并计数（占住 accept 循环是有代价的）。
- 主机应答（ok/reason）回给发起侧；**控制帧不进 Messenger**，不污染聊天消息流。
- 指纹级 TOFU（`trustInboundFingerprint`）仍保留，仅作为「已确认设备」的辅助入口，
  不再是陌生连接的唯一判据。

## 5. 能力位与灰度

`fch1`（文件字节走加密信道）与 `scr1`（画面走独立加密会话）都是**不对称能力位**：
只有双方都声明才走新数据面，否则继续 legacy。这正是"一端升级、另一端没升级"时的灰度开关
——不会出现"发送端推、收端等"。

声明必须与"真的能收"同时成立：声明了 `fch1` 却不起文件数据面，对端会一直等。
`RelayController` 因此按 `luciolaMode && 数据面可用` 条件声明，而不是按开关声明。

## 6. 现状与遗留

- legacy 监听（`TcpFramedChannel` / `LinkServer` / 17879 明文回退）在 luciola 模式下已退役；
  开关关闭时仍按原样工作（双栈共存）。
- 加密信道监听面目前仅 IPv4（Rust 侧 `0.0.0.0`）；IPv6 入站待上游补。
- 信任材料（seed、配对密钥、信任账本）在桌面与 Android 落**应用私有目录的明文 JSON**，
  iOS 走 Keychain。三端保护强度不一致，属于已知缺口。
