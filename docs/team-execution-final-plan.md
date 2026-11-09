# 团队执行（team-execution）方案

> **状态**：待评审（v1）。**上游**：[`team-creation-final-plan.md`](team-creation-final-plan.md)（团队声明与展示）、
> [`team-folder-final-plan.md`](team-folder-final-plan.md)（折叠形态）、
> [`team-agent-interaction-and-orchestration-spec.md`](team-agent-interaction-and-orchestration-spec.md)（编排对账）。
> **一句话**：**不新造执行器**。把团队编制编译成现有的 `DelegationPlan`（干活棒 + 审查棒），
> 走既有的 `StageChainGate` → `ChainDispatcher` → `StageChainRunner` → `ReviewPipeline`；
> 群声明仍然只带身份与角色，**驱动 / 模型 / 主备这些执行配置只存在席位宿主的本地执行档里**。
>
> **边界**：本文只设计"团队声明之后如何真正执行"。团队声明、席位、折叠投影仍以上游文档为准；
> 本文不新增 group 帧键，不改 `TeamMemberInfo` 的三个字段。

---

## 0. 结论速览

| 问题 | 结论 | 说明 |
|---|---|---|
| 触发方式 | 群内 `@<组员名> 任务`（纯文本 @） | 复用现有"文本 @ 触发宿主执行"的判据；不改 `agentIntent`、不加协议键 |
| 谁执行 | **席位宿主**（托管该 Leader 席位的桌面端） | 现有链执行器只在桌面装配；手机只发消息/看卡，不执行 |
| 执行什么 | 编制里的 **1 个执行角色 + 1..N 个审查角色** | 编译成 `[worker] + reviewers` 的 `ChainStage` 列表 |
| 审查闭环 | 复用 `ReviewPipeline`：REJECT 打回 worker、最多 3 轮、超限 blocked | 真实已有机制；本文补的是"团队编制 → 计划"的编译层 |
| 模型怎么选 | **宿主本地执行档**（driver + 可选 model + 主备），不进群元数据 | `TeamMemberInfo` 没有 model 字段；群帧不新增键 |
| 模型探针 | 按驱动现状**诚实探测**；探不到就只给"默认（继承）" | 仓库里没有 `availableModelsOf`，不能假装有；见 §6.3 |
| 多模型联合审查 | 多个审查角色 = 多根审查棒，顺序 PASS | 不是并行投票；`ReviewPipeline` 是严格线性 |
| 主备降级 | P2：模型级冷却 + 下一模型重试 + 可见降级 | 现有 `StageChainGate` 遇冷却直接 Reject，不会自动换模型 |
| 与现有 `组队:` 模板的关系 | 新执行档优先；没有时回落既有 `delegation-teams.json` 模板 | 不破坏"以后本群默认组队：X 开发，Y 审核"这条已上线配置 |
| 旧版本 | 宿主必须升级；旧发送端发 `@coder` 仍能被新宿主执行 | 宿主自己从群元数据算 token 池，不依赖发送端 intent |

---

## 1. 现状与可复用机制（事实）

> 这一节只写**主树里已存在**的东西；设计建立在它们之上，不重复发明。

| 机制 | 事实 | 位置 |
|---|---|---|
| 文本 @ 触发 | 入站群文本按 `shouldTriggerAgent` / `agentPromptRoute` 判「本机是否执行」；`route == Local && !intentOwnsDispatch` 时调用 `onAgentPrompt` | `RelayControllerMessagesComponent`、`RelayController` |
| 意图路径 | 胶囊选中目标 → `agentIntent` → `handleInboundAgentIntent` → `AgentInvokeExecutor` → `handlePromptAwait` | `RelayControllerAgentInvokeComponent`、`DesktopExtension` |
| 文本执行入口 | `onAgentPrompt`（桌面壳注入）→ `DesktopWorkerOrchestrator.handlePrompt` | `DesktopExtension` |
| 计划 | `DelegationPlan(goal, stages, source, fromTemplate…)`；已有 `组队:` 启发式、显式点名、群模板三种来源 | `agentNode/DelegationPlan` |
| 链 | `ChainStage(role, agentKey, instruction, requiresIsolation)`；`StageChainRunner` 逐棒执行、失败即停、隔离组并行 | `agentNode/StageChain` |
| 代码门 | `StageChainGate.enforce`：冷却 ⇒ Reject；在位 ⇒ Reject；单棒（AUTO 降级 / EXPLICIT 拒）；**stage[0] 之后的每一棒都算审查棒，必须与 worker 异厂商**；审查棒彼此可同厂商 | `orchestration/StageChainGate` |
| 审查流水线 | `ReviewPipeline.advance`：index 0 = 干活棒（无条件 Next）；index ≥1 = 审查棒；`verdict == null` ⇒ Blocked；REJECT ⇒ 未满 3 轮 BackToWorker、满 3 轮 Blocked；只有**末棒** PASS ⇒ Merge，中间 PASS 不累计 | `agentNode/ReviewPipeline` |
| 结论解析 | 审查棒 prompt 末尾追加 `VERDICT: PASS/REJECT`，`ReviewVerdict.parseOf` 只认显式行、冲突取 REJECT、缺失 fail-closed | `DesktopStageChain`、`ReviewPipeline` |
| 链执行体 | `DesktopStageChain.dispatcher`（**仅 desktop 装配**）；每棒 `executeCliTask(agentKey, prompt, wsPath, …)`；链在自己的 worktree 里跑 | `orchestration/DesktopStageChain`、`DesktopExtension` |
| 驱动执行 | `AgentDriver.executeTask(sessionId, prompt, onEvent)`；**没有 model/env 参数**；每棒只把 `agentKey` 与 prompt 传下去 | `agent-contract/AgentDriver`、`ControlPlanePluginGateway.executeCliTask` |
| 逐次 env 先例 | `TENON_HOOK_SESSION_ID` 每次 spawn 注入；`envOverrides` 是驱动实例级 | `CliSubprocessDriver`、`SandboxedCliDriver` |
| 限流冷却 | `DriverCooldownTracker` 键 = **驱动键**（不是模型）；`captureIfRateLimited` 只认 429/quota 文本；`StageChainGate` 遇冷却 **Reject**，不降级 | `agent-contract/DriverCooldownTracker`、`StageChainGate` |
| 宿主本地配置先例 | `delegation-teams.json`（`controller.dataDirectory`，`schemaVersion`，写锁 + mtime + 0600 + 原子 rename）；按群存 `workerKey/reviewerKey` | `orchestration/DelegationTeamStore` |
| 群模板配置入口 | "以后本群默认组队：Claude 开发，DSH 审核" → `DelegationTeamStore.save`，保存时**拒绝同厂商** | `DelegationTeamConfigParser`、`DesktopExtension` |
| @ token 池 | `groupParticipantRows(...).mentionKeys` = `virtualAgents[].name` / `agentId` / 宿主 kind / 身份 token；组员名**不在池里** | `SenderIdentity` |
| 权限 | `AgentExecAuthorizer.authorize`：本机用户 / agent 宿主 / 已批设备 / policy 档；**组员不是权限主体** | `core/AgentExecAuthorizer` |
| 模块边界 | `:app` 只依赖 `:core`；`:composeApp` 只拿 `:app` + `:core`；执行/驱动/模型事实都在 `:agentNode` / `:orchestration`，经壳注入的端口读 | `build.gradle.kts`、`DesktopExtension` |

**由此得到的两个硬约束**：

1. 团队执行**不能靠在 `:app` 里解析驱动/模型**——必须由壳注入端口（与 `onExecDevices`、`invokeExecutor` 同一形状）。
2. `ReviewPipeline` 只认"第 0 棒干活、其余都审查"，团队编制就必须**恰好一个执行角色、其余都是审查角色**；不允许两个 worker、不允许"只干活不审查"的链（单 worker 走单棒路径，见 §5.3）。

---

## 2. 目标形态

```
手机 / 其它成员                      席位宿主（桌面）
      │  群文本 "@coder 把登录超时修了"        │
      ├──────── group-text ─────────────────▶│  文本路由：本机是席位宿主 ⇒ Local
                                              │  ① 授权（AgentExecAuthorizer，按 Leader 席位）
                                              │  ② 解析 @coder → (席位, 组员)
                                              │  ③ 读宿主本地执行档（driver/model）
                                              │  ④ 编译 [worker=coder, reviewers…] → DelegationPlan
                                              │  ⑤ StageChainGate → 链执行器
                                              │  ⑥ 每棒 CLI 在链 worktree 里跑；审查棒写 VERDICT
      ◀──────── 链看板卡（同一张卡 rev+1）────┤  REJECT ⇒ 打回 worker；3 轮 ⇒ blocked
```

- **对外单一窗口**：所有卡片/结果只以 Leader 席位身份出现在群里；组员不单独发消息、不开会话。
- **`@leader` 行为不变**：仍走现有的单 agent 派单（不加团队语义），避免"用户只是 @ 领队闲聊"被拉去开链。
- **`@组员` 才触发团队执行**：目标成员必须属于该 Agent 席位的团队，且席位宿主就是本机。
- **缺配置不猜**：点名了组员但本机没有有效执行档 ⇒ 出一张 blocked 卡说明"该团队未配置执行"，
  **绝不回落到"默认 codex 单棒"**（现有 fallback 对未知 token 会落到 codex，这是本方案要堵的洞）。

---

## 3. 数据与配置

### 3.1 协议层：不加键，但收紧 `name` 的可 @ 性

- `TeamMemberInfo(agentId, name, role)` 保持三字段；`entityType`/`teamMembers` 仍随 `group.info` 广播。
- **`name` 必须同时是 @ token**：小写 ASCII（`a-z0-9-`，与 `AgentMemberName.slug` 同一套字符集），
  群内唯一（声明层已要求）；`role` 才是中文展示标签（编码/审查/测试）。
  > 声明层 v1 的 §3.2 已按此收紧（`name` 必须 ASCII slug；中文放 `role`）：否则 v1 允许中文 `name`，
  > v2 上线时无法把 `@审查` 变成稳定的可路由 token（slug 会丢字/撞名）。
- 只有 `isTeam == true`（`entityType == "team"` 或 `teamMembers` 非空）的席位才参与团队执行；
  单兵席位即使本地有执行档，也不被 `@` 解析成团队。
- 不在 `TeamMemberInfo` 上加 `driver`/`model` 字段：执行配置是**宿主本地事实**，不是群事实。
  这样既不加协议键，也不把模型信息广播给全群。

### 3.2 宿主本地执行档（`team-execution.json`）

沿用 `DelegationTeamStore` 的落盘纪律（`controller.dataDirectory`、`schemaVersion`、写锁、mtime+长度失效、
原子写、0600），新增一份文件：

```json
{
  "schemaVersion": 1,
  "teams": [
    {
      "groupId": "g-…",
      "defaultWsId": "ws-…",
      "leaderAgentId": "dsh-a030882e",
      "updatedAt": 0,
      "members": [
        { "agentId": "m-coder", "kind": "worker",   "driverKey": "codex", "models": [] },
        { "agentId": "m-reviewer", "kind": "reviewer", "driverKey": "dsh", "models": [] },
        { "agentId": "m-tester", "kind": "reviewer", "driverKey": "claude", "models": [] }
      ]
    }
  ]
}
```

- **主键** = `(groupId, defaultWsId, leaderAgentId)`：一个群可以有多支团队（多个 Agent 席位），
  席位身份用 `defaultWsId` 而不是声明列表里可能重复的 `name`；`leaderAgentId` 是声明里的
  **`VirtualAgentMember.agentId`（稳定 id）**，不是 `name`（显示 token）。
- `members[].agentId` 对应线格式里的组员 `agentId`（稳定 slug，不随 `name` 改名而变）。
  声明保存时必须**保留既有组员的 agentId**（按稳定 id 对齐，不按 name 重算），否则执行档会孤儿化。
- `kind`：`worker | reviewer`；**恰好一个 worker**，其余 reviewer（可以为 0，见 §5.3）。
- `driverKey`：`AgentProfiles` 的档案键（`codex/claude/agy/opencode/dsh`）；保存时必须是已知键，
  派单时由 `StageChainGate` 再查在位（"装了没"不是保存时的事实）。
- `models`：`[] = 继承驱动默认`；`[主模型, 备1, 备2] = 主备（P2 才生效）`。
- 文件缺失 = 没配过（正常）；损坏 / schemaVersion 未知 / 结构非法 = **整份不可信**（回落到 §3.3 的既有模板，
  并留诊断），与 `DelegationTeamStore` 的三态语义逐字一致。

### 3.3 与既有 `delegation-teams.json` 的关系

- 新档优先。新档没有该席位、且**群内只有一个 team 席位**时，回落既有**群级**模板：
  `workerKey` → worker、`reviewerKey` → reviewer，`driverKey` 取模板值、`models = []`；这样已用
  "以后本群默认组队：…"配置过的群升级后仍能执行。多席位时旧模板无法定位到具体席位 ⇒ 按"未配置"
  出 blocked 卡（提示去团队编制里配置），不猜。
- 两个档位的语义差异如实写进文档：旧模板是"这个群默认谁干活谁审核"，新档是"这个席位的团队编制"。
  未来收敛成一份（旧模板迁移进新档的默认席位）另立小任务；本期不做，避免切断既有入口。

### 3.4 保存校验（宿主侧纯函数，保存与派单共用）

保存时就把"注定派不出去"的配置拒掉（与 `DelegationTeamStore.save` 拒绝同厂商的先例一致）：

1. 恰好 1 个 `worker`；`reviewer` 数量 0..N，且总数 ≤ 声明层上限（8）；
2. 所有 `driverKey` ∈ `AgentProfiles.knownKeys()`；不得有重复的成员 `agentId`；
3. **每个 reviewer 的厂商 ≠ worker 的厂商**（`AgentProfiles.vendorOf`）；reviewer 之间可同厂商；
4. `models` ≤ 3 个、每个 ≤ `Limits.AGENT_MODEL_ID`（新增常量，例如 64），非空；
5. 席位必须在当前 group 元数据里真实存在（`hostNodeId == self` 且 `defaultWsId` 非空、匹配）；`defaultWsId` 为空/席位上没有默认工作区 ⇒ 拒绝保存（团队链需要确定的仓）。
6. 执行档的成员必须与该席位线格式的 `teamMembers` **1:1 对齐**（按 `agentId`）：不允许凭空加人、
   也不允许漏掉声明里存在的组员（方向可先只要求"档 ⊆ 声明"；声明里有但档里没有的组员按未配置处理）。

返回**结构化原因**（哪个成员、哪条规则、当前可用厂商/驱动），UI 直接展示；不允许"先存着"。

### 3.5 读取与悬空清理

- 组员从线格式里被删除 / 席位被撤回：执行档留着无害，但**读时校验**——目标组员不存在 ⇒ 该席位视为
  "配置过期"，派单给 blocked 卡并提示重新编制；不静默挑一个成员顶上。
- **团队解散（Single 退回）时的生命周期同步**：当机主在群设置中关闭“团队协同模式”（`entityType` 回退为 `single`）时，
  宿主侧在 `handleSetGroupTeamDeclaration` 保存成功后，通过 `teamExecutionStore` 同步移除或标记停用对应的
  `(groupId, defaultWsId, leaderAgentId)` 本地执行档，防止存留无用幽灵记录。
- group 已退：文件条目留在磁盘（与 delegation-teams 一致），不做后台清理。

---

## 4. 路由：`@组员` 怎么到达席位宿主

### 4.1 token 规则（单一来源）

- 组员 token = `TeamMemberInfo.name`（ASCII slug，§3.1）；比较**大小写不敏感**、**长 token 优先**
  （与 `GroupMeta.mentionedVirtualAgent` 同一套：`mentionedTeamMember` 新纯函数放 core，复用
  `mentionsToken` 的 ASCII 词边界）。
- 群内 token **全局唯一**：声明层已要求组员名不与其它 `@` token 冲突；因此 resolver 不需要"哪个席位"
  的额外参数——token 本身唯一。若元数据被手改成重复，resolver fail-closed（返回歧义，不发执行）。
- `@` 候选面板（`MentionTarget`）在 Leader 席位下**追加组员条目**（显示 `name · role`，插入 `@name `）。

### 4.2 发送侧

- **先解析目标，再决定执不执行**：在 3 个文本路由点（发送侧自检 / 入站群文本 / 入站 1:1）
  共用一个新的纯函数 `resolvePromptTarget(text, seatTokens, memberTokens)`：返回
  `Seat(token) | Member(token) | Ambiguous | None`；`Ambiguous`（同时 @ 了席位与组员、或 @ 了两个组员）
  **不执行**并广播一句"一次只 @ 一个目标"；结果以现有的 `mentionedAgentKey` 传给 `onAgentPrompt`。
  这样组员 token 不需要改回调签名，也不会出现"随机挑一个目标"。守卫扩展
  `AgentPromptWiringGuardTest`（派单触发点必须伴随该解析 + `logNoWakeIfNeeded`）。
- `groupParticipantRows(...).mentionKeys` 追加所有 `virtualAgents[].teamMembers[].name`；
  `agentKindsInGroup` / `shouldTriggerAgent` / `agentPromptRoute` / `extractAgentPrompt` 全部自动吃上
  （它们都读同一份 `mentionKeys`）。`GroupMentionKeySourceTest` 的"候选与匹配器同源"守卫随之扩展。
- **不加任何 group-text 顶层键**，因此不触发 `GROUP_TEXT_KEYS` 白名单变更，也不依赖 `gta1` 能力位。
- `mentions` 数组仍只装收件人 nodeId，**不装组员 token**（协议语义不变；组员不是群成员）。
- 旧发送端不产新键；它打出 `@coder` 时，**新宿主仍能自己从群元数据算出 token 并执行**（见 §4.6）。

### 4.3 宿主侧：`handlePrompt` 的团队分支

在 `DesktopWorkerOrchestrator.handlePrompt`（以及 `handlePromptAwait`）里，**在委派解析和旧语法之前**插入：

```text
teamTarget = resolveTeamTarget(groupId, mentionedAgentKey)      // token → (席位, 组员, 执行档)
if (teamTarget != null) {
    if (!authorize(senderPeerId, groupId, teamTarget.seat.agentId)) { 可见拒绝; return }
    when (val r = compile(teamTarget, prompt, wsId = teamTarget.seat.defaultWsId)) {
        Rejected -> 出 blocked 卡（原因）；return
        Single   -> dispatchSingleWorker(goal, worker.driverKey, wsId)；return
        Plan     -> if (!delegateByPlan(convId, groupId, plan, available, wsId)) 出 blocked 卡；return
    }
}
// 非组员 token：保持现有路径（parseDelegation → …）
```

- **必须有两个执行入口共用**：文本路由 `handlePrompt` 与意图路由 `handlePromptAwait` 都调同一个
  `resolveTeamTarget` + `compile`，防止两条路各说各话（本仓已有的"两条派单入口必须同源"纪律）。
- `resolveTeamTarget` 只看：group 元数据里的 `isTeam` 席位 + token 匹配 + 本机是席位宿主；
  执行档读取失败/过期 ⇒ 返回 Rejected（而不是 null——null 会落到单棒 codex 兜底）。
- **workspace**：编译时把 `wsId = seat.defaultWsId` 显式传给 `delegateByPlan`/`dispatchSingleWorker`，
  不用 `resolveWorkspacePath` 的"群默认"推导——否则同群多席位时会在错误的仓里跑（现有推导只在
  群级声明里取第一个工作区）。
- **失败可见**：拒绝/未配置/驱动不在位都要有 blocked 卡或群消息（"为什么没跑、缺什么"），
  不能只有本地 notice（notice 不进协议，手机看不到）——沿用 `announceIntentDispatchDenied` 的广播形状。

### 4.4 授权（必须显式补上）

- 团队分支必须调用 `AgentExecAuthorizer.authorize(sender, group, grantAllows = controller.invokeGrantAllows,
  policy = controller.invokeExecPolicyOf(seat.agentId), selfNodeId = self)`；判据与意图路径**同一份**。
- **现存的洞**：主树的文本 @ 路径在调用 `onAgentPrompt` 前只发了一条本地 notice（`unathorizedAgentMention`），
  **没有阻断执行**；意图路径有授权。团队执行会放大这个洞（一条群消息拉起多驱动链）。
  本方案把"文本派单授权收口"列为 **P0-0 先决项**：团队分支必须拦截；领队单 agent 文本路径是否同期修复
  见 §10 待确认 1（推荐同期修，否则同一条消息里"领队可跑、组员不可跑"的语义不一致）。
- 组员不是权限主体：`@coder` 的授权等价于"发送者对 Leader 席位 agentId 有没有执行权"。

### 4.5 多 Agent 宿主下的目标路由（现有判据的坑）

`agentRouteFactsOf` 现在的口径是：`remoteAgentPresent = 群内存在**其它** agent 宿主`；`agentPromptRoute`
在远端在场时让本机让路。于是**群里有 ≥2 台 agent 宿主时，任何一台都不执行文本 @**（互相让路）——
意图路径绕开了它，但团队执行依赖文本 @，必须改成**按目标席位宿主判**：

- 解析 `mentionedToken` → 目标席位的 `hostNodeId`；
- `agentPromptRoute` 增加目标宿主参数：`targetHost == self` ⇒ Local（即使别的宿主有 agent）；
  `targetHost != self` ⇒ Remote；解析不到目标 ⇒ 保持现有"本机有 agent 则 Local"兜底；
- 守卫测试覆盖：单宿主、多宿主、目标=自己、目标=别人、旧发送端无 token 五档；
- 这是**行为变更**：多 agent 宿主群里文本 @ 从"没人执行"变成"目标宿主执行"，提交信息要写清。

### 4.6 兼容性

| 组合 | 行为 |
|---|---|
| 新发送端 + 新宿主 | `@组员` 完整执行（首选路径） |
| **旧发送端 + 新宿主** | 旧端发 `@coder` 普通文本，新宿主自己算 token 池 ⇒ 仍能执行（这是能工作的关键） |
| 新发送端 + 旧宿主 | 旧宿主 token 池没有组员名 ⇒ 不触发；消息照常送达（如实降级，不宣称"已执行"） |
| 胶囊意图（`agentIntent`） | 本方案不改；`agentIntent` 里没有成员键。要支持"胶囊选组员"是 P3 可选项（内部键自由，不破帧） |

---

## 5. 计划编译与执行

### 5.1 编译规则

```text
worker   = 唯一 kind == worker 的成员
reviewers = 其余成员，按声明顺序
stages   = [ ChainStage(role = worker.role.ifBlank { "worker" }, agentKey = worker.driverKey, instruction = goal) ]
         + reviewers.map { ChainStage(role = it.role.ifBlank { "reviewer" }, agentKey = it.driverKey,
                                      instruction = reviewInstruction(goal, it.role)) }
```

- 审查棒指令要明确"审查 patch、给缺陷、最后一行 `VERDICT: PASS|REJECT`"；`DesktopStageChain` 会给
  i>0 的棒自动追加结论行要求，编译层不重复。
- **目标组员直接呼叫语义（`@worker` vs `@reviewer`）**：
  - 若命中目标是 `kind == worker`（如 `@coder`）：启动完整责任链 `[worker] + [reviewers...]`，先编码后审查；
  - 若命中目标是 `kind == reviewer`（如 `@reviewer 审查代码`）：用户意图是对当前工作区直接审查，**不调度 worker 产生冗余代码**，编译为该 reviewer 单棒任务（或仅运行审查棒），指令聚焦于对当前 Git 分支/工作区的代码审计。
- **不支持隔离棒**（`requiresIsolation`）：`ReviewPipeline` 在链里明确拒绝隔离组（会直接 blocked），
  团队链保持严格线性。
- 角色仅用于卡片/日志展示；`role` 的中文标签作为审查提示的一部分（"你是审查/测试角色"）。
- 模型（P1/P2）作为 `ChainStage` 的可选附加信息传入（§6.1），不改变阶段的语义。

### 5.2 代码门仍然是权威

- 编译完仍走 `StageChainGate.enforce`：在位、冷却、异厂商、单棒规则一条不少；保存侧校验（§3.4）
  只是**提前暴露**，不替代门。
- 门拒绝时出既有 `Delegation rejected: …` blocked 卡（带"缺谁/当前有什么/为什么"），不静默。
- `fromTemplate` 语义：团队编译计划标 `fromTemplate = true`（人已显式选定团队，跳过 LLM planner）；
  `Source.AUTO` 以保证单棒时走 `Downgrade` 而不是 `Reject`。

### 5.3 单棒与无审查

- 执行档只有 worker、没有 reviewer：**不建链**，直接 `dispatchSingleWorker(worker.driverKey)`，
  卡面包仍按单棒工单走；文档/UI 如实提示"未配置审查角色，本次不形成审查闭环"。
- 零 worker / 多 worker：保存期拒绝；读到旧数据也拒绝并出 blocked 卡。
- **单棒路径不经过 `StageChainGate`**：编译时用 `availableAgentKeys()` 做同样的在位检查；
  worker 驱动不在位 ⇒ blocked 卡点名缺谁、当前有什么，不交给 `dispatchSingleWorker` 去撞
  `no cli driver` 的通用错误。
- 没有执行档、或执行档过期：blocked 卡"该团队未配置执行"，不回落到单棒 codex。

### 5.4 审查闭环（复用，不重写）

- `ReviewPipeline` 现状：REJECT → `BackToWorker(attempt+1, feedback)`，第 3 次 REJECT → `Blocked`；
  worker 在链 worktree 里继续修，commit/分支保留；所有审查棒 PASS 后给合流信号（仍等机主批准，
  与今天的链行为一致）。
- 多审查棒是**串行**：第 1 根 PASS 才进第 2 根；中间 PASS 不累计（末棒才 Merge，
  若第 2 根 REJECT，则回到 worker 从头再走全部审查棒）。这是既有实现，团队设计不得宣称"联合投票"。
- "测试"角色 = reviewer 角色（跑测试并给 VERDICT）；**不自动执行 `TicketVerification`/`npm test`**
  （那是另一条验收路径，本期不接，避免重复跑测试与误判）。

### 5.5 卡片与身份

- 复用链看板卡（`task-board` / chain 卡），不新增卡片类型；rev 原位推流、终局 blocked/review_ready 语义不变。
- **派不出去也要可见**：`delegateByPlan` 是 fire-and-forget，`dispatchPlanStages` 返回 false（没有链执行器/无仓）
  时调用方原本会丢掉这个 Boolean；团队分支必须检查并出 blocked 卡，不能"点了没反应"。
- 发送者身份用 Leader 席位（对外单一窗口）；组员不产生独立气泡/会话（与上游"组员不占群席位"一致）。
- 卡片/日志里如实写"本轮用哪个驱动/模型/是否降级"（P2 才有降级）。

---

## 6. 模型层（P1：按成员选模型；P2：主备降级）

### 6.1 逐棒模型的最小接缝

现有事实：`AgentDriver.executeTask(sessionId, prompt, onEvent)` 没有 model/env；`executeCliTask` 也没有；
每棒只有 `agentKey + prompt` 能传下去。因此：

1. `:agent-contract` 新增 `data class DriverSessionOptions(val model: String? = null)`；
   `AgentDriver.openSession(wsPath, resumeSessionId, options: DriverSessionOptions = DriverSessionOptions())`
   与 `executeTask(sessionId, prompt, options: DriverSessionOptions = DriverSessionOptions(), onEvent)`。
2. `CliSubprocessDriver` / `SandboxedCliDriver` 在 spawn 时合并 `options`：按 driverId 把 model 变成
   该驱动的 env/参数（见 §6.2），排在 `envOverrides` 之后、与 `TENON_HOOK_SESSION_ID` 同一条"后写者胜"纪律；
   `SandboxedCliDriver` 的 sandbox env 合并点（`envOverrides + mapOf(...)`）加 options.env。
3. `ChainStage` 增加可选 `model: String? = null`（默认 null ⇒ 既有计划/planner/测试逐字不变）；
   `DesktopStageChain.runOneStage` 把它变成 `executeCliTask(..., options = DriverSessionOptions(model))`；
   其它 `executeCliTask` 调用点不传 ⇒ 行为零变更。
4. `NativeRelayDriver` / `ExternalPluginDriver` / `ProcessAgentDriver` 先忽略 options（不支持就如实不支持，
   不假装生效）；插件协议要不要加 model 字段另立。

### 6.2 每驱动注入模型（必须真机验证，不能按"应该可以"写）

| 驱动 | 现有可控入口 | P1 可用性 |
|---|---|---|
| Claude | env `ANTHROPIC_MODEL` / `ANTHROPIC_SMALL_FAST_MODEL`（`CliProviderEnv` 白名单） | ✅ 最可能直接可用：per-session env |
| OpenCode | `OPENCODE_MODEL` 进程级；`OpenCodeConfig` 生成配置；`OPENCODE_CONFIG` 指文件 | ⚠️ 需按会话生成/切换配置，验证后才能开 |
| Codex | `CODEX_HOME/config.toml` 的 model；CLI 是否支持 `-m/--model` 需实测 | ⚠️ 未验证，先只给默认 |
| DSH | 现有 `dshArgs` 无 model 参数；CLI 是否有 `--model`/env 未核实 | ❌ 先只给默认 |
| agy | Google 登录态，无可配上游 | ❌ 不支持 |

- **UI 只对"已验证"的驱动显示模型下拉**；其余驱动显示"默认（继承）"并附一句"该驱动暂不支持按成员选模型"。
- 该表是**验证任务清单**：P1 的第一件事是真机逐驱动核对（改配置/参数后真跑一次任务，看模型是否切换），
  结果回填本表；未验证的驱动不得出现在下拉里。

### 6.3 模型探针（诚实版）

- 新增宿主侧 `ModelCatalog`（`:agentNode`/驱动层）：`probe(driverKey): ModelProbe`，
  `ModelProbe(options: List<ModelOption>, source: String?, note: String?)`；
  **探不到不是失败**：返回空 + 说明，UI 只展示"默认（继承）"。
- 数据来源只读、按驱动：
  - Claude：宿主 env 的 `ANTHROPIC_MODEL` / `ANTHROPIC_SMALL_FAST_MODEL`（**不解析未知配置文件**）；
  - OpenCode：复用 `OpenCodeConfig.probeHostModel`（单文件、单值），可扩展读 `provider.tenon.options.models`；
  - Codex：`$CODEX_HOME/config.toml` 的 `model = "..."` 一行（有则列，没有则 unknown；不引 TOML 依赖）；
  - DSH / agy：无已知配置面 ⇒ unknown。
- 通过壳注入端口暴露给 UI（`:app` 不能 import 驱动层）：
  `RelayController.teamExecutionPort`，实现放 `:orchestration`/`desktopApp`，与 `onExecDevices` 同一形状；
  手机端端口为 null ⇒ UI 只读。
- **不建"可用模型池"HTTP 端点**：控制面目前没有 models 端点，且 UI 与宿主同进程；端口比新增 HTTP 路由更小。

### 6.4 主备降级（P2）

- `models = [主, 备1, 备2]`；**只对限流/超时降级**，其它失败不换模型（换模型会掩盖真实错误）。
- 冷却台账从驱动级扩到 **`driverKey#model` 复合键**（`DriverCooldownTracker` 加 `markModelRateLimited` /
  `remainingMsForModel`，键仍走 `DriverKeys.canonicalKey` 归一；旧的驱动级 API 保留给非团队链）。
- 失败捕获：`executeCliTask` 在 `DriverEvent.Error` 时已知本次 model（options 传入），按复合键记冷却；
  现有 429 文本正则复用（`RATE_LIMIT_PATTERN`）。
- 重试位置：`DesktopStageChain.runOneStage` 的外层——主模型失败且被判限流 ⇒ 选下一个不在冷却的 model
  重跑**同一棒**（最多备选数），成功则 `StageRun` 带 `degradedModel` 说明；全部冷却/失败 ⇒ `ok=false`，
  链 blocked，卡面写"X 限流，备选 Y/Z 也不可用；建议等待 Ns 或换成员"。
- 门预检：团队计划调用 `StageChainGate.enforce` 时传一个复合 `cooldownRemainingMs`：
  某驱动只要还有可用 model ⇒ 返回 0；全冷却 ⇒ 返回最小剩余。这样门不会因为"主模型冷却"把整条链拒掉，
  也不会放过"全冷却"的计划。
- 有界：每棒最多尝试 `1 + fallbacks` 次，不无限重试；降级必须**可见**（卡面/日志），不静默。

---

## 7. UI

### 7.1 团队编制对话框（扩展上游 `TeamRosterDialog`）

在声明层的"名字/角色/删除"之外，**仅席位宿主可编辑**追加：

```
┌─ 组员 1 ──────────────────────────────────────────┐
│ [W] 名字: coder     角色: [编码]                  │
│     执行: (●) 执行  ( ) 审查                       │
│     驱动: [Codex ▾]   模型: [默认（继承） ▾]        │
│     主备: [ + 添加备用模型 ]（P2）                  │
└────────────────────────────────────────────────────┘
```

- 非宿主（手机/其它成员）看到的是只读投影：名字/角色 + "执行配置由宿主 `<host>` 管理"。
- 驱动下拉 = 宿主在位驱动（`AgentProfiles.knownKeys()` ∩ `resolveCliDriver`）；
  模型下拉 = 探针结果 + "默认（继承）"；探不到时只给默认 + 一句说明（不是错误弹窗）。
- 空态：本机没有可用的 agent 驱动 ⇒ 对话框不提供"开启团队执行"，只允许保存声明；
  本群没有席位 ⇒ 只读。
- 保存 = 一条动作：校验（声明 + 执行档）→ 写执行档端口 → 提交 group meta（rev+1 广播）；
  任一步失败则整次不保存并给出具体原因（不允许"先存一半"）。
- 文案进 `values` / `values-en`；布局复用 `SetGroupAgentWorkspacesDialog` 的紧凑行与状态槽；
  `@Preview` 放 `androidApp/src/screenshotTest/ScreenshotPreviews*.kt`（设计门要求真渲染该对话框）。

### 7.2 @ 候选面板

- `MentionTarget` 列表在 Leader 席位下追加组员（`name · role`，缩进或分组标记"团队"）；
  选中插入 `@name `；与匹配池同源（`GroupMentionKeySourceTest` 扩展）。
- 用户手打 `@coder` 也生效（不强制走候选）。

### 7.3 失败/空态/可读性

- 团队未配置 / 配置过期 / 驱动不在位 / 全模型冷却 / 审查 3 轮 blocked：都要有可读原因与下一步
  （"去群信息 → 团队编制"、"等 N 秒"、"换异厂商成员"），不出现"静默无卡"。
- 不展示"运行中"以外的假状态；卡片状态沿用链卡的既有状态词。

---

## 8. 落地拆解

### 8.1 P0-0 先决项（先于团队执行）

| 项 | 内容 | 反向验证 |
|---|---|---|
| 声明层 `name` 收紧 | 组员 `name` 必须是 ASCII slug、群内唯一；写路径 + UI + 守卫（扩展上游 v1） | 中文 name 保存 ⇒ 明确拒绝；改动匹配池 ⇒ 单源守卫红 |
| 文本派单授权收口 | `handlePrompt` 前对**团队分支**强制 `AgentExecAuthorizer`；领队路径是否同修（§10 待确认 1） | 未授权成员 @组员 ⇒ 无执行 + 可见拒绝；去掉授权 ⇒ 反向测试红 |
| 目标宿主路由 | `agentPromptRoute` 按目标席位宿主判 Local/Remote（§4.5） | 双 agent 宿主：目标宿主执行、另一台不执行；回退旧判据 ⇒ 测试红 |
| token 单源 + 目标解析 | mentionKeys 加组员名；`GroupMentionKeySourceTest` 扩展；`resolvePromptTarget` 统一 3 个路由点（Ambiguous fail-closed）；`extractAgentPrompt`/`unathorizedAgentMention` 同步 | 去掉 token 池 ⇒ @ 不触发；只改候选不改匹配 ⇒ 单源守卫红；Ambiguous ⇒ 不执行且有提示 |

### 8.2 P0-A：团队执行 MVP（driver 级，model 继承）

- `:orchestration` 新增 `TeamExecutionResolver`（token→席位/成员）、`TeamPlanCompiler`（编制→stages）、
  `TeamExecutionStore`（§3.2）、`TeamExecutionPort` 实现（load/validate/save/driverCatalog）；
  `DesktopWorkerOrchestrator.handlePrompt` / `handlePromptAwait` 接入团队分支（§4.3）。
- `:app` 新增端口类型与 `RelayController.teamExecutionPort`；UI 读端口；保存流程走端口。
- `:core` 新增 `GroupMeta.mentionedTeamMember`（纯函数、单源）；`Limits` 新增 model id 上限。
- 编译计划标 `fromTemplate = true`、`wsId = seat.defaultWsId`；单 worker 走单棒；缺配置出 blocked 卡。
- UI：对话框加"执行/审查 + 驱动"；@ 候选加组员。
- 测试：编译器矩阵（0/1/N reviewer、同厂商拒、多 worker 拒）、store 三态、路由 token、授权、双节点。

### 8.3 P1：按成员选模型

- `DriverSessionOptions` + 驱动实现 + `ChainStage.model` + `executeCliTask` 参数（§6.1）；
- `ModelCatalog` 探针 + 端口 + UI 下拉（仅已验证驱动，§6.2）；
- 真机逐驱动验证并回填表；未验证驱动只给"默认"；
- 测试：fake driver 记录 options（plumbing 可证）、探针 source/unknown、UI 只读/可编辑。

### 8.4 P2：主备与模型级冷却

- `DriverCooldownTracker` 复合键；`runOneStage` 重试；门预检 lambda；卡面可见降级；有界重试（§6.4）；
- 测试：注入时钟的冷却、主备切换、全冷却 blocked、非限流失败不换模型。

### 8.5 P3（可选，不阻塞）

- 胶囊意图增加成员内部键（`agentId` 保持 Leader，新增成员键；`agentIntent` 内部自由，不破帧）；
- 跨宿主团队（组员在别的宿主执行）——需要 agent-channel/远端执行协议，另立。

### 8.6 改动面与提交

- 文件面：`core`（`GroupMeta` 纯函数、`Limits`）+ `agent-contract`（`DriverSessionOptions`、`AgentDriver`）+
  `agentNode`（`DelegationPlan`/`ChainStage`/`StageChain`/`ReviewPipeline` 周边、驱动、探针）+
  `orchestration`（resolver/compiler/store/port/编排器/DesktopExtension）+
  `app`（端口、控制器、mention 池、路由判据）+ `composeApp`（对话框、候选、资源、预览）+
  `desktopApp`（端口装配）+ 测试与文档。
- 提交拆分：先决项各一条；MVP 一条（含测试）；模型一条；主备一条；每条提交信息写清"旧端/混合版本行为"。

---

## 9. 验收

| 层 | 判据（含反向探针） |
|---|---|
| core | `mentionedTeamMember`：长 token 优先、大小写不敏感、ASCII 词边界、非 team 席位不命中、重复 token fail-closed |
| app | mentionKeys 含组员名；`mentionedAgentKeys`/`extractAgentPrompt`/`unathorizedAgentMention` 同步；目标宿主路由五档；授权拒绝 ⇒ 无 `onAgentPrompt` 调用 |
| 编译 | 0 reviewer ⇒ 单棒；1/N reviewer ⇒ stages 顺序正确；多 worker/同厂商 reviewer/驱动未知 ⇒ 保存拒绝；过期配置 ⇒ blocked 卡 |
| store | 缺失=正常、损坏=整份忽略、schema 未知=忽略、0600/原子写、悬空成员读时判过期 |
| 调度（双节点/回环） | 手机发 `@coder` → 桌面执行链（fake driver）→ 卡 rev+1；**去掉回填/回退 token 池 ⇒ 红**；缺配置 ⇒ blocked 不落 codex 兜底；未授权 ⇒ 不执行 |
| 多宿主 | 群内两台 agent 宿主：目标宿主执行、另一台不执行；旧判据 ⇒ 红 |
| 旧版本 | 旧发送端 + 新宿主：`@coder` 仍执行；新发送端 + 旧宿主：不执行（如实降级）；胶囊意图不受影响 |
| 审查 | REJECT→打回 worker→二审；3 次 REJECT ⇒ blocked；末棒 PASS 才合并信号；中间 PASS 不累计 |
| 模型（P1） | fake driver 收到 `DriverSessionOptions(model)`；未验证驱动 UI 无下拉；探不到模型 ⇒ 只有"默认"且不报错 |
| 主备（P2） | 429 ⇒ 下一模型重试并卡面标注；全冷却 ⇒ blocked + 可操作原因；非限流失败不换模型 |
| UI/门禁 | 双语逐键、`@Preview` 真渲染、`designSpecDrift`/`designStateSlotGate`/`designTokenUsageGate`/`cjkLiteralGate`、`GroupMentionKeySourceTest`、`AgentPromptWiringGuardTest`、`IntentDeniedWiringGuardTest`、全量测试与门禁 |
| 真机 | 电脑端配好团队 → 手机 `@coder 任务` → 链卡在群内推流；REJECT/修复/二审可见；3 轮 blocked；`closeCount` 不新增抖动；未配置/无驱动时原因可见 |

---

## 10. 风险与待确认

1. **文本派单授权收口范围**：团队分支必须拦截；领队单 agent 文本路径是否同期修复？
   推荐同期修（否则同一群里"@领队可跑、@组员不可跑"语义不一致），但这是一次行为变更，需要明确的
   否定测试与真机验证。
2. **`@组员` 的 UX 入口**：MVP 只做"候选面板追加 + 手打"；胶囊选择组员（intent 内部键）延后到 P3。
3. **模型注入逐驱动验证**：P1 前必须真机确认 §6.2 的表；未验证的驱动不得开放下拉。
4. **多宿主路由变更**：§4.5 会改变"多 agent 宿主群里文本 @ 没人执行"的现状，需按变更管理走真机。
5. **团队编制与旧 `组队:` 模板并存**：本期回落而不合并；长期要收敛成一份（另立项）。
6. **测试角色语义**：v1 的"测试"= 审查棒（给 VERDICT），不自动跑 `TicketVerification`；若产品要求
   "测试不过就不许合并"，需要另接验收门（不在本期，避免重复跑测试）。
7. **`AgentWorkspaceEntity` 落库塌缩**：内存里同一 wsId 可挂多个 agentKey、落库主键 `(node_id, ws_id)`
   会塌成一行；执行档按 `(groupId, defaultWsId, leaderAgentId)` 取，读取时以内存/声明为准，
   不要拿 DB 行反推席位归属（已知数据形状，不作为本期修复）。

---

## 11. 回退

- 关闭"团队执行"：删除/停用执行档即可回到"团队只声明、不执行"（声明/药丸不受影响）。
- 移除功能：删团队分支与 UI 字段；群声明字段保留（旧端无感）；不改协议键、不改会话键、无 DDL。
- 旧版本：宿主回退则文本 @组员 不再执行（旧宿主不认识组员 token）；群消息与声明仍在，
  不产生"半执行"状态（执行要么发生、要么 blocked 卡）。

---

## 12. 文档同步

- 上游 `team-creation-final-plan.md`：§13 已指向本文；§3.2 已补"组员 name 必须是可 @ 的 ASCII slug"。
- `team-agent-interaction-and-orchestration-spec.md`：把"团队数据沿用"补上"执行档在宿主本地、群帧不加键"。
- `PROTOCOL-EXTENSION.md`：注明 group 帧**无新增键**；组员名成为本地可路由 token，不是协议字段。
- `README.md`：产品口径——团队编制 + `@组员` 执行（仅桌面宿主）、审查闭环、模型配置在宿主本地。
- `ACCEPTANCE.md`：真机验法（多宿主、旧端、未配置、审查 3 轮、限流降级）。
