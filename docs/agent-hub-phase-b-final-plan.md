# 智能体中心 Phase B：卡片流一级页（决策稿）

> **状态**：待评审（v1）。**裁决人**：产品（2026-11-11，口头裁决「走 Phase B 卡片流」）。
> **上游**：`docs/project-directory-and-execution-site-model.md`（三层模型不变，本文只动 IA 层）、
> `docs/tenon-agent-hub-spec-review.md`（事实与缺口清单，本文逐项吸收）、
> `docs/tenon-agent-hub-and-team-orchestration-spec.md`（SPEC-V2，本文是其可执行子集）。
> **取代**：`docs/team-folder-final-plan.md` 的 D1/§2/§7（「不新增一级页/状态中心」）——
> 反驳依据见 §1；其余裁决（折叠形态 D8/D9、席位定义、执行配置宿主本地）**全部保留**。
> **证据口径**：只写符号名 + 文件路径，不写行号（AGENTS §2.1）。

---

## 1. 为什么可以取代（反驳依据，写给未来的自己）

`team-folder-final-plan` 否决一级页时的三个前提，今天各自变了：

1. 「团队资产只在群里可见」——当时**没有**群外的团队聚合投影。现在有：席位
   （`VirtualAgentMember`）+ 执行档（`TeamExecutionStore`）+ F4 会话归属台账都是已落地的事实，
   「这是同一支队伍」的判定有承载物。
2. 「状态中心没有数据源」——当时审批无群归属、执行无跨群视图。现在 F4 台账
   （`SessionConversationLedger`）+ 席位投影让「按群状态」可派生。
3. 「第 4 格是工作区治理面，改走组织视图会丢治理」——治理面保留（§4），不是二选一。

竞品结论（飞书/Slack/LobeHub/dsh-bot-team 无团队一级页）**仍然成立且被接受**：Tenon 的差异点
是「数字员工组织」，一级页服务的不是「聊天分组」而是「编制与执行」，与竞品的「群列表」不同层。

## 2. 页面信息架构（IA）

智能体页 = **两段一保留**：

| 段 | 内容 | 数据源 |
|---|---|---|
| **智能体卡片流**（新增，置顶） | 每个「(宿主, agent)」实体一张卡：身份 + 角色（单兵/团队 N 人）+ 服务群数 + Urgent-First 状态行 | §3 投影 `hubAgents` |
| **智能体详情页**（B4 增补，点卡的第一落点） | 实体视角整页：身份/角色/组员、服务群列表（每群执行点状态 + 本项目目录名）、项目目录行（→ 工作区详情）、待审批处置卡、该 agent 会话流 | §3a 投影 `agentHubDetail` |
| **工作区治理段**（保留，原三级下钻） | 设备→Agent→项目目录行、待审批卡、授权直通码、会话事件流 | 既有 `AgentWorkspaceBoard` 全套 |
| 页头/导航 | 名称「智能体」、图标 `ic_terminal`（不变，F4 裁决）；`WorkspaceBoardTitleBar` 组件保留但**只在手机壳画**，桌面壳传 `showHeader = false` 不画 | — |

手机单列（居中限宽回落 `ContentWidths.Pane=720`，**不引入 560**）；桌面两栏：左列卡片流，
右列沿用现有下钻（`DesktopView.Agents` 内部结构不变）。

桌面两栏**不画页面级页头**：壳顶部已有窗口标题栏 + 工具栏，各 tab 内容区都不画第二条
`LargeTitleBar`（节点页 / 聊天页在桌面壳都传 `showHeader = false`）。此前智能体页漏传该参数，
于是在两栏之间多出一条按 `ContentWidths.Pane` 收列居中的大标题，副标题与刷新圆钮都跟着飘在
中间——双层 chrome，位置错误。刷新由壳的溢出菜单与快捷键承接，建群由正文「绑定首个项目目录」
与壳的对话框承接，均不依赖页头。手机单列仍画页头，五个首页 tab 共用一条顶栏不变。

## 3. 统一智能体实体（合并规则，写死）

**实体键 = `(hostNodeId, agentId)`**（与席位身份同口径；`agentId` 用 `resolveVirtualAgentKey`
的归一、大小写不敏感）。一张卡聚合三路事实：

| 路 | 键 | 取什么 |
|---|---|---|
| 工作区行 | `AgentUi.WorkspaceKey(peerId, agentKey, wsId)`，按 `sameAgent` 归到实体 | 项目目录数、在线态 |
| 群席位 | `GroupMeta.virtualAgents[]`，`hostNodeId == 实体宿主` 且 `sameAgent` | 服务群数、团队角色（leader/组员数）、每群执行点状态 |
| 会话/事件 | `AgentSessionUi`/`AgentEventUi` 按 peerId+agentKey | 执行态、最新事件文案 |

**不做的合并**：宿主不同的同 kind agent 不并卡（执行点是设备绑定的）；群组员
（`TeamMemberInfo`）不生成独立卡（对外单一窗口，挂在 leader 卡上显示「团队 N 人」）。

## 4. 卡片内容与 Urgent-First（如实口径）

- **P1 审批优先**：该实体有待审批 → 卡顶红行「N 条待审批 · <群名>」。群归属来源：
  宿主侧 = F4 台账（`conversationOf(sessionId)`，已有）；**客户端（手机）侧** =
  审批入站帧的投递会话（审批帧落在哪个会话，就是哪个群）——零协议；若投递上下文
  归因不了的（直通宿主私聊），显示全局计数不写群名（**不编造**，AGENTS §2.5）。
- **P2 执行中**：`AgentSessionUi.state == BUSY` → 「正在执行 · <最新事件 summary>」。
  **不显示 (18/22) 结构化进度**（协议无此字段；显示执行态 + 最新事件文案，评审 Q7 降级裁决）。
- **P3 最近兜底**：最新事件 `at` + 空闲/离线态。
- **服务群数**：席位聚合现算（纯投影），「当前聚焦」行**不做**（排序规则未定义，评审 Q8）。
- 卡片动作（B4 修订）：点卡 → **智能体详情页**（实体视角）；详情内项目目录行 → 既有工作区详情（治理面从详情可达，不消失）；[对话] → 既有 1:1 入口。手机全屏二级页（新 nav 路由），桌面两栏右列切换上下文。
- **§3a 详情投影**：`agentHubDetail(hostNodeId, agentId)` = 实体身份 + 组员清单 + 服务群列表（群名、席位执行点状态：已解析 wsTitle / 未暴露——`defaultWsId` 与 `workspaceOfGroup` 同源）+ 项目目录行 + 可处置待审批全行 + 会话列表（`visibleInDsh` 口径）。全部现有切片派生，零协议零存储。

## 5. 数据与门禁契约

- **零协议、零 caps、零 .sqm**（本轮 IA 层改造的硬边界）：审批群归属走投递上下文 +
  F4 台账，不加帧键、不加 DB 列。
- 新增纯投影 `hubAgents(...)`（`app/.../domain/`），带单测；UI 只消费投影。
- 组件共享（AGENTS §2.6）：卡片**内容**与手机/桌面共用一份；外壳参数化；
  复用 `InboxUnitRow`/`WorkspaceUnitCard`/`AgentApprovalsSection` 的既有形态语言。
- 门禁预算：`cjkLiteralGate`（新文案进 values*/strings.xml 双语）、`designLocaleParityGate`、
  `designSpecDrift`（重投影）、`TabChromeConsistencyGuardTest`（组件骨架不动；新增一条壳级守卫：桌面壳 `showHeader = false`、手机壳不显式关）、
  截图重拍（新卡入镜则 `updateDebugScreenshotTest`）。
- 不动折叠（药丸+展开保留）：消息列表形态与 Hub 是两层，互不替代。

## 6. 分批

| 批 | 内容 | 协议/存储 |
|---|---|---|
| **B1** | §3 投影 `hubAgents` + 审批群归因（投递上下文 + F4 台账）+ 单测 | 零 |
| **B2** | 手机卡片流 UI + 桌面左列卡片流（内容共用） | 零 |
| **B3** | 下钻接线（点卡→治理段）、空态、真机走查（与清库重配同批） | 零 |

## 7. 不做什么（本期）

- 在 Hub 里组建「不属于任何群的通用团队」（无实体无落点，评审 §3.4）——团队创建入口
  仍在群信息页；全局团队模板是独立立项。
- 手机 Direct-API 驱动（评审 §5-7 四项工作量，独立立项）。
- 「当前聚焦群」、结构化执行进度、跨设备项目一致声明。
