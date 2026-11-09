# 第三轮评审后的修复：两个 P0 + 静默丢失链 + P2（2026-11-09）

- **分支**：`feat/agent-trigger-plugin`（worktree `.agents/worktrees/feat/agent-trigger-plugin`）
- **起点**：HEAD `b335f483`（`main..HEAD` 31 条），**本轮改动全部未提交**
- **改动面**：26 个已跟踪文件 `+1515 / −192`，另加本文件
- **性质**：对 `b335f483` 又做了一轮只读评审，找出**两条推翻上一轮结论的 P0** 与一条
  静默丢失链。上一轮（`2026-11-09-agent-trigger-plugin-review-fixes.md`）把只读通道写成
  「两层拦截：执行前 + 事后」，本轮证明**执行前那一层在真实链路上是空的**。

> 本文件是**静态 + 单测读数**，不是真机验收。边界见 §7。

---

## 1. P0①：只读通道的「执行前否决」在真实派单上不生效

### 症状

`TriggerExecutor` 强制 `wsPath = null` → `SandboxedCliDriver` 路由到**裸驱动**
（`CliSubprocessDriver`）。而 `TENON_HOOK_SESSION_ID` 此前只在沙箱分支
（`SandboxedCliDriver`）与 `ProcessAgentDriver` 注入，**裸分支只透传 `envOverrides`**。

于是真 CLI 的 hook 脚本读不到 env ⇒ 不带 `sessionId` ⇒ `hookSessionTrusted = false` ⇒
`ControlPlaneHookRoutes` 把整段「查 `readOnlyToolPolicies` 并 deny」跳过，落到
always-allow / 手机审批。

**唯一会传 `toolPolicy` 的通道，恰好是永远不被前置拦截的那条。**
`AgentHookBridgeTest` 用探针驱动直接 POST，自己带 `sessionId`，所以看不见这条接线断在哪。

### 修法

`CliSubprocessDriver` 在 `envOverrides` 之后**无条件**写宿主值：

```kotlin
envOverrides.forEach { (key, value) -> pb.environment()[key] = value }
pb.environment()[DriverKeys.ENV_HOOK_SESSION_ID] = sessionId
```

顺序是有意的：覆盖项先写、宿主值后写，**外部覆盖顶不掉宿主身份**（否则等于让调用方
自己给自己发一张可信凭据）。

### 守卫

| 用例 | 判据 |
|---|---|
| `CliSubprocessEnvInjectionTest.hookSessionIdReachesEveryCliSubprocess` | 探针脚本把 env 回显成 stdout，断言等于派单 sessionId |
| `CliSubprocessEnvInjectionTest.hostSessionIdWinsOverAnEnvOverrideForTheSameKey` | `envOverrides` 里塞同名键，宿主值仍胜 |
| `SandboxedCliDriverTest.blankWorkspacePlainPathCarriesTheHookSessionId` | 走 `SandboxedCliDriver` 的 `wsPath = null` 路径，端到端拿到 `"trigger-session-1"` |

> 反向验证：把注入那行删掉，前两条立刻红（探针拿不到 env）。

---

## 2. P0②：`SKIP` 策略让 cron 永不派发

`DesktopHostScheduler` 用 `runOnce` 同时兼作「是否补跑积压」与「是否派发当期窗口」，
于是 `missedFirePolicy = SKIP` 的任务连**当期自然窗口**都不派：`nextRunAt` 照常推进、
`missedRuns` 累加、零日志。全仓 `"SKIP"` 零用例（默认 RUN_ONCE），所以一直绿。

### 修法

把两件事拆开：

```kotlin
val runOnce = task.missedFirePolicy == MissedFirePolicies.RUN_ONCE
val shouldDispatch = runOnce || dueWindows == 1   // SKIP 只放弃积压，不放弃当期
val missed = if (shouldDispatch) (dueWindows - 1).coerceAtLeast(0) else dueWindows
```

### 守卫

- `DesktopHostSchedulerTest.skipStillDispatchesItsNaturalWindow`
- `DesktopHostSchedulerTest.skipRunsAgainOnTheNextNaturalWindowAfterAbandoningABacklog`

---

## 3. P1：静默丢失链（五处，同一类症状「发送侧成功、接收侧什么都没有」）

| # | 缺陷 | 修法 |
|---|---|---|
| 1 | webhook **先 ACK 再落盘**：`ingest` 挂在异步 listener 上，异常被 `runCatching` 吞、`false` 被忽略 | `SourceWebhookRegistry.durableSink` 在 ACK 路径上**同步**落 Job Table，三态 `Stored` / `Duplicate` / `Failed`；只有前两态回 200，`Failed` 回 `503 persist-failed` |
| 2 | 内存幂等表**先记账后落盘** | `rememberEvent` 挪到 sink 确认之后；该表降级为 fan-out 去重表，不再代表「已受理」 |
| 3 | 持久化幂等键只用 `event.id`，未按源分域（上一轮只修了内存表） | `TriggerEvent` 主键改**复合 `(source_id, event_id)`**，21.sqm 同步 |
| 4 | `TriggerEventDrain.dispatch` 的 `claimEvent` 抛错时**许可泄漏**，消费循环停摆 | `catch` 里先 `permits.release()` 再抛；单轮失败 `delay(CYCLE_RETRY_MS)` 后 `kick.trySend` 重试 |
| 5 | `syncTriggersTick` 无异常隔离会杀掉整个 `followJob`；指纹在干活**之前**写 | `followJob` 单拍 `try/catch`（`CancellationException` 原样放行）；指纹只在整拍成功后写，失败清空 |

另两处：

- **提示词注入围栏可提前闭合**：`wrapUntrusted` 裸拼正文，一句 `</untrusted_inbound_data>`
  就能让后面的正文在模型眼里变成「宿主指令」。现在 source 属性与 6 个字段全部过
  `escapeUntrusted`（`&` `<` `>` `"` `\r` `\n`）。
- **单行熔断漏杀子进程**：先 `proc.destroy()` 再 `trackTree(proc)`，根一死就取不到后代。
  现在 `BoundedLine.TooLong` 分支**先快照进程树再杀根**，`destroyProcessTree` 接受
  `preKilledSnapshot`。

### 守卫（行为）

`SourceWebhookRouteTest` +5：`persistenceFailureIsRejectedWith503AndRetryIsAccepted`、
`persistenceSinkExceptionIsRejectedWith503AndRetryIsAccepted`、
`durableLedgerDuplicateIsAckedWithoutFanOut`、`everyDeliveryAsksTheDurableLedgerBeforeAck`、
`aThrowingListenerDoesNotSwallowTheRestOfTheFanOut`。

`TriggerEventDrainTest` +2：`drainRetriesAfterACycleFailureWithoutWaitingForAnotherEvent`、
`claimExceptionDoesNotLeakTheDispatchPermit`。

`TriggerExecutorTest` +4（围栏）与 `WorktreeSandboxExecutorTest` +1
（`lineLimitBreakerKillsDescendantsThatIgnoreSigterm`，用忽略 SIGTERM 的孙进程做夹具）。

`SchedulerStoreTest` +2：`sameEventIdFromDifferentSourcesEachGetTheirOwnRow`、
`claimAndStateUpdateAreScopedBySourceId`。

### 守卫（源码层，`TriggerChannelWiringGuardTest` +3）

- `webhookAckIsGatedOnDurablePersistence`：按 handleWebhook 体内下标比先后，
  且 `rememberAt > sinkAt`；
- `everyUntrustedFieldGoesThroughTheEscaper`：围栏体内禁止裸 `append(event.`；
- `followTickFailuresAreIsolatedAndDoNotPoisonTheFingerprint`：`CancellationException`
  单独放行、`delay` 在 catch 之外、指纹晚于 `syncCronTasks`。

---

## 4. P2：四处「不响但会慢慢坏」

| # | 缺陷 | 修法 |
|---|---|---|
| 1 | `TriggerRegistry` 对账重建是 `clear()` + 逐条 `register()`，中间存在空表/半表窗口；消费协程恰好 `resolve()` 到 null 就把**已落盘**事件判 `Fail` | 改不可变快照 + `@Volatile` 引用，新增 `replaceAll()` 一次换表并返回冲突项；`DesktopExtension` 改调它 |
| 2 | `AgentSessionRegistry.sessionConversations`（信任判据）无上限、`evictIfNeeded()` 也不清（第七张表漏了第八张） | 改有界插入序 `LinkedHashMap` + 专用锁；淘汰会话时同步撤销绑定 |
| 3 | `TriggerPrompts.EVENT_FIELD_KEYS` / `PLACEHOLDER` 与 `TriggerPromptKeys.ALLOWED` / `PLACEHOLDER` 各写一份 | 删掉副本，统一引用 `TriggerPromptKeys` |
| 4 | `CronExpression.nextAfter` 把「5 年的循环次数」当 5 年视野，而一次迭代可能跳过整月/整天 | 视野改按**墙钟** `t.plusYears(MAX_YEARS)` 封顶，迭代上限降为防御性熔断 |

### 第 4 项为什么不是「只是慢」

`2100` 不是闰年。从 `2097-03-01` 起，下一个 2 月 29 日在 **`2104-02-29`**，距基准 8 年。
旧实现每年约 40 次迭代（跳月 11 + 跳日 28），260 万次上限实际能扫几万年，于是把
8 年后的解当成「视野内」返回 —— 与文档承诺的 5 年不符。新用例
`observationHorizonIsFiveWallClockYearsNotFiveYearsOfIterations` 断言的正是这个 null。

### 守卫

- `TriggerRegistryTest` +2：`replaceAllKeepsTheOldTableReadableUntilTheSwap`
  （用可控 `Iterable` 在产出每个元素前从消费侧读一次，**确定性**观察窗口，不起线程）、
  `replaceAllReportsCollisionsAndDropsWithdrawnRoutes`；
- `AgentSessionRegistryProbeTest` +3：`conversationBindingsAreBoundedAndOldestFirst`、
  `rebindingAnActiveSessionRefreshesItsAge`、`evictingASessionAlsoRevokesItsConversationBinding`；
- `TriggerExecutorTest` +1：`promptFieldKeysComeFromTheSingleRegistrySource`；
- `CronExpressionTest` +1：见上；
- `TriggerChannelWiringGuardTest` +1：`triggerRebuildSwapsTheWholeRouteTableAtOnce`
  （禁止对账重建出现 `clear()` + `register()`）。

> 第 2 项的安全性：`bindConversation` 的调用点全部紧跟 `recordSession(outbound = true)`，
> 所以即使绑定被容量淘汰，`isHostDispatchedSession` 的第二条判据（`announcedOut`）仍然成立，
> 活跃会话不会因淘汰而失去信任。

---

## 5. 本轮验证读数（实跑）

全量按 `AGENTS.md §1`，强制 `--rerun-tasks --continue --max-workers=1`：

```
BUILD SUCCESSFUL in 7m 39s
97 actionable tasks: 97 executed
GRADLE_EXIT=0
```

测试（667 份 XML，全部本轮写出）：

| 模块 | suites | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|---:|
| `:core:jvmTest` | 179 | 899 | 0 | 0 | 32 |
| `:app:jvmTest` | 130 | 863 | 0 | 0 | 0 |
| `:cli:test` | 3 | 7 | 0 | 0 | 0 |
| `:agent-contract:test` | 5 | 19 | 0 | 0 | 0 |
| `:orchestration:test` | 28 | 170 | 0 | 0 | 0 |
| `:composeApp:jvmTest` | 137 | 711 | 0 | 0 | 0 |
| `:composeApp:testAndroidHostTest` | 51 | 379 | 0 | 0 | 0 |
| `:agentNode:test` | 67 | 530 | 0 | 0 | 1 |
| `:desktopApp:test` | 67 | 367 | 0 | 0 | 3 |
| **合计** | **667** | **3945** | **0** | **0** | **36** |

与上一轮 `3915` 的差额 **+30** 逐项对得上：P0 3 条（env 2 + 裸路径 seam 1）、
P0② 2 条、P1 16 条（webhook 5 + drain 2 + 围栏 4 + 进程树 1 + 台账 2 + 源码守卫 2）、
P2 9 条（注册表 2 + 绑定表 3 + 常量 1 + cron 1 + 源码守卫 2）。

门禁（11 条 + 2 项 Python）：

```
cjkLiteralGate        当前 72 = 基线 72，通过
constructionTraceGate 扫描 19 个根；命中 0 条
staleCommentRefGate   扫描 25 个根；broken 0 / ambiguous 0
domainComposeFreeGate 131 个领域文件，0 处 Compose import
envDependentTestGate  扫描 626 个测试文件；A/B 类 0 个
designSpecDrift       OK — tokens 与代码逐键一致
designTokenUsageGate  OK
designStateSlotGate   OK — 68/68 覆盖
designLocaleParityGate OK — 基线合计 0 / 当前 0
jlinkModulesGate      12 个镜像模块 / 8 个自有 jar
unused-imports.py     OK：没有未使用的 import
check-design-palette  OK：原型 CSS 变量无死引用、深色无漏覆盖
```

`verifyLuciolaNative` / `verifyPackagedLuciolaNative`：**SKIPPED**，日志明说
「这不是通过 —— 契约没有验」。

---

## 6. 反向验证（每条新守卫都证明过能红）

| 变异 | 变红的用例 |
|---|---|
| `replaceAll` 退回 `clear()` + 逐条 `register()` | `TriggerRegistryTest.replaceAllKeepsTheOldTableReadableUntilTheSwap` |
| `bindConversation` 退回无界覆盖写 | `conversationBindingsAreBoundedAndOldestFirst`、`rebindingAnActiveSessionRefreshesItsAge` |
| 删掉 `evictConversations(...)` | `evictingASessionAlsoRevokesItsConversationBinding` |
| 插值改用手抄的、漏 `link` 的字段表 | `promptFieldKeysComeFromTheSingleRegistrySource`、`interpolateOnlyKnowsTheSixEventFields` |
| 去掉 `!t.isAfter(horizon)` | `observationHorizonIsFiveWallClockYearsNotFiveYearsOfIterations` |

更早两轮（P0/P1）的反向验证读数见本文件同目录的
`2026-11-09-agent-trigger-plugin-review-fixes.md` §12.3 与上一轮记录；本轮改动后
所有变异均已回退，`grep -rn "MUTATION-CHECK"` 零命中。

---

## 7. 边界（不许反填进任何「已验」表格）

- **没有真手机、真桌面宿主、真网络帧、手机 `run-as` 落盘、真实 UI 动作**。
- P0① 修完之后，「装了 hook 的**真** CLI 在只读派单里被拒」**仍然没有真机证据**：
  单测里的 PreToolUse 是探针发的。这条只能真机验。
- 插件 leader 走 HTTP 桥，那条路没有 PreToolUse，执行前拦截**不存在**；
  `executePluginTask` 因此仍不收 `toolPolicy`。
- `agy-approval` 回退键仍是全局共享，并发审批会挤同一个会话键（规格 §0 已记）。
- 36 条 skipped 含「luciola 原生库未找到」的跳过用例；**单测绿 ≠ 真机通过**。

---

## 8. 合并就绪度（本轮重测）

`main` 已前进到 `a74b303e`（本分支 ahead 28 / behind 31）。对**已提交的 HEAD**
（不含本轮未提交改动）干跑：

```
git merge-tree --write-tree --messages main HEAD   → EXIT=1，5 个冲突
```

`AGENTS.md`、`agentNode/.../drivers/WorktreeSandbox.kt`、
`agentNode/.../drivers/WorktreeSandboxExecutorTest.kt`、
`docs/AGENT-TRIGGER-AND-PLUGIN-SPECIFICATION.md`（add/add）、
`docs/THIRD-PARTY-AGENT-DEVELOPMENT-KIT.md`。

`ACCEPTANCE.md §5g` 写的「干净 `main` 零冲突」是 `ccdc5177` 时点的读数，**已过时**，
本轮在 §5g 追加了复核说明。迁移号仍不撞（`main` 20.sqm / 断言 21，分支 21.sqm / 断言 22），
但 `RelayDatabase.sq` 自动合并后必须重跑 schema 版本断言与 `:app:jvmTest`。

**本轮没有 merge / push / 提交。**
