# 全屏视觉对齐审计 · 2026-11-07

> 范围：78 个原型屏（prototype/relay.html）↔ 306 张 @Preview 渲染帧的逐屏并排对账。
> 方法：`tools/design-parity-sheet.py`（本轮新增）按配对台账生成 126 张并排图 + 7 张域
> contact sheet，逐屏人审；与 `design-screen-diff --all`（取值差集）、
> `check-design-palette --strict`（像素色溯源）、四维静态审查（本会话早前完成）交叉验证。
> 每条分歧按用户裁决原则分三档：**A = 原型合理、修代码**；**B = 原型有误、不回抄**；
> **C = 代码有意优于原型、登记**。存疑的标「待裁决」。

---

## 0. 量具与证据链事故（本轮先修，全部已修）

这批不修，后面的「对齐」无从谈起——三个量具当时都在带病运行：

| # | 事故 | 根因 | 修复 |
|---|---|---|---|
| 1 | **116 张原型截图全是空白页**（11-06 23:36 全量重拍入库，manifest 把空白 sha 固化成「正常」） | relay.html 的 boot 首渲染调用在 029e174a 之前的编辑中丢失；shoot-relay 靠页面自渲染，`dump-layout` 走 iframe 注入所以一直没暴露；sha 门禁只绑「拍没拍」绑不了「拍到了内容」 | 恢复 `go(curScreen)`（relay.html 尾部）+ shoot-relay 加空白帧检测（直方图主色占比 ≥99.5% 判 FAIL）+ 116 帧全量重拍为真实内容 |
| 2 | **spacing 棘轮少记 3 处**（DesktopComposer.kt:519 行注释里的 `arrayOf("*/*")` 让「先剥块注释」的正则把约 280 行真实代码吞成空白） | `strip_comments` 四份正则副本同病 | 新增 `tools/ktcomments.py`（字符串感知单遍状态机，全仓唯一实现），design_contract / design-screen-diff / render-preview / nullable-callback-wiring 四处收敛；spacing 92→95 与独立扫描对齐 |
| 3 | **render-preview 自检全量误报**（rebrand 后 Res import 正则停在 `app.relay.*` 旧包名）+ 自检扫进测试源集（守卫测试把 `Res.string.X` 当锚点字符串） | rebrand 漏改 + 扫描范围过宽 | 正则改 `com.kapp.tenon.generated.resources`；扫描排除 jvmTest/commonTest/androidTest |
| 4 | design-screen-diff `--markdown` 对「落点不存在」行崩溃（缺 `files` 键） | 存量 | `row.get("files")` 守卫 |
| 5 | 新增 `tools/design-parity-sheet.py`：台账驱动配对 + magick 并排（左绿原型/右棕代码）+ 按域 contact sheet。深色 shot 优先配 Dark 命名帧，无 Dark 变体如实缺配 | 此前并排配方只存在于 docs/plans 一行注释里，无工具化 | 已入仓，P2/P4 全部视觉证据由它产出 |

## 1. 配对覆盖（本轮从 70/78 到 78/78 有行、12 条如实缺帧）

- **台账新立 8 行**：m-chat-more、m-chat-group-more、m-createws、m-chat-workspace、
  m-session-detail、d-createws、d-wsgrant、d-grantqr；**m-workspacedetail 落点翻正**
  （2026-10-13 记「待建」时未实现，现 `WorkspaceDetailView` 已落地）。
- **新增 7 帧**（androidApp/src/screenshotTest）：ChatMoreOpenPhonePreview、
  ChatGroupMoreOpenPhonePreview、ChatWorkspaceRunPhonePreview（三条横幅+打断键全在图）、
  WorkspaceGroupCreatePhone/DeskPreview、WorkspaceQrDeskPreview、
  WorkspaceDetailPhonePreview。配对门禁 `OK — 78 行全部本屏`。
- **已有帧立行**：d-wsgrant（GroupAuth 三帧）、m-session-detail（AgentsSession 五帧）。
- **12 条声明无帧保持登记**：Popup 内容不可拍（m-home-more/m-msgmenu/m-chat-solo 同帧覆盖）、
  宿主轨（d-tray/d-drop/d-capture×2）、代码待建（m-agentdetail/d-agentdetail/m-agents-empty）。
- **状态槽 68/68 全绿**（agents|loading、remote-view|error 实际已有帧——
  `AgentsLoadingPhonePreview` / `RemoteViewErrorViewerPhonePreview`，基线陈旧被棘轮自然消化）。
- **深色变体缺口（遗留账）**：m-home-recent / m-chat-group / m-histdate 的 dark shot
  没有 Dark 命名的代码帧，并排图如实缺配（`m16-chat-group-dark` 等三张深色原型无人对）。
- **夹具欠武装（遗留账）**：`ChatAgentToolsPhonePreview`（m-chat 的主帧）渲染的是**空聊天**
  ——原型 m03-chat 的 Agent 卡片/断网横幅/重试队列/会话切换条从未进过任何一张图。

## 2. 逐屏裁决

### A 档（原型合理、代码偏离）→ 本轮已修

| # | 屏 | 现象 | 原型证据 | 代码落点 | 修复 |
|---|---|---|---|---|---|
| A1 | m-chat（Agent 会话） | 头部连读「在线 / 在线」两行 | m03-chat：副标题 = IP:端口·TCP·能力，无第二份在线态 | ChatHeader.kt:191-203（副标题尾 append 在线态 + titleTrailing 重复） | 副标题只放「工作区 · 会话 · 状态」，两段皆空才回退在线态 |
| A2 | 平板设置覆盖层 | 根背景 surface，与手机/桌面设置的 background 互斥；页面与分组卡同色 | m12-settings 系（页面=background、卡=surface） | App.kt:1122 | surface → background |
| A3 | m-histdate | 日期格圆角 6.dp，与 ShapeRoles.DayCell(7) 差 1dp——角色的声明出处自己不吃角色 | `.hsdays` radius 7×660（offladder 表） | HistorySearchOverlay.kt:942 | 接线 `ShapeRoles.DayCell` |
| A4 | m-set-avatar | 全仓唯一手写 `RoundedCornerShape(9.dp)`（ShapeRoles.kt 明文禁止） | `.asi` border-radius:9px | SetAvatarPage.kt:503 | 接线 `ShapeRoles.Avatar` |
| A5 | 全部徽标族 | 纵向 padding 1.5.dp 残留 4 处，与 ConversationRow.PinnedTag 批次登记的 2dp 口径分裂 | 原型 1.5px；登记口径 = snap 2dp | Common.kt:274 / AgentCards.kt:726,1123 / AgentSessionWidgets.kt:559 | 归一 `sp.xxs` |
| A6 | m-agents / m-home-recent 等 | 「N 待批」角标三处三种形状两种色（6.dp 方角+errorContainer vs percent-50+errorContainer） | `.tag.pend` = 999px 药丸 + 实心 --badge/--on-badge（relay.html:360） | AgentSessionWidgets.kt:470,550 / WorkspaceUnitCard.PendingPill | 三处归一：percent-50 + accents.badge/onBadge |
| A7 | m-chat-workspace | Agent 执行条画成半透明大绿块（primaryContainer@0.6 → #D2EDDA，色板说不出出处）；原型是 surface-2 底 + primary 字 | screenMChatWorkspace（`background:var(--surface-2);color:var(--primary)`） | GroupProgressBar.kt:46,65 | SurfaceTiers.Raised + primary 文字 |
| A8 | WorkspaceGroupCreateDialog | 开关行底色 surfaceVariant@0.5 → #F7F7F4（派生档外，palette 红三张帧） | （无对应原型值；M3 surfaceContainer #F6F5F0 与它逐通道差 1） | WorkspaceGroupCreateDialog.kt:322 | surfaceContainer |
| A9 | 桌面托盘 | 未读点 #E53935 = RelayError(#E63946) 的第二份真相 | —（无原型帧，d-tray 宿主轨） | DesktopServices.kt:125 | `RelayError.toArgb()`（同函数底板已是 toArgb 先例） |
| A10 | 桌面截图 | 选框色 `java.awt.Color(0x2D,0x6A,0x4F)` 手抄 primary | — | CaptureOverlayView.kt:150 | `java.awt.Color(RelayPrimary.toArgb())` |
| A11 | m-set-avatar / PrimitivesAvatar | 头像选中点固定白色，浅底（杏黄）上不可见（:123 注释自认同族问题） | — | AvatarPalette.kt:267 | 选中点走 `foregroundOn(底色)` 判墨 |
| A12 | workspace 单元卡 / 节点详情 | `MutedTag` 两份逐字相同的 private 实现 | `.tag.muted`（relay.html:310） | WorkspaceUnitCard.kt:384 / NodeDetail.kt:500 | 收敛到 ui/components/Common.kt 一份 internal |

### B 档（原型有误/演示残留，不回抄）

| # | 现象 | 判断 |
|---|---|---|
| B1 | 原型 m-addnode / m-myqr 的**棋盘格假二维码**占位 | 代码用真 QR（QrCodeView 物理约束）——延续用户此前「删掉连接设备假二维码」的裁决，保持 |
| B2 | 原型节点/工作区列表 8 台设备的演示密度 vs 代码夹具 1-3 台 | 数据密度差异非样式缺陷；夹具加富属后续可选 |
| B3 | 原型 `.bn.done` 用 emerald rgba(16,185,129,.07)，代码以 accents.online(#2E9E5B) 为底 | token 映射的有意选择（不引入第二个绿色 token），登记不改 |

### C 档（代码有意优于原型，已登记/KDoc 在案）

| # | 现象 | 登记处 |
|---|---|---|
| C1 | full:true 聊天帧画了底导（原型整屏无导航） | ChatPhonePreview KDoc（「一张图同时钉住顶栏+底栏徽标」），本轮新帧沿用同一口径 |
| C2 | 顶栏动作钮的 surface-2 砖底（IbtnBox） | 与原型一致（relay.html:773 `.ibtn{background:var(--surface-2)}`）——审查中曾误判为偏差，贴行号核实后**不改** |
| C3 | 原型 `.bn.*` 横幅 alpha（0.06/0.07/0.08/0.09）不在 Material 派生档内 | `check-design-palette.py` 新增 PROTO_ALPHAS 档（带 relay.html:556-559 引文），量具层面认账 |

## 3. 量具读数（前 → 后）

| 量具 | 前 | 后 |
|---|---|---|
| design-token-usage（欠债） | spacing 95 / shape 87 / color 13 | **spacing 91 / shape 83 / color 11**（档外半径 9 已清） |
| design-screen-diff | 401 组 | **400 组**（1.5px 归一后 proto 侧成为已登记偏差） |
| design-preview-pairing | 70 行（8 屏无行） | **78 行全部本屏**，欠武装无新增 |
| design-state-slot | 68/68（基线陈旧） | 68/68 显式绿 |
| check-design-palette --strict | FAIL：25/315 张、**14 个**无出处色（最差帧 11.89%） | FAIL 收窄：**3 个**（#CEDFD6/#E4D3D4/#0F251B，全在 V2Card RevisionDrawer/Acceptance 叠 alpha 区，最差帧 6.31%）——#F0F8F3/#F6F5FE/#FEF7EC/#F2EFFD/#D2EDDA/#F7F7F4/#EFF3F1 等已修或入 PROTO_ALPHAS；#2F80A7（头像数据色）入白名单 |
| :androidApp:validateDebugScreenshotTest | — | 306 帧全绿（含 7 张新帧 + 修后重渲） |

## 4. 遗留账（本轮不做、已记账）

1. **字重 token 化**：209 处裸 FontWeight vs TextWeights.Emphasis 6 处消费（65 个文件）——
   需要一次专门的批次（SemiBold/Bold 同语义互窜是最大的一块），机械替换会动大量帧。
2. **深色变体缺帧**：m-home-recent / m-chat-group / m-histdate 三张 dark shot 无 Dark 帧。
3. **`ChatAgentToolsPhonePreview` 夹具欠武装**：Agent 聊天的满内容态从未进图。
4. **对话壳 20/16/12、分段轨道 10/12、搜索框 9/8/12** 三族圆角统一——需先逐屏贴原型
   行号（各自的原型 selector 不同，不能一刀切），已在 screen-diff 差集里挂着。
5. **桌面 pane 边距 8/11/13/16/32 五档**、对话框 24+8 双层——同上，待逐屏裁决。
6. palette --strict 余量（3 色，已定位到帧）：#CEDFD6 / #E4D3D4 / #0F251B 全部出自
   V2Card RevisionDrawer/Acceptance（`AgentCardParts.kt:314` 区的叠 alpha 族）——
   量具本轮已修两处精度（PROTO_ALPHAS 档 + Skia 8bit alpha 量化），剩余三色需逐行
   读 Diff/验收卡的 tint 叠层后归档或归档外。

## 5. 结论

骨架层面（导航、页面结构、卡片构成、组件选型）代码与原型已高度一致；「看起来别扭」的来源
不是结构性偏离，而是**细节层的词汇分裂**：同一语义在不同文件各写一套（徽标三种形状、
列表行七种边距、字重四处各写、alpha 手调六档），加上三个量具带病运行让这些分裂在门禁眼皮
底下合法化。本轮把量具修真、把证据链（116 帧原型图 + 126 张并排图）重建、把 12 处确定性
A 档修掉之后，剩余问题都有明确的账目和量具挂钩——下一批照 §4 的顺序清即可。
