# 多调用方案对比基准：首份真读数（2026-11-10）

**结论**：可衡量、可回溯的对比装置已落地并真跑出读数；**纠正上一轮「token 完全没有计量」的判断**——
产品运行时确实没有结构化用量埋点，但 `task.log` 保留了 `dsh` 原始 stdout 的 `usage` 行，
所以 token / 上下文 / 缓存复用率**都能从真值回溯**，不需要估算、不需要改产品源码。
三档链路状态：`single` = `measured`，`chain` = `available-not-run`（执行器实测已装配），
`team` = `requires-device`。**本轮不给「哪种方案更好」的结论**，因为只有一档有读数。

## 一、装置

- `scripts/sdlc-compare.py`：从 `task.log` / `patch.diff` / `result.json` / `relay.db` 采集，
  每条读数带 `source` 路径与关键文件 `sha256`；`--dry-run` 22 项守卫全绿。
- `docs/TEST-PLAN-AGENT-CALL-COMPARISON.md`：指标字典、三同原则、不可测项补齐路径。
- 证据：`comparison.json`（完整）、`comparison.md`（人读）、两轮 `run-1`/`run-2` 原始 `result.json`。

`[事实](来源:scripts/sdlc-compare.py --dry-run,时间:2026-11)` 22 项守卫 0 红，含
「billable 不是 totalTokens」「usage 语义断言」「卡片 0 有反向探针」「同档多轮不覆盖」
「availability 靠真探测」「本地回环绕过代理」。

## 二、token 语义与取证覆盖度

`[事实](来源:/tmp/tenon-ws/artifacts/*/task.log,时间:2026-11)` 70 份日志中 **69 份**含 usage 行，
合计 **1327 行**，全部为 `{"type":"status","phase":"step_end","usage":{...}}`；
字段组合 1275 行带 `cacheReadTokens`、52 行不带。逐行可验：

```
totalTokens = inputTokens + outputTokens + cacheReadTokens
```

`totalTokens` 是**该步送进模型的上下文窗口量**，不是累计消耗，禁止当账单。
本轮两轮共 130 行 usage，`semantic-rejected-lines=0`（口径全部吻合，没有混入别的 CLI 形态）。

`[事实](来源:agentNode/.../drivers/CliLineEventMapper.kt:34-79 + ControlPlanePluginGateway.kt:431,576,时间:2026-11)`
产品运行时把只带 usage 的行判成 `null` 丢弃，`Finished` 映射又丢掉 `totalTokens`；
但 `WorktreeSandbox.kt:275-289` 把**原始行**写进 `task.log`。
→ 结论是「产品 UI 还不能显示用量」，不是「用量无法回溯」。

## 三、首份真读数（同一任务书、同一锚点 `3792076`、同一判据）

| 指标 | run-1 | run-2 | 差异 |
| :--- | ---: | ---: | :--- |
| `acts-pass` | 9/9 | 9/9 | 相同 |
| `prompt-chars-total` | 1500 | 1500 | 相同（任务书逐字一致） |
| `dispatch-count` | 5 | 5 | 相同 |
| `duration-sec` | 441.13 | 684.67 | +55.2% |
| `step-count` | 59 | 71 | +20.3% |
| `input-tokens` | 66,150 | 152,241 | +130.1% |
| `output-tokens` | 35,958 | 54,765 | +52.3% |
| **`billable-tokens`** | **102,108** | **207,006** | **+102.7%（约 2 倍）** |
| `cache-read-tokens` | 606,720 | 1,065,856 | +75.7% |
| `cache-hit-ratio` | 0.8559 | 0.8374 | −1.85 pp |
| `context-peak-tokens` | 20,121 | 37,043 | +84.1% |
| `context-final-tokens` | 15,743 | 15,403 | 基本持平 |
| `patch-files` / `patch-added` | 8 / 531 | 7 / 604 | 产出量级相当 |
| `card-rows-in-window` | NA | NA | 见下 |

`[事实](来源:design/runtime-evidence/agent-call-comparison-20261110/comparison.json,时间:2026-11)`
以上全部 `measured`，来源为两轮各自的 `result.json`（带 sha256）与 10 个 `t-*` 产物目录的
`task.log` / `patch.diff`。

`[推断]+推理链条`：产出同为 9/9 PASS、任务书与派单数完全相同，但 billable 翻倍；
步数只 +20.3%，每步平均 billable 从约 1,731 涨到约 2,916（+68%），同时上下文峰值
+84%、末步上下文却回到同一水平 ⇒ 差异不主要来自「多跑了几步」，而来自**某一幕中途的
上下文膨胀**。这正是需要逐幕归因（把 usage 按 act 切分）才能定位的优化点，也是本装置
下一步最该补的粒度。`[假设]+不成立应对方案`：若两轮 agent 内部重试/工具调用次数不同导致
步数口径不等价，上述「每步」推断不成立；应对是补 `tool_call` 行计数与逐幕 usage 切分再比。

## 四、界面 / 卡片维度：如实标 NA，不写 0

`[事实](来源:relay.db MessageEntity,时间:2026-11)` 全库有 194 条 `card_json`，
但两轮 run 时间窗内 `window-message-count=0` ⇒ 卡片读数被反向探针降级为 `unavailable`，
note 明写「可能是链路本身不写 MessageEntity（直驱链预期），也可能是时间窗错位，
两者无法自动区分」。这是刻意设的防假绿守卫：不把窗口错位伪装成「卡片 0」。

`[事实](来源:ControlPlaneHookRoutes.kt:405 + ChatMessageList.kt:286-312,时间:2026-11)`
直驱链 `executeCliTask(..., null) { _,_,_,_ -> }` 是空回调、不绑会话卡片；
`onPreviewArtifact` 无生产传入（`preview-entry-wired=false`）。
→ **单 agent 直驱链在源码层就没有界面事件出口**，界面差异必须到 `chain`/`team` 档才有读数。

## 五、三档链路可用性（真探测，不静态猜）

| scheme | 状态 | 实测依据 |
| :--- | :--- | :--- |
| `single` | `measured` | 两轮真 dsh 派单，`result.json` 9/9 PASS |
| `chain` | `available-not-run` | headless 宿主起来后 `POST /v1/agent/chain` 空 stages 返回 **400**（`stages 至少要有一棒`），不是 503 ⇒ **`chainDispatcher` 已装配**；单进程 `agentNode` 才会 503 |
| `team` | `requires-device` | 需群 + 工作区宿主真入驻席位 + 手机 `@` 派单，harness 不自动触发 |

「能跑没跑」与「跑不了」分开记，不混用。空 stages 探测不会真起链。

## 六、不可测项（如实留白，不估算）

| 项 | 原因 | 补齐点 |
| :--- | :--- | :--- |
| `cost-usd` | 无 model / provider / 单价表 | 先定义计费口径；`PlannerLlm.postChat()` 响应里的 `usage` 目前也被丢 |
| `heal-rounds` / `memory-hit-rate` | 无埋点 | `AgentSessionRegistry` 结构化落盘 |
| `memory-survives-restart` | `ActiveTicket` 纯内存 | 需重启探针 |
| `human-interaction-events` | 卡片动作无客户端回执埋点 | `HostCardActions` 动作分支加事件 |
| `same-card-id-reused` | 直驱链无卡片 | `chain` 档按同 `cardId` 的 `rev` 序列判 |

## 七、现场发现（顺手修掉的三个真问题）

1. `desktop_db()` 被外部并行改动收成只认新目录 `榫卯 Tenon`，而实测 `relay.db` 仍在
   `捎话 Tidings` ⇒ `--tier real` 卡片观测会静默读空。已恢复新旧双目录按存在性探测，
   现场复核返回旧目录且 `isfile=True`。
2. 采集器首版把同档两次 `--run` 用 dict 存，后者覆盖前者；已改成 list，两轮都留证。
3. 卡片 0 可能是时间窗错位的假绿；已加 `window-message-count` 反向探针 + 全库快照对照。
4. 本地回环探测走了系统代理，宿主没起时收到代理层 502 被误读成控制面异常；
   已改 `ProxyHandler({})` 直连，真探测拿到 400。

## 八、边界

- 本轮**只覆盖 `single` 档真读数**；`chain` 可跑但未跑、`team` 需真机。三同原则下
  `chain` 的 `stages[].instruction` 必须逐字映射 `single` 的 9 幕 prompt 才可比，
  随便跑一条不等价的链然后宣称「对比完成」是假验证。
- token 口径 = `Σ(input+output)`，不含 cacheRead，与任何云厂商账单不等价，
  只用于同环境横向对比；换 `codex`/`opencode` 时若 `usage-lines=0` 一律标 NA。
- 卡片读数是本机 `relay.db` 快照，跨设备对比需指定同一 `conv_id`。
- 上一阶段欠账（880 行全绿 team v2 测试未提交、第 4 批用例、真机复验清单）本轮仍未动。
