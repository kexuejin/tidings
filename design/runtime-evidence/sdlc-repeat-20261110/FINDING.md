# 首版两轮反复验收：抓到两个可复现缺陷（2026-11-10 11:46–12:11）

**结论**：`--stage empty --plan from-zero --repeat 2` 从同一锚点 `3792076` 连续重放两轮，
**两轮都跑通了从无到有**（a1 需求 / a2 原型 / a3 实现+单测 / a4 阶段验收 / 2 新功能 / 3 合流），
且**两轮都倒在同一个位置**（幕 5 故障注入）并连带幕 6 FAIL。
五条幂等判据里 3 条绿、2 条红；两条红各自暴露了一个真实缺陷，均已定位并修复。
这是「可反复验收」该有的样子：不是刷绿，是稳定复现问题。

## 逐轮读数

| 轮次 | 隔离工作区 | 幕数 | 耗时 | 结论 | 失败幕 |
| --- | --- | --- | --- | --- | --- |
| run-1 | `/tmp/sdlc-runs/20261110-114646/run-1/ws` | 9 | 854.6s | FAIL | `5`, `6` |
| run-2 | `/tmp/sdlc-runs/20261110-114646/run-2/ws` | 9 | 607.6s | FAIL | `5`, `6` |

两轮 a1/a2/a3/a4/2/3 全 PASS，真 dsh 产物（节选）：

| 幕 | run-1 | run-2 |
| --- | --- | --- |
| a1 需求 | PASS 94.5s | PASS 33.6s |
| a2 原型 | PASS 326.0s | PASS 140.2s |
| a3 实现+单测 | PASS 232.3s | PASS 272.1s |
| 2 新功能 | PASS 198.1s | PASS 158.5s |
| 3 合流 | PASS 0.8s | PASS 0.8s |

源仓 `~/weather-relay` 跑完两轮 `git status` 为空 —— **只读、零 reset、两轮互不污染**成立。

## 幂等判据读数（`repeat-summary.md`）

| 判据 | 结果 | 读数 |
| --- | --- | --- |
| `same-anchor-across-runs` | ok | 锚点集合 `['3792076']` |
| `each-run-has-fresh-workspace` | ok | 2/2 个独立路径 |
| `identical-verdict-shape` | **FAIL** | 判据名集合 2 种 |
| `all-runs-green` | **FAIL** | run-1 / run-2 均 FAIL |
| `source-repo-still-clean` | ok | 源仓脏行 0 |

## 缺陷 1：故障注入依赖 `npm test` 的默认收集行为（两轮一致复现）

两轮幕 5 读数相同：`故障注入 ... 红=False fail=0`，`npm test` 只报 **5 pass**。

在 run-1 工作区上独立复核（地面真相）：

```
$ cat package.json            # "test": "node --test test/"（agent 没改）
$ ls test/                   # index.js(28B), weather.test.js, uvAdvice.test.js,
                             # sdlc-harness-injected.test.js(364B)
$ npm test                   # tests 5 / pass 5 / fail 0   ← 注入文件没被收集
$ node --test test/sdlc-harness-injected.test.js
                             # tests 1 / pass 0 / fail 1   ← 显式跑才是红的
```

agent 从零自建的 `test/` 布局（带 `index.js`）让 `node --test test/` 漏收集注入文件。
后果有两层：注入被误判成"没造成红灯"，且因为 `device_commit` 只在 `is_red` 时执行，
沙箱基线里**根本没有这个测试** —— 即便继续派单，agent 也会去修一个不存在的 bug。

**修法**：新增 `injected_test_verdict()` 显式 `node --test <注入文件>`；
`inject_failing_test` 以它判红并提交进基线；幕 5 修复后增判
`injected-test-now-green`（单文件真绿），与 `npm test` 回归双判据。
`--dry-run` 加 `injection-probes-explicit-file` 守卫。判据没有放宽，反而更严。

## 缺陷 2：判据名带动态 artifact id，反复比对永远不一致

```
only run-1: ['3:apply:t-10-7f8cb9f9']
only run-2: ['3:apply:t-14-4d00f21c']
```

合流幕把 `Verdict("apply:" + <本次 patch 目录名>)` 当判据名，
两轮任务 id 必然不同 ⇒ `identical-verdict-shape` 恒红，与被测能力无关。

**修法**：判据名固定为 `apply-patch`，目录名与 apply 细节移进 detail/evidence；
`--dry-run` 加 `no-dynamic-verdict-names` 守卫（拼接写法避免守卫自指命中）。
对首版两轮 `result.json` 做静态归一化验证：

```
归一化后判据形状一致 = True，差异 = []   （两轮各 41 条判据）
```

## 顺带修的幂等点

`device_commit()` 在工作树与基线完全一致时原先会因 `nothing to commit` 返回失败，
重放同一幕会被判红。现改为「无需提交」按成功处理（幂等语义）。

## 修复后的复验

`--dry-run` **19/19 绿**（新增 3 条守卫）。
同锚点两轮完整生命周期复验见 `../sdlc-repeat-fixed-20261110/`。

## 边界

- 只验 `cli-direct` 链；orchestrator 的工单卡/追问/自愈/合并按钮/硬打断仍 NA。
- 从零产物是 agent 现场写的简化实现，不是天气仓历史那套 71 条测试的完整工程。
- 两轮耗时差异（854.6s / 607.6s）来自真实 LLM 生成时长，不作为判据；
  判据只认补丁内容、测试读数、工作树与进程。
