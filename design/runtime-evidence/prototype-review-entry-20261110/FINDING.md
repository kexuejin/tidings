# 原型评审入口：从「盲批」到「能看再批」

日期：2026-11-10
范围：`composeApp`（客户端卡片/产物）、`agentNode`（原型卡动作）、`orchestration`（宿主动作分发）、`scripts/sdlc-lifecycle.py`（判据）

## 结论

阶段 A 的「人审」过去是**空转的**：原型卡上有 `Approve & Develop`，但客户端既没有
端内预览、也没有外部打开通路（`onPreviewArtifact` 无生产传入），用户是在看不到原型
的情况下点批准。本次按「先判据、后 UI」补齐三层：

| 层 | 做了什么 | 判据 |
| :--- | :--- | :--- |
| 判据 | 新增 `a2r` 幕（仅 `--tier real`） | `prototype-card-delivered` / `prototype-preview-entry-tested` / `prototype-opened-in-browser` |
| 通路 | `ArtifactPreview.Kind.Html` + `opensExternally` 分流；`ChatMessageList` 接 `ReceivedFileActions.open` | `PrototypeReviewEntryTest`（4 条）、`ArtifactPreviewRoutingTest`（10 条） |
| 动作 | 原型卡多出 `open-prototype`（客户端本地兑现） | `StageChainTest`（动作 + `labelKey`） |

直驱链（headless）没有群卡片、没有产物投递、没有手机 —— `a2r` 如实标 `NA`，
不用「worktree 里有这些字节」冒充「人能看到并评审」。

## 一、判据层：`a2r` 判什么

`[事实](来源:scripts/sdlc-lifecycle.py,时间:2026-11)`

| 判据名 | 地面真相 | 为什么不是更弱的读数 |
| :--- | :--- | :--- |
| `prototype-card-delivered` | 卡片库 `MessageEntity.card_json`：本次群会话存在 `artifacts[]` 里 `.html` / `mime=text/html` 且 `delivered=true` 的卡 | 「worktree 里有 prototype.html」只证明 agent 会写文件；这张判的是它**真的投递到了客户端** |
| `prototype-preview-entry-tested` | 真跑 `:composeApp:jvmTest`（`PrototypeReviewEntryTest` + `ArtifactPreviewRoutingTest`） | 「源码里有个 if」不算；判的是 `.html` 认得出、`delivered` 且已落盘才给入口、网页原型走系统打开 |
| `prototype-opened-in-browser` | `adb shell dumpsys activity activities` 的前台包名 ∈ 浏览器白名单 | 不认截图（截图分不清「浏览器」与「App 自己的页面」） |

`a2r` 已进 `from-zero` 与 `bootstrap` 两个幕序列，位置在 `a2` 之后、`a3` 之前：
原型写完就要能评审，而不是等实现完了再回头看。

`[事实](来源:scripts/sdlc-lifecycle.py --dry-run,时间:2026-11-10)` 结构守卫 22 项全绿，
其中三条专盯这一幕不许退化成假绿：

- `review-gate-is-real-tier-only`：headless 必须直接返回 None；
- `review-gate-judges-delivery-by-card-not-bytes`：「已投递」只从卡片库读；
- `review-gate-judges-browser-by-package-not-screenshot`：只认前台包名。

## 二、通路层：两条打开通路，一次分流

`[事实](来源:composeApp/src/commonMain/.../ArtifactPreview.kt,时间:2026-11)`

`Kind` 新增 `Html(opensExternally = true)`，`kindOf` 认 `.html` / `.htm` / `mime=text/html`。
端内没有浏览器引擎，所以 Html **不属于端内渲染**，按文本渲染源码只会让用户以为原型坏了；
它属于「交给系统打开」：

- Android：`ACTION_VIEW` + `FileProvider`（`AndroidReceivedFileActions`）
- 桌面：AWT `Desktop.open`（`DesktopReceivedFileActions`）
- iOS：`UIDocumentInteractionController` Quick Look（`IosReceivedFileActions`）

`[事实](来源:AgentCardParts.kt:409-500,时间:2026-11)` 产物条目在**这一处**分流：

```kotlin
val previewable = ArtifactPreview.canPreview(artifact, kind, savedPath) &&
    (!kind.opensExternally || onPreviewArtifact != null)
...
Modifier.clickable {
    if (kind.opensExternally) savedPath?.let { onPreviewArtifact?.invoke(artifact, it) }
    else previewOpen = true   // 端内弹窗：md / diff / txt
}
```

顺手修掉一个旧缺口：`previewable` 以前还要求 `onPreviewArtifact != null`，于是
**Markdown / Diff / 文本的端内预览在生产里从来没画过**（那个槽一直是 null）。
弹窗是自包含的，不需要壳给通路，现在按 `previewable` 判。

刻意**没有**做的事：不把本机路径拼成 `file://` 可点链接 —— `ChatReviewLogicTest`
明令禁止（`file:` 不该成为文本里的可点目标），这里走的是系统应用打开通路。

## 三、动作层：`open-prototype` 是本地动作

`[事实](来源:agentNode/.../StageChain.kt:496-510,时间:2026-11)` 原型态卡的 `actions`
现在是两个：`open-prototype`（`labelKey=card.action.open_prototype`，描边）+
`approve-prototype`（`approval=true`，主按钮）。评审排在批准之前 —— 顺序就是流程。

`[事实](来源:composeApp/.../ChatMessageList.kt,时间:2026-11)` 客户端拦截这条动作：

```kotlin
if (isLocalCardAction(actionId)) {
    prototypeSavedPathOf(card, savedPathOf)?.let { receivedFileActions?.open(it) }
} else {
    pendingCardActions = pendingCardActions + "${card.cardId}|$actionId"
    dispatch(peer.nodeId, card, actionId)
}
```

理由两条，都写进代码注释了：

1. **不置 pending**：宿主不会回这一帧，乐观置灰会永久留着；
2. **不派发**：宿主没有这条语义，发过去会被 `HostCardActions` 当「不认识的卡片动作」
   丢掉并顺手把卡翻成 stale。

死按钮纪律也接上了：`CardButtonGroupRow` 新增 `isActionVisible` 槽，
`ChatMessageList` 用 `prototypeSavedPathOf(...) != null` 判 —— 产物还没落到本机时
**整颗按钮不画**，而不是画一颗灰的。

`[事实](来源:orchestration/.../HostCardActions.kt,时间:2026-11)` 旧端把这条动作派上来时，
新增分支只 `RelayLog.info` 留痕，**不翻面** —— 卡还活着，只是这一步在客户端做。

## 四、实测

`[事实](来源:gradle test XML 汇总,时间:2026-11-10)` 三模块全量（`--rerun-tasks` 强制重跑）：

| 模块 | tests | failures | errors | skipped |
| :--- | ---: | ---: | ---: | ---: |
| `:composeApp:jvmTest` | 786 | 0 | 0 | 0 |
| `:agentNode:test` | 544 | 0 | 0 | 1 |
| `:orchestration:test` | 216 | 0 | 0 | 0 |

新增 `PrototypeReviewEntryTest` 4 条全绿：`.html` 路由、按名字认原型、落盘前后路径有无、
本地动作判定。

`[事实](来源:反向探针,时间:2026-11-10)` 把 `kindOf` 的 `.html` 分支改成 `.html-probe`
（即删掉真分支）后：

```text
PrototypeReviewEntryTest[jvm] > htmlPrototypeRoutesToTheSystemBrowser[jvm] FAILED
4 tests completed, 1 failed
BUILD FAILED in 16s
```

恢复后复跑 `BUILD SUCCESSFUL`。守卫不是空转。

`[事实](来源:真机 adb 双向探针，设备 <device-serial>,时间:2026-11-10)` 判据的两个方向都验过：

```text
前台是桌面 launcher  -> foreground='com.google.android.apps.nexuslauncher'  is browser=False
打开 https://example.com -> foreground='com.android.chrome'                 is browser=True
```

设备装的是 Chrome 且是默认 `VIEW` handler，所以「打开原型 → 前台变浏览器」这条路在真机上成立。

`[事实](来源:--tier real --act a2r 两次探针 + headless 对照,时间:2026-11-10)` 幕本身两种档位都跑过：

```text
# tier=real，E2E_GROUP 未设（没有群就无法定位卡片，如实判红）
[FAIL] prototype-card-delivered — 需要 E2E_GROUP 才能定位群会话；不跳过、不猜
[ok]   prototype-preview-entry-tested — gradle :composeApp:jvmTest 通过（路由与入口判据）
[FAIL] prototype-opened-in-browser — 6s 内前台不是浏览器（最后读到：com.google.android.apps.nexuslauncher）
=> 幕 a2r FAIL

# tier=headless（直驱链没有卡片/投递/手机）
=> 幕 a2r NA (0.0s)
```

顺带修了两处判据自身的毛病：预算文案原来写死「300s」（`--timeout` 改小后读数对不上），
以及 `dumpsys activity activities` 在一次转场中间可能短暂没有 `ResumedActivity` 行
（偶发一次空读）—— 现在退到 `dumpsys window` 的焦点行，并报「最后读到的包名」。

`[事实](来源:gradle 门禁,时间:2026-11-10)` 收工门禁全绿：
`constructionTraceGate`（0 条命中）、`cjkLiteralGate`（72 = 72，未新增）、
`staleCommentRefGate`（0 条）、`designLocaleParityGate`（补了 3 条英文词条后 0 退化）、
`designTokenUsageGate`、`designConvergeGate`、`designStateSlotGate`、`designIconGate`、
`designPreviewPairingGate`、`domainComposeFreeGate`、`unused-imports`、`py_compile`。

`[事实](来源::orchestration:test 全量,时间:2026-11-10)` 一次并发跑三模块时
`TriggerEventDrainTest.drainRetriesAfterACycleFailureWithoutWaitingForAnotherEvent`
判红（`expected done but was running`）；单跑该类 3/3 绿，串行全量 216/216 绿。
`[推断]` 这是已知的负载敏感时序（handler 已收到事件、库状态写入尚未完成就被断言），
与本轮改动无因果：把 `HostCardActions` 新增分支摘掉后单跑该类通过、带回来单跑 3/3 也通过，
唯一稳定复现的变量是「三模块测试任务并发」。

## 五、边界（没做的，别当成做了）

- `a2r` 的前两条判据（`prototype-card-delivered`、`prototype-opened-in-browser`）
  **还没在一次真实阶段 A 流程里跑过**：需要 `E2E_GROUP` + 手机 `@` 派单 + 真宿主 +
  人在手机上点「在浏览器打开原型」。本次只验到「判定器本身双向可信」。
- 桌面设计预览壳（`DesktopPreviewActions.receivedFileActions = null`）：网页原型入口
  不画 —— 如实，不是遗漏。
- 卡片被判定（`decidedBy` 落定）后，`open-prototype` 会随按钮组一起禁用；审批后想再看
  原型，目前要回到产物条目点。要不要让这类只读动作豁免锁态，是产品取舍，没动。
- agent 若没按 `purpose=artifact` 投递 `.html`，产物条目与入口都不会出现（不会凭空造）。

## 六、下一步

```bash
E2E_GROUP=<群名> E2E_ADB_SERIAL=<device-serial> \
  python3 scripts/sdlc-lifecycle.py --tier real --stage empty --plan bootstrap
```

跑通后再把 `--repeat 2` 挂上，让「人审」也进反复验收基线。
