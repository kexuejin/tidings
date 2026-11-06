# 真机实测：多 Agent 团队流水线 Tier A（人 + 手机 + 宿主，2026-11-01）

> **一句话结论**：五幕里 **0/1/2/3/4/5 全部真机跑通**（幕 4 实现 59 KB 补丁、幕 5 审查给出 `VERDICT: PASS` 并挑出 4 条真问题）。**幕 6 当日判成「手机上没入口」是误判，已在同一天补测推翻并修好**（§10）：手机派单、收卡、点卡都可用；真正的缺陷是宿主 `onHostCardAction` 那一跳把 `sessionId` 恒传 `null`，导致 [合并此补丁] 这条分支从来没执行过。过程中修掉四个**阻断性缺陷**（审批回执不唤醒长轮询、幽灵 peer 抢占审批收件人、CLI 子进程 stdin 永不关导致挂死、合流分支恒假判据）与两处会让人误判的静默。

## 0. 结论速览

| 环节 | 读数 | 性质 |
|---|---|---|
| 幕 0 探针 | `/v1/peers`、`/v1/agent/sessions` 双 200；`agy/claude/codex` 均在 PATH；真机 `e4bd7b99` rev4，幽灵 `44608041` rev0 同时在线 | ✓ 装置就绪 |
| 幕 1 组队 | 工作区宣告 200 `sent`；群 `e2e-weather` = `626bed34…`，成员含真机 | ✓ |
| 幕 2 设计 | `t-1-9e5d8d65` → patch.diff **54223 B**（prototype.html 1538 行），201 s | ✓ |
| 幕 3 守门 | 审批长轮询 **12 s / 12 s 两次均 allow**，手机库对应行 `approved` | ✓（修完缺陷 A/B 后） |
| 幕 4 实现 | `t-2-23bf313f` → patch.diff **59242 B**（weather.html 1663 行），395 s | ✓ |
| 幕 5 审查 | `t-3-a66c413e` → patch.diff **3414 B**（REVIEW.md 20 行，`VERDICT: PASS` + 4 条问题），≈10 min | ✓ |
| 幕 6 合流 | 当日：**判成「手机上没入口」**（§3）；补测（§10）：手机派单 91 s 出卡、手机点卡 44 s 后宿主仓 `git apply` 落地 `MEREPROOF@c` | ✗→✓（§10 修完） |
| 注入 I2 超时 | 不批、等满 TTL → 长轮询 `deny`（fail-closed 未被缺陷 A 的修复改坏） | ✓ |

## 1. 三个阻断性缺陷（都靠真机才暴露）

### A. 审批回执到了，宿主的长轮询等不到（幕 3 卡死的真因）

手机把 `AgentApprovalEntity` 落成 `approved`，宿主 180 s 后仍返回 `deny — approval timeout`。

`AgentSessionRegistry.applyAckToApproval` 推进状态后**不通知** `approvalListeners`；全仓唯一通知点在 `recordApproval`。手机回执走 `recordAck → applyAckToApproval`，于是长轮询的 `CompletableDeferred` 永远等不到信号 —— **即使 nodeId 完全正确，批准也只表现为「等满超时再拒绝」**。单点探针（显式 `to=真机`）证明：手机 8 s 内批准，宿主 120.1 s 后照样 deny。

修法：应用成功后与 `recordApproval` 走同一通知口。回归用例 `appliedAckWakesTheWaitingLongPoll`，**摘掉通知行该用例即红（已反向验证）**。

### B. 幽灵 peer 抢走审批收件人

`resolveHookPeer` 取「在线且声明 `agq1` 的对端里 nodeId 最小」。同一台手机重配对后节点表里留下两条指向同一 `ip:udpPort` 的记录（旧那条 `profileRev=0` 却仍被同一台设备的广播刷新成「在线」），字典序在前 ⇒ 审批记在永远不会回执的身份上：手机照样弹卡、人照样点，`key.nodeId == envelope.from` 匹配失败。

修法：抽出纯函数 `pickHookPeer`，先按 `ip:udpPort` 折叠取 `profileRev` 最大的一条，再取 nodeId 最小。`HookPeerPickTest` 4 条。

现场仍在暴露：日志里持续出现 `[send] peer=44608041 channel=TCP` —— 折叠只挡住了审批面，其他按 nodeId 选路的地方仍会选中幽灵。

### C. CLI 子进程 stdin 永不关闭 → 会读 stdin 的 CLI 静默挂死

`ProcessBuilder` 默认给子进程一条管道，宿主既不写也不关。`codex exec` 支持管道喂 prompt ⇒ 它先把 `Reading additional input from stdin...` 打出来，然后**连会话都不建、一行不打、占着 worktree 不放**（实测 2 分钟零输出；agy 配额重试那轮同样形态，最后靠人工 kill）。

修法：`start()` 之后立刻 `proc.outputStream.close()`（两处拉起点：`CliSubprocessDriver`、`WorktreeSandbox`）。

**这条只能真机验**：Gradle 测试进程给子进程的 stdin 已经是 EOF，单测复现不出挂起（试过 `cat` / `read -t` 两种假 CLI，都是立刻返回），所以**没有留单测**，只在代码里写明现场证据。

## 2. 两处会让人误判的静默 + 一处环境坑

| # | 形态 | 之后 |
|---|---|---|
| 收割静默为空 | `!patch.ok` 与 `parseStat==0` 两条 `return null` 一行日志都不打 ⇒「没有交付物」与「收割失败」在外部完全同形（一轮 16 分钟的真实执行因此无法归因） | 两条分支 + 非零退出各打一行，**并同时 append 进 `task.log`**（GUI/`open` 启动时宿主 stdout 没人接，结论必须跟着产物走） |
| codex 参数 | `codex exec` **默认 read-only**，连自己 worktree 都写不了（`writing is blocked by read-only sandbox`）⇒ 每次派单「跑完了但没有交付物」 | 参数固定 `exec --json --sandbox workspace-write`；宿主已在 seatbelt 里跑时可显式 `TIDINGS_CODEX_SANDBOX=danger-full-access`（认不出的值一律回落默认） |
| GUI 启动没 PATH | 从 LaunchServices 拉起的宿主只有最小 PATH，而 codex 是 `#!/usr/bin/env node` 脚本 ⇒ `env: node: No such file or directory`，`cli exit=127`（同一个 codex 从终端起就正常） | `CliDiscovery.childPath()`：宿主 PATH + 发现器约定目录 + nvm/volta/bun 的 bin，注入子进程环境 |

## 3. 第 2 号发现 · 幕 6 在真机上没有入口

> **§10 已推翻本节结论**（同一台真机、同一天补测）。本节的三条证据都成立，但归因错了：
> 缺的**不是**「会话/卡片下发」这一段产品能力，而是①群里没有 CLI 席位（装置问题，补一条
> `virtualAgents` 即可）②`onHostCardAction` 那条跳把 `sessionId` 恒传 null，合并分支永远进不去
> （真缺陷，已在 §10 修）。手机侧派单、收卡、点卡全部可用。

三类证据同时指向同一件事：

1. **手机库里没有这条会话**：`AgentSessionEntity` 最新的仍是 `dsh` 的旧会话（`m5-approve-1` 等），幕 4/5 的 `codex-cli` 会话一条都没有。
2. **手机界面上没有 diff 卡**：Agent 会话页只有旧的「等待你审批工具调用…」行，上滑三轮也没有任何卡片。
3. **两端都没有 patch 文件消息**：`MessageEntity` 里既没有 `file_json` 的 patch，也没有 `card_json` 的新行。

而合流动作的落点在 `DesktopExtension.onHostCardAction`（`actionId == "merge-patch"` → `DesktopPatchMerge.apply`），**只有宿主 UI 的卡片点击会调它**，控制面没有对应 HTTP 路由。

⇒ 派单产物与卡片只活在**宿主会话台账**里；「人在手机上点 [合并此补丁]」这条路当前不存在。这不是脚本写错，是产品缺一段：**会话/卡片到手机的下发**。

## 4. 装置说明（读数必须带着它读）

- **驱动换人**：`agy` 上游 `daily-cloudcode-pa.googleapis.com` 连续 7 小时 502（`Eligibility check failed … Bad Gateway`，裸命令复现），幕 4/5 改由 `codex-cli` 执行。证的是「派单 → 执行 → 收割 → 产物」这条链，不是某个具体厂商。
- **两次合流是人工预置**：幕 2 的设计、幕 4 的实现都以 `git apply` 落进宿主仓再提交（`3034aab`、`0bfe75a`），因为第 2 号发现那条合并入口不存在；沙箱 worktree 从 `HEAD` 开，不预置的话下一幕根本看不到上一幕的产物。
- **启动方式有讲究**：宿主必须从**终端**拉起才能跑 codex（GUI 拉起缺 shell 环境）；而宿主自己被 seatbelt 包着时，codex 的 `sandbox-exec` 套不进去，需要那枚 `TIDINGS_CODEX_SANDBOX` 逃生口。

## 5. 复现

```bash
# 宿主（终端启动，带逃生口）
cd /Applications/Tidings.app/Contents/MacOS && TIDINGS_CODEX_SANDBOX=danger-full-access ./Tidings > /tmp/tidings-app.log 2>&1 &

# 单幕（幕 3 要在手机私聊里点批准；幕 4/5 各 6–10 分钟）
E2E_AGENT_KEY=codex python3 scripts/e2e-team-pipeline.py --act 4 --tier real
```

## 6. 该修什么（按优先级）

1. ~~**会话/卡片下发到手机**~~ → **归因错误，撤回**（§10）：下发是通的；真缺陷是合流分支的恒假判据（`sessionId` 恒 null）。→ **已修（§10.3/§10.4）**。
2. **幽灵 peer 的全局治理**：`pickHookPeer` 只保住了审批一条路，发送侧 `[send] peer=44608041` 还在。
3. **派单缺少超时**：CLI 挂住时任务会一直占着 worktree（这轮靠人工 kill 两次）。止损点应在宿主侧，不该靠人盯。→ **已修（§9）**。
4. **沙箱不是边界**：上一轮 102 条命令里 27 条直接操作宿主用户目录（`cd ~/e2e-weather-app && git …`、`find ~ -name …`），`SandboxedCliDriver` 只改 cwd，没有路径约束。

## 7. 落账（一个缺陷一笔）

| commit | 内容 | 反向验证 |
|---|---|---|
| `d2f369d2` | 审批回执推进后唤醒长轮询（`applyAckToApproval` 打通知） | `appliedAckWakesTheWaitingLongPoll`：摘掉通知行即红 |
| `a22fdf04` | 幽灵 peer：抽 `pickHookPeer`，先按 `ip:udpPort` 折叠取 `profileRev` 最大，再取 nodeId 最小 | `HookPeerPickTest` 4 条：摘掉折叠即红 |
| `a6646b08` | 派单三条阻断：spawn 后关 stdin / codex 固定 `--sandbox workspace-write`（留 `TIDINGS_CODEX_SANDBOX` 逃生口）/ `CliDiscovery.childPath()` 补解释器 PATH；收割路径两处静默 `return null` 改 `note()` | `childPath` 与 `codexSandboxMode` 两条用例；stdin 一条只真机可复现（单测里子进程 stdin 已是 EOF），已在代码注释写明 |
| `584f54b5` | E2E 判据改成真机可证的读数（群消息不发只观测、守门看卡片 `decidedBy`、产物只认本次新增、`relay.db-wal` 同读）+ 本文件 | 幕表重跑：判据改完后幕 4/5 仍绿、幕 6 如实红 |

收工读数：`:agentNode:test` 全量绿 2m6s；施工痕迹门禁 17 根 0 命中；`git status` 净。

## 8. 剩余风险

- 发送路径仍会选中幽灵身份（`[send] peer=44608041 channel=TCP`），折叠只护住审批一侧。
- 沙箱只改 `cwd`、无路径约束（§6 第 4 条）。

## 9. 派单止损（§6 第 3 条的落地）

`WorktreeSandboxExecutor.run` 加了**墙钟**上限：默认 30 min（`TIDINGS_SANDBOX_TIMEOUT_MS` 可覆盖，
认不出的值一律回落默认 —— `0` / 负数 / 乱写不许变成「立刻杀掉」）。超过上限按 D-024 中止序
摘掉进程树、宽限后 SIGKILL、**等它真死**再清理 worktree，然后回一个新的终局
`Outcome.TimedOut(timeoutMs)`，并与 `task.log` 里写一行真因。

为什么单列一档而不是并进 `Aborted`：中止是**外部要求**（宿主 HTTP / 调用方取消），超时是
**宿主自主止损**。混成一档，复盘时会把一次自主止损读成一次人为中断。

默认 30 min 的取值依据就是本文件的读数：幕 2 201 s / 幕 4 395 s / 幕 5 ≈10 min，留 ~3 倍余量。

反向验证（真行为的红，不是编译红）：`aHungCliIsStoppedAtTheDeadlineAndTheWorktreeIsCleaned`
用一个 `sleep 300`、一个字都不打的假 CLI —— 接线前它跑到测试外层 `withTimeout(20s)` 抛
`TimeoutCancellationException`（「挂住的派单永远不结束」），接线后 800 ms 上限 + 宽限共 ~4 s
内收场，并验 worktree 与分支都清掉、`task.log` 里有 `timeout`。守反向一条
`aFastRunInsideTheDeadlineIsNotKilled`（60 s 里跑完的不许误杀）+ env 回落一条。

## 10. 幕 6 补测 · 手机派单与点卡合流（推翻 §3）

**驱动方式**：全程 adb 驱动真机（Pixel 6 / `com.kapp.tenon.debug`），未经人手；宿主是
`open -a /Applications/Tidings.app` 起的 GUI 进程（PID 38550，控制面 `:8788`）。

### 10.1 装置准备（不是产品路径）

群里要有一个 CLI 席位，`@` 候选才有得选。宿主与手机的 `settings.json` 各写一条、`rev` 置 2：

```json
{"agentId":"codex","hostNodeId":"46c23456-6e74-4a7c-aab1-20a1d1284cd6",
 "name":"codex-e2e","defaultWsId":"ws-weather-e2e"}
```

补发**不靠产品路径**：`rebroadcastGroupMeta` 挂在「网络相位 + 在线对端集合」的
`distinctUntilChanged` 上，而对端集合里有个永远在线的幽灵 peer（§8 第 1 条），集合不变 ⇒
永不重发。只能改文件。这条本身是 §8 第 1 条的又一个后果。

### 10.2 一次派单的完整读数（09:05:24 → 09:06:56，91 s）

| 环节 | 读数 |
|---|---|
| 手机发群消息 | 气泡 `create MEREPROOF.md …`，09:55 落库 `8e842e3e`（`conv=group:626bed34…`） |
| 宿主开沙箱 | `/tmp/tenon-ws/t-1-0bb5f2eb` + `artifacts/t-1-0bb5f2eb/task.log` 同时出现 |
| CLI 执行 | `task.log` 里 `item_4 file_change` 建 `MEREPROOF.md`，`item_6 cat` 回 `e2e ok`，`turn.completed` |
| 收割 | `[sandbox] harvest t-1-0bb5f2eb: +1 -0 in 1 file(s), patch=140 B`；`report.json` `durationMs=91054` |
| 无痕清理 | worktree 与分支在收场后消失，只留 `artifacts/` 三件（`task.log` / `patch.diff` / `report.json`） |
| 回群 | `MessageEntity` 新增 `8cb1720e`，`sender_id=agent:46c23456…:codex`，`card_json` rev 1（308 B） |
| 到手机 | 群里出现 diff-review 卡「e2e-weather-app 沙箱任务完成 · 第 1 版 +1 −0」，`patch.diff 140 B`，带 **[合并此补丁]** |

⇒ **手机派单 → 宿主沙箱 → CLI → 收割 → 卡回群 → 手机上可点**，这一整段是通的。§3 的
「产品缺会话/卡片下发」不成立。

### 10.3 点卡不合并（真缺陷 · 已修）

点 [合并此补丁]（09:57:26）后：卡面变成「第 2 版 · 已由 Pixel 6 处理」，宿主 `card_payload`
落 `decidedBy=Pixel 6 / decidedActionId=merge-patch`（说明动作帧到了宿主、过了资格门、
rev+1 也广播了），但宿主仓 `git log` 一个提交都没有、`git status` 空。

真因是一条**恒假判据**：

```kotlin
// RelayControllerAgentInvokeComponent.kt:914 —— 唯一调用点，第二参写死 null
onHostCardAction?.invoke(agentId, null, cardId, actionId, decidedBy)

// DesktopExtension.kt:931 —— 合并分支要求它非空
if (actionId == "merge-patch" && sessionId != null) { … DesktopPatchMerge.apply(…) }
```

`op=action` 帧的载荷是 `groupId/cardId/rev/actionId/decidedBy`，**协议里没有会话位**，
所以那一跳永远是 `null`，合并分支在这个产品里从来没有执行过；点击只会掉进 else 被当成
插件动作丢掉。会话归属其实一直在台账里（派单出卡时写了 `eventType=card`、`body=` 卡片 JSON），
只是没人去反查。

修法（按卡片反查，不新增协议键）：

- `ControlPlane.latestPatchRefForCard(cardId)`：按 `card` 事件里的 cardId 反查会话，
  复用会话那条 `latestPatchRefFor`，返回 `SandboxPatchRef(sessionId, workspacePath, patchPath)` 三样一起给
  —— 冲突要落 `merge-result` 事件，没有会话就落不了账。
- `DesktopExtension`：`sessionId` 为空就按卡片找；冲突且无会话时如实打日志，不假装记过账。
- 守卫测试加一条：源码里必须同时有 `latestPatchRefForCard(` —— 旧守卫只锚了
  `latestPatchRefFor(`，所以这个缺陷能活到真机。

### 10.4 修后复验（09:07:00 → 09:07:44，44 s）

重打包 → 换 `/Applications/Tidings.app`（旧的挪到 `/tmp/Tidings.app.old-20261101-090203`）→
重启宿主，然后同一条手机链路重跑一遍（新 cardId `diff-t-1-7ee79fe2`，避开按 `cardId|action`
幂等的判定台账）：

| 环节 | 读数 |
|---|---|
| 派单出卡 | `report.json` `durationMs=12372`、`filesChanged=1`；卡「第 1 版 +0 −0」到手机 |
| 点 [合并此补丁] 前 | 宿主仓 `git status` 空、无 `MEREPROOF*` |
| 点后 | **`?? MEREPROOF@c`**（0 字节，09:07，正是 83 B patch 里 `new file mode 100644` 那个文件） |

⇒ 「人在手机上点一下 → 宿主仓被 `git apply` 改掉」这条现在是**真行为**，不是推演。
（`DesktopPatchMerge` 只 apply 不提交 —— 提交是机主的下一步，判据就该看工作区而不是 `git log`。）

### 10.5 同轮撞出来的两个观察项（未修，待定性）

1. **群信息面板「入驻智能体 (1)」有计数没有行**：`入驻智能体 (1)` 与下面的 `解散群聊` 之间
   没有任何可点节点（`uiautomator dump` 逐节点验过），于是面板里那条「长按成员行直接 @」
   的入口在当前装置上摸不到。是行高为 0 还是被过滤，未定论。
2. **后台拉起即崩**：锁屏状态下 `am start` / `monkey` 拉起时，`RelayApp.onCreate` 里
   `RelayService.start` 抛 `ForegroundServiceStartNotAllowedException`
   （`mAllowStartForeground false`，Android 12+ 限制）⇒ 进程直接 FATAL。
   正常点图标进是前台态、不复现，但「开机/自启/被通知拉起」这类路径会踩到。

### 10.6 装置坑（下一轮别再踩）

- **`adb shell input text` 会在 @ 候选浮层上吞字**：`@codex-e2e` 被截成 `@code`、`MEREPROOF.md`
  掉成 `MEREPROOF.d`、`create` 掉成 `ceate`。可行组合是「打 `@c` → 点候选 → 一个空格键触发一次
  逐字编辑」，胶囊在那一刻才绑上（`capsuleOnDraftEdit` 只认 ±2 的编辑，整段插入算 `Pasted`、
  不动胶囊；大段删除同理，能**保留**已有绑定）。
- **`input keyevent 4`（BACK）会把 App 退出到锁屏**，别用它关浮层；点消息区空白处即可。
- **手机 `sqlite3` 查库必须连带 `-wal`**（否则看不到刚写进去的行）。
- `ps` 在本机沙箱里被拒（`operation not permitted`），判进程要用 `pgrep -f`；我一开始把
  `ps` 的空输出读成「没进程」，差点误判派单没起来。



