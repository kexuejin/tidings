# 团队编排从静态编制到 Planner 动态裁决：决策权分层与改造方案

> **规范标识**：`SPEC-PLANNER-DYNAMIC-ORCHESTRATION-V2`
> **对齐基线**：`SPEC-TENON-SUPER-AGENT-NATIVE-MASTER-V1` §5④ / `docs/team-execution-final-plan.md` / `AGENTS.md`
> **适用模块**：`:orchestration`（主）、`:agentNode`、`:app`（可观测性）
> **性质**：设计评审稿——**本文不含代码改动**，供评审"放开哪些锁、保留哪些门"

## 0. 相对 V1 的修订

| # | V1 的说法 | V2 的修正 | 依据 |
|---|---|---|---|
| 1 | 在 `refinedPlan` 的 `fromTemplate` 分支装配 | **改到 `runTeamDispatch`、`TeamPlanCompiler.compile` 之前** | `refinedPlan(plan, available)` 只有 `DelegationPlan`（`ChainStage.agentKey` 是 driverKey）与 `Set<String>` driverKey；**执行档与 `agentId` 不在作用域**，无法回查 |
| 2 | 0 reviewer 视为需要拍板的"不变量变更" | **0 reviewer 一直是合法形状**，无需新开门；只需修 instruction 丢失与卡面可见性 | `TeamPlanCompiler.compile` 在 `reviewers.isEmpty()` 时已返回 `Compiled.Single(reviewOnly=false)` |
| 3 | 新门重复做"在位/冷却/异厂商" | 新门**只守形状**，上述三判据只留 `StageChainGate` | 同一判据两处实现 = 两处腐坏 |
| 4 | 改 `if (plan.fromTemplate) return teamPlanRefiner?.invoke(plan) ?: plan` | **不动这一行** | `DelegationTemplateWiringGuardTest` 逐字钉住它；按修订 1，planner 不在此处 |
| 5 | §1.2「worker 不换人，放开的是审核面」 | **已被 §十 取代**：编制里可有 N ≥ 1 位 `kind=worker`，主理人在其中点将；`@团队名` 的锚点降级为**默认落点** | 用户口径「leader 真正用来分配任务」 |

---

## 一、问题定位：编排决策权目前分三层，LLM 只在最外层

一次 `@coder 干活` 的责任链，谁决定"几棒、谁干、什么顺序"？逐层拆开：

| 层 | 决策内容 | 决策者 | LLM 能否改 |
|---|---|---|---|
| L1 形状 | 链有几棒、谁 worker 谁 reviewer、driverKey/model | **执行档 + `TeamPlanCompiler.compile`**：`profile.members.filter{isWorker}` 恰好一个，其余为 reviewer，逐棒生成 `ChainStage` | ❌ 不能 |
| L2 硬约束 | 冷却、在位、单棒降级、**异厂商审核** | **`StageChainGate.enforce`**——四条判据逐棒过，失败 `Downgrade`/`Reject` | ❌ 不能 |
| L3 文案与顺序 | reviewer 内部顺序、每棒 instruction 措辞 | **`TeamPlanRefiner.refine`**——一次 DeepSeek HTTP，`response_format=json_object` | ✅ 但被纯函数门锁死 |

**现状结论**：所谓"leader 动态编排"目前是 L3 的 LLM 润色，不是决策。证据在 `TeamPlanRefiner.systemPrompt` 与 `apply`：

- prompt 明写 `The chain shape is fixed`、`Do not add, remove, or swap members`；
- `apply` 四条门：行数一致、`agentKey` 必须是原集合的**排列**、worker 恒居首、instruction 非空限长；`role`/`model` 按原棒携带，LLM 无权改。

即：**人建队时写死形状（执行档 `kind`），编译器展开，LLM 只能给每棒"换个说法、调个审查顺序"，形状一个字节都动不了。**

这与规范 §5④ 的目标形态存在明确差距：

> 现状：`parseDelegation` 依赖字符串归位，强拼一维串行链
> 目标：**Planner Agent 动态工单编排**——智能体根据任务复杂度自主裁决责任链长度与审核棒数量

### 1.1 关于"0 reviewer"：它不是被禁止的，是被编制决定的

这一点必须先说清，否则会把"放开"做成"新开门"：

- `TeamPlanCompiler.compile` 在 `profile.reviewers.isEmpty()` 时**已经**返回 `Compiled.Single(reviewOnly = false)`，即"单干活棒、无审查棒"。
- 也就是说：**当前能不能不审查，取决于建队时执行档里有没有 reviewer 成员**，不取决于任何代码门。
- `先实现后审查是产品不变量` 这句话只出现在 `TeamPlanRefiner` 的 KDoc 里，它解释的是"refiner 为什么不许改形状"，**不是禁止 0 审查棒**。

所以本方案对 0 reviewer 的立场是：**合法、无需限制、但必须可见**。团队编制本来就是可定义物，不应由代码替用户认定"必须审"。详见 §3.5。

### 1.2 一句话定性

> 今天的"编排"= 写死的 L1 + 不可绕过的 L2 + 被锁死的 L3。缺的是**让 LLM 在合法成员池内裁决 L1 的审核面**——要不要审、审几棒、谁审、各棒怎么交代；不是再写一个 planner HTTP 调用，调用链（`PlannerLlm`/`TeamPlanRefiner` → 门 → 降级）已经完备。

注意措辞：**本节写的是"只放开审核面"那一版**（骨架已落地）。点将（谁干活）在 §十 才放开：
编制里 `kind=worker` 可以有 N 个人，主理人在**其中**挑一位下场，`@` 锚点降级为默认落点。
把它叫"super-agent 编排"会重犯 V1 的名不副实。

---

## 二、改造目标与非目标

### 目标

让团队 `@` 派单的**审核棒数量（0..N）**与**审查人选**由 LLM 按任务复杂度裁决，在**合法成员池**内选人，结果仍过全部现有门。

### 非目标（明确不做）

1. **不引入 DAG / 并行 fan-out**：链保持线性，worker 恒居首（§3.5-2 已裁决不做并行审查）。**但 reviewer 数可为 0**。
2. **不做运行时新增成员**：LLM 只能从执行档声明的成员池里选，不能凭空造 `agentId`/`driverKey`。
3. **不改驱动/模型/role/assignee 的来源**：这四者都是"代码按 `agentId` 回查执行档与群声明"的事实，LLM 不产出它们。
4. **不绕开 L2**：`StageChainGate` 四条判据原样保留，Planner 的建议只是输入。
5. **不让派单依赖 LLM**：超时/无 key/解析失败/门不过 ⇒ 一律回落编译固定链。

---

## 三、核心设计：解锁 L1 的审核面，但把解锁关进纯函数门

### 3.1 决策接口（新增，纯数据）

```
OrchestrationSuggestion(
    stages: List<StageIntent>,   // 顺序即链序；可为 1 棒（无审查）
    rationale: String?,          // 决策依据，仅进 flight-record
)
StageIntent(
    agentId: String,             // 必须是执行档声明成员
    asReviewer: Boolean,         // true=审查棒
    instruction: String,
)
```

要点三条：

1. **用 `agentId` 而非 `agentKey`**：`ChainStage.agentKey` 是**驱动键**（`codex`/`dsh`），不是身份键。LLM 在身份层决策，`driverKey` / `model` / `role` / `assignee` 由代码按 `agentId` 回查注入。
2. **`assignee` 必须一起回填**：`ChainStage.assignee` 是卡面步骤行的身份名。若只回填 `agentKey`，卡面会退化成显示 `codex`/`dsh`——用户看到的"谁在干"变成"什么在干"。名字取 `declaredMembers`（群声明）按 `agentId` 匹配，`role` 同源；`model` 取 `profile.members` 的 `models.first()`。
3. **`stages.size == 1` 是合法输出**（0 reviewer），此时该棒必须是 worker。

### 3.2 纯函数门（新增 `PlannerChainGate`，与 `StageChainGate` 分层）

**只守形状合法性**，六条判据，任一不过 ⇒ 整份建议作废 ⇒ 回落编译链：

| 判据 | 规则 | 违规处置 |
|---|---|---|
| 成员来源 | 每个 `agentId` ∈ `profile.members`（**不是** ∪ 群声明名册——名册里可能有人没有 driverKey，回查必失败） | 作废⇒回落 |
| 恰一 worker | `asReviewer=false` 的恰好 1 棒；有 reviewer 时必须居首；0 reviewer 时它就是唯一那棒 | 作废⇒回落 |
| worker 身份 | 该 worker 的 `agentId` == 被 `@` 命中的成员 | 作废⇒回落 |
| 棒数上限 | `stages.size ∈ [1, min(profile.members.size, 4)]` | 作废⇒回落 |
| 无重复 | 同一 `agentId` 不得出现两次 | 作废⇒回落 |
| 指令预算 | 每棒 instruction 非空且 ≤ 2000 字（与组员人设同一预算） | 作废⇒回落 |

**明确不在此门**：在位、冷却、异厂商——这三条只在 `StageChainGate`。新门若复制它们，就是"同一判据两处实现"，两处会各自腐坏。

### 3.3 装配点（唯一一处，已修正）

V1 把装配点放在 `refinedPlan`，那里**拿不到执行档**（`refinedPlan(plan, available)` 只有编译产物与 driverKey 集合），`agentId → driverKey/assignee` 的回查无从做起。

正确位置是 **`runTeamDispatch` 里、`TeamPlanCompiler.compile` 之前**——`profile` / `seatMembers`(= declaredMembers) / `target.member`(= hitMember) / `available` / `capabilityBrief` 全在作用域：

```
// runTeamDispatch（授权通过之后、compile 之前）
val suggestion = plannerChain?.invoke(
    PlannerInput(
        goal = goal,
        hitMember = target.member,
        members = profile.members,
        declared = seatMembers,
        available = available,      // driverKey 集合：在位且未冷却
        capabilityBrief = rosterBrief?.invoke(),
    ),
)
val compiled = TeamPlanCompiler.compile(
    profile, seatMembers, target.member, goal, available,
    capabilityBrief = rosterBrief?.invoke(),
    suggestion = suggestion,        // 新增可选参数；null = 今天的编译链，逐字不变
)
```

`compile` 拿到 `suggestion` 后：过 `PlannerChainGate` → 逐棒回查 `driverKey`/`model`/`role`/`assignee` → 产出 `Compiled.Plan`（≥2 棒）或 `Compiled.Single`（1 棒）。**门不过就当作 `suggestion == null`**，走今天的编译路径。

`refinedPlan` 与 `DelegationTemplateWiringGuardTest` 钉住的那一行**原样不动**。Planner 成功时需显式跳过 `TeamPlanRefiner`（否则同一批 instruction 被改写两遍、白付第二次 20s）：`compile` 在采纳了 suggestion 时给 `DelegationPlan` 打一个来源标记（如 `plannedBy`），由 `refinedPlan` 判读跳过。

### 3.4 决策 Prompt 的边界

新 Planner 的 system prompt 必须写死：

- **可用成员池**：只列"在位且未冷却"成员（`agentId` + 群声明岗位 + 厂商），不列不可用的——减少无效往返；
- **干活人只能从 `kind=worker` 里点**，并给出**默认落点**（`@` 锚点）且明说"不换人也对"
  （§十 把这条从"不得更换"改成"可以点将"：编制回答谁能干，主理人回答这一单谁干）；
- **审核棒数量由你裁决，0 是合法值**：0 = 不需要独立复核（单点、低风险、可自验）；1 = 常规；2 = 高风险或需不同专长（前端/安全）；
- 若给 reviewer，**每一根都必须与 worker 异厂商**（L2 仍是最终裁决，这里只是减少无效往返）；
- 只输出 JSON，无解释；决策依据写进 `rationale` 字段。

### 3.5 0 reviewer 的落地口径

这是本节最容易被做成静默失效的地方。0 reviewer 有**三条路径**，只有一条是对的：

| 路径 | 行为 | 判定 |
|---|---|---|
| `Compiled.Single(reviewOnly=false)`，`instruction` = planner 的 worker 棒指令 | 直接 `dispatchSingleWorker(instruction)`，进单兵卡 | ✅ **正确** |
| `Compiled.Plan`（1 棒）→ `delegateByPlan` → `StageChainGate.Downgrade` | `dispatchSingleWorker(plan.goal)` —— **instruction 被丢弃，退回原始 goal** | ❌ 静默失效 |
| `Compiled.Plan`（1 棒）→ `StageChainGate.Reject` | blocked 卡 | ❌ 把合法意图报成错误 |

所以：**1 棒的 suggestion 必须在 `compile` 里直接返回 `Compiled.Single`**，不得进入 `delegateByPlan`/`StageChainGate`。理由：单棒本就没有"审核独立性"可判，`StageChainGate` 的 `Downgrade` 语义是"没有第二个厂商**可**审"（被动），而 planner 的 0 reviewer 是"判定不需要审"（主动）——两者结果相同、理由相反，混用会让卡片/日志给出错误归因。

**可见性**（不限制，但必须让人知道）：

1. 卡面在 0 reviewer 时明确标注"未经独立审查"（新词条，中英同步）——
   落在点将卡的横幅上（`PlanDecisionBanner.compose`，`orchestration_unreviewed_notice`）；
2. `flight-record` 的 `planDecision` 记 `reviewerCount=0` + `rationale`；
3. 补审是既有路径：用户再发一次 `@reviewer 审查刚才的改动` 即落 `Compiled.Single(reviewOnly=true)`——不需要新造"追加审查"能力。

---

## 四、降级矩阵（fail-closed 全景）

| 失败点 | 处置 | 用户可见 |
|---|---|---|
| 无 `DEEPSEEK_API_KEY` / 超时 20s / 解析失败 | 回落 `TeamPlanRefiner` → 再回落编译链 | 无感 |
| `PlannerChainGate` 不过（成员越界/换 worker/棒数超限/重复/指令超预算） | 整份作废，回落编译链 | 无感 |
| **L2 `Reject` 由 planner 的选择诱发**（选了冷却中/不在位的 reviewer） | **回落编译固定链，再裁决一次**；编译链也 Reject 才出卡 | 无感（planner 的坏选择不该变成用户的失败） |
| L2 `Reject` 由环境诱发（编译链本身也不可用） | `publishDelegationRejected` blocked 卡 | 可见，带原因 |
| L2 `Downgrade`（单棒 AUTO，编译链侧） | `dispatchSingleWorker` | 卡片变单棒 |

纪律：**LLM 的建议永远不会直接变成执行**，中间必须有两道纯函数门 + 编译链保底。且**planner 诱发的失败不许上抛**——否则"LLM 是增强"就成了空话。

---

## 五、可观测性：补上决策留痕

现状：`FlightRecord` 有 taskId/target/metrics/phases/hostEvidence，**没有 plan/decision/rationale**；Planner 只有一条 `RelayLog.info("PLANNER", ...)`，结果只进 `DelegationPlan`，不结构化落盘。

改造：

1. `FlightRecord` 增 `planDecision`（`source` = `llm` / `fallback_toolong` / `fallback_gate` / `fallback_nokey` / `template`、`stageCount`、`reviewerCount`、`rationale` 摘要），脱敏走既有 `sanitize`；
2. 在 `runTeamDispatch` 的各分支打点：采纳 / 门不过回落 / 无 key 回落 / 超时回落；
3. **版本不用升**：写侧 `encodeDefaults = true`、读侧 `ignoreUnknownKeys = true`，新键在 `v1` 内向后兼容；老记录缺该键按 null 渲染；
4. 保留 64 KiB 上限与 100 条保留。

这样"为什么这次 0 审查棒"有据可查，且不把凭证/内部 prompt 落盘。

---

## 六、验收清单（实现阶段执行，本文不执行）

### 新增测试

- `PlannerChainGateTest`：成员越界、换 worker、棒数超限、重复成员、指令超预算 ⇒ 全部作废；**1 棒建议 ⇒ 放行**；合法多棒 ⇒ 放行。
- `TeamPlannerChainTest`：注入假 HTTP，覆盖 L1 成功 / 0 reviewer / 解析失败回落 / 门不过回落 / 无 key 回落 / 超时回落。
- `PlannerSingleStageTest`：**1 棒建议必须产出 `Compiled.Single(reviewOnly=false)` 且 instruction 是 planner 写的那份**——防"经 `Downgrade` 丢 instruction"这条静默失效。
- 变异探针：把"worker 必须匹配 @ 目标"改松一条、把 1 棒改判 `Downgrade`，对应测试必须变红。

### 既有守卫（不许为变绿重刷）

- `TeamPlanRefinerTest` 原有用例保持绿；
- `DelegationTemplateWiringGuardTest`（钉住 `fromTemplate` 那一行——本方案**不改**它，理应直接绿）；
- `StageChainGate` 守卫（`DesktopInvokeWiringGuardTest`、`DriverCooldownWiringGuardTest`）；
- `:app:jvmTest` / `:orchestration:test` / `:composeApp:jvmTest` 及设计门禁。

### 真机 E2E

- `tools/e2e/agent_dispatch_e2e.py` 加 `--scenario planner`：**同一个 `@coder` + 同一个执行档**，喂两条不同复杂度的 goal，断言：
  1. 卡片 `items[]` 棒数**符合预期**；
  2. `flight-record.json` 的 `planDecision.source == "llm"`（只断言棒数会被"两次都回落编译链"骗过——那是假绿）；
  3. 0 reviewer 那条卡面带"未经独立审查"标记。
- 复用现有 fail-closed 夹具：收回目录授权 ⇒ blocked 卡 + banner 指 workspace。

### 可观测性

- 一次真实派单后 `flight-record.json` 里能看到 `planDecision`：`source`、棒数、`reviewerCount`、`rationale`，且不含凭证。

---

## 七、待评审决策点

1. **放开范围**：本文默认"放开审核面，worker 仍为 `@` 命中者"（范围 A）。**范围 B（点将）
   已在 §十 落地**：编制可有 N ≥ 1 位 `kind=worker`，主理人在其中挑人；锚点降级为默认落点。
   注意它并没有让"人指定谁干活"失效 —— `@某个组员` 仍是单兵、仍点谁派谁，被放开的是
   **`@团队名` 这档本来就没有指定人**的入口。
2. **Planner 归属**：复用 `TeamPlanRefiner` 的 HTTP 客户端与凭证纪律扩成新对象，还是新建 `TeamPlannerLlm`？建议**新建**，保持 `TeamPlanRefiner` 的 L3 语义不被污染。
3. **棒数上限**：建议 4（1 worker + 3 reviewer），与 `PlannerLlm` 的 2-4 棒先例对齐；过高会让 20s 预算与卡片高度都失控。
4. **风险分级来源**：任务复杂度由 LLM 自己判（prompt 给描述性标准：影响面 / 可回滚性 / 是否触及持久化与权限），还是宿主先算 `riskHint` 喂进去？**不要用关键词表**——规范 §1.1 批判的正是"遍历敏感词表"。建议先前者。
5. **0 reviewer 的可见性口径**：已定"卡面标 + flight-record 记 + 补审走既有 `@reviewer` 路径"，
   **不做任何数量限制**——审查棒数是团队编制与任务复杂度的函数，不是产品不变量。
   更强的"事后提醒"（如 24h 未补审留一条系统提示）暂不做：没有证据表明用户会漏，
   先攒 `planDecision` 的真实分布再谈。
6. **可观测性落盘**：`FlightRecord` 加字段按本文 §五 属 v1 内向后兼容，不升版本；实现时验证读侧老记录渲染不炸即可。

---

## 八、实现落地记录（本轮已写进代码）

### 8.1 落点

| 设计 | 代码 |
|---|---|
| 决策接口 | `orchestration/.../TeamPlanner.kt`：`StageIntent` / `OrchestrationSuggestion` / `PlannerChainInput` / `PlannerMemberBrief` |
| 纯形状门 | 同文件 `PlannerChainGate.accept`（6 条：成员来源、恰一 worker、worker 身份、棒数 `[1, min(成员,4)]`、无重复、指令非空 ≤2000） |
| LLM 裁决 | 同文件 `TeamPlannerLlm.decide`（DeepSeek、20s、`json_object`、凭证只进请求头） |
| 候选池 | `runTeamDispatch.decideChainShape`：只列**在位**成员（不在位进池 = 一次无效往返 + 整份作废） |
| 装配点 | `runTeamDispatch`（`compile` 之前），装配走新构造参数 `teamPlannerChain`，`DesktopExtension` 注入 |
| 编译 | `TeamPlanCompiler.compile(..., suggestion)`：采纳 ⇒ `plannedByLlm = true`；不采纳 ⇒ 落到原编译链，逐字不变 |
| 1 棒 | `compilePlanned` 直接出 `Compiled.Single(reviewOnly=false)`，**不经** `Plan → Downgrade` |
| 卡面可见性 | `PlanDecisionBanner.compose` + 点将卡横幅（`teamReadyBannerWithReviewNotice`）；词条 `orchestration_unreviewed_notice`（中英） |
| 留痕 | `FlightRecord.planDecision`（`FlightPlanDecision`: source / stageCount / reviewerCount / rationale，过 `sanitize`）；单棒工单终局落盘 |

### 8.2 相对本文的一处实现选择（修正 V2 的 §四）

V2 §四写的是"L2 `Reject` 由 planner 诱发 ⇒ 回落编译固定链，再裁决一次"。落地时**没有**去改
`delegateByPlan` 的重试路径，而是在 `runTeamDispatch` 里对**采纳计划**先跑一次
`StageChainGate.enforce`：`!is Pass` ⇒ 整份建议作废、重新 `compile` 一次。

好处是不复制任何判据（还是那一道门）、也不给链执行器加"再来一次"的状态机；
代价是同一份计划会被门看两次（纯函数，幂等）。**planner 诱发的失败一律不上抛**这条不变。

### 8.3 已知缺口（诚实记账）

1. **飞行记录只覆盖单棒路径**：链执行（`delegateByPlan` → `StageChain`）本来就不写
   `flight-record`（本轮之前的存量缺口，不是本次引入）。所以 0 reviewer 与
   `Downgrade` 落单这两档有 `planDecision`，多棒链只有 `RelayLog` 的
   `PLAN source=… stages=… reviewers=…` 一行。要让链条也落盘，得先给链补飞行记录。
2. **E2E 场景未加**：`tools/e2e/agent_dispatch_e2e.py --scenario planner` 尚未实现；
   本轮验证靠 `:orchestration:test`（`TeamPlannerChainTest` 19 条 + `FlightRecordTest` 新增 1 条）
   与既有 `TeamPlanCompilerTest` / `TeamPlanRefinerTest` / `DelegationTemplateWiringGuardTest` 全绿。
3. **门槛守卫的语义已改**：`DriverCooldownWiringGuardTest` 从"数全仓 `enforce(` 出现次数"
   改成"逐个出口函数点验"——`runTeamDispatch` 多了第三处合法调用点（上面 §8.2 的预检）。

---

## 九、入口语义（甲案）：`@组员` 单兵、`@团队名`/`@主理人` 整队

### 9.1 定案

| 用户打的词 | 语义 | 谁决策 | 落点 |
|---|---|---|---|
| `@团队名` | 整队接活 | 主理人（`TeamPlannerLlm` / `StepDecider`） | 编制里的 worker + 审查链 |
| `@主理人`（席位名 / 席位 agentId，或主理人本人的组员 token） | 同上 | 同上 | 同上 |
| `@某个组员` | 只让它办这一件事 | 不惊动主理人 | 那个组员，单棒 |

判据只在**解析层**做一次：`TeamExecutionResolver.Target.Hit.entry`（`DispatchEntry.TEAM /
LEADER / MEMBER`）。下游不再从 Hit 里推断——三种命中在 Hit 里长得几乎一样（同一席位、
同一份执行档），推不出来。

`@主理人` 两条命中路径都认：① 席位名 / 席位 agentId（主理人不在编制里时唯一可用）；
② 该组员的 `agentId == profile.leaderAgentId`（主理人同时被声明成组员，这也是
`leaderPrompt` 的数据来源）。组员 token 走 ② 才算整队，其余一律单兵。

### 9.2 落点

| 设计 | 代码 |
|---|---|
| 入口枚举 | `TeamExecution.kt`：`DispatchEntry` + `Target.Hit.entry` |
| 解析 | 同文件 `resolve`：团队名 → `TEAM`；组员 token 且 `agentId == leaderAgentId` → `LEADER`；席位 token（`isTeam` 且有 `teamMembers`）→ `LEADER` |
| 整队锚点复用 | 同文件 `teamAnchor`：两个整队入口共用一套拒因与 worker 落点（不各写一遍） |
| 单兵编译 | `TeamPlanCompiler.compile(..., entry)`：`MEMBER` ⇒ 出 `Single(reviewOnly=false)`，**不展开审查棒、不问 Planner** |
| 不问 Planner | `runTeamDispatch.asksPlanner = hitIsWorker && entry != MEMBER`（单兵白花 20s 没有意义） |
| 不写 planDecision | 单兵 `planDecision = null`：否则卡面会按"0 审查棒"给它打上"未经独立审查"，那是另一个意思 |
| 日志 | `RelayLog PLAN entry=… source=… stages=… reviewers=…` |

`entry` 的缺省值是 `TEAM`（即旧行为）：漏传只会让"点名某人"多拉一条链，**不会**把整队
悄悄降级成单兵。单兵语义必须显式声明。

### 9.3 验收

`TeamExecutionResolverTest` 22 条（新增 5：领队 token 两种写法、主理人兼组员、
无组员席位仍走旧路径、未配置队同一句拒因）、`TeamPlanCompilerTest` 18 条（新增 4：
单兵不展开审查棒并与整队对照、单兵无视 Planner 建议并与整队对照、底座不在位编译期拒、
点审查员仍是纯审查单；另加 3 条 `participants`：链序/纯审查/单兵/拒绝空集、
保留执行档大小写、采纳建议后跟着建议的顺序）、`TeamDispatchAuthorizationTest` 6 条、
`TeamDispatchAuthorizationGuardTest` 3 条（含变异探针）、
`TeamDispatchBehaviorTest` 17 条（新增 3：审查棒仅自己可用 ⇒ 整队被拒且点名是谁；点将放行；点到未授权的人仍拒并点名，见 §11.3）。

`tools/e2e/agent_dispatch_e2e.py --scenario team` 的 `@` 目标从 `@coder` 改为
`@e2e-team`：组员 token 现在是单兵，用它触发团队链会"正确地"拿到单棒，
"终局 review_ready"必然假红。

### 9.4 授权：跟锚点判 → 跟编译结果判（已落地）

| 层 | 判据 |
|---|---|
| 编译产物 | `Compiled.participants`：这次**真会被调起**的组员（`Single` = 那一棒的人，`Plan` = 与 `stages` 同序同长，`Rejected` = 空集） |
| 判据 | `TeamDispatchAuthorization.firstDenied(participants) { verdictOf(it) }`：逐人判、只报链序第一个 |
| 两个出口 | ① **锚点先判**（编译前）：被点名的人自己没授权 ⇒ 立即拒；② **编译后逐人再判**：守"主理人换人之后新出现的人" |
| 卡面/回流 | `publishUnauthorizedDispatch(...)`：按**被拒的那个人**记审批申请，文案只此一处 |

三条为什么这么切：

1. **锚点必须排在编译前**。编译会说"本机没装 codex"这类只有宿主才知道的事；让一个
   无权的人先拿到它，等于用一次必然被拒的派单去探测宿主装了哪些底座。行为探针
   `TeamDispatchBehaviorTest` 里那几条"未授权卡不许写成 control plane"钉的就是这个顺序。
2. **锚点过了也要再判**，否则"主理人换人干活"兑现的那一刻就出现"拿 A 的授权跑 B"。
   `TeamDispatchAuthorizationGuardTest` 用源码级判据钉住"判在编译之后、且输入是
   `compiled.participants`"——这两条单测都够不到。
3. **审查棒也判**（这轮的行为变更）：审查棒在宿主上同样跑命令（读工作区、跑测试、
   写 `VERDICT`）。排除它等于"允许小张用 codex"顺带允许"小张拉的队能让只对自己开放的
   老王跑一轮"。代价是配了 N 人就要批 N 次（每台设备每人一次），收益是逐成员口径不再
   有例外——行为探针：`aReviewerWhosePolicyIsOwnerOnlyBlocksTheWholeTeamDispatch`。

### 9.5 仍未做（下一步）

1. ~~**点将权还没放开**~~ —— 已放开，见 §十。
2. **单兵没有"下一步"提示**：名册简报（`rosterBrief`）已经带下去，但卡面没有"这活该找
   谁审"的落点按钮。这一条仍是**唯一**未做的（见 §十一）。

---

## 十、点将权（`@团队名` 选谁下场）—— 已落地

### 10.1 定案

| 概念 | 语义 |
|---|---|
| 编制 `kind = worker` | **这个人能干活**（不是"唯一干活的人"）。N ≥ 1，顺序即候选顺序 |
| 默认锚点 | `workers.first()`。`@团队名` / `@主理人` 仍落在它 —— 点将只是**允许**换人 |
| 点将 | 主理人在 `kind=worker` 的人里挑一个推到链首；`kind=reviewer` 的人**不能**当干活棒 |
| 单兵 | `@组员` 不受影响：他干什么就是什么（`MEMBER` 档在编译前就分叉了） |

一句话：**编制回答"谁能干"，主理人回答"这一单谁干"**。把"恰好一个"写进编制，等于让
用户在配队时就把每单的分工定死 —— 那正是 §一 要拆掉的那件事。

### 10.2 四个假设的处理

| # | 假设 | 处理 |
|---|---|---|
| ① | `profile.worker` 只能取第一个 | 语义改为**默认锚点**（`workers.first()`），文档与调用点逐处说明；新增 `profile.workers` 给需要全集的判据用 |
| ② | 保存期厂商门 `workers.singleOrNull()` | 改为**每一位**：reviewer 必须与**任何**一位 worker 异厂商。只跟默认那位比，等于给"换个人干"留一条同厂商自审的后门 |
| ③ | `seatExecutionOverride` 按 `firstOrNull { isWorker }` 推底座 | 全部 worker 的 `(driverKey, model)` **一致**才覆盖；不一致返回 null（按席位 slug 派单这条路不知道这单点了谁，猜一个就是替主理人点将） |
| ④ | 表单文案"恰好一个 worker" | 校验原因改为 `at least one worker required (found 0)`（宿主侧 + UI 侧两处同口径）。**没有改 UI 文案**：`team_exec_kind_worker`（"主力执行"）与那段描述对多人同样成立 |

`TeamScaffold`（AI 建队）同步：`enforceSingleWorker` → `ensureOneWorker`（0 ⇒ 首行升
worker；**多 ⇒ 原样保留**）。原来"多就降级成 reviewer"是静默改写 —— 模型生成的"两个
人都能干活"会被悄悄改成"只有一个人能"，用户看不到这处变化。

### 10.3 门与授权的变动

| 层 | 判据 |
|---|---|
| `PlannerChainGate.accept` | 去掉 `rows[0].agentId == 锚点`；改为 **`rows[0]` 必须是 `isWorker` 成员**。审查员顶上来不是点将，是把"只审查"的人塞进执行位 |
| `TeamPlanCompiler.compile` | 去掉"恰好一个 worker"的读取期拒（保留"至少一个"）；回落的干活棒显式 = **锚点本人**（`@团队名` → 默认 worker）。点将只是允许换人，不是默认换人 |
| `compilePlanned` | 角色标签取自**被点中的那个人**的群声明，不再抄锚点 —— 否则卡面把 B 的活写成 A 的岗位 |
| `TeamDispatchAuthorization.entryPermits` | **新增纯函数**，把入口闸拆两档：`MEMBER` = 严格锚点；`TEAM`/`LEADER` = **至少一位可干活的人**可被调起。锚点没授权但在编的同事被授权 ⇒ 继续（否则"授权判据"被当成"人员限制"） |
| 编译后 | 不变：`firstDenied(compiled.participants)` 逐人判，链序第一个 |
| 日志 | `PLAN entry=… worker=<链首的人> source=… stages=… reviewers=…` —— 点将之后 `worker` 与 `@` 锚点可能不是同一个人，不记就查不出"为什么跑的是 B 不是 A" |

两档都仍排在**编译之前**：全队都不可调起时不许先拿到"本机没装 codex"这类只有宿主才
知道的结论（否则一次必然被拒的派单就能用来探测底座清单）。

### 10.4 验收

- `TeamPlannerChainTest` 23 条：`anotherWorkerCanBePicked`（点将放行）、
  `aReviewerCannotBePromotedToWorker`（审查员不许顶替）、
  `aPickedWorkerCompilesWithItsOwnDriverAndIdentity`（底座/身份/授权对象跟着被点中的人走）、
  `thePromptSaysTheDefaultWorkerIsNotBinding`（"不换人也对"写进提示词）。
- `TeamPlanCompilerTest`：`aProfileWithoutAnyWorkerIsRejectedAtReadTime`、
  `severalWorkersFallBackToTheAnchorWhenNoSuggestionIsGiven`（锚点 = 编制首位）。
- `TeamDispatchAuthorizationTest` +4：`aTeamEntryPassesWhenAnyOtherWorkerIsAllowed`、
  `aTeamEntryIsRejectedWhenNoWorkerIsAllowed`、`aMemberEntryStaysStrictlyOnTheAnchor`、
  `anAllowedAnchorShortCircuitsTheGate`。
- `TeamDispatchBehaviorTest.aTeamEntryIsBlockedOnlyWhenNoWorkerMayRun`：两人都能干活 +
  两人都"仅自己可用" ⇒ 仍 blocked，审批按锚点记（放宽不能变成"谁都不需要授权"）。
- `TeamExecutionStoreTest.atLeastOneWorkerIsRequired`：多 worker 能落盘，0 worker 仍拒。
- `TeamScaffoldTest.workerCountIsAtLeastOneButMayBeMore`：多 worker 不再被悄悄降级。
- `TeamProfileWriteGuardTest`：新增白名单条目 `DesktopWorkerOrchestrator.kt`（唯一命中
  `teamName = ` 的是 `LeaderBrief(teamName = …)` 命名实参，不写席位、不写档案）。

### 10.5 已知边界

1. **点将只换"谁干活"，不改变"先实现后审查"**：干活棒仍必须居首（`PlannerChainGate`），
   异厂商仍由 `StageChainGate` 在运行期判（1 棒建议不经门，但它没有审查棒可比）。
2. **审批次数随人数增长**：授权按人判 ⇒ 配了 N 个组员就要批 N 次（每台设备每人一次）。
   这是逐成员口径的必然代价（§9.4 第 3 条），不是本节的缺陷。
3. ~~"至少一位可干活的人被授权"这一档没有行为层用例~~ —— 已补，见 §十一.3。
   原来卡在"单测没有控制面 ⇒ 没有候选池 ⇒ 没有点将建议"，解法是给编排器一个
   **在位清单接缝**（`availableDriversHint`，生产必须传 null），点将才在单测里走得通。


---

## 十一、收尾四件（已落地）

### 11.1 卡面与记录能答"这一单点了谁"

`FlightPlanDecision` 增加三个字段：`workerAgentId` / `workerName` / `anchorAgentId`
（都进飞行记录；`workerName` 与 rationale 走同一道脱敏 + 80 字上限）。

- **卡面**：`PlanDecisionBanner.compose(base, notice, decision, pickedWorker)` 多一段
  "本单由 X 承办"，判据是纯函数 `workerSwitched(decision)`：有承办人名 **且** 与锚点
  不是同一个（大小写不敏感）。没有锚点（纯审查）不判"换人"——**"没有锚点"不等于
  "换了个人"**；没有名字也不拼（不许出现"本单由 承办"）。
- 文案走 `uiTexts.pickedWorkerNotice(name)` + `orchestration_picked_worker`（中英双份），
  空串 = 没装桥，不拼分隔符、不编中文。

### 11.2 责任链也写飞行记录

从前链路径只有 `RelayLog` 的 `CHAIN step#N …`（日志会轮转）。现在 `runChain` 在四个出口
（脏工作树 / worktree 建失败 / 正常收尾 / 中途中断）都落一条 `flight-records/<chainId>.json`：

| 字段 | 取值与理由 |
|---|---|
| `phases` | **逐次执行**一条（返工 = 同一棒两行），含耗时与 ok/failed |
| `metrics.exitCode` | 恒 null —— 链没有宿主跑的验收命令，不拿 0 冒充通过 |
| `hostEvidence.verifiedBy` | `agent_self_report` —— 结论来自审查棒的 `VERDICT` 行 |
| `metrics.patchSizeBytes` | 0 —— 链的交付是分支上的提交，不是可量的 patch |
| `planDecision` | 由 `ChainPlanTrace`（`:agentNode` 里的标量快照）回填；不经团队编排的链如实 null |

跨模块只搬标量：`:agentNode` 不许依赖 `:orchestration`，所以取值域仍只在
`FlightPlanDecision.SOURCE_*` 一处定义，`ChainPlanTrace` 只做搬运。

### 11.3 两条行为用例补上

- `TeamDispatchBehaviorTest.anOwnerOnlyAnchorDoesNotBlockTheTeamWhenTheLeadPicksSomeoneElse`：
  锚点"仅自己可用"、主理人点了另一位已授权的人 ⇒ 整队入口放行（断言卡面**没有**
  `not authorized`，且不记锚点的授权申请）。
- `TeamDispatchBehaviorTest.pickingAMemberWhoMayNotRunBlocksAndNamesThatMember`：
  主理人点到的人自己没授权 ⇒ 仍拒、**点名他**、审批按**他**记（批锚点救不了这一单）。
- 让这两条能跑的东西：`availableDriversHint` 接缝（生产必须 null —— 在不在位只有控制面
  答得了），配上注入的 `teamPlannerChain`，点将才在单测里真实发生。

### 11.4 E2E `--scenario planner`

与 `team` 共用夹具与链路，只多两条断言，且**都不数棒数**（回落编译链同样出两棒，
数棒会被"两次都回落"骗过）：

1. 宿主日志 `PLAN … source=llm`；
2. 本轮飞行记录 `planDecision.source == "llm"`（产品自己落的盘，可事后复核）。

前置：`DEEPSEEK_API_KEY` 或 `~/.dsh/.credentials.yaml`，脚本按产品的同一顺序探。
0 审查棒与 N 棒都合法：形状只报读数（`[info] planDecision = …`），不断言。

### 11.5 真机跑出来的两处修正

`--scenario team` 第一次真机跑（本轮改动后的宿主 + 真机）红在"@ 候选里没有 e2e-team"。
根因不是数据，是**候选面板只有 5 行高**：`@所有人` / `@领队` / `@本机` 之后是组员行，
整队行排在组员**之后** ⇒ 一支两人小队就把它挤出折叠线，a11y dump 里连节点都没有。
「整队」是这支队伍的主入口，被挤掉的却是它。

- 产品：`teamTargets` 排到 `teamMemberTargets` **之前**（整队优先，组员是补充）。
- 脚本：候选找不到时先在面板内翻一屏再判「没有」——上一版直接判假红。

同轮还看到链飞行记录如实落盘（`flight-records/cmwm9aygj.json`）：两棒各一行 `phases`、
`exitCode: null`、`verifiedBy: agent_self_report`、`planDecision.source=fallback_unavailable`
（本机没有 DeepSeek 凭证 ⇒ 问了但没有建议），与 §11.2 的判据逐条对上。
