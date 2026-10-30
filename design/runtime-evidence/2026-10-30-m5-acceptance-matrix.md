# M5 真机验收矩阵读数（2026-10-30）

> 本文件是 M5 退出条件里「验收矩阵逐条有读数」的那一份。写法沿用 M1/M2/M4：
> **只写读数，不写推测**；没做到的写「未做 / 部分」，不包装成已验收。
> 截图不入库，序列号/家网段不入库（文档侧统一 192.0.2.x）。

## 0. 环境盘点（矩阵全部条目的共同前提）

| 项 | 读数 |
|---|---|
| 被测包 | **`com.kapp.tidings.debug`**（`versionName=0.1.0-debug`），`lastUpdateTime=2026-10-30 09:11:13` |
| 装机方式 | `adb install -r -d` → Success；产物 `~/Downloads/relay-build-2026-10-30-m5/androidApp-debug.apk`，sha256 `bf290bb0a447…f63` |
| **新代码符号探针**（回读设备 base.apk 的 dex） | `handleSetGroupAgentWorkspaces` 20 / `agentIntent` 8 / `card_json` 11 / `agent-invoke` 6 / `agi1` 2；**旧符号 0**（`extractSessionId` 0、`hostForKind` 0）⇒ 装的是 M1–M4 之后的代码 |
| 桌面端 | `/Applications/Tidings.app`，二进制 mtime `2026-10-30 08:02`；jar 侧探针 `card_json` 12 / `handleSetGroupAgentWorkspaces` 27 / `agent-invoke` 6 / `agi1` 1 |
| 两端与 HEAD 的关系 | 08:02 之后只有三个提交（`11d8ce03` cli、`d325c37c` docs、`223e7e7b` proto），**都不进 Android/桌面产物** ⇒ 两端二进制代码等价 HEAD |
| 手机节点 | nick `Pixel 6`，nodeId `e4bd7b99…`，profileRev 3 |
| 桌面节点 | nick `kedeMac-mini-97`，nodeId `46c23456…`，profileRev 2 |
| 共同群 | `teahouse-mobile 协同群`：两端均 **rev 12 / 6 名成员**（一致） |
| 插件台账（重启后） | `dsh-echo-bridge` online（leader `dsh`，cap `card`）；`echo-team-plugin` 的孤儿进程被清掉，重启后未再注册 |
| 授权 | 手机持有 `scope=all access=rw` + `scope=agent agentKey=DSH access=rw`；policy=`request` |
| 链路基线（手机 `diag/link.json`） | 74 个 peer，`closeCount` 合计 **0**，`lastError` 全空 |

### 0.1 两条环境事实纠正（前序笔记的坑）

1. **被测包是 `.debug`，不是 `com.kapp.tidings`**。设备上两个包并存：`com.kapp.tidings`
   是 10-26 的日常包（dex 里 `extractSessionId`/`hostForKind`/`workspaceOfGroup` 都还在 ⇒ 前 M1 代码），
   `com.kapp.tidings.debug` 才是被测包。此前「这台机器没装被测包」的结论是**查错了包名**
   （`pm list packages -3 | grep kapp.tidings` 只命中日常包）。本轮装机前 `dumpsys` 的
   `lastUpdateTime=2026-10-26` 也印证了这一点。
2. **插件不会跟着桌面重启自动回来**：`dsh-echo-bridge` 在本轮开工时是 offline
   （`lastError=health probe failed`），原因是 M4 的「热拔」把它 kill 了、而进程不会自愈。
   重启 Tidings.app 后新实例注册到新端口（63675）回 online。⇒ 矩阵第 4 条不能靠「重启一下」，
   要显式等 `offlineAfter`。

## 1. 第 1 条：手机 @agent 派单 → 桌面插件执行 → 手机收卡片 ✅

**操作**：手机群聊里点选 `@dsh-teahouse-mobile`（提及候选第 2 项）→ 键入 `m5-row1-probe`
→ 胶囊出现（`轻点选择工作区与任务` / `@dsh-teahouse-mobile · 新任务`）→ 点发送。

| 判据 | 读数 |
|---|---|
| 意图键真的发出去了 | 手机帧日志 `messages.log` seq **6593**，`dir=out`，`text=@dsh-teahouse-mobile m5-row1-probe`，**`agentIntent` 存在** |
| 宿主真的执行了 | 插件 `process.log` 收到 `POST /session/open` + `POST /task/execute` 均 200 |
| 卡片回到手机 | 同一日志 seq **6595**，`dir=in`，`card.cardId=echo-card-echo-session-1`，`rev=1`，`senderId=agent:46c23456…` |
| 时延 | 落库 `at` 列：6593 `1793323525186` → 6595 `1793323525246`，**60 ms** |
| **群流 0 条跨端中间文本** | 桌面库 7874 → 7876 = **+1 用户消息 +1 卡片**，没有第三条；手机日志同窗口只有 6593/6595 两条 group-text |
| 卡片按最新 rev 渲染 | 手机 UI 显示「Echo task · 第 1 版」，与库里 rev=1 一致 |
| 无假横幅 | UI 上没有「没有执行」横幅（M4-T6.5 修的那条） |

**一个必须记下的时间戳矛盾**：桌面库里这张卡片的 `timestamp=1793323518943`（09:25:18.943），
比同一轮用户消息的 `1793323525181`（09:25:25.181）**早 6.3 秒**；而手机帧日志给出的先后是
60 ms、方向正确。两者只能有一个是「真实发生顺序」。按 AGENTS 的既有纪律（判落库看 `messages.log`
的 `at` 列、不看信封 `ts`），**以手机帧日志为准**；桌面那行的 `timestamp` 更可能是沿用了帧里的
`ts` 而不是落库时刻。这条没有继续深挖（不阻塞任何判据），留作观察项。

## 2. 第 2 条：卡片原位刷新 ✅

**操作**：点卡片上的 `Echo again`（该动作走宿主原子台账 → `agent-ack` → 卡片 rev+1 全群广播）。

| 判据 | 读数 |
|---|---|
| DB 新增的是**新行** | 新增 `b1e15741`，`cardId` 同为 `echo-card-echo-session-1`，**`rev=2`**，`decidedBy=Pixel 6` |
| 旧行**不重写** | `293c1838`（rev 1）的 `timestamp`/`length(text)` 一字未变（1793323518943 / 9） |
| 总行数 | 7876 → 7877（+1） |
| FTS 不变量 | `MessageFts_docsize` 7877 ≡ `MessageEntity` 7877 |
| UI 不新增可见气泡 | 同一位置由「第 1 版」变「第 2 版」，卡片数量不变 |

## 3. 第 3 条：双端审批防重 ⚠️ 部分

| 判据 | 读数 |
|---|---|
| 第一个 ack 生效 | `decidedBy=Pixel 6` 落在 rev 2 那一行；此后该卡片按钮**二次点击 no-op**：再点一次，`MessageEntity` 仍 7877 行、`rev` 没有出现 3 |
| 另一端置灰并显示批准人 | **未取到读数**：桌面端是 Compose 自绘，`screencapture` 在本机被系统权限拒绝（`could not create image from display`），`osascript` UI 脚本同样被拒（-10004）。桌面与手机读的是同一行（`decidedBy=Pixel 6`），置灰是 `cardActionUiOf` 的纯函数结果、由 `MultiDeviceApprovalLockTest` 3 条仿真判据钉住，但**真机截图这一格是空的** |
| 并发双端同时点 | **未做**：只有一台手机 + 一台桌面，且两端点击无法在同一毫秒窗口内由人手触发。本轮只验了「已决之后第二次点击不产生新 ack」 |

## 4. 第 4 条：插件热拔 ⚠️ 部分（翻离线的机制与矩阵预设不同）

**操作**：`kill -9` 掉 `dsh-echo-bridge` 的进程（pid 16613，监听 63675），按 30 / 60 / 100 秒三档采样。

| 采样点 | `GET /v1/plugins` 读数 |
|---|---|
| t0（09:28:44） | `dsh-echo-bridge online` |
| t+30s（09:29:14） | **offline**，`lastError=health probe failed: …:63675/healthz unreachable` |
| t+60s（09:29:44） | offline（同上） |
| t+100s（09:30:24） | offline（同上） |
| 主程序 | pid 16573 存活，未崩 |

**判据偏差（必须写清）**：矩阵写的是「按 `offlineAfter`=90s 翻离线」，实测**≤30 秒**就翻了。
原因在代码里：`offlineAfter`（`Limits.kt` 的 90_000）是 `PeerRegistry.sweep` 的**节点 presence 超时**，
而插件/agent 的在线态走的是 **healthz 探测**（`PluginRegistry`），探测周期远短于 90s。
两者不是同一个时钟 —— 矩阵那一格的口径要改，否则下一个人会照着 90s 去等一个早就发生的事实。

**主程序 closeCount**：这一档期间桌面主进程存活，链路上没有新增 close（当时桌面→手机 `closeCount=4`，
`closeReason=replaced`，全部来自本次会话里两端的重启，没有一条 `timeout`/`error`）。

**顺带抓到一条产品缺口**：插件被 kill 后，宿主收到带 `agentIntent` 的群文本时**群流里没有任何反馈**
（第 7 条第一段就是这个现场：手机发了 @dsh，桌面无插件可派，手机端既没有卡片也没有「不可用」提示，
UI 上连一个「失败/离线」字样都搜不到）。设计稿 v2 §4.8「插件离线的动作反馈」尚未落地，这是它的真机证据。

## 5. 第 5 条：链路稳定 ⚠️ 部分（飞机模式没真的断网）

| 操作 | 手机 `closeCount` 合计 | 手机 `lastError` 数 | 桌面→手机 |
|---|---|---|---|
| 基线（09:31:40） | 0 | 0 | online / linkConnected / close 4 / replaced |
| `cmd connectivity airplane-mode enable` 后 20s | 0 | **2** | **仍 linkConnected=true** |
| 关飞机模式后 25s | 0 | 1 → 0 | online |
| 冷启动（force-stop + start）后 20s | 0 | 0 | close 4 → **5**（replaced） |

**结论与坑**：`cmd connectivity airplane-mode` **没有真的切断 Wi-Fi**（Android 12+ 保留已开启的 Wi-Fi），
所以那一档只验到「部分网络路径抖动下链路不抖」，不是全断网。改用 `svc wifi disable`
（`ip route` 为空 = 真断）重做，读数见第 7 条。
**真正判据成立的部分**：全程 `closeCount` 没有一次是因为超时/报错而涨 —— 每一次增长都是 `replaced`
（新链路顶掉旧链路），且 `lastError` 在恢复后清零；没有出现 AGENTS §2.5 说的「每 8.1 秒断一次」那种形态。

## 6. 第 6 条：授权面板 ❌ 未取到读数（手机壳上找不到入口）

**尝试过的五条路径**（都做过，都失败）：

1. 点群标题（`[296,166][688,218]`）→ 无反应；
2. 点「6 位成员」副标题 → 无反应；
3. 右上「更多功能」→ 打开的是**附件面板**（PK / 图片 / 发文件 / 语音消息 / 新任务 / 查找聊天记录），不是群信息；
4. 会话列表长按群行 → 只有「置顶 / 静音 / 删除会话（仅本机）」；
5. 会话列表右上「更多」→ 建群 / 关联工作区建群 / 新建会话 / 只看未读 / 全部标为已读 / 排序。

**根因（代码侧可对照）**：入口是 `ChatHeader` 的一个 action 图标（`ic_groups`，
`ChatHeader.kt` 里 `if (group != null && onOpenGroupInfo != null)`），而真机 dump 的聊天页头部
**只有两个可点节点**：`返回` 与 `更多功能`。也就是说这个 action 在手机壳上没渲染出来 ——
`ChatScreen.kt` 明明传了 `onOpenGroupInfo`，但手机头部没长出来。

**所以这一格是空的**，而且「空」本身是读数：**非宿主设备要验「只读且无路径」，先得能打开那个面板**。
宿主侧的「保存后 rev+1 广播、成员表同步」同样没验（桌面端 Compose 自绘，截图与 UI 脚本都被系统权限拒绝）。
M3 的 `GroupAgentWorkspacesPanelTest` 10 条是进程内判据，覆盖不到「入口在不在」这一层。

## 7. 第 7 条：队列重放 ⚠️ 部分（文本到达 ✅ / 卡片帧到达 ✅ / 卡片看不见 ❌）

**操作**：`svc wifi disable`（`ip route` 为空，真断网）→ 手机群聊里 @dsh 发 `m5-row7-play` → 4 秒后查队列 → `svc wifi enable` → 轮询桌面库。

| 判据 | 读数 |
|---|---|
| 离线时真的进了队列 | `queue.log` 里该消息 id `94850d16` 对 **5 个 peer** 各一条（含桌面 `46c23456…`） |
| 恢复后到达 | 开网 09:38:15 → 09:38:30（**t+15s**）桌面库命中 1 条，`text=@dsh-teahouse-mobile m5-row7-play` |
| 重放帧带意图 | 出站帧（手机 `messages.log` seq 6701）`keys=[agentIntent, groupId, groupRev, kind, text]`，`agentIntent={"agentId":"DSH","mode":"auto"}` |
| 卡片回得来（帧层） | 手机 `messages.log` seq **6748**（in）带完整 card：`cardId=echo-card-echo-session-1`、`rev=1` |
| **卡片看得见（渲染层）** | **❌ 看不到**：同一 `cardId` 上已有一张 `rev=2`（第 2 条那次审批产生的）的卡，客户端按设计折叠到 rev 最大者 ⇒ 新到的 rev 1 **整行不渲染**，UI 上那张卡还停在「第 2 版 / 已由 Pixel 6 处理」 |
| 无重复落库 | 两次离线尝试是两条不同消息（`e634f7ae` 与 `94850d16`），各落一条，没有重复；FTS 7880 ≡ 7880 |

**怎么判这一格**：判据原文是「恢复后到达且卡片完整」。**帧层成立**（意图键、卡片对象、rev 全在），
**渲染层不成立**。根因不在重放链路，而在**夹具插件**：`dsh-echo-bridge` 对同一 session 固定用
`echo-card-<sessionId>` + 固定 `rev=1`，于是第二次派单的卡片必然被第一次的 rev 2 压住。
真插件应当换 `cardId` 或递增 `rev`。⇒ 这一条要补的不是客户端代码，而是**夹具的卡片命名**；
在此之前，任何「同一 session 派两次」的真机验收都会看到这个假阴性。

## 8. 本轮小结

| # | 场景 | 结论 |
|---|---|---|
| 1 | 手机 @agent 派单 → 桌面执行 → 手机收卡片 | ✅（60 ms，零中间文本，intent 键在） |
| 2 | 卡片原位刷新 | ✅（新行 rev+1，旧行不重写，FTS 不变量成立） |
| 3 | 双端审批防重 | ⚠️ 本端成立（二次点击 no-op），另一端置灰无截图 |
| 4 | 插件热拔 | ⚠️ 翻离线 ≤30s（healthz，不是 90s presence）；顺带抓到「agent 离线零反馈」 |
| 5 | 链路稳定 | ⚠️ 飞机模式没真断网；`closeCount` 只涨 `replaced`，无超时/报错型断开 |
| 6 | 授权面板 | ❌ 手机壳上找不到入口，这一格空着 |
| 7 | 队列重放 | ⚠️ 文本 + 卡片帧都到，UI 被夹具的固定 `cardId`/`rev` 压住 |

**三条要改的东西**（按代价排序）：

1. **矩阵口径**：第 4 条的「按 `offlineAfter`=90s」改成「按 healthz 探测周期（实测 ≤30s）」——
   90s 是 peer presence，不是插件/agent 在线态。
2. **手机壳的群信息入口**（第 6 条）：`ChatScreen` 传了 `onOpenGroupInfo`，手机头部没渲染 action。
   在这条修好之前，授权面板在真机上等于不存在 —— 这是本轮最该修的一条。
3. **夹具插件的卡片命名**（第 7 条）：同 session 固定 `cardId` + 固定 `rev=1` 会让「第二次派单」
   的卡片永远看不见。改 `cardId`（带任务 id）或递增 `rev`。

**没有包装的**：第 3 条的并发双端点击、第 4 条的三档时间窗之外、第 6 条整条、第 7 条的渲染层。
本轮只跑了一台手机 + 一台桌面，双桌面跨端仍由 Layer 4 仿真覆盖（M4 的裁决继续有效）。
