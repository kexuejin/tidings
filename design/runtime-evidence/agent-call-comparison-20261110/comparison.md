# 多调用方案对比读数

三同原则：同一任务书 / 同一阶段锚点 / 同一判据集合。破一条则本表不给差异结论。

## 控制面探测

```
{"reachable": true, "http": 400, "chain-executor": "present", "body": "{\"ok\":false,\"error\":\"bad-request\",\"message\":\"stages 至少要有一棒，且每棒都要 role/agentKey/instruction\"}"}
```

## 各档状态

| 轮次 | scheme | 入口 | 链路 | 状态 | 原因 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `single/run-1` | `single` | `POST /v1/agent/hook/dispatch` | `cli-direct` | **measured** | 锚点 3792076，证据 run-1 |
| `single/run-2` | `single` | `POST /v1/agent/hook/dispatch` | `cli-direct` | **measured** | 锚点 3792076，证据 run-2 |
| `chain` | `chain` | `POST /v1/agent/chain` | `stage-chain` | **available-not-run** | 控制面真探测 400（空 stages 校验通过）⇒ chainDispatcher 已装配；本轮未真跑 stages，且尚未与 single 档做等价任务映射，不给差异结论 |
| `team` | `team` | `POST /v1/agent/invoke` | `orchestrator` | **requires-device** | 需群 + 席位入驻 + 手机 @ 派单；harness 不自动触发，不假跑 |

## 指标横向对比

> 只列各档同名指标；`unavailable` / `unmeasurable` 不填数字，不给差异。

| 指标 | `single/run-1` | `single/run-2` | 状态 |
| :--- | --- | --- | :--- |
| `acts-pass` | 9 | 9 | measured |
| `acts-total` | 9 | 9 | measured |
| `duration-sec` | 441.13 | 684.67 | measured |
| `dispatch-count` | 5 | 5 | measured |
| `patch-files` | 8 | 7 | measured |
| `patch-added` | 531 | 604 | measured |
| `patch-removed` | 0 | 0 | measured |
| `commits-count` | 6 | 6 | measured |
| `prompt-chars-total` | 1500 | 1500 | measured |
| `prompt-chars-max` | 300 | 300 | measured |
| `prompt-sha256-list` | ["94e2dec245b46dd3", "14133595510ae1e8", "21307876ac32c656", "b01008ff | ["94e2dec245b46dd3", "14133595510ae1e8", "21307876ac32c656", "b01008ff | measured |
| `dispatch-rounds` | 5 | 5 | measured |
| `same-card-id-reused` | — | — | unavailable |
| `tests-pass` | None | None | measured |
| `tests-fail` | None | None | measured |
| `usage-lines` | 59 | 71 | measured |
| `semantic-rejected-lines` | 0 | 0 | measured |
| `input-tokens` | 66150 | 152241 | measured |
| `output-tokens` | 35958 | 54765 | measured |
| `billable-tokens` | 102108 | 207006 | measured |
| `cache-read-tokens` | 606720 | 1065856 | measured |
| `context-window-tokens` | 708828 | 1272862 | measured |
| `cache-hit-ratio` | 0.8559 | 0.8374 | measured |
| `context-peak-tokens` | 20121 | 37043 | measured |
| `context-final-tokens` | 15743 | 15403 | measured |
| `step-count` | 59 | 71 | measured |
| `turn-count` | 1 | 1 | measured |
| `cost-usd` | — | — | unmeasurable |
| `heal-rounds` | — | — | unmeasurable |
| `memory-survives-restart` | — | — | unmeasurable |
| `memory-hit-rate` | — | — | unmeasurable |
| `window-message-count` | 0 | 0 | measured |
| `whole-db-card-count` | 194 | 194 | measured |
| `card-rows-in-window` | — | — | unavailable |
| `card-type-histogram` | — | — | unavailable |
| `card-rev-max` | — | — | unavailable |
| `card-state-sequence` | — | — | unavailable |
| `card-action-buttons` | — | — | unavailable |
| `card-artifact-entries` | — | — | unavailable |
| `agent-state-banners` | — | — | unavailable |
| `preview-entry-wired` | False | False | measured |
| `human-interaction-events` | — | — | unmeasurable |

## 不可测项（如实留白，不估算）

| 指标 | 原因 |
| :--- | :--- |
| `cost-usd` | 无 model/provider 单价表；不允许按估算单价乘出假成本 |
| `heal-rounds` | heal loop 未结构化落盘 |
| `memory-survives-restart` | ActiveTicket / AgentSessionRegistry 纯内存，需重启探针 |
| `memory-hit-rate` | 无记忆命中埋点 |
| `human-interaction-events` | 卡片动作无客户端回执埋点，真机点击需人工记录 |

## 证据

完整读数（含每条 source 与 sha256）：`comparison.json`。

关键证据文件：
- `single`：`/workspace/src/design/runtime-evidence/sdlc-repeat-fixed-20261110/run-1` sha256=`72b35d3bb297e7637756c846b3b1cdad13477e21af9c1bd1c8530dc8077e7358`
- `single`：`/workspace/src/design/runtime-evidence/sdlc-repeat-fixed-20261110/run-2` sha256=`2986e654e7dc8fbf2de0922b581dbc93e6f5128719eea9f6f807849267b37492`
