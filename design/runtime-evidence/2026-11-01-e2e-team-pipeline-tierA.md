# 真机实测：多 Agent 团队流水线 Tier A（人 + 手机 + 宿主，2026-11-01）

> **一句话结论**：五幕里 **0/1/2/3/4/5 全部真机跑通**（幕 4 实现 59 KB 补丁、幕 5 审查给出 `VERDICT: PASS` 并挑出 4 条真问题），但**幕 6「人点合并」在真机上没有入口**：派单会话与 diff 卡都没到手机，手机侧 Agent 会话还停在旧会话，合流动作只能从宿主 UI 的卡片点击触发。过程中修掉三个**阻断性缺陷**（审批回执不唤醒长轮询、幽灵 peer 抢占审批收件人、CLI 子进程 stdin 永不关导致挂死）与两处会让人误判的静默。

## 0. 结论速览

| 环节 | 读数 | 性质 |
|---|---|---|
| 幕 0 探针 | `/v1/peers`、`/v1/agent/sessions` 双 200；`agy/claude/codex` 均在 PATH；真机 `e4bd7b99` rev4，幽灵 `44608041` rev0 同时在线 | ✓ 装置就绪 |
| 幕 1 组队 | 工作区宣告 200 `sent`；群 `e2e-weather` = `626bed34…`，成员含真机 | ✓ |
| 幕 2 设计 | `t-1-9e5d8d65` → patch.diff **54223 B**（prototype.html 1538 行），201 s | ✓ |
| 幕 3 守门 | 审批长轮询 **12 s / 12 s 两次均 allow**，手机库对应行 `approved` | ✓（修完缺陷 A/B 后） |
| 幕 4 实现 | `t-2-23bf313f` → patch.diff **59242 B**（weather.html 1663 行），395 s | ✓ |
| 幕 5 审查 | `t-3-a66c413e` → patch.diff **3414 B**（REVIEW.md 20 行，`VERDICT: PASS` + 4 条问题），≈10 min | ✓ |
| 幕 6 合流 | **无法执行**：手机上既没有 diff 卡、也没有这条会话 | ✗ 阻塞（第 2 号发现） |
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

1. **会话/卡片下发到手机**（挡住幕 6，也是「团队流水线」的产品闭环缺口）：派单会话要能到手机，diff 卡要能在手机上点 [合并此补丁]。
2. **幽灵 peer 的全局治理**：`pickHookPeer` 只保住了审批一条路，发送侧 `[send] peer=44608041` 还在。
3. **派单缺少超时**：CLI 挂住时任务会一直占着 worktree（这轮靠人工 kill 两次）。止损点应在宿主侧，不该靠人盯。
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
- 派单无超时止损：CLI 挂住就一直占 worktree（本轮人工 kill 两次）。
- 沙箱只改 `cwd`、无路径约束（§6 第 4 条）。

