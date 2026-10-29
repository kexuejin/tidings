# M1 收尾：协议纯化 + 六条技术债清零（2026-10-30）

> **一句话结论**：M1 的八项任务（T0–T8）全部落地，六个 commit 覆盖 T1–T8；白名单落
> 终态 14 键、卡片预算三处同源、六条技术债逐条有反向验证记录。真机装机冒烟通过
> （新代码符号探针 + closeCount=0 + 手机↔桌面群消息双向落库）；@ 触发的 agent 回复
> 未在观察窗内出现（桌面侧 DSH 桥未在跑），如实记账。

## 1. 改了哪些协议事实（T1–T3）

| 事实 | 提交 | 说明 |
|---|---|---|
| `GROUP_TEXT_KEYS` 12 → 15 键（过渡态）→ **14 键终态** | `c1fbb0d9` / `c039ae64` | 新键 `agentIntent` / `agentState` / `card`；`reasoning` 与其预算链整链退场（CoT 不进群） |
| `Limits.CARD_BYTES = 24 KiB` | `c1fbb0d9` | 信封数学：正文 32 KiB + 卡片 24 KiB + 开销 < 64 KiB 整帧硬上限。发送侧（`groupTextVariants`）超限**拒绝不截断**（截断的 JSON 解不了），接收侧 `validateMsg` 判 bad-payload，队列重放走 `decodeTcp` 同一份校验 —— 三处同源 |
| `validateMsg` 值类型校验 | `c1fbb0d9` | `agentState` 限四态（accept/blocked/completed/failed）、`agentIntent`/`card` 限对象、card 超预算整帧拒 |
| `VirtualAgentMember` 改版 | `b042904c` | `wsId`/`role` 退场；`entityType` / `teamMembers` / `allowedWorkspaces` / `defaultWsId` 入列。**隐私收口：路径不进协议**（`GroupWorkspaceInfo` 只有 id 与标题） |
| `decodeGroupInfo` 补齐新字段解析 | `b042904c` | 编码走 `@Serializable`、解码手写 —— 不补就是当年「virtualAgents 整个丢失」的同型事故；`GroupInfoVirtualAgentsRoundTripTest` 往返钉住 |

**vectors 怎么处理的**：`GOLDEN_VECTORS` / `EXTENSION_VECTORS`（`GoldenVectorsData.kt`）
核过 —— 没有任何一条带 `reasoning` / `agentIntent` / `agentState` / `card` 的群文本向量，
**无需重生成**（golden vectors 是与上游逐字节对齐的产物，本仓扩展键不在其中；
`neg-group-text-extra-field` 那条上游白名单守卫照旧成立）。`GroupTextCapabilityGatingTest`
的手写上游键集合一字未动 —— 它代表「上游接受什么」，不是「本仓接受什么」。

## 2. 六条技术债的逐条收口（§9）

| # | 债 | 提交 | 反向验证（哪个测试因它红过） |
|---|---|---|---|
| 1 | `extractSessionId` 删除，分段线只认协议字段 | `79d01463` | `anIdPrefixAloneIsNoLongerASessionSource`：删除前红（旧回退把前缀解析成会话 id）、删除后绿 |
| 2 | `workspaceOfGroup`/`isWorkspaceGroup` 子串猜测删除 → 声明式绑定 + `setGroupAgentWorkspaces` 一次成像 | `310afb1c` | 旧模糊匹配回放 → `aDescriptionOnlyGroupIsNoLongerBoundToAWorkspace` 红 |
| 3 | `requestedSessionId` 接进 `AgentSessionPick` | `9d891d66` | 先红 2 条（点选被忽略）、接线后 17 条全绿（T0） |
| 4 | 读侧老形态 `agent:<kind>` 分支 + `hostForKind` 反查删除 | `aec38000` | `anOldShapedAgentIdIsNoLongerResolved`：删除前红（解析出 kind 身份）、删除后 25 绿 |
| 5 | reasoning 预算链退场 | `c039ae64` | `reasoningIsNoLongerAccepted` + `noGroupForwardPathCarriesReasoning`：删除前双红、删除后绿 |
| 6 | `"dsh"` 默认种类清理 | `586ee899` | `anUnnamedAgentRefusesToProduceAnIdentity`：`of()` 收紧前红（静默产出 `agent:<host>:dsh`）、收紧后绿。附带发现：`AgentSenderIdShapeTest` 原字节预算用例一直靠 dsh 兜底才通过（全中文名 slug 后为空），已按新契约拆分 |

## 3. 存储（T8，`bf5c81b1`）

- `MessageEntity` 加 `card_json` / `agent_state` 两列，16.sqm 自包含，版本断言 16 → 17。
- **都不进 FTS**（与 notice_detail 同一条纪律）；`AFTER UPDATE` 触发器的
  `WHEN old.text IS NOT new.text` 守卫不受影响。
- 存储链四处同步：insert / toRowValues / toUi 读回 / `matches()` 逐列对应。
- 落库语义按方案 §5.3 定死：卡片更新是**追加式新行**（同 cardId、rev+1），不重写旧行。

## 4. 真机装机冒烟（HEAD = `bf5c81b1`）

| 项 | 读数 |
|---|---|
| 产物 | `~/Downloads/relay-build-2026-10-30/androidApp-debug.apk`，sha256 `2e35fa7e2b3c09c5bb5ddd0463c671d1fedffd84b86a918037198f748039646c` |
| 装机 | `adb install -r -d` → Success（真机一台；设备序列号不入仓库） |
| **新代码探针** | 拉取设备上的 base.apk，dex 里 grep 到 `handleSetGroupAgentWorkspaces`（T4）与 `agentIntent`（T1/T3）—— **装的是新代码** |
| 冷启动链路 | `link.json`：74 个 peer，`closeCount` 总和 **0**；桌面 Tidings.app 拉起后 2 个 peer 翻 online，closeCount 仍 **0**（全程无链路抖动） |
| 发一条群消息 | 手机 UI 发送（内容因 `adb input text` 丢字符为 `m1-soke-protocol6`，判据按消息 id 不按内容）：`messages.log` seq 1580 落库（`at` 列），**桌面 relay.db 同 id 收到**（`70fbfae3…`，02:53:43）—— 手机→桌面 编码/发送/接收/落库全链路通 |
| @ 一次 agent | `@dsh …` 发送成功、桌面 relay.db 落库（02:55:13）；**agent 未在 45s 观察窗内回复** —— 桌面侧 DSH 桥未确认在跑（本轮冒烟只拉起了 Tidings.app）。触发判定与投递已验到宿主落库；agent 回复留待 M2 插件宿主网关接线后复测 |
| 待真机读数 | M5 验收矩阵的 7 条场景读数（卡片原位刷新、审批防重、插件热拔等）—— 依赖 M2–M4 的交付物，M1 不填 |

## 5. 遗留

- M2 起：`AgentSenderId.of` 的调用方（插件注册）必须保证身份可 slug 化；全非 ASCII 名
  会被拒绝（`AgentSenderIdShapeTest` 钉住）。
- 串行 suite 每轮偶发 1 条无关抖动（LuciolaSecureChannel / ScreenChannelComparison /
  GroupDissolvePropagation 各一次，单跑恒绿），与 `:testStabilityGate` 3 轮全绿一致 ——
  是 AGENTS §1 描述的「并行等待窗口」形态，不是产品缺陷。
