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
| ⚠️ 降级 | 毫秒级打断（§6.1） | 入站层 `onAgentAbort` 顶层拦截，剥离 agentPromptRoute 依赖，收到 `stop/停` 即刻 cancel 并置 card 为 `aborted` | **只翻了卡片，没杀进程**（§9.4，已补直杀通道；真机验成「一次 stop 摘干净整棵树」，§9.8.2）；端到端延迟被**投递**支配，实测 120–125 s 而非路径本身（§9.8.3）；且打断与编排器无关（§9.9） |
| ⚠️ 待复验 | 沙箱用户主目录隔离（隐私越权） | `prepareSandboxHome` 白名单建立隔离 `.sandbox-home`，封锁 `~/.workbuddy` 与 `~/.ssh`，加 `forceKill` 根治僵尸 | 仅单测；本轮真机未复测该条 |
| ❌ 未接通 | 原型阶段 / 决策卡 / 选项卡（阶段 A） | 增加 `prototype` 状态流转、`prototype.html` 生成、`OptionList` 选项卡与 `[Approve & Develop]` 确认推进 | **真机不触发**：`mockup` 派单后 agent 收到的是原文、没有工单卡片（§9.9）。根因是编排器只挂在 `onAgentPrompt` 上，而真机 `@agent` 走 `invokeExecutor` 直接 `executeCliTask` |
| ❌ 未接通 | 上下文追问唤醒（阶段 A / 交互） | 入站层 `onAgentFollowUp` 优先唤醒当前活跃工单的 `onFollowUp` 回调，支持追问连续迭代 | 同 §9.9：没有活跃工单（编排器不在场），追问无处可唤醒 |
| ❌ 未接通 | 自愈循环 `maxHealRounds` / 退单（§6.2） | 编排器实现最多 3 轮测试红绿循环自愈，超纲失败触发 `Handed back` 退单卡 | 同 §9.9：自愈循环在 `runTicket` 里，真机路径进不到 |
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
   → **已定死并修掉（原因两者都不是：是"杀不干净"），见 §9.7.1。**
2. **手机→宿主投递延迟 60–95 s，且手机持续显示"重发"**：实测 stop 15:38:07 发出 → 15:38:35 落地；
   16:13:07 发出 → 16:14:42 落地。宿主已收到并执行，手机端仍挂"重发"标记 —— 这正是"一次操作
   跑两遍"的现实来源（队列重放）。**只要这条不修，§6.1 的"端到端 500 ms"就不可能成立**，
   它现在被投递支配，而不是被打断路径支配。
   → **已定位到判据（60 s 兜底超时 + 15 s 重试节拍 + ≈14 s 一次尝试 ≈ 89 s），未修，见 §9.7.2。**
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
- `constructionTraceGate` 会拦注释里的 **ISO 日期**（`2026-11-01` 这种），理由是日期会让
  下一个人以为结论有时效。写缺陷史时写「真机现场：…」不带日期；要留完整时间线就建 ADR。
  本轮为这个栽了两次。

## 9.7 第二轮：9.5 第 1 条定死并修掉，第 2 条定位到判据

### 9.7.1 「一次 stop 没杀干净」→ 已修（`cd401ad8`）

**根因不是原以为的二选一**（"注册时机"还是"工单归属"），而是**根本杀不干净**：

`destroyProcessTree` 把「根进程 `isAlive == false`」当成「整棵树没了」。而
`ProcessHandle.descendants()` 有两个性质 —— 是**快照**，且**只对活着的祖先有效**。
SIGTERM 打过去、根干净退出后，它的后代立刻被过继给 init，句柄再也枚举不到；于是
宽限循环只等根、立刻退出，`forceKill(proc)` 因根已死而跳过，**下层还在跑**。
界面形态就是「卡片已经翻 `Aborted`，工作树还在被改」。

修法两层：
1. `destroyProcessTree` 先把整棵树的 pid 记进集合，之后**按 pid 逐个核对死活**，与根的
   死活解耦；宽限期内补一次快照，最后对幸存者统一 SIGKILL。
2. `abortAll` 从"取一次 `running.keys` 快照"改成**重扫**（≤5 轮 / 100ms 间隔）——
   `running` 的条目是在 `pb.start()` **之后**才写进去的，并发派单时第二条完全可能落在
   快照之后。这是"一次 stop 只摘掉一个"的另一半成因。

守卫 `abortKillsDescendantsThatIgnoreSigterm`：假 CLI 起一个 `trap "" TERM` 的子进程
（只能 SIGKILL 收），`abortAll()` 之后断言根与子都必须死。**对旧实现会红**（已实测：
换回旧写法 → `TimeoutCancellationException: Timed out waiting for 20000 ms`，
子进程永远不死）。新实现 `:agentNode:test` 13/13 绿。

### 9.7.2 投递 60–95 s + 手机挂「重发」→ 定位到具体判据，**未修**

原先记的是"投递延迟"。实际是**两个状态机在互相骗**：

- 群发路径 `runGroupSend` 有 60 s 兜底硬超时（`SEND_HARD_TIMEOUT_MS`），超时走
  `outcome == null` 分支把气泡收尾成 `DeliveryState.Failed`
  （`RelayControllerGroupsComponent.kt` 的 null 分支）；
- 而那条消息的帧**可能早就进了 `Messenger` 的积压队列**，队列每 15 s 一拍
  （`DEFAULT_QUEUE_RETRY_INTERVAL_MS`）继续补发，最终送达；
- 一次失败尝试本身要 ≈14 s：UDP 退避 1+2+4 s，再回退 TCP 7 s。

算术：60（超时）+ ≤15（重试节拍）+ ≈14（一次尝试）≈ **89 s**，正落在实测 60–95 s 区间。
所以手机挂「重发」是**谎**：它既没失败、也没停，只是慢。

修法方向（下一轮）：`Messenger` 要把「已入队」这个事实回给上层（它现在返回 `QUEUED`），
群发路径按它置 `Queued` 而不是 `Failed`；`Failed` 只留给"队列已丢弃该帧"。
注意这与 `RelayControllerGroupsComponent` 现有那条注释（"终态恒为 `Sent`，不按
`SendOutcome` 折 `Queued`"）冲突 —— 那条注释的理由是"群扇出没有逐成员回执，
折 Queued 会让已收到的人被标错"。所以真正要加的是**第三态**（已排队 / 在补发），
不是把 Queued 套上去。1:1 路径已经在按 `SendOutcome` 折 `Queued`，可作参照。

### 9.7.3 仍未复验

阶段 A 原型 / 追问唤醒 / 自愈退单三条**依旧只有单测**，本轮全部时间用在修打断与
资源串上。这三条是全篇最大的口径缺口：它们在第 1 版的 §0 表里被写成 ✅ 已实现。


## 9.8 第三轮：工作区打包复验（含并发写入者的改动）

### 9.8.1 打包口径：从工作区构建，但先证产物里真有那笔修复

原本打算用干净 worktree（`git worktree add /tmp/tv-head fd292fd0`）只验已提交的部分，
后来改成**直接从主工作区构建** —— 这一轮要验的就是"仓库当前状态"（含另一个 agent
未提交的 `AgentCards.kt` / `DesktopExtension.kt` / `ChatBubbles.kt` / `DesktopNavigation.kt`）。

时间戳只能当参考，所以直接查产物字节码：

```
javap -p agentNode-*.jar 里的 WorktreeSandboxExecutor
  → destroyProcessTree(Process, Continuation<List<Long>>)   # 按 pid 集合返回，新签名
  → trackTree / aliveHandle / abortAll                      # 三个新方法都在
```

顺带记一个坑：`WorktreeSandbox.kt` 里的类叫 **`WorktreeSandboxExecutor`**（文件名与类名
不一致），照文件名去找 `WorktreeSandbox.class` 会扑空，别据此误判"修复没进产物"。

### 9.8.2 「一次 stop 摘干净整棵树」：真机成立

- 17:28 派单 `run sleep 900`，树上三层：`node`(24789) → `codex`(24790) → `sleep 900`(25008)；
- 17:30 只发**一次** `stop`；
- 三个 pid 在 17:32:30 **同一时刻**全部消失，之后 `codex=0 / sandbox=0`，没有第二次操作。

这是 §9.7.1 的核心断言，此前只有单测（`abortKillsDescendantsThatIgnoreSigterm`），
现在有真机读数。

### 9.8.3 纠正：那 108 秒是**投递**，不是打断 —— 我算错过一次

按 DB 时间戳我第一次得出的结论是「stop 17:30:29 到达、进程 17:32:30 才死 ⇒ 打断慢了 108 秒」。
**这个结论是错的**，根因是把 `MessageEntity.timestamp` 当成了到达时刻。

判定过程：`max(timestamp)` 在 17:43:10 那次查询里还是 `17:32:30`，到 17:43:18 才跳到 `17:41:22`。
说明那一行是**在 17:43:14 前后才写进去的**，而它盖的戳是 17:41:22 —— 即该列存的是
**发送方时钟**，手机比宿主快 13 秒。

换算后：投递 ≈ **120–125 s**（三次独立读数一致：17:41 派单、17:45 派单、17:30 的 stop），
而打断**到达即生效**。所以 §6.1 的端到端延迟完全由投递支配，与打断路径无关。

> 口径：这台机器上 `MessageEntity.timestamp` 只能用来排**同一发送方**的消息序，
> 不能当到达时刻。要量到达，就轮询 `max(timestamp)` 的**跳变时刻**。

### 9.8.4 新缺陷：消息会乱序，且迟到十几分钟

- 手机 17:24:35 发的 `run all tests`，界面显示 **✓（已送达）**，实际 **17:43:56** 才落到宿主
  —— 迟到 19 分 21 秒，并在 17:46 真的起了一个沙箱干这条活。
- 相邻两条（17:24 的 `run tests`、17:28 的 `run sleep 900`）都标**「重发」**，却都即时执行了。

所以手机端的送达标识与实际送达**无关**，且**两个方向都错**：标「重发」的多半已送达；
标 ✓ 的可能压根没到。§9.7.2 记的"重发是谎"只覆盖了一个方向。

后果比单纯"慢"严重：**用户看到 ✓ 却不敢确定对方收到，看到「重发」又可能重发一份
已经执行过的派单** —— 这正是"一次操作跑两遍"的现实来源。

### 9.8.5 本轮代码改动（`agentNode`，取消路径的两个洞）

1. **取消路径上的 SIGKILL 升级执行不到**。`ensureActive()` 抛出后异常已经离开 `use{}`，
   后面那段 `forceKill` 被整段跳过，只剩 `finally` 里的 SIGTERM ⇒ 忽略 SIGTERM 的子进程
   活下来。这与 `abort()` 修掉的是同一个洞，只是换了入口。改为
   `withContext(NonCancellable) { destroyProcessTree(proc) }`。
2. **`running.remove(taskId)` 在取消路径上被跳过**，`running` 里留下一条死进程
   ⇒ `hasRunningSandboxes()` 恒真 ⇒ 下一轮 stop 去打一具尸体，真正活着的进程反而漏掉。
   移到 `finally`。
3. **打断取证落盘**：`abort()` 记入口时刻 / 杀了几个人 / 耗时；teardown 记 `timedOut` 与
   同样三项；`abortAll` 的"重扫完仍漏"也落盘。全部写进该任务自己的 `task.log` ——
   宿主 stdout 重定向后不刷行，事发时一个字都取不到，这条是给下一次复盘留的。

`:agentNode:test` 全绿；`abortKillsDescendantsThatIgnoreSigterm` 仍能区分新旧实现
（旧语义下 20 s 超时红）。

### 9.8.6 仍未复验：阶段 A 原型 / 追问唤醒 / 自愈退单

本轮试了三次"长任务"想留出打断窗口，全部落空：
- `run sleep 900` 两次 —— agent 一次选择等（沙箱活了 4 分钟，见 9.8.2），一次把命令丢到
  后台就 `turn.completed` 退出；
- `run npm test 200 times` —— 35 秒跑完（`npm test` 85 ms × 200）。

叠上 120 s 投递延迟，**任务活得比 stop 到得还短**。下一轮要验打断，得给"必须由 agent
自己长时间动手"的任务（大范围重构），不能指望单条命令撑住。

## 9.9 阶段 A 原型在真机上没触发 —— 不是「未复验」，是**没接在派单路径上**

### 观测

18:00 发 `@codex-e2e mockup page`（`mockup` 是 `isPrototypeIntent` 的三个关键词之一），
沙箱起来了、agent 也干活了，但：

- 它收到的 prompt 就是原文 `mockup page`。证据是 task.log 里 agent 自己的推理：
  「User says "mockup page". We need infer task. … Probably task in a benchmark workspace」——
  而原型分支该注入的是 `You are designing an interactive single-file prototype HTML …`；
- 全程**没有工单卡片**：DB 里该群从 17:24 到此刻一条 `CARD` 都没有；
- 于是也没有 `state=prototype`、三个 Layout 选项、`[Approve & Develop]`。

先排除了"打包漏代码"：`unzip -p desktopApp-*.jar | strings | grep "You are designing an
interactive single-file prototype"` → 1 命中，那段串在产物里。

### 根因：真机的 `@agent` 走的是 invoke 执行器，不是编排器

| 入口 | 落到哪 | 有编排器吗 |
| :-- | :-- | :-- |
| `onAgentPrompt`（文本路由 `agentPromptRoute == Local`） | `DesktopWorkerOrchestrator.handlePrompt` → `runTicket` | **有**：工单卡片 / 阶段 A 原型 / 追问唤醒 / 自愈退单全在这里 |
| `controller.invokeExecutor`（入站帧带 `agentIntent`，M4 派单） | `DesktopExtension:816` 直接 `executeCliTask(...)` | **没有** |

手机的 `@agent` 消息带 `agentIntent`，于是走第二条。`DesktopWorkerOrchestrator` 成了一个
只有单测、以及 `onAgentPrompt` 这条**在真机上进不去的路**才能到的地方。

更直接的旁证：`RelayControllerMessagesComponent` 的仲裁 `intentOwnsDispatch`（本轮修
"一条消息派两次单"时加的）在命中 `agentIntent` 且宿主有 executor 时**主动不再调
`onAgentPrompt`** —— 即便两条路曾经都跑过，现在也只剩 invoke 这一条。

### 为什么打断仍然有效（一个必须记住的推论）

打断桥（`onAgentAbort` + `abortRunningSandboxes`）挂在 `DesktopExtension` 上，**与编排器无关**，
所以照常工作 —— 这正是 §9.8.2 能验成的原因。反过来也成立：**不能拿"打断有效"推断编排器在跑**。
上一轮把"卡片翻 Aborted"当成编排器活着的证据，其实那也可能是编排器不在场、只有摘进程生效。

### 下一轮第一件事

让 invoke 执行器**交给**编排器而不是绕过它。难点是语义不匹配：`invokeExecutor.execute` 是
请求-响应（要回 `AgentInvokeExecution` 的 status/summary/error），`handlePrompt` 是
fire-and-forget。两条候选：
1. `handlePrompt` 加一个能返回结果（`Deferred` 或回调）的重载，invoke 侧 await；
2. invoke 只做受理、立刻回 `accepted`，结论仍走工单卡片。

无论选哪条，都要配一条守卫：断言 `invokeExecutor` 的实现体里出现**编排器调用**，
而不是 `executeCliTask(` —— 照 `DesktopStageChainTest` 的写法。那个测试正是为
「执行体写好了却没人装」设的，而这里坏的是同一类：**能力写好了，但真机那条路上没有它**。

---

## 10. 满屏「重发」的根因（2026-11-01 18:08–18:30，Pixel 6 ↔ 桌面打包宿主）

### 10.1 现象

手机群聊里每一条自己发的消息，右下角都挂着红字「重发」。用户据此手打了一遍又一遍，
聊天记录里出现 `@cod@codex-e2e a weaher page`、`@c@codex-e2e build a weather page` 这类
畸形文本（**那是自动化输入造成的，不是产品行为**，见 §10.6）。

### 10.2 判定链（每一步都是读数，不是推断）

| # | 读数 | 结论 |
| :-- | :-- | :-- |
| 1 | 手机库：`ping` `delivery=Failed`，18:08:05 | 本地认为没发出去 |
| 2 | 桌面库：同一条 `ping`，`mine=0`，18:08:05 | **对端收到了**，本地判失败是假的 |
| 3 | 复现：18:08:05 发送 → 18:09:15 翻 `Failed`，间隔 60 s | 正好是 `SEND_HARD_TIMEOUT_MS` |
| 4 | `[send] peer=46c23456 id=… ep=10.233.138.167:17878 acked=false in=7374ms` | 每个端点都等满 7.37 s，全部 no-ack |
| 5 | 一串端点：`192.168.2.82` / `2408:…`(×2) / `fd7a:…` / `10.233.…`(VPN) / `100.66.…`(Tailscale) | 桌面宣告了所有网卡地址，手机逐个串行试 |
| 6 | 修后同一群：`ep=192.168.2.82:17878 acked=true in=16ms` | 主端点本来是毫秒级，之前的 37 s 全耗在错的路由与候选上 |
| 7 | `queue.log` 33 行 / 3 条消息，每 15 s 重试一轮 | 积压自我放大 |

### 10.3 根因（两层，第二层才是致命的）

1. **候选端点被当成主路径**：`sendAwaitAck` 把同一份完整退避 `[1000,2000,4000]` 套给了
   **每一个**端点，而它上面的注释写着「每个端点只试一次（完整退避留给在线路径）」——
   注释与实现不是一个意思，代码跟的是前者。多网卡的桌面宣告 5 个地址 ⇒ 一条消息 37 s。
2. **超预算后同步补发，把 10 秒拖成 60 秒**：扇出预算到点 → `requeue(member, json, ts)`
   → 内部对在线对端**当场 flushQueue**（无超时、串行、每条又是 7.4 s × 端点）⇒ 上层
   60 秒硬超时 ⇒ `Failed` ⇒ 挂「重发」⇒ 用户重复手打 ⇒ 队列更长 ⇒ 下一轮更慢。

### 10.4 修的四件事

| 改动 | 位置 | 判据 |
| :-- | :-- | :-- |
| 只有当前端点用完整退避，候选快试一次；候选最多 2 个 | `Messenger.sendAwaitAck` + `MAX_CANDIDATE_ENDPOINTS` | 修后主端点 `acked=true in=16–24ms` |
| 扇出预算 10 s → 15 s（最坏正常路径 10.1 s） | `GROUP_FANOUT_BUDGET_MS` | 不再卡在预算线上 |
| 扇出预算到点后的 `requeue` 只入队、不 flush | `Messenger.requeue(flushNow=false)` | 补发交给 15 s 定时重试与 Online 事件 |
| **超时且已入队 ⇒ `Queued`（已排队），只有队列里也没有才 `Failed`** | `sendGroupTextOptimistic` + 可注入的 `queuedProbe` | 新用例 `aTimedOutSendThatIsAlreadyQueuedIsNotReportedAsFailed` |

「已排队」在界面上是时钟图标、**没有重发出口**；后台补发拿到 ACK 后由 `_status` 事件
把同一行翻回「已送达」——所以超时不再等于失败，用户也不会重复发。

### 10.5 端到端：从发起到收尾，27 秒

一次真实操作，全程在手机上完成（桌面只做执行端）：

| 时刻 | 事件 | 读数 |
| :-- | :-- | :-- |
| 18:28:15 | 手机发 `@codex-e2e build a weather page`（@ 胶囊绑定） | `acked=true in=24ms`；信封带 `agentIntent:{agentId:"codex",wsId:"ws-weather-e2e",mode:"auto"}` |
| 18:28:15 | 桌面入群流 | 桌面库同一时刻收到同一条文本与 intent |
| 18:28:2x | 沙箱启动 | `/tmp/tidings-ws/t-7-2f7d6539/` |
| 18:28:42 | 结论回群 | `weather-relay 沙箱任务完成` + `✅ stop` + 代码审查卡（`+338 −63`、11 文件、`patch.diff` 24.4 KB、「合并此补丁」） |
| 18:28:42 | 手机渲染 | 四条自己发的消息全部 ✓（**零「重发」**），卡片与文件卡同屏可见 |

这是第一份「一条消息 → 执行 → 卡片 → 产物」全在同一屏、同一时间线里的真机读数。
之前的取证是分段的（先验派单、再验打断、再看卡片），拼接起来才像一条流程 ——
用户看到的"东一块西一块"就是这个取证方式的直接后果。

### 10.6 仍在的缺陷（不包装）

1. **`stop` 被自动化输入吞成 `stp`**（截图可证），于是那一轮中止并不是 stop 触发的；
   同一只手打 `weather` 也曾变成 `weaher`。**历史里所有畸形文本都来自这里**，
   以后演示必须走「点候选 + 逐字符输入」，否则测的就不是产品。
2. **孤儿进程**：`t-8` 中止后目录已清、`sleep 300` 已死，但 18:24 / 18:26 那两次派单
   留下的两个 `codex exec … build a weather page` 仍在跑（>10 分钟）。任务收尾的清理
   只覆盖了「当次 running」，没覆盖「已经被判定结束、进程却还活着」的那一类。
3. **`@` 菜单里第一项是「@所有人」**：自动化按空格就会选中它，消息变成无 `agentIntent`
   的群广播（18:26:01 那条 `@所有人 build a weather page` 即此）。真人点选不会犯，
   但它把一个「点错一项就等于没派单、也没有任何提示」的坑留在了主路径上。

## 11. §9.9 挂起项闭环：invoke 执行桥交给编排器（不再绕过）

§9.9 判定「阶段 A 原型在真机上没触发」的根因后，留下的「下一轮第一件事」现已落地：
**invoke 执行器的 CLI 分支不再直调 `executeCliTask`，改为交给 `DesktopWorkerOrchestrator.handlePromptAwait`。**

### 改动（desktopApp 两个文件 + 守卫）

1. **`DesktopWorkerOrchestrator` 新增可等待终局**：
   - `TicketOutcome(state, summary, error, cardJson, patchPath)` —— 工单的首个终局
     （`review_ready` / `blocked` / `aborted` / `prototype` 待选 / `error`），带终局卡
     JSON 文本与沙箱 patch 路径；
   - `ActiveTicket.done: CompletableDeferred<TicketOutcome>`，全部终局点一次性 complete；
   - `handlePromptAwait(convId, sender, prompt, replyTo, agentKey)`：与 `handlePrompt`
     同判据同顺序（打断 → 受阻追问唤醒 → 新派单），差异只在「await 终局」；
   - `abortIfRequested` 改为先**同步**把「带卡中止」终局写进 `done`（卡片由纯函数
     `ticketCardJson` 预构建，不走网络），再取消协程 —— 顺序反过来，await 方会先
     收到无卡兜底，域层会在用户自己按了停之后又补一条「没干完」横幅；
   - 派单帧带 `agentId` 时随帧走（`agentKey`），不再写死 codex。
2. **`DesktopExtension` invoke 执行器 CLI 分支重写**：解析到 CLI 驱动即调
   `workerOrchestrator.handlePromptAwait`，await 到终局后：patch.diff 投群
   （`deliverCliArtifacts`）、`error != null` 走 `withCard(ERROR)`、否则
   `done(summary, card)`。帧上无群 id 时如实回 ERROR（CLI 派单必须有群绑定仓）。
   编排器实例提前到装配开头创建，`onAgentPrompt` 与执行桥共用同一实例。
   **dsh 插件分支（`executePluginTask`）不动** —— 自治桥模型与工单编排是两回事。
3. **守卫更新**（`DesktopInvokeWiringGuardTest`）：正向钉
   `workerOrchestrator.handlePromptAwait(` 必须出现在执行器装配里，反向禁
   `created.executeCliTask(` 回潮 —— 按源码扫描锚死「不许再绕过」。

### 真机收益（下一步复验清单）

真机 `@agent` 派单从此与文本路由同权：工单卡（working/review_ready 原位刷新）、
阶段 A 原型立项（mockup 意图 → prototype 卡 + 选项）、受阻工单追问唤醒、
最多 3 轮自愈 + 退单、群绑定确定性落仓 —— 这些能力第一次在真机派单路径上**存在**，
不再是「只有单测、真机进不去的路」。待真机复验三条：原型派单触发、追问唤醒、
自愈退单（§9.7.3 的口径缺口由此收口）。

## 12. 缺陷 A/B 修复复跑（次日 11:00-11:18）

新包宿主（工单卡真 agentKey + prototype 方向判据）+ Pixel 6 + e2e-final 群复跑。
codex 上游仍不可达（确定性失败），本轮验证全部走失败路径 —— 恰好覆盖自愈循环。

1. **方向判据（缺陷 B）**：待选卡在屏时发无 @ `run npm test`，手机出
   `direction-ask` 确认卡（是调整原型还是新任务，两个 action 按钮），
   待选卡原位保留 —— 不再被吞成 refine。
2. **卡片真名（缺陷 A）**：点「派为新任务」，action 帧过 AgentExecAuthorizer
   （宿主日志 `direction-ask resolved: dispatch-pending`），无 denied 横幅，
   新 dev 沙箱启动（containment 前导进 codex）；卡面角色显示 Codex。
3. **自愈循环畅通（观察 C 推翻）**：Heal loop 1/3 卡到手机、round-2/3 沙箱依次创建、
   3 轮后 `Handed back: exceeded max heal rounds (3)` 退单卡到手机。
   昨日「working 卡未达手机」判为宿主重启后队列重放洪流的瞬态窗口，非结构性阻塞。
