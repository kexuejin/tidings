# 多席位 / 多群 / 会话工作区：一致性修正清单

> **状态**：待评审（v1；§4 的 4 项开放问题已裁决，见 §4 裁决记录）。**上游裁决**：`docs/project-directory-and-execution-site-model.md`（D1–D16；本文 C1–C5 由该文 P4 执行）。
> **上游**：`docs/tenon-agent-hub-spec-review.md` 附录 C（工作区归属三层模型）、
> `docs/team-creation-final-plan.md`（席位 = `(groupId, hostNodeId, agentId, defaultWsId)`）、
> `docs/team-execution-final-plan.md`（执行配置是宿主本地事实）。
> **一句话**：三层绑定（定义不绑 / 席位绑默认 / 会话带 `wsId`）本身不冲突，但缺三把键——
> **会话缺群维度、免批缺工作区维度、短 token 缺歧义判据**；本清单给每条的最小改动面与反向验收。
> **边界**：不改 `group.info` 任何键、不动 `caps`、不动 `team-execution.json` schema、不加 `.sqm`；
> F1–F3、F5 零协议零存储；F4 按裁决直接上硬保证（`sessionId → convId` 落宿主本地台账，零协议）。
> **全部不留旧数据兼容分支**：开发期清库重配（决策文 §5.3），旧规则/旧会话随清库消失。
> **证据口径**：只写符号名 + 文件路径，不写行号（`AGENTS.md §2.1` 的 `staleCommentRefGate` 口径）。

---

## 0. 结论

| 项 | 冲突 | 判定 | 档位 |
|---|---|---|---|
| C1 | 同一 agent 多席位时，短 token `@dsh` 被静默解析成第一个席位 | **真冲突**（宿主侧 first-match；发送侧其实已 fail-closed） | 判据层，零协议 |
| C2 | 「群→工作区」有两条推导：`ChatScreen`/桌面/建群对话框用群名/简介**子串反猜**，`workspaceOfGroup` 用席位声明 | **真冲突**（显示与执行可能各说各话） | 显示层，零协议 |
| C3 | 会话/记忆没有群维度（`SessionRecord` 只有 nodeId/sessionId/wsId） | **真冲突**（同 agent+同 ws 在两群会共用 CLI 会话） | 硬保证：宿主本地台账（裁决：不做「尽力而为 + 放行」档） |
| C4 | 显式点选会话不校验 `wsId`（`AgentSessionPick.pick` 的 requested 分支） | **真冲突**（可跨工作区 override，静默） | 判据层，零协议 |
| C5 | `always` 免批键 = `nodeId + agentKey + tool`，无工作区/群维度 | **真冲突**（项目 A 的「始终允许」在项目 B 生效） | 宿主本地表，零协议 |

**不是冲突、但要知道的结构约束**：同一 `(hostNodeId, agentId)` 至多一条团队（`CodecGroup.validateGroup` 的 `teamSeatKeys` 已拦）；同一 agent 可多席位，但**不能**多席位各配一支团队。`teamFoldersByConv` 的 `pluginId` 已用 `host:agentId` 宿主限定，渲染 key 安全。

---

## 1. 逐项修正

### F1（对应 C1）：宿主侧对多席位短 token fail-closed

**现状（两个判据不一致）**

- 发送侧 `resolvePromptTarget` 已经是 fail-closed：token 命中 ≥2 个席位 ⇒ `PromptTarget.Ambiguous`，并发 `NoticeKind.TeamMentionAmbiguous`（`AgentPromptWiringGuardTest` 钉死该 notice 只能有一处）。
- 宿主侧 `resolveAgentInboxHint`（`AgentInboxEnricher`）却用 `GroupMeta.mentionedVirtualAgent`，实现是 **`firstOrNull`**；`tokens` 又把每席的 `name` 与 `agentId` 都放进池（`agentId` 在多席位间相同）。⇒ `@dsh` 会静默投到第一个席位的 `defaultWsId`。

**目标行为**

- 宿主侧与发送侧同一判据：命中的 token 若映射到 >1 个席位 ⇒ `agentHint = null`（不投），不挑第一个。
- `@<全名>` 仍然精确命中；`@agentId` 只在**唯一**席位时有效。
- 发送侧提示沿用既有 `TeamMentionAmbiguous`（**不要在同一发送侧文件新增第二处**——`AgentPromptWiringGuardTest` 断言该文件里恰好 1 处；宿主侧只改判定、不出 notice）。
- **UI 配套（已裁决）**：多席位群的 `@` 候选只显示全名，不再显示裸 `agentId`；`@agentId` 判据不变（仅唯一席位时有效）。

**改动点**

| 文件/符号 | 改什么 |
|---|---|
| `core/.../GroupMeta.kt` 的 `mentionedVirtualAgent` | 命中后调 `seatsMatchingToken(hit)`；`size > 1` 返回 null（或新增 `mentionedSeatStrict` 纯函数，两个 call site 共用） |
| `core/.../TeamMention.kt` 的 `seatsMatchingToken` | 已是现成单源判据，直接复用，不另写匹配 |
| `agentNode/.../AgentInboxEnricher.kt` | 调用改为严格版；`agentHint = null` 即「不投」 |
| `app/.../RelayController.unathorizedAgentMention` | 同步：多席位时不再按 first-match 报「没权限」（避免归属错人） |

**反向验证**：构造两席位同 `agentId` 的群，`@dsh` ⇒ `resolveAgentInboxHint` 返回 null / 无 `targetSessionId`；把 `firstOrNull` 放回去 ⇒ 测试红。
**验收**：真机两席位群 `@dsh` ⇒ 发送侧出「一次只 @ 一个目标」，宿主不执行；`@dsh-<ws>` 正常执行。

---

### F2（对应 C2）：删掉「群→工作区」的子串反猜

**现状（四处同型启发式）**

- `ChatScreen`：`pathMatch = desc.contains(wsPath)` / `titleMatch = group.name.contains(wsTitle)`，得到 `matchedWs` → `effectiveWsId`。
- `DesktopShell`、`App.kt`、`WorkspaceGroupCreateDialog`：同样的 `g.description.contains(wsPath) || g.name.contains(wsTitle)`，用于「工作区反查绑定群 / 建群提示」。
- 与之并存的权威推导是 `workspaceOfGroup` = 各席位 `defaultWsId` 的并集里取第一个匹配。

**目标行为**

- 群→工作区只有一个来源：**席位声明**（`workspaceOfGroup`）；没有声明就显示「未绑定工作区」空态，**不猜**。
- 建群/反查同样走声明，不读 `description`/`name` 文案。

**改动点**：四个文件把启发式替换为 `workspaceOfGroup`（`app/.../ConversationProjections.kt`）或 `GroupMeta.virtualAgents[].defaultWsId`；`ChatScreen` 的 `effectiveWsId` 群分支直接取 `workspaceOfGroup(...)?.wsId`；删除对应注释里的「反猜」措辞。
**风险**：老群（历史版本建的、未写席位声明）会从「猜出一个」变成「未绑定」——这是**诚实**方向，但需要空态文案与一次真机回归。
**反向验证**：构造「简介写 A 路径、席位声明 B」的群 ⇒ 药丸显示 B、派单也 B；把启发式放回去 ⇒ 断言红。
**验收**：真机老群（无声明）显示未绑定且不派错仓；有声明群显示声明仓。

---

### F3（对应 C5）：`always` 免批加工作区维度

**现状**：`AgentToolApprovalStore.Rule` 键 = `(nodeId, agentKey, tool)`；`isAlwaysApproved` 的两个调用点（`ControlPlaneHookRoutes` 的 hook 审批、`ControlPlaneApprovalRoutes` 的 `/v1/agent/approval` 入口）都没有 ws 参数。⇒ 项目 A 里点「始终允许 `rm`」，项目 B 同一 agent 同一工具静默放行；多席位共享 `agentKey`，席位也挡不住。

**裁决（已定：更严档，无兼容分支）**

- `Rule` 键改为 `(nodeId, agentKey, wsId, tool)`；`isAlwaysApproved(nodeId, agentKey, wsId, tool)`；`remember` 由 `ControlPlane.init` 的 `approvalPolicy` 从 `ApprovalRecord.sessionId` 反查 `sessions.sessionOrNull(...)?.wsId`；两个调用点（`ControlPlaneHookRoutes` 的 hook 审批、`ControlPlaneApprovalRoutes` 的 `/v1/agent/approval` 入口）补传 session ws。
- **null 只命中 null**：授予来源会话无 ws 时，规则记 `wsId = null`，只命中同样无 ws 的派单——不存在「null = 全部工作区」的分支。这正是「更严档」的准确含义。
- **不留「旧规则按全工作区命中」的兼容分支**：存量三参旧规则随开发期清库重配消失（决策文 §5.3），不写迁移、不做双读；升级后用户看到的就是「逐工作区重新授权」。
- 零协议；改动面：宿主本地表 + 三处调用点。

**反向验证**：在 ws A 记 `bash` ⇒ ws B 仍询问、ws A 不询问；把键退回三参（无 ws 维度）⇒ 测试红。

---

### F4（对应 C3）：会话候选加群维度

**现状**：`AgentSessionRegistry.SessionRecord` 无 `groupId`；`AgentSessionPick` 只按 `boundWsId` 过滤。同一 agent + 同一 ws 在两个群都有席位时，`ControlPlaneInboxRoutes` 的候选会话是全 agent 的，pick 挑「该 ws 下最近活跃」⇒ 两个群共用同一条 CLI 会话与上下文。

**目标行为**：派单候选会话必须属于**本群**；归属未知 ⇒ 不进候选（fail-closed，不回退现状的全 agent 候选）。

**裁决（已定：直接上硬保证，不做「归属未知放行」档）**

- `sessionId → convId` 落**宿主本地台账**（与 `team-execution.json` 同套落盘纪律：写锁 + mtime + 0600 + 原子 rename；零协议、零 `.sqm`）。派单时 `bindConversation` 写入，**重启后仍在**——这是它与现有内存映射（有界、会淘汰、重启即失）的本质区别。
- `ControlPlaneInboxRoutes` 构造 `AgentSessionPick.Candidate` 处按台账过滤：候选必须是 `== ConvId.group(groupId)` 的会话；台账无条目（归属未知）⇒ 不进候选。
- 1:1 与控制台会话天然无群归属，不受影响（它们本就不走群派单候选）。
- 台账读失败 = 空（候选为空），不猜、不派单（与 `AgentToolApprovalStore` 同纪律）。

**反向验证**：两群同 agent 同 ws，A 群派单后 B 群派单不选 A 的 session；删掉台账条目（模拟异常）⇒ 该会话不进候选且原因可读；把「全 agent 候选」放回去 ⇒ 测试红。

---

### F5（对应 C4）：显式点选会话的 ws 校验

**现状**：`AgentSessionPick.pick` 的 requested 分支只在候选里找、**不校验 `wsId`** ⇒ 点着某条 agent 会话回复时，可以把不匹配席位绑定的会话变成执行目标；而默认路径是严格「不跨工作区借用」。同一套判据两个答案。

**目标行为（已裁决：收紧档）**

- **收紧（采纳）**：`Result` 新增 `RequestedSessionOutsideWorkspace`；`AgentInboxEnricher` 把 `targetSessionId` 置 null，并给出可读原因（「你点的会话属于另一个工作区」）。**不静默换投**。
  与默认路径「不跨工作区借用」收敛为同一套判据（fail-closed，与 F3/F4 同一取向）。注意这不是兼容问题：清库重配后问题照样存在，根因是 requested 分支漏校验 `wsId`。
- **保留 override（未采纳）**：允许跨 ws 但必须显式标注——它让同一判据保留两个答案，且「群里 @ 执行到非默认仓」正是本清单要消灭的形态。将来真有跨仓执行需求，按决策文 §10 口径单独立项。

**改动点**：`core/.../AgentSessionPick.kt`（新结果）→ `AgentInboxEnricher`（映射）→ `agentHint` JSON（新增原因字段，桥接侧消费）→ 测试 `AgentSessionPickTest`、`AgentInboxEnricherTest`。
**兼容**：收紧方向对旧端只会「不投」，不会更糟。
**反向验证**：点选别 ws 会话 ⇒ 不执行且原因可见；点选同 ws ⇒ 正常执行。

---

### F6（附）：多席位 ≠ 多团队（不写代码）

校验层已拦（同 `(host, agentId)` 至多一条团队）；写路径保存某席位团队时会清掉同 agent 其它席位的团队字段。**要做的是**：团队编制对话框在「同一 agent 有多席位」时明确提示「团队只能挂在其中一个席位」；这条写进验收脚本，避免用户以为保存失败是 bug。

---

## 2. 实施顺序

| 批次 | 内容 | 协议/存储 | 测试集 |
|---|---|---|---|
| **P1** | F1（严格 mention）+ F2（删启发式） | 零 | `:core:jvmTest`、`:agentNode:test`、`:app:jvmTest`、`:composeApp:jvmTest` |
| **P2** | F3（免批键加 wsId，无兼容分支） | 宿主本地表 | `:agentNode:test`、`:app:jvmTest`、桌面设置页文案 |
| **P3** | F5（点选会话校验，收紧档）+ F4 判据（候选限本群，fail-closed） | 零 | `:core:jvmTest`、`:agentNode:test` |
| **P4** | F4 持久化（`sessionId → convId` 宿主本地台账） | 本地档案（零 `.sqm`） | `:agentNode:test`、`testStabilityGate`（若动异步） |
| **P5** | F6 提示文案 | 零 | 文案 + 真机 |

**每一条的提交纪律**（`AGENTS.md §4/§7`）：测试与反向验证写进同一条提交；提交信息写清「旧端/旧数据会怎样」。

---

## 3. 门禁与真机验收

- `cjkLiteralGate`：新增文案（歧义提示、会话归属原因、候选全名）必须进 `values*/strings.xml`；`designLocaleParityGate` 同步 `values-en`。
- `AgentPromptWiringGuardTest`：**不新增** `TeamMentionAmbiguous` 的第二处；F1 只改判定不改 notice。
- `envDependentTestGate`：新增测试不得用固定 `delay` 赌时序（用 `awaitUntil`）。
- 真机判据：
  1. 两席位群 `@dsh` ⇒ 不执行、提示可见；`@全名` ⇒ 执行且落在该席位 ws；
  2. 「简介写 A、声明 B」的群 ⇒ 显示 B、执行 B；
  3. 项目 A 点「始终允许」⇒ 项目 B 仍弹审批（F3）；旧规则随清库消失，不存在跨工作区命中的旧规则；
  4. 两群同 agent 同 ws ⇒ B 群不选 A 群的会话（F4，台账硬保证）；重启后归属仍在；
  5. 点选别 ws 会话 ⇒ 不执行 + 原因（F5 收紧档）；
  6. 多席位群 `@` 候选只显示全名，不出现裸 `agentId`（F1 UI 配套）。

---

## 4. 裁决记录（原 4 项开放问题，已裁决）

| # | 问题 | 裁决 | 一句话理由 |
|---|---|---|---|
| 1 | F5 点选会话跨工作区 | **收紧**：不执行 + 可读原因，不静默换投 | 这不是兼容问题，是判据自相矛盾——requested 分支漏校验 `wsId`；与默认路径收敛为同一套判据（fail-closed） |
| 2 | F3 旧免批规则（无 wsId） | **直接失效（更严档）**：键加 `wsId`，null 只命中 null，不留兼容分支 | 旧规则随清库重配消失；不为开发期写「null = 全工作区」的双读 |
| 3 | F4 会话群维度硬保证 | **直接上硬保证**：`sessionId → convId` 落宿主本地台账 | 「尽力而为 + 归属未知放行」是为旧数据留的档，清库口径下不成立；fail-closed |
| 4 | `@` 候选显示 | **只显示全名**，不显示裸 `agentId` | 比判据层更根治；`@agentId` 仅唯一席位有效（判据不变） |

裁决只影响 P4 写法与 UI 文案，不改变判据与验收框架。

---

## 附录 A：证据索引

| 主题 | 符号 / 文件 |
|---|---|
| 席位与声明 | `VirtualAgentMember`（`defaultWsId`/`allowedWorkspaces`）、`declarationFor`、`CodecGroup.validateGroup` 的 `teamSeatKeys` |
| 群→工作区 | `workspaceOfGroup`（`ConversationProjections.kt`）、`ChatScreen` 的 `matchedWs`、`DesktopShell`/`App.kt`/`WorkspaceGroupCreateDialog` 的 `description.contains` |
| 会话选择 | `AgentSessionPick`（`pick`/`Result`/`Candidate`）、`AgentInboxEnricher.resolveAgentInboxHint`、`ControlPlaneInboxRoutes` 的候选构造、`AgentSessionRegistry.SessionRecord`/`bindConversation`/`conversationOf` |
| mention 判据 | `GroupMeta.mentionedVirtualAgent`、`seatsMatchingToken`、`resolvePromptTarget`、`NoticeKind.TeamMentionAmbiguous`、`AgentPromptWiringGuardTest` |
| 免批 | `AgentToolApprovalStore.Rule`/`isAlwaysApproved`/`remember`、`ControlPlaneHookRoutes`、`ControlPlaneApprovalRoutes`、`ControlPlane.init` 的 `approvalPolicy` |
| 团队投影 | `teamFoldersByConv`（`pluginId = host:agentId`）、`TeamFolderWiringGuardTest` |

## 附录 B：不在本清单范围

- 团队模板（全局定义）与「进群实例化」——那是 Phase B 的独立设计。
- 群级工作区字段（`GroupMeta.wsId` 恢复）——已在评审中判为事实错误，不做。
- 跨群记忆的**产品语义**（是否允许共享）——本清单只做隔离判据，语义由产品定。
