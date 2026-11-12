# SDLC 生命周期 harness 读数（2026-11-12T00:12:37.228455+00:00）

- tier：`headless`；stage：`current`（锚点 `HEAD`）；轮次：`single`；agent：dsh
- 工作区：`/Users/developer/weather-relay`；只读源仓：`/Users/developer/weather-relay`
- 基线 HEAD `1437d00dae9c`，基线测试 pass=90 fail=0
- 装置提交（非被测行为）：无

| 幕 | 标题 | 链型 | 结论 | 判据读数 |
| --- | --- | --- | --- | --- |
| 0 | 环境体检 | local | PASS | control-plane=ok / cli:dsh=ok / dsh-credentials=ok / workspace-is-git=ok / workspace-clean=ok / stage-files-present=ok / stage-files-absent=ok / baseline-tests-green=ok / artifacts-dir=ok |

**结论**：全部判据通过。

> `cli-direct` 幕只证明「真 agent 真沙箱真补丁」，不证明编排器的工单卡/追问/自愈/合并按钮；
> `orchestrator` 幕在 headless tier 下如实标 NA，两者不得互相冒充。
