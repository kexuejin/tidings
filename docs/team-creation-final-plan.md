# 团队创建（声明）方案 · 评审重构版

> **状态**：待确认后实施（Phase 1：声明与展示）。本版由 2026-11-09 二次评审重构，替代分支工作稿
> （`feat/team-folder-phase0` @ `9502bff7`）。**先决依赖**：该分支先合入 `main`（团队折叠投影、
> `isTeam` 单一定义在分支上；当前 `main` 尚未包含）。
> **本文性质**：决策文档。上游：[`team-folder-final-plan.md`](team-folder-final-plan.md)（分支上）。
> **一句话**：团队创建不做新页面、不加新协议键、不建新表——它是**群信息页里、对本机某个席位
> 的一次元数据编辑**：把 `VirtualAgentMember.entityType` 置为 `"team"` 并填写 `teamMembers`，
> 走既有的 rev+1 广播链。
>
> **本版最重要的边界**：v1 只做**声明与展示**。工作稿里的「`@coder` 派单/down-drill、Review
> 闭环、多模型主备、宿主模型探针」属于 v2，**当前都不成立**（代码不存在或未接入），已移入
> §13 作为 v2 设计输入，不再混在 Phase 1 的落地清单里。

---

## 0. 结论速览

| 问题 | v1 结论 | 说明 |
|---|---|---|
| 对用户简单吗 | 一键预设 + 保存 | 默认填好经典研发组（编码/审查/测试），可增删；移动端窄屏用双层紧凑行 |
| 创建入口 | 群信息页「入驻智能体」区，本机 agent 行上的「团队编制」 | 不建独立向导；已编队在该行展示「团队 (N)」徽标 |
| 团队的单位 | **一个席位** `(groupId, 本机 hostNodeId, agentId, defaultWsId)` | 声明与 `@` token 的最小单位都是席位；一个 agent 每群最多一个团队 |
| 要新协议键吗 | **不要** | `entityType` / `teamMembers` / `TeamMemberInfo` 已在线格式中（PPE-2 / Goal 5）。**注意**：`group` 帧**没有字段白名单**（`CodecGroup.validateGroup` 刻意没有），"零协议变动"只对帧字段成立 |
| 要新表/迁移吗 | 不要 | 数据随 `GroupMeta` 存 `settings`，无 SQLDelight `.sqm`；回退不需要迁移 |
| 要新写路径吗 | 新增**一个领域写入口**，与工作区授权共用 `commitGroupMetaUpdate` | 不是"零写路径"；必须收口提交+广播，否则会出现第三份元数据写入 |
| 谁能创建 | 本机在该群**有席位**即可（含普通成员） | 需要 §5 的合并规则，否则普通成员的声明会被收端整帧拒收。**兜底**：只对群主/管理员开放（§12 确认项 1） |
| 怎么解散 | 同路径反向写：`entityType` 回 `"single"`、清空 `teamMembers`，一次 rev+1 广播 | 与开关关闭同一实现，不建删除接口 |
| v1 改变执行吗 | **不改变** | `teamMembers`/`role` 无执行消费方；v1 是声明 + 展示 + 既有的 `@leader` 派单入口。组员名暂不参与 `@` 唤醒（§6） |
| 后续演进 | 见 §13 | `@组员` 派单、审查闭环、多模型/主备、模型探针 = v2，需要独立方案 |

### 0.1 重构对照（工作稿 → 本版）

| 处置 | 内容 |
|---|---|
| **保留** | 入口在群信息页、一键预设、紧凑卡片行、保存 = 一次 rev+1/一次广播、`(groupId, defaultWsId)` 定位席位、宿主本地收口（无密钥输入）、两端共用同一 Compose 实现、`commitGroupMetaUpdate` 共用 |
| **修正** | "`group.info` 白名单已含它"（无白名单）；"零协议变动"（帧字段成立，合并语义会变）；"4 个文件"（约 18–22）；工作稿 T2 的"杜绝跨机越权"（收端无宿主过滤，成员侧反而被拒）；工作稿 T4 冲突域（@ 认 `name` 或 `agentId`，忽略大小写；人类昵称不是 token）；工作稿 T5（`role` 用 `AGENT_KIND` 语义不符；缺数量/整帧/收端校验）；工作稿 T7（`groupAuthEditable` 是工作区授权判据，不覆盖团队）；BUSY 防御（没有团队级状态，删除该承诺） |
| **降级为 v2** | 工作稿 §2 协同闭环（ReviewPipeline）、工作稿 §3.3 多模型联合/主备（T15）、工作稿 §3.4 宿主模型探针与注入、工作稿 T9/T10/T11（`@组员` 下钻派单）、工作稿 T12（Review 驳回闭环）；工作稿 `TeamDraft.models` 字段 |
| **删除** | "已评审闭环"状态（无评审记录，且三个阻塞项未处理）、`@team` 语法（不存在）、"429 自动切换备选模型"（现实现是拒绝，不是切换） |
| **新增** | §1 三个已证事实（广播回填、合并权限、席位/行错配）、§4 校验与帧预算、§5 合并主权裁决与兼容性、§8 反向验收探针、§13 v2 设计输入（含"现有机制 / 缺口"核对） |

---

## 1. 先决条件：三个已证事实（不解决不要开工）

| # | 事实 | 证据（主树符号） | 后果 |
|---|---|---|---|
| F1 | 每次广播都用 `groupMetaWithNicks` → `virtualAgentsFor` **从本机工作区重建声明**；新建的 `VirtualAgentMember` 只带 `agentId/hostNodeId/name/defaultWsId`，并替换同 `(agentId, defaultWsId)` 的旧声明 | `RelayControllerGroupsComponent.virtualAgentsFor`、`handleSetGroupAgentWorkspaces` | 写在席位上的 `entityType`/`teamMembers` 在**下一次广播**就被抹掉：本机落库有、发出去的帧没有；`group` 帧不拒收、不报错 |
| F2 | `GroupMerge.decide` 只允许群主/管理员 apply；普通成员只有"只增不减地邀请新人"能过，`virtualAgents` 不参与比较 | `GroupMerge.decide` 的 `member-may-only-invite` 分支 | 普通成员（包括托管 agent 的那台机器）声明自己的席位/团队时，rev+1 会被每个对端**整帧拒收**，发送侧却显示已保存 |
| F3 | 声明单位是 `(agentId, defaultWsId)`，一个 agent 可有多条；但群信息页的 agent 行是**每个 agent 一行**，团队折叠投影按**每条声明**出团队标签且 key 用 `agentId` | `AgentMemberName` KDoc、`groupParticipantRows`、`teamFoldersByConv`、`TeamFolderProjection` | 按 agent 写团队会被放大成多份；按声明写需要 UI 能指定席位；同 agent 多席位/跨宿主同 `agentId` 还会撞投影 key |

---

## 2. v1 目标形态

### 2.1 入口

```
群信息 · 项目研发群
├─ 人类成员 (5)
├─ 入驻智能体 (1)
│   └─ 🤖 dsh-teahouse-mobile · teahouse-mobile
│        [团队 (3)]  [工作区授权]  [团队编制]   ← 本机席位才画第三个按钮
└─ 解散群聊
```

- 「团队编制」只对本机有席位的 agent 画（有保存通道 + 本机席位非空）；无席位只读，不解释成
  "手机端缺失"。
- **工作区绑定前置拦截（Zero-Workspace Guard）**：团队运行依赖明确的代码仓库，若当前代表席位
  尚未绑定默认工作区（`seat.defaultWsId == null`），点击「团队编制」弹出友好引导提示：
  *“请先在「工作区授权」中为该智能体绑定默认工作区，再开启团队编制”*，同时置灰开启开关，杜绝产出无工程目录的悬空团队。
- 团队徽标按**该 agent 的席位**聚合：任一席位 `isTeam` 即显示「团队 (N)」。
- 一个 agent 在本群有多个席位时，对话框顶部先给**席位选择**（用 `@token` 展示）；默认选中
  该 agent 行当前展示的那个席位，且"显示"和"写入"必须用同一个代表席位判据（§3.5）。
- 一个 agent 每群**最多一个团队**：保存到某席位时，清掉同一 agent 其它席位的团队字段。
  席位被移除（授权面板取消暴露）时团队随声明消失——这是席位属性，不单独搬迁。

### 2.2 团队编制对话框（`TeamRosterDialog`，与 `SetGroupAgentWorkspacesDialog` 同族）

```
┌ 团队编制 · dsh-teahouse-mobile ────────────────────────┐
│  席位： [teahouse-mobile ▾]   （仅当本群有多个席位时出现）  │
│                                                        │
│  [✓ 开启团队协同模式]                                    │
│  开启后，群里仍以领队为统一窗口（@ 指向它）。             │
│                                                        │
│  👑 领队（统一出口）: dsh-teahouse-mobile               │
│                                                        │
│  团队阵容（默认预设，可直接改）：                        │
│  ┌─ 组员 1 ──────────────────────────────────────┐     │
│  │  [C] 名字: [ coder               ]  [删除 ×]  │     │
│  │      角色: [编码 ▾]                            │     │
│  └───────────────────────────────────────────────┘     │
│  ┌─ 组员 2 ──────────────────────────────────────┐     │
│  │  [R] 名字: [ reviewer            ]  [删除 ×]  │     │
│  │      角色: [审查 ▾]                            │     │
│  └───────────────────────────────────────────────┘     │
│  ┌─ 组员 3 ──────────────────────────────────────┐     │
│  │  [T] 名字: [ tester              ]  [删除 ×]  │     │
│  │      角色: [测试 ▾]                            │     │
│  └───────────────────────────────────────────────┘     │
│  [+ 添加组员]（最多 8 人）                               │
│  快捷角色: [+ 架构] [+ 前端] [+ 后端] [+ 审查] [+ 安全]  │
│                                                        │
│  组员是团队编制的分工标签：不占群席位，也不会单独执行。   │
│                               [取消]  [保存并广播]       │
└────────────────────────────────────────────────────────┘
```

- 保存按钮沿用既有的 `group_auth_save`（"保存并广播"），**不出现 rev+1 等协议术语**。
- 组员行：`name`（显示名，群内唯一，见 §3.2）、`role`（角色标签，Chip 直接填）、`agentId`
  （内部 slug，由界面生成，不暴露给用户）。
- **Mobile 与 Desktop 双端形态契约**：
  `TeamRosterDialog` 严格遵循单 UI 参数化接缝（`AGENTS.md §2.6`）：
  - 手机端渲染为**名册模式**（仅编辑 name/role），底部注解提示：“执行驱动由桌面宿主 (Desktop) 本地托管”；
  - 桌面端宿主（`desktopApp`）在挂载执行端口后才呈现驱动与高级配置，避免给移动端用户造成“手机能跑本地 CLI 驱动”的错觉。
- 关闭开关 = 一次反向写（`entityType=single`、`teamMembers=[]`），药丸与折叠成员行随之消失。
- 确认文案只用如实的话：「关闭后，群成员将看不到这个 agent 的团队组员清单；@ 仍然指向该 agent」。
  **不写"任务结束后生效"**——没有团队级 BUSY 这一事实（`AgentSessionStates.BUSY` 是单会话状态）。
- 保存失败（席位已被撤、校验失败、合并被拒）要有可读反馈；广播是可靠 + 入队，离线不是失败，
  UI 不说"发送失败"。

### 2.3 交互规则

- **一次保存 = 一次 rev+1、一次广播**（与工作区授权同一节奏，不逐条 toggle）。
- 保存前跑 `TeamRosterValidation`（§4.4），错误就地指出行/字段，不允许"存下去再说"。
- 预设只填默认值，不替用户提交；关闭再打开保留已保存的编制。
- 滚动/键盘/窄宽/深色/无障碍复用 `SetGroupAgentWorkspacesDialog` 的状态槽与 a11y 口径。

### 2.4 明确不做的 UI 承诺

- 不展示"运行中/任务结束后生效"这类没有数据来源的状态。
- 不暗示组员是独立 agent（不说"已创建 3 个 agent"），不承诺 `@coder` 一定叫得醒（v1 不路由）。
- 不做独立团队页 / 创建向导 / 团队 tab / 模型下拉（模型配置是 v2）。

---

## 3. 数据模型

### 3.1 团队挂在席位，一个 agent 每群最多一个团队

```text
席位 = (hostNodeId, agentId, defaultWsId)     // 与 VirtualAgentMember 的声明单位一致
团队 = 该席位上的 entityType == "team" + teamMembers
约束 = 同一 (hostNodeId, agentId) 在一个群里最多一条声明带团队
```

- 保存到席位 X 时清掉同 agent 其它席位的团队字段（写路径保证，见 §4.3）。
- 为什么不是"按 agent 写多份"：协议里 leader token 属于**声明**（席位），不是 agent；
  按 agent 写会制造 N 份副本、放大 `group.info` 帧，并与折叠投影的 `pluginId=agentId` key 冲突。
- 为什么 v1 不允许多团队：UI 没有区分同 agent 多团队的语言；投影 key 也还没带席位。
  要放开时连投影 key 一起改（v2）。
- 席位必须有 `defaultWsId`：旧形态没有默认工作区的声明不在团队编辑入口里（只读展示），
  不替它猜一个工作区。

### 3.2 字段语义与命名规则

| 字段 | v1 语义 | 规则 |
|---|---|---|
| `entityType` | `"single"` / `"team"` | 引用 `EntityTypes`（§3.3），只允许这两个值由本端产出 |
| `TeamMemberInfo.agentId` | 组员内部稳定 id；**不是**驱动键、不是群成员 | 创建时由 `AgentMemberName.slug(name)` 生成；之后**改名不重算**（v2 执行档按它对齐）；团队内唯一；不暴露给用户 |
| `TeamMemberInfo.name` | **同时是 v2 的 `@` token**（展示名与唤醒词同一字符串） | 非空、≤ `Limits.NICK`(32)、**ASCII slug**（小写 `a-z0-9-`，与 `AgentMemberName.slug` 同一字符集）、**群内唯一（大小写不敏感）**，且不与现有 `@` 可点名 token 冲突：所有 `virtualAgents[].name` / `agentId` / 已知 agentKind / 各团队组员名。中文放 `role`（编码/审查/测试） |
| `TeamMemberInfo.role` | 角色标签（编码/审查/测试…） | 非空、≤ `Limits.TEAM_ROLE`(32)，自由文本；Chip 预填 |

- 名字规则现在就按"未来可能成为 token"来收：v1 不路由，但先保证唯一性，避免 v2 上线时被迫
  重命名（重命名等于把别人正在用的名字打断）。冲突时表单拦截并给出具体冲突对象，不做静默改名。
- **编辑保全 DTO 契约**：
  ```kotlin
  data class TeamMemberDraft(
      val agentId: String,          // 稳定内部 slug；编辑时必须沿用旧值，改名绝不重算
      val name: String,             // @ 识别 token (小写 ASCII slug)
      val role: String,             // 中文角色标签 (自由文本 / Chip 预填)
      val isNew: Boolean = false,   // 标记是否为本次新建行
  )
  ```
  - 加载既有团队时：`agentId` 严格沿用原 `TeamMemberInfo.agentId`；
  - 界面点击「+ 添加组员」时：自动分配 `"m-" + slug(name) + "-" + newId().take(4)`；
  - 保存时：按原 `agentId` 映射回去，**坚决不以当前输入重新生成 ID**，杜绝导致 v2 `team-execution.json` 索引失联孤儿化。
- 人类昵称（`memberNicks`）**不是** `@` token，只用于展示；不与组员名做冲突判定。
- 组员名与 role 允许中文；长度按字符计，与协议 `isStr` 同一口径。

### 3.3 `entityType` 值域只有一份定义

新增 core：

```kotlin
object EntityTypes {
    const val SINGLE = "single"
    const val TEAM = "team"
    val ALL = setOf(SINGLE, TEAM)
    fun normalized(raw: String?): String = if (raw in ALL) raw!! else SINGLE
}
```

- `VirtualAgentMember` 默认值、`CodecGroup` 解码兜底、`ChatScreen` / `SetGroupAgentWorkspacesDialog` /
  `isTeam`、本方案的新对话框全部引用它，不再写字面量。
- `agentNode` 的 `PluginRegistration.TYPE_SINGLE/TYPE_TEAM` 是同一值域的另一处定义，改为引用 core
  的 `EntityTypes`（或注释写明同源）；不允许两边各写一份。
- 未知值：**解码不拒帧**，消费方 `normalized()` 归一到 `SINGLE`。group 帧本来没有白名单，
  前向兼容优先；这是展示字段不是动作指令，代码里写清理由并用测试钉住"发送侧只产两个值、
  消费方未知值退化为单兵"。

### 3.4 字段与数量预算（单一定义在 `Limits`）

| 项 | 上限 | 说明 |
|---|---|---|
| 席位/组员 `agentId` | `Limits.ID`（64） | 与现有声明一致 |
| 组员 `name` | `Limits.NICK`（32） | 展示名 |
| 组员 `role` | `Limits.TEAM_ROLE`（32，**新增**） | 不复用 `AGENT_KIND`（它是驱动类型） |
| 每个团队组员数 | `Limits.TEAM_MEMBERS_MAX = 8` | 预设 3–4；8 足够表达分工，且控帧 |
| 每份 `group.info` 的 `virtualAgents` 条数 | `Limits.GROUP_MAX_VIRTUAL_AGENTS = 48` | 真机单 agent 最多 15 个席位；48 留足多 agent |
| 每份 `group.info` 的组员总数 | `Limits.GROUP_MAX_TEAM_MEMBERS_TOTAL = 64` | 与条数共同把最坏帧控制在预算内（§4.6） |

- 两条条数上限是**新增的收端约束**（旧发送侧正常不会触发）；调整上限必须同步 `validateGroup`
  与 §4.6 的帧预算测试，不允许只改常量。

### 3.5 席位定位与"代表席位"

- 写入定位键：`(groupId, defaultWsId)`（本机 host 由 `selfNodeId` 隐含；写路径必须再校验目标声明
  `hostNodeId == self`）。
- "某个 agent 行对应哪个席位"目前由 `declaredWorkspaceOfSender`（core `DeclaredAgentLookup`）
  返回**第一条**匹配声明的 `defaultWsId`；`resolveAgentOfHost` 的显示名也用 `firstOrNull` 取声明。
  两处判据不同，多席位时可能不一致。
- **本版要求**：抽一个单一判据 `representativeSeat(declaredAgents, hostNodeId, agentId)`（core 或
  app 领域层，可测），同时供「行显示名/默认席位」和「对话框默认选中」使用；UI 里显式提供席位
  选择，不把"第一条"当作用户意图。加源码级守卫：两处必须引用同一函数。

---

## 4. 写路径与防线

### 4.1 唯一提交入口 + 声明构造收口

- 抽取 `commitGroupMetaUpdate(updated: GroupMeta)`：`handlePersist` + 逐成员 `sendGroupInfo`
  （发送前必须经 `groupMetaWithNicks`）。
- `handleSetGroupAgentWorkspaces` 与 `handleSetGroupTeamDeclaration` 都只调它。
- 声明构造收敛到一个 `declarationFor(ws, previous)`：新席位/既有席位都走它，`entityType` 与
  `teamMembers` 从 `previous` 原样带过来。
- 源码守卫覆盖**所有** `VirtualAgentMember(...)` 构造点（当前主树 3 处 + 解码 1 处），不是只守
  "两条写路径"；守卫要能区分"保留了团队字段"。

### 4.2 广播必须回填团队字段（F1 的修法）

```text
virtualAgentsFor(meta) 重建本机席位时：
  previous = meta.virtualAgents.firstOrNull { hostNodeId == self && agentId 相同 && defaultWsId 相同 }
  declarationFor(ws, previous)   // entityType / teamMembers 从 previous 带过来；新席位默认 single/[]
```

- 否则保存后的广播没有团队字段，症状是"本机看到药丸、对端什么都没有"。
- `rebroadcastGroupMeta` 的同 rev 去重除 `hasAgents` 外还应有 `hasTeams` 一维：同 rev 内容从
  "没团队"升级为"有团队"时要能补发一次（与 `groupMetaSentHadAgents` 同款先例）。
- **回归测试断言广播产物**：
  `Codec.decodeGroupInfo(Codec.groupInfoPayload(groupMetaWithNicks(updated)))` 仍含团队字段，
  不只断言本地 `settings`。反向验证：去掉回填 → 红。

### 4.3 `handleSetGroupTeamDeclaration` 算法

```text
handleSetGroupTeamDeclaration(groupId, defaultWsId, roster: TeamRosterDraft?)
  meta ← 本地 groups[groupId]；self ← selfNodeId
  权限：self ∈ meta.members
  定位：seat = meta.virtualAgents.firstOrNull {
          hostNodeId == self && defaultWsId == 目标
        } ?: 失败（席位已撤/并发变化，给可读反馈）
  校验：roster 先过 TeamRosterValidation（§4.4），并检查群内 token/名字冲突
  next = meta.virtualAgents.map { va ->
      // 目标席位：entityType = roster != null ? TEAM : SINGLE；teamMembers = roster?.members ?: []
      // 同一 host + agentId 的其它席位：一律清成 single/[]
      // 其它宿主、其它 agent：逐字段原样保留
  }
  updated = meta.copy(virtualAgents = next, rev = meta.rev + 1, updatedBy = self, updatedTs = now)
  commitGroupMetaUpdate(updated)
```

- 撤销 = `roster = null`，同一函数。
- 不新增协议键、不新增表：`entityType`/`teamMembers` 字段早已存在。

### 4.4 `TeamRosterValidation`（core 纯函数，入参含群内 token 池；UI + 域 + 测试共用）

- 组员数 ≤ `TEAM_MEMBERS_MAX`；`name`/`role`/`agentId` 非空且在预算内；
- `agentId` slug 归一去重；`name` 团队内唯一；
- **冲突域（修正 T4）**：群内所有可被 `@` 匹配的 token = 既有 `virtualAgents[].name` +
  `agentId` + 已知 `agentKind`（`mentionKeys` 的口径），加上其它声明的组员名；**大小写不敏感**。
  人类 `memberNicks`/`selfNick` 不是 token，不参与；
- 错误返回结构化原因（哪一行/哪个字段/跟谁冲突），UI 直接展示。

### 4.5 收端校验与解码

- 现状：`validateGroup` 对 `virtualAgents` **完全不校验**；坏条目靠解码侧 `mapNotNull` 静默丢弃。
  注意：这里补的是**已知字段的形状/预算校验**，不是字段白名单——未知键仍按现状忽略，
  "加白名单"依旧违反本仓对 group 帧的既有裁决。
- 新增：`validateGroup` 校验 `virtualAgents` 数组形状、条数、逐字段长度、`teamMembers` 条数与预算、
  `defaultWsId`/`allowedWorkspaces` 的键与长度；越界**拒绝整帧**（`bad-payload`），不再到解码层
  静默消失。`entityType` 未知值按 §3.3 不拒帧。另加一条 v1 结构约束：同一
  `(hostNodeId, agentId)` 至多一条 `entityType == "team"`（防手改帧/旧数据；正常发送侧已保证）。
- 发送侧：只有校验通过的 `GroupMeta` 才允许广播（写路径先跑同一校验），做到"**发送侧不产出
  注定被拒/注定被丢的帧**"。
- 解码侧保留 `mapNotNull`（防御老帧/坏帧），同时新增测试证明经发送侧校验的数据不会走到丢弃分支。

### 4.6 整帧预算

`group.info` 没有发送侧 clamp（`Limits` 的 KDoc：超帧在收端是 framing **peer-closed**，队列还会
重放同一帧）。按 §3.4 上限构造**最坏合法元数据**，测试断言编码后 < `Limits.MAX_FRAME_BYTES` 并留
信封余量；上限一改，这条测试必须跟着改（写在测试头注释里）。

---

## 5. 合并权限（F2）

### 5.1 现状

`GroupMerge.decide` 对 rev+1：群主直接 apply；管理员/旧建群者 apply；普通成员只有"只增不减地
邀请新人"能过。`virtualAgents` 的变化不参与任何判断 ⇒ 成员宿主声明自己的席位/团队会被
`member-may-only-invite` 拒收。

### 5.2 推荐：增加"宿主自声明主权"合并规则

> **兜底方案（如不接受）**：入口只对群主/管理员开放，普通成员只读；本方案其余部分不变。
> 两条路只能选一条，不能"界面允许、收端拒收"。

规则（只对普通成员分支生效；群主/管理员路径不变）：

1. 除 `virtualAgents` 外的所有字段（`members`/`ownerId`/`adminIds`/`name`/`avatarHash`/
   `description`/`announce`/管理身份）必须与本地完全一致；
2. 比较前把 `virtualAgents` 的 `null` 与空列表归一，按**条目多重集合**比较（顺序不敏感）；
3. 对 `hostNodeId != updatedBy` 的每一条声明：两边必须**逐字段相同**（增、删、改都不行）；
4. 对 `hostNodeId == updatedBy` 的声明：允许增、删、改（含 `entityType`/`teamMembers`/工作区白名单）；
5. 仍要求 `updatedBy` 是当前成员、rev 严格递增、自己不被移出；
6. 同一 rev 的 `virtualAgents` 补字段例外保持原样（它已有专门的权限绕过测试）。

- 安全边界：声明是**自述的展示元数据**，不授予执行权（执行名单是独立台账）；允许成员声明
  "自己机器上的 agent/团队"与既有"机主主权"一致，且比现状更严——现状是管理员可整份改写。
- 顺带修存量缺陷：现有"工作区授权"由普通成员保存时同样被拒收；这条规则修好后一并补双节点测试。
- **兼容性代价**（提交信息必须写明）：旧版本节点的合并逻辑仍会拒收成员发起的 rev+1。团队声明的
  可见性依赖组内成员版本一致；组内有旧版本成员时，编辑者需是群主/管理员，或等对方升级。
  这是"新增语义"，不是"新增字段"，不能按 `AGENTS.md §2.2` 的可选键口径说"零风险"。

### 5.3 旧数据 / 旧端 / 回退

- 旧客户端（字段存在之前的构建）不认识 `entityType`/`teamMembers`：收端忽略；但它作为管理员
  改群名/成员时会重编码出**不含这两个字段**的整份元数据，rev+1 后团队声明被静默清空。文档与
  验收里要写明这条混合版本窗口；不要把"零 DDL"当成"零兼容风险"。
- 降级安装旧版本：字段在 `settings` 里，旧版本忽略；再升级回来仍在（前提是没有旧版本管理员
  重写过群元数据）。
- 回退：关闭开关即清空本机席位的团队字段；零协议键新增、零 DDL、零迁移。

---

## 6. v1 能力边界

### 6.1 v1 能做什么

- 声明某个席位的团队编制（组员名字/角色）；
- 全群通过 `group.info` 同步：群信息里看到团队徽标，会话列表看到 `团队 N ▸` 药丸；
- 展开看组员标签；点 leader/成员行预填 `@<leader token>`（既有行为）；
- 关闭后恢复单兵，一次广播。

### 6.2 v1 不能做什么（必须如实写进产品文档与 UI 文案）

| 能力 | v1 现状 | 说明 |
|---|---|---|
| `@coder` 唤醒组员 / 下钻到 Leader | 无 | mention 池只有 `virtualAgents` 的 `name/agentId/kind`；组员名不在池里 |
| 按 role 注入 persona | 无 | `role` 没有任何执行消费方 |
| 按模型档位派单 / 主备降级 | 无 | `TeamMemberInfo` 没有 `models` 字段；`ChainStage` 也没有 model |
| 审查驳回→修复→二次验收 | 机制存在但未接入 | `ReviewPipeline` 由 `DelegationPlan`/`DesktopStageChain` 驱动，与 `teamMembers` 无连接 |
| 组员独立执行 / 独立会话 | 无 | 执行主体仍是 leader agent |
| 创建插件团队 | 无 | `PluginManifest(type="team")` 是另一套可执行团队模型；v1 不创建、不桥接 |

### 6.3 与插件团队的关系

- 群 `virtualAgents[].entityType/teamMembers`：**群内声明/展示**，随 `group.info` 广播，非可执行。
- `PluginManifest` 的 `type/leader/members`：**宿主本地可执行团队**，由 agentNode 注册、编排层消费。
- v1 只做前者。v2 若要执行，方向是"声明 → 宿主本地插件清单（执行事实源），群元数据继续做展示投影"；
  不在本方案范围。
- UI 用词区分：按钮叫「团队编制」，组员区标注"分工标签，不单独执行"；不出现"已创建团队/智能体"。

---

## 7. 落地拆解

### 7.1 P0-0 先决项（独立提交，先于功能）

| 提交 | 内容 | 反向验证 |
|---|---|---|
| P0-0.1 值域与声明构造收口 | `EntityTypes`；`PluginRegistration` 引用；`declarationFor(ws, previous)`；`virtualAgentsFor` 回填；`isTeam`/UI 字面量全部改引用；`representativeSeat` 单判据 | 去掉回填 → 广播产物测试红；保留字面量 → 源码守卫红 |
| P0-0.2 合并主权规则 | `GroupMerge` 自声明分支 + 测试矩阵（own 增/改/删通过；foreign 改/删拒；其它字段变拒；null/空归一；顺序不敏感） | 注释掉新分支 → 双节点测试红 |
| P0-0.3 收端校验与预算 | `Limits` 新常量；`validateGroup` 校验 `virtualAgents`；`TeamRosterValidation`；最坏帧预算测试 | 放回"不校验" → 超长组员静默丢失测试红；调大上限 → 帧预算测试红 |
| P0-0.4 phase0 小修（合并前） | 团队折叠投影行 key 不要只用 `agentId`（跨宿主同 `agentId` 会撞）；key 用 `name` 或 `(host, agentId)` | phase0 已有"key 唯一"测试 |

### 7.2 P0-1 领域写路径

- `RelayControllerGroupsComponent`：`commitGroupMetaUpdate`、`handleSetGroupTeamDeclaration`、
  `handleSetGroupAgentWorkspaces` 改走统一辅助；广播断言用例。
- `RelayController`：接口 + 实现转发（与 `setGroupAgentWorkspaces` 同形）。
- 测试：`app/src/jvmTest` 单测 + 双节点用例（见 §8）。

### 7.3 P0-2 UI 与资源

- `GroupInfoDialog`：本机 agent 行加「团队编制」按钮与「团队 (N)」徽标；新参数回调。
- `TeamRosterDialog`（`ui/components/`，与 `SetGroupAgentWorkspacesDialog` 同族；**两端共用**）。
- 动作接线：`ChatScreenArgs`（action 类型）、`App`（手机侧实现）、`ChatDialogs`（手机壳）、
  `DesktopChatPane`（桌面壳）——桌面不特殊化，必须同挂。
- 资源：`values/strings.xml` + `values-en/strings.xml` 逐键对齐（严防 `cjkLiteralGate` 红线）：
  | 键名 | 中文默认值 | 英文对照 |
  |---|---|---|
  | `team_roster_button` | 团队编制 | Team Roster |
  | `team_roster_dialog_title` | 团队编制 · %1$s | Team Roster · %1$s |
  | `team_roster_enable_switch` | 开启团队协同模式 | Enable Team Collaboration |
  | `team_roster_enable_desc` | 开启后以领队为统一对外窗口，内部由专职组员分工执行。 | Leader acts as unified window with internal members. |
  | `team_roster_no_ws_warning` | 请先在「工作区授权」中绑定默认工作区，再开启团队编制。 | Please bind a default workspace in Group Auth before enabling team. |
  | `team_roster_member_name_label` | 名字 (@标识) | Name (@mention) |
  | `team_roster_member_role_label` | 角色标签 | Role Label |
  | `team_roster_add_member` | + 添加组员 | + Add Member |
  | `team_roster_quick_roles` | 快捷角色 | Quick Roles |
  | `team_roster_conflict_warning` | 与群内成员 @%1$s 冲突，请更换 | Name conflicts with @%1$s, please choose another |
  | `team_roster_limit_warning` | 名称不得超过 %1$d 字符 | Name cannot exceed %1$d characters |
  | `team_roster_mobile_notice` | 执行驱动与模型由桌面宿主 (Desktop) 托管 | Execution drivers and models are managed by Desktop host |
- 设计契约：`design/design-spec.json` 组件条目（file/screens/states/previews/requiredTokens）；
  至少一帧 `@Preview`（`designStateSlotGate` 的证据口径）；必要的截图/预览文件。
- 测试：`composeApp/src/commonTest` 的表单判据/状态槽/预览；`androidApp/src/screenshotTest` 预览。

### 7.4 真实改动面

| 层 | 文件（约） | 内容 |
|---|---|---|
| core | `GroupMeta.kt`、`CodecGroup.kt`、`Limits.kt`、`GroupMerge.kt`、`DeclaredAgentLookup.kt` + 测试 | 值域、校验、合并、席位判据 |
| app 领域 | `RelayControllerGroupsComponent.kt`、`RelayController.kt`、`SenderIdentity.kt`、`ChatScreenArgs.kt` | 写路径、回填、代表席位、接口桥 |
| app 壳 | `App.kt`、`ChatDialogs.kt`、`DesktopChatPane.kt`、`GroupInfoDialog.kt`、新 `TeamRosterDialog.kt` | 入口与接线 |
| 资源/设计 | `values/strings.xml`、`values-en/strings.xml`、`design-spec.json`、预览/截图 | 双语 + 设计契约 |
| 文档 | `team-agent-interaction-and-orchestration-spec.md`、`PROTOCOL-EXTENSION.md`、`README.md`、`ACCEPTANCE.md` | 状态/生产者说明/产品口径/真机验法 |

预计 **18–22 个文件**（含测试与资源）。按 `AGENTS.md §7` 拆提交：先决项各一条，功能一条（测试同条），
UI 一条；P0-0.2 的提交信息必须写清"合并语义变更 + 旧端兼容后果"。

---

## 8. 验收判据

| 层 | 判据（含反向探针） |
|---|---|
| 领域单测 | 保存成功 rev+1；目标席位 `entityType=team`、组员正确；同 agent 其它席位被清空；其它宿主声明逐字段不变；关闭后回 `single` 且组员空；多席位切换写对目标 |
| 广播回归 | 保存后**广播产物**（编码→解码）仍含 `teamMembers`；反向：去掉 `virtualAgentsFor` 回填 → 红；同 rev 内容升级能补发一次 |
| 合并（双节点） | 编辑者=普通成员：对端 apply；编辑者改他人声明：对端拒；编辑者改群名/成员：仍按既有规则拒；群主/管理员路径不回归 |
| 校验/预算 | 超组员数/字段长/总组员数/`virtualAgents` 条数 → 同一校验明确失败（不是解码侧静默少人）；最坏合法帧 < 64 KiB |
| 守卫 | 所有 `VirtualAgentMember(...)` 构造点在允许清单内；元数据提交只经 `commitGroupMetaUpdate`；`EntityTypes` 之外无字面量（剥注释后扫描）；显示与写入共用 `representativeSeat` |
| UI | 有席位可编辑 / 无席位只读；多席位切换；超过 8 人不可加；冲突定位到行；空态/关闭态可读；键盘与窄宽 |
| 门禁 | `cjkLiteralGate`（文案进资源）、`designLocaleParityGate`（双语逐键）、`designSpecDrift`/`designStateSlotGate`/`designTokenUsageGate`、`:domainComposeFreeGate`、`tools/unused-imports.py`、`tools/check-design-palette.py` |
| 真机（电脑→手机） | 电脑端建队 → 手机端收到 `group.info` 并渲染药丸 → 展开见组员 → 点行预填 `@leader`；关闭后药丸消失；群名/预览/未读/在线数不被改动 |
| 真机（成员宿主） | 非群主宿主声明团队 → 群主端可见（依赖 §5.2；若选兜底方案则此条改为"只读"） |
| 真机（旧端） | 旧版本作为管理员改群名后，团队声明被清空——按 §5.3 记录为已知兼容限制，不作为缺陷 |
| 回归 | 现有群操作（建群/加人/改资料/解散/工作区授权）与折叠投影零回归；`closeCount` 不新增抖动 |

---

## 9. 不做 / 延期（v1 明确不做）

- 不做：`@组员` 派单/down-drill、Review 驳回闭环接线、多模型联合审查、主备模型降级、宿主模型
  探针/下拉、插件团队桥接、独立团队页/向导/tab、团队会话键、每 agent 多团队。
- 延期理由与设计输入见 §13；它们需要的协议/storage/编排改动都超出"声明编辑"的范畴。

---

## 10. 回退

- 关闭开关：`entityType` 回 `single`、`teamMembers` 清空，一次 rev+1 广播 → 药丸与成员行消失。
- 移除功能：删入口按钮与对话框，保留字段（旧数据无害，显示为单兵）；不删表、不改会话键。
- 版本回退：旧客户端忽略团队字段；混合版本窗口见 §5.3。
- **不需要数据迁移**；需要的是运维口径：组内管理员应升级到认识这两个字段的版本（旧版本管理员
  编辑群资料时会把它不认识的字段一起重编码掉，现状如此，不是本方案引入）。

---

## 11. 文档与提交管理

- 分支 `feat/team-folder-phase0` 上目前是工作稿（`9502bff7`）；本重构版应**提交到该分支**（或同时在
  main 落一条），不要在 `main` 留"未跟踪的重构版 + 分支上的工作稿"两份——合并分支时 git 会拒绝
  覆盖未跟踪文件，且评审会分不清哪份是现行方案。提交信息写清"评审重构：先决条件 + 席位/合并
  裁决 + 反向验收；v2 目标拆出"。
- 合并后同步：`team-agent-interaction-and-orchestration-spec.md`（团队数据/创建状态）、
  `PROTOCOL-EXTENSION.md`（`entityType`/`teamMembers` 的新生产者、无新增键）、`README.md`
  （产品口径：v1 只做编制声明）、`ACCEPTANCE.md`（真机验法与混合版本限制）。

---

## 12. 开工前确认（3 项）

1. **成员宿主能否自声明**：采纳 §5.2 的合并主权规则（推荐，修存量缺陷，但属于行为语义变更、
   旧端会拒收），还是兜底为"仅群主/管理员可编辑、成员只读"？这决定 P0-0.2 是否在本期。
2. **规模上限**：`TEAM_MEMBERS_MAX=8`、每帧组员总数 64、`virtualAgents` 48 是否接受（只为帧
   预算；调上限必须同改测试）。
3. **v1 边界**：`@组员` 派单/审查闭环/多模型全部延到 v2（§13），是否接受；若要求 v1 就能执行，
   需要先补 §13 的前置设计，工期与风险另算。

---

## 13. 附录：v2 设计输入（已拆出）

团队执行的完整设计已拆到 [`team-execution-final-plan.md`](team-execution-final-plan.md)（待评审）。
本文只保留边界结论，不重复其中的设计与代码事实：

- v1 仍只做**声明与展示**；`@组员` 路由、Review 编译层、按成员选模型/主备都在 v2 文档里，不混进本方案落地。
- 已核对的 v2 事实：`ReviewPipeline` / `StageChainGate` / `StageChainRunner` 真实存在，但由
  `DelegationPlan` 驱动、与 `teamMembers` 零连接；`TeamMemberInfo` 没有 model 字段；仓库没有
  `availableModelsOf` 之类的模型池 API；`StageChainGate` 遇驱动冷却返回 Reject，不会自动切模型。
- 跨文档约束：组员 `name` 必须是可 `@` 的 ASCII slug（§3.2），否则 v2 无法把组员名变成稳定、可路由的 token。
