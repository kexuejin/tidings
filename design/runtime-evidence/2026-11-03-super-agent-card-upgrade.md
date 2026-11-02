# Super-Agent 卡片升级（LobeHub Acceptance 范式）· 验收台账

> 计划书 `design/super-agent-acceptance-upgrade-plan.md` Phase 0–5 的落地证据。
> 文字入库；截图不入库（AGENTS §6）。

## 1. 交付物与提交链

| Phase | 内容 | 提交 |
|---|---|---|
| 0 | 原型对齐与契约冻结（relay.html v2 + design-spec 重投影） | `39e8400f` |
| 1 | 解析契约 v2：checks/bot/chips/diff.hunk/subActions/lastBeatTs + items 回退 + 七类畸形安全回退单测 | `118c35de` |
| 2+3 | 渲染四件套单源落地（CardHeaderRow/CardChecksColumn/CardDiffHunkViewer/CardRevisionDrawer）+ 13 对双语词条 | `bf890959` |
| 4 | 宿主五条接线：合流驱动/打回注水/心跳探针/看门狗租约/Fail-Loud 崩溃 | `036903ec` |
| 债 | 泵死亡自愈测试改轮询（envDependentTestGate B 类清零） | `7b51c53b` |
| 债 | 逐屏差集基线向原型 v2 重投影（337 组新棘轮底） | `debfcb30` |

## 2. 单源复用证据（AGENTS §2.6）

- 四个新组件全部住在 `composeApp/.../feature/chat/AgentCards.kt`，`ui/desktop/` 下 grep 零命中（手机 393 / 桌面 720 两档宽度走同一份 `AgentCardBubble`）。
- 双端帧 4 条进 `ScreenshotPreviewsM3Shared`（V2CardAcceptancePhone/Desk、V2CardRevisionDrawerPhone/Desk），夹具走生产解析器 `parseAgentCard`，并登记 `componentSpecs.previews`。
- `designPreviewPairingGate` 104 条判定无退化；`designDupFrameGate` 291 张参考图两两互异。

## 3. 反向验证记录（先红后绿）

| 验证 | 手段 | 结果 |
|---|---|---|
| 泵死亡自愈测试 | 摘掉 `t.restart()` 调用 | 测试立即红（AssertionError），恢复后绿 |
| Phase 4 接线①②（合流/打回） | 摘 `mergePatchByCard`+`rejectRevisionByCard`+扩展分支 | 编译期 Unresolved reference + 3 条守卫红，恢复全绿 |
| Phase 4 接线③④⑤（心跳/看门狗/崩溃） | 摘看门狗协程+feed+心跳块+熔断尾判+崩溃 catch | 3 条守卫红，恢复全绿 |
| 编码/解码契约 | `rejectRevisionActionIdOf`/feedbackTextOf 单测 ×4 + 宿主侧 RevisionFeedback 解码测试 | 同一前缀格式两侧钉死 |

## 4. 宿主五条接线的落点（符号名）

1. 合流驱动：`DesktopWorkerOrchestrator.mergePatchByCard` —— `DesktopPatchMerge.apply` 两段式，仅 `Outcome.Applied` 才下发 decidedBy；账目缺失如实回 Conflict。
2. 打回注水：`rejectRevisionByCard` → `ActiveTicket.userConstraints` → `WorkerPrompt.build` 把 HARD CONSTRAINTS 前置进每一轮自愈提示词。
3. 心跳探针：`HeartbeatEmitter`（无变化不发 + 4s 节流）挂 `tool.started`，`OrchestratorCards.heartbeatCardJson` 带 `lastBeatTs`；整卡 24 KiB 闸门超限抛（不静默截断）。
4. 看门狗：`SandboxIdleWatchdog`（注入时钟纯判定）180s 无 I/O → TERMINATE（复用沙箱 TERM→宽限→KILL 中止序）→ 5s 后 FORCE_KILL 发 `state=timeout` 卡。
5. Fail-Loud：执行体抛异常 → `state=failed` 红卡 + `CrashReport.drawerOf`（stderr 尾 20 行进 `checks[].drawer`）。

## 5. 门禁读数（本台账写就时逐条实跑）

**七模块串行全量**：隔离 worktree（`.agents/worktrees/phase5-verify` @ `036903ec`）`--continue --max-workers=1 --rerun-tasks` 74 tasks executed 全绿（6m22s）。环境注记：luciola 原生库在本机位于 `/Volumes/Kapp/RustBuild/cargo-target/release`（不是 `$HOME`），跑 Luciola 相关用例须显式 `TIDINGS_LUCIOLA_NATIVE` 指到该绝对路径——这是环境债不是代码债，`DesktopLuciolaHostTest` 带该路径 4/4 绿。

- 单测：`:composeApp:jvmTest` + `:composeApp:testAndroidHostTest` 绿（新增 `AgentCardAcceptanceRenderTest` 7/7）；`:desktopApp:test` 绿（新增 19 条：13 行为 + 6 落点守卫）。
- 设计七门（specDrift/token/previewPairing/dupFrame/stateSlot/localeParity/cjk）全绿；token 棘轮欠债还降（shape 92→86、dimension 145→144）。
- `envDependentTestGate`：扫描 494 个测试文件，A/B=0（B 类 2 处已修为 `awaitTrue` 轮询）。
- `constructionTraceGate` / `staleCommentRefGate` / `unused-imports --check`：无新增。
- `designScreenDiffGate`：原型 v2 重投影后 337 组无扩大；`designDpDebtGate`：账本再生成后与源码逐字一致。

## 6. 稳定性门禁

`:testStabilityGate` 首跑两轮各红一条（`screenFromAHandshakedAddressIsAckedAndDelivered` / `pendingPairingSessionTimesOut`），单跑恒绿 —— 判定为满载串行下等待窗口被挤压（AGENTS §2.7-6：预算是「愿意等多久」，不是「允许多慢」）。修法是把两条用例的观测预算提到 30s（`7ce55a27`，条件本身是确定事件，加宽不掩盖缺陷）；改后 3/3 全绿（8m03s）。

## 7. 已知边界（如实）

- 基线重投影（`debfcb30`）记录的 337 组差集是**历史既有漂移**（代码侧未适配屏的 15/17/20 字号族等），不是本轮引入；新棘轮底只许变小。
- 看门狗/心跳的真机联调（真 CLI 派单 + 卡片探针观察）属 Tier A 真机验收范围，本轮交付的是宿主侧行为与测试证据；真机轮次记于 `2026-11-01-sdlc-acceptance-weather.md` §14 的后续。
- `design/icon/icongen-tenon*.swift` 两个未跟踪文件属另一会话的图标工作，本轮未纳入。
