# Super-Agent 中长期落地（§4.2 + 衔接项）· 验收台账

> goal-2c6d273c 的证据台账。文字入库；截图不入库。

## 1. D-NEW-1/2/3 核对结论（本轮只核对不改码）

| 缺陷 | 闭合位置 | 判据 |
|---|---|---|
| D-NEW-1 宿主重启后旧卡按钮静默丢弃 | `0882ddad`（`BoardCardFlip.stale` + `DesktopExtension` 滞留翻面 + `BoardCardFlipTest` 34 行） | 宿主不再认得的卡动作 → 如实翻 `aborted`（保留标题/diff、摘按钮、横幅解释），gated on hasTicketForCard |
| D-NEW-2 工单活跃期列表滚不上去 | `ChatScreen.kt` reverseLayout 双翻重构（f634c783 链） | 「跟随只该在本来在底部时生效」由 `shouldFollowLatest(isNearBottom, …)` 保留；「何时该滚」的三轮修补被从根上取消（索引 0 即最新） |
| D-NEW-3 合并卡增删数展示两遍 | `0882ddad` 渲染侧 + `SandboxedCliDriver` summary 只放文件数 | 渲染层 `+N −N` 彩色等宽一处承担计数；summary 行不再追加（AgentCards 注释钉死口径） |

## 2. 包⑥ 自愈反哺最小形态（`70b113ec`）

- 判定件 `PitfallLedger`（desktopApp）：仓库内 `.tenon/pitfalls.jsonl`，人可读可编辑；损坏行跳过；条目 {source, pattern, rule, createdAt}。
- 匹配注入：`selectFor(标题+派单原文)` 按关键词计分；**针对性条目优先、恒匹配垫底**（万金油规约不挤精准规约）；上限注入；单条正文截断。
- 接线：`rejectRevisionByCard` 把人的打回意见追加进账本（pattern 留空，人可后补）；`runDevelopmentAndHealLoop` 组装提示词时账本命中条目与用户反馈合并，经 `WorkerPrompt` 硬约束前置。
- 反向验证：摘 `score > 0` 过滤 ⇒ `selectMatchesByKeywordCountAndBreaksTiesByRecency` 红；恢复全绿。6 用例；`:desktopApp:test` 281 条绿。
- heal 成功轮的经验记录（source=heal）留待真机轮接——需要真实运行数据才有可信 pattern，不造假数据。

## 3. 包③ 分级授权引擎（`27445412`）

**L1 会话免审闭环**：手机 `CardSessionExemptRow` 点选 → `session-exempt|<cardId>` 单槽编码动作（与打回同款前缀契约，编码/解码纯函数单测钉死）→ 宿主按 agent 最近会话解会话键（动作帧无 sessionId，与 merge-patch 反查同纪律）→ `SessionExemptLedger` 落表（`session-exempts.json` 0600、键=(会话,agentKey)、5min 租约、坏文件=空表 fail-closed）→ `DesktopStageChain` 验收棒 `review_ready` + 免审命中 + 全棒低危（`LowRiskReviewer`：黑名单永远赢白名单，两层都不命中=拦，fail-closed）⇒ 自动 `merged`；任一棒非低危照常等 1-Click。

**L3 高危租约屏障**：`HighRiskLease` 判定件（词表三类：破坏性删除/配置重置/网络穿透；邻近整词匹配，`rm -rfx` 不误伤、多 token 词必须相邻）+ `LeaseBarrier` 三态（60s TTL 含端点、时钟回拨判 EXPIRED）；宿主动作分发前置闸——卡 `cmd` 高危时必须有 `expiresAt`（=签发+TTL）且按时到达，否则拒绝并 `flipStale` 如实翻面（晚来的同意不放行）。闸的守卫测试 3 条（源码级，GuardSourceScan 剥注释），反向验证：`classify` 旁路 ⇒ 守卫红，恢复绿。

**测试**：队友 35 例（编码/激活判据/台账 TTL/低危判据/租约边界/夹具开关）+ Lead 守卫 3 例；反向验证 12+ 处先红后绿；`composeApp`/`desktopApp`/`agentNode` 三模块全绿。

## 4. 包② S4-4 中途死亡窗口（`27445412` 同笔）

`MockExternalPlugin.hangMs` 开关（env `TENON_MOCK_PLUGIN_HANG_MS`，默认 0 行为不变，负值/乱值回落）让插件停在接单后半途；`MidExecutionDeathWindowTest` 三例：挂住期零事件（静默被钉住）、拔掉必 Error 事件 + onConnectionLost 真实触发、开关口径。等待全走 Await 轮询探针。后续真机轮用长任务真 agent 时，看门狗的 TERMINATE/FORCE_KILL 有了可注入的故障注入手段。

## 5. 包⑤ P2P 大产物直投（`435339dc`）

- 预算纯函数与协议拒绝边界**同尺**（严格大于）：正好 24KiB 卡内放得下、超 1B 直投；`CARD_BYTES` 引 `Limits` 本体不抄数。
- 投递走现成入口（`sendAgentArtifacts` purpose=artifact → TransferManager → TransferClient/FileChannelRouter），patch 本体走既有 `deliverPatchBundle` 不重发（防对端双报价）；直投只补 report.json/task.log。
- 卡片 artifacts[] 自描述：intent 稳定键（渲染端查双语词条）、delivered 只写真实直投事实；composeApp 预览路由（md/diff/txt/json+mime 兜底，其余如实不可预览）+ 全屏窗（Diff 复用 `agentCardHunkLinesOf`）；ChatScreen 只给同会话同名+Completed 的落盘路径（无孤立路径展示）。
- 测试 27 条；反向验证三处摘红（路由分支/阈值边界/intent 组装）。

## 6. 包④ Supervisor 三权分立最小闭环（`d00c4eb8`）

- `ChainStage.requiresIsolation`（缺键/非布尔全回 false；老调用零翻新）+ `[iso]` 文本标记（双位识别、解析老文本一字不动）。
- 并行：连续隔离棒成组，`Semaphore(4)` + `async(Dispatchers.Default)`（runBlocking 继承单线程退化假并行——实测修正）；全组完成按棒序串行 land（git 不并发）；组内失败整组不落盘。
- Reviewer 审计三判据以 checks[] 挂合流卡：语法规范/CJK 最小复制（只审 + 行，权威源 tools 门禁）、白名单键对照协议取值域清单（`AgentAckKinds`/`AgentGrantScopes`/`AgentExecPolicies` VALUES 装配——取值域单一权威源）、帧预算自指量测；缺清单如实 SKIP；任一 failed ⇒ blocked 不自动合流。
- 测试 26 条；红→绿 6 处（实现缺陷 2 + 测试自伤 4）；`valueDomains` 装配由 Lead 完成（`DesktopStageChain`）。

## 7. 包⑦ OBS-1 群信息面板「入驻智能体 (1)」定性（新帧 `ChatGroupInfoAgentsPhonePreview`）

- 新帧夹具带一条 `virtualAgents`（codex, single），393dp 视口渲染：**「入驻智能体 (1)」标题下 agent 行清晰可见**（头像座 + codex + 智能体徽标 + @codex + 授权管理入口），见渲染图 `androidApp/build/outputs/screenshotTest-results/preview/debug/rendered/.../ChatGroupInfoAgentsPhonePreview_chat-group-info-agents_*.png`（构建产物，不入库；截图不入库纪律）。
- **定性：内容在、渲染正常**。真机「有计数没有行」的两种可能 remain：① 560dp 视口需滚动（本帧 393dp 已整段可见，560dp 更宽不可能更少）；② 真机那个群的 `group.info` 里 `virtualAgents` 声明为空/未同步——按"真机重进群信息页复测 + dump group.info"走，不是渲染缺陷。
- 天然对照组：旧帧 `chat-group-info`（夹具无 virtualAgents）无 agent 行 ⇒ 「无声明 ⇒ 无行」方向已由既有帧钉住；渲染侧反向验证以两帧对照承载。
- 登记：`componentSpecs.previews`（GroupInfoDialog 组件）+ spec 重投影（生成计数 +1 spacing/slotSites）；设计四门绿（specDrift/previewPairing/dupFrame/stateSlot）。
- 参考图重录：`updateDebugScreenshotTest`（v2 卡片族因产物 intent 行 UI 变化一并重录，属包⑤的预期视觉变化）。

## 8. 包⑧ 最终阶段验收读数

- **七模块串行全量**（隔离 worktree `midterm-verify` @ `60661390`，`--rerun-tasks` 74 tasks）：
  第一轮红 1（`pairFrameClaimingSomeoneElsesKeyIsRejected` 5s 超时，单跑恒绿）→ 预算提到 30s（`bd0ea1bd`）；
  第二轮红 2（`aMisaddressedChannelFrameGetsAnHonestNoExecutorBack` / `agentSessionsAndEventsArePersistedAcrossRestart`）与
  第三轮各轮换一条 —— **既有 ：app 轮换债**（出自 M7-T2 `69e13c61` 等本轮之前的提交；两轮改面不含 agent 会话注册表与通道回执），
  非本轮引入。两条各 30s 预算下仍轮换 ⇒ 不是等待窗口，是**真竞态**（`RelayControllerAgentComponent` 的注册表/回执并发形状），
  按纪律不放宽断言，专项调查留待下一轮（含此前已知的 NudgeNotificationTest 族）。
- **:testStabilityGate 3 轮**（core+app）：R1 红 `crossPeerSameSessionIdIsolationAndDeduplication`、R2 绿、R3 红
  `aMisaddressedChannelFrameGetsAnHonestNoExecutorBack` —— 与上同族，记账为已知债升级（新增 2 条轮换成员），不阻塞收口。
- **各包本地全绿读数**（交付时实测）：③授权 `:composeApp:jvmTest :desktopApp:test` 绿 + 26 例新增；
  ②S4-4 `:agentNode:test` 331 例 0 失败；⑤直投 `:composeApp:jvmTest :desktopApp:test` 绿 + 27 例新增；
  ④Supervisor `:agentNode:test :desktopApp:test` 绿 + 26 例新增（`--rerun-tasks` 复跑过）；unused-imports / 痕迹 / cjk / stale / env 门禁各交付点全绿。
- **遗留**：OBS-2（锁屏 FGS 崩溃）需真机复现+logcat 定位，本 goal 内未动；:app 轮换族专项（见上）。

## 9. 结论

四包交付物（③分级授权 / ⑤P2P 直投 / ④Supervisor / ⑥反哺 + ②S4-4 铺路 + ①D-NEW 核对 + OBS-1 定性）全部落地并各自带红→绿验证；
最终串行全量的红均为**既有轮换债**（非本轮改动面），已记账升级不掩盖。goal-2c6d273c 的可判定项全部达成；
OBS-2 与 :app 轮换专项是下轮的入口。

## 10. 遗留债清偿（goal-394a93a0，`38ff49a5` / `253b4005`）

### 10.1 :app 轮换族（`38ff49a5`）

**根因判定**（7 轮全量 3 红的插桩证据，非猜测）：接收侧 NodeRuntime **先 ACK 后交接**——assign/回执/会话帧拿到 ACK（`acked=true in=1ms`），但 `handleOnAgentChannel` / inbound 订阅者全程未见；SharedFlow 交接在满载 JVM 下静默丢，发送侧拿到 ACK 即停重试 ⇒「发送成功 ≠ 对方收到」§3 第 8 种形态。

**修复**（测试侧 redrive，生产源零改动）：委派帧按「重新委派」节奏重投（1s、新信封 id、同 channelId——与真实宿主租约超时重派同形），会话族 5 处单发判定点同纪律（事件带原 cursor 幂等）；`awaitUntil` 判定条件一字未改，加严 `assertFalse(anySendFailed)`。反向验证：假应答桥吞帧 ⇒ 红。

**终验**：`:testStabilityGate` 3/3 全绿（8m25s）；`:app:jvmTest` 776 例 0 失败。

### 10.2 OBS-2 锁屏 FGS（`253b4005`）

**定性修正**：证据日的 FATAL 对应旧构建（`RelayService` 已不存在；`efcfed69` 三层 runCatching 已把崩改成「静默缺席」）。真机实测（Pixel 6/Android 15）：HEAD 锁屏拉起 `getFgsAllowStart=DENIED` / `uidState=TPSL` / `startForegroundCount=0`——保活服务缺席，后台收消息受损。

**修复**：`canStartForegroundServiceNow` 纯函数时机判定（TOP_SLEEPING 如实不在前台、悬浮窗豁免）+ `pendingStart` 登记 + `MainActivity.onWindowFocusChanged` promote 重发；关开关连 pending 撤销。

**真机读数**：修复前 DENIED/count=0；修复后 7 轮冷启动锁屏拉起每轮 `PROC_STATE_TOP / count=1 / isForeground=true`、通知 4202 在列、closeCount 全 0（92 peers 无恶化）。反向验证两层：单测摘豁免 ⇒ 指名红；真机摘 promote 钩子 ⇒ 严格不解锁场景复现缺席，恢复 3 轮全绿。

### 10.3 遗留（如实）

- **core 的 ACK-before-delivery 生产缺陷**：`Messenger.handleReliable` 先回 ACK 再 tryEmit、交接无失败隔离——根治方向（ACK 只在帧进入接收管道后回，或 pump 加失败重投）需动 core 传输协议语义，另开任务。
- CME 单次现场无堆栈（7+3 轮未复现），未定位，记账。
- NudgeNotificationTest 族不在本轮范围，未动。

## 11. 传输层根治：ACK 后置（goal-dc521ac3，`1823a6b9`）

**根因**：`Messenger.handleReliable` 无条件先回 ACK 再 `_incoming.tryEmit`，而 tryEmit 在交接管道满时返回 false 且被忽略。对端拿到 ACK 停止重传、本端帧被静默挤掉 —— 「拿到 ACK 但永远没投递」正是 §3 第 8 种形态的生产源（轮换族插桩证据：assign 帧 acked=true in=1ms 但 handleOnAgentChannel 全程未执行）。

**修法**：
- UDP：去重提前（重复帧仍回 ACK 停重传，不重投）→ 先 tryEmit → 成功才回 ACK；进不了管道就不回，对端可靠重传接管（最多多一次重复投递，去重表挡住）。
- TCP：`acceptTcpMessage` 同纪律 —— 进不了交接管道返回 false，调用方不回 `msg-ack`。
- 第二级交接：`_inbound` 缓冲 256→1024，挤掉时 `handOffDropped` 计数 + 日志（messageStore 已先落，离线回放不丢；只暴露实时订阅者侧丢失，不静默）。

**兼容后果**：帧格式与 golden vectors 零变化；行为变化只在「接收侧交接失败」异常路径（谎报 ACK+丢帧 → 暂缓 ACK+重传）。两端无需同版本升级：老端维持旧行为，新端对老端重传照常 ACK。

**验证**：新钉子 `ackIsWithheldWhenHandOffBufferIsFull`（慢订阅读者灌满 128 容量，断言 ACK 数 128..129 且第 130 帧无 ACK）；反向验证把 ACK 挪回 tryEmit 之前 ⇒ 红（130 ACK），恢复绿；连跑 3 轮绿。钉子本身的边界（慢订阅读者取走 0/1 帧）已容忍为 128..129，旧顺序恒 130 仍被区分。

## 12. 轮换族收敛（本 goal 附带的稳定性修复）

| 红点 | 处置 |
|---|---|
| `StageChainIsolationTest.twoIsolatedStagesOverlapInTime` | 墙钟窗口是赌调度——改会合屏障（真并行双方到达；串行第一棒超时红）；cap 对照改「并发峰值 ≥ 2」 |
| `MessengerReliabilityTest` 新钉子 | 慢订阅读者 0/1 帧边界容忍为 128..129（旧顺序 130 仍被区分）；订阅用 onSubscription 确定性同步 |
| `CrossNetworkTlsTransferTest` | TLS 握手连接预算 5s→30s（满载挤压） |
| `DiscoveryProfileRefreshTest` | 替身数据竞态：采集协程写 seen、测试线程遍历 ⇒ CME；换 CopyOnWriteArrayList |
| `OfflinePatchBundleTest` | 双节点启动/发现等待 30s→60s（观测预算） |

`:testStabilityGate` 3/3 全绿（8m42s）。

## 13. 推送自检（AGENTS §7.0，人审记录）

- ① 隐私形态：`git diff origin/main..HEAD` 84 行命中逐条过目——全部为夹具 UUID / 毫秒时间戳 / 占位 IP（`192.168.2.22` 属 docs/privacy-scan §4 有意保留的家网段）/ 占位用户名（`/Users/someone`）/ 正则与常量。真问题 1 处已修：路径检测自测样例写了真实工作目录（`532ff0d3` 换成占位路径）。
- ② 提交邮箱：`ke xuejin <168250+kexuejin@users.noreply.github.com>` / `kexuejin <…@users.noreply.github.com>`——全部 noreply。
- ③ ignored-but-tracked：无。
- ④ mavenLocal/SNAPSHOT：`settings.gradle.kts` 唯一命中是 `LUCIOLA_LOCAL=1` 门控的 luciola 本地验证块（默认关闭；公共仓坐标照常解析，不构成 mavenLocal-only 依赖）。

## 14. 推送回执（S6-4）

`git push origin main` → `3325d158..31337183  main -> main`，退出码 0；推送后 `git rev-list --count origin/main..HEAD` = **0**（本地与远端一致）。自检四命令读数见 §13。

推送覆盖的两轮升级（本仓当日累计）：super-agent 卡片升级短期计划 Phase 0–5 + 中期 §4.2 三块 + 遗留债（轮换族、OBS-1/2）+ 传输层 ACK 后置根治。当前工作树仅剩另一会话的图标资源在途改动（未纳入本批）。
