# A32 · 心跳停摆判据收口到「已连上的会话」+ 档案刷新不再丢信道端口

A31 结尾记账的三条未决里，这一轮清掉第 ① 与第 ③ 条，第 ② 条（落盘历史端点）由
①的修法顺带降级为「惰性数据」，理由写在最后。

## ① 自愈仍在误判：`targets` 比 `send` 宽了一格

**形态**（A31 复验时已经量到，只是当时判成「启动期一轮」）：

```
[heartbeat] entry targets=2 sent=0 linked=0 skipped=28 failed=[192.168.2.22:17878, ...]
```

这两个目标既没有入站会话、也没有外拨会话，只是**配对库里有 pin**。`send` 在无会话时
会走 `channelFor` 拨一次，拨不通照样返回 false —— 于是 `targets>0 && sent==0`
**长期恒定**，`SweepOnlyDiscoveryEngine` 每两轮判一次「泵停摆」并 `transport.restart()`。

真机上这条误判是可量化的：换包前的手机进程（pid 17260）在 00:16:30、00:17:30、
00:18:30、00:19:30、00:20:30、00:21:30 连续六次 `[presence] transport restart
(no packet left for 2 rounds)`，间隔正好 60 秒 —— 每重启一次，已连上的会话全断。

**定因**：`SecureChannelTransport.canReach` 的判据是「入站已接管 ∨ 外拨有会话 ∨
配对库有 pin」，而同一个文件里 `send` 的成功条件是「§ 有会话且握手完成」。判据比
被它预测的行为宽了一格，宽出来的那一格恰好是所有历史配对身份都会命中的形态。

**修法**：`canReach` 只认**已经连上的会话**（`inboundByEndpoint[key].isConnected()`
或 `outbound[key].isConnected()`），配对库的 pin 只说明「可以拨」，不再说明「已经通」。
拨号是消息面与链路层的事（`channelFor` / `LinkClient`），presence 不负责建会话。
`DatagramTransport.canReach` 的 KDoc 同步改成「判据要跟 `send` 的成功条件同宽，
不能更宽」，并写明覆盖边界：没有任何会话的节点 socket 全坏时这里不触发重建 ——
那时也没有东西可投递，重建只会在下一轮把会话又断一次。

**顺带把「不发包」的轮次留下痕迹**：修完之后 `targets=0` 成了稳态，日志里若只剩
「什么都没发生」，事后分不清是心跳在跑而目标都不可达，还是循环根本没起来。
`sendPresenceHeartbeat` 因此在「本轮一个包都没发、但有 skipped/linked」时打一行
`[heartbeat] idle linked=… skipped=… manual=…`，限流 60 秒（`IDLE_HEARTBEAT_LOG_MS`）。

### ①b 顺手把「拨不拨」与「算不算停摆」拆成两个判据

收紧 `canReach` 会误伤一类端点：用户手填的 `host:port`（只有地址、没有 nodeId）。
它存在的意义就是「现在没有会话也拨一次」，用 `canReach` 过滤等于把手动添加关掉 ——
配对过的手动地址一旦会话自然断开，就再没人拨它，对端按 `offlineAfter` 翻离线。

但把它整体算进 `targets` 又是回到旧坑（这类地址长期不通是常态）。两个问题分开答：

- **拨不拨** ⇒ `DatagramTransport.dialable`（默认 = `canReach`；安全信道覆盖为
  「有已连上的会话 ∨ 配对库有 pin」）。只有手动地址段用它。
- **算不算停摆** ⇒ 仍看 `canReach`。裸 UDP 上它恒真 —— `send` 在裸 UDP 失败只可能是
  本机 socket 坏了，那正是 task-29 的原始判据（`TransportSelfHealTest` 钉着它）；
  安全信道里没有会话的手动地址则只拨不计数（对方不可达 ≠ 本机泵死）。

这段先后被写错过两次，值得记下判据怎么发现的：
1. 第一版把**手动地址**整段从 `targets` 摘掉 ⇒ `TransportSelfHealTest
   .aStalledTransportIsRebuiltByThePresenceLoop` 判红（它用裸 UDP + 手动地址模拟
   「socket 坏了」）—— 证明「手动 ≠ 不归本机」；
2. 第一版的 criterion `manualAddressesAreDialedButNeverCountAsAStall` 用
   `await(sends >= 2)` 就断言，反向探针**假绿**（第二轮正好是判据边界，重启还没落地）
   ⇒ 收紧成「再多跑三轮之后再断言」，探针才稳定判红。

## ② 档案刷新会把已知的 `luciolaPort` 抹掉（第 ③ 条未决）

`touch` 采纳新档案时是**整份替换**。而 `luciolaPort` 不是对端「资料」，是运行时注入的
（`selfProfile` 从 `transport.boundPort` 取；监听还没绑上时是 -1 ⇒ `encodeDefaults=false`
把整个键省略）。任何一帧在绑端口之前发出、或没走 `selfProfile` 的路径都带 null，
替换之后 `sendingPortFor` 只能回退到数据报**源端口** —— 而 luciola 的源端口是临时端口，
症状与 A30 完全同形（满屏 `no session / unpaired`），只是成因换成「知道过的端口被覆盖」。

**修法**：收成一条 `Profile.keepingKnownChannelPort(known)`，在 `touch` 采纳分支里用。
只补 `luciolaPort` 一个键：`screenPort` 的 null 是**真状态**（用户关了文件信道），
`tlsPin` 的 null 也不能顶替已知 pin（那会把信任根糊住）。

## ③ 落盘历史端点：不再清洗，但也不再驱动任何动作

修 ① 之后，这些端点（宿主 30 个、手机 12 个在册离线身份）全部落进 `skipped`：
心跳不拨、不计入 `targets`、不触发自愈。它们仍留在名录与候选池里，由既有的
`pruneOfflineOlderThan`（7 天窗口）与「设备再出现时发现流程学回来」处理 ——
本轮不为它们造新的垃圾判据（「端口数字像不像垃圾」正是 A31 拒绝过的猜法）。

## 判据与反向探针

- `PresenceHeartbeatTargetsTest`（5 例，三处反向探针）：
  - `secureChannelCountsOnlyLiveSessionsNotPairingPins`（原 `…MarksOnlyPinnedEndpointsReachable`
    改名改断言）：配过对、无会话 ⇒ `canReach` 必须 false。探针把 `|| peerKey(...) != null`
    加回去 ⇒ 1/5 判红。
  - `manualAddressesAreDialedButNeverCountAsAStall`：手动地址照拨（`dialable` 真），
    但 `canReach` 假 ⇒ 不计 targets、不许重启。探针把 `targets++` 无条件加回手动段
    ⇒ 1/5 判红。**这个 criterion 自己先假绿过一次**（`await(sends>=2)` 恰在第二轮边界
    就断言，重启还没落地），收紧到「再多跑三轮」后探针才稳定判红 —— 记在这里，因为
    "探针判红"本身也要验证。
  - `aRealStallStillRestartsTheTransport` 重写：目标必须是 **registry 里已连上的对端**
    （靠 `replay=1` 在 `start()` 前塞一条真实编码的入站 ENTRY，不用定时器赌同步），
    不再拿手动地址凑数。`TransportSelfHealTest`（裸 UDP + 手动地址，3 例）在
    第一版"手动一律不计"下判红，正是它逼出了 ①b 的正确口径。
- `LuciolaSendingPortTest.aProfileRefreshWithoutChannelPortKeepsTheKnownOne`：
  同一节点先带 17878 再发一帧不带该键 ⇒ 档案与 `sendingPortFor` 仍拿 17878。
  **反向探针**把 `keepingKnownChannelPort` 摘掉 ⇒ 1/8 判红，恢复后 8/8 绿。
- `LuciolaSecureChannelTest.canReachFollowsLiveSessionsNotPairingPins`（真信道）：
  握手前 false、握手后双向 true。**16/16 PASS**（含既有 15 例），**反向探针**
  把 pin 分支加回去 ⇒ 1/16 判红（正是这一条），恢复后 16/16 绿。
  仓库自带的引擎在 `desktopApp/resources/common/native/`，但 `build.gradle.kts` 的
  约定目录只找 `../RustBuild/cargo-target`，所以必须**显式给绝对路径**：
  `TENON_LUCIOLA_NATIVE=<worktree>/desktopApp/resources/common/native`。
  给相对路径会被 gradle daemon 的 cwd 解析成 `…/daemon/9.8.0/desktopApp/…`，
  `LuciolaNativeGuard` 会让 16 例**全红**（「显式配置了却用不了」），不是跳过。
- **core 1001 tests / 33 skipped / 0 失败**（未配原生库时真引擎 16 例跳过）；
  配好后 `:core:jvmTest --tests "*LuciolaSecureChannelTest*"` **16/16 绿，连跑 3 遍全绿**。
  其间有过一次 `pairFrameClaimingSomeoneElsesKeyIsRejected` 超时（30s 等
  `rejectedInbound >= 1`），发生在 `--rerun-tasks` 重编译与上一轮全量并行的那一次；
  单跑绿、随后三遍全绿。它等的是「主机真处理过一帧」，`send` 不读 `canReach`（只读
  `channelFor` 的会话），所以这不是本次改动的行为变化 —— 记为**负载敏感的偶发**
  （与仓库里「端口即放被复用」同一族），没有顺手改它。
- 本轮跑得通的门禁全绿：`cjkLiteralGate` 72=72（未刷基线）、`constructionTraceGate`、
  `staleCommentRefGate`、`domainComposeFreeGate`、`envDependentTestGate`、
  `designSpecDrift` / `designTokenUsageGate` / `designStateSlotGate` / `designLocaleParityGate`、
  `jlinkModulesGate`；unused-imports / design-palette / harness dry-run（28 项）全绿；
  `:androidApp:assembleDebug` 通过。
- **`:testStabilityGate`（core + app 连跑 3 轮）没给绿，原因不在本次改动**：
  - 第一轮 01:21 那次（并行会话正在写未跟踪的
    `app/.../RelayControllerProjectDirectoryComponent.kt`）3 轮全红：2 轮是
    `:app:compileKotlinJvm FAILED`（那个文件 `nowMillis` 未解析），1 轮是
    `ClassNotFoundException`（类在测试运行中被改）。
  - 缩小到 `:core:jvmTest` 连跑 3 轮，同等负载下 **2 绿 1 红**，红的是
    `LinkK1Test.thePhoneOpensALinkAndTheHostDownlinksTextOverIt` 的 `withTimeout(5_000)`。
    这条用例只走回环 UDP + 长连接投递，**不进本次改动的任何分支**（心跳手动地址段在
    它里面没有配置，`send` 也不读 `canReach`）。
  - 机器安静后（并行会话停手 5 分钟）再连跑 3 轮 **全绿**；`LinkK1Test` 单跑
    **6/6 绿**。⇒ 记为**负载敏感的偶发**，与本轮改动无关，但**没有给绿、也没放宽它的
    5 秒断言**，留作未解项。
- **九模块全量 4374 tests / 0 失败 / 37 skipped**（core 1001、app 968、cli 7、
  agent-contract 19、orchestration 223、composeApp 1215、agentNode 562、desktopApp 379）。
  这一轮在并行会话的 HostDirectory 特性提交（`e2e54ac0`）之后跑；**先删掉各模块的
  `build/test-results` 强制重跑**——否则九个 test 任务全部命中 `UP-TO-DATE`，
  「BUILD SUCCESSFUL」只是把上一轮的产物又念了一遍。条数从结果 XML 里数，
  并核对了 `PresenceHeartbeatTargetsTest`(5) / `LuciolaSendingPortTest`(8) /
  `TransportSelfHealTest`(3) 三个类与其用例名确实在册（防「旧产物冒充新结果」）。

## 真机复验（换包 + 重启源码宿主）

两轮：第一轮验 ①③（`canReach` 收紧 + idle 日志），第二轮换包复验带 ①b 的最终版。
第二轮恰好把 ①b 钉在了真机上 —— 这台手机的设置里有 **2 个手动地址**
（`192.168.2.82:17878`、`192.168.2.82:17886`），每轮都在拨、其中一个持续失败：

```
[heartbeat] entry targets=1 sent=1 manual=2 linked=1 skipped=11 failed=[192.168.2.82:17886]
```

`manual=2` 说明照拨了，`targets=1 sent=1` 说明失败的那一个没被算成停摆证据 ——
同一台机器换包前（pid 17260）是每 60 秒 `transport restart` 一次。

读数见同目录 `measurements.json`，原始日志 `host-headless.txt`、
`phone-link.json`、`phone-pid-transitions.txt`。

- **旧包（pid 17260）**：`[presence] transport restart` 每 60 秒一次，连续 6 次。
- **新包（pid 22443，第一轮）**：同一窗口 `transport restart` **0** 次；
  `[heartbeat] idle linked=0 skipped=15` 是它的第一行心跳。
- **宿主**（日志起点 00:22，采样 00:30:18，uptime ≈ 8 分钟）：
  `[heartbeat] idle linked=0 skipped=30`（对应 A31 的 `targets=2 skipped=28`：
  那两个「有 pin 没会话」的端点下沉到 skipped）；`transport restart` 0、
  `[presence] sent=0` 0、`[sweep] offline` 0、`send refused` **0**（A31 同窗口 2156）。
- **链路未受影响**：`/v1/peers` 30 条里 1 条在线（Pixel 6，
  `linkConnected=true / udpPort=17878`）；手机 `linkTotal=1 / carrier=tls /
  dialAttempts=1 / dialFailures=0 / dialLastTarget=192.168.2.82:17880 /
  closeCount=0 / pinSource=pairing`。
- **双向投递**：宿主→手机三条群消息 `acked=true`，44 / 54 / 68 ms。
- **第六判据 `peer-idle-stays-online`**：uptime ≈ 2 分钟与 ≈ 8 分钟各跑一次，两次
  `PASS`（`peers=12 flips=0`）；换包后的最终版再跑一次仍 `PASS`（同样 `peers=12 flips=0`），
  不是空集假绿。
- **最终版复验**（`host-headless-rerun.txt` / `phone-link-rerun.json` /
  `phone-heartbeat-rerun.txt`）：宿主 `[heartbeat] idle linked=0 skipped=30 manual=0`、
  restart 0 / `sent=0` 0 / `[sweep] offline` 0 / `send refused` 0 / `acked=true` 3 条
  （25ms、40ms）；`/v1/peers` 30 条 1 在线（Pixel 6，`linkConnected=true`）；
  手机最终包 pid 25441 `transport restart` **0**、`[sweep] offline` 0、
  `linkTotal=1 / carrier=tls / dialAttempts=1 / dialFailures=0 / closeCount=0`。
- **8 分钟稳态**（`phone-heartbeat-steady-final.txt` / `peer-idle-steady-final.txt`）：
  最终包 21 轮心跳去重后只有三种读数，稳态那一种逐字相同
  （`entry targets=1 sent=1 manual=2 linked=1 skipped=11 failed=[192.168.2.82:17886]`），
  另两种只出现在启动头两轮（`targets=0 sent=0 manual=2`、`targets=1 sent=1 linked=0`）。
  「稳态不动」比「某一刻没重启」强：误判一旦发生会每两轮来一次，8 分钟里藏不住。
  同一时刻第六判据再跑一次 `PASS`（`peers=12 / online=1 / linkConnected=1`，
  `diag/link.json` 的 `updatedAt` 距采样 1 秒，说明文件在刷新、判据读的不是陈旧快照）。

## 仍未解

- `--tier real` 的人审与幕 4 / i1 / i2 仍需手机侧配合（harness 如实标 NA）。
- 跑真引擎用例必须显式配 `TENON_LUCIOLA_NATIVE` 的**绝对路径**（见上）：仓库里的
  `desktopApp/resources/common/native` 不在 `build.gradle.kts` 的约定目录里，
  默认全量会把那 16 例跳过。约定目录只认 `../RustBuild/cargo-target/{release,debug}`
  与 `$HOME/RustBuild/...`，要不要把仓库内那份纳入解析顺序另案决定。
- 手机侧在休眠（屏幕灭）时整套心跳停摆，是靠宿主的链路豁免维持在线 —— 这与本轮改动
  无关，属于既有形态；本轮验证时用 `svc power stayon true` 保证双端都在记录。
