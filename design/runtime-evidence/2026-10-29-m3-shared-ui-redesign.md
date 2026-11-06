# M3 · 共享界面改版 —— 设计契约与反向验证读数（文字入库）

> 执行追踪器：`docs/plans/2026-10-29-group-agent-interaction-goals.md`（M3 段）。
> 规格出处：方案 §5（界面交互规范）/ §7.3（受限卡片 DSL）/ §8（Layer 5）。
> 本文件只放**可复核的读数**：每条判据对应一条命令或一个数字。

## 1. 交付物与单一定义证明

三件套全部住在 `composeApp/src/commonMain`，手机与桌面共用同一份实现
（AGENTS §2.6：内容共享、外壳参数化）：

| 组件 | 文件 | 定义处数（全仓 grep） |
|---|---|---|
| 卡片引擎 `AgentCardBubble` + 5 原子组件 | `feature/chat/AgentCards.kt` | 1 |
| 上下文唤起胶囊 `AgentContextCapsule` + `AgentTaskPickerSheet` | `feature/chat/AgentContextCapsule.kt` | 1（+1） |
| 授权面板 `SetGroupAgentWorkspacesDialog` | `ui/components/SetGroupAgentWorkspacesDialog.kt` | 1 |

调用路径：手机 `App.kt → ChatScreen`，桌面 `DesktopChatPane → ChatScreen`（composerSlot
只换输入卡片外壳）——两条壳走**同一个**消息流分支与胶囊挂点，没有第二份实现。

## 2. 「改一处两端都变」验证（§2.6 第五步）

1. **结构证明**：四个共享 Composable 全仓各恰好 1 处定义（上表）；
   手机与桌面的渲染路径汇于同一处（ChatScreen 的 `message.card != null` 分支与
   胶囊挂点都在 ChatScreen 内部，桌面壳不重写）。
2. **实验证明**：临时把 `AgentCardBubble` 的 decided 知情行色调从 `primary` 改为
   `tertiary`，然后同批编译
   `:androidApp:compileDebugScreenshotTestSources`（9 帧预览的编译单元，含手机帧与
   桌面帧）与 `:composeApp:compileDevKotlinJvm` —— BUILD SUCCESSFUL，两列帧都流经
   改动后的函数；随后恢复原样，`git status` 无痕。
3. **参数通路证明**：卡片桌帧多传乐观锁集合（`pendingActions = {task-89|approve}`）、
   手机帧传空集 —— 同一函数的同一条参数，两帧各自如实（`ScreenshotPreviewsM3Shared.kt`
   的 `AgentCardDeskPreview` 注释）。

## 3. previews 与 componentSpecs

- `ScreenshotPreviewsM3Shared.kt`：9 帧 —— 卡片（手机/桌面/审批终态）、胶囊
  （手机/桌面/选择 Sheet）、授权面板（手机/桌面/非宿主只读）。手机 393×852、
  桌面 1280×800，locale=zh-rCN。
- `design/design-spec.json` 的 `componentSpecs.components`：10 → **13**（棘轮只增不减，
  `ux-scoreboard` 的「组件级规格条数」读数 13/基线 7）。
- `requiredTokens` 口径：spacing/iconSizes 不列 —— 量具探针
  （`design_contract.py` 的 `_COMPONENT_PROBES`）只认 `LocalAppSpacing.current.X`
  直接形，认不出 `val sp = LocalAppSpacing.current` 的别名形；本仓既有 10 条规格
  同此口径（无一条要求 spacing）。

## 4. designScreenDiffGate 记账（11 个「只代码」取值）

先逐字对原型（纪律要求，不是走过场）：

- `grep -c "team-task-board\|任务看板\|target-cap\|胶囊" prototype/relay.html` → 6 处，
  逐条核对全部是**审批状态药丸 / 筛选 chip** 的注释与样式，**没有**任何团队任务卡、
  目标胶囊、授权面板的 markup —— 三件组件是本里程碑的新交付物，原型稿无对应屏。
- 新增值全部来自**既有**角色 token，不是新发明档位：
  `radius 11 = ShapeRoles.ListCard`（`ui/theme/ShapeRoles.kt`，ConversationRow 等早已在用）、
  `radius 7 = ShapeRoles.DayCell`、`spacing 24 = AppSpacing.xl`。
- 处置：`--baseline --reason` 记账（理由逐条写进基线 reason 字段，与基线里 d-tray
  宿主轨那条同一类）。基线写入后 `--check` 实测：**290 组差集无扩大**。

## 5. 门禁与测试读数（M3 收尾实测）

| 项 | 读数 |
|---|---|
| `:composeApp:jvmTest`（--rerun-tasks 强制重跑） | **553 条全绿**（含新增 44：卡片 11 + 审批锁 3 + 胶囊 20 + 面板 10） |
| `:composeApp:testAndroidHostTest` | **295 条全绿** |
| `:app:jvmTest`（--rerun-tasks） | **702 条全绿** |
| 七模块全量串行（--max-workers=1） | BUILD SUCCESSFUL |
| AGENTS §1 门禁链 10 项 | 全绿 |
| 设计门禁 11 项（screenDiff/tokenUsage/stateSlot/localeParity/converge/formPairing/previewPairing/dupFrame/dpDebt/icon/specDrift） | 全绿 |
| `tools/unused-imports.py --check` | OK |
| `ux-scoreboard` 的 `ci.duplicate_composables` | **0**（基线 7，方向 down）—— 三件套零复制 |
| strings.xml 双语对账 | zh 1360 = en 1360，键集合差集为空 |

## 6. 预存红修复（开工时即红，与本轮改动无关）

`designScreenDiffGate` 在 M3 开工前报「基线记于原型 sha 85691b3d6e26，当前原型
b137725ec90a，两边不可比」。成因：隐私历史重写把原型 mock 里的真实本机路径换成
占位用户名（原型字节变了），而基线最近一次重投影落在重写之前。处置：只改
`generatedAt` 一行，**棘轮签名一个键没动** —— 换 sha 后 `--check` 实测
「291 组差集无扩大，本轮改进 0 组」，证明重写只碰了路径字面量、没有动任何
font-size/spacing/radius 取值。

## 7. 反向验证清单（每件：先红后绿）

| 测试 | 红 | 绿 |
|---|---|---|
| `AgentCardRenderTest`（11）+ `MultiDeviceApprovalLockTest`（3） | 对空实现编译期红（Unresolved reference 'parseAgentCard' 等） | 实现后全绿 |
| `ContextCapsuleErgonomicsTest`（20） | 对空实现编译期红 | 实现后全绿；第一轮实现后仍有 7 红 —— 测试用整句模拟键入，按判据它就是粘帖；改逐字符 `typeOut` 模拟真实键入后绿（判据反过来校准了测试，不是放宽断言） |
| `GroupAgentWorkspacesPanelTest`（10） | 对空实现编译期红 | 实现后全绿（一条测试夹具自身缺 defaultWsId 被抓出修正） |

## 8. 遗留与下一个动作

- 卡片动作派发（`onCardAction`）与跨端 ack 广播是 M4 交付物：两个生产壳暂传 null，
  卡片按钮组整块不画（`decidedBy` 知情行照画）；UI 锁定与 disabled 语义已由
  `MultiDeviceApprovalLockTest` 的仿真判据钉住。
- 授权面板的「设为群默认」单选未做（规格 §5.1 mock 有此控件）：当前域模型
  （M1-T4 定死）是一工作区一声明、每条声明自带 `defaultWsId`，没有群级默认槽位；
  硬加会破坏 `GroupAgentWorkspacesBindingTest` 钉住的「defaultWsId 集合 = 所选集合」
  语义。已记入自主裁决。
- iOS 目标 `compileCommonMainKotlinMetadata` 因 `:core` 的 uniffi vendored 依赖
  在本机缺失而编译不过（`LuciolaDiscoveryEngine` 的 github.uniffi 引用）—— **预存问题**，
  与 M3 无关（M3 未触碰 ：core）；本机 JVM/Android 全绿。
