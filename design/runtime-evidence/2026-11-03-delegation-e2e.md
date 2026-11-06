# 委派双挡真机 E2E（手机 + 宿主，2026-11-03 晚）

> 前置：委派双挡 Step1-6 已落地（b18a4b18 / 0d467268），本轮重建部署后真机复验，
> 并修掉 E2E 当场暴露的两个缺口（G1 健康名册、G2 链级盲区账本）。

## 0. 结论速览

| 幕 | 内容 | 结果 |
|---|---|---|
| 1 | 手机 `@codex-e2e team: append e2e marker to README`（挡位 B，英文别名） | ✓ 受理回执 1s 内到，链 worktree 开跑 |
| 2 | LLM planner 真调 DeepSeek API 拆解 | ✓ 生效（审核棒选了 claude——这暴露 G1） |
| 3 | 链 codex 棒干活 + claude 棒 error | ✓ 失败即停、不合并（设计行为） |
| 4 | 宿主重启后第二单 planner **又**选 claude | ✗ → 发现内存台账重启即失 → **AgentHealthLedger** |
| 5 | G2：链级 blocked 进盲区账本 | ✓ `~/.tenon/blindspot/2026-11.jsonl` 记录「第 2 棒失败，链条已停」 |
| 6 | G1 修复后第三单：审核棒 = **opencode**（回避 claude） | ✓ 两棒全 done，链到 review_ready，README `+marker three` |
| 7 | 单棒派单走验收门 | ✓ 宿主日志 `[verify] passed in 370ms (npm test --silent)` |

## 1. 装置

- 宿主：/Applications/Tidings.app（createDistributable 三次重建），启动命令沿用
  `env -u NODE_OPTIONS -u PYTHONPATH -u BASH_ENV TIDINGS_LUCIOLA=1 … ./Tidings --background`。
- 手机：adb（1C101FDF6005H4），e2e-weather 群（=群 626bed34，绑 `~/weather-relay`，有 `npm test`）。
- 派单输入：**逐字符** `input text` + 0.4s 间隔、空格走 keyevent 62；`@` 后点候选 + 补空格。
  发送要点：**ENTER 不发送**（只加换行），必须点发送按钮 (990,2195)。

## 2. `team:` 别名的由来

`组队:` 是中文，adb 打不出中文字 ⇒ 给 AUTO 挡加等价英文别名 `team:`（语义零差异，
DelegationParserTest 钉住）。挡位 A 的中文角色词（审核）是内部分类词，不经过 adb，不受影响。

## 3. 两个当场修掉的缺口

### G1 · 「在位」≠「健康」

- 现场：run1/run2 的审核棒都被（LLM planner）指给 claude——二进制在位但未登录，链走到第 2 棒才 error。
- 第二次踩中暴露根因：**`AgentSessionRegistry` 是进程内存**，重启后错误史蒸发，
  `recentCliSessionState` 查到 null ⇒ 健康检查形同虚设。
- 修法（三层）：
  1. `ControlPlane.recentCliSessionState(agentKey)`（内存台账，本进程新事实）；
  2. `AgentHealthLedger`（`~/.tenon/agent-health.jsonl`，挂 registry `onRecordSession` 钩子，
     同会话同状态不重写，启动回放）——错误史活过重启；
  3. 编排器 `healthyAgentKeys`：自动选棒（启发式/planner 名册）用健康名册，
     **显式点名不受它管**（用户指名就派，链失败即停兜底）；全员不健康退回全在位集合。
- 复验：run3 审核棒 = opencode（done），claude 被回避。账本首建时用 run1/2 的真实历史播种。

### G2 · 链级 blocked 不进盲区账本

`DesktopStageChain` 的 publish(blocked) 与链崩溃统一接 `onBlindSpot`（与单棒工单共用
`~/.tenon/blindspot/`）；实测 run2 的链失败已入账：
`{"kind":"blocked","title":"add marker two to README","detail":"第 2 棒失败，链条已停：cli status=error"}`。

## 4. 观察项（不修，留档）

- **手机只收到每张卡的 rev 1**：working/review_ready/blocked 后续 rev 都没落库——
  与 2026-11-01 产品评审的「手机端替换判据 rev > existingRev 依赖宿主本地槽位」同源，遗留问题非本轮回归。
  宿主侧证据齐全：`[verify] passed`、patch bundle/artifact 投群日志、卡片广播无失败日志。
- 链棒的提交摘要出现「开发: stop」——summary 取自 agent said 尾部，词不达意（既有行为）。
- LLM planner 拆解在 run1/run2 均生效（真调 DeepSeek API）；超时降级路径未在真机触发（单测覆盖）。

## 5. 复现要点

- 换包：TERM 旧进程 → mv 旧包 → ditto 新包 → 后台任务启动（**前台启动会被工具超时连坐杀掉**）。
- 查健康账本：`cat ~/.tenon/agent-health.jsonl`；盲区：`cat ~/.tenon/blindspot/$(date +%Y-%m).jsonl`。
- 手机端查卡：`MessageEntity.card_json`（cards 不进 text 列）。
