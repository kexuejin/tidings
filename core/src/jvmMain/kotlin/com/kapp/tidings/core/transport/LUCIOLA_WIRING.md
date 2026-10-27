# 阶段二接线规格(实测定位,2026-10-25)

Discovery 在 NodeRuntime 的全部触点(行号基于 8a7dc980 附近):
- 构造: NodeRuntime.kt:291(transport/registry/selfProfile/timings/manualPeers/onGossip/onPeerPin/subnetBroadcasts)
- start: :654(注意 :638 注释——必须在 registry 快照之前)
- stop: :911
- announce: :1052; announceTo: :1081 与 :1331(定向单播 entry,对接 luciola 需
  manualPeers 一次性注入或引擎级 API 补一个 directed-probe)
- announceExit: :1089

阶段二注入缝设计(下一个会话执行):
1. commonMain 提取 `DiscoveryEngine` 接口,成员 = 上述触点 + 入站帧流;
   `Discovery` 改为 legacy 实现(iOS 继续用)。
2. JVM/Android actual: `LuciolaDiscoveryEngine` ——
   found/updated/lost → registry.touch/markOffline;Profile 从 extra 反序列化;
   announce→controller.start;announceTo→manualPeers 定向。
3. 隐藏坑(设计时必须回答):Discovery 还把**非发现帧**(msg/gossip 等)递给
   Messenger——luciola mesh 下这些帧走 SecureChannel(阶段三),阶段二 JVM
   侧需保留旧 UdpTransport 只为收发非发现帧,或直接跳到阶段三一起切。

## 阶段三 合并切(已落地,2026-10-26)

注入缝从「传实例」改为**传工厂**:

```
NodeRuntime(messengerTransportFactory = { registry -> factory.createTransport(registry) })
```

- registry 必须先于 transport 创建:luciola 的入站回调**只报对端指纹,不报 socket
  地址**。SecureChannelTransport 用帧里的 `from` 查 registry 得到 (ip, udpPort),
  把入站信道登记到该端点名下,再以该地址把帧递给 Messenger(否则
  `registry.touch` 的源地址校验会整帧丢弃);该端点之后的 ACK/回复优先走这条
  入站信道(手机不监听,拨回去必然失败)。
- 加密信道是 TCP `listen(udpPort)`:发现 UDP 与信道 TCP **同号不同协议**,
  对端 profile 里的 `udpPort` 就是该拨的端口。监听面仅 IPv4(Rust 侧
  `0.0.0.0`),IPv6 入站待 luciola 补。
- 信任:factory 持久化「endpoints(host:port→公钥) + TrustStore JSON + 仅指纹
  TOFU 集」一个 JSON 封套(`SecureStateStore`);`pairPeer(host, port, pk)` 是
  扫码后的唯一入口。旧 `trustDevice(nodeId)` 的键与
  `peerKey(host,port)` 对不上,已改按端点绑定。deny-by-default 双侧生效
  (未配对不外拨、未受信入站直接断+计数)。
- 验收:`LuciolaSecureChannelTest` 五例走真实引擎/回环 —— 双向收发、未配对拒绝、
  未受信入站拒绝、重启后 pin 仍生效、TOFU 指纹落盘。

## 配对准入:自证公钥的传输层控制帧(2026-10-26)

主机收陌生入站时,luciola 只报指纹、不报公钥/地址;直接按指纹 TOFU 等于
「谁先连进来谁可信」。所以加了一条**传输层控制帧**(不进 Messenger):

```
TLPAIR1 {"nodeId":"...","pk":"<base64 32B>","secret":"<base64 16B>","name":"可选"}
```

三关都过才放行,缺一即 deny(回 `{"ok":false,"reason":...}` 后断开):

1. `identityFingerprint(pk) == 会话握手出来的对端指纹` —— 防冒名;
2. `secret` 与主机二维码里带出去的那把 128 位随机数常量时间相等 —— 证明扫过码;
3. `onPaired` 落信任成功(失败则不放行)。

配套行为:

- 陌生会话不再立刻断:给 `pendingPairingTimeoutMs`(默认 30s)窗口等配对帧,
  窗口内业务帧一律丢弃;超时断开并计数(占住 accept 循环是有代价的)。
- `pairingResults` 把主机应答(ok/reason)交回发起侧;**控制帧不进 Messenger**。
- 指纹级 TOFU(`trustInboundFingerprint`)仍保留,仅作为「已确认设备」的辅助入口,
  不再作为陌生连接的唯一判据。

## 还没接的(宿主侧)

1. 手机扫码 → `factory.pairPeer`:需要 luciola `luciola://pair` v2 的宿主
   渲染 + 解析(当前 Tidings 二维码是自有 JSON,含 nodeId/tlsPin,没有 Ed25519
   pk);RelayController 在 commonMain,工厂在 jvmMain —— 要走 PlatformContracts
   的注入缝,并保留 legacy 码(阶段四双栈)。
2. 主机侧的入站 TOFU 配对:回调只给指纹,TrustStore 条目要公钥;要么补一个
   「配对帧带上自证公钥 + 指纹==会话指纹」的应用层帧,要么在 luciola 侧让
   listener 暴露 peer public key。
3. legacy `TcpFramedChannel` / `LinkServer` 在合并切模式下仍会监听;阶段四按
   「双栈共存」决定保留到哪个版本。
