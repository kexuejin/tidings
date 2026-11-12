# SDLC 生命周期 harness 读数（2026-11-12T07:51:32.424988+00:00）

- tier：`real`；stage：`empty`（锚点 `3792076`）；轮次：`single`；agent：dsh
- 工作区：`/tmp/sdlc-runs/20261112-154036/single/ws`；只读源仓：`/Users/developer/weather-relay`
- 基线 HEAD `3792076edace`，基线测试 pass=None fail=None
- 装置提交（非被测行为）：['bootstrap a1: 需求立项', 'bootstrap a2: 原型与设计']

| 幕   | 标题          | 链型           | 结论   | 判据读数                                                                                                                                                                                                                                                                                                                |
| --- | ----------- | ------------ | ---- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 0   | 环境体检        | local        | PASS | control-plane=ok / cli:dsh=ok / dsh-credentials=ok / workspace-is-git=ok / workspace-clean=ok / anchor-checked-out=ok / stage-files-present=ok / stage-files-absent=ok / baseline-has-no-implementation=ok / source-repo-untouched=ok / workspace-is-isolated=ok / adb-device=ok / desktop-db=ok / artifacts-dir=ok |
| a1  | 需求立项（空仓）    | cli-direct   | PASS | patch-is-real=ok / expected-file-produced=ok / prd-has-numbered-ac=ok / stage-landed=ok / stage-visible-to-next-sandbox=ok                                                                                                                                                                                          |
| a2  | 原型与设计       | cli-direct   | PASS | patch-is-real=ok / expected-file-produced=ok / prototype-is-interactive-html=ok / stage-landed=ok / stage-visible-to-next-sandbox=ok                                                                                                                                                                                |
| a2r | 原型可评审（人审守门） | orchestrator | FAIL | card-reader-self-check=ok / prototype-card-delivered=FAIL / prototype-preview-entry-tested=ok / prototype-opened-in-browser=ok / prototype-artifact-bytes-landed=ok / prototype-no-main-tree-write=ok / peer-idle-stays-online=ok                                                                                   |

**结论**：FAIL 幕 a2r。

> `cli-direct` 幕只证明「真 agent 真沙箱真补丁」，不证明编排器的工单卡/追问/自愈/合并按钮；  
> `orchestrator` 幕在 headless tier 下如实标 NA，两者不得互相冒充。

