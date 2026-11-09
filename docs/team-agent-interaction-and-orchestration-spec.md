# 榫卯 (Tenon) · 团队智能体协同交互：增量差异表
# (Team Agent Interaction — Delta Spec)

> **文档标识**：`SPEC-TEAM-AGENT-INTERACTION-2026-11-08`（v8 校勘）
> **文档性质**：相对既有基线的**增量差异表**，不是独立架构规范；**状态：待评审（技术裁决已收敛，无 `[待核实]` 项；v7 按代码修正折叠接线落点，v6 撤销 1 处误判，v5 修正了 v4 的 6 处设计错误）**
> **决策以最终方案为准**：形态、裁决、改造量与验收见 [`team-folder-final-plan.md`](team-folder-final-plan.md)（本文的 §3.5 / §7.1 中"组头替代会话行"已被该方案取代，仅作过程记录）。
> **证据时点**：2026-11-09 工作区磁盘现状（含未提交改动）
> **对齐基线**：
> - 局域网协同主干：[`docs/lan-team-agent-im-detailed-design.md`](lan-team-agent-im-detailed-design.md)
> - PPE-1 协议口径：[`docs/PROTOCOL-AGENT-WORKSPACE.md`](PROTOCOL-AGENT-WORKSPACE.md)
> - 插件扩展 LLD：[`design/spec/tenon-plugin-extensibility-detailed-spec-2026-11-05.md`](../design/spec/tenon-plugin-extensibility-detailed-spec-2026-11-05.md)
> - 交互大一统口径：[`docs/unified-chat-and-agent-interaction-architecture.md`](unified-chat-and-agent-interaction-architecture.md)
> - 工程红线：[`AGENTS.md`](../AGENTS.md)
> - 工作稿参考（**未入库，不作权威依据**）：`docs/AGENT-TRIGGER-AND-PLUGIN-SPECIFICATION.md`，处置见 §8
> **证据口径**：代码引用一律以**符号名**为准，不写「文件名 + 行号」形式的引用；表中「现状」列均为 `[事实]`（符号名即来源），`[推断]` 必附推理链，`[待核实]` 不得写成结论且必须给验证方法。基线互相矛盾时**以代码为准**。**裁决项必须在 §6 给结论**，不得以「开工前再定」挂账。
> **v5 校勘（相对 v4）**：① **C4 方案换了**——v4 的"升级 `orderConversationList` 返回类型"会级联改 `ConversationSidebar(sessions: List<ConversationUi>)` 这个 public 签名、四个分档调用点、预览与截图夹具，代价被严重低估；改为"分组投影纯函数 + 共享组头组件，只接共享容器（v7 按代码修正实现份数），不动既有排序契约"。② **成员行不能复用 `ConversationRow`**——它要 `ConversationUi`，而成员只有 `TeamMemberInfo`；成员行是组头内部的轻量行。③ **`agentId` 上线还受 `aws1` 门控**——`sendAgentSession` 只在 `peerDeclares(CAP_AGENT_WORKSPACE)` 时才带 `agentId`，v4 只写了 `agn1`，缺 `aws1` 时聚合键为 null、呼吸点全 Idle。④ **聚合匹配口径是 `agentId ?: agentKind`**（照 `countWorkspaces`），死等 `agentId` 会漏掉只报 `agentKind` 的 leader。⑤ **"禁止第二份 busy 判定"会直接判红现有代码**——`healStaleBusySessions` 用裸字符串 `"busy"`/`"idle"` 并自己复制了一份阈值比较（没调 `isStaleBusy`），`InboxWorkspaceUnits` 另有私有 `AGENT_STATE_BUSY`；正确做法是先收编再新增。⑥ **折叠态不能塞进 feed 构建函数**——`orderInboxFeed` 是纯函数，折叠集合只能作为参数传入。
> **v6 校勘（相对 v5）**：**撤销 v5 的「agentNode 控制面未做 `agn1` 门控」误判**——`ControlPlaneAgentRoutes` 的每个 agent 路由第一行就是 `resolveAgentTarget(exchange, obj, CAP_AGENT_SESSION)`（或其能力组），该函数内部先查 `CAP_AGENT_SESSION` + `requiredCap`（不满足回 409 `peer-unsupported`），再做 `grants.allows(nodeId, agentId ?: agentKind, wsId, requireRw)` 授权过滤（不满足回 403 `not-granted`）。同时**关闭 v5 的两条 `[待核实]`**：`MsgTypes.MOBILE_CAPS` 已含 `CAP_AGENT_WORKSPACE`（手机端声明 `aws1`，`agentId` 会上线）；授权门控确认存在。剩余边界只剩"旧版本设备未声明 `aws1`"。
> **v7 校勘（相对 v6）**：落地时读代码发现 `ConversationSidebar` **内部复用** `ConversationListPanel`（把 `sessions` 映射成 feed 传进去），所以折叠**只有一处实现**、桌面只做参数透传 —— v6 写的「手机与桌面各接一次」把实现份数说多了，已按代码改正。另：组头标题避开 15sp（`titleSmall`），因为 15 不在 `m-groupcreate` 原型声明过的字号里，会让 `designScreenDiff` 从 13 组涨到 14 组；换 17sp 后复跑逐条一致。
> **v8 校勘（相对 v7）**：① **`VirtualAgentMember` 没有 `pluginId`**——线格式只有 `agentId`/`hostNodeId`/`name`/`entityType`/`teamMembers`/`allowedWorkspaces`/`defaultWsId`；组标识只能取 leader 的 `agentId`，凭空造 pluginId 会在两端口径上分叉。② 团队判定原先在 `ChatScreen` 与 `SetGroupAgentWorkspacesDialog` 各写一遍（`entityType == "team" || teamMembers.isNotEmpty()`），已收成 `VirtualAgentMember.isTeam` 一处。③ 状态匹配必须**同宿主**（`peerId == hostNodeId`）：v7 只写了大小写归一，漏了宿主维度，两台宿主各有一个 `coder` 时会互相点亮。

## 0. 结论与范围

**本轮只有两个真增量：Team Folder 折叠视图、活跃状态透出。** 其余能力（多棒责任链、异厂商门、验收门、五 CLI 驱动、单卡槽卡片、审批分级、Worktree 沙箱、帧预算、team 插件清单）在基线或代码中**已经存在**，本方案只沿用、不重写、不宣称升级。

**动手前必须先补四处契约**：

| # | 缺口 | 不补的后果 | 裁决 |
|:--|:--|:--|:--|
| C1 | 团队成员行的**会话键归属** | 新造 `team-*` 键没有投递落点，发送侧静默无帧 | §3.2：禁止新键，成员行只预填 `@<leader.name>` |
| C2 | 活跃状态的**数据源、聚合与门控** | 塞进群文本会污染主群；漏查 caps 会让老端静默丢弃并重传到入队 | §4.2：复用 `agent-session.state`，零新键，发前查 `agn1` + `aws1` |
| C3 | 首页派单的**工作区单一来源与落盘** | 画着 A 仓、派到 B 仓；重启后第一单落进别的仓 | §5：复用 `DispatchWorkspaceChoice.resolve` + 落盘 |
| C4 | 分组逻辑的**单一来源与落点** | 手机与桌面各写一份折叠，或为分组开第二条排序数据流 | §3.5：一个投影纯函数 + 一个组头组件，只接在共享容器 `ConversationListPanel`（桌面透传） |

**本轮明确不做**（理由见 §6）：首页常驻 `@` 胶囊 + 打字上浮面板、把组员做成可点进的独立会话、审批回执 HMAC 验签、生物识别快捷批准、动态派生子 Agent、Gradle/Cargo 验收门、扩 `GROUP_AGENT_STATES` 承载 Running、`agent-event` 新增状态 `eventType`、新增 `state` 值表达 Pending Approval、任何新表/新列/`.sqm`。

**裁决一览**：

| 裁决项 | 结论 | 落点 |
|:--|:--|:--|
| 状态通道选型 | 复用 `agent-session.state`（`BUSY` = Running），不扩群文本、不加 `agent-event` | §4.2 |
| 多会话聚合 | 该 agent 名下任一会话 `BUSY` 即 Running；全部落在 `ENDED` 才算非活跃；匹配按 `agentId ?: agentKind` | §4.2 |
| 发送门控 | 两个 cap 都要：`agn1`（收帧）+ `aws1`（才带 `agentId`） | §4.2 |
| 首页 `@` 胶囊 | **不做**，不推翻「彻底移除动态胶囊」的既定决策 | §6 |
| 折叠组聚合键 | `(convId, pluginId)`，同一 team 跨群**不合并** | §3.5 |
| 桌面四档折叠态 | 屏幕级单一状态，作为参数传给投影函数 | §3.5 |

## 1. 定位：不是两套并存，也不是换皮

`AGENTS.md §2.6` 的原文约束是**跨端同一 UI 元素共用一份实现**，判据是「内容 vs 外壳」，接缝优先参数化；它并不禁止"两套产品界面并存"。v1 把节标题、规则句、易错句拼成伪逐字引用，已撤销。

**四层分工（以代码为准）**：

1. **排序层**：`orderInboxFeed`（置顶 → 有未处理 → 最近活动）与 `orderConversationList`（查询词筛选 + 前者）。本轮**不动**。
2. **分组投影层（新增）**：一个纯函数，输入"已排序的会话序列 + team 映射 + 展开集合"，输出"带组头的行序列"。它只做结构折叠，不改顺序、不碰协议。
3. **渲染层**：`ConversationListPanel(feed: List<InboxFeedItem>)` 是**唯一实现**；桌面 `ConversationSidebar(sessions: List<ConversationUi>)` 内部把 sessions 映射成 feed 后调用它（不是第二份列表实现）。折叠只加在 `ConversationListPanel`，桌面只透传参数。
4. **外壳层**：`ConversationsScreen`；桌面 `ConversationsPane` 的四档 `when(sizeClass)`（四个 `ConversationSidebar` 调用点）。

Team Folder 是**现有会话列表的分组视图**，不新增会话类型、不新增列表容器、不新增第二套入口。

## 2. 相对基线的逐条对账

| 主题 | 现状（`[事实]`） | 本轮处置 | 状态 |
|:--|:--|:--|:--|
| 多棒责任链 | `DesktopStageChain` / `StageChainGate` / `TicketVerification` 已落地并有测试 | **沿用**，本轮零改动 | 现状 |
| 异厂商守卫 | `StageChainGate` 四条规则：冷却、在位、单棒降级、异厂商审核（KDoc 与代码分支的 2/3 编号顺序相反，引用时不编号）；`AgentProfiles.vendorOf` 是**厂商级 5 值**，`reviewers = plan.stages.drop(1)` 遍历全部审查棒，无模型级校验 | **沿用**；不写"相同厂商模型" | 现状 |
| 验收门 | `TicketVerification.testCommandFor` 只认 `package.json` 的 `scripts.test`（`"scripts"` 后 2000 字符窗口子串粗判，非 JSON 解析），执行 `npm test --silent`；`skipped` 也返回 `passed=true` | **沿用 + 记为已知限制** | 现状 / 已知缺口 |
| CLI 驱动 | `CliDiscovery` 五个硬编码 bin（`claude`/`codex`/`agy`/`opencode`/`dsh`）；`cliDriverRegistrations` 是**顶层函数**；`DriverEvent` 七型（`ThoughtDelta`/`TokenDelta`/`ToolCall`/`Card`/`Artifact`/`Finished`/`Error`），`CliLineEventMapper` 只产三型 | **沿用**；不称"自动发现"、不称"抹平为七型" | 现状 |
| 单卡槽卡片 | `(cardId, rev)` 严格递增；`AgentCards` 是追加式新行 + `latestRevCardMessageIds` UI 折叠；`CardStateTag` 映射 `aborted/blocked/prototype/merged/review_ready/working`，**无 `proposed` 专用档**（靠动作反推） | **沿用**；不新增卡片类型；不称"原位覆盖" | 现状 |
| 审批分级 | `AgentRiskLevels` 常量是 `READONLY`/`MODIFYING`/`HIGH`，**值域**才是 `L1/L2/L3`；hook 超时/送达失败回 `decision=deny`，**无在线对端时回 HTTP 503 `no-phone`**；hook 键 `"agy-" + conversationId` 与派单侧 **无对齐键**；`agent-ack` 无签名字段 | **沿用**；L3 终审属后续演进 | 现状 / 演进目标 |
| 沙箱 | `WorktreeSandboxExecutor` 的 `deleteArtifactsSafely()` 是 companion object 的 `internal fun`，`walkFileTree` 不 FOLLOW_LINKS；`SandboxIdleWatchdog` 在 **`DesktopWorkerWatchdog.kt`**，`DEFAULT_IDLE_TIMEOUT_MS = 180_000L`、`KILL_GRACE_MS = 5_000L`；进程树宽限由 `WorktreeSandboxExecutor.GRACE_MS = 3_000L` 决定；沙箱根可被 `TENON_SANDBOX_ROOT` 覆盖，产物 `artifacts/<taskId>/patch.diff`，链分支 `chain/<id>` | **沿用**；修正归属与 5s/3s 语义 | 现状 |
| 合流 | `DesktopPatchMerge` 只 `git apply --check` + `git apply`，不 add、不 commit 当前分支、不动 HEAD 与真实索引；成功后 `MergeBaseRef.snapshot` 用临时索引 `commit-tree` 写快照并 `update-ref refs/tenon/base` | **沿用**；不称"合并进入主分支"，也不称"不产生提交对象" | 现状 |
| 帧预算 | `Limits.CARD_BYTES = 24 KiB`（超限拒绝不截断）、`Limits.MAX_FRAME_BYTES = 64 KiB`；`CodecMsg.GROUP_TEXT_KEYS` 是 `msg` 帧白名单，`CodecGroup.validateGroup` 刻意没有白名单 | **沿用**；本轮不加键 | 现状 |
| 团队数据 | `GroupMeta.virtualAgents: List<VirtualAgentMember>`，组员在 `VirtualAgentMember.teamMembers`；`TeamMemberInfo` 正好 3 字段；KDoc 定死「**对外单一窗口**：全群只认 leader 的 token，组员不出现在群成员表，禁止向主群平铺中间碎片对话」 | **沿用**；修正字段层级 | 现状 |
| 插件清单 | `PluginManifest`（`type`/`leader`/`members`/`allowedWorkspaces`/`defaultWsId`/`capabilities`）；`PluginRegistration`（在 `PluginManifest.kt`）与 `PluginManifestJson`（`internal object`，在 `PluginHost.kt`）**两个入口都 `ignoreUnknownKeys = true`**；`duplicate-agent-id` 按 slug 归一后去重 | **沿用**；示例对齐 `examples/plugins/echo-team-plugin/manifest.json` | 现状 |
| 会话状态通道 | `AgentSessionStates` 六值（`IDLE`/`BUSY`/`DONE`/`ERROR`/`ARCHIVED`/`CLOSED`）+ `ALL` + `ENDED`；codec 层 `state` 只校验字符串（取值不封闭）、未知键走 `tolerantKeys`（**预算内忽略、超预算仍丢帧**）；**控制面入口按 `ALL` 校验，非白名单值回 `bad-request`**；`agentId` 键只在 `peerDeclares(CAP_AGENT_WORKSPACE)` 时才由 `sendAgentSession` 附带，而 `MOBILE_CAPS` 已含 `CAP_AGENT_WORKSPACE` | **沿用**（v2 曾误判"不存在"） | 现状 |
| busy 判定现状 | `[事实]` 有两处，且**都不是单一来源**：`InboxWorkspaceUnits` 有私有 `AGENT_STATE_BUSY = "busy"` + 公开 `isStaleBusy`/`STALE_BUSY_TIMEOUT_MS`；`RelayControllerAgentComponent.healStaleBusySessions` 用**裸字符串 `"busy"`/`"idle"`**，并**自己复制了一份** `(now - lastActivity) > timeoutMs` 比较（没调 `isStaleBusy`） | **先收编再新增**：`healStaleBusySessions` 改调 `isStaleBusy` + 用 `AgentSessionStates` 常量；本轮呼吸点不得再写第三份 | 阶段 0 |
| 跨端组件 | `ConversationRow(row: ConversationUi, ...)` 只有原语参数（含 `dense`），**无 content slot**；`ConversationListPanel(feed: List<InboxFeedItem>)`；`ConversationSidebar(sessions: List<ConversationUi>)` —— 两容器**不同形** | **修订**：分组走投影层，两端各加参数；**不改 `ConversationSidebar` 主参数类型** | 阶段一 |
| Team Folder 折叠视图 | 排序层与渲染层之间无"折叠投影"；无组头组件 | **新增（纯函数 + 共享组头）** | 阶段一 |
| 活跃状态透出 | 列表层无 Idle/Running token 与角标；`GROUP_AGENT_STATES` 只有四个执行结果态，1:1 文本协议无 `agentState` 键；`AgentSessionStates` 无 Pending Approval 值 | **新增（复用 `agent-session.state`，零协议改动）** | 阶段一 |
| 首页 `@` 胶囊 | `unified-chat-and-agent-interaction-architecture.md` 明确「摒弃/彻底移除动态胶囊」 | **不做（已裁决）** | 已裁决 |
| Schema | 基线 RFC §3.1 另有 `18.sqm` 演进线；本轮两个增量不需要 DDL | **不否定、不覆盖**；后续改列/加表按 `AGENTS.md §2.3` 必须同步 `.sqm` | 分线 |

> 基线互冲突处：LLD §四 的 `PluginManifest`（`id`/`description`/`capabilities` 四能力对象）与代码里的 `PluginManifest` 是**两套互斥形状**，逐字段对照即可见，不依赖未入库文档。本表以代码为准。

## 3. 增量 A：Team Folder 折叠视图

### 3.1 定位

在消息主屏（原型 `screenHomeRecent`，screen id `m-home-recent`）的**分组投影层**引入折叠：同一团队插件的成员收敛到一个组头下。折叠态占一行，展开态在组头下按缩进展示成员摘要。组头与成员都是**展示与快捷入口**，不是会话实体。

### 3.2 C1：会话键归属（硬约束）

`[事实]` `ConvId` 只认两种键：私聊 = `peerId` 本身，群 = `group:<groupId>`；会话标题解析（`conversations()` 内的局部 `convTitle`）对 `!isGroup(convId)` 走 `resolveNodeIdentity`；发送侧 `ConvId.groupIdOf` 对非群键恒 null。

`[推断]` 推理链：`team-<pluginId>` 无 `group:` 前缀 → 被判私聊 → 当作不存在的 nodeId 找对端 → 无目标 → **静默无帧**（`AGENTS.md §3` 最贵的一类缺陷）。因此：

1. **禁止**引入 `team-*` / `plugin-*` 形态的会话键，禁止把 `agent:<host>:<slug>` 当 `peerId`。
2. 组身份来源是该 team 所属的 `group:<groupId>`（数据源 `GroupMeta.virtualAgents[]`），一个群可挂多个 team。
3. 成员**不提供独立 1:1 会话入口**（`TeamMemberInfo` 仅展示与内部调度）。点击成员的唯一动作：打开所属群会话并预填 `@<leader.name>`——对外只有 leader 一个 token。`[事实]` 现在已接：壳提供草稿写入口（手机 `updateDraft(draftsByConv, …)` / 桌面 `onDraftChange`）时成员行可点；没有该槽的壳（预览）整行不可点，而不是点了没反应。
4. 展开成员只是本地 UI 展示，不改 `virtualAgents` 线格式、不进群成员表、不产生碎片对话。
5. 折叠态可放内存，但**不得承载"派单到哪个工作区"的选择**（见 §5）。

### 3.3 数据源与身份

- 清单：`PluginManifest`（`type="team"`，`leader` 必填）+ `PluginRegistration.parse`；去重按 `AgentSenderId.slugForSenderId`，重名报 `duplicate-agent-id`。
- `[事实]` 线格式里 `VirtualAgentMember` **没有 `pluginId`**（见 `GroupMeta`）：组标识只能取 leader 的 `agentId`；团队判定用 `VirtualAgentMember.isTeam`（`teamMembers` 非空即团队，`entityType` 只是声明）。
- 身份：`AgentSenderId.of(hostNodeId, agentId)` 产出 `agent:<hostNodeId>:<slug>`，`:` 分隔、agentId 段 lowercase、slug 只留小写字母数字与连字符。**不得**写成 `agent:host/team_member`。
- `[事实]` `of()` 会 lowercase，按 agentKey 的授权判定必须归一大小写，否则 `AgentGrants.scopeMatches` fail-closed，表现为"点了没反应、宿主零日志"。
- `[事实]` `ignoreUnknownKeys = true` 会静默吞键；两个入口改严格模式后未定义键会直接注册失败。manifest **不得**新增 `description` 等未定义键。

对齐后的清单（与仓内 `examples/plugins/echo-team-plugin/manifest.json` 同形）：

```json
{
  "pluginId": "devops-swarm",
  "name": "DevOps 智能协同团队",
  "version": "1.0.0",
  "type": "team",
  "entrypoint": "node dist/index.js",
  "leader": { "agentId": "butler", "name": "butler", "role": "Team Orchestrator" },
  "members": [
    { "agentId": "coder", "name": "coder", "role": "Engineer" },
    { "agentId": "reviewer", "name": "reviewer", "role": "QA Engineer" }
  ],
  "allowedWorkspaces": [{ "wsId": "primary-repo", "title": "主工程代码库" }],
  "defaultWsId": "primary-repo",
  "capabilities": ["task-dag", "progress-stream", "card"]
}
```

`capabilities` 是宿主 `listCapabilities` 读的字符串声明，不是 LLD §四 的四能力对象。

### 3.4 组件落点

- **组头组件写一次**：`TeamFolderHeader`（团队名 + 成员数 + 未读徽标 + 两态状态点 + 右侧折叠符号），手机与桌面都调它。**交互**：整行点击**进该群会话**（折叠态下这是唯一入口），右侧 `+`/`−` 是独立热区负责折叠，内层 `clickable` 不冒泡到整行 —— 与原型"点文件夹行进文件夹屏"同一直觉。
- **成员行不复用 `ConversationRow`**：`[事实]` `ConversationRow` 的第一个参数是 `row: ConversationUi`，而成员只有 `TeamMemberInfo(agentId, name, role)`——类型对不上，硬造 `ConversationUi` 会带进 `convId`/`pendingCount` 等假数据。成员行是组头内部的**轻量行**（头像 + 名字 + 角色 + 状态点），它不是"第二份会话行实现"，因为它不承担会话行的任何契约（点击、长按菜单、草稿、发送者署名）。
- **接线**：`ConversationListPanel` 加三个默认参数（`teamFoldersByConv`、`expandedTeams`、`onToggleTeam`）并在内部调 `foldTeamRows`；`ConversationSidebar` 只把同三个参数透传给前者，自己不折叠。
- 状态样式与 `AvatarPalette` 无关（它管头像底色与动物）：新增 Idle/Running **两态** token 与角标；Pending Approval 由审批卡片承担，不进呼吸点。原型 `pulse-dot` 是 **`@keyframes` 动画名**（用在 `.acard .hd .bdot.run`），不是可复用类/变量。
- 文案进 `composeResources/values/strings.xml` 与 `values-en/strings.xml` 成对新增，否则 `cjkLiteralGate` 判红。

### 3.5 C4：分组键、折叠态与落点（已裁决）

1. **聚合键 = `(convId, pluginId)`**。`[推断]` 推理链：组行点开必须落回某一个 `convId`；同一 team 注册到多个群时若按 `pluginId` 合并，一行对应两个会话 → 点击落错，与 C1 同型。故跨群不合并。`pluginId` 取 leader 的 `agentId`——线格式没有 pluginId（v8）。
2. **投影是纯函数**：输入"已排序的会话序列 + `Map<convId, TeamFolderUi>` + `expanded: Set<String>`"，输出"组头与行交替的渲染序列"；组头位置跟随其**代表会话**在已排序序列中的位置（默认取 leader 所在会话，缺失时取组内第一条），**不重新排序**。理由：`orderInboxFeed` 的排序键（置顶/待处理/最近活动）是既有纪律，投影层再排一次就会出现"两把尺子"。
3. **折叠态由屏幕级持有**，作为参数传入投影函数；`[事实]` `orderInboxFeed` 是纯函数，不能持有可变状态，所以折叠集合不能"放进 feed 构建层"，只能作为入参。
4. **一处实现 + 桌面透传，不改既有契约**：`InboxFeedItem`、`orderInboxFeed`、`orderConversationList` **全部不动**（`DesktopSidebarPinnedOrderGuardTest` 钉住桌面必须过 `orderConversationList`，动它会判红）；`ConversationSidebar` 的 `sessions: List<ConversationUi>` 主参数**不改**，只加可选参数（默认空，预览/截图夹具零变化）。桌面侧用 `sessions.map { InboxFeedItem.Conversation(it) }` 做形状适配，**不重排**，排序真源仍是 `orderConversationList`。
5. **否决的两条更重方案**：① 给 `InboxFeedItem` 加组分支——它要求实现 `key`/`timestamp`/`pendingCount`/`isPinned` 四个抽象属性，且会让 `ConversationSidebar` 必须改 public 签名（级联四个分档 + 预览 + 截图夹具）；② 桌面另开一条 feed 出口——那是第二条数据流，正是桌面置顶失效的历史成因。

## 4. 增量 B：活跃状态透出

### 4.1 C2：缺口精确化

`[事实]` 五条：

1. **群文本路径不适合承载 Running**：`GROUP_AGENT_STATES` 只有 `accept/blocked/completed/failed` 四个执行结果态；1:1 文本协议无 `agentState` 键；`broadcastAgentState` 要求群存在且自己是成员。
2. **跨节点会话状态通道已存在**：`agent-session.state`（六值 + `ENDED`）；codec 层取值不封闭、未知键预算内忽略；接收侧有陈旧 busy 自愈。
3. **但宽容只在 codec 层**：控制面入口按 `ALL` 白名单校验，非白名单值回 `bad-request`。新增 `state` 值的成本 = codec + 控制面 + UI 判定 + 协议事实文档多处。
4. **`agentId` 受 `aws1` 门控**：`sendAgentSession` 里 `val withWs = peerDeclares(peerId, CAP_AGENT_WORKSPACE)`，`agentId = if (withWs) agentId else null`。`[推断]` 推理链：呼吸点靠 `agentId` 关联 team → 若接收端只声明 `agn1` 没声明 `aws1`，帧里**没有** `agentId` → 聚合键为 null → 该团队永远显示 Idle，且没有任何报错。
5. **投递 caps 门控在调用方，且已在两处落实**：`sendAgentSession` 自身不查 caps。app 域 `handleSendAgentSession` 先查 `CAP_AGENT_SESSION`（未声明返回 null）；agentNode 控制面每个 agent 路由第一行就走 `resolveAgentTarget(exchange, obj, requiredCap)`——该函数内部先查 `CAP_AGENT_SESSION` + 该帧所属能力组（不满足回 409 `peer-unsupported`），再做 `grants.allows(nodeId, agentId ?: agentKind, wsId, requireRw)` 授权过滤（不满足回 403 `not-granted`）。`[事实]` 只有 orchestration 的 transfer 宣告（`DesktopExtension` 里 `origin="transferred"` 那一处）直接调 `runtime.sendAgentSession`，没有 caps 前置——它是收到 `agc1` 帧后的可见性宣告，既有路径、非本轮新增，但同样受"对端未声明 `agn1` 就静默丢弃"的约束。

`[推断]` 真正缺的是四件事：团队维度、多会话聚合规则、**两个 cap 的门控**、以及 busy 判定的单一来源（见 §2「busy 判定现状」）。

### 4.2 裁决：复用 `agent-session.state`（零协议改动）

| 项 | 裁决 | 依据 |
|:--|:--|:--|
| 数据源 | `AgentSessionStates.BUSY` = Running；`ENDED`（`DONE`/`ERROR`/`ARCHIVED`/`CLOSED`）= 非活跃；`IDLE` 等其余按非活跃。呼吸点只做两态，**只用 `AgentSessionStates` 常量**，不写 `"busy"` 字面量 | §4.1(2) |
| 多会话聚合 | 该 leader 名下**任一**会话 `BUSY` 即 Running；**全部**落在 `ENDED` 才算非活跃；无会话按非活跃。`[推断]` 用 `any` 避免一条刚结束的旧会话把正在跑的盖掉；台账只有 `countWorkspaces` 这类读数，没有"取最新态"入口，自造一个就是第二份归约 | §4.1(2) |
| 关联匹配 | 照 `countWorkspaces` 的口径 `(s.agentId ?: s.agentKind) == agentKey`，**必须归一大小写**。`[推断]` 死等 `agentId` 会漏掉只报 `agentKind` 的对端；这是既有代码里就有的回退，不新增 | §3.3 |
| 两个 cap | 发状态前必须同时满足 `peerDeclares(peerId, CAP_AGENT_SESSION)`（收帧）与 `peerDeclares(peerId, CAP_AGENT_WORKSPACE)`（帧里才有 `agentId`）。缺前者 = 对方收不到；缺后者 = 收到但关联不上。手机端 `MOBILE_CAPS` 已同时声明两者，无需额外配置 | §4.1(4) |
| 覆盖范围 | 新增状态发送**必须复用已门控入口**：agentNode 走 `resolveAgentTarget(requiredCap)`，app 走 `handleSendAgentSession` 式 `peerDeclares`；orchestration 若新增发送点须补同样前置（现有 transfer 宣告未门控，属既有路径） | §4.1(5) |
| Pending Approval | **不新增 `state` 值**，由现有 `agent-approval` 卡片（L1/L2/L3）表达。理由：控制面按 `ALL` 白名单拒收，加值要三处同改 | §4.1(3) |
| 超时降级 | 先**收编**：`healStaleBusySessions` 改调 `isStaleBusy` 并用 `AgentSessionStates` 常量，只保留 `STALE_BUSY_TIMEOUT_MS` 一个阈值；呼吸点复用收编后的结果，**不写第三份**。真机投递 120–125 s ≪ 30 min，不会误降级 | §2「busy 判定现状」 |
| 版本边界 | 本轮不加键、不加值、不加帧 ⇒ 不触发字段白名单整帧拒收；但**不是"无版本要求"**——未声明 `agn1` = 收不到，未声明 `aws1` = 收到但无 `agentId`，两者都要在验收写清 | §4.1(3)(4) |
| 明确否决 | ① 扩 `GROUP_AGENT_STATES` 走群文本：主群产生消息行，违反「禁止向主群平铺中间碎片对话」，且需群 + 成员资格、执行结果态与运行态混用；② `agent-event` 新增 `eventType`：需 `sessionId`/`cursor`，`shouldPersist` 默认 true ⇒ 逐条落库（KDoc 的 57,644 行 / 5.2 MB 是同型风险，非本场景实测），还得改 `EPHEMERAL` | §4.1(1)(2) |

`[事实]` 两条原疑问已关闭：① `MsgTypes.MOBILE_CAPS` 含 `CAP_AGENT_WORKSPACE`，手机端声明 `aws1` ⇒ `sendAgentSession` 会附带 `agentId`；② 授权门控确实存在——`resolveAgentTarget` 调 `grants.allows(nodeId, agentId ?: agentKind, wsId, requireRw)`，不放行回 403 `not-granted`，其 `agentKey` 回退口径与 §4.2 的聚合口径同源。**剩余边界**：旧版本设备可能未声明 `aws1`，此时帧里没有 `agentId`，按下面的回退走，不改协议。

`[推断]` **回退条件**：若 `agentId` 不上线或无法关联，本轮退化为「只在桌面/宿主本机显示状态角标，手机端不亮」，**不得临时改协议补键**——那会把"零协议改动"变成两端同版本硬门槛。

**产品限制如实写**：未声明 `agn1`/`aws1` 或未授权的设备看不到团队活跃状态；状态是会话级并向上聚合到 leader，成员不各自判活。

### 4.3 判活口径

`[事实]` `ProcessHandle.descendants()` 是快照且只对活着的祖先有效：根进程退出后后代过继给 init，`isAlive == false` ≠ 进程树没了。`Process.isAlive` 单判会给出假 Idle。本轮**不自造判活层**，状态源以声明该会话的一方（团队场景即 leader 所在宿主）的会话状态机为唯一 owner，本端只渲染不推断；将来若需进程级判活，沿用 `WorktreeSandboxExecutor.destroyProcessTree` 的「先记 pid 集合、再逐个核对」，且只留一处实现。v1 的 `TaskState.RUNNING` 在代码中无落点，已删除。

## 5. 派单路径：不新增写入入口

`[事实]` `ChatScreen.submitMessage` 与 `effectiveWsId` 都调 `DispatchWorkspaceChoice.resolve(remembered, available)`；界面态 `localActiveWsId` 优先，回落 `settingsSlice.settings.lastDispatchWsId`，写入经 `RelayController.rememberDispatchWorkspace` 落盘（变了才 `persist`）。

`[推断]` 首页发起的写入没有会话上下文 → 若另写 `?: available.first` → 显示与派单分叉；界面态不落盘 → App 被回收后回到第一个 → 重启后第一单落进别的仓（不可撤销副作用）。

因此：

1. 任何从组头或快捷入口发起的派单，**必须**复用 `DispatchWorkspaceChoice.resolve`，不得新增回退链。
2. 决定"往哪个目录写字"的界面态必须落盘；读取端校验 remembered 仍存在，解绑/换机一律回退，绝不照悬空 id 写。
3. 未绑定工作区时**禁止写入**，只生成 `state="proposed"` 提议卡，复用现有卡片渲染与 `HostCardActions` 的 `"start-task"`/`"cancel-task"` 两个 `actionId`。`[事实]` 两者是 `when` 分支里的**字面量比较，无 `const val`**；`proposed` **无专用 `CardStateTag` 档**（靠动作反推）⇒ 收益是"零新状态、零新文案"，**但**不能承诺"零像素"：没有独立的"提议中"视觉标识，需在验收说明。
4. `@团队/成员` 定向指派若要做，先过 `CognitiveFlow.classify` 的软/硬动词判据（`team:` 只是硬派单词之一），并复用 `DelegationParser` 的 `AUTO_PREFIX`（`^(?:组队|team)\s*[:：]\s*(.+)$`），不要新写会误吞提问的正则。

## 6. 明确不做 / 已裁决

| 项 | 处置 | 依据 |
|:--|:--|:--|
| 首页常驻 `@` 胶囊 + 打字上浮面板 | **不做（已裁决）** | 不推翻「彻底移除动态胶囊」既定决策；重开须单独立项 + 书面记录推翻理由 + 说明与 `AgentContextCapsule` 两级 BottomSheet 的差异 |
| 组员做成可点进的独立会话 | **不做** | `VirtualAgentMember` KDoc「对外单一窗口」+ §3.2 无会话键 |
| 扩 `GROUP_AGENT_STATES` 承载 Running | **不做（已裁决）** | §4.2 否决①：污染主群、语义混用 |
| `agent-event` 新增状态 `eventType` | **不做（已裁决）** | §4.2 否决②：默认落库，须改 `EPHEMERAL` |
| 新增 `state` 值表达 Pending Approval | **不做（已裁决）** | 控制面按 `ALL` 拒收，加值要三处同改 |
| 审批回执 HMAC / `[批准合并]` / 生物识别 | **不做** | `agent-ack` 无签名字段，HMAC 只用于 webhook 入站；全仓无 `BiometricPrompt`/`LAContext`；L3 终审前置是补 hook 对齐键 + 端到端钉子测试 |
| "跑真实 Gradle/Cargo / 100% Pass" | **不做，且不得再写** | `TicketVerification` 只跑 `npm test --silent`，`skipped` 也放行 |
| 动态派生子 Agent | **不做** | 后续周期 |
| 零 Schema 绝对化 | **改为分线表述** | 本轮不需要 DDL，但不否定 `18.sqm`；加 `.sqm` 等于放弃回滚 |
| "异厂商跨模型" | **改为厂商级** | `vendorOf` 是厂商级 5 值，不校验运行时模型 |
| 折叠组跨群去重 | **已裁决**：`(convId, pluginId)`，跨群不合并 | §3.5 |
| 折叠态归属 | **已裁决**：屏幕级持有、参数传入，不进纯函数 | §3.5 |
| 分组落点 | **已裁决**：投影纯函数 + 组头组件，只接在共享容器 `ConversationListPanel`（`ConversationSidebar` 透传）；不动 `InboxFeedItem`/`orderConversationList`/`ConversationSidebar` 主参数 | §3.5 |
| 状态帧选型 | **已裁决**：复用 `agent-session.state`，零协议改动 | §4.2 |
| 多会话聚合 | **已裁决**：任一 `BUSY` 即 Running，全 `ENDED` 才非活跃，匹配 `agentId ?: agentKind` | §4.2 |
| 发送前 caps | **已裁决**：`agn1` + `aws1` 两个都要，且复用已门控入口（agentNode `resolveAgentTarget`、app `peerDeclares`） | §4.2 |

## 7. 落地顺序与验收

**阶段 0（契约与收编，先于 UI）**：

1. **C1**：会话键只许 `peerId` / `group:<groupId>`；源码扫描守卫禁止 `team-*` / `plugin-*` 字面量进入 `convId`；成员不持有独立 `convId`。
2. **C2 收编**：`healStaleBusySessions` 改调 `isStaleBusy` 并改用 `AgentSessionStates` 常量，消除裸 `"busy"`/`"idle"` 与复制阈值比较；`STALE_BUSY_TIMEOUT_MS` 单一来源。收编完成后，再新增呼吸点且**不得出现第三份 busy 判定**（守卫按"新增行数"棘轮，不按"是否已有多份"判红）。
3. **C2 门控**：新增状态发送必须走已门控入口——agentNode 侧 `resolveAgentTarget(exchange, obj, CAP_AGENT_SESSION)`，app 侧 `handleSendAgentSession` 式 `peerDeclares`；若在 orchestration 新增发送点，必须补同样前置（现有 transfer 宣告未门控，属既有路径）。守卫断言"新增的 `sendAgentSession` 调用点上方存在门控"，不要求改造既有两处已门控通路。
4. **C3**：派单只经 `DispatchWorkspaceChoice.resolve`，写入前校验 remembered 仍存在。
5. **C4**：投影纯函数落在一个共享位置（手机 `ConversationsScreen` 与桌面 `ConversationSidebar` 都能调）；守卫断言**两端渲染循环都调用它**，且 `InboxFeedItem` / `orderInboxFeed` / `orderConversationList` 未被修改、`ConversationSidebar` 的 `sessions` 参数类型未变。
6. 前置确认：`aws1` 已在 `MOBILE_CAPS`，无需再探测；只需在真机抓一帧确认 `agentId` 非空（旧版本设备可能未声明 `aws1`），为空则按 §4.2 回退，不补协议键。

任何一条没有守卫，不进入阶段一。

**阶段 1（UI）**：投影纯函数 + `TeamFolderHeader` → `ConversationListPanel` 接投影（`ConversationSidebar` 只透传） → 状态角标（读 `BUSY` / `ENDED`） → **参考帧先行，原型折叠态延期**（见 §7.1「C4 参考帧」的延期条件）。不得加 `@` 胶囊、不得改 `ConversationRow` 参数形状。

**阶段 2（可选）**：`@团队/成员` 定向指派，前置是解析器复用与 `CognitiveFlow` 判据核验。

**验收限制**：未声明 `agn1`/`aws1` 或未授权设备不显示 Running；状态聚合到 leader，成员不各自判活；提议卡无独立"提议中"视觉标识。三条写进验收说明，不判缺陷。

**门禁（缺一不可）**：`AGENTS.md §1` 十条通用门（含 `cjkLiteralGate`、`constructionTraceGate`、`staleCommentRefGate`、`domainComposeFreeGate`、`envDependentTestGate`、`jlinkModulesGate`）、`tools/unused-imports.py --check`、`tools/check-design-palette.py --css-vars`；七个设计门 `designScreenDiffGate`、`designIconGate`、`designConvergeGate`、`designFormPairingGate`、`designPreviewPairingGate`、`designDupFrameGate`、`designDpDebtGate`，以及 `designSpecDrift`、`designTokenUsageGate`、`designStateSlotGate`、`designLocaleParityGate`；八个模块测试按 `AGENTS.md §1` 串行复核。

**改 `prototype/relay.html` 必带三件**：全量重拍刷新 `shots-manifest.json` 的 `source_sha`；带理由重投影 `design-screen-diff-baseline.json` 并写清改动范围；跑 `check-contrast.mjs`。另跑 `check-relay.sh`、`check-render.mjs`、`check-refs.py`。**为让门禁变绿而重刷基线是本仓明确禁止的**（`AGENTS.md §2.1`）；重投影前确认 HEAD 上已红的差异组不是别人的改动。

### 7.1 落地进度（分支 `feat/team-folder-phase0`，基于 HEAD；不改变本版裁决）

| 契约 | 状态 | 落地物 |
|:--|:--|:--|
| C1 | 已落地 | `ConvIdContractTest`（4 项）：两种键形态、`team-*`/`plugin-*` 不是群键、groupId 可含冒号（`removePrefix` 而非 `split`） |
| C2 收编 | 已落地 | `healStaleBusySessions` 改调 `isStaleBusy` + `AgentSessionStates`；`InboxWorkspaceUnits` 删除私有 `AGENT_STATE_BUSY`；`StaleBusySingleSourceTest`（7 项，含 4 个行为等价钉子） |
| C2 门控 | 已落地 | `AgentSessionSendGateTest`（2 项）：已门控入口必须保留门控；`ControlPlaneHookRoutes` / `DesktopExtension` 两处既有未门控点按数量基线只许降不许升 |
| C3 | 已覆盖（无需新增） | `DispatchWorkspaceChoice.kt` 已入库；守卫 `DispatchWorkspaceMemoryWiringTest`（4 项：落盘、设置字段、药丸与派单同一回退链）已在 main 上，含三条反向探针。**不重复造** |
| C4 | 已落地（含接线） | `foldTeamRows` + `TeamFolderProjectionTest`（8 项）；`TeamFolderRows` + `ConversationListPanel` 三分支 + 侧栏透传 + `TeamFolderWiringGuardTest`（4 项）；`designScreenDiff` 差异组数与改动前一致（13 组存量） |
| C4 数据侧 | 已落地 | `teamFoldersByConv`（域层纯函数）+ `TeamFoldersTest`（11 项）：同宿主匹配、agentKey 归一、任一 BUSY 即 Running、无会话即 Idle、多团队保序 |
| C4 成员 | 已落地 | 成员行点击 → 写 `@<leader> ` 草稿 + 打开群会话；没有 `onDraftChange` 的壳整行不可点；`TeamFolderWiringGuardTest` 断言链路完整 |
| C4 交互 | 已落地 | 整行点击 = 进群会话，右侧符号 = 折叠（独立热区、不冒泡）；`TeamFolderWiringGuardTest` 断言两个入口都在 |
| C4 参考帧 | 已落地 | 两帧 Compose 参考图 `chat-home-team-folder` / `-expanded`（`ScreenshotPreviewsChat.kt`）+ `initialExpandedTeamConvs` 截图专用接缝（与 `initialFilter` 同一条先例，生产调用方一律默认折叠）；`TeamFolderWiringGuardTest` 断言两帧与接缝都在 |
| C4 未读承接 | **已被形态取代** | "组头替代会话行"本身被推翻（丢预览/时间），会话行内嵌后未读天然跟着行走，`FeedRow.Folder.unread` 补丁已删除。教训保留：**列表的基本单位不能被另一种行型替代** |
| C4 原型 | 已落地（2026-11-09） | 新屏 `m-home-team-folder-open`（展开态；折叠态 = `m01-home` 本身）；117 帧全量重拍；台账已登记（落点点名 `TeamFolderRows.kt`）；基线带理由重投影 **408 组**（原型 sha `08e48f43 → b013591a`）。解锁条件因 main 侧重投影而提前满足 |
| C4 外壳 | 已落地 | 手机 `ConversationsScreen` 与桌面 `ConversationsPane` 各自 `remember` 计算并持有 `expandedTeams`；桌面四档都透传（守卫按 4 处计数）；容器 `ConversationListPanel` 保持无状态 |

验证：`:core:jvmTest` / `:app:jvmTest` / `:composeApp:jvmTest` 按 `AGENTS.md §1` 串行 `--max-workers=1` 全绿；`cjkLiteralGate` / `constructionTraceGate` / `staleCommentRefGate` / `domainComposeFreeGate` / `envDependentTestGate` 全绿；`tools/unused-imports.py --check` 干净。**未跑** `testStabilityGate` 与七个设计门——本轮没动并发、存储、设计 token、原型与截图夹具。

### 7.2 原型折叠态的解锁清单（等 13 组清零后照此执行）

为什么不在本轮做，三条约束是叠乘关系，任何一条单独看都能过：

1. `[事实]` 改 `prototype/relay.html` ⇒ `prototype/shoot-relay.py` 会逐帧比对 `source_sha`（`ent.get("source_sha") != cur_sha` 直接报错），`prototype/shots-manifest.json` 的 116 帧全部失效、必须全量重拍。
2. `[事实]` 原型的一屏 = `SCREENS` 派发表一行 + 一个 `screen*()` 函数；状态变体是**独立 screen id**（`m-home-more` / `m-home-unread` 的先例）。折叠态按惯例要开新屏。
3. `[事实]` `design-screen-diff` 是**双向**棘轮（`signature()` 的键是 `{屏}|{维度}|{侧}`，`ratchet()` 两侧都只许变小），新屏必然添新键；而唯一的解 `write_baseline()` 写的是**当前全量签名**，会把 HEAD 上 13 组别人的红一并接管 —— 这正是「为了让门禁变绿而重刷基线」。

解锁后按序执行（缺一步就会在下一轮被门禁打回）：

1. 前置：`python3 tools/design-screen-diff.py --root . --all --check` 必须报 **0 组**。
2. 加屏：`SCREENS` 增 `{id:'m-home-team-folder', form:'mobile', label:'消息·团队折叠', tab:'conversations'}` + `screenHomeTeamFolder()`；演示数据里加一个团队群（leader + 3 组员）。
3. 台账新行：**代码落点必须点名 `feature/conversations/TeamFolderRows.kt`**。`[事实]` 代码侧取值是「落点文件 + 一跳 `<Name>View(` 跟进」，而 `TeamFolderHeader` 不匹配该命名，不点名就量不到组头。
4. 重拍：`python3 prototype/shoot-relay.py`（117 帧，自带 verify 刷 `source_sha`）。
5. 对比度：`node prototype/check-contrast.mjs --theme both`。
6. 配对：新屏要有预览帧；本分支已有的 `chat-home-team-folder` / `-expanded` 两帧可直接复用（注意**闸门参数要传齐**——配对门只对"没传的可空参数"记棘轮键）。
7. 重投影：`design-screen-diff.py --baseline --reason "新增 m-home-team-folder 折叠态"`，此时 reason 只写这一件，13 组一笔都不许进。

**顺带记录一条本地事实**：`[事实]` `:androidApp:validateDebugScreenshotTest` 在本仓本地**恒红**（315 条全部 `ScreenshotImageNotFoundException`）——参考图没有入库，不是改动引起的。要看图用 `updateDebugScreenshotTest` 渲染到 `build/`，别把它当回归。

**执行结果（2026-11-09）**：清单已走完，两处与预想不同，记录给后来人——
1. "13 组清零"不是被修掉的，是 main 侧**重投影了基线**（403 组）——门绿了但欠账被记录而非清偿；
   本轮重投影（408 组）因此不会再吞任何人的红。
2. `check-contrast` 在 main 上**本来就是红的**（156 处 3.83:1，全在代码 diff 视图 `div.df`）——
   用 `git stash push -- prototype/relay.html` 切两版对照确认本轮零新增；它的清偿是独立欠账，
   不在本清单内。

### 7.3 团队创建（声明）落地进度（`feat/team-folder-phase0`，方案 `docs/team-creation-final-plan.md` §7）

先决项四条与功能两条均已落地（v1 只做**声明与展示**；`@组员` 派单/审查闭环/多模型在 v2，见 `team-execution-final-plan.md`）：

| 块 | 内容 | 落地物 |
|:--|:--|:--|
| P0-0.1 值域与声明构造收口 | `EntityTypes` 单一定义；解码未知值归一；三条声明构造路径收口 `declarationFor`（团队字段从 previous 原样带回，既有 @ token 不漂）；`representativeSeat`/`seatsOfAgent` 代表席位单判据（行显示名、撤回按钮、对话框默认选中三处共用） | `core/.../EntityTypes.kt`、`GroupMeta.declarationFor`、`DeclaredAgentLookup`；守卫 `VirtualAgentDeclarationGuardTest`（反向验证过） |
| P0-0.2 合并主权 | 普通成员只改写 `hostNodeId == 自己` 的声明可过收端权限矩阵（外来声明规范形多重集合比较、其余字段逐字段一致）；**旧端仍拒收成员 rev+1**（兼容性代价见 PROTOCOL-EXTENSION §14.5） | `GroupMerge.memberMayRedeclareSelf` + 测试矩阵（own 增/改/删、foreign 动不得、null/空归一、顺序不敏感；反向验证 4 条变红） |
| P0-0.3 收端校验与预算 | `validateGroup` 校验 `virtualAgents` 形状/条数/长度/组员预算，越界拒整帧；发送侧 `validateGroupMeta` 同判据；`TeamRosterValidation` core 纯函数（token 池冲突域、结构化问题）；最坏合法帧 42.4 KiB < 64 KiB | `CodecGroup`、`Limits` 四常量、`TeamRosterValidation` + 三个测试文件（上限调大 → 帧预算测试红） |
| P0-0.4 投影 key | `TeamFolderUi.pluginId` 宿主限定 `"<host>:<agentId>"`——跨宿主同 agentId 不再撞折叠行 key | `TeamFolders.kt` + `TeamFoldersTest` |
| P0-1 写路径 | `commitGroupMetaUpdate` 提交收口（发送侧预算自检→落库→need 游标→广播）；`handleSetGroupTeamDeclaration`（定位键 `(groupId, defaultWsId)`，关闭=同一函数反向写，同 agent 其它席位清成单兵）；补发台账补 `hasTeams` 维 | `RelayControllerGroupsComponent` + `TeamDeclarationWritePathTest`（14 条，含双节点端到端：成员声明 → 群主 apply；动他人声明 → 拒） |
| P0-2 UI | 群信息页「团队编制」按钮 +「团队 (N)」徽标；`TeamRosterDialog` 两端共用一份（席位 chip、预设、快捷角色、编辑保全、就地校验、Zero-Workspace Guard、手机壳宿主注解）；三壳接线同挂 | `ui/components/TeamRosterDialog.kt` + 守卫 `TeamRosterWiringGuardTest`（住 :composeApp guards 包）+ 双语 strings + design-spec 条目 + 两帧预览 |

验收判据见方案 §8；真机验法与混合版本限制记在 `ACCEPTANCE.md` §10e。

## 8. 文档状态与引用

- **裁决：保留本文为独立增量差异表，不并入基线**。理由：本文价值是跨三份基线的差异对账，并入任何一份都会丢掉「谁与谁冲突」的上下文。
- **入库动作（评审通过后）**：本文当前 **untracked**。需 `git add` 本文并在 `AGENTS.md §9` 加一行索引；否则 `AGENTS.md §6`「引用证据文件先确认它真的在版本库里」仍不满足。本轮不改 `AGENTS.md`。
- **断链处置**：`AGENT-TRIGGER-AND-PLUGIN-SPECIFICATION.md` 当前 untracked ⇒ 其引用已降级为**工作稿参考**，权威引用改指已入库的 `docs/PROTOCOL-AGENT-WORKSPACE.md` 与 `docs/lan-team-agent-im-detailed-design.md`。
- `docs/` 下已有多份同主题文档（`lan-team-agent-im-architecture.md`、`lan-team-agent-im-detailed-design.md`、`DYNAMIC-MULTI-AGENT-TEAM-ORCHESTRATION.md`、`unified-chat-and-agent-interaction-architecture.md`、`tenon-architecture-and-agent-extensibility-blueprint.md` 等）。本文只读增量差异。
- **撤销的断言台账**：v1 的伪逐字引用 `AGENTS.md §2.6`、`ConversationEntity`、`GroupMeta.teamMembers`、`team-<pluginId>` 会话键、`PluginManifest.validate()`、manifest 的 `description`、`agent:node-…/…` 身份形态、`WorktreeSandbox.kt` 行号引用、HMAC 验签、生物识别、Gradle/Cargo 验收与 100% Pass、"合并进入主分支"、卡片"原位覆盖"、5s SIGTERM→SIGKILL、Mapper 抹平七型、异厂商"模型"级、零 Schema 绝对化；v2 的「没有任何跨节点状态通道」与「必须动白名单 + 两端同版本」；v3 的「`ConversationListPanel` 负责分组」「`AgentSessionStates` 5 值」「`state` 取值不封闭（未区分 codec 与控制面）」「`sendAgentSession` 自带 caps 门控」「`orderConversationList` 能透出组分支」「`ConversationProjections.convTitle`」「`DesktopConversationWidgets`/`DesktopConversationsPane` 是符号」「`AgentRiskLevels.L1`」「`DesktopPatchMerge` 完全不产生提交」「`CliSubprocessDriver.cliDriverRegistrations` 是成员」「`proposed` 有专用状态胶囊」；v4 的「升级 `orderConversationList` 返回类型即可实现 C4」「成员行复用 `ConversationRow`」「只需 `agn1` 一个 cap」「按 `agentId` 严格匹配」「禁止第二份 busy 判定（未先收编）」「折叠态放进 feed 构建层」；v5 的「agentNode 控制面未做 `agn1` 门控」（实际经 `resolveAgentTarget` 门控 + 403 授权过滤）与「`aws1` 是否声明属待核实」（`MOBILE_CAPS` 已含）；v6 的「手机与桌面各接一次投影」——实际桌面复用 `ConversationListPanel`，只有一处实现；v7 的「组标识取 `PluginManifest.pluginId`」（线格式里没有该字段，实际取 leader 的 `agentId`）；v8 的「组头整行点击 = 展开/收起」（已改成整行进会话、右侧符号折叠，与原型"点文件夹行进文件夹屏"一致）。
