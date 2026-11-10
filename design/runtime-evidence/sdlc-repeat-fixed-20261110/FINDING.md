# 可反复验收：同锚点两轮完整生命周期全绿（2026-11-10 12:13–12:32）

**结论**：`--stage empty --plan from-zero --repeat 2 --timeout 600` 从**同一个空仓锚点
`3792076`** 连续重放两轮完整研发生命周期，**两轮各 9 幕全部 PASS（18/18）**，
五条幂等判据 **5/5 全绿**，源仓 `~/weather-relay` 跑完 `git status` 为空。
这是「空项目 → 需求 → 可点原型 → 实现+单测 → 验收 → 新功能 → 合流 → 缺陷修复 → 总验收」
这条链**可反复验收**的实测证明，不是剧本、不是截图、不是受理回执。

## 装置

- 宿主 headless（启动前剥 WorkBuddy 的 `NODE_OPTIONS`/`PYTHONPATH`/`BASH_ENV`/
  PATH `safe-bin`），真 `dsh` CLI；控制面 8788。
- 每轮从只读源仓 `git clone --no-local --single-branch` 出全新工作区，
  再 `checkout -B sdlc-run 3792076`；**源仓零 reset、零写入**。
- 幕序列 `0,a1,a2,a3,a4,2,3,5,6`；两轮共 **10 次真 dsh 派单**
  （每轮 a1/a2/a3/2/5 各一次），逐次都有 `[sandbox] harvest` 与
  `[e2e] dispatch done ... status=finished`。

## 逐轮读数

| 幕 | run-1 | run-2 | 判据取什么 |
| --- | --- | --- | --- |
| 0 体检 | PASS 0.7s | PASS 0.6s | 锚点 HEAD / 仅 package.json / 源仓干净 / 隔离路径 / 空仓红测只作读数 |
| a1 需求 | PASS 67.2s | PASS 91.5s | 补丁含 `docs/PRD.md` + 正文 AC1..AC4，落地并进基线 |
| a2 原型 | PASS 73.2s | PASS 152.5s | 补丁含 `prototype.html`、`<script>`/交互逻辑、增行 ≥40 |
| a3 实现 | PASS 153.2s | PASS 284.1s | 补丁同时含 `src/` 与 `test/` 文件，落地后 `npm test` 全绿 |
| a4 从零验收 | PASS 0.7s | PASS 0.6s | 四类产物齐备 + 单测全绿 + git log 有 3 条装置提交 |
| 2 新功能 | PASS 85.4s | PASS 79.3s | `uvAdvice.js` 与其测试同现 |
| 3 合流 | PASS 0.8s | PASS 0.8s | 补丁 apply + 工作树真变 + 测试通过数 > 空仓基线 |
| 5 缺陷修复 | PASS 59.4s | PASS 74.7s | 注入单文件真红 → 补丁只改实现 → 注入单文件真绿 → 回归全绿 |
| 6 总验收 | PASS 0.6s | PASS 0.6s | 全绿 / 无测试数回归 / 无孤儿 CLI 进程 / 无失败幕 |
| **合计** | **PASS 441.2s** | **PASS 684.7s** | 9/9 与 9/9 |

## 幂等判据（`repeat-summary.md`）

| 判据 | 结果 | 读数 |
| --- | --- | --- |
| `same-anchor-across-runs` | ok | 锚点集合 `['3792076']` |
| `each-run-has-fresh-workspace` | ok | 2/2 个独立路径 |
| `identical-verdict-shape` | ok | 判据名集合 **1 种**（两轮各 41 条判据） |
| `all-runs-green` | ok | run-1 / run-2 均 PASS |
| `source-repo-still-clean` | ok | 源仓脏行 0 |

## 独立于脚本自报的地面真相（事后复核）

```
$ git -C ~/weather-relay status --short             # 空
$ for r in run-1 run-2: (cd <ws> && npm test)
  -> # tests 5  # pass 5  # fail 0  # skipped 0     （两轮各自独立）
$ for r in run-1 run-2: (cd <ws> && node --test test/sdlc-harness-injected.test.js)
  -> # pass 1  # fail 0                              （注入的缺陷真被修好）
$ git -C <ws> log --oneline -1
  -> harness: fix injected defect                    （两轮各有自己的装置提交）
```

`npm test` 只跑 5 条而注入测试单文件跑 1 条，正是这一轮修好的地方：
`node --test test/` 在 agent 自建的 `test/` 布局下漏收集注入文件
（首版两轮实测 `npm test` 5 pass / 单文件 fail=1），所以判据必须显式指向被测文件。

## 与首版两轮的关系（为什么这次才全绿）

`../sdlc-repeat-20261110/FINDING.md` 记录了首版两轮：同样从空仓重放、
a1..a4/2/3 全绿，但**两轮都倒在幕 5**，且 `identical-verdict-shape` 判红。
两个缺陷都被稳定复现并修复：

1. 故障注入改用 `node --test <注入文件>` 判红/判绿，并提交进沙箱基线；
2. 合流幕判据名从带动态 id 的 `apply:t-10-...` 改为固定 `apply-patch`。

另修了 a3 硬编码 `src/app.js` 的假红（见
`../sdlc-from-zero-20261110/FINDING.md`）、preamble 对空仓撒谎导致的超时、
`device_commit` 的「无需提交」幂等。
`--dry-run` 从 10 项扩到 **19 项**守卫，把这几条都钉死在自检里。

**没有放宽任何判据**：新增的是「注入单文件真绿」这条更严的判据；
判据总数从首版每轮 41 条保持不变（形状一致）。

## 边界

- 只验 `cli-direct` 链（真 agent + 真沙箱 + 真补丁 + 真测试）。
  orchestrator 的工单卡 / 追问唤醒 / 自愈退单 / 合并按钮 / 硬打断仍如实 NA，
  需 `--tier real` 与真机守门，不得冒充。
- 从零产物是 agent 现场写的简化实现（5 条测试），不是天气仓历史那套
  71 条测试的完整工程；它验的是「能从空仓建出可运行、可测的东西」。
  要验历史实现用 `--stage implemented`。
- 多 agent / team 仍只留结构（`--agent` 可重复）。
- 两轮耗时差异（441s / 685s）来自真实 LLM 生成时长，不作判据。

## 重跑

```bash
python3 scripts/sdlc-lifecycle.py --dry-run
python3 scripts/sdlc-lifecycle.py --stage empty --plan from-zero --repeat 2 --timeout 600 \
    --out design/runtime-evidence/<新目录>
# 快速回归（只跑空仓 → 需求 → 原型短链）
python3 scripts/sdlc-lifecycle.py --stage empty --plan bootstrap --repeat 3
```

隔离工作区默认保留在 `/tmp/sdlc-runs/<session>/<run-N>/ws` 供人工查看，
不会被下一轮复用（目录已存在直接判环境错）。
