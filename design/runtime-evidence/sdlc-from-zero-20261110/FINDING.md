# 从空仓重放完整生命周期：首跑读数（2026-11-10 11:34–11:45）

**结论**：`--stage empty --plan from-zero` 首次重放，9 幕中 **8 幕 PASS**，
从无到有的链路**真的成立**：空仓 → PRD → 可交互原型 → 实现+单测（5 条全绿）
→ 新功能 → 合流 → 注入缺陷 → 修复 → 总验收 `npm test` 9/9。
唯一 FAIL 是 **harness 自己的判据 bug**（硬编码文件名），不是 agent 能力失效。
判据修好后由 `--repeat 2` 复验（见 `../sdlc-repeat-20261110/`）。

## 装置

- 宿主 headless（剥 shim），真 `dsh`；`--stage empty` 从只读源仓
  `~/weather-relay` clone 出隔离检出 `/tmp/sdlc-runs/20261110-113423/single/ws`，
  锚点 `3792076`（只有 `package.json`）。
- 幕序列：`0, a1, a2, a3, a4, 2, 3, 5, 6`；每幕 `--timeout 600`。
- 源仓跑完 `git status` 为空（**只读成立**）。

## 逐幕读数（全部来自被测对象输出）

| 幕 | 真 dsh 产物 | 判据 | 结果 | 耗时 |
| --- | --- | --- | --- | --- |
| 0 体检 | — | 锚点 3792076 / 仅 package.json / 源仓零污染 / 隔离路径 / 空仓红测只作读数 | 12/12 | 0.6s |
| a1 需求 | `t-2-c81f376b` **3155 B, +32**, `docs/PRD.md` | 目标文件 + 正文 AC1..AC4 + 落地 + 基线提交 | PASS | 58s |
| a2 原型 | `t-3-1012a0d6` **10551 B, +199**, `prototype.html` | 有 `<html>`/`<script>`/可执行交互逻辑、增行 ≥40 | PASS | 204s |
| a3 实现 | `t-4-44bcd2fd` **10366 B, +284**, `src/weather.js` + `test/index.js` + `test/weather.test.js` | src/ 与 test/ 齐备、落地、**`npm test` 5/5 全绿** | **FAIL（判据 bug）** | 284s |
| a4 阶段验收 | — | 四类产物齐备 / 5/5 全绿 / git log 有 3 条装置提交 | PASS | 0.6s |
| 2 新功能 | `t-5-a132b93f` **1843 B, +55**, `uvAdvice.js` + 测试 | 实现与测试同现 | PASS | 46s |
| 3 合流 | 补丁 apply + 提交 | pass **8** > 空仓基线 0、fail=0 | PASS | 0.7s |
| 5 修缺陷 | 注入 fail=1；`t-6-8f124ede` **838 B, +18** 只改实现 | 注入测试未删 / 只改实现 / pass **9** | PASS | 35s |
| 6 总验收 | — | 9/9、skipped=0、无孤儿进程 | FAIL（因 a3 连带） | 0.7s |

宿主日志逐轮对齐：`[sandbox] harvest t-3-1012a0d6: +199 -0`、
`t-4-44bcd2fd: +284 -0 in 3 file(s)`、`t-5-a132b93f: +55 -0 in 2 file(s)`、
`t-6-8f124ede: snapshot 1 modified file(s) ... +18 -0`，
每轮都有 `[e2e] dispatch done ... status=finished`。五轮真派单，无一轮靠受理回执蒙混。

## 唯一 FAIL 的归因：出题与判据不一致

`BOOTSTRAP["a3"]` 的 prompt 是「在 `src/` 下新增**至少一个** ESM 模块」，
但 `expected-file-produced` 判据硬编码了 `src/app.js`。
agent 合理地写了 `src/weather.js`（并自带 `test/index.js` 与 `test/weather.test.js`，
5 条测试全绿），于是被判红：

```
[FAIL] expected-file-produced — 期望 src/app.js；实际
       ['src/weather.js', 'test/index.js', 'test/weather.test.js']
[ok]   impl-and-tests-both-produced
[ok]   bootstrap-tests-green — pass=5 fail=0 skipped=0
```

**这是判据错误，不是被测能力错误**：两条判据互相打架，且后者才是与 prompt 一致的
那一条。幕 6 的 `no-failed-acts` 因此连带 FAIL，属于正确的收口行为（不许有失败幕还判总绿）。

**修法**：`BOOTSTRAP["a3"]` 的 `expect` 改为 `None`（不再锁死文件名），
`bootstrap_act` 只在 `expect` 非空时加 `expected-file-produced`；
a3 由 `impl-and-tests-both-produced` + `bootstrap-tests-green` 验收。
`--dry-run` 增守卫 `bootstrap-a3-judges-by-content-not-filename` 防回归。
判据**没有放宽到「只看补丁大小」**，仍要求补丁里确有 `src/` 与 `test/` 文件且测试真绿。

## 期间修掉的另两个真问题（同一方案内）

1. **preamble 对空仓撒谎**：初版写「cwd 含 .git、src、test、package.json」，
   空仓只有 `package.json`。首轮 probe 的 task.log 里 dsh 明确写下
   「The user said it contains src, test, package.json, but actually only package.json」，
   随后在长篇中文 PRD 生成中途断开（300s 超时、docs/ 空目录、宿主会话仍 busy）。
   改为中性表述「可能只有 package.json，不要假定已有 src/ 或 test/」，
   并给 a1/a2/a3 加篇幅上限（120/200/150 行）。修后同一最小链路
   `--act 0,a1` **122s PASS**：`PRD.md` 6201 B / +102 行
   （`../sdlc-repeat-probe2/`）。
2. **超时无法归因**：`dispatch` 超时原先只报「没跑完」。现采集会话台账 state、
   活 CLI 进程数、最近 `task.log` 写入时间写进证据，区分「agent 还在生成」、
   「进程已死但宿主未收到结束事件」与「沙箱没建」。

## 边界

- 只验 `cli-direct` 链；orchestrator 的工单卡/追问/自愈/合并按钮/硬打断仍 NA。
- 从零链路的产物是 agent 现场写的简化实现（5 条测试），**不是**天气仓历史上那套
  71 条测试的完整工程；它验的是「agent 能从空仓建出可运行、可测的东西」，
  不是「复刻历史实现」。要验后者用 `--stage implemented`。
- 多 agent / team 仍未扩展。
