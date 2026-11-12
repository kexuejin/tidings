# Tenon 智能体双维解耦与插件体系架构演进规范（提案·待评审）
# (Two Dimensions of Agent & Plugin Architecture Evolution Spec)

> **状态**：提案 · 待评审（对齐 Phase B 最终方案）  
> **前序与基线规范引用**：
> - `docs/agent-hub-phase-b-final-plan.md`（2026-11-11 最终裁决：Phase B 卡片流、IA 实体键 `(hostNodeId, agentId)`、硬边界「零协议零 caps 零 .sqm」）
> - `docs/tenon-agent-hub-and-team-orchestration-spec.md`（智能体中心与编排层契约）
> - `docs/unified-chat-and-agent-interaction-architecture.md`（统一聊天与 Agent 交互架构）
> - `docs/tenon-agent-hub-spec-review.md`（智能体规格评审记录与反思）
>
> **核心原则**：本规范作为 Phase B 的**增量演进方案**，在严格遵守**「零协议变动、零 caps 变动、零 .sqm 迁移」**三原则的前提下，解决真机环境中暴露的“跨群串发”、“强制建群税”、“存量 CLI 伪插件秒退”以及“掌上接力体验割裂”四大具体问题。

---

## 一、代码事实核验与四大病根诊断

经对照代码库实测核验，当前相关逻辑的准确事实与病根如下：

### 1. 跨群串发（`AgentEventForwarder.groupsForSession`）
* **代码实况**：
  在 `orchestration` 模块的 `AgentEventForwarder.groupsForSession` 中，实时投递与离线补发均以此为唯一真源：
  ```kotlin
  return state.settings.groups.filter { group ->
      val desc = group.description?.trim().orEmpty()
      (path.isNotEmpty() && desc.contains(path)) ||
          (id.isNotEmpty() && (desc.contains(id) || group.name.contains(id))) ||
          (title.isNotEmpty() && group.name.contains(title)) ||
          (path.isNotEmpty() && group.name.contains(path.substringAfterLast('/')))
  }
  ```
* **问题本质**：
  基于路径、ID 和标题的子串反查属于全量模糊多播（Fan-out）。若同一个项目目录关联了多个协同群，事件会无差别复制到所有群，导致灾难性串群。
* **既有武器**：
  仓库内**早已存在 `SessionConversationLedger` 与 `AgentSessionRegistry.bindConversation`**，`conversationOf(sessionId)` 在本地持久化并记录了会话与会话 ID（`convId`）的明确绑定关系。

### 2. 强制弹窗收“建群税”（`App.kt` 与 `DesktopShell.kt`）
* **代码实况**：
  在手机端 `App.kt` 与桌面端 `DesktopShell.kt` 的 `onOpenChat` 中：
  当用户点击工作区行的聊天按钮时，若该工作区未绑定群，系统会触发 `groupPromptFor = ws`，弹起 `WorkspaceGroupPromptDialog` 引导建群。
* **问题本质**：
  虽然弹窗内提供 `onOpenWithoutGroup` 允许直接进 1:1，但**前置拦截弹窗**严重破坏了“直接找 Agent 说话/回复”的即时通讯直觉；而在桌面端甚至曾引导进入建群流程，抬高了单 Agent 交互的门槛。同时 `FirstProjectCardGuardTest` 对新手卡建群回调有断言约束，改动必须保持双端壳行为自洽且不破坏守卫测试。

### 3. CLI 工具伪装为常驻插件的“存量自愈漏洞”
* **代码实况**：
  `BuiltinPluginSeeder.seed()` 逻辑具有幂等性（仅在目标目录不存在时才写入清单）。`PluginHost` 虽已具备 `watchForImmediateExit` 能够记录退出码与错误到 `process.log`，但若仅从种子清单删除 `cli-*`，**老用户本地已存在的 `cli-dsh/manifest.json` 仍会在每次启动时被拉起并秒退**，无法达成存量环境自愈。

### 4. 首屏接力摘要的投影依据
* **代码实况**：
  `AgentSessionUi` 实体中**并不存在 `summary` 字段**，真正的最新事件文案存在于 `AgentEventUi.summary` 中。Phase B 卡片流已消费此字段。任何试图向 `AgentSession` 增加协议字段的做法都会破坏跨端白名单与帧预算。

---

## 二、架构澄清：四类东西，三个入口，不要混

名字里都带「DSH」，但在系统里它们是**四种不同地位的东西**。混在一起谈是上一版把 UI 与配置画乱的根源：

| 类别 | 是什么 | 代码实体 | 归属入口 | 生命周期 |
| :--- | :--- | :--- | :--- | :--- |
| **员工（智能体）** | 一个有名字、有人设、能干活的主体；可同时归属多个团队 | `VirtualAgentMember`（作为 leader 时）/ `TeamMemberInfo`（作为组员时） | 智能体页（首屏卡片流） | 长期，身份不随团队增减而变 |
| **团队** | 一个 leader + 组员清单，对外单一窗口（@ 只认 leader token） | `VirtualAgentMember.entityType = "team"` + `teamMembers`；**不是一个独立对象，没有 teamId** | 智能体页的团队视图 / 群设置 | 随群，一个群可有多条团队席位 |
| **底座（执行器）** | 此刻由谁干活 + 用什么模型：dsh / claude / codex / agy / opencode | `DriverKey` + `TeamExecutionMemberSpec(driverKey, models)`；能力清单来自 `AgentDriver.listCapabilities()` | **执行配置**（独立入口） | 可随时更换 |
| **通道（桥）** | 跨端接力的搬运工：把电脑上的会话进度搬到手机、把你手机上的回复带回电脑 | `dsh-tenon` 外部桥（Node 侧插件，轮询本机控制面 `GET /v1/inbox`，按 `agentHint.targetSessionId` 注入**既有** DSH 会话） | **接入与通道**（设置内） | 常驻进程，不是驱动 |

四条判断依据（都能在代码里指出来）：

* **团队不是群，也不是员工的下级**：它是 `VirtualAgentMember` 的一种形态（`entityType = "team"`，`isTeam` 同时接受「声明是团队」或「组员清单非空」）。一个群可以有多条团队席位——`TeamMention.resolveTeamMemberToken` 专门有 `Ambiguous` 分支处理「token 命中多个席位」。所以团队键只能是 `(groupId, leaderAgentId)`，拿 `groupId` 当团队键会在多团队群里撞车。
* **能力属于底座，不属于员工**：`listCapabilities(): List<DriverCapability>` 是 `AgentDriver` 的成员，`CliSubprocessDriver` 报的是 `cli-task`。所以「这个底座能干什么」应挂在底座那一行，而不是员工卡上。
* **通道不是底座**：`dsh-tenon` 不实现 `AgentDriver`、不进 `resolveCliDriver`，它是轮询 `/v1/inbox` 的外部进程（控制面固定 8788，与桥的 `baseUrl` 默认值必须一致）。把它当「插件式执行器」会得到一个永远在 `pluginPresence=OFFLINE` 的假驱动。
* **通道的产物不是配置，是会话**：它做成了，用户看到的是「手机能看见并回复桌面那条会话」，而不是某个开关被打开。所以它的 UI 面是 1:1 会话里的「接力」标识 + 详情页的通道健康，**不占配置页的主位**。

> 一处必须写死的事实差异：**dsh 底座不能按次指定模型**。`dsh --profile headless --help` 没有 `--model`，模型写在 profile 的 `agent-default-model` 插件配置里（`deepseek-v4.1-flash`）。所以员工选 dsh 时模型选择器必须不可用，保存必须拒——不能「存下来但跑默认模型」。

---

## 三、五条演进不变量 (Evolution Invariants)

本规范制定五条硬性不变量：前四条在**零协议改动**约束下达成，第五条（员工 / 团队 / 底座解耦）的落地只碰宿主本地执行档与驱动参数，同样不动帧。

### 不变量 1：复用 `SessionConversationLedger` 根除串群（零协议变更）
* `AgentEventForwarder.groupsForSession` 彻底废除基于 `wsPath` / `wsTitle` / `wsId` 的 `contains` 模糊多播；
* **统一收敛为台账精准查询**：调用 `SessionConversationLedger.conversationOf(sessionId)` 获取唯一目标 `convId`：
  - 若对应群会话存在，仅投递至该特定群；
  - 若无会话绑定，执行 **Fail-Closed 阻断**，记录 `logEventRoute("session", sessionId, 0L, 0, "no-conv-bound")`，坚决不向任何群做模糊广播；
* 补充双群防串扰回归测试：同仓库建两群，产生事件断言仅投递绑定群。
* **迁移前置（不做就会从「串群」变成「不说话」）**：`bindConversation` 的写入点并非覆盖所有路径（历史会话、对端建的群、重启后恢复的会话都可能是空的）。改成 fail-closed 之前必须先做一次自检：对每个已知群会话跑 `conversationOf`，未命中的逐条记 `logEventRoute(..., "no-conv-bound")` 并在「接入与通道」页给出可操作提示 —— 否则用户看到的现象只是「agent 突然不回话了」。自检本身要有正向样本（至少一条已绑定会话命中并投递成功），否则又是一次空集假绿。

### 不变量 2：自由 1:1 直达，保留建群入口（零门槛交互）
* 在 `App.kt` 与 `DesktopShell.kt` 中重构 `onOpenChat`：
  - 点击 Agent 卡片或工作区聊天动作时，**默认直接导航至 1:1 会话**（`openWorkspaceChat` 或 `openSession`），取消前置阻塞式建群弹窗；
  - 保留 `WorkspaceGroupPromptDialog` 与 `onBindFirstProject` 的独立入口（例如通过顶栏专设的“+ 创建项目协同群”或新手引导卡触发）；
  - 更新 `FirstProjectCardGuardTest` 的断言契约，确保双端壳与源码守卫同步翻绿。

### 不变量 3：CLI 驱动纯粹化与存量自愈迁移
* **种子层停用**：在 `BuiltinPluginSeeder` 中将纯 CLI 工具从默认常驻清单中解绑；
* **存量自愈机制**：在 `BuiltinPluginSeeder.seed()` 或 `PluginHost` 初始化阶段加入一次性清理/停用迁移，扫描并停用历史遗留的 `cli-dsh`、`cli-agy`、`cli-codex` 伪常驻清单（置为 disabled 或移除），避免已安装环境持续秒退刷屏；
* **正向执行验证**：CLI 工具统一由 `AgentProfiles` 与 `WorktreeSandbox` 在沙箱分支内按需拉起，验收时必须包含 CLI 实际产出 Patch 的正向断言。

### 不变量 4：首屏接力基于既有 `AgentEventUi.summary` 投影（零协议变更）
* 不新增任何协议字段，严禁触碰严格键白名单与整帧预算；
* 首屏活动看板直接消费既有的 `AgentEventUi.summary` 与 `at` 时间戳；
* **新鲜度保护机制**：根据 `at` 时间戳判断会话新鲜度；若事件陈旧或对端离线，不展示“立即回复”引导，避免给用户造成误导。

### 不变量 5：员工是主体，团队是归属关系，底座 / 模型是员工的绑定

**这是本规范的一等模型**（编号置于末位只为不扰动前四条引用；优先级最高，前四条都服从它）。智能体页面呈现的主体是**员工**；团队是员工的**归属**（一个员工可归多个团队）；执行器（底座）与模型是**员工身上可换的绑定**。

| 层 | 是什么 | 稳定键 | 现有承载 | 可变性 |
| :--- | :--- | :--- | :--- | :--- |
| **员工** | 有名字、有人设、能干活的主体 | `(hostNodeId, agentId)` —— 沿用 Phase B 实体键，**不另造第三个键** | leader 时是 `VirtualAgentMember`；组员时是 `TeamMemberInfo` | `agentId` 不变（`AgentMemberName` 契约：改名不重算） |
| **团队** | 一个 leader 席位 + 组员清单 | `(groupId, leaderAgentId)` | `entityType = "team"` + `teamMembers` | 领队可换、成员可增减 |
| **归属** | 员工 × 团队 的多对多；每团队一份 `name` / `role` | `(hostNodeId, agentId, groupId, leaderAgentId)` | 各群声明里逐条出现（可导出，无需新字段） | 可加可退 |
| **执行绑定** | 底座 + 模型 | 员工级默认 + 团队级覆盖 | 员工默认存本机台账；团队值存 `TeamExecutionProfile.members` | ✅ 可换 |

两条键的口径必须先说死：

* **组员的宿主就是 leader 的宿主**——这不是推断，是执行事实：`TeamExecution.resolve` 要求 `seat.hostNodeId == selfNodeId`，组员只能由 leader 所在宿主执行；`TeamMemberInfo` 协议里本来就没有 `hostNodeId`。所以「组员的 `(hostNodeId, agentId)`」取 leader 的 `hostNodeId`。
* **员工不是团队的属性**：`agentCount` / `groupCount` / `teamSize` 这类计数在 `AgentHubUi` 里是员工视角的聚合，方向是「员工 → 它任职于哪些团队」，不是「团队 → 它拥有哪些员工」。

#### 5.1 多团队归属与「默认团队」

你问的两种说法（「支持多个团队」与「先归属默认团队」）**不是二选一，而是主模型 + 入职落点**：

* **多对多成立**：同一 `(host, agentId)` 可以在 A 团队当组员、B 团队当组员、C 团队当 leader。三种形态都只是各群声明里的一条记录，打开 `group.virtualAgents` 即可导出，**零协议**。
* **员工不能零团队存在**：协议里没有「独立于团队的员工」容器——`name` 是团队内 @ token、`role` 是团队内角色。所以「已接入未任职」只是本机投影（有工作区宣告/会话，但没有任何席位），不是一个可编辑实体。
* **默认团队 = 入职落点 + 兜底解析，不是排他归属**：
  - 把一个已接入 agent 变成员工时**必须选一个团队**，这一条即默认团队，卡面主名与主角色取它；
  - 默认团队记在宿主本地台账（不进帧），手机端由「最近活动的团队」兜底并在 UI 标注来源；
  - 新增归属不改变默认团队；只有移除默认团队那条归属时才需重选，此时**不自动挑**（fail-closed，卡面显示「未设默认团队」）。
* **退役 ≠ 删除**：员工退出一个团队只是删掉那条归属；最后一条归属被移除后回到「已接入未任职」，身份（`agentId`、人设、默认底座）保留。

#### 5.2 执行配置：一个真源 + 一层覆盖，不做两份

底座与模型挂在员工上，但执行档是团队粒度的（`TeamExecutionProfile` 键 `(groupId, dirId, seatName)`）。为避免「员工在两个团队里底座不一致却没人知道」，规定：

* `TeamExecutionMemberSpec(agentId, kind, driverKey, models, customPrompt)` 本来就是「员工 + 可换绑定」的形状：派单取 `agentKey = hitSpec.driverKey`，`agentId` 只是稳定 slug；`TeamExecutionValidation` 已按 `AgentProfiles.vendorOf` 校验 worker / reviewer 异厂商。所以这层不是新设计，缺的只是**员工级默认**与**归属可见性**。
* **员工级默认**存本机台账（`(hostNodeId, agentId) → driverKey / models`），只有在团队没配时才生效；
* **团队级覆盖**写在执行档里，UI 上必显「本团队覆盖」标记，未覆盖的行显示「继承 · claude」；
* 读配置只有一条路径：团队有值取团队值，否则回落员工默认——**不允许两处各写一套回退链**（本仓已有「同一值两处各写回退链必不一致」的教训）。

#### 5.3 团队的三条能力边界（诚实声明，别在 UI 上画过头）

1. **leader 今天不做运行时决策**：团队路径是编译期模板——`TeamPlanCompiler` 要求恰好 1 个 worker、其余 reviewer，链为 `[worker] + [reviewers…]`，并显式 `fromTemplate = true` 跳过 LLM planner。所谓「leader 分配任务」，现状是「按执行档里写死的分工依次执行」，不是 leader 看需求自行拆活。
2. **责任链分派的单位是驱动，不是员工**：`ChainStage.agentKey` 是 `resolveCliDriver` 的入参（驱动键），`role` 源码注明「展示用角色名，不参与路由」。所以「派给墨查这个人」在链里只体现为「用 claude + 注入墨查的 `customPrompt`」。要让卡面显示「谁干的」，最小改动是给 `ChainStage` 加**展示用** `memberAgentId`（调度属性，不进帧，与 `requiresIsolation` 同款宽松读取），而不是新造一条员工路由。
3. **@ 组员直达会绕过 leader**：`resolveTeamMemberToken` 命中组员时编译成该组员的单棒（审查员则纯审查）。这是既有交互（点谁谁干），不要因为「有 leader」就禁掉它。

**把「员工」与「底座」焊死的地方，复核后只剩一处（此前列为三处，两处已推翻/已落地）：**

1. **单兵席位没有执行配置层**（✅ 已落地）：`initialExecutionProfileUi` 在 `seat.teamMembers` 为空时直接 `return null`，单兵的执行器退化为 `agentId` 本身 ⇒ 换底座 = 换席位 = 丢编制。
   已改为：单兵也生成一条 worker 条目（`agentId` 仍是席位 slug，`driverKey` 留空由用户显式选择，**不猜**）。
2. **~~执行档主键含 `leaderAgentId`，换领队即换键~~ —— 此条作废（2026-11-12 复核推翻）**：第三键的实参是 `seat.agentId`（`initialExecutionProfileUi`、`TeamRosterDialog`、`TeamExecutionResolver.resolve` 三处都是它），而 `VirtualAgentMember.agentId` 是**席位身份**（`(hostNodeId, agentId)` 二元组的一半），不是驱动键。「换领队」= 换了一个席位（另一个人），本来就该是另一份配置；「换底座」只改 `driverKey`，`seat.agentId` 不变 ⇒ **主键根本不动**。改成 `seatName` 反而会让「席位改名」丢配置，是一次纯粹的可用性倒退。
3. **~~`declarationFor` 按 `name` 找 `previous`~~ —— 此条作废（2026-11-12 复核推翻）**：`handleAddGroupAgent` 的入参里**没有名字**，名字是被推导出来的（`previous?.name ?: AgentMemberName.base(kind, agentId, duty)`），所以「按名字找回 previous」在这条路径上无法实现；而它真正解决的是「换 agentId 后编制被清空」——那个场景本来就是**换人**（一个人退出、另一个加入），重建编制是正确行为。
   正确表述：**换底座只改执行档里的 `driverKey`，绝不动席位的 `agentId`**。补上单兵执行层之后，「换底座」= 改一个配置字段，席位、名字、编制一律不动。

**并且：补一条执行层不等于生效（✅ 已落地）。** 派单入口是 `dispatchSingleWorker(convId, groupId, prompt, agentKey, wsId)`，`agentKey` 直接来自会话/人员解析。现已让派单入口先查执行档取 `driverKey`（`seatExecutionOverride`），查不到才回落 `agentId`——**顺序必须是「先覆盖、后归一」**：席位全名 `dsh-<host>-<ws>` 一经 `AgentProfiles.resolveKey` 归一成档案键，席位 slug 就没了，再查执行档必然查不到。团队派单传的是编译后的 `driverKey`（本就是档案键），因此不会被二次改写。

**第四件事：模型这一维本轮做实，不留「存了不生效」（✅ 代码已落地，UI 待接）。** 原状是 `models` 没有任何 UI 能写、派单侧也从不读它——`CliSubprocessDriver` 的启动参数在装配期就固定成 `argSpec(prompt)`，模型根本没有入参。四个点，一个都不能少：

1. **唯一旗标词表** `DriverModelFlags`（:agent-contract）：claude `--model` / codex `--model` / agy `--model` / opencode `--model`；**dsh 缺席**（模型写在 profile 的 `agent-default-model` 配置里，命令行没有旗标）。缺席即不支持，禁止写「大概都支持」的默认值。
2. **驱动侧入参**：`AgentDriver.withModel(model)` 默认返回 `this`（契约不假设每个驱动都能选模型）；`CliSubprocessDriver` / `SandboxedCliDriver` 返回带模型的新实例，模型进**首跳与续跑跳**两处参数。不支持却收到非空模型 ⇒ `DriverEvent.Error`，**不许静默忽略**。
3. **派单瓶颈点透传**：`ControlPlane.executeCliTask` 加 `model` 入参，在 `resolveCliDriver(agentKey)` 之后 `.withModel(model)`——它是全仓唯一的 CLI 执行出口（链棒与单棒共用），改一处即全覆盖。
4. **上游带值**：`TeamPlanCompiler` 把成员的 `models.firstOrNull()` 带进 `Compiled.Single` 与 `ChainStage`；`DesktopStageChain.runOneStage` 原样传给 `executeCliTask`；工单把这值存进 `ActiveTicket.model`，原型/微调/开发循环三处调用都带它。

两个实现坑（都已踩过，别重犯）：**codex / opencode 的 prompt 是末尾位置参数**，`--model` 追加在 prompt 之后会被吞进提示词，旗标必须插在 prompt 之前；**沙箱驱动的裸路径委托实例 id 是 `claude-cli-plain`**，拿它查词表会把合法的 claude 判成不支持，必须用基键 `modelKey`。

配套的保存期约束（✅ 已落地）：`TeamExecutionValidation` 对「驱动不支持却填了模型」与「`models` 多于一项」都给结构化拒绝。前者是能力事实；后者是为守住「存下来就必须生效」——多值本期没有消费路径，允许它等于又造一批死数据。UI 侧只做「多值」这一条结构前置，**驱动能力不在 UI 判**：同一份词表抄两处必错一处。

**两条安全约束（不可省）**：

* 切换执行器时**复用** `TeamExecutionValidation` 的异厂商判据，禁止另写一份词表（本仓已有词表对账守卫）；把 worker 从 claude 换成 dsh 而 reviewer 也是 dsh，会让审核独立性静默失效。
* 旧执行者的 `AgentGrantExecutionScope` **不自动继承**：换人后需显式重授，补守卫断言旧 grant 对新执行者不生效。

### 5.4 落地状态（2026-11-12）

| 段落 | 状态 | 判据 |
| :--- | :--- | :--- |
| 模型旗标词表 `DriverModelFlags` | ✅ | `DriverModelFlagsTest`(7)，反向探针：词表误加 dsh ⇒ 3 红 |
| 驱动侧 `withModel` + 模型进 argv | ✅ | `CliModelSelectionTest`(5)，反向探针：`withModel` 返回 `this` ⇒ 红 |
| `executeCliTask(model=)` → `.withModel` | ✅ | `ModelWiringGuardTest`，反向探针：摘掉 ⇒ 红 |
| `Compiled.Single` / `ChainStage` 带模型 | ✅ | `TeamModelPropagationTest`(4)，反向探针：摘掉 reviewer 那棒 ⇒ 红 |
| 工单四类 CLI 调用都带模型 | ✅ | `ModelWiringGuardTest.ticketsKeepTheirModelForEveryCliCall` |
| 单兵执行层 + 派单先查执行档 | ✅ | `initialExecutionProfileUi` 单兵分支 + `seatExecutionOverride`；`ModelWiringGuardTest.singleSeatOverrideFeedsTheTicket` |
| 保存期两条拒绝 | ✅ | `TeamExecutionModelValidationTest`(5)，反向探针：各摘一条 ⇒ 对应用例红 |
| 员工**归属**投影 | ✅ | `agentAssignments()` + `AgentAssignmentProjectionTest`(7)，反向探针：删组员分支 ⇒ 红 |
| 执行档主键改 `seatName` | ❌ **撤销** | 见 5.3 第 2 条：第三键就是席位身份，换底座不动它 |
| 模型的**写入面**（执行配置行里「模型」一格） | ✅ | `TeamRosterDialog.ExecutionConfigRow`；不支持/多值由保存期拒并把原因显示在同一行 |
| 不变量 1：台账优先 + 三键唯一解 | ✅ | `AgentEventRoutingBehaviorTest`(6)，反向探针：摘台账层 ⇒ 3 红；放宽歧义 ⇒ 1 红 |
| 不变量 2：点聊天直接进 1:1 | ✅ | `FirstProjectCardGuardTest.openingAChatNeverBlocksOnTheGroupPrompt`，反向探针：shell 里写回 `groupPromptFor` ⇒ 红 |
| 不变量 3：CLI 伪插件停用 + 存量自愈 | ✅ | `BuiltinPluginSeederTest`(6)，反向探针：`retire` 直接返回空 ⇒ 4 红 |
| 不变量 4：首屏「最新活动」摘要 + 新鲜度 | ✅ | `ActivityFreshnessTest`(5) + `agentActivityFreshness`：24h 之外写「最后活动」、对端离线写「离线 · 最后活动」、无活动整句不画；RECENT 行渲染 `AgentHubStatusRow.summary`。反向探针：摘掉 `!online` 分支 ⇒ 红；窗口设成恒 FRESH ⇒ 红 |
| 团队视图（团队卡 + 员工/团队切换） | ✅ | `agentTeams()` + `AgentTeamProjectionTest`(7)；反向探针：按 `groupId` 去重 ⇒ 「一个群两支队伍」变红；待批归因兜底到第一个群 ⇒ 变红。切换器只在**有团队**时出现 |
| 员工详情「任职」段 | ✅ | `AgentHubDetailUi.assignments`（复用 `agentAssignments()`，零新规则）；主团队标「推断」——默认团队台账本轮没落盘，见下方 §5.5 |
| 执行配置页 | ✅（**只读矩阵**） | `executionMatrixRows()` + `ExecutionMatrixProjectionTest`(8)；反向探针：去掉本机过滤 / 在位判据写成「非空即在位」/ `overridden` 恒 false，各变红。写入口仍只有群编制一处（§5.5） |
| 接入与通道页 | ✅ | `AccessChannelPort`（宿主只读端口）+ `accessChannelUi()` 投影 + `SettingsAccessChannelSubpage`；三类数据：桥的注入判定台账（`InboxInjectionTrace`，控制面唯一写入）、本机信任库的 pin（`NodeRuntime.pinOfPeer`）、事件路由计数（`EventRouterDiagnostics.snapshot()`，转发侧唯一写入）。判据 `AccessChannelProjectionTest`(13)/`InboxInjectionTraceTest`(14)/`AccessChannelHonestyGuardTest`(11)。**桥没有在线灯**（词表里根本没有 `set_access_bridge_online/offline`）；`targetSessionId=null` 逐条给成因（`InboxUnresolved` 五个 token 全覆盖，其中「未绑工作区却没投成」报的是 `no-unbound-session` 而不是 `no-bound-workspace` —— 未绑定本身不是失败原因，用户要做的动作是「去开一个不带目录的会话」而不是「去绑一个目录」）；出站成因同理（`EventRouteReasons.BLOCKING` 五档全覆盖，未知 token 原样显示）。手机端 `accessChannelPort=null` ⇒ 桥块与路由块各留一句「在电脑上，读不到」、配对块整块不画 |

**关于「先改图纸再写 Compose」的一处更正（2026-11-12 实测）**：`design/design-spec.json` 里的
`tokens` 是**从代码投影**出来的（`design-spec-project.py --write`，门禁用 `--check` 判漂移），
不是手写源。所以顺序不是「先手改 spec 再写码」，而是：写码 → 跑 `:designSpecProject` 重投影 →
review diff；`prototype/relay.html` 只在**新增屏 / 新增状态槽**时才需要动（状态槽是覆盖率的
分母，改动会连带 `designStateSlotGate`）。本次 UI 增量（执行配置行的模型格）属于既有屏内的
控件，不动原型与状态槽，六个静态门禁已过。

**下一轮（未做的三块，按依赖排序）**：① 员工卡片流（把 `AgentHubUi` 的归属投影
`agentAssignments()` 画成一卡一人，并把「最新活动一句话」接上 —— 不变量 4 的落点）；
② 团队视图切换 + 员工详情「任职」区；③ 设置内「接入与通道」页（桥不装在线灯，读
`EventRouterDiagnostics.snapshot()` 与 `/v1/inbox` 的 `AgentInboxHint` 统计）。
三块都要先在 `prototype/relay.html` 加状态槽、补 `@Preview`，再跑 `designStateSlotGate`。

**2026-11-12 第二批落地（本轮）**：① 员工卡片流与团队视图已成型；② 不变量 4 落到 RECENT 行；
③ 执行配置页落地为**只读矩阵**；④ 接入与通道页落地，数据来自新增的**宿主只读端口**
（`AccessChannelPort` → `InboxInjectionTrace` + `pinOfPeer`），不改任何协议帧。

接入页那次「宁可先不画」的判断是对的：真正卡住的不是缺一个 Composable，而是**没有一条
宿主→客户端的读取通路**。补端口的形状与 `TeamExecutionPort` / `HostDirectoryPort` 一致
（`:app` 定义接口，`:orchestration` 装配实现，手机端为 null ⇒ 只画读得到的那一块）。
两条经验值得记住：

* **台账必须由写入方持有**（`ControlPlane.inboxTrace` 单实例），端口只读它。壳里另建一份
  的症状是「桥在干活、页面说没判定过」——守卫 `AccessChannelHonestyGuardTest` 钉住这条。
  「只此一份」**不靠守卫**：`InboxInjectionTrace` 的构造器是 `internal`，`orchestration`
  那侧根本造不出第二份 —— 能交给编译器的，就别留在断言里。
* **「桥没在跑」与「没有 @ 消息」必须分开**：所以除了逐条判定，还要记**取件本身**
  （`recordPoll()`），否则两种状态在页面上长得一模一样。三档判据（未取件 / 取过但空 / 有判定）
  收在 `bridgeBlockStateOf()` 这个纯函数里 —— 判据住在 Composable 里就只能靠源码字符串守，
  格式化或改名即假红；沉到 `:app` 才能直接写单测。

### 5.5 本轮的三个取舍（都是「少写一份实现」而不是「少做一件事」）

1. **执行配置页只读**。规范 §4.0 原写「桌面可写」，但写执行档要连「校验 → 结构化拒绝 → 落盘」
   三件事，而这三件事已经完整地住在群编制对话框里。在这页再开一套下拉，等于把同一套规则写
   两遍 —— 本仓为「同一规则两份实现」付过的代价（两处回退链必不一致）比「少一次点击」贵得多。
   所以这页只摊**生效值**（含「本团队覆盖 / 继承」与「在位 / 不在位」两对标签），底部指路到群编制。
2. **团队卡点卡落到领队的员工详情**。团队不是独立对象（协议里没有 teamId），没有可下钻的
   团队实体页；「这队是谁在带」就落在领队那张员工卡上。凭空造一个团队实体才是编身份。
3. **主团队标「推断」**。默认团队要记本机台账（`(hostNodeId, agentId) → groupId`），本轮没有
   落这份持久化；`primaryAssignment()` 给的是「领队优先」的明说规则，UI 因此必须标「推断」。
   把推断当权威，用户会以为改过就生效了。

**一个必须记下的坑**：群**没有项目目录指针**时，`initialExecutionProfileUi` 返回 null。矩阵
如果照它早退，整支团队会从执行配置页里消失（用户以为席位没了）。正确做法是退回**声明层**
（`declaredProfileUi`，`driverKey` 恒空 + `editable=false`）——「没法改」不等于「不存在」。


`orchestration` 全量 297 例、`:app` 定向 7 例均真执行通过（已清 `test-results`，非 UP-TO-DATE 假绿）。

---

## 四、UI 与交互落地：员工卡片流 + 独立的执行配置页

### 4.0 三个入口，各管一件事

底座与模型是**宿主本地配置**，不是某个页面上的临时状态；把它们摊在每张员工卡上，等于把同一份配置画 N 遍，窄屏还会挤成三个下拉。所以入口按「谁在哪儿改」切开：

| 入口 | 位置 | 回答的问题 | 谁可写 |
| :--- | :--- | :--- | :--- |
| **智能体（卡片流）** | 主导航页 | 有哪些员工、各自在哪些团队、现在什么状态、有没有要审批的 | 无（只读 + 回复） |
| **执行配置** | 智能体页右上角（⚙ 之后的第一项，或设置内同名子页） | 每名员工由哪个底座执行、用哪个模型；这台机器装了哪些底座、各自支持什么 | **仅桌面** |
| **接入与通道** | 设置内（见 4.8） | 谁在跟这台机器说话：已配对宿主、外部桥（`dsh-tenon`）、链路与注入健康 | 桌面 + 手机（配对属手机主路径） |

**「执行配置」页的形状**（一张矩阵，不是一堆表单）：

```
执行配置                                     这台电脑 · 2 个底座在位
─────────────────────────────────────────────────────────────
项目协同群
  墨查 · 质检审查员   底座 继承 · claude ▾  模型 继承默认        ● 在位
  小榫 · 架构领队     底座 未指派 ▾         模型 —              ○ 未指派
deepseek 群
  阿栞 · 全栈开发     底座 claude ▾（本团队覆盖） 模型 opus-4-1  ● 在位
─────────────────────────────────────────────────────────────
员工默认（只在团队没配时生效）
  墨查 → claude · 继承驱动默认        [ 改默认 ]
  阿栞 → claude · opus-4-1            [ 改默认 ]
─────────────────────────────────────────────────────────────
底座能力
  DSH      cli-task · 支持模型 ✗（模型写在 profile 配置里）· 续跑 ✓
  Claude   cli-task · 支持模型 ✓ · 沙箱 ✓
  Codex    cli-task · 支持模型 ✓ · 沙箱 ✓ · 续跑 ✗
```

* **行是「团队 × 员工」，不是「员工」**：执行档本身就是团队粒度的（key `(groupId, dirId, seatName)`），所以同一员工在两个团队各占一行，这正好让「在本团队换个底座」成为本地操作；
* **未配的行显式写「继承 · <值>」**，改过才标「本团队覆盖」——不这样标，用户永远不知道这一行到底生效的是哪份配置；
* **员工默认只在此页改一次**，生效顺序唯一：团队有值取团队值，否则回落员工默认（见 5.2）；
* 「底座能力」块的数据来源只有一个：`AgentDriver.listCapabilities()` + `DriverModelFlags.supports(key)`，**不另维护一份能力表**；
* 驱动不支持模型时，模型格显示 `—` 并就地说明原因（不让它变成可点但无效的下拉）；
* 这页只写宿主本地执行档（`TeamExecutionProfile`），**不进 protocol 帧**——手机端进这页是只读视图，写操作标注「在电脑上修改」；
* 首屏卡片因此**不再放任何下拉**，只留一行只读徽标（「由 DSH 执行 / 未指派」），点它进这页。这也顺带解决了 360dp 下「一卡三个控件」的挤压（`designDpDebtGate` / `designFormPairingGate`）。

### 4.1 实体键：员工 = `(hostNodeId, agentId)`，与 Phase B 一致

上一版曾把首屏实体改成 `(groupId, name)`，**那是错的，本版撤回**：员工可归属多个团队，用「群内名字」当主键会让同一个人在 A 团队、B 团队变成两张卡，还会让「换团队」看起来像新建了一个员工。

* 首屏列表实体 = **员工**，键 `(hostNodeId, agentId)`——就是 Phase B 的实体键，**不需要取代记录**；
* `name` / `role` 降级为**每团队一份的本地属性**（协议里它们本来就挂在席位上）；
* 团队作为**可切换的第二个视图**，键 `(groupId, leaderAgentId)`；
* 对 Phase B 的唯一增量：卡上的 `groupCount` / `teamSize` 从纯计数升级为可展开的**归属列表**，数据来源不变、零新字段。

### 4.2 为什么团队不能画在员工卡里

* 团队是**独立视图**：同一团队要展示成员表与领队，而同一员工又要展示任职列表，两个方向都需要，塞进一张卡只能选一个方向；
* `AgentBoardContainerGuardTest` 明确 Agent 层不得再出现 `LocalCardShape.current` / `relayCardShadow()`（「再套卡就变成三层并列盒子」），工作区行**必须** `InboxUnitRow(`，且不得自造 `BoardWorkspaceRow`。卡内再画一个带框区块即撞守卫。

### 4.3 首屏：员工卡片流 + 团队视图

```
智能体                                [ 员工 · 团队 ]   ← 视图切换，不是上下两段
1 台设备 · 3 名员工 · 2 个团队

┌──────────────────────────────────────────────────────────┐
│ 墨查 · 质检审查员                  ● 由 DSH 执行    领队 │
│ ⚠ 2 条待审批 · 项目协同群                                │
│ 最新活动 · 3 分钟前                                       │
│ “已完成对 3 个迁移文件的审查，建议补充单测”                │
│ 任职 2 团队 · 主团队 项目协同群        模型 继承默认      │
│ [ 回复 ]                                       详情 ›    │
├──────────────────────────────────────────────────────────┤
│ 小榫 · 架构领队                              ○ 未指派     │
│ 任职 1 团队 · 主团队 项目协同群                          │
│ [ 去指派 ]                                     详情 ›    │
└──────────────────────────────────────────────────────────┘

待入职 · 已接入未任职   DSH（这台电脑）  claude（这台电脑）  ›
```

切到「团队」视图：

```
┌──────────────────────────────────────────────────────────┐
│ 项目协同群 · 团队                      2 人 · 领队 墨查   │
│ 组员 小榫（架构领队）                                     │
│ 最近活动 3 分钟前 · ⚠ 2 条待审批                          │
│ [ 打开协同群 ]                                  详情 ›    │
└──────────────────────────────────────────────────────────┘
```

* **主名只取一个**：卡面标题用默认团队那条归属的 `name` / `role`；同一个人在别的团队另有名字，只在详情「任职」区显示。卡面并列两个名字会让人以为是两个人；
* **任职 N 团队是链接**，点开详情任职区；「主团队」是默认团队的显示名，不印单目录（一个员工可服务多群多目录，写单目录会误导）；
* **领队徽标**来自「它在默认团队里是 `entityType = team` 的那条席位」，不是全局属性——同一个人可以在 A 团队当领队、在 B 团队当组员；
* **P1 不丢**：排序仍按 Phase B 的 Urgent-First（`pendingCount` 红行 > 执行中 > 兜底），归因从 agent 改为**员工**；
* **未指派是合法一等状态**：不自动挑一个执行者，也不显示「空闲/在线」；`[ 去指派 ]` 跳到执行配置页对应行，**不在卡上展开下拉**；
* **执行器永远可见**：员工卡上必须始终写「由谁执行 / 未指派」，否则用户会以为它是独立 AI。桌面渲染 `driverKey`；手机端此位显示执行者名或「未指派」，理由见 4.6；
* **页头骨架不动**：`WorkspaceBoardTitleBar` 结构不改，统计行复用既有 subtitle 文案，**不加「N 驱动就绪」徽标**——不变量 3 已让 CLI 不再常驻，进程态不可用，这个徽标只能做成假绿。

### 4.4 详情页：员工看「任职」，团队看「成员」

**员工详情**（键 `(hostNodeId, agentId)`）：

```
员工 · 墨查                              键 (hostNodeId, agentId)
  身份证：agentId dsh · 宿主 这台电脑

任职（2）                                      ← 多对多，每团队一份名字/角色/人设
  项目协同群 · 领队      墨查 · 质检审查员   ★ 默认团队
    人设：边界用例、自愈测试（该团队的 customPrompt）
    └ 成员 小榫（架构领队）
  deepseek 群 · 组员     审查员
    人设：（未设置）
  [ 调整归属 ]  [ 设默认团队 ]        ← 桌面可写；手机只读

执行绑定（只读）
  底座 DSH · 模型 继承默认 · 宿主 这台电脑
  在线 ● 在线 · 最近活动 3 分钟前
  [ 打开执行配置 ]                    ← 唯一写入口

产出与事件流
  最近事件 / 产物卡片（AgentEventUi.summary 列表）
```

**团队详情**（键 `(groupId, leaderAgentId)`）：

```
团队 · 项目协同群                        领队 墨查 · 2 人
  成员
    墨查   质检审查员   人设 边界用例     底座 DSH（继承）      ● 在线
    小榫   架构领队     人设 未设置       底座 claude · 覆盖     ○ 未指派驱动
  [ ＋ 聘人 ]   ← 只能从**本机**已接入的 agent 里挑（组员在 leader 宿主执行）
  [ 打开协同群 ]
```

* **人设是团队级的，不是员工级的**：`customPrompt` 挂在 `TeamExecutionMemberSpec` 上（团队执行档），因为「这个人在这支队里干什么」本来就随团队变。员工详情里看到的人设是**当前团队的**，切团队就换一段——不要把它写成员工属性；
* **`＋聘人` 只能聘本机 agent**：组员没有 `hostNodeId`，执行只能在 leader 宿主（`TeamExecution.resolve` 要求 `seat.hostNodeId == selfNodeId`）。所以候选 = 本机 `AgentProfiles` 在位驱动 + 本机已接入 agent；「把别的机器上的人拉进我的团队」协议表达不了，别画这个入口；

* **两个方向都要有**：员工详情回答「这个人在哪几个团队、各自什么角色」，团队详情回答「这个团队有谁、各自用什么底座」。缺任一个，用户都查不到自己要的信息；
* **详情页不放下拉**：底座与模型都改在「执行配置」页（4.0）。同一份执行档在详情页与配置页各画一次，必然漂成两个状态；
* `[⚡ 实时终端]` **收进详情页**，且只在能拿到具体 `sessionId` 时出现（`AgentLiveLogPanel(sessionId)` 需要会话 id，员工可能挂多条会话）；手机端暂无该入口，不要画；
* 时间文案只用 `AgentSessionUi.updatedAt`；`hostUpdatedAt` 的 KDoc 明确「不进任何『多久前』文案」，只用于排序；
* 状态只用 `AgentSessionUi.state` 的 idle / busy / done / error，其余照原样显示——**不给状态词表加词**。

### 4.5 执行者视角（镜像，只读）

点卡上的「由 DSH 执行」进入的是**这个宿主上的这个 agent 实例**：它任职于哪些团队、默认底座是什么、最近活动在哪。这是 Phase B `AgentHubUi`（`groupCount` / `isTeam` / `teamSize` / `pendingCount` / `primaryWsTitle` / `lastActivityAt`）的原有能力，正好承载「已接入未任职」区。

三个视角互为镜像，键各不相同，别混：员工 `(hostNodeId, agentId)` / 团队 `(groupId, leaderAgentId)` / 归属 `(hostNodeId, agentId, groupId, leaderAgentId)`。

### 4.6 跨端能力边界（必须写死，别含糊）

* **桌面**：执行配置页两个选择器都可写（执行档在宿主本地，天然可写）。
* **手机**：能看到「员工 / 当前执行者名 / 未指派」，进执行配置页是**只读矩阵**，改不了 `driverKey` 与 `models`——执行档源码写明「不进 group 帧、不广播给全群」，协议 `teamMembers` 只有 `agentId / name / role`。只读视图必须显式标注「在电脑上修改」，不能做成可点但无效的下拉。
* **手机能写的只有配对**：接入与通道页里「加节点 / 扫码配对 / 信任」是手机主路径（`scan_import_image`），与执行配置无关，别混进同一页的写权限。
* **手机看不到「默认团队」的本机记录**：默认团队存宿主台账、不进帧，手机端只能按「最近活动的团队」兜底展示，并标注「按最近活动推断」——不能假装那是权威值。
* **员工合并的边界**：同一 `(hostNodeId, agentId)` across 多团队 ⇒ **合并成一张员工卡**（本规范要做的）；`hostNodeId` 不同 ⇒ 不合并（跨机器同一 `agentId` 是两个实例，没有能力宣告字段能证明它们是一个）；`agentId` 不同 ⇒ 不合并（哪怕名字一样，`name` 只在团队内唯一）。

### 4.7 卡面纪律（逐条对应现行守卫）

| 约束 | 依据 |
| :--- | :--- |
| 单层卡，卡内不得 `LocalCardShape.current` / `relayCardShadow()`；工作区行必须 `InboxUnitRow(` | `AgentBoardContainerGuardTest` |
| 页头不改骨架、不加徽标 | `TabChromeConsistencyGuardTest` / Phase B「`WorkspaceBoardTitleBar` 骨架不动」 |
| 中文文案全部进 `values*/strings.xml` 双语 | `cjkLiteralGate` / `designLocaleParityGate` |
| 图标只用既有 `Res.drawable.ic_*`，不新增 | `designIconGate` |
| 窄屏 360dp：一卡一主按钮 + 一个文本动作；**卡上不放下拉**（底座/模型都在执行配置页） | `ContentWidths.Pane` / `designFormPairingGate` / `designDpDebtGate` |
| 先改 `design/design-spec.json` 与 `prototype/relay.html`（含状态槽），再写 Compose | `designSpecDrift` / `design-preview-pairing` |

### 4.8 接入与通道页（设置内）：放「桥」，不放「底座」

这页回答的是「**谁在跟这台机器说话**」，与执行配置正交。四块内容：

```
接入与通道
─────────────────────────────────────────────────────────────
这对节点
  这台电脑（宿主）        ● 链路在线 · 最近帧 12s 前
  我的手机 · Pixel        ● 链路在线 · 已配对（pin 指纹 AB:CD…）

外部桥 · dsh-tenon（入站）
  最近一次取件 8s 前 · 最近一次可注入消息 4 分钟前
  窗口最多 20 条 · 已判定 6 条：可注入 5 · 未注入 1
  未解析原因：该 agent 没有绑定会话（targetSessionId=null）

事件路由（出站）
  这个项目目录同时绑了多个群 —— 3 次
  这条会话没有绑定任何群 —— 1 次

配对
  [ 添加节点 ]  手机端另走「扫码 → 从图片导入二维码」
─────────────────────────────────────────────────────────────
```

四条纪律（前三条是入站方向，第四条是出站方向）：

* **桥不是插件，别给它一个「在线/离线」灯**。`dsh-tenon` 是外部常驻进程，宿主侧**没有**它的心跳（它不注册进 `PluginHost`，也没有 last-poll 记录）。硬画一个绿灯就是假绿。能如实显示的是**最近一次成功注入的时间**与判定统计 —— 数据来自 `/v1/inbox` 每条消息的 `AgentInboxHint`（`addressedToAgent` / `boundWsId` / `targetSessionId`），这是宿主真正持有的事实。
* **「未解析」必须给原因**：`targetSessionId=null` 的成因是本仓踩过的坑（席位没绑定会话 / 刚入驻找不到自己的会话），只显示计数而不显示原因，用户除了重装什么都做不了。
* **配对是唯一允许手机写的地方**：`添加节点` 在桌面，`扫码 → 从图片导入二维码` 在手机（`scan_import_image`）。两端都写同一份 pin 表 —— 这条与执行配置无关，别混进同一页的权限模型。

**第四条（出站方向）**：「桥」管**入站**（外部 @ 消息注进既有会话），「事件路由」管**出站**（本地会话的事件该投进哪个群）。两块症状几乎一样（群里什么都没有），成因却住在两个模块（`InboxInjectionTrace` / `EventRouterDiagnostics`），合成一块会让「桥没跑」与「会话没绑群」互相盖住。出站只列**阻断档**（`EventRouteReasons.BLOCKING`），`dispatch` 与两条 `duplicate-*` 是正常路径，混进来只会把真问题淹掉；口径是「除正常路径外一律显示」，不白名单 —— 否则宿主新增的成因会被界面静默丢掉。聚合键必须是**稳定 token**，`hits=N` 这类参数只能进诊断行：拼进键会把同一原因裂成 `hits=2` / `hits=3` 两行。

底座能力（`listCapabilities`）**不在这页**，在执行配置页（4.0）——「这个底座能干什么」属于执行器，「这条通道通不通」属于接入，两件事分开摆。

---

## 五、验收测试规范与硬性门禁

### 1. 正向 + 负向双重验收标准
1. **多群隔离验收**：
   - 配置环境：同一项目目录绑定群 A 与群 B；
   - 产生会话事件（绑定群 A）；
   - **正向断言**：群 A 成功落库并渲染该事件卡片；1:1 控制台正确渲染事件 `cursor`；入站 `dedup.log` 有确切处理记录；
   - **负向断言**：群 B 绝对静默，`duplicate-delivery` 计数为 0，日志输出 `no-conv-bound` 阻断记录。
2. **存量自愈与 CLI 真实执行验收**：
   - 预置旧版包含 `cli-dsh/manifest.json` 的数据目录，启动宿主；
   - 断言：迁移机制成功将其置为禁用，`process.log` 不再产生退出码 1 的秒退记录；
   - 正向拉起：在任务派发时，`WorktreeSandbox` 成功调用 `dsh` CLI 产出真实 Patch，且能在群内推进合流卡片。
3. **1:1 直达无弹窗验收**：
   - 手机端与桌面端在无协同群状态下点击聊天，断言直接进入 1:1 会话，不弹出建群拦截对话框。
4. **换底座不换员工（不变量 5 的正向 + 负向断言）**：
   - 预置：群 `G` 内席位 `name=墨查`，`driverKey=claude`，`entityType=team`，`teamMembers=[墨查, 小榫]`；
   - 执行切换为 `dsh`；
   - **正向断言**：席位 `name` / `role` / `entityType` / `teamMembers` / `allowedWorkspaces` 逐字段不变；执行档按新键 `(groupId, dirId, seatName)` 读回同一份配置；派单实际以 `dsh` 拉起；卡面写「墨查 · 质检审查员 · 由 DSH 执行」；
   - **负向断言**：`previous` 未走 `(hostNodeId, agentId)` 查找（源码守卫 + 行为守卫各一）；旧执行者的 grant 对新执行者不生效；若切换导致 worker / reviewer 同厂商，保存被 `TeamExecutionValidation` 拒绝并给出结构化原因。
   - **反向探针**：把 `previous` 查找键改回 `(hostNodeId, agentId)`，上述「编制不变」断言必须变红——否则这说明测试没打到点上。
5. **未指派态验收**：员工无执行者时，卡面显示 `未指派`，不显示「空闲/在线」，不自动绑定任一驱动；从 `[ 去指派 ]` 进执行配置页选择底座后才产生绑定。
6. **模型真的生效（不变量 5 第 2 步的正向 + 反向探针）**：
   - 预置：席位成员 `driverKey=claude`、`models=["claude-opus-4-1"]`；
   - **正向断言**：以**假 CLI 脚本**（把 argv 写入文件后退出 0）拉起，断言 argv 里确有 `--model claude-opus-4-1`（四个支持模型的驱动各一条）；续跑跳（dsh 之外的 resume 形态）同样带模型；派单链路只经 `executeCliTask` 一处注入口；
   - **负向断言**：`driverKey=dsh` 且 `models` 非空 ⇒ 保存被拒且原因是「cannot select a model per task」；`models` 多于一项 ⇒ 保存被拒；驱动层即使被绕过传入模型，也报 `DriverEvent.Error` 而不是静默跑默认模型；
   - **反向探针**：把 `withModel` 的注入删掉（或让 `executeCliTask` 不透传），上述 argv 断言必须变红。只在 UI 上「选得动」而 argv 里没有，就是本仓最典型的假绿。
7. **通道页不许造假绿**：接入与通道页**不得**出现「桥在线 / 已连接」类字样与绿灯（宿主没有该信号）；注入统计必须与 `/v1/inbox` 的 `AgentInboxHint` 逐条对得上；`targetSessionId=null` 的行必须显示成因（未绑定会话），不能只给计数。
8. **多团队归属与默认团队（不变量 5.1 的验收）**：
   - 预置：同一 `agentId` 在群 A 是团队「项目协同群」的组员、在群 B 是团队「deepseek 群」的领队，两处 `name` 不同（`role` 也不同）；
   - **正向断言**：智能体页只出**一张**员工卡；卡面主名 / 主角色取默认团队那条；点「任职 2 团队」可见两条（团队 / 名字 / 角色各异）；团队视图各出一张团队卡，两张卡的成员表都指向同一员工；把默认团队从 A 改为 B 后卡面主名随之变，员工身份、人设、默认底座均不变；
   - **负向断言**：员工退出 A 团队后不再出现在 A 的成员表，但员工卡仍在（任职 1 团队）；移除最后一条归属后落「已接入未任职」，身份与人设**不清空**；默认团队被移除时**不自动挑**另一个，卡面显「未设默认团队」；
   - **反向探针**：把首屏实体键改回 `(groupId, name)`，上述「只出一张卡」必须变红——这是上一版真犯过的错，值得钉住。

> **空集假绿**：上述 1、2 两条属高发区——`duplicate-delivery=0`、`process.log` 无秒退，在宿主未连、两侧信任库为空时**同样成立**。因此每条负向断言必须同时给出一条同批次的正向证据（事件 `cursor` 已渲染 / `dedup.log` 有新记录 / CLI 真出 `patch.diff`）。

### 2. 真机与前置运行条件
* 宿主必须在真实 GUI 桌面环境启动（headless 模式不组合 Compose，无法作为完全 UI 验收点）；
* 双端必须建立双向 TLS + pin 绑定；
* 手机端需至少存在 1 个有效工作区（确保 `agent-workspace` 帧正常宣告）。

### 3. 质量门禁必跑清单
改动完成后必须完整跑通以下门禁（缺一不可，严禁仅跑局部）：
```bash
# 1. 八模块单元测试与安卓宿主测试
./gradlew :core:jvmTest :app:jvmTest :cli:test :agent-contract:test :orchestration:test \
          :composeApp:jvmTest :composeApp:testAndroidHostTest :agentNode:test \
          :desktopApp:test --continue --max-workers=1

# 2. 静态与架构守卫门禁
./gradlew :cjkLiteralGate :constructionTraceGate :staleCommentRefGate \
          :domainComposeFreeGate :envDependentTestGate :designSpecDrift \
          :designTokenUsageGate :designStateSlotGate :designLocaleParityGate \
          :jlinkModulesGate --continue

# 3. UI 改动必跑的七个设计门（漏跑即假绿）
./gradlew :designScreenDiffGate :designIconGate :designConvergeGate \
          :designFormPairingGate :designPreviewPairingGate :designDupFrameGate \
          :designDpDebtGate :testStabilityGate --continue

# 4. 未使用引用与样式合规检查
python3 tools/unused-imports.py --check
python3 tools/check-design-palette.py --css-vars
```

> **防「UP-TO-DATE 冒充全绿」**：九个 test 任务全为 `UP-TO-DATE` 时 `BUILD SUCCESSFUL` 只是重念上轮产物。
> 跑之前先清 `<module>/build/test-results`（或加 `--rerun-tasks`），结束后核对 executed 计数与守卫类名在册。
>
> **UI 顺序**：必须先改 `design/design-spec.json` 与 `prototype/relay.html`（含状态槽：未指派 / 有任务 / 离线），
> 再写 Compose，否则 `designSpecDrift` / `design-preview-pairing` 必打回。
