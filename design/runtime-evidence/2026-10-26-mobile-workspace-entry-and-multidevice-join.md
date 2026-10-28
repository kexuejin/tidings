# 真机实测：手机补上「关联工作区建群」入口 + 0 成员群加入设备后 agent 同步（2026-10-26）

> **一句话结论：两条都成立** —— ① compact 布局现在能从消息页 ⋮ 菜单建工作区群，真机点通；② 先建 0 成员的工作区群、事后再把手机加进来，手机收到的 `group.info` 带着 `virtualAgents`，群信息里显示「入驻智能体 (1) dsh-care-in-china」。

## 0. 结论速览

1. **手机入口**：`ConversationsOverflowItems` 新增一项「关联工作区建群」（紧跟「建群」），`App.kt` 不再按 `twoPane` 分支；工作区看板顶栏那颗按钮也随之在 compact 出现。真机（Pixel 6 / 1080×2400 竖屏）实测：⋮ → 关联工作区建群 → 对话框打开 → 填 `/sdcard/verify-ws` → 立即创建协同群 → 进入群聊，横幅「「协同群」建好了，正在通知 1 位成员。」
2. **多设备入群**：桌面端在**0 台在线设备**时建出「care-in-china 协同群」：`members=[self]`、`rev=1`、`virtualAgents=[{agentId:DSH, hostNodeId:self, name:dsh-care-in-china, wsId:ws-redacted}]`；随后从群信息「邀请新成员」把 Pixel 6 加进来 ⇒ `rev=2`、`members=[self, c3d4e5f6-…]`，**手机端收到并显示**：`协同人类成员 (2)` + `入驻智能体 (1) dsh-care-in-china` + `@dsh-care-in-china`。
3. **两条既有红灯已清**：`envDependentTestGate`（Luciola 两个测试里 17+4 处固定 `delay` 当同步）与 `designPreviewPairingGate`（4 个 settings 预览缺 `onExperimentalLuciolaChange`）都转绿，判据见 §4。

## 1. 环境

| 项 | 值 |
|---|---|
| 桌面 | `/Applications/Tidings.app`（本轮源码构建），真实数据目录；节点 `a1b2c3d4` |
| 手机 | Pixel 6 / Android 15，`com.kapp.tidings` release（`adb install -r -d` 覆盖，数据保留） |
| 驱动 | uiautomator dump（手机）＋ System Events/AXPress（桌面）——**不用截图、不用坐标猜** |

## 2. 手机入口（compact）

改前的证据：工作区页只有「刷新」，消息页 ⋮ 菜单只有「建群 / 新建会话 / 只看未读 / 全部标为已读 / 排序」。改后同一位置：

```
建群 | [606,326][680,379]
关联工作区建群 | [606,452][861,505]      ← 新增
新建会话（去配对节点） | [606,578][1006,631]
```

点开后对话框（compact 也是同一个 `WorkspaceGroupCreateDialog`，标题「关联工作区建群」），手机没有工作区宣告时给出空态 +「关联本地新目录」分支：

```
暂无已宣告的本地工作区，可在下方直接关联新目录
关联本地新目录 | EditText | [183,1128][897,1275]
立即创建协同群 | [605,1746][860,1798]
```

填路径并确认后的读数：

```
「协同群」建好了，正在通知 1 位成员。
2 位成员 · 1/2 人在线
```

（成员=2 是因为手机当时有 1 台在线对端：`state.peers.filter { it.online }`；0 对端那一档由 §3 的桌面侧证明。）

## 3. 多设备入群：0 成员 → 加人 → agent 同步

1. 先 `am force-stop` 手机，等桌面侧 `online=0`（实测 `tidings_status` 从 `online:1` 变 `online:0`）。
2. 桌面：工作区 → 关联工作区建群 → 一键建群 / 共享 ⇒ 横幅「「care-in-china 协同群」建好了。群里还没有其它设备 —— 对端上线后可从群信息里添加成员。」
3. 落库读数（`settings.json`）：
   ```json
   members: ["a1b2c3d4-6e74-4a7c-aab1-20a1d1284cd6"]
   rev: 1
   virtualAgents: [{ agentId: "DSH", hostNodeId: "a1b2c3d4-…",
                     name: "dsh-care-in-china", wsId: "ws-redacted" }]
   ```
4. 手机重新上线后，桌面群信息 → 邀请新成员 → 选「Pixel 6, 在线」→ 确定：
   ```
   2 位成员 · rev 2 · 群主 <host>
   已邀请 1 位新成员，正在通知全群。
   members: ["a1b2c3d4-…", "00000000-0000-4000-8000-0000000000a1"]
   virtualAgents: [dsh-care-in-china]   ← 加人没有把它冲掉
   ```
5. **手机端（关键）**：会话列表出现「care-in-china 协同群 1/2 人在线」；进群 → 群信息：
   ```
   协同人类成员 (2)   Pixel 6 / <host>（群主）
   入驻智能体 (1)     dsh-care-in-china   智能体   @dsh-care-in-china
   ```
   ⇒ `virtualAgents` 随 `group.info` 同步到对端，且 @ token 在手机侧可用。

## 4. 两条既有红灯的判据与改法

### 4.1 envDependentTestGate（B 类：断言前用固定 delay 同步）

- 改前：`core/.../LuciolaSecureChannelTest.kt` B=17、`desktopApp/.../DesktopLuciolaHostTest.kt` B=4；
- 改法：把所有「先 delay 再断言」换成两个具名工具（各自模块一份，`AwaitSupport.kt`）：
  - `awaitTrue(timeoutMs) { … }` —— **有界**等待某个真事件发生，到点失败；
  - `deliverRepeatedly(times) { … }` —— 明确表示「这是重复投递的刺激节奏」，断言仍需自带上界。
  负向断言（「没进信任库」）额外先等一个正向信号（`rejectedInbound >= 1`）再断言，避免「什么都没发生」被当成通过。
- 改后：`扫描 355 个测试文件；A/B 类 0 个`。

### 4.2 designPreviewPairingGate（可空闸门参数没传）

- 改前：4 处 —— `DeskSettingsDataPreview` / `SettingsMoreLoadingPhonePreview` / `SettingsMoreLongTextPhonePreview` / `SettingsMoreStoragePhonePreview` 都没传 `onExperimentalLuciolaChange`，那一支开关点了没反应；
- 改法：四个预览补 `onExperimentalLuciolaChange = {}`（生产 `App.kt` 接的是真 controller 方法）；
- 改后：`OK — 104 条判定无退化；夹具欠武装 23 处无新增`。

## 5. 验证命令与结果

```bash
./gradlew :core:jvmTest :app:jvmTest :cli:test :composeApp:jvmTest \
          :composeApp:testAndroidHostTest :agentNode:test :desktopApp:test \
          --continue --max-workers=1        # BUILD SUCCESSFUL
./gradlew :cjkLiteralGate :constructionTraceGate :staleCommentRefGate :domainComposeFreeGate \
          :envDependentTestGate :designSpecDrift :designTokenUsageGate :designStateSlotGate \
          :designLocaleParityGate :jlinkModulesGate :designScreenDiffGate :designIconGate \
          :designConvergeGate :designFormPairingGate :designPreviewPairingGate \
          :designDupFrameGate :designDpDebtGate --continue --max-workers=1   # 17 项全绿
```

## 6. 诚实边界

1. 手机是 release 包（`run-as` 不可用），手机侧读数**全部来自 uiautomator 文本**，没有读库对照；桌面侧有 `settings.json` 逐字段读数。
2. §2 那次手机建群用的是「关联本地新目录」分支（手机没有工作区宣告），因此组名是兜底「协同群」、路径 `/sdcard/verify-ws`；**工作区卡片那条（分支 B）在手机上仍未被拍到**——手机要看到工作区行，得先有设备向它宣告 `agent-workspace`（本轮手机 `0 工作区`）。
3. 本轮在真实数据里留下两个测试群：`协同群`（手机建，`/sdcard/verify-ws`）与 `care-in-china 协同群`（桌面建，含 Pixel 6）。产品没有「解散群聊」入口，只能「退出群聊」，所以**留着等人工清理**。
4. 手机侧对 `virtualAgents` 的显示依赖它收到的 `group.info` 里带该字段；本轮桌面是 rev=2 广播，带上了。**rev=1 那一份（0 成员）没有外发**——与设计一致，也因此没验证「建群瞬间对端就收到 agent」。