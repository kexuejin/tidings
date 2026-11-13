# SDLC 生命周期 harness 读数（2026-11-13T04:35:17.497271+00:00）

- tier：`headless`；stage：`current`（锚点 `HEAD`）；轮次：`run-1`；agent：dsh
- 工作区：`~/weather-relay`；只读源仓：`~/weather-relay`
- 基线 HEAD ``，基线测试 pass=None fail=None
- 装置提交（非被测行为）：无

| 幕 | 标题 | 链型 | 结论 | 判据读数 |
| --- | --- | --- | --- | --- |
| 0 | 环境体检 | local | FAIL | cli:dsh=ok / bundle-entry=ok / install-consistency=ok / dsh-credentials=ok / control-plane=FAIL / not-run=FAIL / not-run=FAIL / not-run=FAIL |
| 1 | 需求确认 | cli-direct | NA | not-run=FAIL |
| 2 | 新功能实现 | cli-direct | NA | not-run=FAIL |
| 3 | 审查与合流 | local | NA | not-run=FAIL |
| 4 | 追问续接 | orchestrator | NA | not-run=FAIL |
| 5 | 缺陷定位修复 | cli-direct | NA | not-run=FAIL |
| 6 | 收尾验收 | local | NA | not-run=FAIL |
| i1 | 超纲退单 | orchestrator | NA | not-run=FAIL |
| i2 | 硬打断 | orchestrator | NA | not-run=FAIL |

**结论**：FAIL 幕 0；根因 control-plane（下游标 not run，不报派生症状）。

- doctor：`mode=deep`，`root_cause=control-plane`，`exit_code=1`；报告见 `doctor.json`（与终端读数同源）。
- 边界：静态层只归因，execution 真跑才是权威；bundle/ESM 在静态检查之后加载，静态全绿不等于能跑

> `cli-direct` 幕只证明「真 agent 真沙箱真补丁」，不证明编排器的工单卡/追问/自愈/合并按钮；
> `orchestrator` 幕在 headless tier 下如实标 NA，两者不得互相冒充。
