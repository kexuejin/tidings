# UI 精致度审计 · 2026-11-01

> 范围：`androidApp/build/outputs/screenshotTest-results/preview/debug/rendered/` 全部 **289 张**
> @Preview 渲染图（25 张 contact sheet 逐张过了一遍），加上 `ui/theme/` 六个 token 文件与
> `design/design-spec.json` 的对账。目的：回答「整体架构都在，为什么不像成熟产品」。
>
> 结论：骨架不差（浅灰底 + 绿主色 + 卡片列表是干净的）。破功的是四件事——
> **文案视角错、dev plumbing 上脸、密度失衡、规范取值未为本产品设计**。
> 修复分支：`design/polish-pass`（本文件所在 worktree）。

---

## A. 系统级问题（跨屏，优先修）

| # | 问题 | 证据（预览名） |
|---|---|---|
| A1 | **开发视角文案直接进 UI**：教程式长句当正文（"先去「节点」确认对方在线；Agent 一旦宣告了工作区，这里会自动长出卡片，不用手动加"）、操作提示当常驻 UI（"点击文件选中，点击文件夹进入"）、运维动词（"清理没有回应的（1）"）。成熟产品的文案是名词和状态，不是说明书 | AgentsPhone、AgentConsolePhone、CabinetPeerPhone、NodesPhone |
| A2 | **dev plumbing 上脸**：IP:端口作节点副标题、未配对节点名=IP、composer 常驻"局域网直连"/"草稿 0 / 800 B"、底栏"已连接 2 个节点 · 本机 n-self00 · 待补发 1"、设置-数据页显示本机绝对路径 `/Volumes/...`、传输日志黑底绿字终端风、连接授权/Agent 卡满屏 mono ID（@artifact/@builder/golden vector 段） | DeskConversationsWide、DeskNodesExpanded、SettingsDataCleanup、DeskTransferLog、ConnectAccountPhone、V2CardDiff* |
| A3 | **Agent 任务卡引入第三强调色**：紫/蓝紫 header + 紫进度条 + mono，与全 app 绿色系统冲突 | V2Card*（p1–p19 全系）、AgentCardDesk |
| A4 | **深色主题对比度不足**：卡片与底色同灰度，分割线/选中态难辨；红色 badge 成为深色屏上最响的元素 | DeskConversations*Dark、DeskSettings*、TabletSettingsOverlayDark、ConversationsDark |
| A5 | ~~**桌面 compact 回落成手机底部导航**~~ **误报（已核实）**：桌面壳 Compact 档画的是**自有** `CompactNavigationBar`（`DesktopNavigation.kt:181`，底部三段导航），Medium 档是 64px 竖导（`COMPACT_NAV_PX`/`MEDIUM_RAIL_PX`）；窄窗底导是刻意设计，与手机 `NavigationBar` 是两个组件两条路 | DeskFilesCompact、DeskNodesMedium |
| A6 | **空态无设计**：纯文字 + 按钮，无插画/图形；桌面"选一个会话"孤字悬在大片空白里 | ChatEmpty、DeskNoSelection*、DeskFilesPaneEmpty、ConversationsEmpty |
| A7 | **桌面 dialog 用手机密度**：建群/群信息/历史检索 dialog 字小行密按钮小，需桌面密度档 | ChatDeskGroupCreate、ChatDeskHistSearch、ConversationsGroupPhone |
| A8 | **按钮/动作层级乱**：~~双按钮高矮不齐~~（已核实为原型忠实——`.btn.sm` 28dp 砖与 `.ibtn` 34dp 砖本就两套规格，垂直居中排列，改齐反而漂移）；保留：屏幕协商页红色大按钮占比过大（红=最高警报色用在常规取消）；compact 下浮动刷新按钮压在底栏上 | RemoteView 协商屏、AgentsPhone、DeskAgentsCompact |
| A9 | ~~**行操作菜单未完成感**~~ **误报（已核实）**：生产代码走 `DropdownMenu` 卡片容器（`ConversationRow.kt:197`），裸列表只是 Layoutlib 不合成 `Popup` 容器的预览伪影（该 KDoc 自己写明了） | ConversationsRowMenuPhone |
| A10 | ~~**时间格式两套**~~ **误报（已核实）**：三层口径（列表相对时间 / 气泡 HH:mm / 分隔条 今天-昨天-日期）共享同一显示偏移（`Common.kt DISPLAY_UTC_OFFSET_MILLIS`），KDoc 有完整辩护，与微信等成熟 IM 一致。**保留真实问题：pill 标签五花八门**（同事/Agent/在线/未分组/可上传 形状底色字号各异，20+ 个私有实现） | ConversationsPhone、ChatPhone |
| A11 | **文件图标无类型区分**：md/zip/pdf/m4a 全是同一个文档图标；手机文件柜是"名称/大小"表格 + checkbox 列（网页 table 观感），与桌面三栏文件页是两种语言 | CabinetPeerFont2x、DeskCabinet* |
| A12 | **截断/溢出实测 bug**：工作区 meta "2 台设备 · 0 Agent · 0 工作区 · 0 …"；会话行右列 时间/待发/badge 三层堆叠挤压；"1/2 么和"这类半截文案 | AgentsPhone、ConversationsRow* |
| A13 | ~~**组件演示页混入英文标签**~~ **非缺陷（已核实）**：`ScreenshotPreviewsComponents.kt` 是组件族门禁帧（文件头写明），标签=组件 API 词汇（AppButton / Medium / Danger），非产品 UI，不进用户视野 | ComponentsButtonsPhone |

## B. Token 层取值问题（`ui/theme/`，本次分支先修这里）

| # | 问题 | 现状 | 目标 |
|---|---|---|---|
| B1 | **letterSpacing 是拉丁文调校**：bodyLarge 0.5 / labelMedium 0.5 / labelSmall 0.5 / bodySmall 0.4 / titleMedium 0.15 / titleSmall 0.1——汉字方块字加 tracking 发虚，小字没精神 | `AppTheme.kt` RelayTypography | **全部归零**（本次第一批改动） |
| B2 | **字阶是 M3 出厂默认**：15 角色全保留，display/headline 六档基本无人消费；层级靠"同号不同重"（titleSmall=bodyMedium=14sp、titleMedium=bodyLarge=16sp），中文 14sp 下 Medium/Normal 灰度差太小，主次拉不开 | 15 角色断言在 `design_contract.py: EXPECTED_TYPOGRAPHY` | 裁到 7~8 个中文语义角色（pageTitle 20 / listItemTitle 16 / body 15 / meta 12），**同步改 EXPECTED_TYPOGRAPHY**；号差做层级，不靠字重 |
| B3 | **字族未选型**：`FontFamily.Default`（Android=Roboto，中文回退系统字体，桌面取平台 UI 字体），无品牌记忆点 | AppTheme.kt | 评估中文优先字族（如 MiSans/HarmonyOS Sans/思源黑体 可变字体），至少桌面端定一份 |
| B4 | **间距 17 档、相邻 1dp**（2/3/4/5/6/7/8/9/10/11/12/13/14/16/24/32/48）：来自原型手调值 snap，"把不整齐制度化"。同屏三套节奏（气泡内 10 / 气泡间 14 / 行内 12–16 混用） | `Spacing.kt`，断言 `EXPECTED_SPACING` | 保留存量档位不删（棘轮兼容），**冻结新增**；新代码限 4/8/12/16/24/32/48 八档；中期一次性重排 |
| B5 | **图标缺光学档**：stroke 1.7@24 viewBox 缩到 14dp 发虚、放到 30dp 偏细；底栏粗描边+药丸 vs 桌面 rail 细描边裸图标，两种语言；rail 徽标比图标本体还宽；菜单按钮容器/图标比 ≈3:1（常规 1.6–2:1） | `IconSizes.kt` 六档值合理 | ≤14dp 加粗 stroke 变体；底栏/rail 统一 stroke 与选中语言；徽标缩到 16dp+白描边；按钮内图标提到 20dp |
| B6 | **跳档太大**：底栏图标 24dp+12sp 文字 vs 页面标题 ~28sp，中间无过渡 | 各屏 | 随 B2 一起收敛 |

## C. 逐屏要点（抽样列举，修复时以 A/B 类规则套用）

- **会话列表（ConversationsPhone）**：头部两行 + chips 行占屏过高；行内 meta "13 分钟前 / 2 待发" 双列小字挤；"星星电脑 你再来我等天说过，能用 1/2 么和" 截断。
- **聊天（ChatPhone / ChatDeskPane）**：日期 chip 是完整 "2025-01-01 08:00"；发送勾/时钟手机版悬在气泡外左下（桌面在气泡内，两端口径不一）；同发送者连发消息间距 = 不同发送者间距，分组语义缺失；"0 / 800 字符" 常驻。
- **节点（NodesPhone / DeskNodes*）**：IP:端口作副标题、"还没有回应"红字 + "清理"按钮、"备注"大 textarea + 独立"保存备注"按钮（应失焦即存）、"发消息"全宽巨按钮、"详细信息"IP/主机/平台 key-value 表——工程感重灾区。
- **工作区（AgentsPhone / DeskAgents*）**：米黄 banner + 长解释文案占正文；meta 行截断（见 A12）；双按钮不齐（见 A8）。
- **搜索/历史检索（ChatGlobalSearch* / HistorySearchDialog）**："发送『房间』，刷新室内 or 输入关键词或进 #标签" 类拗口文案；日历组件与 app 风格脱节；结果里 mono hash 直接展示。
- **设置（Settings* / DeskSettings*）**：深色下中英字段名并排（"昵称 nick"、"公司 company"）像 i18n 调试视图；色板选择器一行 12+ 裸色圆点（dev swatch 观感）；数据页显示本机绝对路径（见 A2）。
- **文件柜（Cabinet* / DeskFiles*）**："名称/大小"表头 + checkbox 列（见 A11）；行 meta 三行小字；桌面右详情"选中一个文件查看详情"大片空白与左列表密度失衡。
- **屏幕协商/远程观看（RemoteView* / Req/Share/ViewScreenHost）**：全屏黑底 + 孤字等待文案，无进度反馈设计；红/绿按钮语义错位（见 A8）。
- **桌面空态（DeskConversationsPaneEmpty）**：QR + 三步卡 + 底部橙色警告——密度尚可但卡片文字过小、警告文案偏运维。

## D. 修复路线（本分支按批提交）

- [x] **第 1 批（token 值级，零风险）**：B1 letterSpacing 全归零 → `design-spec-project.py --write` 重投影 → `--check` 对绿。
- [x] **第 2 批（字阶裁剪）**：实际落地 15→**11** 角色（计划的 7~8 过紧，保住 caption/label 层），`EXPECTED_TYPOGRAPHY` 同步；spec 重投影 + 289 张 preview 换代基线。
- [x] **第 3 批（文案 + plumbing 退场）**：教程句改名词句、UTF-8 计数注释退场（dda41a62）。**IP / 字节计数保留**——2026-11-01 用户以茶话间节点页为实用基准裁决：节点行 IP 是实用样本的一部分，chat_byte_hint 常显有辩护，「移诊断页」按用户裁决否决。
- [x] **第 4 批（系统统一）**：A5/A9/A13 经核实为误报/已有设计（2936b946）；实际落地空态居中图形化 + 导航徽标收紧 + 状态栏去节点 ID（067a59db）。
- [x] **第 5 批（质感）**：A6 空态图形头体系落地（含第 2 轮 DesktopNoSelectionEmpty 补强 0bca4261）；A4 深色容器阶梯修复（cc17f670）；A3 Agent 紫**否决**（原型自有 token --agent-t，对比度 6.1:1/7.6:1 达标，改绿会与 success 混叠）；A11 文件类型图标**暂缓**（原型 `icon:'file'` 本就不区分，做它=超出原型的新增设计，按「实用>复杂」待用户裁决）；B5 光学档**部分落地**（rail 徽标已收紧为 NavCountBadge；stroke 加粗变体需重绘资产，第 2 轮复审未见 14dp 发虚，暂缓）。

## E. 第 2 轮全量审计（2026-11-01，289 张渲染图 / 25 张 contact sheet）

五批修复完成后，对 worktree 重渲后的全部渲染图做了第二轮逐张复审：

- **确认生效**：空态体系（居中 + 64dp 软色圆底图形头）在会话/文件柜/聊天/节点各屏一致；
  导航徽标收紧（labelSmall 11sp 小药丸）；深色容器阶梯正常；桌面状态栏无节点 ID；
  字阶裁剪后层级由号差驱动，主次可辨。
- **本轮补强 1 项**：桌面未选中会话空态（DesktopNoSelectionEmpty）此前是原型 `.emptyd`
  逐值对齐的居中版式，但缺图形锚点——正是用户点名的「孤字悬在大片空白」。
  已补与共享 EmptyState 同一颗 EmptyGlyph（`AppStates.kt` EmptyGlyph 提为 internal 复用），
  偏差登记 fidelity-deviations.yaml（原型本无图形头，属有意补强，消除 demo 感）。
- **复核结论**：其余各屏（搜索/历史/设置/传输日志/远程观看/v2 审批卡/平板形态/深色形态）
  未发现新的 P0/P1 缺陷；筛选无结果类小空态按设计退化为无图形居中版（IconSizes/密度合规）。
- **最终验证**：十二项设计门禁全绿；六模块测试
  （core/app/cli/composeApp jvmTest + androidHostTest + agentNode + desktopApp，
  `-PluciolaNativeDir=/Volumes/Kapp/RustBuild/cargo-target`）全部通过。

**判定：两轮审计闭环，第 1–5 批全部落地，无遗留阻塞项。**

## F. 第 6 批：产品级语汇（2026-11-01 晚，用户复核「还没到产品级」后立项）

两轮缺陷导向审计闭环后，用户仍判定「不像成熟产品」。以产品设计师视角重审四张核心屏
（会话列表/聊天/节点/桌面三栏），根因不是 bug 而是**工程语汇裸露在主界面**：
标题栏印 IP:端口、输入区常驻「0 / 800 字符 · 局域网直连」、列表页解释排序算法、
发送队列计数直接上列表。统一裁决口径：**异常才提示，常态不说话**。

五处落地（d2824651）：

| # | 位置 | 前 | 后 |
|---|---|---|---|
| 1 | 桌面标题栏/聊天头部 | `192.168.1.1:49213 · 已连接 · 最后在线 刚刚` | `已连接 · 最后在线 刚刚`（peerPresenceLine）；地址留在详情栏/节点页 |
| 2 | 输入栏路由提示 | `局域网直连` 常驻 | UDP=常态不提示；改走 TCP 才提示；两键退场 |
| 3 | 手机字符计数 | `0 / 800 字符` 常驻 | 过半才显示，超限红字永远在 |
| 4 | 列表 meta | `2 待发` 文字 | ic_clock_pending 图标（语义留、语汇降） |
| 5 | 列表副标 | `…，按最近一条消息排` | 删除（排序算法解释是工具语汇） |

核实后不动的设计：节点页「设备（N）」分组标题（原型同款）；聊天头部「公司·部门·团队」
副标（身份信息，产品语义）；日期分隔条（TimelineSeparator 已是 今天/昨天/日期 相对化，
预览图的完整时间戳是夹具时差假象）；「没有回应」状态文案（与「离线」语义有别，有辩护）。

验证：十二门禁全绿；六模块测试通过；渲染图逐屏确认（标题栏/输入区/列表 meta 均已干净）。

## G. 第 7 批：视觉层——配色/间距/字号（2026-11-01 晚）

第 6 批解决语汇后，用户继续点名「整体配色、间距、文字大小也有影响」。三层重审结论：

- **配色：核实后不动**。茶绿+暖中性体系完整（surfaceTint 钉死、outlineVariant 去
  Material 紫、容器阶梯全补、语义对完整）；出/入气泡 = primaryContainer/surfaceVariant
  与原型 --bubble-mine/--bubble-peer 同源；品牌色出现在选中 chip、未读徽标、出站气泡、
  激活导航，密度与健康度达标。没有可判缺陷的项。
- **间距：核实后不动**。消息流 gap 10px（桌面 9px）、气泡 padding 8/12、列表行节奏
  全部原型锁定且已逐值对齐。
- **字号/布局：两处落地（本批）**：
  1. **气泡正文 14→16sp**（纯文本 + Markdown 基准同步）。聊天是主界面，14sp 对话
     正文是 demo 感来源（iMessage/WeChat 17、Telegram 15~16）；原型默认 14px 但自带
     font-lg 档=16px（relay.html:1617），16 在原型设计空间内。偏差登记。
  2. **气泡 meta 行重组**：时间右对齐、投递勾/时钟与时间同行收进气泡内（Telegram 式）；
     文字气泡不再在页边漂浮投递图标（文件/图片/PK 卡保留外置标记）。原型气泡本不含
     时间/状态（1256 行「气泡只装话」），此行是代码侧补的，摆法不受锁定。偏差登记。

验证：十二门禁全绿；六模块测试通过；渲染图确认（时间 08:02 ✓ 同行右下、页边干净、
正文 16sp）。共享组件一处改动同时覆盖移动端与桌面端。
