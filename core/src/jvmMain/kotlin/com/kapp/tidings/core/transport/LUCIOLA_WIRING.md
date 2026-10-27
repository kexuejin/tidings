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
