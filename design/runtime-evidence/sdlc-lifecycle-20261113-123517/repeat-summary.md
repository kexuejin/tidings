# 反复验收汇总（stage=current，共 2 轮）

| 判据 | 结果 | 读数 |
| --- | --- | --- |
| same-anchor-across-runs | ok | 锚点集合 [''] |
| each-run-has-fresh-workspace | FAIL | 1/2 个独立路径 |
| identical-verdict-shape | ok | 判据名集合 1 种 |
| all-runs-green | FAIL | run-1=FAIL / run-2=FAIL |
| source-repo-still-clean | ok | ~/weather-relay 跑完 2 轮后脏行=0 |

| 轮次 | 工作区 | 幕数 | 结论 | 耗时 |
| --- | --- | --- | --- | --- |
| run-1 | `~/weather-relay` | 9 | FAIL | 0.1s |
| run-2 | `~/weather-relay` | 9 | FAIL | 0.0s |

> 每轮都从同一固定锚点检出全新隔离工作区；源仓只读，反复跑不互相污染。
