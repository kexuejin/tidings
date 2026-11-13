# DSH 完整工作区 × 触发通道 × 卡片家族 · 真机验证（main @ `0e8e1fbd`）

> 日期：2026-11-09 23:00 – 2026-11-10 00:35
> 装置：Pixel 6（<device-serial>，com.kapp.tenon.debug）+ macOS 宿主 Tenon.app（同仓 HEAD 构建）
> 工作区：teahouse-mobile（ws-e9b18063d183，落点 = 本仓工作树）
> 全程 `closeCount=0`（两次宿主重启 + SIGSTOP/SIGCONT + 十余次派单）；`[flush] drop` 0 次。
> 两个未跟踪的 team-* 计划文档为本轮之前已存在的评审材料，与本轮无关、未动。

## 0. 为什么重装：一次现场抓到的「合并后才成立」缺陷

会话开始时的在装产物（APK 23:44 前构建 / 宿主 18:45 构建）**早于** `0e8e1fbd`
（20:20 提交）。用旧宿主打开本机桌面库检查：`user_version=21` 且
`SchedulerTaskState` / `TriggerEvent` **两张表都不存在**——正是 `0e8e1fbd` 修的
「老桌面库整段跳过 21.sqm」，在真机库上活体复现。

从 HEAD 重建两端并重装：

| 产物 | 值 |
|---|---|
| APK | `androidApp-debug.apk`，sha256 `1d39ecb849553a9f…`，lastUpdateTime 23:12:35 |
| dex 新符号 | `CREATE TABLE IF NOT EXISTS SchedulerTaskState` 命中 classes5/classes9（只在新代码里有） |
| 宿主 | /Applications/Tenon.app 由 HEAD dist 替换 + `lsregister -f -R -trusted`，23:13 重启 |
| 宿主 jar | app-jvm 内 SchemaSelfHeal 自愈符号命中 |

**`0e8e1fbd` 自愈真机验证 ✅**：换包重启后同一桌面库
`user_version 21 → 22`，`SchedulerTaskState` / `TriggerEvent` 两表补齐，
`[RELAY-TRIGGER] trigger tasks synced` 正常初始化（修复前这条链路面对的是
no-such-table）。装机纪律见 ACCEPTANCE §1b。

## 1. L0 完整测试（交付口径）

```
=== L0 FULL 2026-11-09 === HEAD=0e8e1fbd
8 模块测试   BUILD SUCCESSFUL（82 tasks，对当前树 mostly up-to-date——
             上一会话已在本树跑过全套；本轮改动只有文档）
10 门禁      BUILD SUCCESSFUL（GATES_EXIT=0）
imports     OK / palette OK
testStabilityGate  全新执行 3 轮（6m11s，1 task executed）→ STABILITY_EXIT=0
```

`0e8e1fbd` 动了存储面，稳定性门禁必跑且为全新执行；8 模块的 up-to-date
读数如实标注，不冒充「又跑了一遍」。

## 2. DSH 真实 agent 完整工作区（Round A：真实需求多轮）

主判据沿用 T2 七跳口径，所有「到没到」以两端库/工作区文件为准。

### A1（创建）：给 docs/acceptance-guide.md 增设 §8「触发通道真机验证」

逐字符注入 + 回读全对（195/195）后派单：

1. 手机 `[send]` → 2. 宿主 `[OCGO-BRIDGE] invoke agent=DSH wsId=ws-e9b18063d183`
→ 3. 沙箱 `task t-1-46872452`（from merged base，链路自续）→
4. `harvest +8 −0, patch=2073 B` → 5. review_ready 卡**内嵌真内容 diff**
（`+- **webhook 先落盘再 ACK**：…只有返回 Stored 或 Duplicate 才回 200…`——
与 main 上 round3 修复语义逐条吻合，agent 真读了代码）→ 6. 点「合并此补丁」：
`[RELAY-INVOKE] patch merged …; next sandbox base=3fd3baa3…` →
7. **落盘判据**：`git diff docs/acceptance-guide.md` 出现 §8 五条中文 bullet；
账簿 `ticket-patches.tsv` 新行；卡面翻「**已由 Pixel 6 处理**」（D7 渲染口径 ✓）。

DSH 产出的 §8 内容五条全部命中本轮后续实测点（webhook 落盘门控 / 复合幂等键 /
drain 状态机 / SKIP 当期照发 / no-such-table 按 SchemaSelfHeal 处理）。

### A2（追加）：同文件第 6 条 bullet（验证沙箱基吃进上一单合并结果）

`task t-3-3be9f5a9 starts from merged base 3fd3baa3…`（= A1 合并产物）；
review_ready 的 hunk `@@ -197,3 +197,4 @@` 起始行落在 §8 内——基自续在 diff
上下文级别可证。合并后文件第 200 行出现第 6 条
（`closeCount 是链路稳定性的唯一判据…`），账簿第二行 `chain-tmw9f16c1`。

### 失败/重试路径（活体）

A2 首个任务 `t-2-1c3442c7 failed: cli exit=1 (cause in task.log)`——
task.log 原话 `dsh: DISCOVERY_FAILED: llm-opencode-go: could not reach
https://opencode.ai/...`（上游网络抖动，非产品缺陷）。宿主按退避自动重试
`t-3`，同一卡原位 rev 推进，无静默丢失、无重放循环（D15 归档结论复验成立）。

## 3. 触发通道（P0② / P1 webhook 落盘门控 / 复合幂等键）

夹具：一次性插件 `trigger-verify`（echo-bridge 同形状；cron `*/2 * * * *`
`missedFirePolicy=SKIP` + webhook sourceId `verify-hook`，dispatch 进
teahouse-mobile 协同群）。注册阶段两道 fail-closed 门**如期咬合**：
目录名≠pluginId 拒收；targetConvId 不存在拒收（宿主会话 id 是 `group:<groupId>`
形态——第一次就写对了反而说明没验到校验器）。

### webhook（P1：ACK 以落盘为前提）

| 用例 | 读数 |
|---|---|
| 无签名 POST | **401**（fail-closed） |
| 签名 POST（HMAC-SHA256, `x-webhook-signature`） | **200** `duplicate:false`；`TriggerEvent` 行落地（state=done）；插件收到 task/execute；群卡广播 `[CARD] broadcast card=trigger-d653e44e`；手机群收到 `trigger-result / trigger:verify-hook` |
| 同 body 重发 | **200** `duplicate:true`，插件 task/execute 计数不变（**零重派**，复合幂等键真机生效） |
| prompt 含 fail（插件回 error 事件） | `TriggerEvent` 行 `state=failed / trigger task failed`；错误卡广播到群 |

### cron（P0②：SKIP 放弃积压、当期照发）

- 自然窗口 23:46:11 照常派发（插件第 2 次 execute，第二张群卡）。
- **判别场景**：23:48:03 SIGSTOP 宿主 → 跨过 23:48、23:50 两个窗口 →
  23:51:38 SIGCONT。读数：`SchedulerTaskState.missed_runs=2`（积压如实记账）、
  **23:52:08 当期自然窗口照常派发**（第三张群卡）。修复前 SKIP 连当期都不派、
  零日志——此读数即 P0② 的真机成立。
- STOP 期间手机侧 send 进入 `schedule=[1000,2000,4000]` 重试，CONT 后收敛；
  closeCount 全程 0。

收尾：插件已注销（`/v1/plugin/unregister` → `0 cron, 0 webhook route(s)`）、
进程退出、夹具目录删除。真实群不被探针继续打扰。

**如实记的边界**：webhook 的 503 persist-failed / no-consumer 两面未真机构造
（需注入落盘故障；`SourceWebhookRouteTest` +5 已钉）。

## 4. 卡片家族真机覆盖（本轮新增上屏形态加粗）

| 形态 | 本轮读数 |
|---|---|
| working | 多单复验（耗时段 + stage chip）；t-6 卡出现**无停止键**的 working 变体（与 23:28 A2 卡带「停止」的形态不同，见 O8） |
| review_ready | 真内容 diff hunk + 合并此补丁（A1/A2）；**待合并 chip + 「Details externalized to local Artifacts; full board exceeds 5 KiB」预算外置横幅**（t-5，0e8e1fbd 的 warn 色横幅形态） |
| merged / decided | A1/A2 两单，知情行「已由 Pixel 6 处理」 |
| **prototype** | working（`Generating interactive prototype (prototype.html)…｜下一步：无需你操作｜第 1/1 棒·20%`）→ ready（`Interactive prototype ready. Reply to adjust it…` + prototype.html 附件卡 + **Approve & Develop 按钮上屏**——上一轮缺的截图证据本轮补齐）→ 批准后转开发工单 |
| **ask（方向确认）** | 0e8e1fbd 的 1:1 双派追问真机复现：卡内引用我的追问原文「是调整这个原型，还是一个新任务？」+ 作为调整发送/派为新任务 + 已由 Pixel 6 处理 |
| **blocked** | 「受阻 · 等你给方向」+ **「Handed back: no git workspace bound to group｜下一步：需要你给方向：可重试或补充说明」** + Retry——0e8e1fbd（兜底卡补 wsId 回落）的可见退单面 |
| **trigger-result** | 成功（webhook/cron 两条）+ 失败（fail 探针）三种，均落到手机群 |
| 文件卡 | patch.diff / report.json / prototype.html，打开/分享出口在位 |
| 审批卡 / 崩溃检查卡 / 熔断卡 / aborted | 未在本轮驱动（审批=T3 已有真机读数；后三者需必败/watchdog 专门场景，解析层由 `AgentCardFamilySpecTest` 钉住） |

## 5. 只读通道与 P0①（如实边界）

- P0① 的**接线面**真机成立：真实 dsh spawn（`node bin.js --profile headless
  --json`）环境里抓到 `TENON_HOOK_SESSION_ID=<派单会话>`（两次捕获，
  cwd=`/private/tmp/tenon-ws/t-*`，即沙箱分支）。裸分支（wsPath=null）的
  捕获未达成：本轮所有 1:1 文本派单都被分诊进沙箱工单链（含纯问答——见 O7），
  研讨通道没跑到。
- **执行前否决对真 dsh 仍不可验**：dsh 0.2.2-rc.1 没有 PreToolUse hook 面
  （hook 脚本只覆盖 gemini/agy）。round3 §7 的结论在 main 上维持：
  env 注入是必要条件，真 CLI 的执行前拒绝要等 dsh 侧 hook 落地。

## 6. 立案（本轮新增观察项，均不阻塞交付）

| # | 现象 | 读数 |
|---|---|---|
| O5 | 冷启动竞态下「宿主睡眠/离线」横幅滞留：宿主恰在恢复窗口时启动 app，链路已 online 但横幅/头部「离线」未即时清除，重进会话后正常 | 23:13 一次；不复现路径待定 |
| O6 | 交付链对「无 patch 任务」的重试压力诱发模型编造无关改动：问答式派单（要求写 /tmp 计数文件）被分诊进沙箱工单链 → 空收成 → 重试 prompt「Previous attempt failed: No patch produced…」→ dsh 产出 **+33 −2 的卡片词表测试改动**（与原任务无关）。系统兜住了：无 RELAY-INVOKE、工作树零污染、review_ready 卡未被合并 | `/tmp/tenon-ws/artifacts/t-2-a09a8044/patch.diff`；留档不合并 |
| O7 | 方向确认卡的派单腿未继承 1:1 药丸工作区：Q&A 新任务腿退单「no git workspace bound to group」，而该 1:1 明确选着 teahouse-mobile。退单**可见**（好），但 1:1 有药丸时本不该退 | 需对照 0e8e1fbd 判据 2b 的解析域 |
| O8 | working 卡的「停止」按钮两形态：A2 卡（23:28）有、t-6 卡（00:24）无（均进行中、均有活进程） | 疑与阶段/动作帧状态相关，待查 |

另外两条如实记：t-6（abort 探针）在我停止尝试期间自然完成，`docs/abort-probe-2.md`
的 patch 留在 review_ready 未合并（工作树无污染）；问答「What does section 8…」
最终没有拿到回答（进了工单链并退单）——研讨通道的真机读数仍是空白。

## 7. 未覆盖清单（不得由本轮读数推定）

- 自愈/审查闭环（verify FAILED → heal）：本轮**未重驱**；git log 确认
  `DesktopStageChain` / `ReviewPipeline` / `HealSteering` 未被触发分支合并触碰
  （最后提交为 `029e174a` / `029e174a`），11-08 的 heal 真机结论对 main 维持有效。
- 熔断卡、崩溃检查卡、aborted 卡的真机构造；审批卡本轮未重验（T3 在案）。
- webhook 503 两面；公网 TLS+pin；跨网文件；iOS。
- dsh 执行前只读否决（依赖 dsh 侧 hook，见 §5）。

## 8. 结论

- `0e8e1fbd` 自愈、`P0②` SKIP 语义、webhook 落盘门控 + 复合幂等键、
  0e8e1fbd 双派追问、0e8e1fbd 可见退单、D7/D14/diff 可视化——本轮全部拿到
  **main 分支的真机读数**。
- DSH 完整工作区七跳 × 2 轮（创建 + 追加）全通，沙箱基自续成立，失败重试无静默。
- 卡片家族真机新增 6 种形态（prototype working/ready+approve、ask、blocked、
  trigger-result ×3、预算外置横幅、待合并 chip）。
- 全程 closeCount=0、flush-drop=0。
- 4 条观察项（O5–O8）立案，无阻塞项。

---

# 续轮（01:00–01:40）：问题修复 × 群聊闭环 × P0① 裸分支

> 承接上节 O5–O8：三条修成代码并真机闭环（每条带钉子 + 反向验证），O5 受控复现未触发，
> 另立 O9。全部改动只落 orchestration/desktopApp（宿主侧），手机 APK 未换包。

## 9. 群聊任务发起 → 验收合并（T5/A22-R5 的 main 主线闭合）

- **带席位的群 @ 派单在 main 上是通的**：teahouse-mobile 协同群（1811f2d5，历史 DSH 席
  带 defaultWsId）里经 **@ 选择器**选 `@dsh`（选择器数据源=工作区 agent，正则按 kind
  匹配）派「create docs/probe-grp-20261110.txt」→ 宿主唤醒 → **群卡** chain-tmw9hdedp
  rev 1→4（`[CARD] broadcast`，非 1:1 直投）→ 沙箱 +1 −0（202 B）→
  `[ORCHESTRATOR] patch bundle dispatched to 1811f2d5-…` → 群内点「合并此补丁」→
  账簿行 `chain-tmw9hdedp | 1811f2d5-… | 本仓工作树` →
  **文件真落仓**（`PROBE-GRP-OK-20261110`，验证后已删）。
- A22-R5 的残余面如实记：**字面量手打 `@dsh` 会被 @ 选择器劫持成 `@所有人`**（选择器
  在 `@` 键上拦截）→ `NO_WAKE groupId=1811f2d5`；无席位的手机新建群依旧 NO_WAKE
  （旧判据）。修复方向仍是「seat-sync 对手机建群路径生效 / 选择器与花名册同源」。
- 探针文件已删，工作树零残留。

## 10. 修复三件套（O7 / O6 / O8，每条 = 修复 + 钉子 + 反向验证 + 真机）

### O7 方向确认卡「派为新任务」腿丢 wsId → 必退单
- 根因：`confirmPrototypeInput` 的 `dispatchSingleWorker(...)` 不传 wsId；
  1:1（groupId=null）+ 多工作区宿主在 `resolveWorkspacePath` 无路可走
  （群绑定/简介判据不适用、单工作区推导不唯一）→ `Handed back: no git workspace
  bound to group`，而药丸明明选着工作区。
- 修复：`ActiveTicket` 增加 `val wsId: String? = null`，构造点存入，
  ask 卡新任务腿原样带回（orchestration/DesktopWorkerOrchestrator.kt）。
- 钉子：`DesktopWorkerOrchestratorTest.askCardNewTaskLegKeepsTheExplicitWorkspace`
  （源码守卫）；反向验证 = stash 修复 → **两条新守卫红**，复原绿。
- 真机闭环：原型待选期追问 → 方向卡 → 派为新任务 →
  `direction-ask resolved: dispatch-pending` → **`task t-2-12ee70be starts from
  merged base a77bf281`**（派发成功）；全程 `no git workspace bound` 零命中。

### O6 无 patch 重试的话术诱发编造
- 根因：重试 prompt 无条件喊「fix all issues and ensure npm test passes」；
  问答式任务没有补丁是**正常终态**，这句话把模型逼到编造无关改动凑补丁
  （上节 O6 现场：+33 −2 卡片词表测试改动与原任务毫无关系）。
- 修复：重试 prompt 按「无 patch 且无验收判负」分化——明确
  「do NOT create or modify files to satisfy a patch requirement」。
- 钉子：`DesktopWorkerOrchestratorTest.patchlessRetryDoesNotDemandTestFixes`
  （反向验证同上，红→绿）。
- 真机闭环：修复后同场景的重试任务（t-3-6835d225）task.log 里实时抓到新话术
  `Previous attempt ended without a patch (No patch produced by worker). If the
  task genuinely needs no file changes, finish with the final answer text and do
  NOT create or modify files…`。

### O8 心跳卡丢「停止」按钮
- 根因：`OrchestratorCards.heartbeatCardJson`（watchdog 心跳 rev，执行期**替代**整卡）
  构建 working 态卡片漏了 `actions` 数组——`StageChain.cardOf` 的
  「干到一半的工单必须卡上有停」纪律在心跳 rev 断链。真机卡帧流自证：
  rev 2/4（publishTicketCard 推，带 abort-task）与 rev 3/5/6（心跳推，
  actions=null）交替；心跳窗口里「进行中」整卡无按钮。
- 修复：心跳卡补 abort-task 动作（orchestration/DesktopWorkerWatchdog.kt）。
- 钉子：`DesktopWorkerWatchdogTest.heartbeatWorkingCardKeepsTheAbortExit`
  （行为级：构建心跳卡 JSON 断言 actions）；反向验证红→绿。
- 真机闭环：新任务 working 期**心跳窗口出现停止键**（轮询第 1 跳即命中）→ 点下 →
  `[RELAY-INVOKE] abort by card action … ticket closed as aborted` →
  `[sandbox] task t-1-882a7149 teardown: killed 1/1 pid(s) in 54ms` →
  卡翻 **「已停止」+「Aborted by user request」**（进展 0%，Dsh × 图标）——
  **aborted 卡家族形态真机上屏**。

## 11. P0① 裸分支真机证据（round3 §7 遗留项收口）

探针：一次性 cron 触发插件，**leader agentId 故意取 "dsh"**——
`TriggerExecutor.execute` 先查 `resolveCliDriver("dsh")` 命中 → 走**裸驱动**
（wsPath=null，非沙箱分支，即 P0① 修的那条）。

读数（两次 cron 派发，01:22:05 / 01:23:05）：
- 裸 spawn env 抓到 **`TENON_HOOK_SESSION_ID=<每单独立会话 id>`**
  （01ff16c6… / 12ebfd53…），cwd=**宿主工作区**（本仓工作树），
  非 `/private/tmp/tenon-ws/t-*`——分支判据成立：round3 P0① 的
  「裸分支也注入 hook 会话键」在真机成立；
- **只读事后判负真机开火**：真 dsh 为「数文件」跑了 bash →
  `[RELAY-TRIGGER] cron task trigger-dsh-probe/bare-cron failed: read-only
  trigger channel violated by tool "bash"; aborted`——CognitiveFlow.allowsTool
  白名单判负（bash 不在只读白名单）在真 CLI 上生效；
- 探针即验即注销（`0 cron, 0 webhook route(s)` 复核），夹具目录删除。

边界不变：dsh 无 PreToolUse hook 面，「执行前否决」仍只能等 dsh 侧 hook。

## 12. verify 门 + 跨工作区合并（自愈前置判据真机成立）

- dsh-probe（/private/tmp/dsh-probe）重布必败基线
  （`"test": "node -e \"process.exit(1)\""`，提交 df88f5c），经
  `POST /v1/agent/workspace` 向手机宣告 → 药丸切 dsh-probe → 派「make npm test
  pass」→ worker 首轮改 package.json（`exit(1)`→`exit(0)`，+1 −1）→
  **`[verify] passed in 161ms (npm test --silent)`**——P4 验收门（交了补丁要在
  同基线重放仓库测试）真机成立；heal 轮因首轮即绿未触发（循环体未被本次合并
  触碰，11-08 读数维持）。
- 手机点「合并此补丁」→ `[RELAY-INVOKE] patch merged into workspace …
  next sandbox base=8b0fadb2` → **真实工作区** `/private/tmp/dsh-probe` 的
  package.json 更新、`npm test exit=0`——**跨工作区合并**第二据点（账簿行
  `chain-tmw9j00hm → /private/tmp/dsh-probe`）。

## 13. O5 受控复现未触发 + O9 新立案

- **O5**（启动竞态横幅滞留）：受控复现 = force-stop app → SIGSTOP 宿主 →
  冷启动 app（横幅+离线如实出现）→ SIGCONT → **15s 内横幅原地自清、头部翻在线**，
  无需重进会话。23:13 那次滞留降级为一次性观察（当时恰逢宿主重启的三重竞态，
  与 O9 的端口回退可能同源）。
- **O9（新立案）**：宿主重启时若旧进程未完全释放 17879，新进程**静默回退**
  临时端口（lsof 实证链路端口漂到 52206），手机仍拨宣告的 17879 →
  connect-timeout 28 次、宿主侧 `linkTotal=0`。不影响 closeCount（从未连上即
  无关闭），但「回退无声」违反「诊断字段要么如实」的口径——修法方向：绑定失败
  要么大声失败要么如实写 diag。重启脚本应轮询端口释放后再起。
  端口释放后重启即恢复（`*:17879 (LISTEN)`）。

## 14. 回归与本轮改动面

```
=== L0 REGRESSION 2026-11-10 === HEAD=718b11e0 dirty=O7/O6/O8
（8 模块 + 10 门禁 + 2 脚本门禁，读数见文末补记；4 文件 +115 −2：
 DesktopWorkerOrchestrator.kt / DesktopWorkerWatchdog.kt + 两条测试文件）
```
