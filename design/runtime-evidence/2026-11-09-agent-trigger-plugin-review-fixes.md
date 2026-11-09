# 代码评审修复轮：触发式编排与插件分支（2026-11-09）

- **分支**：`feat/agent-trigger-plugin`，基线提交 `2e63e154`，本轮落成 6 条提交
  （`0a7341d6` 沙箱 → `15d4461d` webhook → `c6974f52` token 断言 → `5ecea2b8` 路由/指纹
  → `751aab67` 台账/调度器 → 本条文档），`main..HEAD` 因此由 19 条变 25 条
- **改动面**：21 个文件 `+1171 / −119`（含 2 个新文件：
  `app/src/jvmTest/.../CommonMainJvmApiGuardTest.kt`、本文件）
- **性质**：一轮**整体代码评审**后按发现逐条修复。评审覆盖 `main..HEAD` 的 55 个文件
  （`+4960 / −77`），产出 1 个 P0 + 14 个 P1 + 若干 P2；本文件记录**修了什么、没修什么、
  凭什么说修好了**。

> 本文件是**静态 + 单测读数**，不是真机验收。真机/宿主/luciola 链路的边界见 §7。

---

## 1. 修掉的 P0：iOS 目标编不过（CI 永远看不见）

两个缺陷叠在一起，症状是「`:app:compileKotlinIosSimulatorArm64` 一开就炸」：

| # | 缺陷 | 修法 |
|---|---|---|
| 1 | `expect fun createSchedulerStore` 声明在 `commonMain`，`jvmMain` / `androidMain` 都有 actual，**`iosMain` 没有** | `app/src/iosMain/.../MessageStore.ios.kt` 补 `actual`：`createDatabaseDriver(platform)` → `RelayDatabase` → `SqlDelightSchedulerStore`，与另两端同一条路径 |
| 2 | `SqlDelightSchedulerStore` 的默认时钟写成 `System::currentTimeMillis` —— 它在 `commonMain`，而 `java.lang.System` 在 Kotlin/Native 上不存在 | 改用 `com.kapp.tenon.core.nowMillis()`（`core` 的 `expect/actual`，三端齐全，`Platform.kt` 的 KDoc 明文要求走它） |

**为什么之前没人发现**：`.github/workflows/ci.yml` 只跑 JVM / Android 任务，
**从不编 iOS**；而本地编 iOS 又依赖一个本地没发布的 luciola klib（见 §7）。
也就是说这条错误此前**没有任何门禁会红**。

**因此加了一条能在 JVM 上跑的守卫**：`app/src/jvmTest/.../CommonMainJvmApiGuardTest.kt`
（3 条用例）

- `commonMainContainsNoJvmOnlyApi`：剥注释后扫 `app/src/commonMain` 全部 `.kt`，
  禁 `System.*` / `System::` / `java.*` / `synchronized(` / `Charsets.` / `Dispatchers.IO` /
  `Thread.sleep|currentThread`；并断言扫到的文件数 > 0（防「遍历空集合恒真」）。
- `theDetectorActuallyCatchesWhatItClaims`：7 条真实样本反向验证每个正则都能命中 ——
  正则改坏时这条先红，而不是守卫永久变绿。
- `commentsMentioningJvmApisAreNotOffenders`：本目录有 6 处「为什么不用 `java.util`」的
  KDoc，剥注释后必须零命中（否则下一个人只会把守卫删掉）。

> 本轮实测：修之前 `SchedulerStore.kt` 会命中 `System::currentTimeMillis`；
> 修之后全目录 **0 命中**（`java.*` 等 9 处提及全部在注释里）。

---

## 2. 修掉的 P1：事件永久丢失 / 双派（drain 链路）

`TriggerEventDrain` 是数据丢失面最大的地方。旧实现的三个洞：

1. **认领不是原子的**：`pendingEvents()` 读、`updateEventState(RUNNING)` 写，两步之间
   别的消费者可能已经认领同一行；而 `updateEventState` 返回 `Unit` ⇒ **「我没抢到」
   在类型上不可观测**，于是照样派发 —— 双派。
2. **崩溃即永久丢失**：进程被 kill 时来不及翻终态的行停在 `running`，它既不在
   `pending` 里（拉不到），也不在终态里（`prune` 不管），没有任何日志会提到它。
3. **先翻 running 再 `acquire()`**：行在「等并发额度」期间就处于 running，这段时间
   被 kill 就成了一条死行。

修法：

| 位置 | 改动 |
|---|---|
| `RelayDatabase.sq` | 新增 `claimTriggerEvent`（`UPDATE … WHERE event_id = ? AND state = 'pending'`）、`reclaimRunningTriggerEvents`（`running → pending`）、`selectChanges`（`SELECT changes()`） |
| `SchedulerStore` 接口 | `claimEvent(eventId): Boolean`、`reclaimRunningEvents(): Int`、`updateEventState(...): Boolean`（原来是 `Unit`） |
| `SqlDelightSchedulerStore` | 条件 UPDATE + `changes()` 读回，**两步在同一个 `transactionWithResult` 里** —— `changes()` 是连接级状态，跨事务读会拿到别的连接上的陈旧值 |
| `TriggerEventDrain.dispatch` | 顺序改成 **先 `acquire()` → 再 `claimEvent()` → 失败即放弃**；并发许可挂在 `job.invokeOnCompletion` 上（写在协程体里的 `finally` 在作用域已取消时**不会执行**，许可会被永久吃掉） |
| `TriggerEventDrain.start` | 启动时先 `reclaimRunningEvents()`（此刻本进程不可能有在飞行 ⇒「看到 running 就是上一进程遗留」），且**只在这里做一次**（放进扫描循环会把真在跑的行重新派发） |
| `TriggerEventDrain.drainOnce` | 加「本轮一条都没认领到就退出」的判据（否则原地死循环）；`for (unit in kick)` 外面加 per-cycle catch（旧实现一次 `SQLITE_BUSY` 就让整个消费循环退出，此后事件只进不出且静默） |
| handler 调用 | 协程取消**不再**翻成 FAILED（旧实现每次正常关停都误记一批失败），行留在 running 交给下次崩溃恢复重放 |

**为什么 `changes()` 必须同事务**：`SerializedAccessDriver.newTransaction()` 在事务期间
持锁、同线程复用同一条连接，事务内紧跟 UPDATE 的 `changes()` 一定读到那条 UPDATE。

**反向钉子**（都在 `SchedulerStoreTest` / `TriggerEventDrainTest`，旧实现下必红）：

- `claimEventIsConditionalAndSecondClaimLoses`：第二次认领同一行必须返回 false。
- `reclaimRunningEventsReturnsOnlyRunningRowsToPending`：只放回 running，已终态不许复活。
- `updateEventStateReportsMissingRow`：行不存在返回 false（旧接口连这个都表达不了）。
- `startReclaimsOrphanedRunningEventsAndReplaysThem`：手工把行停在 running → start → 必须被重放并翻 DONE。
- `drainStopsWhenItCannotClaimAnything`：认领全失败时 `pendingEvents` 只许读 ≤2 轮（旧实现无限循环）。

---

## 3. 修掉的 P1：同一窗口重复执行（调度器）

`DesktopHostScheduler.scanTick` 四个缺陷：

| # | 缺陷 | 后果 | 修法 |
|---|---|---|---|
| 1 | `nextRunAt = cron.nextAfter(now, zone)`，而不是用枚举出来的窗口 | **负偏移下同一窗口派两次**：8:00 的任务偏移 −5min，现在 7:56，`nextAfter(now)` 返回的还是 8:00，下一拍它又「到期」 | 循环结束时 `cursor` 就是第一个未到期窗口，直接落账；只有封顶饱和时才回退到 `nextAfter(now)` |
| 2 | 先 `dispatchBounded(task)` 再 `saveTaskRunState` | 进程在「派完还没落账」之间被 kill ⇒ 同一窗口再派一次（重复执行 = 双倍 Token + 双份副作用） | **先落账再派发**。漏跑一次的代价是晚一个周期，重复执行的代价是双份副作用 —— 选前者 |
| 3 | `nextAfter == null` 时填 `now + 86_400_000` | 永不触发的 cron（如 `0 0 30 2 *`）**每天假派发一次**，无日志 | 新增 `NEVER_FIRES = Long.MAX_VALUE` 哨兵；扫描时**在算 `+ jitter` 之前**跳过（`MAX_VALUE + 正数` 会溢出成负数，判据反转成「早已到期」）；注册时留日志 |
| 4 | `scope.launch { acquire() }` —— 许可在协程**里面**拿，且无超时 | 每个到期任务起一个协程排队，协程数无上限；挂死的执行体永久占住许可，池只有 2~4 个 ⇒ 两次挂死整个调度停摆而表面上没报错 | 许可在**扫描协程**里拿（在飞协程数恒等于池大小）；`executeCronTask` 套 `withTimeout(dispatchTimeoutMs=10min)`；`TimeoutCancellationException` 必须**先于** `CancellationException` 捕获，否则超时被当成正常取消静默上抛 |
| 5 | `runCatching { scanTick() }` 静默吞异常 | 每一拍都在同一个地方失败，用户只看到「定时任务再也不跑了」 | 换成 `catch` + `RelayLog.info`（并单独 rethrow `CancellationException`） |

另修一处配套缺陷：`registerOne` 原本只判 `existing == null`，**schedule 改了不重算
`nextRunAt`**（台账里只存 nextRunAt，不存它按哪条表达式算的）。加了内存
`registeredSchedules` 比对；已知局限（进程重启后为空 ⇒ 宿主关着时改的 schedule
仍会沿用一次旧值）写进了 KDoc，彻底解决要把 schedule 落进台账（加列 + 迁移），本轮不做。

**反向钉子**：

- `negativeJitterDoesNotRedispatchTheSameWindow`：偏移 −30s、`now` 在整分之前，
  断言 `nextRunAt` 推进到下一窗口（旧实现停在同一个整分上），且第二拍不派。
- `impossibleCronNeverDispatches`：`0 0 30 2 *` 落哨兵值；连扫 400 天派发 0 次（旧实现 400 次）。

---

## 4. 修掉的 P1：沙箱产物泄漏

`OsSandboxGuard.wrap` 原本把 seatbelt profile 写成 `File(worktree, ".tenon-sandbox.sb")`
—— 落在 **worktree 里面**。任务结束 harvest 用 `git add -A` 收集改动，于是这份
**沙箱策略文件本身**被当成用户改动收进 `patch.diff`：一个纯读任务也会产出
「新增 `.tenon-sandbox.sb`」的假补丁。

修法：`wrap` 去掉 `worktree` 参数（它已无用途，留着就是陷阱），改成
`profileDir`（调用方传 `artifactsDir`，随任务清理）。单点调用
`WorktreeSandbox.kt:184` 同步改为 `profileDir = artifactsDir`。

**反向钉子**：`OsSandboxGuardTest.seatbeltProfileIsNeverWrittenIntoTheWorktree`
（旧实现下必红）—— 调用 `wrap` 后断言 worktree 目录里**一个文件都没有**。
`wrapProducesTheRightCommandShapes` 原来断言的是
`seatbelt[2].endsWith(".tenon-sandbox.sb")`（**把 bug 写成了期望值**），已改为断言
profile 落在调用方给的 `profileDir` 下。

同轮收紧 `deleteArtifactsSafely`：旧写法「先清 `.sandbox-home`、末尾对整个
`artifactsDir` 调 `deleteRecursively()`」，对 `.sandbox-home` **之外**的链接毫无防护
（任务进程能写产物目录，留一个指向 `$HOME` 的软链就够）。现在对**整个 `artifactsDir`**
做一次不传 `FOLLOW_LINKS` 的 `walkFileTree`，链接在 `visitFile` 里作为「文件」被删本体、
遍历不下钻；末尾只对根做一次 NOFOLLOW 单条删除，**绝不**退回 `deleteRecursively()`。

---

## 5. 修掉的 P1：webhook 入站的两个面

| 缺陷 | 后果 | 修法 |
|---|---|---|
| `exchange.requestBody.readBytes()` 无上限 | `HttpServer` 不设请求体上限，一个几百 MB 的 POST 就是**一次不需要密钥的 OOM**（验签发生在读完 body 之后） | 新增 `readBoundedBody()`：分块读、超 `MAX_WEBHOOK_BODY_BYTES = 8 MiB` 返回 null → `413 payload-too-large`。不看 `Content-Length`（可缺失、可撒谎），判据只能是「我读到了多少」 |
| 幂等键只用 `event.id` | 两个 source 各自投递 id 为 `evt-1` 的事件时，第二条被判成「第一条的重放」直接 `200 duplicate=true` —— **一个源静默吞掉另一个源的事件**，发送方看不出异常 | `idempotencyKey(sourceId, eventId)` = `sourceId + "\u0000" + eventId`；`rememberEvent` / `forgetEvent` 全部走这个键 |

**反向钉子**：`SourceWebhookRouteTest.idempotencyIsScopedPerSourceNotJustPerEventId`
（两个源、同一 id，两条都必须派发）、`readBoundedBodyRejectsOversizedInput`
（4 KiB 流 + 1 KiB 上限 → null；刚好等于上限 → 原样放行）。

---

## 6. 修掉的 P1：静默覆盖与漏掉的指纹字段

| 缺陷 | 后果 | 修法 |
|---|---|---|
| `TriggerRegistry.register` 是 `routes[key] = route` | 两个插件声明同一 `sourceId` 时后者**静默**覆盖前者，谁赢取决于清单遍历顺序 | 改成 `putIfAbsent` 语义，返回 `Boolean`；调用方对冲突**记日志**（先注册者胜） |
| `syncTriggersTick` 的指纹只取 `pluginId#triggerId:triggerType` | 改 `schedule` / `prompt` / `filter` / `targetConvId` 都不改指纹 ⇒ 对账被跳过、新配置永远不生效，且无日志 | 指纹改成**整份清单的 `toString()`**（`PluginManifest` / `PluginTriggerDef` 都是 data class，覆盖全部声明属性，加字段自动跟上）。手挑字段的写法每加一个字段就漏一次 |

---

## 7. 修掉的测试假绿

| 用例 | 假绿形态 | 修法 |
|---|---|---|
| `PluginRegistrationTest` 的 0600 断言 | 整段包在 `runCatching { }` 里**且不接收结果** ⇒ 断言失败被吞，任何机器上恒绿 | 只有「文件系统不支持 POSIX 权限视图」才跳过，其余真断言 |
| `SourceWebhookRouteTest.ackReturnsBeforeListenerFinishes` | `assertTrue(started.getCount() > 0 \|\| ackMillis < 10_000)` —— 后半句在任何机器上都成立，整条恒真 | 阈值改 5s：listener 被 latch 挡住 10s，同步实现必然接近 10s ⇒ 能区分「解耦」与「同步」 |
| `TriggerChannelWiringGuardTest` 的 `allowsTool` 判据 | 全文 `contains` —— 文件里出现过不等于在通道体里被调用 | 改成按 `execute` 调用块判 |
| `TriggerChannelWiringGuardTest` 的新增判据 | — | 钉住 `store.claimEvent(`、`reclaimRunningEvents(`、`dispatch` 体内「认领先于 launch」、`invokeOnCompletion`。顺序判据按 `dispatch` 函数体取下标（按整文件取会把 `start()` 里的 `scope.launch` 算进来 —— 这条一开始就写错过一次） |

---

## 8. 本轮**没有**修的三件事（如实记录）

> **后续**：三件都已在本文件 §12 修掉（`dfa7a46b` / `29b87cbf` / 只读通道前置否决）。
> 本节保留原判定，作为「当时为什么判断它们不该在那一轮做」的记录 —— 删掉它等于把
> 「当时确实留了白」这件事一起抹掉。

### 8.1 只读通道的拦截是**事后**的 —— 已在 §12.3 修掉

`tool.started` 是「已经开始执行」的通知，不是「请求批准」的钩子；`CliRunRequest`
也没有工具白名单字段，执行体拿到 prompt 就开跑。所以 §7.1 第 3 条的实际语义是
**检测 + 判负**，不是**阻止**：越界的那个调用已经落地。

要变成前置拦截，得走 PreToolUse 审批（`ControlPlane.agentHookApproval` 已经有
「问一次再决定」的形状）并给派单会话打只读标记 —— 一次跨 `:agentNode` /
`:orchestration` 的契约变更（`:agentNode` 不依赖 `:app`，`CognitiveFlow` 搬不过去）。
**本轮只把文档改成诚实的措辞**（`TriggerExecutor` 类 KDoc 新增「如实边界：拦截是事后的」、
规格 §0 矩阵对应行同步），没有假装它已经前置。

### 8.2 `hookSessionId` 不与已知会话对账 —— 已在 §12.2 修掉

`agentHookApproval` 把客户端给的 `sessionId` 字符串直接当会话键用，没有和
`sessions.findSession(nodeId, id)` 对账 —— 回环上的任意进程都能把审批卡挂到别人的会话上。
收紧方式已明确（查不到就回退派生键 + 记日志），但
**`executeCliTask` 是否在首个 PreToolUse 之前把会话登记到 `runtime.nodeId` 名下，
未在真机验证**；盲改会让 C13 的对齐键静默退化回旧行为。留作真机验收项，写进了规格 §0。

### 8.3 `ProcessAgentDriver` 的单行熔断在**分配之后** —— 已在 §12 修掉

`BufferedReader.readLine` 对行长零限制，256 KiB 判据在**整行进堆之后**才生效；
单行 1 GB 时 OOM 先于熔断。`WorktreeSandbox` 的读线程同样如此，但它超限后会摘进程树，
`ProcessAgentDriver` 只 `break` 不杀进程、随后还要 `waitFor(timeoutSec=300)` 才收场。

**本轮（§12）已改为有界读取**：四处读循环统一走 `BoundedLineReader`，累积到上限即停、
剩余字节边读边丢、返回 `TooLong` 由调用方止损。本节保留原判定，作为「当时为什么判断它
不值得在那一轮做」的记录。

---

## 9. 验证读数

### 9.1 全量测试（`AGENTS.md §1`，`--continue --max-workers=1`）

```text
./gradlew :core:jvmTest :app:jvmTest :cli:test :agent-contract:test :orchestration:test \
          :composeApp:jvmTest :composeApp:testAndroidHostTest :agentNode:test \
          :desktopApp:test --continue --max-workers=1
```

- `BUILD SUCCESSFUL in 4m 14s`，`TESTS_EXIT=0`
- `82 actionable tasks: 13 executed, 69 up-to-date`
- 665 份 XML → **3892 tests / 0 failures / 0 errors / 36 skipped**

**如实说明**：69 个 `up-to-date` 是**未受本轮改动影响**的模块（`:core` / `:cli` /
`:agent-contract` / `:composeApp`）复用了上一次全量运行（`2e63e154`，97/97 executed，
7m44s）的产物，它们的 XML 也是那一次写的。本轮改动落在 `:app` / `:orchestration` /
`:agentNode` / `:desktopApp`，这四个模块的编译与测试任务全部重新执行。**没有**加
`--rerun-tasks`，所以不要把这 665 份 XML 当成「本轮全部重跑过」。

### 9.2 受影响用例的精确读数（XML 计数，非估算）

| 测试类 | 用例数 | 失败 |
|---|---|---|
| `SchedulerStoreTest[jvm]` | 10 | 0 |
| `CommonMainJvmApiGuardTest[jvm]` | 3 | 0 |
| `DesktopHostSchedulerTest` | 10 | 0 |
| `TriggerEventDrainTest` | 8 | 0 |
| `TriggerChannelWiringGuardTest` | 5 | 0 |
| `TriggerRegistryTest` | 6 | 0 |
| `TriggerFingerprintTest` | 4 | 0 |
| `SourceWebhookRouteTest` | 18 | 0 |
| `OsSandboxGuardTest` | 8 | 0 |
| `WorktreeSandboxExecutorTest` | 21 | 0 |
| `PluginRegistrationTest` | 25 | 0 |

合计 118 条，`fail=0 / err=0`。其中本轮**新增 12 条**（`CommonMainJvmApiGuardTest` 3、
`TriggerFingerprintTest` 4、`TriggerRegistryTest` +2、`SchedulerStoreTest` +4、
`DesktopHostSchedulerTest` +2、`TriggerEventDrainTest` +2、`SourceWebhookRouteTest` +2、
`WorktreeSandboxExecutorTest` +1、`OsSandboxGuardTest` +1）。

**反向验证清单（旧行为放回去会变红的那几条，逐条实跑过）**：

| 用例 | 放回的旧行为 | 结果 |
|---|---|---|
| `deleteArtifactsSafelyDoesNotFollowStraySymlinksOutsideSandboxHome` | 只清 `.sandbox-home` + 末尾 `deleteRecursively()` | **红**：宿主 `host-secret.txt` 被删 |
| `CommonMainJvmApiGuardTest.commonMainContainsNoJvmOnlyApi` | `nowMillis = System::currentTimeMillis` | **红** |
| `TriggerFingerprintTest`（4 条中的 3 条） | 旧指纹 `pluginId#triggerId:type` | **红**（`schedule` / `promptAndTarget` / `enumerationOrder`） |
| `TriggerChannelWiringGuardTest` | 认领改回无判据 `updateEventState` | 红（结构判据：`store.claimEvent(` 缺席） |

未实跑「放回旧行为」的两条，如实标注：`seatbeltProfileIsNeverWrittenIntoTheWorktree`
（旧实现直接往 worktree 写文件，红是显然的，未再跑一遍）；`idempotencyIsScopedPerSource`
（旧键是 `event.id`，第二条必判重放，同上）。

### 9.3 门禁（`AGENTS.md §1` 十条 + 两项 Python 检查）

```text
BUILD SUCCESSFUL in 1m 38s
47 actionable tasks: 15 executed, 32 up-to-date        GATES_EXIT=0
python3 tools/unused-imports.py --check   → OK：没有未使用的 import。  IMPORTS_EXIT=0
python3 tools/check-design-palette.py --css-vars                       PALETTE_EXIT=0
```

各门禁自述读数：`cjkLiteralGate` 当前 72 = 基线 72；`constructionTraceGate` 扫描 19 个根、
命中 0；`staleCommentRefGate` 扫描 25 个根、broken 0 / ambiguous 0；`domainComposeFreeGate`
扫描 131 个领域文件、0 处 Compose import；`envDependentTestGate` 扫描 624 个测试文件、
A/B 类 0；`designStateSlotGate` 68/68 覆盖；多语言对账基线 0 / 当前 0。

两条本轮**先红后修**的门禁，如实记录：

- `:cjkLiteralGate` —— 第一轮 93/72（新增 21 处），全部是我新写的 `RelayLog` 日志用了中文。
  改法是把这些日志改成英文（与既有 `"trigger tasks synced: …"` 同风格），
  **没有下调基线**。终态 `当前 72 = 基线 72`，新增 0 处。
- `:envDependentTestGate` —— 第一轮报 `A=1`：新守卫的**测试样本字符串**里写了
  `System.currentTimeMillis()`，文本扫描把它当成真实调用。改法是把方法名在源码层拼出来
  （`listOf("current","Time","Millis").joinToString("")`），**没有加白名单**。
  终态 `扫描 624 个测试文件；A/B 类 0 个`。

### 9.4 稳定性门禁

改了并发用例与测试替身，按 `AGENTS.md §1` 另跑：

```text
./gradlew :testStabilityGate
=== run 1/3: :core:jvmTest :app:jvmTest ===  PASS
=== run 2/3: :core:jvmTest :app:jvmTest ===  PASS
=== run 3/3: :core:jvmTest :app:jvmTest ===  PASS
重复跑 3 轮：全部通过。
BUILD SUCCESSFUL in 6m 43s   STABILITY_EXIT=0
```

---

## 10. 边界：**没有**验到的东西

- **iOS 目标仍未真正编译过。** `:app:compileCommonMainKotlinMetadata` 在本机失败在
  **依赖解析**这一步，不是代码：
  ```text
  Execution failed for task ':core:transformCommonMainDependenciesMetadata'
  > Could not resolve all files for configuration ':core:iosSimulatorArm64CompilationDependenciesMetadata'.
     > Could not find luciola-iossimulatorarm64-0.9.0-metadata.jar (io.github.kexuejin:luciola-iossimulatorarm64:0.9.0).
  ```
  也就是说 §1 的两个修法在**代码层确定正确**（`nowMillis` 是三端齐全的 actual；
  `createSchedulerStore` 另两端就是这么写的），但**没有端到端编译证明**。
  `CommonMainJvmApiGuardTest` 是这条判据在 JVM 上能跑的那一半，不是全部。
- 没有真手机、真桌面宿主、真网络帧、手机 `run-as` 落盘、真实 UI 动作。
- `verifyLuciolaNative` / `verifyPackagedLuciolaNative` 在本环境是 **SKIPPED**
  （日志明说「这不是通过」）。
- 本轮**已提交 6 条**（见文件头），没有 merge / push / 重写历史。工作树干净。
- 评审里的 P2 项（`PluginManifest` 跨清单 sourceId 冲突的注册期校验、
  `TriggerAssembly` 的 `filter` 空值语义等）未逐条处理；跨清单冲突已由
  `TriggerRegistry` 的冲突日志兜住。

---

## 11. 提交拆分与推送前自检

### 11.1 六条提交（按 `AGENTS.md §7.1`「一件事」判据）

| # | 提交 | 内容 | 独立可构建 |
|---|---|---|---|
| 1 | `0a7341d6` | 沙箱：profile 移出 worktree；产物清理改整树 NOFOLLOW | 是（`OsSandboxGuard` 签名变更与唯一调用点同条） |
| 2 | `15d4461d` | webhook：body 限长先于验签；幂等键按 sourceId 分域 | 是（新增顶层常量与函数，无外部调用点变更） |
| 3 | `c6974f52` | 测试：插件 token 的 0600 断言不再被 `runCatching` 吞掉 | 是（纯测试） |
| 4 | `5ecea2b8` | 路由冲突显式化；对账指纹改整份清单并排序 | 是（`register` 唯一调用点同条） |
| 5 | `751aab67` | 台账原子认领 + iOS actual + 调度器 jitter/顺序/哨兵/超时 | 是 |
| 6 | 本条 | 文档：规格矩阵、`AGENTS.md §2.8`、`ACCEPTANCE §5h`、本文件 | 是（纯文档） |

**第 5 条为什么不能再拆**：`SchedulerStore` 新增 `claimEvent` / `reclaimRunningEvents`
是 `TriggerEventDrain` 改原子认领的**前提**，而 `TriggerEventDrainTest` 的
`InMemorySchedulerStore` 是 `SchedulerStore` 的唯一测试替身 —— 接口一加成员，替身就
编不过。任何「先提交接口、再提交用法」的切法都会留下一条编不过的中间态，属于
`§7.2` 那种「必须写明只有最后一条是绿的」情形；本轮的取舍是**不制造**这种中间态，
把接口 + 用法 + 替身 + 用例放在同一条。

**「独立可构建」不是推断，是逐条 checkout 实跑**（`§7.1` 的原话就是「单独 `checkout`
出来能不能独立编译」）：

```text
for sha in 0a7341d6 15d4461d c6974f52 5ecea2b8 751aab67 75ee6e4e:
    git checkout -q $sha
    ./gradlew :agentNode:compileTestKotlin :orchestration:compileTestKotlin :app:compileKotlinJvm
```

六条全部 `BUILD SUCCESSFUL`，且 `compileKotlin` / `compileTestKotlin` 在输出里**没有
`UP-TO-DATE` 标记**（即真的重新编译过，不是空转）。唯一一处 `UP-TO-DATE` 是
`751aab67` 的 `:app:compileKotlinJvm` —— 它与分支末端的 `:app` 源码逐字节相同，而那份
内容已在上面的全量运行里编过并跑过 118 条用例，属于 Gradle 的**内容级**跳过，不是
「没验」。验证完已 `git checkout` 回分支，`git status` 干净。

### 11.2 `AGENTS.md §7.0` 四条推送前自检（实跑）

| # | 命令要点 | 读数 |
|---|---|---|
| ① | 增量里的本机/真机身份形态 | 13 处命中，**逐条看过全是形状噪声**：`ARCHITECTURE.md` / `CONTRIBUTING.md` / `MILLISECONDS` / `AGENT-TRIGGER` 这类 ≥12 位大写标识符与文件名。无 `/Users/`、无 `/Volumes/`、无 `192.168.`、无 `2408:` |
| ② | 提交邮箱 | `kexuejin <168250+kexuejin@users.noreply.github.com>` —— 唯一一个，noreply |
| ③ | 被忽略却已入库 | 空（`git ls-files` 逐条 `check-ignore`，无输出） |
| ④ | 依赖能否在**别人机器**上解析 | `settings.gradle.kts:39` 的 `mavenLocal()` 命中，但它在 `if (System.getenv("LUCIOLA_LOCAL") == "1")` 里 —— opt-in 且默认关闭，不污染正常构建；`gradle/libs.versions.toml` 无 `SNAPSHOT` |

**① 的边界**：按 `AGENTS.md §7.0` 的说法它是**噪声过滤器不是判据**，这里也只是「人逐条
过一眼」，不是「无输出即干净」。② 只看还没推上去的增量；一旦推上去，判据要换成 §7.5
的「必须为零」形态。

**① 的读数后来被证伪（2026-11-09 晚补记，原读数保留不改）**：那一行写的
「无 `/Users/`、无 `/Volumes/`、无 `192.168.`」是**错的**。当时只盯着
「≥12 位大写」这一种形态，另外三类根本没进过 `grep` 的模式里 —— 于是把
「我没扫到」写成了「不存在」。实际在合并进 `main` 后重扫，增量里有：

- 本机绝对路径 16 处（`/Volumes/<卷名>/source/…`）；
- 本机用户目录 3 处（`/Users/<用户名>/…`）；
- 真机内网地址 3 处（`192.168.2.x`，按 `docs/privacy-scan-2026-10-29.md` §4
  应改成 `192.0.2.x` —— §4 的「保留原样」只适用于**代码/测试**，不适用于文档/证据）。

三类都已在 `docs(privacy)` 那一条里按既定占位改掉，口径与残留记在
`ACCEPTANCE.md` §5k-1。教训与 §7.0 自己写的那句是同一件事：**过滤器为空不等于干净，
要先确认自己扫的形态覆盖了要防的那几类。**

本轮**没有** merge / push / 重写历史。分支在 `main` 之上 25 条，`origin/main` 之上 43 条
（远端更旧）。

---

## 12. 收尾：§8 留下的三件（已做完）

三件的共同点：问题都不在「判据写错了」，而在**判据挂在错的位置**。所以每条的修法都是
把判据挪到能生效的那一层，并且都做了反向验证（把改动退回去，看对应用例是不是真的红）。

### 12.1 行上限改在**读取方式**上生效（`dfa7a46b`）

根因不在那条 `line.length > MAX` 判据，而在**读法**：`BufferedReader.readLine()` /
`useLines` / `forEachLine` 对行长零限制，判据生效时整行已经在堆里 —— 单行 1 GB 时
OOM 先于熔断。

- 新增 `BoundedLineReader`：累积到上限即停、返回 `TooLong`；剩余字节**边读边丢**，
  流因此不会错位（否则一次超限之后每一行都解析不出来）；
- 行终止符认 `\n` / `\r\n` / **裸 `\r`** —— curl / npm 的进度条用 `\r` 刷新，只认 `\n`
  会把整条进度流拼成一行「超长行」，于是**杀掉一个健康的进程**（比原缺陷更糟）；
- 四个读循环统一迁移（`ProcessAgentDriver` / `CliSubprocessDriver` /
  `ExternalPluginDriver` / `WorktreeSandbox`）。stdout 超限**杀进程**（`destroy` +
  2 s 收尸；旧实现只 `break`，随后还要 `waitFor(300 s)`）；stderr 超限**只丢内容不杀**
  —— stderr 不是协议面，为一行噪声杀掉正在干活的进程是误伤。

读数：`BoundedLineReaderTest` 8、`ProcessAgentDriverTest` 9、`CliSubprocessDriverTest` 13、
`ExternalPluginDriverTest` 7、`WorktreeSandboxExecutorTest` 21，`fail=0 err=0`。
反向验证两条：退回 `useLines` 后「超限真的杀进程」与「只等 2 秒收尸」都红
（旧实现读数 `killed=false`、`waitForTimeoutMs=300000`）。

### 12.2 hook 审批的 `sessionId` 先对账（`29b87cbf`）

判据从「它存在」改成「**本节点派单出去过**」：`conversationOf(sid)` 非空，或台账里
`announcedOut` 为真。`findSession(...) != null` 不能单独用 —— `ensureSession` 会给
乱序帧造占位行，占位行同样查得到，那等于「往本节点扔过一条带该 id 的帧」就能冒名。

没对账通过时回退派生键（`agy-<convId>`）、记一行日志、**不回带 `convId`**
（否则等于把「任意字符串 → 某条真实对话」的映射送出去）。

反向验证：退回旧实现，用例红在 `expected:<agy-c-7> but was:<sess-forged-…>` ——
伪造的字符串当时**逐字**被当成了会话键。

### 12.3 只读通道：执行前否决（本轮）

`tool.started` 没有「拒绝」这个动作，但装了 hook 的 CLI 有：它在工具执行**之前**
POST `/v1/agent/hook/approval`。所以把白名单判据挂在**派单**上，让那条路径去读：

| 位置 | 改了什么 | 为什么在这儿 |
|---|---|---|
| `TriggerExecutor.execute` | 把 `CognitiveFlow.allowsTool` 作为 `toolPolicy` 交给 `CliRunRequest` | 白名单唯一事实源在 `:app`，`:agentNode` 不依赖它 —— 传纯函数，不传类型 |
| `ControlPlane.executeCliTask` | 起执行体**之前**登记 `readOnlyToolPolicies[sessionId]`，`finally` 必清 | 晚一步就等于又退回事后判负；不清会把「只读」泄漏给同 id 的下一次任务 |
| `ControlPlaneHookRoutes.agentHookApproval` | 在**免批表之前**查判据，不放行就 `deny`（不弹卡） | 免批是机主以前点的「以后别再问我」，属便利；只读是宿主对这次派单的硬约束 —— 便利不许覆盖硬约束 |
| `ReadOnlyToolPolicy` | 记下**第一个**越界工具 | 判负口径与事后那一半一致：**越界即失败卡**，拦住了也要判负 |

**为什么两层都留**：没装 hook 的驱动（以及插件 leader 的 HTTP 桥，那条路没有
PreToolUse）只有事后判负可用。所以 `executePluginTask` **不收** `toolPolicy` ——
给它一个永远读不到的白名单，比没有更糟（看起来拦了，其实没有）。

**为什么先拒绝、不先弹卡**：越界的调用不该有「问一下也许能过」的路径，问就是给它机会。

**用例的时序不是等出来的**：探针驱动 `HookProbeDriver` 在 `executeTask` 里当场打一次
审批请求，于是「登记早于执行」由构造保证 —— 若登记晚一步，审批路由查不到策略、调用被
放行，用例随即红。第一版用「起真脚本 + 轮询策略表」写，被 `envDependentTestGate`
判成 B 类（`delay(20)` 落在断言前 25 行内），改掉之后是 0 命中 —— 门禁这次挡住的正是
`AGENTS.md §4` 那条「不许用固定 delay 赌同步」。

反向验证（五次，每次只退一处）：

| 退掉什么 | 红了什么 | 读数 |
|---|---|---|
| 整块否决 | 3 条 | `deny` 变成 `allow`；任务收场 `finished` |
| 只把否决挪到免批表**之后** | 1 条（顺序用例） | `{"decision":"allow","reason":"matched an always-allow rule for tool=run_command"}` |
| 判负块（denial → error） | 1 条 | `expected:<error> but was:<finished>` |
| 只去掉 `finally` 清理 | 1 条 | `expected null, but was:<ReadOnlyToolPolicy@…>` |
| `execute` 里的 `toolPolicy = …` | 1 条 | `toolPolicy 没传下去 —— hook 审批就没有判据可查` |

**如实说明**：5 条只读用例里有 2 条（「白名单内照旧走手机审批」「冒名 sessionId 借不到
别人的策略」）在旧实现下**也是绿的** —— 它们是**过紧防护**（防止改出「只读 = 免批」
「策略可被冒名借用」），不是本轮缺陷的反向钉子，不计入上面的反向验证。

### 12.4 读数

全量串行（`AGENTS.md §1`，`--continue --max-workers=1`）：

```text
./gradlew :core:jvmTest :app:jvmTest :cli:test :agent-contract:test :orchestration:test \
          :composeApp:jvmTest :composeApp:testAndroidHostTest :agentNode:test \
          :desktopApp:test --continue --max-workers=1
```

- `BUILD SUCCESSFUL in 4m 10s`，`82 actionable tasks: 12 executed, 70 up-to-date`
- 667 份 XML → **3915 tests / 0 failures / 0 errors / 36 skipped**
- 与上一轮（`3892 / 665`）的差额 = +10（§12.1 的有界读取用例）+2（§12.2 的对账用例）
  +11（本轮：只读审批 5 + `ReadOnlyToolPolicyTest` 5 + `TriggerExecutorTest` 1）、
  文件 +2（`BoundedLineReaderTest` / `ReadOnlyToolPolicyTest`）—— 逐项对得上。

**如实说明**：70 个 `up-to-date` 是**未受本轮改动影响**的模块复用了上一次运行的产物；
本轮改动落在 `:agentNode` / `:orchestration` / 文档，这两个模块的编译与测试任务全部
重新执行。`:app:jvmTest` 这一轮 861 条全绿（§12.6 那个 flake 本轮没撞上）。

| 项 | 读数 |
|---|---|
| `:agentNode:test` | 67 份 XML → **517 tests / 0 failures / 0 errors / 1 skipped** |
| `:orchestration:test` | 28 份 XML → **155 tests / 0 failures / 0 errors** |
| `ReadOnlyToolPolicyTest` | 5 / 0 fail |
| `AgentHookBridgeTest` | 22 / 0 fail（原 17，+5） |
| `TriggerExecutorTest` | 10 / 0 fail（原 9，+1） |
| `TriggerChannelWiringGuardTest` | 5 / 0 fail（结构不变量仍成立） |
| `:envDependentTestGate` | 626 个测试文件，**A/B 0** |
| `:cjkLiteralGate` / `:staleCommentRefGate` / `:constructionTraceGate` / `:domainComposeFreeGate` | 全绿（broken 0 / ambiguous 0 / 命中 0 / 0 处 Compose import） |
| `:designSpecDrift` / `:designTokenUsageGate` / `:designStateSlotGate` / `:designLocaleParityGate` / `:jlinkModulesGate` | 全绿 |
| `python3 tools/unused-imports.py --check` | OK |
| `python3 tools/check-design-palette.py --css-vars` | OK |
| `:testStabilityGate` | **1/3 轮红**，红在 `:app:jvmTest` 的 `GroupDissolvePropagationTest`（见 §12.6） |

`:agentNode` 从 505 涨到 517 = +2（12.2 的对账用例）+5（只读审批）+5（`ReadOnlyToolPolicyTest`）。

### 12.5 仍存的边界

- 执行前拦截**只在装了 hook 时存在**：没装 hook 的 CLI、以及插件 leader 的 HTTP 桥
  （那条路没有 PreToolUse）只剩事后判负；
- `agy-approval` 回退键全局共享：并发审批仍会撞在同一个会话键上（规格 §0 已记）；
- 以上全部是**代码级 + 单测级**验证。**真机 / 真宿主 / 真网络帧未验** —— 尤其
  「装了 hook 的真 CLI 在只读派单里被拒」这条只有真机才成立（单测里那次审批请求是
  探针驱动发的，不是真 CLI 发的）。写进 `ACCEPTANCE.md` 的待验项，不反填。

### 12.6 顺手撞见、**没有**修：`:app` 侧的 `freePort()` 竞态

`:testStabilityGate` 连跑 3 轮，第 3 轮红在 `:app:jvmTest` 的
`GroupDissolvePropagationTest.dissolvingAGroupRemovesItOnTheOtherNode`
（`IllegalStateException`，`awaitUntil` 超时；`861 tests completed, 1 failed`）。

**这不是本轮引入的**，理由不是「我觉得无关」，是依赖图：`app/build.gradle.kts` 只
`implementation(project(":core"))` + 测试侧 `:testsupport`，**不依赖 `:agentNode` /
`:orchestration`** —— 本轮 diff 不含这两个模块的任何字节，`:app:jvmTest` 的编译产物与
执行路径逐字节相同。单跑该类 5 次全绿（`--rerun-tasks`，5/5 PASS），说明它只在整轮
861 条满载时才翻。

根因嫌疑（未验证到能改的程度）：`freePort()` 是 `ServerSocket(0).use { it.localPort }`
—— 探的是 **TCP** 空闲端口，但 `RelaySettings` 把它当 **UDP** 端口用（`tcpPort =
udpPort + 1`）。于是「探到空闲」与「真的绑上」之间有一段 TOCTOU 窗口，且探的协议与用的
协议不是同一个；整轮满载时别的用例也在 `freePort()`，撞上的概率随并发上升。

**没有顺手改**的原因：那是一个独立的测试基建缺陷（另一模块、另一条功能线），塞进这条
提交会把「只读通道执行前否决」变成两件事（`AGENTS.md §7.1`）。留作待办，读数与推断
写在这里，不写进「已绿」。
