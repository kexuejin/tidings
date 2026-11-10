# SDLC 生命周期 harness：真 dsh 六幕全绿（2026-11-10 10:10-10:15）

**结论**：`scripts/sdlc-lifecycle.py --act 0,1,2,3,5,6 --timeout 360` 在本机真实环境
（headless 宿主 + 真 `dsh` CLI + 真 `~/weather-relay` 工程）跑完六幕，
**6/6 PASS、0 FAIL、0 N/A、5m03s**。四轮真 agent 派单各自产出可验收补丁，
天气仓真实新增 3 个提交、4 个文件，`npm test` 从 87/87 涨到 **90/90 / 0 fail / 0 skipped**。
这是「真 agent 在真项目上走完需求→实现→合流→修缺陷→验收」的可重跑链，不是界面演示。

## 装置

| 项 | 值 |
| --- | --- |
| 宿主 | `/Applications/Tenon.app/Contents/MacOS/Tenon --headless`，启动前剥 WorkBuddy 的 `NODE_OPTIONS`/`PYTHONPATH`/`BASH_ENV`/PATH `safe-bin`；控制面 8788 1s 内 200 |
| 驱动 | `dsh` v0.2.1-rc.x，`/opt/homebrew/bin/dsh`，`dshArgs = --profile headless --json <prompt>`；宿主注册日志确认 `dsh-cli` |
| 派单入口 | `POST /v1/agent/hook/dispatch`（`agentKey=dsh`, `wsPath=~/weather-relay`），直驱 `executeCliTask` → `WorktreeSandboxExecutor` |
| 被测工程 | `~/weather-relay`，跑前 HEAD `b221f7d`、工作树干净、基线 87/87 / 341ms |
| 真机 | 未参与（tier=headless）；orchestrator 专属幕本轮不跑，见「边界」 |

## 六幕读数（全部来自被测对象输出，非受理回执/截图）

| 幕 | 链型 | 真 dsh 产物 | 判据 | 结果 | 耗时 |
| --- | --- | --- | --- | --- | --- |
| 0 体检 | local | — | 控制面 200 / dsh / 凭证 / 仓干净 / 87 全绿 / artifacts | 7/7 | 0.97s |
| 1 需求确认 | cli-direct | `t-2-df182e73/patch.diff` **17225 B, +320/-0**, `docs/SDLC-HARNESS-REQ.md` | patch 内容 + 目标文件在补丁里 | PASS | 138.2s |
| 2 新功能实现 | cli-direct | `t-3-bd462bdd/patch.diff` **1734 B, +57/-0**, `src/utils/uvAdvice.js` + `test/uvAdvice.test.js` | 实现与测试两个文件同时出现 | PASS | 48.1s |
| 3 审查与合流 | local | 两份补丁按序 `git apply` → `harness: merge feature from sandbox` | apply 成功 / 工作树真变 / **89 pass > 基线 87** / 基线提交 | PASS | 1.14s |
| 5 缺陷定位修复 | cli-direct | 先注入 `test/sdlc-harness-injected.test.js` 造成 **fail=1**；dsh 产出 `t-4-67934a22/patch.diff` **954 B, +16/-0** 只改 `uvAdvice.js` | 注入测试未被删改 / 只改实现 / apply / **90 pass** / 修复提交 | PASS | 113.2s |
| 6 收尾验收 | local | — | 90/90 且 skipped=0 / 无测试数回归 / 无孤儿 CLI 进程 / 无失败幕 | PASS | 0.98s |

宿主日志逐轮对齐：`[sandbox] harvest t-2-df182e73: +320 -0 ...`、
`t-3-bd462bdd: +57 -0 in 2 file(s)`、`t-4-67934a22: +16 -0`。
三轮 `task.log` 的 `session.cwd` 分别是 `/private/tmp/tenon-ws/t-{2,3,4}-...`，
即每轮都在**独立一次性 worktree** 中执行；主仓工作树全程零污染。

## 地面真相（独立于脚本自报，事后复核）

```
$ git -C ~/weather-relay log --oneline -4
1437d00 harness: fix injected defect
95251d8 harness: inject failing test into base
52676de harness: merge feature from sandbox
b221f7d drill: drop injected failing test after heal loop   <- 跑前 HEAD

$ git -C ~/weather-relay status --short          # 空：干净
$ cd ~/weather-relay && npm test
# tests 90 / # pass 90 / # fail 0 / # skipped 0 / duration_ms 387.7
```

四个新文件均在磁盘：`docs/SDLC-HARNESS-REQ.md` 16733 B、
`src/utils/uvAdvice.js` 1204 B、`test/uvAdvice.test.js` 840 B、
`test/sdlc-harness-injected.test.js` 364 B。
`harness:` 前缀的 3 个提交是**装置动作**（代替产品里的 `refs/tenon/base` 滚动基线，
让下一次沙箱看得见上一幕结果），已在 `result.json` 的 `device_commits` 单独记账，
不计入被测行为。

## 本轮修掉的两个真问题

1. **首跑根因纠错**：09:53 初版 FINDING 把「无 patch.diff」归因为
   `wsId=null` ⇒ 宿主不隔离，是**误判**。task.log 铁证显示沙箱 worktree 已建、
   cwd 正确；真因是 harness 自己在幕 1 prompt 里写了主仓绝对路径
   （`在 /Users/ci/weather-relay 里新增...`），dsh 第一条命令就 `ls` 那个
   绝对路径并把文件写回主仓 —— 沙箱逃逸（产品侧 `DesktopStageChain.kt:334-342`
   有对应 `Escaped write` 判据）。详见
   `../sdlc-lifecycle-act1-rerun/FINDING.md`（已重写并留档误判版本）。
2. **修法**：所有 prompt 去掉绝对路径、统一加 `SANDBOX_PREAMBLE`
   （cwd 即本次完整检出 / 一律相对路径 / 禁绝对路径 / 禁访问宿主主仓）；
   `dry-run` 加 `prompts-do-not-leak-abs-path` 守卫防回归。
   复跑幕 1 立刻 PASS：`patch.diff` 13761 B、主仓零污染
   （`sdlc-lifecycle-act1-fixed/`）。修法有效，不是换判据迁就现状。

配套修正：合流幕改为按序 apply **本次会话全部**补丁（原先只合最近一个，
会丢需求文档）；孤儿进程判据抽成 `CLI_PATTERN` 并用源码守卫对齐
`CliSubprocessDriver.dshArgs`，反向探针实测「探针命中 1 行 → kill 后归零」，
排除「pattern 永不命中所以恒绿」这种假通过。
（后两项在六幕跑完后加入：匹配串与运行时完全相同，未放宽判据、未改动已落盘读数；
改完 `py_compile` + `--dry-run` 10/10 复检通过。）

## 边界（不许冒充）

- 本轮只验 `cli-direct` 链：真 agent、真沙箱、真补丁、真测试。
  `orchestrator` 链（手机 `@` 派单 → `invokeExecutor` → `handlePromptAwait` →
  `DesktopWorkerOrchestrator`）的**工单卡、追问唤醒、自愈退单、合并按钮、硬打断**
  本轮未跑，幕 4 / i1 / i2 在 headless tier 下如实标 NA。
- 多 agent / team 仍只留结构（`--agent` 可重复、判据认产物不认席位），
  本轮单 dsh，未扩展 opencode 或责任链。
- `npm test` 的 90 条里，2 条来自新功能、1 条来自故障注入；
  这些是 harness 出的题，不是原有业务测试自己长出来的。

## 重跑与还原

```
# 重跑（宿主需已起、且天气仓工作树干净）
python3 scripts/sdlc-lifecycle.py --act 0,1,2,3,5,6 --timeout 360 --out <证据目录>
# 纯本地自检，不碰网络/设备/LLM（10 项）
python3 scripts/sdlc-lifecycle.py --dry-run
# 若要从原基线重来（会丢弃本次 3 个 harness 提交）
git -C ~/weather-relay reset --hard b221f7d
```

## 现场发现（与本轮并行发生，未归入本轮产物）

- 主仓工作区在收尾核对时多出 `desktopApp/.../DesktopPlatform.kt` 的未提交改动：
  产品改名「捎话 Tidings」→「榫卯 Tenon」，`DESKTOP_APP_DIR` 与
  `LEGACY_DESKTOP_APP_DIR` 对调、注释同步。**这不是本轮 harness 产生的**，
  本轮未触碰、未提交；已在最终交付里单独登记。
- 它会影响下一条链：harness 的 `desktop_db()` 原本写死旧目录，改名后
  `--tier real` 观测卡片会静默读空、判据超时。已改为新/旧目录按存在性探测
  （当前实测回落 `~/Library/Application Support/捎话 Tidings/relay.db`，
  `exists=True`），并保留 `E2E_DESKTOP_DIR` 覆盖。

## 门禁

`constructionTraceGate` / `cjkLiteralGate` / `staleCommentRefGate` 全绿，
`tools/unused-imports.py --check` OK，`--dry-run` 10/10，`py_compile` 通过。
宿主已停（8788 无监听），天气仓工作树干净、3 个 `harness:` 提交与 4 个新文件保留为验收物。
