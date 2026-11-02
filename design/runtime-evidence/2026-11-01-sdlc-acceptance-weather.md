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

## 13. 阶段 A/B/C 全线上机 + `merged` 终态首次出现（2026-11-02 11:55–12:25）

§12 结尾留的三条「待真机复验」（原型派单触发、追问唤醒、自愈退单）本轮全部补上，
并把 §5 里那条**最大口径缺口**（`merged` 终态从未在设备上出现过）收口。

### 13.0 装置：确定性桩 CLI，验的是链路不是模型

本机没有任何可用的真 agent CLI。**逐条实测**（2026-11-02 13:12–13:20 复核，命令与输出见下），
四类阻塞原因不同，不能笼统写成「不可用」：

| CLI | 版本 | 实测结论 | 判据 |
|---|---|---|---|
| `codex` | 0.145.0 | **未登录 + 上游不可达**（网络这层先失败） | `codex login status` → `Not logged in`（exit 1），无 `~/.codex/auth.json`；`codex exec` 打出会话头后连续 `failed to connect to websocket: IO error: tls handshake eof, url: wss://api.openai.com/v1/responses`，`Reconnecting… 1..5/5` 后 `Falling back from WebSockets to HTTPS transport` |
| `claude` | 2.1.212 | **仅未登录**（网络是通的） | `claude -p …` → `Not logged in · Please run /login`；`~/.claude.json` 里 `oauthAccount=None`、`primaryApiKey=None`，无 `~/.claude/.credentials.json`；`https://api.anthropic.com/v1/models` → **401**（TLS 0.36s，可达） |
| `agy` | 1.4.12 | **上游 502** | `agy -p …` → `error: Eligibility check failed: Post "https://daily-cloudcode-pa.googleapis.com/v1internal:loadCodeAssist": Bad Gateway` |
| `gemini` | — | **解释器缺失** | `exec` 直接 `ENOENT`（shebang 指向不存在的 node），连 `--version` 都起不来 |
| `opencode` | 2.2.5 | **同一张网** | 能起，`run` 走 `gpt-5.4`，同样卡在 OpenAI 侧，只反复打 `> build · gpt-5.4` |

两个**必须写清楚**的点（第一次记录时写错了，这里更正）：

1. **`codex` 不是「零输出」**。它会打出会话头（`model: gpt-5.6-sol` / `provider: openai` /
   `sandbox: read-only`）和 5 次 TLS 重试错误，只是**永远拿不到模型回复**。
   上一次记录成「50 秒零输出」是因为只看了 stdout、没接 stderr —— 这正是
   「静默失败被当成卡死」的经典误判，记录里不能这么写。
2. **不是 WorkBuddy 沙箱、也不是本机代理在拦**。`api.openai.com` 在**关掉沙箱**、
   **清空 `HTTP(S)_PROXY`** 直连的情况下仍是 `000`（连续三次），而同一时刻
   `www.baidu.com` → 200、`api.anthropic.com` → 401。所以是**出口到 OpenAI 这一段被切断**，
   换环境跑不会好。

推论（对后续复验有用）：`claude` 只差一次 `/login` —— 它的上游是通的，
登进去就能跑真模型；`codex` 则要同时解决「登录」和「到 api.openai.com 的通路」。

本轮因此改用桩：`TIDINGS_CODEX_CLI=design/runtime-evidence/tools/stub-codex.sh` —— 一个与
`codex exec --json` 同形的 `sh` 桩（`thread.started` / `item.completed` / `turn.completed`），
按 prompt 分支产出确定产物；分支清单与「为什么每个分支长这样」写在脚本头注释里。
配套 `tools/run-with-timeout.py`（macOS 自带没有 `timeout`，验收脚本要在干净机器上能直接跑）。

**这条要写死：本节验证的是「派单 → 沙箱 → 产物 → 卡片 → 合流」这条编排链路与交互，
不是模型能力。** 桩能产出的东西（`prototype.html`、`src/aqi.js`、`normalizeQuery` 修复）
都是预先写好的，不构成「真 agent 干成了活」的证据。

> **13:40 更正（本节的阻塞已解除）**：上面「不可用」是**默认上游**的结论。
> 三家 CLI 都支持自定义 base URL；换成 opencode-go 网关
> （`https://opencode.ai/zen/go/v1`，同时提供 OpenAI `/chat/completions`、`/responses`
> 与 Anthropic `/messages`）后，`claude` / `codex` / `opencode` 都能用
> `deepseek-v4.1-flash` 跑通，且都能真实改文件。配方与实测见 §13.14；
> 复跑用 `tools/real-cli-smoke.py`（密钥只从 `OPENCODE_API_KEY` 读，不落盘）。

### 13.1 阶段 A：原型立项在真机上第一次完整走通

`@codex-final prototype` → 沙箱 `t-1-29c8dcce`（`+50 −1445`，1 文件，patch 51697 B）。

卡片 `chain-tmvyq8swf` **只有一行**（单卡槽 T-2d 生效），`rev 3`，卡片 JSON：

```json
{"cardId":"chain-tmvyq8swf","rev":3,"type":"task-board","state":"prototype",
 "title":"prototype","progress":50,
 "items":[{"id":"Codex","label":"Codex","status":"prototype"}],
 "banner":{"text":"Interactive prototype ready. Select layout or reply to adjust.","tone":"info"},
 "options":[{"label":"Layout A (Minimalist)","recommended":true},
            {"label":"Layout B (Capsule Cards)","recommended":false},
            {"label":"Layout C (Compact Table)","recommended":false}],
 "artifacts":[{"name":"prototype.html","bytes":2599,"mime":"text/html"}],
 "actions":[{"actionId":"approve-prototype","label":"Approve & Develop","approval":true}]}
```

本轮修掉的两处（都在 §12 之后）：

- **`state` 由卡片自己声明**（`StageChain.cardOf` 新增 `state` 字段）。此前客户端只能靠
  `actions` 里有没有 `approve-prototype` 反推状态，于是原型待选卡被标成**待合并**。
  真机复验：新包下同一张卡标签是 **待确认**。
- **产物是真交付的那一份**。从前 `deliverPrototypeArtifact` 未实现时卡上写死
  `("prototype.html" to 4096L)`，那行点不动。现在卡上 `bytes=2599` 等于落盘文件真实长度，
  群里同时出现一张**真传输卡**：`prototype.html · 2 KB · 已接收 · 已存到 …/prototype.html`，
  带「打开 / 分享」。

### 13.2 追问微调：无 @ 文本唤醒同一张工单

待选卡在屏时发无 @ `make it dark` → `direction-ask` 卡
`chain-…-ask`（`state=blocked`，横幅「「make it dark」— 是调整这个原型，还是一个新任务？」，
两个 action）→ 点「作为调整发送」→ 原卡 `rev 4`「Resumed: processing follow-up」
→ refine 沙箱 `t-2-567089de` → `rev 5`「Refined prototype ready」，产物字节数同步更新。
`direction-ask resolved: dispatch-pending` 与「作为调整发送」两条分支本轮都走过。

### 13.3 立项转开发 → `review_ready`

点 `Approve & Develop` → `approvePrototypeByCard` → dev 循环（prompt 前缀
`Prototype confirmed! Implement the full features and test suite for: prototype`）
→ 沙箱 `t-7-f42d8705`（`+115 −0`，2 文件，patch 3879 B）→ 卡 `chain-tmvyqfi84`
`rev 5`：

```json
{"cardId":"chain-tmvyqfi84","rev":5,"state":"review_ready","progress":100,
 "banner":{"text":"2 个文件","tone":"success"},
 "artifacts":[{"name":"patch.diff","bytes":3879,"mime":"text/x-diff"}],
 "diff":{"path":"ws-clean","summary":"2 个文件","additions":115,"deletions":0},
 "actions":[{"actionId":"merge-patch","label":"Merge","approval":true}]}
```

### 13.4 `merged` 终态：两个阻断缺陷，都不是 UI 问题

点「合并此补丁」得到：

```
[RELAY-INVOKE] patch merge FAILED (card chain-tmvyqfi84): no sandbox patch recorded for card chain-tmvyqfi84
[RELAY-INVOKE] patch merge has no session to record on (card chain-tmvyqfi84)
```

宿主仓 `git status` 干净、`src/utils/` 里没有 `uvIndex.js` —— **一个字节都没变**。
这就是 `merged` 从未出现的直接原因，拆开是两个独立缺陷：

**D1 工单卡不写会话台账，按会话反查补丁恒为空。**
`ControlPlane.latestPatchRefForCard` 的判据是「找一个会话，它的事件里有一条 `type=card`
且 body 含 cardId」，再取该会话的 patch 产物。但工单卡是
`controller.broadcastChainCard` → `broadcastBoardCard` **直接广播**的，从不写任何会话台账
⇒ 对工单卡恒为 null。§12 里 `c7945fa4` 加的 `latestPatchRefForCard` 兜底因此形同虚设。

修法（谁发布卡片谁登记补丁）：`DesktopWorkerOrchestrator` 增加
`patchByCard: ConcurrentHashMap<String, SandboxPatchRef>`，在**发布 review_ready 卡之前**
登记（先发后登记会漏掉「卡片到达同一秒用户就点合并」这一拍）；`onHostCardAction` 的
`merge-patch` 分支第一判据改为 `workerOrchestrator.patchRefForCard(cardId)`，
原有的 `latestPatchRefFor(sessionId)` / `latestPatchRefForCard(cardId)` 保留为兜底。

**D2 翻面卡靠扫本地消息反推群号，扫不到就静默不发。**
合并成功后要往群里发同一张卡的 `state=merged` 版本，群号是从
`controller.state.messages` 里找 cardId 相同的行、再 `peerId → ConvId.groupIdOf` 反推的。
反推失败时 `appliedGroupId` 为 null，`publishCardNow` 首行 `return` ——
合并成功、仓已改、手机停在「待合并」，**一行日志都没有**。真机实测正是如此。

修法：编排器同时登记 `groupByCard`（群号派单时就是已知的），
`appliedGroupId = workerOrchestrator.groupForCard(cardId) ?: <原有反推>`，
且反推也失败时打 `merged card has no group to publish to`。

**另外把翻面卡改成「在原卡上改」**：从前从零 build 一张
`{type=diff-review, title="Patch applied"}`，翻面瞬间 diff 明细整块消失，
用户看不出合进去的是哪几行。现在保留原卡的 `type/items/progress/artifacts/diff/title`，
只覆盖 `state/rev/banner` 并删掉 `actions`。

修后真机（阶段 C 那一次）卡面：

```
工单任务 · 已合并
fix beijing uppercase lookup
Changes merged into workspace
进展 100%   Codex
ws-clean +31 −1 · 2 个文件 +31 −1
patch.diff · text/x-diff · 1.9 KB
已由 e4bd7b99-… 处理          ← 按钮消失
```

手机库里同一行（单卡槽原位替换）：

```json
{"cardId":"chain-tmvyqxzmr","rev":6,"state":"merged","title":"fix beijing uppercase lookup",
 "banner":{"text":"Changes merged into workspace","tone":"success"},
 "progress":100,"diff":{"path":"ws-clean","summary":"2 个文件","additions":31,"deletions":1},
 "artifacts":[{"name":"patch.diff","bytes":1989,"mime":"text/x-diff"}],
 "decidedBy":"e4bd7b99-6eb9-4278-a679-edde214909e9","decidedActionId":"merge-patch"}
```

`rev` 是 6 而不是 1：`broadcastBoardCard` 发现传入 rev 不大于既有值时自己 +1，
所以翻面卡不会因为 rev 回退被客户端的 T-2d 规则丢掉。

### 13.5 阶段 B：1→N 特性迭代（AQI 面板）

`@codex-final add aqi panel with tests` → 沙箱 `t-1-1a5b72af`（`+92 −0`，2 文件，
`src/aqi.js` + `test/aqi.test.js`）→ `review_ready`（待合并、`2 个文件 +92 −0`、
`patch.diff 3.5 KB`）→ 点合并 → `patch merged into workspace`，宿主仓出现
`?? src/aqi.js` / `?? test/aqi.test.js`。

### 13.6 阶段 C：缺陷自愈（BEIJING 大写查不到）

`@codex-final fix beijing uppercase lookup` → 沙箱 `t-1-063b577b`（`+31 −1`，2 文件，
patch 1989 B）→ 全量回归 **74 pass / 0 fail / 305 ms** → `review_ready`
（`+31 −1`、`patch.diff 1.9 KB`）→ 合并 → 宿主仓：

```js
function normalizeQuery(cityQuery) {
  // 归一化：NFKC（全角空格/全角字母）→ trim → 小写。
  if (typeof cityQuery !== 'string') return '';
  return cityQuery.normalize('NFKC').trim().toLowerCase();
}
```

`M src/services/weatherService.js` + `?? test/cityQuery.test.js`，卡面 `已合并`（见 13.4）。

在**合并过的宿主仓**里再跑一次全量（计划 §阶段 B 第 3 条的判据「通过数提升且全绿」）：

```
# tests 79   # pass 79   # fail 0   # duration_ms 276
```

71（基线）+ 5（AQI）+ 3（城市）＝ 79，工作区为 `M src/services/weatherService.js` +
三个未跟踪新文件。**但 HEAD 没动** —— 这一条见 13.10 的 D5。

### 13.7 §6.1 硬打断：两次实测

| 次数 | 判据 | 宿主自测 | 进程树 | 产物 |
|---|---|---|---|---|
| 1 | 发 `stop` → 进程消失 | `killed 2/2 pid(s) in 59ms` | `sh` + `sleep` 全灭 | `slow-drill.txt` 未生成 |
| 1 | 端到端（含 adb 往返） | 90 ms | — | — |
| 2 | 收尾遗留工单 | `killed 2/2 pid(s) in 54ms` | 同上 | — |

宿主日志：`[sandbox] abort t-2-a635ef88: entered at …, destroying process tree`。
卡面：`已停止` / `Aborted by user request` / `进展 0%`，与 500 ms 预算相比余量充足。

### 13.8 §6.2 三轮自愈交回 + `Retry` 的真落点

`@codex-final failing drill`（桩永远不产出补丁）跑满三轮：
`t-3`（`task failed: cli exit=1`，未走到 harvest）+ `t-4` + `t-5`（均
`nothing to harvest`）→ 卡 `chain-tmvyr1ibx` `rev 7` `state=blocked`，
横幅 `Handed back: exceeded max heal rounds (3) — No patch produced by worker`，`进展 40%`。

**D3 `retry` 这个 actionId 宿主没有分支。** `StageChain` 往受阻卡里写
`actionId=retry`，客户端照发，但 `onHostCardAction` 没有 `retry` 分支，于是落到
`followUpByCard(cardId, actionId)` —— 把**机器 id 当用户文本**喂进去。真机两种错法：

- 开发卡：`card action retry for agent codex has no local plugin (dropped)`，点了毫无反应；
- 原型卡：`retry` 被原型闭包当成一句微调，卡面回「Refined prototype ready」
  （沙箱 `t-5-3818639d` `+61 −1447`，什么都没重试）。

修法：`ActiveTicket` 增加 `devPrompt`（立项后改写成
`Prototype confirmed! …`，让重试重放的是「照原型实现」而不是「再画一遍原型」）；
新增 `retryByCard(cardId)` 在同一张卡上重跑 `runDevelopmentAndHealLoop`；
`onHostCardAction` 增加 `retry` 分支并**排在 `followUpByCard` 之前**。
真机复验：点 Retry → `retry restarted the dev loop (card chain-tmvyr5ss1)`，
新沙箱 `t-4-1e140629` 立刻起来，再跑满三轮后再次交回。

### 13.9 §6.4 双工单并发

同一群里连发 `slow drill` 与 `quick drill`，两条链各自独立：

| cardId | rev | state | title | progress |
|---|---|---|---|---|
| `chain-tmvyr7fcg` | 2 | `working` | slow drill | 40 |
| `chain-tmvyr7n9m` | 4 | `review_ready` | quick drill | 100 |

`slow drill` 的桩进程仍在 `sleep 120` 时，`quick drill` 已经独立跑完并出 `review_ready`
卡 —— 两条工单的卡片、沙箱、补丁互不干扰（`quick drill` 的 patch 为 `+17 −0`）。

### 13.10 本轮新增缺陷清单

| 编号 | 现象 | 根因 | 状态 |
|---|---|---|---|
| D1 | 合并必然失败 `no sandbox patch recorded for card …` | 工单卡不写会话台账，`latestPatchRefForCard` 恒空 | 已修（`patchByCard` 登记） |
| D2 | 合并成功但卡不翻面，且无日志 | 群号靠扫本地消息反推，失败即静默 `return` | 已修（`groupByCard` + 留痕） |
| D3 | `Retry` 要么无反应、要么变成原型微调 | 宿主无 `retry` 分支，落到 `followUpByCard` 当文本 | 已修（`retryByCard`） |
| D4 | 交回横幅只报最后一轮原因 | `executionError` 每轮覆盖，首轮 `cli exit=1` 被末轮 `No patch` 覆盖 | 未修（记录） |
| D5 | 合并后的改动不进 git 历史，下一轮沙箱看不到 | `DesktopPatchMerge.apply` 只 `git apply` 到工作区，不 `add`/`commit`；worktree 从 HEAD 建 | **已修**（`MergeBaseRef` 滚动基线，见 13.13） |
| D6 | 翻面卡 `items[0].status` 仍是 `review_ready` | 只覆盖了 `state`，没同步阶段项 | 未修（记录，UI 读 `state`，不影响显示） |

D5 值得单独说一句：`git apply` 之后宿主仓是 `M src/services/weatherService.js` +
若干 `??` 未跟踪文件，**HEAD 没动**。于是下一张工单的沙箱（`git worktree add` 自 HEAD）
看不到上一轮合进去的代码 —— 本轮 AQI 的 `+92 −0` 与城市修复的 `+31 −1` 各自独立、
互不可见，`npm test` 的基线也一直是 71 而不是累计值。计划里写的「atomic `git apply`
into host repo」字面成立，但「合进去的改动成为后续迭代的起点」不成立。修复见 13.13。

### 13.11 未覆盖

- **§6.3 离线 P2P bundle**：本轮未做（需要断网/改网卡，会打断同机其它验证）。
- ~~**真 agent CLI 全链路**~~ → **已覆盖（2026-11-02 14:35，见 §13.15）**：网关打通 +
  `PWD` 修正 + 凭证注入之后，真 claude 在沙箱里跑完了 A/B 两轮，B 轮在合并基线上
  读到了 A 轮的产物。`extractFileFromPatch` 的形状限制仍成立（两轮都是「新文件」形状），
  但「真模型产物能被抽出来」这条不再只是推断。

### 13.12 计划书事实校准（`docs/TEST-PLAN-APP-LIFECYCLE-WEATHER.md`）

| 计划里写的 | 实测 |
|---|---|
| `npm test` = 72 tests / 238 ms | **71 tests / 243 ms**（`478f060` 干净检出；多次运行 243–267 ms） |
| 城市归一化在 `src/provider.js` 的 `normalizeCityQuery` | 在 **`src/services/weatherService.js:16` 的 `normalizeQuery`** |
| 仓库结构含 `src/aqi.js` | 基线**没有**，AQI 是阶段 B 新增的 |
| `prototype.html` 是产物 | 基线**已被 git 跟踪**（`prototype.html` 在仓里），阶段 A 是覆盖写 |

### 13.13 D5 修复：合并后的工作树成为下一轮的基线（滚动基线）

**根因复述**：合流只 `git apply` 到宿主工作树，**不 add / 不 commit**（提交是机主的
下一步）。HEAD 停在原地 ⇒ `git worktree add` 自 HEAD 建 ⇒ 下一轮任务看不见上一轮
合进去的代码。

**做法**（`MergeBaseRef`，`agentNode/.../drivers/MergeBaseRef.kt`）：

1. 合并成功后，用**一次性索引**把当前工作树打成一份快照提交：
   `GIT_INDEX_FILE=<tmp> read-tree HEAD` → `add -A` → `write-tree` →
   `commit-tree <tree> -p HEAD`，再 `update-ref refs/tidings/base <sha>`。
   全程**只新增对象**：不动机主 HEAD、不动分支、不动真实索引/暂存区。
2. 沙箱建 worktree 时起点从隐式 HEAD 改成 `MergeBaseRef.baseFor(hostRepo)`：
   快照有效 ⇒ `refs/tidings/base`；否则 ⇒ `HEAD`。起点**立刻解析成不可变 sha**
   再交给 `worktree add`：并发工单里另一条链合并会推进 ref，若 harvest 时再读 ref，
   就会拿「别人的新基线」去 diff 自己的工作树 —— 补丁里凭空出现一段回退。
3. 「有效」的判据是**快照的父提交 == 当前 HEAD**。机主后来自己 commit 了，
   HEAD 前移、父提交对不上 ⇒ 自动回落到 HEAD —— 不拿一份旧树去盖住机主的新提交。

**为什么不是「合并时顺手 commit」**：那会把编排器的提交塞进机主的线性历史，
`git log` 从此混入机器提交；机主想 rebase / squash 时还要先认出哪些是它的。
快照放在 `refs/tidings/` 下，`git branch` 列表里看不到，`git gc` 也不会回收它
（有引用，不是悬空对象）。

**补丁形状因此变成「相对上一轮合并」**：worktree 的 HEAD = 快照，harvest 的
`git diff <base sha>` 只含本任务的改动 —— 不是把上一轮已合并的内容重新打一遍。
基线用**开工时定下的 sha** 而不是 `HEAD`：CLI 若在沙箱里自己 commit，`HEAD` 会前移，
`git diff HEAD` 会把它的活读成「没有改动」。
下一份 patch 落到宿主工作树时，基线正好是上一轮合并后的状态，`--check` 能干净通过。

**验证**（真 git，全绿）：

| 用例 | 钉住的事实 |
|---|---|
| `WorktreeSandboxExecutorTest.nextTaskStartsFromTheMergedSnapshotNotFromHead` | 未提交的合并产物（`merged.txt`）在下一轮沙箱里读得到；harvest 只 +1 行（相对快照，不是重打） |
| `WorktreeSandboxExecutorTest.harvestSurvivesACliThatCommitsItsOwnWork` | CLI 在沙箱里自己 commit 后，收割仍相对开工 sha（`git diff HEAD` 会把它的活读成零改动） |
| `DesktopPatchMergeTest.mergedNewFileLandsInTheRollingBase` | 未跟踪的新文件（`src/aqi.js` 形状）也进快照 |
| `DesktopPatchMergeTest.mergedWorkBecomesTheBaseForTheNextTask` | 端到端：合并 A → 沙箱看得见 A → 合并 B → 宿主仓同时有 A/B，快照含两轮全部改动 |
| `DesktopPatchMergeTest.baseFallsBackToHeadOnceTheHumanCommits` | 机主 commit 后 HEAD 前移 ⇒ 回落到 HEAD |
| `DesktopPatchMergeTest.snapshotLeavesHeadAndTheRealIndexAlone` | 快照不动机主 HEAD、真实索引/工作树状态逐字不变 |
| `DesktopPatchMergeTest.conflictingPatchLeavesTheWorkspaceUntouched` | 冲突不推进基线（ref 仍为空） |

模块级读数：`:agentNode:test` **287 tests / 0 fail**（38 suites，129 s）、
`:desktopApp:test` **238 tests / 0 fail**（48 suites，5.5 s）。宿主日志新增一行
`next sandbox base=<sha>`，是「下一轮真的站在上一轮肩膀上」的外部证据。
（287 = D5 修复前的 285 + 后文 §13.14 的两条 `PWD` 用例。）

**关于「DSH super agent 有没有现成方案」**：本机 `~/.dsh` 的顶层与 `bin/` 下没有
worktree / 合并策略的规范可抄（只有运行态、会话与启动脚本）。同类 agent harness 的
常见做法就是「每个 worktree 从**最新的集成基线**拉起，而不是固定 HEAD」—— 即本轮
实现的滚动基线；差别只在快照放哪儿（ref / tag / 独立集成分支）。这里选
`refs/tidings/base` 是因为它对机主完全隐形、又不会被 gc。

### 13.14 真 CLI 解封：三家都走 opencode-go（自定义 base URL + model）

§13.0 的「本机没有可用真 CLI」只在**默认上游**下成立。实测三家都支持自定义
base URL，统一指向 opencode-go 后全部可用。

**网关能力**（`GET /models` 实测）：

- `deepseek-v4.1-flash`：context 1,000,000 / output 384,000，`tools=true`，
  `reasoning.effort = low|high|max`；计费 `$0.15 / $0.6` per 1M（工作日高峰 ×2）。
- 同时提供三条兼容面：OpenAI `/chat/completions`、OpenAI `/responses`、
  Anthropic `/messages`。
- **每个请求都必须带 `x-opencode-session: <任意非空>`**，否则 400
  `MissingSessionID`。Anthropic 面只认 `x-api-key`；用 `Authorization: Bearer`
  会 401 `Go requires a credential`。

**三家配方**（均已实测 `PONG` + 真实写文件）：

| CLI | 关键配置 | 启动 |
|---|---|---|
| `claude` 2.1.212 | `ANTHROPIC_BASE_URL=https://opencode.ai/zen/go`（**不带 `/v1`**，SDK 自己拼 `/v1/messages`）、`ANTHROPIC_API_KEY`、`ANTHROPIC_MODEL=deepseek-v4.1-flash`、`ANTHROPIC_SMALL_FAST_MODEL` 同值、`ANTHROPIC_CUSTOM_HEADERS="x-opencode-session: claude-code"` | `claude -p "<prompt>" --permission-mode bypassPermissions` |
| `codex` 0.145.0 | `$CODEX_HOME/config.toml`：`model`、`model_provider`、`[model_providers.opencode-go]` 的 `base_url` / `env_key=OPENCODE_API_KEY` / `wire_api="responses"` / `http_headers` | `codex exec --skip-git-repo-check -s danger-full-access "<prompt>"` |
| `opencode` 2.2.5 | `OPENCODE_CONFIG` 指向自定义 provider：`package=@ai-sdk/openai-compatible`、`settings.baseURL`、`settings.apiKey="{env:OPENCODE_API_KEY}"`、`headers`、`models` | `opencode run --standalone --auto -m opencode-go/deepseek-v4.1-flash "<prompt>"` |

**两个不明显的坑（都实测踩过）**：

1. **codex 0.145 移除了 `wire_api = "chat"`**：填 chat 会直接
   `Error loading config.toml: wire_api = "chat" is no longer supported`。
   网关有 `/responses`，所以填 `responses` 即可。
2. **opencode 用 `$PWD` 解析工作区，不是进程 cwd**。`ProcessBuilder.directory()`
   / `subprocess(cwd=…)` 都不会改 `$PWD`，于是它把文件写到父进程的项目根
   （首次冒烟真把 `proof.txt` 写进了本仓，已删）。必须显式
   `PWD=<worktree>`；另外 `run` 要带 `--standalone`，否则后台常驻服务缓存旧配置，
   自定义 provider 报 `Model unavailable`。

**沙箱侧的 `PWD` 已修**（2026-11-02 14:10）：这条坑不只是冒烟脚本的事 —— 编排链
真正拉起 CLI 的两处都只设了 `ProcessBuilder.directory()`，继承来的 `PWD` 仍指向
宿主目录，于是 opencode 在真流水线里同样会把产物写到宿主仓根。两处都补了
`pb.environment()["PWD"] = <工作区绝对路径>`（先设、后灌 env，显式注入的 `PWD`
仍然赢）：

| 位置 | 用途 |
|---|---|
| `WorktreeSandbox.kt` 的 `ProcessBuilder(command).directory(worktree)` | 沙箱派单（A/B/C 全链路走这条） |
| `CliSubprocessDriver.kt` 的 `pb.directory(it)` | 裸 CLI 会话 |

各配一条回归用例，且都**验过不修就红**：

- `WorktreeSandboxExecutorTest.theChildSeesPwdPointingAtTheWorktreeNotTheHost`
- `CliSubprocessEnvInjectionTest.pwdFollowsTheWorkspaceInsteadOfTheHostDirectory`

两条都用 `/usr/bin/env` / `python3` 读**原始环境**，不用 `/bin/sh`：POSIX shell
启动时会拿 getcwd 校验并**重写** `PWD`，用 shell 探针等于自证（不修也能过）。
摘掉修复后的实测失败值正是宿主目录：

```
expected:<PWD=/var/folders/…/wt-sandbox-test…/t-pwd> but was:<PWD=/Volumes/Kapp/source/teahouse-mobile>
```

**实测**（`tools/real-cli-smoke.py`，2026-11-02 13:40–13:50）：

```
# chat
claude    PASS  rc=0  reply=PONG
codex     PASS  rc=0  reply=PONG
opencode  PASS  rc=0  reply=PONG
全部通过（3/3）

# agentic（临时 git 仓里创建 proof.txt）
claude    PASS  rc=0  proof.txt=CLI_OK
codex     PASS  rc=0  proof.txt=CLI_OK
opencode  PASS  rc=0  proof.txt=CLI_OK
全部通过（3/3）
```

**对后续复验的影响**：§13.1–13.13 的桩跑仍然有效（它验的是编排链路），但
「真模型产物能否被 `extractFileFromPatch` 抽出来」这条 §13.11 的未覆盖项现在**可以真跑**。
沙箱侧 `PWD` 已修（见上），`opencode` 写错目录这条阻塞已消除 —— A/B/C 真链路复跑
只剩「把 `tools/real-cli-smoke.py` 的配方接进 `SandboxedCliDriver` 的装配」这一步。

### 13.15 真 CLI 进沙箱：凭证白名单 + 权限旗标 + A/B 两轮实测

§13.14 把网关打通之后，真链路还差两步才真的能跑 —— 都是「沙箱的隔离挡住了 CLI 自己
的凭证」这一类问题。

**第一步：凭证从哪来。** 沙箱把 `HOME` 换成隔离目录（`prepareSandboxHome`，为的是不把
`.ssh` / `.workbuddy` 递给子进程），于是 CLI 在 `~/.claude`、Keychain 里的登录态**看不见了**。
把真 `HOME` 放回去是错的（那就等于取消隔离），正路是**显式注入**：新增
`CliProviderEnv`，按 CLI 分白名单搬运。

| CLI | 注入的变量 | 为什么是这几只 |
|---|---|---|
| claude | `ANTHROPIC_BASE_URL` / `ANTHROPIC_API_KEY` / `ANTHROPIC_AUTH_TOKEN` / `ANTHROPIC_MODEL` / `ANTHROPIC_SMALL_FAST_MODEL` / `ANTHROPIC_CUSTOM_HEADERS` | Anthropic 兼容面的全部上游开关（含网关要求的 `x-opencode-session`） |
| codex | `CODEX_HOME` / `OPENCODE_API_KEY` | 上游写在 `$CODEX_HOME/config.toml` 的 `model_providers` 里，key 走 `env_key` 指向的那只 |
| opencode | `OPENCODE_API_KEY` / `OPENCODE_BASE_URL` / `OPENCODE_CONFIG` | 自定义 provider 走 config 文件 + 环境里的 key |
| agy | （空） | 走 Google 登录态，没有可配的上游 —— 显式留空，不是「顺手全塞」 |

三条纪律：**白名单不是全量环境**（`AWS_SECRET_*`/`PATH`/`HOME` 一概不带）、**值只进子进程
环境**（提示行只报变量名，见 `CliProviderEnv.describe`）、**空集是合法值**（没配上游时
行为与注入前逐字一致）。装配处新增 `env` 参数（默认 `System.getenv()`），沙箱与裸两条
分支都带上；测试注入合成 env，结论与本机无关。

**第二步：headless 没人点「允许」。** 装配里 claude 原本只有
`-p … --output-format stream-json --verbose`，默认权限模式会把每一次写文件都拒掉 ——
派单永远「跑完了但没有交付物」。沙箱分支补 `--permission-mode bypassPermissions`，
理由与 agy 的 `--dangerously-skip-permissions` 同一条：写操作只落在**一次性 worktree**
（宿主仓零接触，收割后无痕清理），人的守门点是合流审查（merge-patch）。
**裸路径不加** —— 那里的工作区是机主自己的仓。

**A/B 两轮实测**（`RealCliChainEvidenceTest`，默认跳过，`TIDINGS_REAL_CLI=1` 才跑）：

```
[drivers] claude-cli ← provider env: ANTHROPIC_API_KEY, ANTHROPIC_BASE_URL,
          ANTHROPIC_CUSTOM_HEADERS, ANTHROPIC_MODEL, ANTHROPIC_SMALL_FAST_MODEL
          (values stay in the child process)
[sandbox] harvest t-1-45c01944: +1 -0 in 1 file(s), patch=118 B
[sandbox] task t-2-faa55b86 starts from merged base 54348ab6d9321d32e24d2175917d5cfd359ac277
[sandbox] harvest t-2-faa55b86: +2 -0 in 1 file(s), patch=126 B
[real-cli] A/B 两轮通过：B 轮在合并基线上读到了 A 轮的 a.txt，且只交付自己的改动
```

判据两条，都是「真模型 + 真沙箱」下的：

1. **B 轮看得见 A 轮**：B 的补丁里含 `ALPHA`（它读的 `a.txt` 是 A 轮写、合并、进快照的）；
2. **B 轮只交付自己**：补丁里**没有** `diff --git a/a.txt` —— 说明 harvest 是相对开工
   基线 diff 的，不是把上一轮重打一遍（D5 的判据在真模型下再验一次）。

耗时 21.8 s 两轮（`deepseek-v4.1-flash`）；凭证只在子进程环境里，不落盘。

**四个坑（都实测踩过）**：

1. **Gradle 不把环境变量当 test 任务的输入**。改了 `TIDINGS_REAL_CLI` / `ANTHROPIC_*`
   再跑，任务被判 UP-TO-DATE，拿到的是**上一次**的结果 —— 表现为「明明设了却 skipped」。
   要 `--rerun-tasks`（或 `--no-daemon` + 确保真的重跑）。
2. **跳过要如实**。默认路径早先写成「打一行日志然后 `return`」，报告里是一个绿点 ——
   那一轮什么都没验，这就是「为没发生的活报完成」。改成
   `AssumptionViolatedException`，报告里如实显示 skipped 并带上原因。
3. **凭证只能从宿主进程环境来**。`--no-daemon` 时单次 daemon 继承客户端环境；常驻
   daemon 有它自己启动时的环境 —— 这也是坑 1 的另一面。
4. **测试里 stub 掉 resolver 会打印误导性提示**（`codex CLI not found`），那是用例
   自己传的 `resolveCodex = { null }`，不是发现逻辑坏了。

**读数**：`:agentNode:test` **299 tests / 1 skipped / 0 fail**（41 suites，170 s）——
299 = 287 + 白名单纯函数 9 + 装配端到端 2 + 真链路 1（默认 skipped）；
`:desktopApp:test` **238 tests / 0 fail**（48 suites，5.5 s）。

**门禁现状（如实记）**：

- `envDependentTestGate` / `cjkLiteralGate` / `domainComposeFreeGate`：通过。
- `staleCommentRefGate`：**本轮修好**。它在 HEAD 上本来就是红的 —— 两处注释写着
  `app/relay/ui/AppStates.kt:23 → MessageState:87`，而 `EmptyState` 早已搬到
  `ui/AppStates.kt`，那个行号落在空行上。改成符号名（`ui/AppStates.kt`，内部转调
  `MessageState`），不写行号。用 HEAD 的临时 worktree 复核过：修之前 HEAD 同样 2 条红。
- `constructionTraceGate`：**仍红，且与本轮无关**（HEAD 的临时 worktree 同样 11 条）。
  命中是 10 条 `2026-11-01 产品级语汇裁决` 注释里的 ISO 日期 + 1 条内网地址，都在
  composeApp 的桌面 UI 文件里。门禁建议的改法是「留结论、去掉日期，缺陷史进 ADR」——
  那是一次文档策略决定（要不要为这些裁决补 ADR），不属于本轮范围，留给机主定。
