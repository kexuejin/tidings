# A31 · luciola 私网长连接与心跳停摆判据

## 待拍板项怎么定的

上一轮留下三条候选：A 契约对齐（不广播 lnk1、在线豁免改认 luciola 会话）、
B 保留 17879 给 K1 下行、C 只留判据接受 K1 废弃。**三条都不选**，取证后发现
问题被归错类了：这不是「一条废弃链路要不要认」，而是「一条正在用的链路被旧
裁决拨到了不存在的端口」。

- K1/K2 不是废弃功能：`lnk1` 注释写的是「我能接受一条长连接（K1/K2 下行）」，
  `LinkClient` 至今按它决定拨不拨；撤掉广播会连公网的 K2 TLS 一起停掉（A 的代价）。
- B 要给信道模式下有意关闭的明文监听面开后门，与「legacy 生产链路清零」相悖。
- C 最省事，但等于把一条能修好的链路判死刑：真机上宿主的 **17880 TLS 接受面
  一直在 LISTEN**，手机对宿主的 `pinSource=pairing`，两个条件都齐，只是没人用。

## 定因

`LinkClient.linkTargets` 的 lead 裁决是「私网 ⇒ 明文 `tcpPort`（K1 局域网路径）」。
这条裁决的前提是 17879 真的在听。信道模式下
`TransferManager(legacyTcpListener = !fileChannelEnabled)` 有意不建 17879，
而 `RelaySettings.toProfile()` 仍广播 `tcpPort=17879` —— 那只是
`Codec.validateProfile` 要求 1..65535 的必填占位（写成 0 会整帧被拒收）。
于是手机拿着 pairing pin，连续拨一个不存在的端口：`dialAttempts=229 /
dialFailures=229 / dialLastTarget=...:17879 / connect-timeout`。

连锁后果：`linkConnected` 恒 false ⇒ `NodeRuntime.stillReachable`
（`linkSnapshot().carrierOf()`，sweep 在线豁免）长期不生效，`sendPresenceHeartbeat`
的「有链路的不发」也不生效；A29 修好的第六判据因此一直返回「不适用」。
**在线稳定靠 presence 帧刷 lastSeen，不是链路恢复。**

## 推荐方案：让判据认事实，不动契约

只用对端档案里**已有的** `luciolaPort` 判形态 —— `Profile` 的 KDoc 明写
「null = 这个节点不提供 luciola 信道（legacy 节点）」：

- **私网 + 老端**（无 `luciolaPort`）⇒ 明文 17879，行为一字不变；
- **私网 + luciola 监听端** ⇒ 走已存在的 `tlsPort` + TLS + 配对 pin；
- **公网** ⇒ 原样（TLS + pin，fail-closed）；
- 缺 pin / 缺 tlsPort ⇒ 一个目标都不拨，`skipReason` 如实说原因；
  本端没有 TLS 连接层 ⇒ `lan-luciola-needs-tls`。

不动 caps、不新增协议字段、不碰帧预算、不复活 17879 明文监听面。
`skipReason` 与 `linkTargets` 同步改（诊断不许把「没拨」的原因说错）。

## 第二处：心跳的停摆判据

`sendPresenceHeartbeat` 把 registry 里**所有**已知节点与候选端点算成 `targets`，
而安全信道对未配对端点 deny-by-default ⇒ `send` 必然 false。修复前宿主稳定复现
`targets=32 sent=2 linked=0`，`send refused` 累计 2156 条。只要某一轮连那 2 个
也发不出去，`SweepOnlyDiscoveryEngine` 就判「泵停摆」并 `transport.restart()`，
把已连入的会话全断掉。

收口方式是给 `DatagramTransport` 加 `canReach(host, port)`（默认 true，legacy /
测试回环语义不变），`SecureChannelTransport` 覆盖为「入站已接管 ∨ 外拨已有会话
∨ 配对库里有 pin」。心跳三处目标只有可送达才计入 `targets`，其余记 `skipped`。
**判据用准入事实，不按端口数字猜** —— 上一轮遗留的历史临时端口因此不再被
心跳拨，也不误伤自定义端口的老端。

边界：这只收口了心跳出站。消息出站与落盘候选池里的旧端点未清洗（那是产品
判断「哪些历史数据算垃圾」，本轮未动）。

## 判据与反向探针

- `core/src/commonTest/.../LuciolaLanLinkTargetTest.kt` 六个用例：老端私网仍拨
  17879、luciola 私网改拨 17880 TLS 且带 pin、缺 pin 不拨、缺 tlsPort 不拨、
  公网不变、无 TLS 连接层时说清原因。**反向探针**把 `luciolaPeer` 恒置 false
  ⇒ 4/6 判红（老端与公网两条按设计仍绿），恢复后 6/6 绿。
- `core/src/jvmTest/.../PresenceHeartbeatTargetsTest.kt` 四个用例：只剩未配对
  端点时不许 restart、真停摆仍必须 restart（对照）、安全信道只认配对端点、
  无准入概念的传输默认全算目标。**两次反向探针**：摘掉心跳三处 `canReach` 过滤
  ⇒ 1/4 判红；`SecureChannelTransport.canReach` 恒 true ⇒ 1/4 判红。恢复后 4/4 绿。
- 全量九模块 4346 tests / 0 失败；11 门禁全绿（`cjkLiteralGate` 72=72 未刷基线）、
  `:testStabilityGate` 连跑 3 轮 PASS；unused-imports / design-palette /
  harness dry-run 全绿；`:androidApp:assembleDebug` 通过。

## 真机复验（换包 + 重启源码宿主）

读数见同目录 `measurements.json`。要点：

- 手机 `linkTotal=1 / linkConnected=true`，`dialLastTarget=192.168.2.82:17880`、
  `lastError=null`、`closeCount=0`、`pinSource=pairing`；从 229 次 17879 超时
  变成 4 次拨号（前 3 次失败）后建连。
- 宿主心跳从 `targets=32 sent=2` 变 `targets=2 sent=0 skipped=28`；启动期出现过
  一轮 `[presence] sent=0 dead=1`，随后轮次恢复，**2 分钟内 `transport restart` 0 次**。
- `[sweep] offline` 0 条、无「宿主设备睡眠」横幅。
- **第六判据 `peer-idle-stays-online` 首次真正 PASS**（`peers=12 flips=0`，
  链路在册对端全 online）。A29 起它一直因无在册链路返回「不适用」；这次给绿
  是在有真实链路的前提下，不是空集假绿。
- **长空闲复验**（宿主 uptime 523s ≈ 8.7 分钟）：判据二次 PASS，
  `linkTotal=1 / linkConnected=true / dialLastTarget=...:17880 / closeCount=0`，
  手机与宿主 `[sweep] offline` 均 0 条、`transport restart` 0 次、无离线横幅。
  拨号次数停在 4 次不再上涨，说明链路稳住了，不是靠反复重拨维持。

## 未决（如实记账）

1. 心跳启动期仍有 `targets=2 sent=0`：`192.168.2.22:17878` 有配对 pin 但对端
   （手机）不监听入站，属「对端能力」而非本地泵故障。本轮观察未连续两轮、
   未触发 restart；若将来出现长期占着 targets 的这类端点，自愈仍可能误判，
   需要把「对端不接受入站」与「本地泵坏」分成两个事实来源。
2. 落盘历史身份 / 候选端点里的临时端口仍在消息出站路径，未清洗。
3. 档案刷新若丢失 `luciolaPort` 会回退到明文拨号；目前 `Profile` 的 rev 判据防住，
   但没有专门守卫。
4. `--tier real` 的 a2r 人审与幕 4 追问 / i1 退单 / i2 硬打断仍需手机侧配合；
   harness 对这几幕如实标 NA，不冒充通过。本轮只被动观测、未派单。
