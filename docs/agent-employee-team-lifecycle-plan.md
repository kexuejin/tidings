# 员工与团队的全生命周期方案（创建 / 编辑 / 自动创建）

> 本文回答一个问题：**员工和团队从哪来、谁有权改、改完怎么让所有端看到同一份事实。**
> 现状是三条互不相连的写路径（入驻 / 撤回 / 团队编制）加两个只读页面（智能体页、群信息），
> 于是「群里能建团队、智能体页只能看、agent 完全不能建」。本方案把它们收成**一条命令 + 三个入口**。

## 0. 结论先行

| 维度 | 现在 | 本方案 |
|---|---|---|
| 员工创建 | 只能在群信息里「邀请 agent 入驻」，名字自动派生（`kind-职责`） | **员工档案先行**：先在本机登记员工（名字 + 职责），群只负责「添加」；先建档、一个群都不进也成立（已落地，见 §2.8 / §3.4） |
| 员工编辑 | **没有**。改名、改职责、改项目目录、换底座都没有面 | 新增「编辑员工」：改显示名 / 职责，身份键不变；改名**不动 `@` token** |
| 团队创建 | 只能在群信息里「团队编制」（人点） | 同一命令，多两个入口：智能体页「新建团队」、agent 工具面 |
| 组员编辑 | 群信息里的名册（整表替换） | 同一命令，卡片行内增量改（改名 / 改角色 / 移出） |
| agent 自动创建 | **不支持**（控制面只有派单与事件，没有编制命令） | 新增 `roster` 授权域 + 工具动作，走同一条命令、带审计、fail-closed |
| 唯一真相 | 席位在 `settings.groups[].virtualAgents`；名字 / 名册 / 职责都挤在席位里 | **分层两份**：档案（团队 / 员工）住 `RelaySettings`，席位持引用 + 快照；席位写入口收成一条，档案写入口各一条，绕过它们的路径由守卫封死 |

**已引入的两个引用键**：`teamId`（团队档案，B 档）与 `employeeId`（员工档案）。
它们不是「独立对象进协议」，而是**本机档案 + 席位快照**：档案住宿主 `RelaySettings`，
协议里只多两个可空键与一份快照。**身份键仍是裁决**：团队 `(groupId, leaderAgentId)`、
员工 `(hostNodeId, agentId)` —— 档案只给它们一个名字，不改身份。
「团队名被群名绑定」已作为缺陷修掉（§10 记录了取舍与落地）。

## 1. 现状（判据都在代码里，不靠记忆）

### 1.1 数据面：席位一份 + 两份本机档案

- 席位：`RelaySettings.groups[].virtualAgents: List<VirtualAgentMember>`，身份键 `(groupId, hostNodeId, agentId)`。
- 团队：某条席位 `isTeam`（`entityType == "team"` 或 `teamMembers` 非空），键 `(groupId, leaderAgentId)`。
- 组员：`teamMembers: List<TeamMemberInfo(agentId, name, role)>`，**没有 hostNodeId**（执行宿主 = 领队宿主）。
- **团队档案**：`RelaySettings.teamProfiles: List<TeamProfile>`（`teamId` + 名字 + 名册）；
  席位持 `teamId` 引用 + `teamName` 快照。
- **员工档案**：`RelaySettings.employeeProfiles: List<EmployeeProfile>`（`employeeId` + `agentId` +
  名字 + `@` token + 职责）；席位持 `employeeId` 引用 + `employeeName` 快照。
- 归属四元组、执行绑定 `driverKey + models` 分别住 `AgentAssignmentProjection` 与 `TeamExecutionStore`。

两份档案都**只在本机**（宿主的 `RelaySettings`），不进协议帧体积预算以外的任何结构；
对端读到的是席位快照 —— 所以「本机改了、手机没变」只可能是快照没重建，判据是群 rev 有没有推进。

### 1.2 写路径现在有三条，互不共享校验

| 动作 | 入口 | 实现 | 广播 |
|---|---|---|---|
| 入驻 | 群信息 / 建群流程 | `handleAddGroupAgent` | `commitGroupMetaUpdate` → `sendGroupInfo` |
| 撤回 | 群信息成员行 | `handleRemoveGroupAgent` | 同上 |
| 团队编制 | 群信息 → 成员行「团队编制」 | `handleSetGroupTeamDeclaration` | 同上 |

三条各自做局部校验（是否在群、席位是否存在、token 池），**校验规则已经出现分叉**：
团队编制校验了 token 池，入驻只校验名字唯一性。这就是「同一个名字在两条路里一个能过的、一个过不了」的来源。

### 1.3 两个盲区

- **员工没有编辑面**：全仓没有 `renameAgent` / `updateAgentSeat` 之类的入口。想改一个席位的名字，只能撤回再入驻（会换 `agentId` 派生名，历史 @ 全断）。
- **agent 没有写面**：控制面路由里只有派单（`/v1/agent/invoke`）、事件、审批、授权、项目目录；**没有任何「改编制」的路由**。所以 agent 无法自建团队，也无法把自己加进群。

## 2. 模型与不变量（先钉死，再谈入口）

1. **员工有一份本机档案，席位只是它在某个群的出现**。身份仍是 `(hostNodeId, agentId)`；
   名字、`@` token、职责住 `RelaySettings.employeeProfiles`（键 `employeeId`），席位只持有
   `employeeId` 引用 + 快照 `employeeName`。智能体页按 `(hostNodeId, agentId)` 聚合，
   **卡面名字取档案名**（没有档案才回落席位 token）。
2. **`agentId` 是身份键，任何编辑都不许改它**。改名改的是档案的 `name`（显示名），不是身份，也**不动 `@` token**。
3. **`@` token 在群里定，跨群默认一致**。档案的 `token` 是默认值；某群撞名时该群用**别名**
   （`token-2`），而不是拒绝入驻 —— 结果里如实带 `aliasedGroupIds`，界面必须说明。
4. **一个 agent 每群最多一个团队**（既有约束，写路径 + 收端 `validateGroup` 双重兜底）。
5. **团队有自己的名字（B 档已落地）**。团队是一份**档案**（`RelaySettings.teamProfiles`：
   `teamId` + `name` + `leaderAgentId` + 名册），群里的席位只持有 `teamId` 引用 + 一份广播快照
   （`teamName` / `teamMembers`）。一份名册可进多个群，改名一次对所有引用群生效。
   群名不再是团队名，只作副行上的「它在这个群」的线索。详见 §10.3 A/B 与 §10.4 落地记录。
6. **写入必须 rev+1 并广播**。rev 是并发与幂等的唯一凭据；不广播的写入等于制造分歧。
7. **声明本机主权**：只能声明 `hostNodeId == selfNodeId` 的席位；替别的宿主声明 = 两边各说各话，一律拒绝。
8. **「创建员工」不是「创建 agent」**。agent 是「这台机器上真的有一个进程在跑」，身份来自宣告；
   档案只做**登记 + 命名**（`agentId` 必须能从本机台账找到；台账为空时放行，界面如实提示未上报）。
   做成空表单「新建员工」只会产出点了派单静默失败的幽灵。

### 2.1 员工档案的字段与生命周期

| 字段 | 含义 | 谁写 |
|---|---|---|
| `employeeId` | 档案键（`emp-…`），席位用它定位 | 写路径 `allocateEmployeeId` |
| `agentId` | 本机 agent 身份键（**换底座不改它**） | 登记时定，之后只读 |
| `name` | 显示名（可含中文），卡面/详情页标题 | `Create` / `Update` |
| `token` | `@` 默认 token（ASCII）；改 `name` **不动它** | `Create` 派生、`Update` 显式改 |
| `duty` | 职责（`AgentDuties` 取值域，域外归一） | `Create` / `Update` |
| `rev` / `updatedTs` | 版本与时间戳（「重建真的发生了」的判据） | 每次写入 +1 |

生命周期：`Create`（可带 `groupIds`，空 = 只建档）→ `AddToGroups`（后加群）→
`Update`（改名 / 改 token / 改职责，对所有引用群生效）→ `Forget`（删档案，**席位保留**）。

## 3. 唯一写路径：`GroupDeclarationCommand`

### 3.1 命令面（八条，覆盖全部编制变更）

```kotlin
sealed interface GroupDeclarationCommand {
    val groupId: String
    val baseRev: Int            // 乐观并发：不匹配即拒绝，不静默覆盖
    val actor: DeclarationActor // HUMAN(nodeId) / AGENT(hostNodeId, agentId) / REMOTE(nodeId)
    val origin: DeclarationOrigin // GROUP_INFO / AGENT_HUB / AGENT_TOOL / PROTOCOL

    data class JoinSeat(...)        // 入驻：agentId + duty + defaultWsId?
    data class LeaveSeat(...)       // 撤回：agentId
    data class UpdateSeat(...)      // 编辑：name? / duty?（改名 = 本群别名，不动档案）
    data class SetTeamRoster(...)   // 群内直编名册：agentId + roster(?)，null/空 = 关闭
    data class AttachTeam(...)      // 挂团队档案：teamId + teamName + roster 快照
    data class DetachTeam(...)      // 摘团队档案：回单兵
    data class AttachEmployee(...)  // 挂员工档案：employeeId + employeeName 快照 + token
    data class DetachEmployee(...)  // 摘员工档案：回「未登记 agent」
}
```

后四条**只由两个档案写路径发**（`TeamProfileWritePath` / `EmployeeProfileWritePath`），
界面不许自己拼 —— 源码守卫 `TeamProfileWriteGuardTest` / `EmployeeProfileWriteGuardTest`
按「`copy(字段 = ` 的写入点」与「命令的构造点」两条钉子守死。

`handleAddGroupAgent` / `handleRemoveGroupAgent` / `handleSetGroupTeamDeclaration` **已降级为命令的入口适配**：
它们只把界面输入翻译成命令、把结果映射成横幅，校验与落盘顺序都在 `applyDeclaration` 里
（`app/src/commonMain/kotlin/com/kapp/tenon/domain/GroupDeclarationWritePath.kt`）。
对外新增 [DeclarationResult]（`Applied` / `Rejected` / `Conflict`），三个入口的旧签名保持不变
—— 界面与既有测试不用改，P2 再加带 `baseRev` 的新入口。

### 3.2 命令内部固定顺序（顺序本身就是判据）

1. 群存在且 `actor` 在群里（agent 还要过授权域，见 §5）。
2. `baseRev == group.rev`，否则返回冲突 + 最新快照。
3. 定位席位（本机 + `agentId`，大小写不敏感）；`UpdateSeat` 允许「席位不存在」时按入驻处理，其余命令不猜。
4. 校验：名字唯一 + **token 池**（群内所有可 @ 的 key，含领队自己）+ 每群一个团队
   （席位上限目前没有常量，不引入新上限 —— 加限制是另一件事，不在写路径收口里顺手做）。
5. 生成新 `virtualAgents`（其余席位原样保留，**不做整表重写**）。
6. `rev + 1`、`updatedBy = actor.nodeId`、`updatedTs = now`。
7. `handlePersist` → `commitGroupMetaUpdate` → `sendGroupInfo` 给群内其它成员。
8. 审计一行（本地 `declaration-audit.jsonl`）：`actor / origin / command / 前值 → 后值 / rev`。

**判据**：任何入口改动同一字段，落盘后的 `rev` 增量、广播帧数、审计行数必须完全一致。
不一致就是有旁路，按缺陷处理。

### 3.3 封死旁路

现有代码里已经出现过「靠整体改写 settings 达成目的」的做法（旧授权面板）。本方案加一条源码守卫
（`GroupDeclarationBypassGuardTest`），三条判据：

| 判据 | 白名单 | 越界含义 |
|---|---|---|
| `commitGroupMetaUpdate(` 出现处 | 定义（`RelayControllerGroupsComponent`）、编制命令（`GroupDeclarationWritePath`）、指针投影重建（`RelayControllerProjectDirectoryComponent`） | 第四处 = 又一条绕过 rev / 校验 / 审计的落盘路径 |
| `copy(groups =` 出现处 | 仅 `RelayControllerGroupsComponent`（建群/改资料） | 编制变更改写 `settings.groups` = 绕过命令 |
| 三个入口的函数体 | 必须含 `applyDeclaration(`，且不许出现 `commitGroupMetaUpdate(` / `virtualAgents =` / `rev = ` | 入口又把 patch 抄了一遍 ⇒ 三份抄本会再次分叉 |

守卫剥注释后按词匹配，并按函数体**取块**（不是全文 `contains`）：本仓踩过两种假绿 ——
被自己的注释绊倒、被同文件里另一段同名代码骗过。

### 3.4 员工档案：两层写路径（不是第二条旁路）

**档案层**（`EmployeeProfileWritePath`）：`Create / Update / AddToGroups / Forget` 四条命令，
唯一实现 `applyEmployeeProfile`。顺序 = **先全量预校验（目标群成员 + 本机 token 池）→ 落档案 →
逐群挂席位**；跨群**不是事务**，某群失败不回滚别群，结果如实带 `failedGroupIds`，
撞名用别名的群带 `aliasedGroupIds`。

**席位层**：`AttachEmployee` / `DetachEmployee`，仍然走 `applyDeclaration` —— 每群各自 rev+1、
各自广播、各自一行审计，与人工入驻同一套。

判据（`EmployeeProfileWritePathTest`，14 条 + 守卫 3 条）：先建档一个群都不进也成立；
一份档案多群复用；改名一次生效而 **token 不漂**；显式改 token 才重写席位名；
撞名走本群别名而不是拒绝；删档案只摘引用、席位与名字都留；同一 agent 第二份档案被拒。

## 4. 三个入口（同一命令，不同面）

### 4.1 群内人工（保留，不动交互）

群信息 → agent 成员行 → 「团队编制」→ 开启团队协同模式 / 添加组员 → 保存。
这条是既有的、真机验过的路径，不重做。

### 4.2 智能体页人工（新增，补两个面）

**A. 员工详情 → 「登记为员工 / 编辑员工」（已落地，桌面壳）**

| 字段 | 可改 | 规则 |
|---|---|---|
| 显示名（`EmployeeProfile.name`） | 是 | 可含中文；**改名不动 `@` token**（token 一漂，群里的输入习惯就断） |
| 职责（`duty`） | 是 | 取值域 `AgentDuties.ALL`；只影响新建席位的默认名与展示 |
| `@` token | 否（界面不给） | 建档时由名字派生（`slug` → 空则回落 `kind-职责`）；真要换走命令的显式参数 |
| 项目目录 | 否（在群席位 / 群指针上） | `defaultWsId` 是**每群事实**，不属于员工档案 |
| 执行档 | 否（团队粒度） | 走既有 `TeamExecutionPort`；员工级执行档本轮不做（无消费方） |
| `agentId` / `hostNodeId` | 否 | 只读展示，明确写「身份不可改」 |

入口只在**桌面壳**画（`DesktopPaneActions.saveEmployeeProfile`）：档案只住宿主机，
手机读不到也写不了；手机端详情页 `onEditEmployee == null` ⇒ 不画这颗按钮。
没有身份键（`agentKey == null`）时也不画 —— 点了只会失败。

**B. 团队视图 → 「新建团队」（已落地，桌面壳）**

- 表单：团队名 → 选领队（本机卡片流里的 agent）→ 加组员 → 选要进的群（可多选；新建至少一个）。
- 提交即 `TeamProfileCommand.Create(name, leaderAgentId, members, groupIds)`：**一次动作**建档案 + 逐群挂席位。
- **不画的条件**：`onSaveTeam == null`（手机 / 预览）⇒ 两颗入口都不出现；本机没有任何 agent 时
  对话框照画，但保存置灰并给出路（「先去项目目录里让一个智能体跑起来」），
  而不是把入口藏起来装作没有这个功能。
- 落点：`TeamProfileDialog.kt` + `AgentTeamCards.kt`（段头「新建团队」、团队卡底行「编辑团队」）。

**C. 组员行内编辑（已落地，入口在「编辑团队」里）**

团队卡 → 「编辑团队」→ 改组员名 / 角色 / 增删，提交是 `TeamProfileCommand.Update(teamId, members = …)`
＋ `AddToGroups`（只增不减）——改一次档案，所有引用群各自重建快照。**不走** `SetTeamRoster`
（那条是群内直编，对档案化席位会被 `TEAM_PROFILE_BACKED` 拒掉）。

### 4.3 agent 自动创建（新增，这是本方案的核心增量）

**工具面（三个动作，最小集）**

| 动作 | 语义 | 作用域 |
|---|---|---|
| `team.roster.read` | 读某群名册（席位 + 组员） | 只读，不需要写授权 |
| `team.roster.set` | 设/清某群某席位的团队名册 | 需 `roster` 写授权 |
| `employee.update` | 改本机某**员工档案**的名字 / 职责（`EmployeeProfileCommand.Update`） | 需 `roster` 写授权 |

**授权模型（复用既有 `AgentGrant`，不新造一套）**

- 新增授权域 `scope = "roster"`，与现有 `all / agent` 并列。
- 三档语义沿用：
  - `auto`：允许 agent 直接写，但受**硬上限**约束（成员数 ≤ `TEAM_MEMBERS_MAX`、只能改自己当领队的队、组员必须是本机台账里存在的 agent）。
  - `byDevice`：必须在该设备的白名单里；不在白名单 = 沉默拒绝（与既有口径一致）。
  - `request`：落一条审批卡（复用现有 approval 流），批准后一次性授权，用完即失效。
- **默认档是 `request`**：新装/未配置时 agent 不能自建团队，只能申请。

**审计与回执**

- 每次写入落审计行（`actor = AGENT(hostNodeId, agentId)`、工具名、前后值、rev）。
- 群内回一条系统提示（可折叠）：`dsh-assistant 调整了团队编制：+coder、+reviewer`。
- 拒绝时回**可读原因**（哪个校验挂的），不许沉默——「没反应」是这套系统里最贵的故障形态。

**边界（写进实现，不是口头约定）**

- agent 不能给**别的宿主**加席位、不能改别人的队、不能把自己加进没入驻的群（这三条即使 `auto` 档也不放行）。
- agent 不能改自己的 `agentId`、不能绕过 rev 并发检查、不能改授权本身（授权只由人/设备写）。

## 5. 并发、幂等与广播

| 场景 | 行为 |
|---|---|
| 两台设备同时改同一群 | `baseRev` 不匹配者拒绝，UI 提示「编制已被 X 改成 rev=N」并给「重新载入 / 覆盖」两个明确选择 |
| 同一命令重复提交 | `baseRev` 已推进 ⇒ 第二次拒绝（幂等靠 rev，不靠去重窗口） |
| 广播失败（对端离线） | 本地仍算成功（rev 已推进）；对端下次 `group-info` 全量对齐时收敛。**不重试刷屏** |
| 组员名与群内 token 撞 | 拒绝并指出撞了谁（现在只报「非法」，要给出具体名字） |

## 6. 验收判据（全部可执行）

1. **入口等价**：三条入口各建一次团队，落盘结果字节级一致（除 `updatedBy/updatedTs`），`rev` 各 +1 一次。
2. **改名不改身份**：改显示名后 `agentId` 不变、`AgentHubUi` 的 key 不变、历史消息的 @ 解析仍指向同一员工（单测 + 真机）。
3. **旁路封死**：把某处改成直接 `copy(groups=...)` 写 settings ⇒ 源码守卫变红。
4. **agent 无授权写入被拒**：`request` 档下 `team.roster.set` 返回 `pending-approval` 且不落盘；`auto` 档下超上限被拒且**不部分写入**。
5. **并发冲突**：两个命令同 `baseRev`，第二个返回冲突，落盘只有第一份。
6. **广播计数**：群内 N 个成员 ⇒ 每次写入产生 N-1 帧 `group-info`（离线成员计入未送达，不重试）。
7. **空态纪律**：没有已入驻的群时，「新建团队」按钮不存在（源码守卫 + 截图核对）。

## 7. 分期（每期都能独立交付并验收）

**P1 · 命令收口（无新 UI）**
把三条 handler 收成 `applyDeclaration`，补 `baseRev` 与审计，加旁路守卫，跑现有全部编制测试。
价值：为后面两个入口铺路，且立刻消除「两条路校验不一致」。

> **已落地**。`GroupDeclarationWritePath.kt`（命令 + 结果 + 审计环 + 四条 plan 函数），
> 三个 handler 变成适配器，`GroupDeclarationCommandTest`（15 条）与
> `GroupDeclarationBypassGuardTest`（3 条）为判据，三条守卫都做过反向探针。
>
> 落地时如实留下的边界：
> - `baseRev` 在**既有界面入口上是 null**（界面还不知道「我看到的是哪一版」），
>   带基线校验的能力已有并有测试，但只有 `actor = AGENT` 时才**强制**要求 ——
>   P2/P3 让界面与工具面各自带上 rev 后，`null` 这条退路就该关掉。
> - 审计是**内存环**（最近 64 条），不是 `declaration-audit.jsonl`：`:app` 没有文件写路径，
>   落盘要宿主端口。行数与 rev 增量口径已用测试钉住，接宿主 jsonl 时不用改判据。
> - `UpdateSeat` 只改 `name`（`duty` 只在「席位不存在 ⇒ 按入驻处理」时参与默认名）。
>   项目目录与执行档分别住投影重建与执行绑定端口，不在编制命令上 —— 这条是既有模型，未改。

**P2 · 员工档案 + 编辑面（已落地）**

员工从「席位的聚合」升级为**本机档案**（§2.8 / §3.4），详情页加「登记为员工 / 编辑员工」
（桌面壳；手机不画）。价值：解决「想改个名字只能撤回重入驻」这个真痛点，并让「先建员工、
再决定进哪个群」成立。

> 与旧计划的两处差异（旧计划写的是「只做改名 / 改 @ token」）：
> - **职责**：档案新增 `duty`，改它是真写入（旧模型里席位没有 duty，`UpdateSeat.duty` 对既有席位是 no-op）。
> - **改名不动 `@` token**：token 在建档那一刻定下来，改显示名只改 `name`。
>   要换 token 必须走命令的显式参数（界面暂不给）—— 否则群里所有人的输入习惯会跟着断。
>
> 落地边界（如实留下）：
> - **员工级执行档不做**：现在没有消费方（执行档是团队粒度、按群存），加了就是死字段。
> - **手机端只读**：档案只住宿主机；手机端 `onEditEmployee == null` ⇒ 详情页不画入口。
> - `aliasedGroupIds`（哪些群用了本群别名）已在返回值里，但界面还没提示 —— 下一步。
> - 团队执行档仍按 `(groupId, dirId, leaderAgentId)` 存：团队跨群复用后，同一支队进三个群
>   要配三份执行档。这是 B 档留下的尾巴，独立于本轮，未修。

**P2b · 团队档案（B 档，已落地）**

团队从「群的一条属性」升级为**本机档案**（`TeamProfile`：`teamId` + 名字 + 名册），
一份名册多群复用、改名一次生效、群能整体添加一个团队。档案层 `TeamProfileWritePath`
（Create / Update / AddToGroups / Dissolve）+ 席位层 `AttachTeam` / `DetachTeam`。
落地边界：**团队的界面入口（新建 / 改名 / 加群）还没接**，卡片只会在有 `teamName` 时显示独立名。

**P3 · agent 写面**
`roster` 授权域 + 三个工具动作 + 审批卡 + 审计回执；默认 `request` 档。
价值：agent 能自己组队，这是「数字员工」与「聊天机器人」的分界线。

## 8. 明确不做

- 不把团队做成**可寻址成员**（独立会话流 / 派单目标）。团队档案（`teamId`，§10）只是名字与
  名册的容器：席位仍是它唯一的执行身份，派单仍只认领队与组员。
- 不做「全局员工库」：员工是**本机**档案（一台宿主的 `RelaySettings`），不是跨设备的第二份身份表；
  对端只从席位快照 `employeeName` 读到它。
- 不做跨宿主席位声明（只能声明本机 agent）。
- 不为 agent 写面新增协议帧：`group-info` 已能承载全部字段，改帧格式的代价远大于收益。
- 不做「自动团队推荐」（让 agent 自己决定编制，但那属于编排策略，不属编制写面）。

## 9. 与既有文档的关系

- 席位/团队的身份键与「团队不是独立对象」：见 `docs/tenon-agent-hub-and-team-orchestration-spec.md`。
- 团队编制的交互与真机形态：见 `docs/agent-hub-phase-b-final-plan.md`。
- 本方案只补「创建/编辑的入口与写路径」，不推翻上面两份的裁决。

## 10. 团队名与群名解耦（B 档已落地数据面）

### 10.1 事实

| 事实 | 判据 |
|---|---|
| 团队没有任何名字字段 | `VirtualAgentMember(agentId, hostNodeId, name, entityType, teamMembers, allowedWorkspaces, defaultWsId)`，`name` 是领队的 `@` token（`GroupMeta.kt:198-211`） |
| 团队卡标题 = 群名 | `AgentTeamUi.groupName`，注释「群名（团队对外的那一个窗口）」（`AgentTeamProjection.kt:45`） |
| 团队身份 = `(groupId, leaderAgentId)`，无 `teamId` | `AgentTeamProjection.kt:26-38` |
| 一个 agent 每群最多一支团队 | `planTeamRoster` 把同 agent 其它席位收敛成单兵（`GroupDeclarationWritePath.kt:562-577`） |
| 组员不是可寻址节点 | `TeamMemberInfo` 只有 `agentId/name/role`，**没有 `hostNodeId`**；执行宿主恒为领队宿主（`GroupMeta.kt:213-219`） |
| 执行点是**每群**事实 | `defaultWsId` / `allowedWorkspaces` 挂席位，随 `group.info` 广播（`GroupMeta.kt:188-196`） |

### 10.2 缺陷的三条后果

1. **组织名被聊天室名绑定**：改群名即改团队名。
2. **「测试团队」而不多一个群会话 = 无解**：每个团队必须占一个群。
3. **一个群只能一支团队**：开发团队与测试团队并存必须两个群。

### 10.3 三档方案（成本已评估）

**A. 只加团队显示名（最小）**
`VirtualAgentMember.teamName: String?`（纯展示名，**不进 `@` token 池**，可含中文），
团队卡标题 = `teamName ?: 群名`；创建团队时填，团队卡上可改（走 `SetTeamRoster` 或新增字段的 `UpdateSeat`）。

- 协议成本集中在**解码侧**：`virtualAgents` 数组**没有键白名单**，加键不会整帧拒收
  （`CodecGroup.kt:101-106` 明说不加字段白名单）；但 `group.info` 的席位解码是**手写逐字段**
  （`CodecGroup.kt:234-262`），漏一处就是「发送端有、接收端 null」——必须同时补解码 +
  `GroupInfoVirtualAgentsRoundTripTest` 往返断言，并确认 `@EncodeDefault` 下默认值不会被省略。
- 不动身份键、不新增对象、不动 `@` 解析（团队名不是 token）。

**B. 团队档案 + 名册引用（中期）**
宿主本机 `teams[]`（`teamId` + name + roster），群席位只带 `teamId` 引用，名册一份多群复用、改名一次生效。

- 新增：引用解析、名册冲突策略（A 群改名后 B 群是否跟随）、离线一致性（对端拿不到 teams 时回落群名）、
  跨群 `@` 查表。
- **注意：不省每群一条席位**。执行点（`defaultWsId`/白名单）是每群事实，团队进每个群仍要声明一条
  席位；B 省掉的只是「名册维护」，不是「进群」。

**C. 团队成为独立群成员（出现在成员表、有自己会话流）：不做。**
与「对外单一窗口：全群只认 leader 的 token，组员间交互走宿主内部通道，禁止向主群平铺中间碎片对话」
（`GroupMeta.kt:184-186`）直接对撞，且会把执行授权面摊开。

### 10.4 当前决定

**已按 B 落地数据面**（2026-11-12 裁决：直接按 B）。落地物：

- `RelaySettings.teamProfiles` + `TeamProfile(teamId, name, leaderAgentId, members, rev)`；
- `VirtualAgentMember.teamId` / `teamName`（协议两键 + 手写解码 + 往返断言 + 发送侧长度自检）；
- `TeamProfileWritePath.kt`：`Create` / `Update` / `AddToGroups` / `Dissolve` 四条命令，
  **先全量预校验、再落档案、再逐群挂席位**（每群各 rev+1 / 广播 / 一行审计）；
- 席位层仍走唯一写路径：新增 `AttachTeam` / `DetachTeam` 两条命令，
  席位不存在时**就地入驻**（「群里添加一个团队」是一次动作，不要求先逐个加人）；
- 收口：`SetTeamRoster` 对 `teamId != null` 的席位 **fail-closed 拒绝**
  （`TEAM_PROFILE_BACKED` + 可读横幅），两个入口不许写同一份名册；
- 团队卡标题 = `teamDisplayName(teamName, groupName)`（档案 → 快照 → 群名，三段兜底一处实现），
  有独立名时群名挂到副行（同一支队多群时唯一的区分线索）；
- 判据：`TeamProfileWritePathTest`（13 条，含界面入口 3 条）、`TeamProfileWriteGuardTest`（2 条）、
  `TeamProfileDialogTest`（2 条）、`TeamProfileWiringGuardTest`（3 条，两端接线 + 手机不接）、
  往返测试新增 2 条。

**当时仍是空的**：新建/改名团队的 **UI 入口**（团队卡「团队档案」与群成员面板的「添加团队」）。
数据面已可用（命令 + 判据齐），但界面上还没有调用点 —— 下一段补的正是这两处。

### 10.4.1 入口已接（同一轮补完）

上一段列的两个入口已落地，写路径一个字没改，只补了适配层与界面：

| 入口 | 落点 | 写路径 | 备注 |
|---|---|---|---|
| 新建团队 | 智能体页「团队」视角 · 段头「新建团队」 | `saveTeamProfileEntry(null, …)` → `Create` | 至少进一个群（不进群就没有席位，谁都看不到这支队） |
| 编辑团队 | 团队卡底行「编辑团队」（仅 `teamId != null`） | 先 `Update` 再 `AddToGroups` | 改名对所有引用群生效；群**只增不减**（移出某群是那个群的决定） |
| 添加团队 | 群信息 → 项目目录授权 → 「添加团队」段 | `attachTeamToGroupEntry` → `AddToGroups` | 一支队一次进群，组员随档案过来 |

三条纪律在这张表里能读出来：

1. **领队只在新建时选**。换领队 = 摘旧席位的 `teamId` + 给新领队建席位（两条命令），
   不是「改个名字」。编辑态把领队画成只读，而不是给一排点了不生效的 chip。
2. **档案只住宿主机**。手机壳不接 `onSaveTeam`（`TeamProfileWiringGuardTest` 钉住）——
   接上就是一颗「点了没反应」的按钮；手机端的团队档案池为空，群侧「添加团队」段自然不画。
3. **「已在的群」不可再勾**（`addableTeamGroups`，`TeamProfileDialogTest` 两条）：
   勾了只会得到一条重复席位（域层按 `teamId` 去重），界面上表现为「点了一下什么都没变」。

横幅口径：`TeamProfileSaved(name, groupCount, failedGroupCount)`。有群没写进去时走**警告位**
并点名失败群数 —— 绿色横幅会把「有一个群没成功」盖掉，用户下次打开那一群发现队伍不在，
却查不到任何痕迹。员工档案同理：`EmployeeProfileSaved(name, aliasedGroups)` 在存在本群别名时
点名是哪些群（用户在 A 群看到 `@dsh-coder-2`，不说出来就会被当成「档案没生效」）。


### 10.5 派单语义（支持 `@团队名`，逐成员可见性）

派单目标有三种：

1. `@<团队名>`：整队接单。`resolveTeamNameMention` 解析团队名 → `resolveTeamTarget` 编到执行档**唯一那个 worker**
   （其余 reviewer 照既有责任链走）。不必先 @ leader。带空白的团队名不参与解析（`@` 在空格处断开，打不出来）。
2. `@<领队 token>`：单席位路径，不变；
3. `@<组员 token>`：直接派给某个成员（`TeamMention` 的既有解析，长 token 优先、重名 fail-closed）。

**逐成员可见性（mulica 式）**：每个员工（组员）各有 `AgentExecPolicies` 档位
（`LIST`=仅自己 / 可信设备，`ANY_MEMBER`=群内任何人）。`AgentExecAuthorizer.authorize` 按 **agentId** 判，
不按宿主、不按领队席位。`@团队`/`@组员` 在 `runTeamDispatch` 里按 `target.member.agentId` 逐成员授权——
领队开放但 worker 仅自己可用 ⇒ 外部 `@团队` 仍被 `not authorized` 拦下；全员仅自己可用 ⇒ 整队对外部不可调。
无执行面 / 未配置 ⇒ 出 blocked 卡，**绝不**回落默认 codex 单棒（§2 的洞）。

UI：`EmployeeEditDialog` 的「谁能调用」段切换档位，经 `setAgentExecPolicy` 写宿主侧 `AgentExecPolicyStore`；
手机壳读不到策略整段不画。
