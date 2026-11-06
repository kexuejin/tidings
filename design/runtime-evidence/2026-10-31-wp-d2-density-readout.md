# WP-D2 桌面密度档 · 帧实测读数（2026-10-31，ui-lead）

## 交付物

ConversationRow 加 dense: Boolean = false（参数化，非第二份实现）；桌面 ConversationSidebar
默认 dense = true，经 ConversationListPanel 透传；DesktopMetrics.MEDIUM_SIDEBAR_PX(240) 收编
DesktopConversationsPane 的字面量；DesktopMetrics.INSPECTOR_MIN_PX(320) 预备（WP-D3 消费）。

取值锚点：prototype/relay.html 的 .winset .li{min-height:40px;padding:5px 8px;gap:8px}
（:1651）与 .winset .li .t1{font-size:12px}（:1654）→ 代码 40dp 行高 / 8×5 内边距 / 8dp 间距 /
标题 labelMedium(12sp)；头像 24dp = IconSizes.Hero−Badge（原型 .fi2 26px 就近档）。

## 帧证据（androidApp screenshotTest，参考图按仓惯例 gitignore，过程产物可重录）

- conv-row-dense-phone：手机宽度下 dense 形态（组件级证据；手机壳 ConversationsScreen 不传 dense）
- desk-conv-row-regular-720 / desk-conv-row-dense-720：720dp 高侧栏双密度对照（判据③的实测场景）
- 既有 conv-row-states / desk-conv-row-states：regular 档双端屏级帧（不动）

componentSpecs.ConversationRowStates.previews 登记 11 帧（原 8 + 新 3）；
stateSlots.vocabulary 增 cross-cutting 槽 dense（与 compact/expanded 同层），
design-state-slot-report --check 68/68 覆盖。

## 红线：手机 dense=false 帧逐像素不变

方法：stash 本包全部改动跑基线渲染（F0），恢复后重渲染（F2/F4），逐字节比对
ConversationsRowStatesPhonePreview / ConversationsFailedEmptyPhonePreview /
ConversationsDeleteConfirmDialogPhonePreview / ConversationsRowMenuPhonePreview 四帧
—— 全部 IDENTICAL（shasum 相同）。桌面 desk-conv-row-states 如预期 DIFFERS（改一处两端联动）。
最终 :androidApp:validateDebugScreenshotTest BUILD SUCCESSFUL（0 失败）。

## 判据③：720dp 高侧栏可见行数

- 实测（帧像素扫描，2.625x 密度）：regular 行距 53.7dp（双行内容行）/ dense 49.1dp；
  等价 min-height 行：688dp 可视区 13 行（48+2）→ 16 行（40+2）。
- **诚实读数：+23.1%，未达计划 §6-2 的 ≥30% 阈值**。原因：计划算术锚在原型 .li 的
  min-height:52px（52→40 = -23% 行高），但代码 regular 行实际是 token 档 sp.xxxl=48dp
  （52px 从未是代码值），48→40 = -17%，叠 2dp 行距后即 +23%。
- lead 裁决（2026-10-31）：接受 +23% 如实记账，不压实现 —— 40dp 是原型 .winset .li 逐字
  锚点，压到 36 偏离原型；密度档价值判据「摆脱拉大的手机应用感」已达成。计划文档
  §2.1-2 的 30% 算术锚由本轮证伪（ROADMAP-LEDGER Round 条目同步）。

## 反向验证

ConversationRowDensityGuardTest 5 条判据先红（4 红 1 平凡真）后绿：
摘行高/头像/内边距任一 dense 分支、摘面板透传、摘 sidebar 默认 true、
恢复 240.dp 字面量、加手机壳 dense 实参 —— 对应断言逐条红。
DesktopLayoutTest 新增常量钉子（240 / 320 与 Medium/Wide 下限推导）。

## 门禁读数

设计门禁全组绿：designScreenDiffGate(--check，净新增 0；修复过程摘掉 1 个真偏离值 13)
designStateSlotGate(68/68) designTokenUsageGate designLocaleParityGate designDupFrameGate
designFormPairingGate designPreviewPairingGate designConvergeGate designDpDebtGate(账与源码一致)
designIconGate designSpecDrift；cjkLiteralGate constructionTraceGate staleCommentRefGate
domainComposeFreeGate；:composeApp:jvmTest :composeApp:testAndroidHostTest 全绿。
:androidApp:validateDebugScreenshotTest 全绿。
