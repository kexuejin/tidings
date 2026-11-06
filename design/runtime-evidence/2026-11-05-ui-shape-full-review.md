# 卡片圆角/描边全量 review 与真机复核（2026-11-05，HEAD `b9ca96ce`）

> 触发：用户先贴出文件卡截图问「这个样式不对吧」，随后明确要求
> 「不只是这一个卡片问题，你最好再全部都 review 一轮并修复」。
> 因此本轮不是单点补丁：先在整个生产源码面做形状契约审计，再修所有确认缺陷，
> 最后用当前 HEAD 的**真机 APK + 真 luciola 宿主**复核。

## 0. 结论

| 项目 | 结论 |
|---|---|
| 用户报的文件卡「只有左上角是圆的」 | ✅ 已修，真机像素级复核通过 |
| 同类第二处：`ScreenHistoryCard` 屏幕协助卡四方直角 | ✅ 已修（全量审计新发现） |
| 同类第三处：自定义卡内「引用源消息」横条被外层裁弧 | ✅ 已修（全量审计新发现） |
| 全生产源码形状/裁剪/描边契约 | ✅ 222 个 `.kt`、23 处带边框卡片，0 处漏 `shape`、0 处 shape/clip 不一致 |
| 回归守卫 | ✅ 新增 1 个全仓源码门禁 + 4 条纯函数单测，并做了变异测试 |
| 全量测试 + 设计门禁 + 截图校验 | ✅ `BUILD SUCCESSFUL`，5018 tests / 0 failures / 0 errors |
| 真机四屏 + 会话 + 文件卡视觉巡检 | ✅ 6 组真机截图全部无断角 |
| 边界 | ⚠️ 引用卡、屏幕协助卡没有单独的真机帧；桌面专属屏未在本轮真机覆盖 |

**没有把「改完代码」当成「验完」**：下面每一步都留了可复核的读数、哈希或截图。

## 1. 现象与根因

- 用户截图 `before-user-report.png`（356×158，红框标出右上/左下/右下），
  真机原帧 `before-device-chat.png`（1080×2400）里三张文件卡都是同一症状：
  **只有 topStart 是圆角，其余三个角的边框是互不相接的横竖短线**。上方文字气泡四角正常。
- 字节码级根因：material3-desktop **1.9.0** 的 `SurfaceKt.surface-XO-JAsU` 在 modifier 链
  末尾 **无条件** `.clip(shape)`（`background` → `border` → `clip`）。
  `ChatBubbles.kt` 对自定义卡（文件 / 图片 / PK）虽然设了 `color = Color.Transparent`、
  `border = null`，外层仍以文字气泡的 8/16dp 非对称圆角裁剪子内容。
- `FileCardView` / `InlineImagePreview` / `PkCardView` 各自用 `RelayShapes.medium`（12dp）
  画自己的边框。外层 16dp 的三个角比内层 12dp 更圆 ⇒ 内层圆角弧线被整段裁掉；
  只有 `topStart = 8dp < 12dp` 留得住。这就是「只有左上角圆」的几何解释。
- 同一机理还有两种形态：`Surface` 不传 `shape` 时默认 `RectangleShape`（直角裁 + 直角描边）；
  以及父层裁剪半径大于子层描边半径时，子层弧线同样被切掉。

## 2. 全量 audit 的扫描面与读数

独立于新增门禁，另跑了一遍 Python 源码扫描（先剥注释，再按平衡括号取调用参数块，
只用最外层参数做判定，避免被嵌套调用里的 `border =` 骗到）。

| 判据 | 扫描范围 | 结果 |
|---|---|---|
| 生产 `.kt` 文件数 | `composeApp/{common,android,ios,jvm,jvmShared}Main` + `androidApp/src/main` + `desktopApp/src/main` | **222** |
| `Surface/Card/OutlinedCard/ElevatedCard` 且带 `border`/`clip`/`relayCardShadow` | 同上 | **35** 处 |
| 其中带 `border =` 的卡片 | 同上 | **23** 处，**23** 处都显式声明 `shape`，`0` 处漏声明 |
| 显式 `RectangleShape` 的有框卡片 | 同上 | **0** |
| `Card` 的 `shape` 与 `relayCardShadow(shape = …)` 不一致 | 同上 | **0** |
| 同一 modifier 链里 `.clip(X)` 与 `.border/background(…, Y)` 形状不一致 | 同上 | **0** |
| 卡片同时写 `shape =` 与 `.clip(` | 同上 | **3** 处，全部逐字一致（`DesktopHistorySearch.kt:157`、`ThinkingBlock.kt:107`、`ChatApproval.kt:91`） |
| 父卡片与内层带边框卡片形状不同 | 同上 | **2** 处：`ChatBubbles.kt:423`（真实缺陷，已修）；`DesktopHistorySearch.kt:157` → `:335`（**假阳性**：「清除」按钮在带 12dp padding 的可滚 Column 内，离 dialog 的 12dp 裁剪边界很远，不会被切） |

低风险但未改的 **Rank C token drift**：`CabinetScreen.kt:438`、`CabinetPane.kt:275`
两处 `Card` 没显式写 `shape`。M3 的 `Card` 默认取 `CardDefaults.shape`（圆角），
不是 `Surface` 的 `RectangleShape`；真机截图里节点/工作区卡片四角连续，不属于断角事故。
另有若干裸 `RoundedCornerShape(N.dp)` 字面量，属既有 `:designTokenUsageGate` 基线欠债，
本轮不扩大改动面。

## 3. 本轮改动（4 个文件）

| 文件 | 改动 |
|---|---|
| `composeApp/src/commonMain/.../feature/chat/ChatBubbles.kt` | 抽出纯函数 `chatBubbleShape(...)`：`isCustomCard` 直接返回 `RelayShapes.medium`，让外层裁剪路径与内层描边路径重合；文字气泡的 6/8/16dp 编组圆角原样保留。另抽出 `quotedCardShape(isCustomCard)`：自定义卡 `bubblePad = 0.dp`、引用卡 `fillMaxWidth()` 四边齐平，必须与外层同形；文字气泡引用卡仍保持 `RelayShapes.small`。 |
| `composeApp/src/commonMain/.../ui/components/ScreenHistoryCard.kt` | KDoc 里写了「12dp」但代码一次都没用上 —— 补 `shape = RelayShapes.medium`。这是全量审计**新发现**的直角卡片。 |
| `composeApp/src/commonTest/.../feature/chat/FileCardStyleTest.kt` | 新增 4 条单测：自定义卡气泡在 8 种 `mine/prev/next` 组合下形状恒等 `RelayShapes.medium`；文字气泡三档半径不变；自定义卡内引用卡与外层同一形状对象；文字气泡引用卡保持 8dp。加了 `Shape.radii()` 把圆角读回 px 值，纯 JVM 可判。 |
| `composeApp/src/jvmTest/.../guards/CardShapeContractGuardTest.kt`（新增） | 全仓源码门禁：凡画 `border =` 的卡片必须显式 `shape =`，且不许显式 `RectangleShape`。要求扫描文件数 > 200、命中数 ≥ 20，防止「扫到空集合恒绿」。 |

**变异测试**：临时删掉 `ScreenHistoryCard.kt` 的 `shape = RelayShapes.medium`，
门禁当场以精确文件/行号报错；随后完整还原（`git diff` 复核过，无残留）。

## 4. 构建、测试与门禁读数

最终命令（一次 invocation，日志 `/tmp/e2e-20261105/style-final-gates.log`）：

```bash
./gradlew :core:jvmTest :app:jvmTest :cli:test :agent-contract:test :orchestration:test \
  :composeApp:jvmTest :composeApp:testAndroidHostTest :agentNode:test :desktopApp:test \
  designTokenUsageGate designConvergeGate designScreenDiffGate designSpecDrift \
  designDpDebtGate designStateSlotGate designIconGate designLocaleParityGate \
  designPreviewPairingGate designFormPairingGate :androidApp:validateDebugScreenshotTest \
  --continue --console=plain
```

- `BUILD SUCCESSFUL in 3m 8s`，`FINAL_GATES_EXIT=0`，`127 actionable tasks: 19 executed`。
- 测试 XML 汇总：**5018 tests / 0 failures / 0 errors**（4 skipped）。
  `:composeApp:jvmTest` **702/702**、`:composeApp:testAndroidHostTest` **378/378**。
- `designPreviewPairingGate`：`104 条判定无退化`；`designFormPairingGate`：`0 条违规`。
- `:androidApp:validateDebugScreenshotTest` 通过。截图源集 306 帧的 reference 由
  `:androidApp:updateDebugScreenshotTest` 重新生成；该目录被 `.gitignore:44` 忽略，
  `git ls-files` 为 0，**不是本次提交内容**，真正的门禁是 validate。
- **未纳入** `designContractSelfTest`：它在**未改动的 pristine HEAD** 上就已经红
  （`tools/design-state-slot-report.py --self-test` 的 `ChatFilesPhonePreview` render target
  期望 `chat` 实得 `None`；`tools/design-icon-map.py --self-test` 漏报重复 sprite symbol）。
  与本轮改动无关，不能拿它当本轮的通过项，也不能算本轮引入的失败。

## 5. 真机与真 luciola 宿主复核

- 装置：Pixel 6 `<device-serial>`（`com.kapp.tenon.debug`，`192.0.2.22`）↔
  `/Applications/Tidings.app`（`192.0.2.82`，`nodeId 46c23456…`）。
  `/healthz` `ok=true`；宿主 TCP LISTEN `17878 / 17880 / 17888`；
  `/v1/peers` 里 Pixel 6 `online=true`、`legacyDiscovery:"off"` —— 仍是真 luciola，
  没有为了看 UI 切回 legacy。
- APK 一致性（防止「截图其实是旧包」）：
  `androidApp/build/outputs/apk/debug/androidApp-debug.apk`，`61,810,057` B，
  sha256 `5dc6e5010126dd4d9cd89d91aa5d6ae57657ea06951fe1d4d2e18119ec32c5ad`；
  手机 `base.apk` 的 sha256 **逐字相同**。APK 构建于 `19:29:56`，晚于最后一处源码改动
  `19:18:01`；截图拍于 `19:31–19:33`。
- 复现卡片：旧 offer 是内存态，`install -r` 后必然消失，所以经真宿主
  `POST /v1/send-file` 重新发 `ui-full-review-1793878344.bin`（16384 B，
  `status:"offering"`，transferId `f303e640-247c-4d31-9e70-0ed49ea6a59b`），
  `uiautomator dump` 确认卡片可见后再截图。
- 像素级判据 `after-device-file-card-zoom.png`（2×）：四角都是连续圆弧，
  无横竖短线断口；文字气泡、`接收` / `拒绝` 按钮形状无回归。
- 四屏巡检：`文件柜`、`节点`、`工作区`、`设置` 全部实机点开并截图，
  卡片圆角连续、阴影与圆角同形，未发现新的断角/描边缺口。会话页 `after-device-chat.png`
  的文字气泡四角也保持原样。
- 渲染帧复核：`after-preview-chat-files.png` 四张文件卡四角连续；
  `after-preview-screen-card.png` 的屏幕协助卡由原先的四方直角变成圆角。

## 6. 证据文件

本目录 `design/runtime-evidence/2026-11-05-ui-shape/`。
**注意**：`.gitignore:54` 是 `/design/runtime-evidence/**/*.png`，仓库本来就不跟踪证据图
（`git ls-files design/runtime-evidence | grep '\.png$'` = 0）。下列 PNG 是本机留存的判读底片，
**随仓库流转的持久证据是这份 markdown + `ACCEPTANCE.md` §5d**；换机器要复核画面时，
按 §5 的命令重装同 sha256 的 APK、重发 offer 重新截图即可。

| 文件 | 说明 |
|---|---|
| `before-user-report.png` | 用户贴的原始问题截图（356×158，带红框） |
| `before-device-chat.png` | 修复前真机 1080×2400 原帧，三张文件卡断角 |
| `after-device-chat.png` | 修复后真机会话页，文字气泡无回归 |
| `after-device-file-card.png` | 修复后真机文件卡（新 offer） |
| `after-device-file-card-zoom.png` | 上帧四角 2× 放大，像素级判据 |
| `after-device-cabinet.png` / `after-device-nodes.png` / `after-device-workspace.png` / `after-device-settings.png` | 四个底部 tab 的修复后真机巡检 |
| `after-preview-chat-files.png` | 重新渲染的 `ChatFilesPhonePreview` 帧 |
| `after-preview-screen-card.png` | 重新渲染的 `ChatScreenCardPhonePreview` 帧 |

其它读数：`/tmp/e2e-20261105/style-final-gates.log`（全量门禁）、
`/tmp/e2e-20261105/style-sweep/`（原始截图与 `ui-full-review-1793878344.bin`）、
`/tmp/e2e-20261105/luciola-host-L5.log`（真 luciola 宿主 stdout）。

## 7. 未覆盖 / 不要越界宣称

- **引用卡 + 自定义卡**这处修复由源码推理 + 4 条单测 + 形状恒等判据钉住：
  现有 306 个 `@PreviewTest` 帧里没有「回复并附文件」的帧，控制面 `/v1/send` 也没有 quote/reply 字段，
  因此**没有单独拍到真机帧**。不为它单独造帧，是因为新增 Preview 会牵动
  `designPreviewPairingGate` / 台账基线，超出本轮修复范围。
- `ScreenHistoryCard` 的验证是 **Compose 截图渲染 + 真机 APK 装载**，没有在真机上
  真的发起一次屏幕协助会话；A17 的历史读数不因本节改写。
- 桌面专属窗口（`DesktopHistorySearch` 等）、深色主题下的边框观感、平板形态
  未在本轮真机巡检内；源码审计覆盖了它们。
- 本轮只改 UI 形状契约，**不触碰** §5c 里记录的两个真实缺陷：
  `PluginStateManager` 状态文件被建成目录（控制面 6 条 FAIL），以及打包
  `libluciola_uniffi.dylib` 与 0.9.0 绑定的 UniFFI checksum 不匹配（10/71）。
  `legacy` 夹具套件仍不作为验收路径。
