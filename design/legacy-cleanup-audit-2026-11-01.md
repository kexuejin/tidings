# 历史遗留清理清单 · 2026-11-01 全量排查

> **执行记录（同日深夜，按本文档执行清理）**：
> - **已删**：A1（WsGateway/WsCodec + tests + agentNode/tools 跨网五件 + 文档提法）、A2（ModelEnvMapper + 2 测试 + AgyCliDriverTest 两用例）、A3（6 个零调用 SQL 查询；AgentEventLoadCapTest 改用 store 全量对照）、A4（cli 的 :agentNode 依赖）、A5（123 个死字符串 key，中英成对，机器核对零引用）、B1（OpenAiDriver）、B2（DsBotDriver + selectDriverIdForUserPrompt + DriverHonestyTest）、B4（**修正后 10 条**：见下）、B5（AgentGrantRuleEntity，19.sqm DROP）、B6（WorkspaceBindingEntity + Manager + 2 测试，19.sqm DROP）、B9（legacy JSON 镜像：SQLite 非空/迁移完成即 retire——删文件停写，save 加退役守卫）、B10（PreviewState.kt 挪进 androidApp/src/screenshotTest，同包免改 import）、C2/C3（16.sqm 注释、agentSenderIdFor 死兜底 KDoc）、D1-D7（前一轮已修）。
> - **B4 修正**：原列 11 条中 `GET /v1/pairing-qr` 与 `GET/POST /v1/agent/session-pages*` **保留**——用户指出消费方在 `dsh-relay` 仓（src/tidings.ts:854/889/900 实调，session-pages 队列由其 session-page-responder 轮询回填，PROTOCOL-COVERAGE.md:57 同证）。实际删除：`/v1/announce`、`/v1/candidates`、`/v1/agent/queue`、`/v1/agent/run`、`/v1/agent/cancel`、`/v1/agent/artifact`、`/v1/agent/ack`、`GET/PUT /v1/agent/exec-policies`、`GET/PUT /v1/agent/exec-devices`（dsh-relay 全部路由调用面已核对，均不在其中）。
> - **保留不动（附因）**：B3 NativeRelayDriver（注册在案的默认兜底，非死代码）；B7 FilePurpose.UPDATE/STICKER（对端协议互通词汇，删前须对齐对端）；B8 CAP_MSG_AGENT_SESSION（互通约定名）；C4 last_message_id（写而不读但无害，随下次换表迁移去）；D8 已在 README 补「枚举以插件仓为准」；D9 产品裁决未做（DSH 会话无任务卡终态面）。
> - 数据库版本 19 → **20**（19.sqm 只做 DROP）；ScreenHistoryRecordTest 版本断言同步 20。

> 排查口径：产品定位已调整（agent 主线 = CLI 派单 + dsh 状态投影桥），按「最终代码没有任何历史包袱」的标准逐文件核对引用方。每一条都附 grep 级证据；「仅测试引用」与「零引用」分开列，删除动作分三档：**A 可直接删 / B 待裁决删 / C 必修的断链（不是删，是补）**。
>
> 本文档只列清单与风险，不含任何代码改动。

---

## 0. 前置结论：两种 agent 接入模型并存（不是一新一旧）

| | codex / claude / antigravity（CLI 派单） | dsh（DeepSeek Harness 桥） |
|---|---|---|
| 谁指挥 | 宿主。`DesktopWorkerOrchestrator` 在 worktree 沙箱拉起 CLI 子进程，拥有完整任务生命周期 | DSH 自己。宿主不指挥，只做状态投影 |
| 数据入口 | `executeCliTask` / `executePluginTask`（ControlPlane.kt:981/842，进程内调用） | 外部插件 dsh-tidings-bridge 回环 HTTP：`POST /v1/agent/session·event·workspace·approval`（ControlPlane.kt:1846/2529/1934/3047） |
| 反向通道 | 手机派单帧 → 宿主 | dsh 插件轮询 `GET /v1/inbox`（DesktopExtension.kt:1058-1073 注释明确分工） |
| 任务卡生命周期 | 有（受理→待合并→合并/受阻→产物免接收） | 没有。终态只在工作区看板/会话页表达 |
| 显示漏斗 | AgentSessionRegistry → `DesktopExtension.onRecordEvent` → `formatAgentEventForChat`（白名单） | **同一条**（已核实：/v1/agent/event 的 onSent 同样 `sessions.recordEvent`，ControlPlane.kt:2591-2599） |

**结论**：显示层已经在 2026-11-01 白名单方案下统一，dsh 不需要为显示做适配；两种模型的差异是架构分工（宿主指挥 vs 自治投影），不是新旧方案。antigravity 的 hook 面（`/v1/agent/hook/*` + `scripts/antigravity-tenon-hook.py` + `tenon-mcp-server.py`）是给外挂进程的注入/上报通道，属主路径。dsh 需要更新的只有 **§4 列出的脚本与文档残留**，另有 **§4.6 一项产品裁决**。

---

## 1. A 档：零引用，可直接删（风险最低）

| # | 对象 | 位置 | 证据 | 删除影响 |
|---|---|---|---|---|
| A1 | `WsGateway.kt` + `WsCodec.kt`（整个文件） | agentNode/src/main/.../agent/ | 全仓唯一实例化在 WsGatewayTest.kt:91-211；desktopApp 的 Main/DesktopExtension 均不启动它，:8789、/v1/pipe、/v1/dgram 运行时不存在。「手机↔桌面跨网 WS 隧道」方案从未接入产品 | 连带删 WsGatewayTest、agentNode/tools/ws-link-check.html、ws-relay-probe.py、ws-soak.py；CONTRIBUTING.md:31 的提法同步改。**注意**：若「手机跨网隧道」还在产品路线图上，改为在文档标注「未接线」而不是删 |
| A2 | `ModelEnvMapper.kt` | agentNode/.../drivers/ | 生产装配从不传 envOverrides（CliSubprocessDriver.kt:46 默认 emptyMap）；模型凭证注入链路未接线；仅 ModelEnvMapperTest、AgyCliDriverTest 引用 | 删时连带两个测试文件 |
| A3 | 6 个零调用 SQL 命名查询 | app/src/commonMain/sqldelight/.../RelayDatabase.sq | `updateMessage`(:216)、`updateDelivery`(:211)、`selectMessagesAfter`(:204)、`selectAllAgentEvents`(:668)、`selectAgentSessionsByNode`(:633)、`selectAgentEventsBySession`(:672，仅 AgentEventLoadCapTest 引用) | 命名查询不涉盘上结构，**无迁移风险**；selectAgentEventsBySession 删时同步删对应测试断言 |
| A4 | cli 对 `:agentNode` 的无用依赖 | cli/build.gradle.kts | 代码零引用（`--serve` 已移除）；注释口径「只依赖 :app」已失真 | 无 |
| A5 | 128 个死字符串 key（中英成对） | composeApp/.../values/strings.xml + values-en/strings.xml | 全仓 `Res.string.<key>` 零引用，key 集合两份一致（共 1415 个）。整组残留对应已完成的界面改版：agent 执行策略面板 11、节点页改版 18、历史搜索 9、Inbox/工作区 15、桌面壳 12、卡片文案 11、group_auth 11、头像自定义 5、传输日志 8、设置页 8、其余零散（含 `agent_demo_driver_notice`） | 纯资源删除，无逻辑影响；注意 screenshotTest 若有引用需先核对（已核实仅注释提及） |

## 2. B 档：遗留/demo，删除需产品裁决

| # | 对象 | 位置 | 现状 | 裁决建议 |
|---|---|---|---|---|
| B1 | `OpenAiDriver.kt` | agentNode/.../drivers/ | 自认 stub：openSession 直接 throw（:36-39），executeTask 编假回复；注册于 DriverRegistry.kt:25 但无生产选择路径 | 删（连 DriverRegistry 注册行 + DriverTest 对应用例）。它是「不产出假回复」收敛意图的违背者 |
| B2 | `DsBotDriver.kt` + `selectDriverIdForUserPrompt` | agentNode/.../drivers/ | 自述「演示驱动、本地模拟器」；选择函数仅 DriverHonestyTest 调用 | 删或显式标注 demo-only 并挪出主注册表 |
| B3 | `NativeRelayDriver.kt` | agentNode/.../drivers/ | 「诚实不执行」的默认兜底（defaultDriverId="native"）；DesktopExtension.kt:1058 注释自述「不再跑本地驱动、由 DSH 注入桥承担」 | 半退役兜底。裁决点：无 CLI 宿主的降级场景还需不需要「诚实的占位」；若删，`resolveCliDriver("native")` 路径与 NativeRelayDriverHonestyTest 连带处理 |
| B4 | ControlPlane 零调用方路由（11 条） | ControlPlane.kt | `/v1/agent/run`、`/v1/agent/cancel`（仅文档提及）、`/v1/agent/artifact`、`/v1/agent/ack`（全仓零调用）、`GET/PUT /v1/agent/exec-policies`、`/v1/agent/exec-devices`、`GET /v1/pairing-qr`、`POST /v1/announce`、`GET /v1/candidates`、`POST /v1/agent/queue`（仅测试）、`GET/POST /v1/agent/session-pages*`（宿主走进程内台账，HTTP 镜像仅测试） | **删前核对另仓 dsh-tidings-bridge 是否调用**（本仓 grep 不到不等于没人用）。session-pages 若分页回填走 HTTP 版则保留 |
| B5 | `AgentGrantRuleEntity`（幻影表） | RelayDatabase.sq:587-595（18.sqm 建） | 零读写。注释声称的消费方 ApprovalPolicyEngine 全仓不存在 | 铲（需新 .sqm 做 DROP TABLE）或明确标注 T-3 线预埋 |
| B6 | `WorkspaceBindingEntity` + `WorkspaceBindingManager` | RelayDatabase.sq:565-580；app/.../workspace/ | 已建未接线：Manager 只被自己的测试构造，RelayController 无引用（T-2b 半成品） | 同 B5：要么接线要么铲 |
| B7 | `FilePurpose.UPDATE` / `FilePurpose.STICKER` | core/.../FileTransfer.kt:90-91 | UPDATE 无生产者（MsgTypes.UPDATE 在 UNHANDLED，防桌面推自更新包）；STICKER 本仓无生产者 | 疑为茶话间/TS 互通收侧词汇，**删前必须对齐对端协议**；不动是安全选项 |
| B8 | `CAP_MSG_AGENT_SESSION`（"ams1"） | MessageTypes.kt:349 | 全仓唯一 0/0 cap；注释明示「刻意保留」（互通约定名） | 按团队约定保留即可，不算包袱 |
| B9 | legacy JSON 镜像（`FileMessageStore` ×3 平台） | app/src/{androidMain,jvmMain,iosMain}/.../MessageStore.*.kt | 只在 SQLite 为空时读一次做迁移，**但每次 save() 仍全量重写镜像**（SqlDelightMessageStore.kt:250-253，真机实测 4 MB/6656 条）——迁移完成后是纯写放大 | 产品定位定稿后加开关淘汰：首次迁移成功即删镜像文件并停写。这是清单里唯一有持续运行时代价的遗留 |
| B10 | `PreviewState.kt`（836 行假数据，public，在 commonMain 产品源集） | composeApp/.../ui/desktop/ | 消费方全在 screenshotTest（12 文件） | 测试夹具长在产品源集。裁决：挪到 screenshotTest 源集自持（需复制夹具），或接受现状 |
| B11 | examples/plugins/echo-team-plugin | examples/ | 插件协议参考实现 + 手动 smoke 装置（ARCHITECTURE/ACCEPTANCE 引用） | 插件通道保留则留；随 B 组插件退役才动 |

## 3. C 档：必修的断链与误导项（不是删，是补）

| # | 问题 | 位置 | 影响 | 修法 |
|---|---|---|---|---|
| C1 | `AgentArtifactEntity.sha256/summary` **写而不读** | 写：SqlDelightMessageStore.kt:772-785；读：loadArtifacts(:631-643) 不选这两列 | **用户可见缺陷**：重启后产物校验退化为 UNDECLARED（AgentApprovalCards.kt:940 读的是内存模型） | loadArtifacts 补两列映射，或删列（后者需 .sqm，且这两列是桌面库版本哨兵的一部分，DatabaseDriverFactory.jvm.kt:122-131，**不能轻删**）→ 建议补读侧 |
| C2 | 16.sqm 注释与现行语义相反 | migrations/16.sqm | 注释说「卡片更新以新消息进流，不重写旧行」，18.sqm/D-026 已裁决原位刷新（rev>existingRev 替换） | 改注释，防止下一个人按旧语义实现 |
| C3 | `agentSenderIdFor` 的死兜底文档 | DesktopExtension.kt:1788-1794 | KDoc 说「kind 为空退回 agent:dsh」，实际调用链在 1744 行已 return null，分支不可达 | 删该分支或改 KDoc |
| C4 | `ConversationSummary.last_message_id` 整列 write-only | RelayDatabase.sq:170；模型字段 RelayCoreState.kt:146、RelayUiState.kt:622 | 无读取方 | 低风险，随下次换表迁移一并去掉；短期留着无害 |

## 4. dsh 接入尚存的残留 / 未打通之处

**链路本身已闭合**（README 承诺的控制面底座路由全部真实落地；手机端三面——工作区看板、会话详情、群任务卡——与宿主数据源完整核对无缺失）。残留集中在脚本、规格文档与产品语义：

| # | 残留 | 位置 | 说明 |
|---|---|---|---|
| D1 | 桥模板拉收件箱**永远返回空** | scripts/bridge/agent_relay_bridge_template.py:165-166 | 读 `resp.get("items", [])`，控制面实际返回键是 `messages`（ControlPlane.kt:3620）。工具 #4「拉取指令与审批结果」功能性失效 |
| D2 | 桥模板 announce 不带 agentId | 同上 :100-137 | verify 脚本（:339-352）明确要求带 agentId，注释自述「只报 agentKind 会让同一工作区在手机上拆成两行」——模板照抄即踩此坑 |
| D3 | 规格文档与实现脱节 | design/spec/dsh-tenon-bridge-spec.md:59,112-133 | 承诺的 `POST /v1/inbound` 路由不存在；/v1/inbox 示例字段（items/ackFor/status）与实际（messages/ackOf/kind）不符 |
| D4 | 审批 choices 基线漂移 | scripts/run-live-dsh-flow.py:109 | 仍发旧两档 `allow,deny`；基线已收口四档 `allow,deny,allow_session,always`（MessageTypes.kt:682-687）。显式声明合法但手机端看不到「本次免审/总是允许」 |
| D5 | 脚本写死历史环境 | run-live-dsh-flow.py:61 | 写死历史 nodeId fallback（f6a7b8c9-…），换设备失效 |
| D6 | E2E 夹具词汇漂移 | scripts/e2e/e2e_workspace_collaboration.py:214 | `eventType:"tool_call"`（下划线）≠ 线协议词 `tool.started`；能 ACK 能落台账但进不了任何语义分支 |
| D7 | run_all 排序遗漏 | scripts/e2e/run_all.py:44-54 | PREFERRED_ORDER 未列 workspace_collaboration（dsh 桥契约唯一夹具） |
| D8 | README「九个 agent 工具」本仓不可核验 | README.md:253 / README_EN.md:230 | 枚举本体在另仓 dsh-tidings-bridge；建议另仓 README 或本仓 spec 补清单，否则永远核不了 |
| D9 | **产品裁决**：DSH 会话无任务卡终态面 | — | CLI 任务有卡（受理→合并/受阻），DSH 会话终态只在工作区看板。新显示方案下 DSH 的 finished/error 在群里沉默（白名单正确行为），但用户若只在群里看，会话结束无感知。要不要给 DSH 会话也发卡是产品决策，不是缺陷 |

## 5. 明确不是包袱（防误删）

- `MessageEntity` 的 notice/card_json/card_payload/agent_session_*/screen_json/file_json/agent_state/seq 列全部存活，各有读写方与守卫测试。
- 18 个 .sqm 迁移链全部服务于老库升级（card_payload/sha256 同时是桌面库版本哨兵），**一个都不能删**。
- `cleanupDirtyDshSessions` 等 .sq 自愈查询在启动路径活跃。
- 白名单制 `formatAgentEventForChat` 周边无旧映射表残留（`assistant.message/text/message` 是白名单成员非旧分支），且被 AgentChatMessageConvergenceTest 源码钉死。
- reasoning 键退场、`agent-ev-` 前缀回退删除等历史项均有守卫测试确认清干净。

## 6. 建议执行顺序

1. **C1**（用户可见缺陷，最优先）→ 2. **A1/A2/A3/A4**（零风险机械删）→ 3. **D1/D2**（桥模板两处真 bug，随删随修）→ 4. **D3/D4/D5/D6/D7**（脚本与文档对齐）→ 5. **A5**（128 key 成对删，机器核对后人工抽查）→ 6. **B 档逐项过裁决**（B1/B2 建议直接删；B4 删前查另仓调用；B9 需要设计淘汰开关）→ 7. **C2/C3/C4** 随手清。
