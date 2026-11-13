# 真实场景闭环复盘（2026-11-13）

## 结论

`@团队 → 责任链 → 卡片演进 → 手机合并 → 工作区落盘` 已一次跑通：

- 链路 `chain-cmwegkccd`，3 棒（编码 codex / 测试 claude / 审查 claude），第 1 棒 232s 产出 3 文件 114 行。
- 卡片 rev 1→10 单调演进、无重复帧；rev8 `review_ready` 带 `merge-patch` 动作并**停在原地等机主**。
- 手机点击「合并此补丁」后：工作区出现三个文件，卡片 rev10 `merged`，`decidedBy=Pixel 6`，手机显示「已由 Pixel 6 处理」。
- 场景仓提交 `ae4e788`；`python3 -m unittest -v` → `Ran 5 tests OK`；六条 AC 逐条实测通过（含 `nan`/`inf` 退出码 2）。
- TLS `linkCarrier=tls`、`linkConnected=true`、`closeCount=0`、`dialFailures=0`。

## 本轮修掉的四个 P0

1. **审查棒看不到完整目标**：`TeamExecution.reviewInstruction` 两分支都 `goal.take(80)`，六条 AC 只剩「AC1 新建 we」，审查只能误判 REJECT。修为全文进审查棒，`TeamPlanCompilerTest` 钉住「第 1 棒指令含完整 goal」。
2. **回炉反馈只带开头**：`ReviewPipeline.Advance.BackToWorker` 用 `feedback.take(200)` 保留头部，而问题清单与 `VERDICT` 在发言尾部，第二轮 worker 以为「基线已满足」→ nothing to harvest → blocked。修为独立预算 + 保留尾部 + 显式返工语义。
3. **合流审计越界**：`ReviewerAudit` 把 Tenon 自己的「生产 Kotlin 源码 CJK/施工痕迹门禁」无差别套到所有 patch，把 weather-relay 的 `README.md`「# 温度换算工具」判成硬编码文案。修为逐 `+++` 文件头只审本仓库生产 Kotlin 源码，不适用时两条 check 如实 SKIP。
4. **假合并**：`DesktopStageChain` 在 `reviewPassed && review_ready` 时直接 `publish("merged")`，卡面说「已合并」而项目目录一行未动（实现只在 `chain/<id>` 分支）。修为不自行翻 merged，合流必须走机主点击 → `HostCardActions` → `DesktopPatchMerge.applyBranch` 真合入工作树。

四个修复都有正向测试 + 变异探针（把旧行为放回去必须红），并已落 `04`–`07` 四份证据。

## 观察项（不改代码，先记账）

- `tester` 与 `reviewer` 的 `customPrompt` 完全相同：两棒行为趋同，真实链路里确实给出相似结论。后续建队生成应让「测试」与「审查」的默认人设有可区分职责。
- 合并动作会先发一帧 `review_ready + decidedBy`（rev9），再发 `merged`（rev10）：状态机没跳变，但同一状态两帧略冗余，可评估合并为一次翻面。
- 场景仓自带 `npm test` 引用不存在的 `test/` 目录（空仓初始化遗留），与本次任务无关，未改。
- dsh CLI 在本机不可用（`deepseek-harness` 依赖残缺，28 处 `ERR_MODULE_NOT_FOUND`），链路全程未使用它；宿主驱动清单里 `dsh-cli` 只是「路径 + 凭证文件存在」的注册，不是可用性探针。

## 待办（用户产品口径，排在闭环之后）

- 建队不强制加群：允许先建档、后加群；空群给知情提示（群里/手机看不到、@ 不到）。
  **已落地**：`Create` 收空 `groupIds`（只写档案、不建席位，名册仍过 `TeamRosterValidation`）；
  对话框不再因未勾群置灰，改为一行知情提示；横幅走「团队已建档（还没进任何群）…」；
  名录「未入驻任何群」那一段就是它的落点。判据 `TeamProfileWritePathTest` 三条新增
  （只建档 / 空群仍校验名册 / 界面入口报 orphan 状态）。
- 团队卡与员工 tab 统一改成可折叠列表行，去掉 `LocalCardShape` + 阴影 + 卡内套框。
- 桌面两栏（compact）下「AI 建队」入口被 `!compact` 隐藏，需要补入口。
  **已落地**：窄列不再只有「段头右侧」一个落位 —— `compact` 时段头只留计数、动作独占一行
  （`if (compact) TeamActionButtons(…)`）；段头被抑制（有团队/切换器在）时本来就有独立一行。
  如实记：这条只有代码形状与预览核对，没有自动化判据（界面落位暂无量具）。
