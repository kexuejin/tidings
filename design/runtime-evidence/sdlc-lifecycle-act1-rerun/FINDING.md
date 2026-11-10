# 首跑发现：沙箱 worktree 已建，但 prompt 把 agent 引回了主仓（根因更正，2026-11-10）

> 本文件是对 09:53 初版结论的**更正**。初版写「直驱链不进隔离沙箱 / `wsId` 缺省静默回落」，
> 经查宿主 task.log 是**误判**：沙箱建了、cwd 正确、harvest 也跑了。真正的失效是
> harness 自己在 prompt 里写了主仓绝对路径，agent 照着写回主仓 —— 一次**沙箱逃逸**。
> 误判版本的关键读数保留在本文末节，不删改历史。

## 装置

- 宿主：`/Applications/Tenon.app/Contents/MacOS/Tenon --headless`（启动前剥掉 WorkBuddy 的
  `NODE_OPTIONS` / `PYTHONPATH` / `BASH_ENV` 与 PATH 里的 `safe-bin`），控制面 `8788` 1s 内 200。
- agent：`dsh`，`POST /v1/agent/hook/dispatch`，`agentKey=dsh`、`wsPath=~/weather-relay`。
- 目标仓：`~/weather-relay`，HEAD `b221f7de5333`，工作树干净，`npm test` 87/87 / 412ms。
- 真机：未参与（本轮 tier=headless）。

## 铁证：沙箱确实建了

`/tmp/tenon-ws/artifacts/t-1-f8bc332f/task.log`（dsh 会话逐事件记录）：

```
{"type":"session","sessionId":"session-7aab964e-...","cwd":"/private/tmp/tenon-ws/t-1-f8bc332f"}
```

cwd 是宿主创建的**一次性 worktree**，不是主仓。宿主日志随后：

```
[sandbox] harvest t-1-f8bc332f: nothing to harvest — add.exit=0 add.stderr= stat= patchBytes=0
[e2e] dispatch done: agentKey=dsh sessionId=... status=finished
```

`harvestAndWrite`（`agentNode/.../drivers/WorktreeSandbox.kt:534-571`）在 worktree 里
`git add -A` 成功、`git diff <base>` 为空 ⇒ worktree **一个字节都没改**。
`git worktree list` 里该 worktree 已被无痕清理，符合正常收场。

## 真正根因：prompt 泄漏主仓绝对路径

harness 幕 1 的 prompt 原文（`scripts/sdlc-lifecycle.py` 首版 359 行）：

```
在 /Users/ci/weather-relay 里新增 docs/SDLC-HARNESS-REQ.md：...
```

task.log 里 dsh 的**第一条工具调用**就是照抄这个绝对路径：

```
{"type":"tool_call","tool":"bash","input":{"command":"ls -la /Users/ci/weather-relay ..."}}
{"type":"tool_call","tool":"glob","input":{"pattern":"**/*","path":"/Users/ci/weather-relay"}}
```

后续写文件也落在主仓：`docs/SDLC-HARNESS-REQ.md` 11668 B、AC1..AC4 齐全
（副本存本目录 `dsh-produced-REQ.md`）。沙箱 cwd 只是**进程的当前目录**，
不是强制隔离面 —— 这一条产品侧早有判据与注释：
`orchestration/.../DesktopStageChain.kt:334-342`（真机 cmvx8q5ig 实测，
「codex 从 worktree 的 `.git` 读到宿主 gitdir，再往上一层写」），
该链用 `hostRepo 变脏 = Escaped write` 兜底；直驱链没有这层兜底，也没人提醒 prompt 别带绝对路径。

## 判据分离（harness 幕 1，首跑如实判红）

| 判据 | 结果 | 依据 |
| --- | --- | --- |
| `agent-produced-artifact` | **ok** | dsh 真读懂需求、真产出 11668 B 且 AC1..AC4 齐全 |
| `patch-harvested` | **FAIL** | 无 patch.diff，宿主 `patchBytes=0` |
| `sandbox-no-escape`（初版名 `sandbox-isolation`） | **FAIL** | 主工作树被写入；worktree 零改动 |

幕状态 **FAIL**。首版 harness 干等 480s 只报「没跑完」；改成分离判据 + 早停后
135s 判出事实。这一半是对的 —— 错的是**归因文字**，不是判红本身。

## 修法（本轮）

1. 所有幕的 prompt 去掉主仓绝对路径，统一加执行环境约束：cwd 就是本次任务完整检出、
   只用相对路径、禁止绝对路径与访问宿主主仓。
2. `dispatch()` 的早停分支与 `no_patch_verdicts()` 改判「沙箱逃逸」，
   不再声称「直驱入口没有 wsId 所以不隔离」。
3. `--dry-run` 加守卫：任何 prompt 生成后不得含目标仓绝对路径，违反即自检 FAIL。
4. 重跑幕 1 验证 patch.diff 能否 harvest；能则继续跑全生命周期其余幕。

## 边界

`ControlPlaneHookRoutes.kt:389-407` 的 `/v1/agent/hook/dispatch` 仍只解包
`agentKey/prompt/wsPath` 并调 `executeCliTask(..., requestedSessionId = null)`；
会话台账 `wsId=null` 是**台账未登记该字段**，与「建不建沙箱」无关（沙箱在
`CliSubprocessDriver` / `WorktreeSandboxExecutor` 内，按 `wsPath` 建）。
产品主链（手机 `@` 派单 → `invokeExecutor` → `handlePromptAwait` →
`DesktopWorkerOrchestrator` 带 `wsId`）与工单卡/追问/自愈/合流这一层，
直驱链依旧**证明不了**，headless 下保持 N/A，不冒充通过。

## 初版误判留档（供对账）

初版依据 `GET /v1/agent/sessions` 的 `wsId=null` + `harvest ... nothing to harvest`
推出「wsId 缺省 ⇒ 宿主静默回落成不隔离、不提示」，并建议「直驱入口要么强制 wsId，
要么如实回报本次不进沙箱」。该因果链已被 task.log 的 `cwd` 行证伪；
两条建议本身不算错（台账字段确实可以更明确），但**不是本次失效的原因**。

## 复验结论（10:08，同一宿主 / 同一 dsh / 同一仓）

只改 prompt（去绝对路径 + 加 cwd 纪律），**不动产品代码、不放宽任何判据**：

```
幕 1 PASS：t-1-6b10b487/patch.diff 13761 B, +274/-0,
           docs/SDLC-HARNESS-REQ.md；主工作树 git status 为空
宿主日志：[sandbox] harvest t-1-6b10b487: +274 -0 in 1 file(s), patch=13761 B
```

证据目录 `../sdlc-lifecycle-act1-fixed/`。根因判断成立：这是 prompt 引导的沙箱逃逸，
与 `wsId` 无关。随后同一修法跑通完整六幕，见
`../sdlc-lifecycle-full-20261110/FINDING.md`（6/6 PASS，87/87 → 90/90）。
