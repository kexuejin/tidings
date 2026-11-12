# SDLC 生命周期 harness 读数（2026-11-12T00:29:53.892061+00:00）

- tier：`headless`；stage：`current`（锚点 `HEAD`）；轮次：`single`；agent：dsh
- 工作区：`/Users/developer/weather-relay`；只读源仓：`/Users/developer/weather-relay`
- 基线 HEAD `1437d00dae9c`，基线测试 pass=90 fail=0
- 装置提交（非被测行为）：['merge feature from sandbox']

| 幕 | 标题 | 链型 | 结论 | 判据读数 |
| --- | --- | --- | --- | --- |
| 0 | 环境体检 | local | PASS | control-plane=ok / cli:dsh=ok / dsh-credentials=ok / workspace-is-git=ok / workspace-clean=ok / stage-files-present=ok / stage-files-absent=ok / baseline-tests-green=ok / artifacts-dir=ok |
| 1 | 需求确认 | cli-direct | PASS | patch-is-real=ok / requirement-doc-produced=ok |
| 2 | 新功能实现 | cli-direct | FAIL | dispatch-harvest=FAIL |
| 3 | 审查与合流 | local | FAIL | apply-patch=ok / worktree-changed=ok / post-merge-tests-green=FAIL / baseline-visible-to-next-sandbox=ok |
| 4 | 追问续接 | orchestrator | NA | not-applicable=ok |
| 5 | 缺陷定位修复 | cli-direct | FAIL | fault-injection-red=FAIL |
| 6 | 收尾验收 | local | FAIL | final-tests-green=ok / no-test-count-regression=ok / no-orphan-cli-process=ok / no-failed-acts=FAIL |
| i1 | 超纲退单 | orchestrator | NA | not-applicable=ok |
| i2 | 硬打断 | orchestrator | NA | not-applicable=ok |

**结论**：FAIL 幕 2, 3, 5, 6。

> `cli-direct` 幕只证明「真 agent 真沙箱真补丁」，不证明编排器的工单卡/追问/自愈/合并按钮；
> `orchestrator` 幕在 headless tier 下如实标 NA，两者不得互相冒充。
