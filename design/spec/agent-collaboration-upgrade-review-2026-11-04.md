# 《榫卯下一代多 Agent 协同体系升级规划与实施方案》评审与逐条差异表

> **评审对象**：`agent_collaboration_upgrade_spec.md` v1.0.0-PROPOSAL（2026-11-04 15:10 读取）
> **版本**：v1.2（20:2x 复核：Gate 0 已执行，11-03 Spec 已按代码真名修订；修正本文两处
> 关于 `Profile` 字段与「同厂商分流」的误判。v1.1 于 20:09 补「规范 ↔ 实现漂移」对账）
> **存放说明**：原稿与外部方案曾放在 `~/.gemini/antigravity/brain/...`，该目录 19:04 被清空；
> 本文按会话记录**重建到工作区 `design/spec/`**，不再依赖临时目录。
> **核对基线**：2026-11-04 20:09 的本仓工作树（`:constructionTraceGate` /
> `:staleCommentRefGate` / `:designSpecDrift` 已复跑通过）。HEAD 会推进，故不写死提交号。
> **性质**：只读评审 + 裁决记录，**非规范、不生效**。原配套草案
> `orchestration-model-fit-2026-11-03-addendum-draft.md` **已并入并删除**。
> **状态**：`ARCHIVED` · 2026-11-04 20:3x 裁决完成 —— 保留项已并入
> `SPEC-ORCHESTRATION-2026-11-03` 的 §6~§14。本文只作**历史举证**（26 条逐条对账是唯一的
> 裁决链），**不生效、不得被引为依据**；仓库里生效的编排规范始终只有那一份。
> **结论**：**不建议按现文开工**。四个模块里三个是仓内既有规范/实现的重述，四处技术前提不成立；
> 更关键的是仓内 `SPEC-ORCHESTRATION-2026-11-03` 与它自己的实现已漂移。
> **Gate 0 已执行**：该 Spec 已按代码真名修订，裁决落点是它的新增 §5.3 对账表。

---

## 0. 判读口径

| 判定 | 含义 | 处置 |
|---|---|---|
| **作废** | 前提不成立，或与已落地规则冲突 | 从方案删除，不排期 |
| **合并** | 仓里已有等价物（规范或代码） | 改写为对既有章节的引用/增补，不新建第二套 |
| **修正后保留** | 方向对，实现口径必须改 | 按「处置」列改写后再排期 |
| **真新增** | 仓里确实没有，且值得做 | 单独写规格、单独立项、单独定验收 |

证据记法：`路径:行号` = 仓内文件；「真机记录」= 项目工作记忆里的实测读数（无源码行号）。
**若把本文合并进仓内源码注释，所有 `X.kt:NN` 引用须换成符号名** —— `staleCommentRefGate`
禁止注释里留行号引用，`constructionTraceGate` 禁止注释里留日期/绝对路径/模型名。

---

## 1. 总览

方案可判定断言共 **26 条**：作废 6 / 合并 9 / 修正后保留 7 / 真新增 4。
其中 **4 条阻塞级**（B1~B4）不解决就不该进入里程碑：规范双源、`[PASS]` 反向耗 Token、
前缀缓存论断错误、把投屏查看当成远程控制。

一句话：**这是一份写得很好的产品愿景，但它没有做现状对账，且把另一套产品的记忆/缓存机制
（三层 MEMORY.md、4000 字符上限、Snapshot Message）搬进了本仓的驱动架构里。**

> **v1.1 补记**：B1 不只是「外部方案与仓内规范双源」——**仓内 11-03 Spec 与它自己的实现
> 也已经漂移**（见 B1 末段）。在此之前任何「合并」都只是把文字抄到另一份文字上。
> **v1.2 状态**：这道阻塞已解除——11-03 Spec 已按代码真名修订并新增 §5.3 对账表。
> v1.1 表格里两处误判（`Profile` 字段名、同厂商按来源分流）也已在 B1 更正。

---

## 2. 逐条差异表

### §1 背景与核心目标

| # | 方案断言 | 仓内现状 / 证据 | 判定 | 处置 |
|---|---|---|---|---|
| 1.1-① | 移动端输入成本高，用户仍要「调参选模型」 | 输入成本**属实**：真机记录 `adb input text` 长词吞字、`@` 派单必须点候选菜单再逐字符打正文；但「选模型」已不成立 —— 11-03 Spec §2.2 / `DelegationParser.parse`（`DesktopWorkerOrchestrator.kt:589,662`）已按岗位档案自动组队 | **修正后保留** | 痛点收窄为「移动端把已有指令打出来很贵」，据此只做快捷入口，不新建调度体系 |
| 1.1-② | 群聊缺乏仲裁者，抢话、无限客套、Token 爆炸 | `PROTOCOL-EXTENSION.md:774-777` §14.5 已有 Chief Pattern + 「`@Agent` 唤醒或 Chief 编排，未被唤醒时不消耗 Token」；`README.md:79` 已写明群内唤醒判据（私聊每条算，群里要 `@DSH` 点名或回复 Agent 那条）；`docs/lan-team-agent-im-detailed-design.md:740-744` 已有严格单向流水线、禁止 Agent 双向私聊；`:761-765` 已有 assign/transfer 权责划分 | **作废** | 删掉问题陈述，改成「现有唤醒判据在移动端缺少可点击的入口」 |
| 1.1-③ | 动态注入历史破坏 LLM 前缀缓存，TTFT 与费用激增 | 本仓 prompt 由 DSH/codex/opencode 等**进程外驱动**自行拼接，没有统一 provider 缓存统计面；`PROTOCOL-EXTENSION.md:770-772` §14.4 还规定切工作区/会话分段要**物理重置 Prompt 上下文**；`docs/...:753-759` 已有「三层克制注入」 | **作废** | 见 B3；本轮不做缓存承诺，先做观测 |
| 1.2 | Token 压降 ≥60%、TTFT 降 ≥40%、Mobile Handoff 基于现有 `screen` 协议 | 前两项无测量主体/对照组（见 §5）；第三项前提不成立（见 B4） | **作废/修正** | 目标改写为「唤醒判定可观测 + 移动端零打字派工」 |

### §2 总体架构图

| # | 方案断言 | 仓内现状 / 证据 | 判定 | 处置 |
|---|---|---|---|---|
| 2-① | `:core` 帧预算「TCP 32K / Card 24K」由一处 Frame Guard 校验 | 数字口径分三档且**发送/接收/队列重放三处一致**：群文本 TCP 32 KiB、`card` 24 KiB（`Limits.kt:207`）、`agent-event` body 64 KiB（`:148`）、整帧 64 KiB；`CodecMsg.kt:38` 是 group-text 严格字段白名单，`:137-153` 是校验；`AGENTS.md §2.2` 要求队列重放必须用 `Codec.decodeTcp` | **合并** | 图里改成「分层预算 + 三处一致」，引用 `Limits` 与 `CodecMsg`，别造新 Guard |
| 2-② | Chief Router / VirtualTeam / PASS Filter 都是新组件 | Chief 与唤醒规则已在 `README.md:79` / `PROTOCOL-EXTENSION.md:776`；虚拟成员模型在 `GroupMeta.kt:248`（`VirtualAgentMember.allowedWorkspaces`）；`GroupProgressBar.kt:36` 已落地 | **合并** | 图里标「既有」与「新增」两色，避免读者以为要重写 |
| 2-③ | MemoryEngine（`bot/` `group/` `team/` `journal.jsonl`）为新增子系统 | 与 `BlindSpotPatrol.kt:34`（按月 `.jsonl`）、`SessionExemptLedger`、`AgentToolApprovalStore`、`GroupForwardLedger`（`AgentEventForwarder.kt:100`）形成第二套事实源 | **作废** | 见模块二 |

### §3 模块一：Admin-First 仲裁与 `[PASS]`

| # | 方案断言 | 仓内现状 / 证据 | 判定 | 处置 |
|---|---|---|---|---|
| 3.1-① | 群聊**所有**用户发言默认由 Chief 集中接收 | 与 `README.md:79` 唤醒判据冲突，也与 11-03 Spec §2.1（挡位 A「`@worker`/`@reviewer` 确定性语法解析、零模型消耗」）冲突；`DesktopInvokeWiringGuardTest:208` 钉住 `DelegationParser.parse` 的优先级 | **作废** | 若真要「第三挡全托管」，须单独立项并写清旧语义迁移与两端版本协商，不能默认覆盖 |
| 3.1-② | 内部 `@Coder`/`@Reviewer` 定向指派 | `agentNode/.../DelegationPlan.kt:47` 的 `DelegationParser` + `orchestration/.../StageChainGate.kt` 已落地；`docs/...:743` 已有 `@Architect→@Coder→@Tester`；`MessageTypes.kt:126-133` 已有 `agent-channel` / `agent-channel-result` | **合并** | 角色走 **`AgentProfiles.Profile.role`**（`AgentProfiles.kt:28`，代码真名；11-03 Spec §2.3 写作 `AgentCapabilityDescriptor` 且含 `strengths`/`probe`，**代码里没有这两个字段**）；**不要**把 `@Coder` 当身份 ID；发送者标签只能由 `agentSenderIdFor()` 生成（`AGENTS.md §2.4`，真机曾出现两种拼写各 801 / 113 条） |
| 3.1-③ | `relayHops` 硬上限 3，达阈值抛 `ask_user` 卡 | 11-03 Spec §4.2 **规范文字**写了 `MAX_TURNS = 3` + `NEEDS_HUMAN_INTERVENTION`；代码里对应物是 `DesktopWorkerOrchestrator.kt:1420` 的**局部** `maxHealRounds = 3` 与 `HealReport.bannerOf` 交还卡（`:1591-1592`），**没有同名常量/状态**；`docs/...:767-772` §7.9 与 `AgentCardLogic.kt:76` / `AgentCards.kt:582` 已落地 `ask_question` 多选卡 | **合并（先对齐）** | 先辨析「自愈轮次」与「接力跳数」是否同一个 3，把局部量抽成**单源常量**，别造第二个计数器；卡 type 统一叫 `ask_question` |
| 3.2-① | 虚拟成员「推理判定无需介入」后发标准 `[PASS]` 帧 | 见 **B2**：为省调用先调模型；`PROTOCOL-EXTENSION.md:776` 明确「未被唤醒时不消耗 Token」 | **修正后保留** | 改为**路由层代码硬判**（未点名 / 非回复 / 非绑定群 ⇒ 根本不派单）；`[PASS]` 降级为进程内诊断信号，改名 `SKIP`/`NO_WAKE`（`DesktopStageChain.kt:341` 的 PASS 已表示「审查棒判通过」，字面撞车） |
| 3.2-② | `[PASS]` 不进广播 / 不落库 / 不触发未读与通知 | 方向正确；但 `CodecAgent.kt:114-146` 的 `agent-event.eventType` 取值不封闭（收得下），而 `AgentEventForwarder.kt:584-587` 的 `shouldForwardAgentEventToGroup` **只看 `alreadyInChat`、不看 eventType** —— 只要做成 agent-event 帧就会被转发进群，直接违反「不进广播」 | **修正后保留** | **绝不走 agent-event 帧**；只走编排层 `RelayLog.info(tag, msg)` + 诊断包 `recentLogLines`。三不钉成源码守卫，但必须区分「业务库 `MessageEntity` 不落」与「诊断日志要留」，否则 §4.3 E2E-2 无法举证 |
| 3.2-③ | 硬熔断阈值 3 | 同 3.1-③ | **合并** | 复用现有 `maxHealRounds` 并抽成单源常量；`const val` 编译期内联，运行期断言不了，只能源码层守卫 |

### §3 模块二：三层 Markdown 记忆与 KV Cache 保护

| # | 方案断言 | 仓内现状 / 证据 | 判定 | 处置 |
|---|---|---|---|---|
| 2-① | 数据落 `~/.tenon/memory/` | 本仓持久化根是平台注入的 `DataDirectoryProvider`：Android `filesDir/relay`（`AndroidPlatform.kt:90-92`）、iOS `Application Support/relay`（`IosPlatform.kt:83-101`）、桌面由 `Main.kt:90` 取；`GroupForwardLedger` 与 `BlindSpotPatrol` 都落 `controller.dataDirectory` | **作废** | 硬编码 home dot-dir 绕过可注入契约，`:orchestration`（零 UI）与 `:cli`（无 UI 验收物）不可移植；改接 `dataDirectory` |
| 2-② | `bot/` `group/` `team/` 三层物理隔离 + 4000 字符上限 | 与 `docs/...:753-759` 的「三层克制注入」（固定层/语义摘要层/数据指针层）术语撞车但语义完全不同；4000 字符上限是另一套产品的记忆配额，不是本仓约束 | **作废** | 先定义「一个事实一个来源」的边界：偏好归谁、与 `SessionExemptLedger` / `AgentToolApprovalStore` / `BlindSpotPatrol` 如何分账 |
| 2-③ | **缓存保护铁律**：System Prompt 字节级恒定，动态快照作为第一条历史只读消息 | 见 **B3**：动态内容放在序列前部仍是前缀，一变后面全失效；且与「bot 专有记忆要生效」自相矛盾 | **作废** | 改「稳定前缀固化 + 动态内容后置 + 先做观测」；保留 §14.4 上下文重置裁决，不要为缓存牺牲会话隔离 |
| 2-④ | 异步 Memory Review 写 `journal.jsonl`、原子 Undo、过滤密钥与 Prompt 注入 | `BlindSpotPatrol.kt:34` 已有按月 `.jsonl` 聚合；但「抽取偏好 + 可撤销」确实没有 | **真新增（低优先）** | 先解决失败通道：`FileStores.rewriteLog` 的 `openFresh(path) ?: return` 自己吃掉 `deleteFile`，下游 `runCatching{}.onFailure{}` 是死分支 —— 这正是「看起来在记、其实没记」高发区；再定义并发写、幂等、Undo 粒度、密钥/Prompt 注入过滤判据 |

### §3 模块三：移动端操作台与折叠流

| # | 方案断言 | 仓内现状 / 证据 | 判定 | 处置 |
|---|---|---|---|---|
| 3-① | 常驻 Slash 芯片 `/status` `/diff` `/test` `/stop` | 仓内无此组件；**这是全文最值得做的一块**（真机吞字有据） | **真新增** | 见 §6 落地清单：共享组件、领域层解析、文案资源、设计门、`/stop` 单一执行路径 |
| 3-② | 「在 `:composeApp` 消息编辑器上方常驻」 | `MessageComposer` 是手机/桌面**逐字共享**组件；`AGENTS.md §2.6` 规定一个 UI 元素只许一处定义；`ux-scoreboard` 跨壳重复分母已含 `desktopApp` | **修正后保留** | 做成共享 Composable 的参数/接缝（桌面可显式关闭），不是移动端独占；指令解析放 `:app`、`:cli` 可复用 |
| 3-③ | `/stop` 向 `:agentNode` 发刹车信令终止进程 | `DesktopExtension.kt:821-827` 已有文本 `stop` 关键词消费；`design/product-review-2026-11-01.md:109` 记着「拼错 = 静默无效，用户以为停了任务还在跑」；真机记录：`ProcessHandle.descendants()` 是快照，根退出后后代过继给 init 枚举不到 | **修正后保留** | 与现有文本 stop **共用同一执行路径与可见回执**；杀树先记 pid 集合再逐个核对；须覆盖两条派单路（`onAgentPrompt → DesktopWorkerOrchestrator`、`invokeExecutor` CLI 分支 → `handlePromptAwait`）；「打断有效」不等于「编排器在跑」（打断桥挂 `DesktopExtension`） |
| 3-④ | `/status` 拉「最近一次 Commit」/ `/diff` 展示暂存区统计 | 合流判据只看工作区：`DesktopPatchMerge` 只 `git apply` 不提交，用 `git log` 判成败会给出误导读数；`PROTOCOL-EXTENSION.md:775` §14.5.1 规定本机绝对路径不进协议 | **修正后保留** | 改读群绑定 `wsId` 的工作树状态，不读 commit；只广播 wsId 与标题 |
| 3-⑤ | 终端日志流原地折叠、执行后抽屉查看 | `ThinkingBlock.kt:63-71`（`ChatBubbles.kt:483` 已在用）、`AgentCardParse.kt:199-201` 按 `cardId` 折叠只留最新 `rev`、`docs/...:721-725` §7.2「执行流不写 `MessageEntity` + 单卡片槽位」、`docs/...:734-738` §7.4 大产物走 `TransferClient`/`SecureChunkSender` | **合并** | 只补「stdout 单行进度条」的呈现，别新建日志管道；卡面 rev 必须严格递增且分配串行（`cardBroadcastMutex`，`BoardCardLocalPersistenceTest` 的 1→2→3 钉子）；`card` 24 KiB 超限拒绝不截断 |

### §3 模块四：卡点临时接管

| # | 方案断言 | 仓内现状 / 证据 | 判定 | 处置 |
|---|---|---|---|---|
| 4-① | 带 `kind:"require_intervention"` 的 **L2** 安全卡片 | L1/L2/L3 是 `agent-approval.riskLevel` 取值域（`MessageTypes.kt:658-679`），不是 `kind`；`CodecAgent.kt:62` 的 `agent-approval` payload 是固定 8 键严格白名单；`AgentAckKinds`（`MessageTypes.kt:603-656`）要求「键 + 取值域全仓一份」 | **修正后保留** | 复用 `agent-approval` + `riskLevel`；新增回执 kind 值必须三处同步（编解码白名单 / 控制面入口 / 台账分派，本仓踩过两次）；`wireJson` 是 `encodeDefaults=false`，带默认值的键会整键省略 ⇒ 对端 `bad-payload`（`ScreenPayload.kt:48-55` 有同类注解） |
| 4-② | 用现有 `screen`（`rv1`/`rvs1`）「秒级拉起微缩视图」并完成**触控操作** | 见 **B4**：`ScreenOps`（`ScreenPayload.kt:10-15`）只有 request/accept/reject/end，`ScreenTcpFrame`（`:121-146`）只有 Open/Ready/Next/Frame 画面帧，**无任何输入事件帧**；`rv1`=查看、`rvs1`=共享（`MessageTypes.kt:317-321`），`ScreenRole` 只有 Viewer/Sharer | **真新增（须单独立项）** | 不是「基于现有协议」：需新增双向输入帧（归一化坐标/事件类型/seq/幂等）、宿主输入注入（macOS 辅助功能权限）、逐次授权状态机、新 cap 台账（`CapabilityDeclarationTest` + `PROTOCOL-COVERAGE.md`）、L3 风险档；「交还后 Agent 恢复上下文」现有 `ScreenPhase` 无对应态。且投屏是「单节点持有一个会话、逐次授权同意」，卡点触发时可能被 `BUSY` 挡住 |
| 4-③ | Phase 3 一周内跑通全链路 E2E | 依赖 4-② 的新协议族 + 双端真机 | **作废** | 移出本轮四周计划 |

### §4 实施与验证对账 / §5 里程碑

| # | 方案断言 | 仓内现状 / 证据 | 判定 | 处置 |
|---|---|---|---|---|
| 4.1 | 五项量化基线全部作为「验收基线」 | 见 §5；`AGENTS.md §2.1` 的 `envDependentTestGate` 禁止结论依赖本机/外部服务/固定 delay 赌同步 | **修正后保留** | 拆成「可确定性门禁」与「离线采样报告」两类 |
| 4.2 | 5 道 Gradle 门即「全量本地门禁」 | 缺 `:envDependentTestGate`、`:designStateSlotGate`、`:designLocaleParityGate`、`:jlinkModulesGate`、`tools/unused-imports.py --check`、`ux-scoreboard.py --check`、`RelayLogTest`；动 UI 还须 `designScreenDiffGate` / `designIconGate` / `designConvergeGate` / `designFormPairingGate` / `designPreviewPairingGate` / `designDupFrameGate` / `designDpDebtGate`；验收须 `--max-workers=1` + `:testStabilityGate`（3 轮，`AGENTS.md §1`） | **修正后保留** | 按 `AGENTS.md §1` 原样抄全量链，并加 `--continue` |
| 4.3-1 | E2E-1 富卡片含通过率与覆盖率 | 11-03 Spec §4 已裁决：轻量 Checklist 上卡（<1 KiB），详报走文件通道；`Limits.CARD_BYTES` 卡片 24 KiB | **合并** | 判定改为「卡片正文 ≤1 KiB + 原位刷新 rev 递增」，别把日志塞进卡。**Gate 0 复核（v1.2）：`evidenceId` 不是本仓的键，旧判定里的这个名词已作废**；详报通道以 Spec §4.1 / §10 的 `ArtifactPipe` → `sendAgentArtifacts`、卡内 `artifacts[]`（`intent` / `delivered`）为准 |
| 4.3-2 | E2E-2「后台日志记录到 `[PASS]` 拦截记录」 | 与 §3「不落库」表面矛盾 | **修正后保留** | 明确举证来源 = `RelayLog` 诊断环，不是 `MessageEntity` |
| 4.3-3 | E2E-3 投屏接管验收 | 依赖尚不存在的协议 | **作废** | 随 4-② 单独立项后再写 |
| 5-① | Phase 1 涉及 `:core`；四阶段未列 `:app` / `:cli` | `[PASS]` 是编排层内部信号，不该动 `:core`；Slash 指令的领域解析应落 `:app`，`:cli` 是零 UI 验收物 | **修正后保留** | 重排模块归属（见 §4） |
| 5-② | 四周交付，无版本协商/灰度 | `AGENTS.md §2.2`：旧节点不认识新键 ⇒ 整帧拒收，**两端必须同版本**；`§2.3`：加了 `.sqm` 就放弃回滚 | **修正后保留** | 补版本协商、双端同版验收、回滚边界；Phase 1 的熔断卡依赖 Phase 3 才动的 `:agentNode`，顺序要调 |

---

## 3. 四条阻塞级硬伤（展开）

### B1 规范双源（v1.1 补强：仓内自己也漂移了；v1.2：Gate 0 已执行）

方案零引用 `PROTOCOL-EXTENSION.md §14.5`、`README.md:79`、
`design/spec/orchestration-model-fit-2026-11-03.md`（前一天刚定）、
`docs/lan-team-agent-im-detailed-design.md §7.2/7.5/7.7/7.8/7.9` 四份文件，
却在角色名（`@Reviewer` vs `@Architect`）、卡 type（`ask_user` vs `ask_question`）、
「三层」术语（记忆层 vs 上下文注入层）、熔断计数器（跳数 vs 自愈轮次）上另立一套。

**更麻烦的是，11-03 Spec 描述的形状与代码真名并不一致**：

| 概念 | 11-03 Spec 用词 | 代码真身 | 差异 |
|---|---|---|---|
| 岗位档案 | `AgentCapabilityDescriptor`（key/vendor/role/**strengths/probe**） | `AgentProfiles.Profile`（`AgentProfiles.kt:26-31`：`key / vendor / displayName / defaultRole`，`Role = WORKER/REVIEWER/BOTH`） | `strengths` / `probe` / 「role 分架构·实现·审核·测试」均未实现；在位是 `normalizeKey` + `availableKeys`，健康是 `AgentHealthLedger`，没有探针字段 |
| 计划数据 | `StageChainPlan` | `DelegationPlan`（`DelegationPlan.kt`，`Source.AUTO/EXPLICIT`）；`ChainStage.role` 是「开发」/「审核」字符串 | 名字不同 |
| 熔断 | `MAX_TURNS = 3` / `NEEDS_HUMAN_INTERVENTION` | `DesktopWorkerOrchestrator.kt:1420` 局部 `maxHealRounds = 3` + `HealReport.bannerOf` 交还横幅 + `ticket.stage = "blocked"` | 无同名常量与状态 |
| 策略降级 | `STRICT` / `PERMISSIVE` + 「⚠️ 同源自审」标签 | `StageChainGate.kt:26-35` 的 `Pass` / `Downgrade` / `Reject` | **同厂商一律 `Reject`（不分计划来源）**；`Downgrade` 只在自动计划**本身只有一棒**时发生，且是「只留一根干活棒」而非「同源自审」（`:55-62,73-83`）。根本不存在放行同源自审这条路 |
| 验收 | Lint / Test / Build 三项 + `evidenceId` / `ChannelFileTransfer` | `TicketVerification.verifyReport()` 只有「重放补丁」+「仓库测试」两项（只认 `scripts.test`，其余 `skipped`）；详报走 `ArtifactPipe` → `sendAgentArtifacts`（`purpose=artifact`），卡内带 `intent` / `delivered` | 旧通道名不是生产组件；`ChannelFileTransfer` 只是 `:core` jvmTest 的测试类名 |
| Planner 降级 | 超时 >30s + 二级「一键指引卡片」 | `PlannerLlm.DEFAULT_TIMEOUT_MS = 20_000L`，失败单层回落 `parseAuto` 启发式两棒 | 二级降级不存在（v1.2 补） |

五个落地文件（`AgentProfiles.kt` / `DelegationPlan.kt` / `StageChainGate.kt` /
`TicketVerification.kt` / `BlindSpotPatrol.kt`）都在，`DelegationParser.parse` 也确实在
`DesktopWorkerOrchestrator.kt:589,662` 被调用 —— 所以不是「规范没实现」，而是
**实现用了另一套名词与另一套策略**。这正是 `AGENTS.md §2.4` 最忌的形状。
**裁决原则：以代码真名为准；要么补实现，要么改规范文字，不许两边各留一份。**
**执行结果（v1.2）**：11-03 Spec 已按上表逐节改写（岗位档案、双挡判据、三态门、20 s 回落、
验收两项、`ArtifactPipe` 直投、`maxHealRounds` 交还、盲区日志行、模块名 `:agent-contract`），
并新增 §5.3「规范 ↔ 实现单源对账表」把旧写法列为作废项。**纯文档改动，未动任何源码。**

### B2 `[PASS]` 反向耗 Token
§3 写「虚拟成员接收非强制消息时，若**推理判定**无需介入则发 `[PASS]`」——
为了省一次调用先付一次调用。现有规则是「未唤醒就根本不调模型」（`PROTOCOL-EXTENSION.md:776`、
`README.md:79`）。静默必须是路由层**代码硬判**，`[PASS]` 只能是诊断产物。
两条附加风险：① `CodecAgent.kt:119` 的 `eventType` 取值不封闭，看起来「加个值就能上线」，
但 `AgentEventForwarder.kt:584-587` 的转发判据只看 `alreadyInChat`，任何 agent-event 都会进群；
② `DesktopStageChain.kt:341` 注释里 PASS 已表示「审查棒判通过」，同名会污染排障字面量匹配。

### B3 前缀缓存论断错误
KV cache 复用的是**从位置 0 起的最长公共前缀**。把动态快照挪成「第一条历史消息」仍在序列最前，
只要快照变一个字节，其后所有 token 的缓存都失效；从 System Prompt 挪到第一条消息并不改变这一点。
而且「System Prompt 字节级恒定」与「bot 专有记忆要生效」互斥（生效就得进上下文）。
本仓更特殊：prompt 由进程外多厂商驱动自拼，没有统一缓存统计面；
`PROTOCOL-EXTENSION.md:770-772` 还规定切工作区要物理重置上下文 —— 与「前缀长期稳定」直接冲突。
**正确方向**：稳定前缀（system + 工具声明 + 长期不变的规则）固化并单独缓存，动态内容**后置**
到本轮消息末尾或 provider 支持的独立缓存段；先补「prompt 字节稳定性 + cache hit 读数」观测钉子。

### B4 投屏查看 ≠ 远程控制
`ScreenPayload.kt` 全文件只有会话协商（request/accept/reject/end）与画面帧
（Open/Ready/Next/Frame），没有任何输入事件、坐标或按键回传；`rv1`=查看、`rvs1`=共享。
「用户在手机上完成触控 + 完成接管后 Agent 自动恢复上下文」需要一整套新能力：
双向输入帧设计（归一化坐标/事件类型/seq/幂等/限速）、宿主侧输入注入与系统权限、
逐次授权与「交还」状态机、新 cap 声明与台账、L3 风险档与安全审计。
这是**新的协议族**，不是「基于现有 `screen` 协议」，一周 Phase 3 不成立。

---

## 4. 修订后的落地边界与里程碑重排

**Gate 0（v1.1 提出 → v1.2 已完成）**：规范 ↔ 实现单源对账已执行，裁决结果落在
`SPEC-ORCHESTRATION-2026-11-03 §5.3`；后续排期不再被它阻塞。

**真新增仍是三块**：
| 块 | 内容 | 归属模块 | 前置 |
|---|---|---|---|
| N1 | 路由层静默的代码判据 + `SKIP` 诊断可读性（三不守卫） | `:orchestration`（不动 `:core`） | 与 §14.5 唤醒规则对齐，明确诊断日志通道 |
| N2 | 移动端快捷入口（Slash 芯片 + stdout 单行进度条） | `:app`（解析）+ `composeApp`（共享组件）+ `:cli`（验收） | 见 §6 清单 |
| N3 | 卡点接管（新协议族 + 宿主注入 + 授权状态机） | `:core` / `:agentNode` / `:desktopApp` / `:composeApp` | **单独立项**，先出协议规格与权限模型 |

**按依赖重排**（不写周数）：
1. Gate 0 对账 → **已完成**（11-03 Spec §5.3）+ 裁决差异表（本文）；
   「合并后的单一规范」**已完成并入**（Spec §6~§14），草案已删除、本文归档。
2. N1 + 观测钉子（prompt 字节稳定性 / cache hit / TTFT 计时点定义）——先有读数再谈优化。
3. N2 —— 共享组件 + 领域解析 + 文案资源 + 设计门，与现有文本指令共用执行路径。
4. N3 —— 独立 RFC、独立版本协商、独立真机验收。
5. 全量门禁 + 串行 battery（`--max-workers=1` + `:testStabilityGate`）。

---

## 5. 验收口径重写

| 原指标 | 新口径 | 归类 |
|---|---|---|
| 主屏气泡 ≤3 块 | 由已有 `(cardId, rev)` 单槽折叠做**确定性断言**（同一 cardId 只渲染最新 rev） | 可进门禁 |
| 打字字符数降 ≥80% | 固定「跑测试并审阅」任务定义，断言 UI 点击次数与实际输入字符数（纯函数/截图测试） | 可进门禁 |
| 3 跳内收敛 100% | 改为对编排器**纯函数**的合成输入测试：固定 `DelegationPlan` → 断言轮次计数与熔断翻面 | 可进门禁（不调模型） |
| Token 节约率 ≥60% | 需定义对照组（同批 prompt / 同模型 / 温度 / 轮数）与「无效调用」判定口径 | 离线采样报告 |
| 首字延迟降 ≥40% | 计时点定在「宿主 → 模型 API」，**排除 P2P 投递**；真机记录投递 120–125 s 且乱序、两端时钟差 13 s、`MessageEntity.timestamp` 是发送方时钟，端到端耗时不能当 TTFT | 离线采样报告 |
| 50 次模糊需求 100% 收敛 | LLM 非确定且依赖外部服务，会撞 `envDependentTestGate` | 离线采样报告，不作门禁 |

`[PASS]`（改名后）的验收应是**源码守卫 + 诊断日志探针**，而不是抓包看帧：
断言「未唤醒消息不产生任何模型调用 / 不产生 `MessageEntity` 行 / 未读数不增 / `RelayLog` 有且仅有一条 SKIP」。

---

## 6. N2 落地前的硬清单

- **共享**：芯片做成 `MessageComposer` 的参数/接缝（两端逐字共享，`AGENTS.md §2.6`）；`ux-scoreboard` 跨壳重复分母含 `desktopApp`，人为放同名 Composable 会红。
- **分层**：指令解析落 `:app`（零 Compose，`:domainComposeFreeGate`），`:cli` 复用；中文文案进 `composeResources/values*/strings.xml`（`cjkLiteralGate` 棘轮只许降不许升），过 `designLocaleParityGate`。
- **设计门**：`designScreenDiffGate` / `designIconGate` / `designConvergeGate` / `designFormPairingGate` / `designPreviewPairingGate` / `designDupFrameGate` / `designDpDebtGate` 全套 + 截图夹具对账。
- **`/stop` 单一路径**：与现有文本 `stop` 共用执行与回执；进程树按 pid 集合逐个核对；覆盖两条派单路；「打断有效」不能当「编排器在跑」的证据。
- **`/status` `/diff`**：读工作树不读 commit；明确群绑定 `wsId`；本机绝对路径不进协议。
- **版本**：新增帧/键 ⇒ 旧端整帧拒收，两端必须同版本；补协商、双端同版验收与回滚边界（含 `.sqm` 不可回滚那条）。

---

## 附录：证据索引（本仓）

| 主题 | 文件 |
|---|---|
| Chief / 唤醒 / HITL / 上下文重置 | `PROTOCOL-EXTENSION.md §14.2-14.5`、`README.md:79` |
| 双挡委派 / 熔断 / 卡片预算 / 盲区巡检 | `design/spec/orchestration-model-fit-2026-11-03.md` |
| 防刷屏 / 单向流水线 / 三层克制注入 / assign / ask_question | `docs/lan-team-agent-im-detailed-design.md §7.2, 7.4, 7.5, 7.7, 7.8, 7.9` |
| 协议白名单与取值域 | `core/.../protocol/CodecMsg.kt`、`CodecAgent.kt`、`MessageTypes.kt`、`Limits.kt` |
| 投屏协议形状 | `core/.../protocol/ScreenPayload.kt` |
| 事件转发与数据目录 | `orchestration/.../AgentEventForwarder.kt`、`composeApp/.../AndroidPlatform.kt` / `IosPlatform.kt`、`desktopApp/.../Main.kt` |
| 委派实现真名 | `agentNode/.../DelegationPlan.kt`、`AgentProfiles.kt`、`orchestration/.../StageChainGate.kt`、`DesktopWorkerOrchestrator.kt` |
| 门禁与硬约束 | `AGENTS.md §1, §2.1-2.6` |
| 已落地 UI | `ThinkingBlock.kt`、`AgentCardParse.kt`、`GroupProgressBar.kt`、`AgentCardLogic.kt`、`AgentCards.kt` |
