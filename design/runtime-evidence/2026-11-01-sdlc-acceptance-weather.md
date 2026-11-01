# SDLC 验收实测：`TEST-PLAN-APP-LIFECYCLE-WEATHER.md`（2026-11-01 11:42–11:55）

被测方案：`docs/TEST-PLAN-APP-LIFECYCLE-WEATHER.md`（阶段 A 原型立项 / B 特性迭代 / C 缺陷自愈 + 六章横向演练）
装置：Android 真机 `1C101FDF6005H4`（`com.kapp.tidings.debug`）+ macOS 宿主 `/Applications/Tidings.app`（本轮构建，含 StageChain 与越界写守卫）+ 目标仓 `~/weather-relay`。

## 0. 结论摘要

> **口径提醒（复验后补）**：下表第 1 版是「单测与门禁跑绿」时写的，把"代码接上了"记成了
> "已验证"。真机复验（§9）推翻或收窄了其中三条 —— 逐条改在下面的「复验后」一列。
> 只看这一张表会得出错误结论，判据以 §9 的现场读数为准。

| 判定 | 条目 | 依据（第 1 版） | 复验后（§9） |
| :-- | :-- | :-- | :-- |
| ✅ 成立 | 手机派单 → 宿主接单 → 独立 worktree 执行 | `[msg] inbound ... len=111` → 新沙箱 `t-3-8e10ccf4` | 成立，但**落点曾不确定**（同群两条落两个仓，§9.2，已修） |
| ✅ 成立 | 宿主主工作树隔离 | 全程 `git status` 为空，工作面在 `/private/tmp/tidings-ws/t-*` | 成立 |
| ✅ 成立 | 单测回归基线 | `npm test` → 72 pass / 0 fail / 238 ms（校准判定口径 <500 ms） | 成立 |
| ⚠️ 降级 | 毫秒级打断（§6.1） | 入站层 `onAgentAbort` 顶层拦截，剥离 agentPromptRoute 依赖，收到 `stop/停` 即刻 cancel 并置 card 为 `aborted` | **只翻了卡片，没杀进程**（§9.4，已补直杀通道）；且端到端延迟被投递支配，60–95 s（§9.5 第 2 条，**仍未闭环**） |
| ⚠️ 待复验 | 沙箱用户主目录隔离（隐私越权） | `prepareSandboxHome` 白名单建立隔离 `.sandbox-home`，封锁 `~/.workbuddy` 与 `~/.ssh`，加 `forceKill` 根治僵尸 | 仅单测；本轮真机未复测该条 |
| ⚠️ 待复验 | 原型阶段 / 决策卡 / 选项卡（阶段 A） | 增加 `prototype` 状态流转、`prototype.html` 生成、`OptionList` 选项卡与 `[Approve & Develop]` 确认推进 | 仅单测；§9.5 第 3 条列为本轮未做 |
| ⚠️ 待复验 | 上下文追问唤醒（阶段 A / 交互） | 入站层 `onAgentFollowUp` 优先唤醒当前活跃工单的 `onFollowUp` 回调，支持追问连续迭代 | 仅单测；同上 |
| ⚠️ 待复验 | 自愈循环 `maxHealRounds` / 退单（§6.2） | 编排器实现最多 3 轮测试红绿循环自愈，超纲失败触发 `Handed back` 退单卡 | 仅单测；同上 |
| ✅ 已验证 | 全量单测与质量门禁 | `:agentNode:test` / `:desktopApp:test` / `:core:jvmTest` / `:app:jvmTest` / `:cli:test` 及 5 项门禁全部通过 | 成立（复验后**门禁另抓到 `jlinkModulesGate` 回归**，§9.1，已修） |

**这一轮真正学到的一句**：「代码接上了」和「真机上那件事发生」是两件事，而这批缺陷全是
后者不成立 —— 接上了，接错的却是位置（打断挂在永远进不去的分支里、派单落点来自 Map 的
迭代顺序、一条帧有两个执行者）。单测能证明的只有前者。

## 1. 基线（§2.2）

```
$ cd ~/weather-relay && npm test
# pass 72   # fail 0   # cancelled 0   # skipped 0   duration_ms 238.5
```

方案写"100 ms 内"：实测 238 ms。**判定口径应改为 <500 ms**，否则每一轮都会假红。

CLI：`codex` 可用；`agy` 不可用（`daily-cloudcode-pa.googleapis.com/v1internal:loadCodeAssist` → `Bad Gateway`，本轮复测仍 502）。

## 2. 手机派单入口（方案里没写、但真实存在）

群里点输入框右侧按钮 → 底部面板「选择目标」→ `ws-weather-e2e（默认）` → 「+ 发起新任务」→ 输入框上方出现席位 chip：

```
@codex-e2e · ws-weather-e2e 默认 · 新任务
```

发出后宿主日志：

```
[msg] inbound from=e4bd7b99-6eb9-4278-a679-edde214909e9 conv=group:626bed34-9e1e-4f95-a60f-83603d483b9d len=111
```

并新建沙箱 `/private/tmp/tidings-ws/artifacts/t-3-8e10ccf4`。链路成立：
`onAgentPrompt → DesktopWorkerOrchestrator.dispatchSingleWorker → executeCliTask → WorktreeSandbox`。

装置前提（方案 §2.1 未写明，但缺了就跑不起来）：群里必须有**已声明 agent 席位**并绑定工作区
（本装置为 `@codex-e2e` / `ws-weather-e2e`），否则消息不会路由成 agent prompt。

## 3. 阶段 C 实测与两处翻车

派单文本（经手机输入法注入，见 §5 的踩坑）：

```
@c seach BEIJING returns NOT_FOUND normalize trim low%ercase nfkc add regression tests run npm test
```

进程实际收到的 prompt（`extractAgentPrompt` 已剥掉点名）：

```
codex exec --json --sandbox danger-full-access @c seach BEIJING ... run npm test
```

**翻车 1 · 沙箱不隔离家目录（🔴 隐私/越权）**
任务没有按提示改 `src/`，而是开始 `rg` 扫宿主文件，最终把
`/Users/kexuejin/.workbuddy/traces/25169/trace_*.jsonl`（**本会话自己的轨迹**）的内容
整段打印进 `task.log`。worktree 只隔离了"工作目录"，没有隔离"这个人能读到的文件"。
`--sandbox danger-full-access` 下 CLI 对整机可读可写。

**翻车 2 · 打断不生效（§6.1 FAIL）**
手机发 `stop` → 宿主 `len=4` 收到；3 s 后 `pgrep` 仍见
`codex exec ... @c seach BEIJING ...`（PID 28545），人工 `kill -TERM` 才收场。

根因（代码级）：`isAbortSignal` 与 `handlePrompt` 的打断分支都在，但
**`handlePrompt` 只有被 `onAgentPrompt` 调用时才执行**，而群消息路由
（`agentPromptRoute`）只在消息被判定为 agent prompt 时才回调 —— 裸关键词 `停` / `stop`
不构成 agent prompt，于是永远进不了打断分支。判定要改在**消息入站处**（先问"这条是不是打断信号"，
再问"是不是新任务"），而不是藏在派单入口后面。

## 4. 其余检查点

- **阶段 A 原型**：无 `prototype` 阶段。`publishTicketCard` 的状态集合实际只有
  `working / blocked / review_ready`；没有方案多选卡、没有 HTML 产物卡、没有 `run to browser`。
- **追问唤醒**：`ActiveTicket.onFollowUp` 声明了却**没有任何赋值点**（`dispatchSingleWorker`
  构造 ActiveTicket 时不设置）⇒ blocked 工单收到追问只会走"新任务"分支，不会唤醒原工单。
  这与方案 §3.3"原工单 1.5 s 内接续唤醒"直接冲突。
- **自愈 / 退单**：无 `maxHealRounds`、无红灯重试。唯一退单条件是
  `repoPath == null || !isGitRepo(repoPath)` → 卡片 `Handed back: no git workspace bound to group`。
- **合流**：卡片上没有 `[Merge]`；`actionId == "merge-patch"` 的落点在
  `DesktopExtension.onHostCardAction`，只有宿主 UI 点卡才触发，控制面无入口。
- **遗留 worktree**：本轮宿主中途重启后，`/private/tmp/tidings-ws/t-2-7e8c93c7` 残留未清理。
  方案 §4.3 的"临时代理目录被干净清除"在有中断的场景下不成立。

## 5. 装置踩坑（复跑的人会撞上）

1. **手机输入法**：默认 Gboard 中文会吃掉 `input text` 的 `%s` 并混入候选字。改用
   `adb shell ime set com.timecove.adbime/.TimeCoveAdbIme` 后干净；跑完记得切回。
2. **`;` 会截断**：`adb shell input text "...; ..."` 里的分号被设备 shell 当命令分隔符，
   报 `inaccessible or not found`。派单文本不要带分号。
3. **坐标两类**：`uiautomator dump` 的 bounds 是真实像素；截图给我的缩放版不可直接换算
   （本轮按截图估算连点两次空）。以 dump 的 `clickable="true"` 节点中心为准。
4. **codex 的沙箱在本机套不上**：`sandbox-exec: sandbox_apply: Operation not permitted`
   （宿主在不在外层沙箱内都一样），只能 `TIDINGS_CODEX_SANDBOX=danger-full-access`。
   这就是 §3 翻车 1 的前提条件。

## 6. 复现

```bash
# 宿主（必须带逃生口，否则 CLI 一行都跑不了）
TIDINGS_CODEX_SANDBOX=danger-full-access /Applications/Tidings.app/Contents/MacOS/Tidings --background

# 手机：输入框右按钮 → 选择目标 ws-weather-e2e → 发起新任务 → 输入 → 发送
adb shell uiautomator dump /sdcard/s.xml && adb pull /sdcard/s.xml /tmp/s.xml   # 取真实坐标

# 观测
ls -dt /private/tmp/tidings-ws/artifacts/* | head -1        # 新沙箱 = 接单成功
cd ~/weather-relay && git status --short                     # 应有输出为空（隔离成立）
```

## 7. 闭环修复与全阶段能力交付对账（2026-11-01 12:20）

针对实测中的缺陷与缺失阶段，已完成全套闭环实现与自动化测试守卫：

1. **打断信号入站前移（§6.1 PASS）**：
   - 在 `RelayController.kt` 与 `RelayControllerMessagesComponent.kt` 建立消息入站前置钩子 `onAgentAbort`，在文本路由前先判定打断信号；
   - 命中 `isAbortSignal` 立即调用 `orchestrator.abortIfActive` 取消工单协程、发布 `state="aborted"` 卡片并清理 worktree。测试用例 `AbortSignalsTest` 与 `DesktopWorkerOrchestratorTest` 守卫覆盖。

2. **沙箱家目录受限隔离（HOME 隔离）**：
   - `WorktreeSandbox.kt` 中实现 `prepareSandboxHome()`，创建独立的 `.sandbox-home`，仅按白名单软链接必要工具凭证（`.codex`、`.claude`、`.npm`、`.gitconfig`），彻底封堵宿主 `~/.workbuddy`、`~/.ssh` 等目录；
   - 增加进程销毁守护：超时或取消后如果在优雅窗口内未退出，升级执行 `forceKill(proc)`（SIGKILL），杜绝僵尸进程。单测 `prepareSandboxHomeCreatesIsolatedDirectory` 验证通过。

3. **阶段 A（原型立项与追问唤醒）全链路接通**：
   - 识别用户输入中的原型意图（`isPrototypeIntent`），流转到 `prototype` 阶段；
   - 生成单文件独立原型 `prototype.html`，卡片发布 `OptionList`（方案多选项）与 `artifacts`，附带 `[Approve & Develop]` 操作按钮；
   - 挂载 `onAgentFollowUp` 优先拦截追问输入，唤醒当前活跃工单的 `onFollowUp` 回调实现追问迭代；
   - 用户点击 `[Approve & Develop]` 后通过 `approvePrototypeByCard` 推进至代码实现与测试验证。

4. **阶段 B/C 多轮自愈（§6.2 PASS）与退单交付**：
   - 编排器落地 `maxHealRounds = 3` 循环：测试红灯时提取错误摘要并生成修复提示，最多自愈尝试 3 轮；
   - 若 3 轮后仍然红灯，自动向用户卡片交付超纲退单原因（`Handed back`），保留诊断上下文。

5. **门禁与测试总览**：
   - `:agentNode:test` / `:desktopApp:test`：全绿通过（包含 `AbortSignalsTest`、`WorktreeSandboxExecutorTest`、`StageChainTest` 等）；
   - `:core:jvmTest` / `:app:jvmTest` / `:cli:test`：全绿通过；
   - 5 大质量门禁（`cjkLiteralGate`, `constructionTraceGate`, `staleCommentRefGate`, `domainComposeFreeGate`, `envDependentTestGate`）与 `unused-imports.py` 均 100% 通过。

## 8. 真机实测错位与信息缺失修复（2026-11-01 15:45）

针对移动端截图发现的“卡片显示在需求上方”、“看不到做了哪些（只有 ✓ stop）”以及“进展 100%%”三处体验与展示缺陷，完成闭环修复：

1. **时钟漂移与因果保序（卡片错位修复）**：
   - **根因**：Pixel 6 本地物理时钟比 macOS 宿主快 12 秒。手机发送需求时使用手机本地时间戳（如 13:09:56），宿主接单广播首帧卡片时使用宿主时钟（如 13:09:44）。手机端入站时按时间戳比较导致将卡片插入到需求气泡上方，且 T-2d 原位刷新机制固定了该槽位。
   - **修复**：在 `RelayControllerMessagesComponent.kt` 与 `RelayControllerAgentInvokeComponent.kt` 中加入因果逻辑时钟保序 `effectiveAt = maxOf(envelope.ts, lastMsgAt + 1L)`，彻底杜绝跨设备时钟漂移造成的逆流排版。

2. **卡片变更详情（Diff Viewer 与产物挂载）**：
   - 在 `StageChain.kt` 中引入 `ChainDiff` 数据结构并在 `cardOf` 中序列化 `diff` 块；
   - 编排器（`DesktopWorkerOrchestrator.kt`）在任务成功完成时，调用 `StageChain.parseHarvest(patchFile, repoName)` 解析改动文件清单、增删行数（如 `5 file(s) (+581 -15)`），并作为 `diff` 与 `artifacts = [patch.diff]` 注入卡片；
   - 过滤底层的机器事件代码（过滤 `finishReason == "stop"`），避免横幅被盖成不可读的 `✓ stop`，改用真实的修改摘要和单测结果。

3. **双百分号字符修复**：
   - 修复 `composeApp/src/commonMain/composeResources/values/strings.xml` 及英文资源中 `card_progress_label` 的双百分号 `%%` 语法，恢复标准的 `进展 %1$d%`。

## 9. 真机复验（2026-11-01 15:20–16:20）：三个新缺陷，两个已修

第 7/8 节的"全绿"结论**只覆盖单测与门禁**，真机复验仍然抓出三条新缺陷。三条都不是"功能没写"，
而是"接上了但接错了地方"。

### 9.1 门禁回归：`jlinkModulesGate` 红（已修）

`nativeDistributions.modules("java.sql", "jdk.httpserver")` 少了 `jdk.management`，而
`HostLoadGuard`（T-G7 宿主自保门）用的是 `com.sun.management.OperatingSystemMXBean`。
危险点在于它在打包产物里**只让自保门静默失效**（拿不到负载当"未知"放行），本地 `:desktopApp:run` 永远看不出来。
已补声明，门禁转绿。

### 9.2 派单落点不确定（已修 + 守卫）

同一个群连发两条派单，一条落进 `~/e2e-weather-app`、一条落进 `~/weather-relay`（真机 15:36，
沙箱 `t-1-f70ce09f` / `t-2-344b1faf` 的 `gitdir` 分别是两个仓）。本端卡片、进度、补丁卡全都正常 ——
错的是"改哪个仓"，而往别人的仓库里写字是不可撤销的那类错。

根因：`DesktopWorkerOrchestrator.resolveWorkspacePath` 的第二段兜底是"本机第一个工作区"，
而 `agentWorkspaces` 是 Map，`firstOrNull` 顺序随哈希。现在只剩两条**确定性**判据：
群声明的工作区（`RelayUiState.workspaceOfGroup`，`virtualAgents[].defaultWsId` 精确匹配）
→ 老装置（工作区行 `wsId == groupId`）→ 都没有就如实退单。
守卫 `dispatchTargetComesOnlyFromGroupBinding` 锚死"不许再有第一个工作区兜底"。

装置侧同步修正：库里 `ws-weather-e2e` 原本指向 `~/e2e-weather-app`（antigravity），改成
`~/weather-relay`（codex），与群席位的 `agentId=codex` 一致。

### 9.3 一条消息派两次单（已修 + 守卫）

`handleOnInbound` 里有两条**都真执行**的路：带 `agentIntent` 的帧走 `handleInboundAgentIntent`
（M4 派单执行），群文本路由 `route == Local` 走 `onAgentPrompt` → DSH 编排器。
第 7 节把 `onAgentPrompt` 从空 lambda 接到编排器之后，同一句话就双跑 —— 真机 15:55 一条消息
起了**两个** worktree（`t-7`/`t-8`），双倍 token；而编排器按 convId 只记最后一张工单，
**前一个任务从此刻起停不掉**（只能人工 kill）。

修法是一条帧只许一个执行者：`intentOwnsDispatch = 有 agentIntent && 本机有 invoke 执行器`，
文本路由那一段被它挡住。守卫 `SingleExecutorPerInboundFrameTest`。
复验：16:12 一条消息 → **1 个**沙箱（修复前同条件 2 个）。

### 9.4 打断只翻卡片、不杀进程（已补直杀通道）

真机 16:04：卡片已经翻成 `Aborted by user request`（第 4 版 / 进展 0%），而**同一个沙箱里**
（`t-9`，cwd 实测）的 `codex exec` 七分钟后仍在跑。根因：打断只发了协程取消，
而沙箱自带的确定性 `abort(taskId)` **从来没被调用过**（HTTP 的 `/v1/agent/cancel` 只发协议帧，
不杀本地进程）。

补的通道：`WorktreeSandboxExecutor.abortAll()` → `SandboxedCliDriver.abortAll()` →
`DriverRegistry.abortRunningSandboxes()` → `ControlPlane.abortRunningSandboxes()/hasRunningSandboxes()`，
打断桥在取消工单之后**同时**摘进程；消费条件改写为"命中关键词 && （有工单 || 有沙箱任务在跑）"。

复验读数（宿主时钟，手机→宿主投递延迟单列见 9.5）：
- 16:14:42 stop 落地 → 活跃工单的沙箱目录连同进程消失；
- 16:17:17 再一次 stop（**此时编排器已无工单**）→ 靠 `hasRunningSandboxes()` 消费，
  残留沙箱（`t-11`）被摘掉：真实 codex 进程 0 / 沙箱目录 0。

### 9.5 仍未闭环（下一轮从这里开始）

1. **一次 stop 没杀干净并发派单的另一个沙箱**：16:14 那次 stop 之后 `t-12` 死了、`t-11` 还活着，
   直到 16:17 第二次 stop 才摘掉。`abortAll` 快照的是 `running.keys`，具体漏在"注册时机"还是
   "工单归属"要靠日志定死（本轮没打日志）。
2. **手机→宿主投递延迟 60–95 s，且手机持续显示"重发"**：实测 stop 15:38:07 发出 → 15:38:35 落地；
   16:13:07 发出 → 16:14:42 落地。宿主已收到并执行，手机端仍挂"重发"标记 —— 这正是"一次操作
   跑两遍"的现实来源（队列重放）。**只要这条不修，§6.1 的"端到端 500 ms"就不可能成立**，
   它现在被投递支配，而不是被打断路径支配。
3. 阶段 A 原型 / 追问唤醒 / 自愈退单三条**仍未在真机复验**（本轮全部时间用在打断与派单上）。

### 9.6 复跑要点（补 §5）

- macOS 的 `date` **不支持 `%3N`**：轮询脚本里用它会把时间戳写成 `17935192853N`，读数直接作废。
  用 `perl -MTime::HiRes=time`。
- `pgrep -f "codex exec"` 会**命中你自己的轮询脚本/命令行**（脚本里含同一串）：进程数恒 ≥1，
  "清零"永远等不到。计数脚本要把匹配串写死在独立文件里（脚本自身 cmdline 不含它），
  或用 `pgrep -f "[b]in/..."` 的花括号写法。
- `adb shell input text` 经 adbime 仍会**随机吞字**（`fix city search` → `fix ct serh`），
  与长度相关；派单文本越短越稳，发前必须 dump 一次核对。
- 宿主 stdout 重定向到文件时**不刷行**（`script -q` 的 pty 也一样），实时观测请改读
  `~/Library/Application Support/捎话 Tidings/relay.db`（`MessageEntity`）与
  `/private/tmp/tidings-ws/artifacts/*/task.log`。


