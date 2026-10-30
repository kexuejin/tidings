# 真机与宿主面验收清单

这份清单存在的理由只有一条：**自动化证据止步于「进程内 + 本机回环」**，下面这些要在真设备上点出来。
开发机上有可用设备：Pixel 6（`SERIAL-TEST-01`，Android 15 / SDK 35）与两个 booted 的 iOS 模拟器
（iPhone 17 Pro、PigeonVerify）。**跑过的条目在标题后标 `✅ 已跑`，并写出证据文件名** —— 原始命令
与输出在 `design/runtime-evidence/*.txt`（截图不入库）；没跑过的保持空白。
「代码写完了」不等于「验证过了」，这份清单就是用来把这两件事分开的。

自动化已经覆盖的部分（跑 `./gradlew` 七个门禁任务，外加一条不在 Gradle 里的调色板对账
`python3 tools/check-design-palette.py --strict`，见 [README](README.md#测试)）：

| 维度 | 现状 |
| --- | --- |
| 单元/协议测试 | 772 个用例，0 失败（KMP 模块 jvm + iosSimulatorArm64 双跑，JVM-only 模块单跑） |
| 协议覆盖完整性 | 17 种信封类型 + 5 种 `msg` kind 逐条表态；`MsgTypes.HANDLED` ∪ `UNHANDLED` = `KNOWN`，由 `MsgTypeDispositionTest` 钉住（人读版 [`PROTOCOL-COVERAGE.md`](PROTOCOL-COVERAGE.md)） |
| 界面截图基线 | 93 张参考图（手机 393×852、平板、桌面三档宽度、深浅色、输入法自检页）；每张另算一条渲染校验，与上面的用例数分开计 |
| 界面配色一致性 | 93 张参考图里占比超过 0.5% 的每个色值都说得出来源（声明色 / 派生色 / 白名单），由 `tools/check-design-palette.py --strict` 把关 |
| 硬编码中文门禁 | 122 处 / 16 文件（棘轮，只降不升） |
| 协议一致性 | golden 向量 14（TS 生成）+ 2（老节点结论）+ 5（本仓扩展） |
| 二维码编码 | 黄金向量 7（libqrencode 4.1.1 生成），外加 `tools/verify-rendered-qr.py` 做屏幕像素比对 |

自动化**不能**覆盖的部分，就是下面每一条：权限弹窗、相机栈、系统分享/打开、广播帧在
Wi-Fi 省电模式下能否收到、TCP 直连、托盘/深链/多窗口、通知在真实通知栏里的样子。

## 0. 怎么记录

- **文本读数进仓库**：写到 `design/runtime-evidence/*.txt`，格式照
  `cross-net-01-latency-shim.txt`（结论 → 链路 → 参数 → 逐项读数）。
- **截图不进仓库**：同目录 `*.png` 被 `.gitignore` 排除（通知栏、时间、电量、设备是真实信息）。
  截图留在本地，把结论写进上面那份 txt。
- 每条请写清四件事：**怎么做的 / 期望看到什么 / 实际看到什么 / 结论（通过·不通过·没做成）**。
  没做成的项**不要删** —— 留在这里比假装通过有价值。

## 1. 准备

- 手机端：本仓库当前 HEAD 构建的 debug 包（Android `:androidApp:assembleDebug`，
  iOS 走 `iosApp` 的 Xcode 工程）。
- 对端：上游协议基准节点 **0.61.1**（协议基准版本），或本仓库的桌面外壳
  `:desktopApp:run`（第 5 节单独验）。
- 两端在**同一个 Wi-Fi**，且该 Wi-Fi **没有开启 AP 隔离**（开了的话广播互相看不见，
  这时请用「填对方地址」手动节点，那本身也是一条用例）。
- 手机端当前声明的权限：Android `INTERNET`、`ACCESS_NETWORK_STATE`、`POST_NOTIFICATIONS`、
  `CAMERA`（运行时申请）；iOS `NSLocalNetworkUsageDescription` + `NSBonjourServices(_relay._udp)`
  + `NSCameraUsageDescription`。相机那两项是 A15 的前提：Android 不声明 `CAMERA` 时
  `ActivityResultContracts.RequestPermission()` 会**立刻回调 false 且不弹窗**；
  iOS 缺 `NSCameraUsageDescription` 时请求相机权限会被系统**直接杀进程**。

## 2. Android 真机

### A1 首启不崩、能绑上端口 ✅ 已跑（2026-10-07）
- 步骤：装包，点图标。
- 期望：「节点」页状态是**正在监听端口…** → 本机地址出现 `192.168.x.x:17878`，
  状态变「正常」，顶部同步条从「广播已停」变绿。
- 反例：状态停在「启动失败 · 见下方提示」，下方写出真原因（例如
  `UDP 端口 17878 没能监听（Address already in use）`）。这时点「重新监听」应能起来。
- 注意：**没有 `INTERNET` 权限时 `DatagramSocket` 在真机抛 `SocketException: Permission denied`，
  而构建与单测全绿** —— 这是清单里最容易被漏掉的一类缺陷。
- 证据（2026-10-07，Pixel 6）：`design/runtime-evidence/scan-a15-android-permission.txt`
  记着顶部「mDNS 广播中 · UDP 17878 / TCP 17879」、本机地址 `192.0.2.22:17878`、
  状态「正常 · 本机防火墙已放行」、在线节点 `1 / 1`，且冷启动后恢复出上次的会话线程。
  > **这条读数要按模式读**：它取自带该同步条的构建。加密数据面开启后，顶部不再出现 `TCP 17879`
  > —— 那个监听已退役（判据见 A21）；**legacy 模式下仍是 17879**。`UDP 17878` 两种模式都在。

### A2 通知权限（Android 13+）✅ 授权与拒绝两条路都跑过（2026-10-07）
- 步骤：首次进入后应弹系统通知授权；拒绝一次，再进「设置 → 通知」重新打开。
- 期望：授权后收到文本会弹通知；拒绝时**不弹**且**不崩**。
- 反例：`NotificationManagerCompat.notify` 抛 `SecurityException`，或被静默吞掉 ——
  症状是「收到消息却什么都没弹」，而构建与单测全绿（2026-10-04 在 Pixel 6 上实测到过）。
- 证据（2026-10-07，Pixel 6）：`design/runtime-evidence/recv-a2-android-text-notify.txt`
  记着 `dumpsys notification` 里 `channel=relay_messages_v2`、`android.title=String (证据节点)`、
  正文是消息全文，以及会话行、会话页与 `files/relay/messages.json`。
  **「拒绝一次」那半条已在 2026-10-07 补跑**（`pm revoke POST_NOTIFICATIONS` → 发送 →
  `dumpsys` 里 0 条通知、`messages.json` 里 1 条、`pidof app.relay` 仍在、logcat 有
  `W AndroidPushNotification: 通知未发出：通知权限未授予（系统设置里可改）`）：不弹、不崩、
  也不静默吞掉。连同三个通知开关（Agent 开关 / 静默时段）与戳一碰的真机判读，见
  `design/runtime-evidence/notify-android-policy.txt`。

### A3 局域网自动发现（手机 ↔ 手机）
- 期望：两台手机都开着捎话时，几秒内各自出现在对方的「局域网发现」里。
- 已知未验：手机端**只做发送侧广播**，不声明 `CHANGE_WIFI_MULTICAST_STATE`。
  部分机型在 Wi-Fi 省电模式下会过滤广播帧 ⇒ 本项请**特意锁屏放 2 分钟再打开**复验一次，
  若确实收不到，再回来讨论要不要加 MulticastLock。

### A4 手动节点
- 步骤：「节点 → 填对方地址」，输入 `ip` 或 `ip:port`（端口默认 17878）。
- 期望：保存后出现在「已保存的节点（管理）」里，在线/离线状态会自己变；
  同一台机器不会**同时**出现在「局域网发现」和「已保存」两组里。

### A5 长文本走 TCP
- 步骤：发一条 **>800 字节**的文本（例如 1000 个汉字）。
- 期望：对端完整收到，本端状态从「等待中」变「已送达」。
- 反例：卡在「等待中」甚至入队 —— 说明 TCP 兜底没生效（UDP 单包 800 字节预算）。
- **群文本另有一条预算**（2026-10-19 真机）：单聊 TCP 上限 4096 字节，**群文本放宽到 32 KiB**
  （`Limits.GROUP_TEXT_TCP_LIMIT`）。所以「>4096 字节被拒」只对单聊成立；群里的长回复
  （agent 答的，真机上 5.4 KB 级别）必须完整到达。判读点：收端库里那条的
  **UTF-8 字节数**要等于发端发出的，不是「看起来差不多」。

### A6 离线队列补发
- 步骤：把对端手机切飞行模式 → 本端发 3 条 → 对端恢复网络。
- 期望：恢复后自动补发，顺序不乱、不重复；对端在线时本端顶部不显示「离线」横幅。
- **反方向也要跑**（2026-10-19 真机，红米）：**手机自己在断网时发** —— 开飞行模式，在群里发一条，
  再关飞行模式。期望：本地那条立刻出现（id 形如 `group-local-<seq>-<ts>-QUEUED-<n>`），
  恢复网络后 40 秒内对端库里出现同一条（`conv_id` 是群、`sender_id` 是手机 nodeId）。
  判读点：**用户不需要重发**。

### A7 收到的文件能打开、能分享 ✅ 已跑（2026-10-07）
- 步骤：对端发一张图片和一个文件 → 在本端文件卡上点「打开」「分享」。
- 期望：系统用能读懂它的应用打开；分享面板能把它交给别的应用。
- 机制：落盘在 `filesDir/relay/downloads`（应用内部目录，文件管理器看不见），
  出口是 `FileProvider`（`${applicationId}.fileprovider`，只签发 `relay/downloads`）。
- 反例：`FileUriExposedException`（Android 7 起禁止直接递 `file://`），或按钮点了没反应。
- 证据（2026-10-07，Pixel 6）：`design/runtime-evidence/e2e-20-android-open-share.txt`。
  对端是本仓 agentNode（用本批新增的 `POST /v1/send-file` 发报价），发的是一张 2 KB 的 PNG。
  「打开」落到 `com.example.photoapp/.pager.HostPhotoPagerActivity`，
  「分享」落到 `com.android.intentresolver/.ChooserActivityLauncher`（`ACTION_CHOOSER`），
  落盘文件与原文件 md5 相同；logcat 里没有 `FileUriExposed` / `ActivityNotFound`。
  **只覆盖私聊、单文件、2 KB**：打开失败退到分享那条分支、`file_paths.xml` 之外的路径、
  大文件续传都还没跑（群图 10MB 已另立一条，见 A13b）。
- 注意：iOS 侧此前**没有**这个出口（`receivedFileActions` 为 null，界面据此不画按钮）——
  2026-10-07 已补上并在模拟器验过，见 I5 与 `design/runtime-evidence/ios-i5-file-exit.txt`。

### A7b 图片与语音的内联预览 ✅ 已跑（2026-10-07）
- 步骤：对端发一张图（`purpose:"image"`）和一段语音（`purpose:"share"` + `.m4a`）→
  看图有没有缩略图、语音有没有「播放」按钮 → 点「播放」。
- 期望：缩略图与源图一致；「播放」真的出声；放不了的格式显示一行「这台设备放不了这个格式」
  而不是什么都不发生；解码失败安静退回普通文件卡，**不崩、不弹错误**。
- 机制：`composeApp/src/commonMain/kotlin/app/relay/app/MediaKind.kt` 判媒体类型
  （`purpose` 优先，协议里没有的值当作没说，再按扩展名），
  `platform/ImageDecoding.kt` 三平台解码，`platform/AudioPlayback.kt` 三平台播放。
- 反例：把 `purpose:"share"` 的 `.m4a` 判成普通文件（**第一版就是这样，真机才发现**）、
  大图直接整张解码吃爆内存、点「播放」没有任何反应。
- 证据（2026-10-07，Pixel 6）：`design/runtime-evidence/media-inline-preview.txt`。
  图片自动接收后画出缩略图（与源逐字一致）；语音点「接收」后出现「播放」，
  点击后 logcat `AudioTrack: stop(211): called with 22050 frames delivered`
  = 正好 1 秒 × 22050 Hz，整段播完。
- 注意：发端**自己**发出去的图也画缩略图（`FileCardUi.localPaths`），
  但本机唯一能当收端的 agentNode 从不接受入站报价，所以这条只有单测覆盖；
  桌面侧只验到编译与截图。

### A8 撤回
- 步骤：自己发一条文本 → **长按**它 → 选「撤回」。
- 期望：5 分钟内可撤回；撤回后本端显示「你撤回了一条消息」，对端显示「对方撤回了一条消息」。
- 超窗：等 6 分钟再长按，应**没有**撤回入口（而不是点了没反应）。
- 媒体：发一张图片后撤回，双方都声明 `mrec1` 时对端应停下进行中的拉取并清掉 `.part`；
  若对端已经收完并存盘，**已保存的文件不会被删**（协议只要求隐藏消息）。

### A9 戳一碰限流
- 步骤：连点对方头部的手指图标。
- 期望：**60 秒内最多 2 次、相邻至少 15 秒**；超限时本端提示被限流，对端不重复震动。
- 对端侧：收到时应有明显的视觉提示，且**不改变会话未读数**。
- 边界：只有单聊有；Agent 会话头部**不画**这个按钮。

### A10 免打扰
- 步骤：把某个会话设为免打扰，再让对端戳一下/发消息。
- 期望：**不唤起、不置前、不震动**（通知静默时段 22:00–08:00 同理）。

### A11 键盘与 insets
- 步骤：进聊天页，点输入框弹出键盘。
- 期望：内容随键盘上推，**导航栏与键盘之间没有空白带**，顶栏不被推出屏幕。
- 机制：`windowSoftInputMode="adjustResize"`，inset 只在 `App.kt` 根消费一次
  （屏内一律不再 `imePadding()`）。这条曾在真机上出过
  「约 195dp 空白 + 顶栏被推出屏幕」。

### A12 杀进程后的离线判定
- 步骤：直接杀掉手机端进程，看对端多久把它标成离线。
- 期望：约 **90 秒**（`Timings.offlineAfter` 存在性超时）内变离线。
- 说明：正常退出会尽力广播 `exit` 告别帧；但**手机端没有生命周期钩子**，
  所以手机侧这条告别帧实际发不出去 —— 90 秒超时才是手机的真实路径。**这是已知现状，不是缺陷**。

### A13 表格图/头像等「收到但不处理」的帧（2026-10-18 复核：标题里的「头像」已过时）
- 期望：对端发这些时本端**不崩、不回错误、不显示半截内容**（按 README 的「不做的理由」处理）。
- 详见 [`PROTOCOL-COVERAGE.md`](PROTOCOL-COVERAGE.md) 第一节（会处理的）与第三节（有意不处理的），
  以及 [README 的能力声明一节](README.md#能力声明caps声明了什么没声明什么)。
- 表格图**例外**：对端发带 `tableText` 的图片 offer 时，本端应当能展开看那段文字（见 C5）。
- **投屏已不再是「不处理」**：`screen` 已完整实现并双向真机验收通过（见 A17）。
- **头像也已不再是「不处理」**（2026-10-18 复核）：`avatar` 的 `op:get` / `op:data` / `op:miss` 已在
  `RelayControllerAvatarComponent` 闭环（含 SHA-256 缓存），能力位 `av1` 随「自定义头像」开关声明。
- **今天真正「有意不处理」的只有两种**：`update`（局域网 P2P 更新源）与 `peers`（gossip 摘要）——
  见 `MsgTypes.UNHANDLED`。另有一种特殊形态：`share` 只在**传输层**被 ACK，业务层至今只有壳。

### A13b 群图片的 10MB 上限 ✅ 已跑（2026-10-07）
- 步骤：桌面端在一个群里发一张 15MB 的图片（群图协议上限 10MB）。
- 期望：本端**不自动接收**，卡片停在「接收 / 拒绝」等用户点；同一张图在**单聊**里 ≤20MB 会自动接收。
- 依据：`IMG_AUTO_ACCEPT` 20MB（决议 #2）、`GROUP_IMG_AUTO_ACCEPT` 10MB（决议 #33）；
  超限群图协议写的是「拒绝」，本端退化成手动领取（理由见 `PROTOCOL-COVERAGE.md` 第四节）。
- 已覆盖的部分：`TransferAutoAcceptTest` 钉住两条上限与边界值。
- 证据（2026-10-07，Pixel 6）：`design/runtime-evidence/a13b-android-group-image-cap.txt`。
  同一个 15728640 字节的文件做了三次对照：不带 `groupId` → **自动收下**（全程没点屏幕）；
  带 `groupId`/`groupRev` → **停在「对方发来的文件，等你接收」**；点「拒绝」→ 不落盘。
  两条接收路径的落盘文件 md5 都与源文件一致（`35d3d682e406d6a1a90a841206c7b89a`）。
- 这一项**以前造不出场景**：收端一直按 `groupId` 判群聊，但发端 `TransferManager.offerFiles`
  恒发 null，本客户端根本发不出一条协议认可的群图。本批把 `groupId`/`groupRev` 接到
  `offerFiles` → `NodeRuntime` → `POST /v1/send-file`（成对校验，只给一个就 400）。
- **已更正（2026-10-18 复核）：界面上的群图/群文件发送已经打通** —— 附件按钮对群会话照画
  （`ChatScreen` 的 `onAttachFile` 在移动端与桌面端都是无条件传入），`handlePickAndSendFiles`
  有 `groupId` 分支：先校验群存在与「自己是成员」，再逐成员扇出。原文写的「聊天页对群会话
  刻意不画附件按钮」已经不成立。
- 仍未覆盖：`purpose` 为 sticker / update 时的内联上限。

### A13c 对端改了资料/头像名，本端多久跟上 ✅ 已跑（2026-10-07）
- 步骤：对端改自己的显示名（触发 `profileRev` +1），**不发任何消息**，只等心跳。
- 期望：本端在几十秒内（presence 周期 + 节流 10 秒）把节点表里的名字换过来，
  不需要重启、不需要重新握手。
- 依据：协议 §3 presence 带 `profileRev`，失配则单播 `entry` 换回全量资料（决议 #317，需求 F-DISC-7）。
- 已覆盖的部分：`DiscoveryProfileRefreshTest` 用两个真 UDP socket 跑过这条往返。
- 证据（2026-10-07，Pixel 6）：`design/runtime-evidence/profile-refresh-android-restart.txt`。
  真机上这一项**原本永远跑不通**，本轮抓到两个缺陷并修掉：① `handlePresence` 的旧判据把
  「本地根本没有对端资料」也当成"版本一致"，于是从不多问一句；② `UdpTransport` 没开
  `SO_BROADCAST`，局域网广播**静默地一条都发不出去**（`Discovery.sendRaw` 用 `runCatching` 吞掉）。
  修后实测：冷启动 6 秒时行标题是 `aeb471f0`（nodeId 前 8 位），46 秒后变成 `证据节点`。

### A14 我的二维码（生成侧）✅ 已跑（2026-10-07）
- 步骤：「节点」页 → 「本机」卡下方的「我的二维码」。
- 期望：一张白底黑码的二维码，下面印着它的**原文**（`relay://<本机地址>:<端口>`），
  再下面一句话说明对方在「节点 → 扫码配对」里扫它。拿任意手机的相机 App 扫这张码，
  应当识别出同一串 `relay://…`。
- 边界：
  - 还没拿到本机地址（节点启动中、没有局域网口）时，卡片如实写「正在监听端口…」之类，
    **不画一张空码**；地址长到编不进版本 10（213 字节）时如实说编不出来，**不截断内容**。
  - 深色主题下这张码仍然是白底黑码（主题色的浅色码不少相机扫不出来）。
- 主机侧的替代证据（不需要相机，本机已验证过）：
  ```bash
  python3 tools/verify-rendered-qr.py \
    androidApp/src/screenshotTestDebug/reference/app/relay/app/screenshot/ScreenshotPreviewsKt/NodesPhonePreview_nodes-phone_861b9e7f_0.png \
    'relay://192.168.1.57:17878'
  ```
  它把截图里的二维码还原成矩阵，与 `qrencode` 对同一载荷的输出逐模块比对
  （脚本自身也验过两个负控：换一个端口的载荷会报 188 个模块不同；没有码的截图会报找不到）。
  这只证明「像素里那张码是对的」，**不能替代拿手机扫**。
- 真机证据（2026-10-07，Pixel 6）：节点页「我的二维码」卡画出 25 模块的码，
  content-desc 是「配对二维码，让对方用「节点 → 扫码配对」扫它」，下面印
  `relay://192.0.2.22:17878` 与说明句 —— 见
  `design/runtime-evidence/scan-a15-android-permission.txt`。

### A15 扫码配对（1、3、4 与拒绝授权已跑；**第 2 步真机对扫还没跑过**）
- 步骤：设备 A 打开「节点」页停在我的二维码上（A14）；设备 B：
  「节点 → 添加节点 → 扫码配对 → 打开相机扫码」，把 A 的码放进取景框。
- 期望：
  1. B 第一次点「打开相机扫码」时弹系统相机授权框（Android 是「允许捎话拍照」，
     iOS 是「捎话要用相机扫对方屏幕上的配对二维码…」）；
  2. 取景框里能看到**真实相机画面**，把 A 的码对准后约 1 秒内自动返回节点页；
  3. B 的节点表里出现 A（地址 = A 屏上那串 `relay://` 里的 host:port），随后能收发消息。
  4. 在取景框上按**系统返回键**（或点左上角 ×）→ 回到节点页，**不是**退出应用。
- 反例（每一种都要能在界面上看到原因，而不是"点了没反应"）：
  - 拒绝授权 → 扫码页横幅「相机权限被拒绝，请在系统设置里开启后重试。」+「再试一次」；
  - 相机被别的应用占用 / 模拟器没有相机 → 横幅「这台设备现在给不出相机画面（模拟器没有
    相机，或相机正被别的应用占用）。也可以回节点页用「填地址」把对方加进来。」；
  - 扫到别的二维码（网址、支付码）→ 横幅「这不是捎话的配对码」；
  - 一直扫不到 → 「再试一次」重绑相机；「扫不到？回节点页填地址」是**同等地位**的出路。
- 已经跑过的（2026-10-07，Pixel 6，见 `design/runtime-evidence/scan-a15-android-permission.txt`）：
  第 1 步的授权框（首次点「打开相机扫码」时 `granted=false` → 弹系统框 → 点允许后 `granted=true`）、
  第 4 步的返回键（回到节点页，同一 Activity 仍 Resumed、进程还在，**没有退出应用**），
  以及拒绝授权后的横幅「相机权限被拒绝，请在系统设置里开启后重试。」+「再试一次」。
  点允许后系统状态栏出现**相机隐私指示**，说明相机确实被打开了。
- **第 3 步已经用另一条路跑完了（2026-10-07）**：扫码页多了一个「从图片导入二维码」，
  从一张**已有的图片**里读码，不需要相机、也不需要第二块屏幕。Android（Pixel 6）与
  iOS（iPhone 17 Pro 模拟器）都验过：删空「已保存的节点」→ 导入一张编着
  `relay://192.0.2.82:17878` 的图 → 横幅「已向 192.0.2.82:17878 打招呼，等它回应。」，
  落盘设置里多出那一条；两张反例图分别给出「这个二维码不是捎话的配对码。」与
  「这张图里没找到二维码。换一张对着码拍的照片，或者直接用相机扫。」。全程命令与原始输出见
  [`design/runtime-evidence/scan-import-image.txt`](design/runtime-evidence/scan-import-image.txt)。
- **还没跑过的**：第 2 步「取景框里能看到真实相机画面」。截下来的取景区是**全黑**的 ——
  分不清「摄像头朝着暗处」与「预览没挂上」，只能把手机对准一块亮着的屏幕来判断。
  这一条机器代替不了，所以本项**不算走完**。
- 主机侧已有的证据：编解码往返（`QrPairingTest` 的 `whatWePrintIsWhatWeParse`）、
  像素级验证（A14）、扫码页三种状态与权限被拒横幅的参考图
  （`scan-waiting` / `scan-failed` / `scan-no-preview`）、
  导入这条路的 15 条单测（`RelayControllerScanQrTest`，含「一碰相机权限就红」那条）。

### A16 群聊（建群、群元数据、双端发群消息）✅ 已跑（2026-10-07）
- 装置：Pixel 6（`SERIAL-TEST-01`）建群、iPhone 17 Pro 模拟器（`00000000-…`）当成员，
  Mac 上的桌面端与 agentNode 作为第三方在场节点（免得「群里只有我们俩」掩盖归属判断）。
- 步骤与判读：
  1. Android 消息页「群聊」→「建群」→ 群名 + 勾选成员 → 创建；横幅「…正在通知 1 位成员。」
  2. 从**设备里读回**两端设置：`settings.json` / `relay.settings.json` 的 `groups` 逐字段相同
     （同 `groupId` / `name` / `members` / `rev` / `updatedBy` / `ownerId` / `adminIds`）。
     时序要记一笔：iOS app 被杀掉时 `groups` 还是 `null`，起来并收到一条群文本后才出现 ——
     「离线成员回来即知道自己进了群」是靠群文本触发的 `group.need` → 对端 `group.info` 补齐的。
  3. 两端各发一条：收端都归到 `group:<groupId>` 会话（`messages.json` 的 `threads` 键是群，
     发言人在 `senderId` 里），气泡上别人的那条有署名、自己那条没有。
- 验收当场挖出三处**截图门禁与 900 条单测全绿时都看不见**的缺陷，已修并复验（其中会话标签条
  本身已按产品决策整体移除 —— `User said (m08784)`「去掉详情里面切换会话人的入口,没用」，
  见 `docs/plans/2026-10-09-relay-agent-session-switcher-design.md` §3.1；下列读数是当轮真机
  证据，保留）：
  会话标签条把群画成 `group:3a`（根因是拿**会话键**而不是节点 id 去查节点表 ——
  群键是 `group:<groupId>`，这条知识现记在 `app/RelayUiState.kt` 的 `convTitle` KDoc 里）；
  群里所有人都在线时仍说「有成员不在线」（拿含自己的
  `group.members.size` 去比不含自己的 `memberOnlineCount`，永远少算一个）；
  停在会话里收到消息仍累加未读（当时表现为那条标签条上挂一个不消的红点，**1:1 同样成立**，
  不是群独有）。
  未读那条是三帧对照验的：停在群里收到 → 无红点；返回列表后再收到 → 红点「1」。
- 命令与原始输出见
  [`design/runtime-evidence/group-chat-acceptance.txt`](design/runtime-evidence/group-chat-acceptance.txt)。
- **仍未覆盖**（同一份证据文件里也写了）：群成员变化（加人/移人）与随之而来的 `group.need`
  重新同步没在真机上制造过；
  `gr1` 角色矩阵整块不做；中文群名只能靠截图参考图覆盖
- **已更正（2026-10-18 复核）**：原文写「群里发文件没接（聊天页对群会话刻意不画附件按钮）」，
  现在**已经接上** —— 附件按钮对群会话照画，`handlePickAndSendFiles` 先校验群与成员身份再扇出
  （同一处更正见 A13b）。
  （`adb shell input text` 与 `idb ui text` 都注入不了中文：`input text '周末爬山'` →
  `NullPointerException at InputShellCommand.sendText`）。

### A17 屏幕协助（投屏）双向 ✅ 已跑（2026-10-09，Pixel 6 真机）
- 步骤：①手机当 Sharer —— 由一台线协议替身（`scripts/relay-screen-fixture.py viewer`）发
  `screen.request`，手机点「同意共享」并授予 MediaProjection；②手机当 Viewer ——
  聊天页点「屏幕协助」，对端桌面节点点「选择屏幕 → 整个屏幕 → 开始共享」。
- 期望：两方向都能建立连接并持续收到真实画面帧；不接受时对端能收到明确的 reject。
- 实际：方向一连续收到 **60 帧**（864x1920、7 种尺寸、JPEG magic `ffd8`）；
  方向二 0.7s 间隔连拍 **8/8 帧 md5 各不相同**（画面确实在动，不是静止图）。
- **本轮在真机上抓出并修掉三个根因**（此前门禁 204 测试全绿但功能 100% 不可用）：
  ①手机生成的 `sessionId`/`token` 不是 UUIDv4 / 32 位 hex，对端在解码阶段就丢帧；
  ②`TransferServer.serve` 的 `firstFrame` 先清后判 ⇒ `openScreen` 永不可达；
  ③ktor socket 通道一次性绑定，`ScreenSender` 不能再自己开 —— 改为由 `serve` 把已开的
  `read`/`write` 透传下去。
- 原始读数见
  [`design/runtime-evidence/screen-mirroring-android.txt`](design/runtime-evidence/screen-mirroring-android.txt)。
- **自动化只覆盖到哪一步（别把 22 条当上面那两条）**：`scripts/e2e/e2e_screen.py` 在
  `scripts/e2e/run_all.py` 里跑的是默认的 `--mode watch` —— 19 条本地自检（信封形状、TCP 帧形状、
  `screen` 在 `RELIABLE_TYPES` 里、sessionId 是严格 UUIDv4、JPEG 走查、PARTIAL 映射）+ 1 条
  `handshake-alive` + 2 条「手机声明了 `rv1`/`rvs1`」，1.8s 结束、**不建立投屏会话**。
  真正的双向验收要 `--mode sharer`（手机当 Sharer，配 `scripts/relay-screen-fixture.py viewer`）
  或 `--mode viewer`（手机当 Viewer，配一台真桌面发起）。
- **自动化真跑（2026-10-10，Pixel 6 真机，`--ui-assist`）**：两个方向都已在夹具里跑绿，
  这是 A17 的**机器读数**（不是上面 `--mode watch` 那 22 条）：
  * 方向①（手机当 Sharer，真 MediaProjection）`python3 scripts/e2e/e2e_screen.py --mode viewer
    --ui-assist --secs 150 --phone 192.0.2.22 --host-ip 192.0.2.82 --tcp-port 17920`
    ⇒ **`result PASS 24`**（`/tmp/screen-viewer4.log`）：assist 按 dump 坐标依次点
    `同意共享` (719,1336) → `共享一个应用` (540,1143) → `共享整个屏幕` (461,1332) →
    `共享屏幕` (859,1597)，随后 TCP 收到 **60 帧 / 8 种尺寸**（`frames-ok(60/10)`）。
  * 方向②（手机当 Viewer，夹具当 Sharer）`--mode sharer --ui-assist --secs 120 --tcp-port 17921`
    ⇒ **`result PASS 24`**（`/tmp/screen-sharer3.log`）：assist 点会话行 → 点「屏幕协助」→
    夹具收到 `screen.request`（`udp<- type=screen → ACK`）→ 回 `screen.accept` →
    手机 TCP 连入 `192.0.2.22:57176` → `screen-open` 的 sessionId/token 与 accept 一致 →
    泵出 **40 帧**假 JPEG（`frames-ok(40/20)`）。
  * 跑绿路上抓出的东西（**门禁全绿也照样红**）：①系统投屏授权对话框的模式是 **Spinner**，
    默认「共享一个应用」⇒ 确认键是「下一步」，切到「共享整个屏幕」才变「共享屏幕」；
    选过之后 Spinner 自己的文案也变成「共享整个屏幕」，模式标签排在确认键之前就会打转；
    ②后台的 app 被 Android 冻结 ⇒ 15s 一条 `alive` 都没有，夹具必须无条件先拉前台；
    ③sharer 的会话行要点对（列表记住滚动位置），投屏页标题行含 nick，别当会话行点掉；
    ④**产品缺陷**：TCP 回退的 `connect` 抛出的 `ConnectException` 没人接住 ⇒
    「对端 TCP 端口没开」这种最普通的情况会把整个 app 打死（`logcat -b crash` 里三次
    `FATAL EXCEPTION … SocketImpl.connect`，时间/PID `02:52:55 29113`、`02:55:50 8774`、
    `03:00:48 9244`）—— 已修并补了 `jvmTest` 回归
    （`core/src/jvmTest/kotlin/app/relay/core/transport/TcpFallbackRefusedConnectionTest.kt`），
    详见计划文档 §5.10；⑤夹具自己的收包线程曾**静默死亡**（`except OSError: return` 不打日志），
    表现为"等不到 `screen.request`"，已改成记日志并继续收。
- **仍未覆盖**：①桌面端 GUI 没有「发起投屏」入口（上游桌面端 `showHeader = false` 绕过
  `ChatHeader` 的入口，桌面宿主不挂 `RelayApp`）⇒ 方向一必须借替身，不是真桌面发的邀请；
  ②取消系统授权时对端收到的是 `capture-failed`，`permission-denied` 这条 reason 没有生产路径；
  ③iOS 真机广播链路未验（模拟器编译就绪）。

### A18 跨网无感与真名（2026-10-19 真机：红米 K80 `<device-serial>` + 桌面端）

这一组是「用户只关心哪张网都能无感连上」的正面验收。**判据只有一个**：`diag/link.json` 里
`closeCount` 全程为 0（链路一次都没被判死重拨），`linkConnected=true`、`carrier` 随网络自动切换。

| 场景 | 实测 |
|---|---|
| WiFi 冷启动 | `carrier=plaintext connected=true closeCount=0` |
| 关 WiFi（只留蜂窝） | 8 秒内 `carrier=tls`（公网 IPv6 直连），`closeCount=0` |
| 恢复 WiFi | 仍 `tls`、`connected=true`、`closeCount=0` |
| 飞行模式 开→关 | 关掉后 40s 内 `tls connected=true closeCount=0` |
| **只留蜂窝 + 杀进程 + 冷启动** | **15 秒内** `tls connected=true`，会话与真名都在 |
| 飞行模式 30/60s | `online=true`（在 90s 阈值内，符合 A12） |
| 飞行模式 **100s** | **`online=false`**（90s 超时如实生效） |

**真名**：节点页显示 `demo-mac-97` / `Pixel 6`，**不是** `node-a1b2c3d4`。
这一条要**在飞行模式里重启后再看** —— 那时对端根本不可达，名字只能来自
`stores/known-peers.json`（`PeerRegistry.restoreOffline`）。没有它，冷启动会先造一个合成名。

**群消息的发送者归属**：用户自己在群里打的话，在别人设备上要挂在**用户**名下
（载荷 `senderId` = 用户 nodeId），不能显示成 agent；agent 答的才是 `agent:<小写 kind>`。
真机反例（已修）：红米库里那条用户消息被存成 `senderId=agent:dsh`。

- 背景证据：[`design/runtime-evidence/link-idle-drop-20261019.md`](design/runtime-evidence/link-idle-drop-20261019.md)
  （`closeCount` 这条判据的来历：空闲 5 秒被读超时误判成对端关闭，每 8.1 秒断一次）。
- 库那条不变量与它的两次翻车见
  [`design/runtime-evidence/relay-db-bloat-20261019.md`](design/runtime-evidence/relay-db-bloat-20261019.md)。

### A19 什么时候会真的叫醒 Agent（2026-10-19 真机：红米 K80 `<device-serial>` + 桌面端）

判据只有一份（`RelayController.shouldTriggerAgent`），三个触发点（收方 / 本机发方 / 群发方）
都调它。

**改之前真机测到的是这一组（旧规则）** —— 正是它暴露了规则是反的：

| 步骤 | 旧规则的实测 |
|---|---|
| 红米 → 桌面端 **1:1**，正文**不带** `@dsh` | 桌面端**落库了这条 1:1**（`conv_id` = 红米的 nodeId），但**没有任何 `agent-reply-*`** —— 没触发 |
| 红米 → **工作区群**，正文**不带** `@dsh` | **1 秒后** `agent-reply-*` 落库（`sender_id=agent:native`）—— 触发了 |

> 判读时踩过一个坑：我第一条群探针自以为是 1:1，其实落进了群（帧是 `group-text`）。
> **先断言帧的 `kind` 与 `peerId`，再看有没有回复**，否则会把「群触发」读成「1:1 触发」。

**改后的规则**（本节的新判据）：

| 步骤 | 期望 |
|---|---|
| 1:1，正文不带 `@dsh` | **触发**（进这个会话就是指名 agent） |
| 工作区群，不带 `@dsh`、不是回复 agent | **不触发** |
| 工作区群，**回复 agent 那条** | **触发** |
| 任意群，正文带 `@dsh` | **触发** |

- 判读点：桌面端 `MessageEntity` 里有没有**新的** `agent-reply-*` 行（`sender_id=agent:native`），
  不要看界面。
- **⚠️ 改后的这四条还没在真机上跑过**（改后的包还没装）—— 单测已覆盖真值表
  （`AgentTriggerRuleTest`，反向验证过：换回旧规则立刻红），真机复验待装包后进行。

**为什么这次要收窄群规则**：触发后跑的是宿主本地的执行能力（默认驱动 `NativeRelayDriver`：
`bash` / 文件读写 / 代码搜索，受 ACL 约束）。改之前工作区群里**任何人的任意一句话**都会拉起它 ——
把最宽的能力面挂在人最多的频道上。

**判读点**：看桌面端有没有**新的** `agent-reply-*` 行（`MessageEntity`，`sender_id=agent:native`），
而不是看界面。真机上这条差点被读反：一条群探针我以为是 1:1，其实落进了群（帧是 `group-text`）——
所以先断言**帧的 kind 与 peerId**，再看有没有回复。

### A20 `gta1` 门控与「按原 id 重发」（2026-10-20 新增，待装包真机跑）

这两条守的是同一类缺陷：**发送侧一切正常、接收侧什么都没有 / 多了一条**。

| 步骤 | 期望（判据） |
|---|---|
| 群里发一条带 `senderId` 的 agent 转发消息，群内有**上游茶话间 0.61.2**（或老 Tidings，caps 无 `gta1`） | 它**能显示这条**，且收到的帧只含 `kind/text/groupId/groupRev/mentions/resend/replyTo`；`id` 与声明了 `gta1` 的成员收到的**完全相同**（`ts` 也相同） |
| 同一条失败消息连点「重发」5 次 | 对端 `MessageEntity` 只多**一条**，且 `id` 等于本地 `MessageUi.id`；不是每次一个新 id |
| 关 WiFi、只留蜂窝后点重发 | 15 秒内该对端在 `files/relay/stores/queue.log` 的行数**开始下降**；不降则 `adb logcat` 过滤 `ENETUNREACH` —— 出现即内核没路由（需要 UDP/TCP 绑网，见 `ARCHITECTURE.md`） |
| 全程 | `files/relay/diag/link.json` 的 `closeCount` 保持 **0**（换网/断网/冷启动） |

> 判读「对方离线」时先看 `lastSeen`：`online=false` 但距上次入站 **< 90 秒** ⇒ 是 `markOffline`（某次发送失败）标的，不是心跳超时（`Timings.offlineAfter=90s`），别等 10 秒就判缺陷。

### A21 加密数据面：端口退役、两条数据面与配对准入（待装包真机跑）

每条判据都取自**端外可见的事实**（谁连得上、库里的字节、对端看到什么），不看界面文案。

| 步骤 | 期望（判据） |
|---|---|
| 两端都已配对、同一个 Wi-Fi | 从桌面探测手机：`nc -z <手机IP> 17878` **通**（加密信道的监听口，与发现端口同号不同协议）；`17879`、`17881`、`17882` 三个都 **connection refused**。被测的桌面端如果也接了数据面，本机 `lsof -nP -iTCP:17879 -iTCP:17881 -iTCP:17882` 应当一条都没有 |
| 设置里关掉加密数据面 → **重启节点** | 同一组探测反过来：`17879` 重新可连；一台**未升级**的旧客户端能在节点列表里重新出现（自动发现回来）。再打开 → 重启，它又消失 |
| 双向发一个 ≥50MB 文件（两端都支持新数据面） | 两端落盘文件的 `sha256` 一致；传输过程中对端日志里**没有** `pull` 请求（不再拨 17879/17881 来拉） |
| 对端**没有** `fch1`（旧客户端或夹具） | 文件仍能收，走 legacy 拨号拉取；**不能**出现「发送侧推、接收侧一直等」—— 判据是接收端最终落盘且 `sha256` 一致 |
| 扫码配对一台新设备 | 主机日志出现一行 `paired`（带对端指纹前缀）；用**错误的配对密钥**再试一次 ⇒ 被拒并留下 `bad_secret`，且该设备**不会**进入信任台账 |
| 陌生连接不配对 | 只建连、不发配对帧的陌生会话在 **30 秒**窗口内被断开；窗口内它发任何业务帧都**不进**应用层（收端库与会话列表都不动） |

- **判「老设备消失」要读对**：数据面开着时，未升级的旧客户端是**发现不到**（节点列表里没有它 / 一直显示离线），**不是**「发出去了对方没收到」。区分方法：本端节点表里有没有它 —— 没有节点就没有「发送」这件事。
- 回退只有一条路：设置里关掉开关并**重启节点**。不是改端口、也不是加白名单。
- 证据落到 `design/runtime-evidence/`（文字），截图不入库。

## 3. iOS 真机

**本轮（2026-10-07）在 iPhone 17 Pro 模拟器（iOS 26.2，`00000000-FEF7-409D-AE33-803A77C62376`）
上把 I1–I6 逐条跑过一遍**，全程命令、原始输出与判读写在
[`design/runtime-evidence/ios-acceptance-i1-i6.txt`](design/runtime-evidence/ios-acceptance-i1-i6.txt)。
跑的过程里查出并修掉两个真缺陷（I3 的 Bonjour 广播、I6 的前台通知），
另有三条如实记录「本轮没验到 / 验不了」。

### I1 构建与首启 ✅ 通过
- 期望：装到真机后能起来。
- 机制：`Info.plist` 的 `CADisableMinimumFrameDurationOnPhone` 必须是 `true`，
  否则 **DEBUG 构建在启动时直接抛异常崩溃**，症状是「点了图标什么都没发生」
  （2026-10-04 在 iPhone 17 Pro 模拟器上实测）。
- 本轮：全新安装 + 启动，进程起来，截图显示「消息」页空态（「还没有会话 / 去节点页」）
  与底部四个标签，没有崩溃；`plutil -p` 确认该键为 `true`。

### I2 本地网络授权弹窗 ⚠️ 模拟器不强制，只能记现状
- 步骤：首次进「节点」页。
- 期望：弹「捎话需要在同一个 Wi-Fi 下找到其他设备…」。
- 反例：**连弹窗都不出现、自动发现永远搜不到任何东西** ——
  说明 `NSLocalNetworkUsageDescription` / `NSBonjourServices` 缺失或被改坏。
- 本轮：模拟器**不强制**本地网络隐私（Bonjour 注册直接成功），所以弹窗复现不了；
  两个键都在（`plutil -p` 确认），且 I3 的广播真的发出去了 —— 反例不成立。
  真机上的弹窗要等有真机才能判。

### I3 Bonjour 发现 ✅ 广播与浏览两半都通过
- 期望：与 A3 相同；`NSBonjourServices` 里逐个列出的是 `_relay._udp`（不能用通配符）。
- **查出并修掉一个真缺陷：iOS 的 Bonjour 广播从来没成功过。**
  修前节点是 `Running`、UDP/TCP 都绑上了，但主机 `dns-sd -B _relay._udp local.`
  只有安卓的 `我的手机-bc1988`，本机服务一条都没有 —— 别人永远发现不了这台 iOS。
  根因：`NSNetService` 的注册挂在**调用线程的 run loop** 上，而 Kotlin/Native 协程跑在
  Darwin 全局队列的工作线程（没有 run loop 在转），注册请求排进去没人推 →
  5 秒超时 → 服务被撤下。修法：`IosMdnsAdvertiser.kt` / `IosMdnsBrowser.kt` 里把
  「建服务/浏览器 + 设 delegate + publish/search + stop」整段搬进
  `dispatch_async(dispatch_get_main_queue())`。
- 修后：`dns-sd -B` 出现 `iOS验收-27272e`，`dns-sd -L` 解析到
  `demo-mac-97.local.:17888`。
- 浏览那半（iOS 主动发现别人）也验了：把 `manualPeers` 清成 `[]`（强制只能靠 Bonjour 发现），
  在主机上注册一个**只有 Bonjour 广播、零 UDP presence** 的假服务
  `dns-sd -R "fake-bonjour-only" _relay._udp local. 17999`，几秒后 iOS 节点页的
  「局域网发现」里出现 `fake-bonjour-only` / `demo-mac-97.local.:17999`，带可点的「连接」。
  对照组：主机在 `0.0.0.0:17999` 上跑 5 秒 UDP 监听收到 `TOTAL_UDP_PACKETS_ON_17999=0` ——
  它一个 UDP 包都没发，iOS 却列出了它，所以只能是被 mDNS 发现的。
  代码侧佐证：`RelayController.kt:507` 的 `onServiceFound` 只做自过滤（`:511`），
  不校验 UDP 存活，所以假服务能进列表即证明浏览半本身在工作。
- 仍未覆盖：在发现列表里点「连接」真的完成一次握手这条没验。

### I4 文本与文件 ✅ 两半都通过（文件半是补跑）
- 期望：与 A3/A5/A6 相同；注意 iOS 上收到的文件落在
  `Application Support/relay/downloads`。
- 本轮：文本 ✅ —— 从证据节点发一条，iOS 会话列表出现「证据节点」+ Agent 徽标 + 未读 1。
- 文件 ✅（补跑）—— `POST /v1/send-file` 返回 `offered`（只是报价）；**收到的文件不自动接受**，
  要用户点「接受」才开始传（设计如此，不是缺陷）。装上 `idb` 后用
  `idb ui tap` 点了「接受」：界面从「等你接收」→「已接收」→「已存到 …」，
  容器里确实出现 `Library/Application Support/relay/downloads/ios-i4-file.txt/ios-i4-file.txt`，
  67 字节，sha256 `d5ba271c…7aa9`，**与源文件逐字节相同**。
  所以「落在 `downloads`」这句现在有证据了。
- 多出来的同名一层（`downloads/<文件名>/<文件名>`）**是设计，不是 iOS 缺陷**：每笔传输先按传输名
  建目录，再按相对路径放文件（`TransferManager.kt:694-698` `saveDir = pathJoin(root, safeRoot)`、
  `:739` `saved += pathJoin(saveDir, it.relPath)`）；安卓证据里同样是这个嵌套
  （`c5-android-table-text.txt:42`、`e2e-20-android-open-share.txt:36`）。
- 顺带确认：`messages.json` 有内容 ⇒ iOS 的「消息落盘」开关是真的生效的。

### I5 收到的文件有没有出口 —— **✅ 两个按钮都通过**（出口是本轮补的）
- 原状：`receivedFileActions` 在 iOS 为 `null`，界面据此不画「打开/分享」按钮 ——
  文件确实落盘（I4 文件半已验证 sha256 与源文件一致），但用户拿不走，当时结论是「收得到，取不走」。
- 补法：新增 `composeApp/src/iosMain/kotlin/app/relay/platform/IosReceivedFileActions.kt`
  （「打开」= `UIDocumentInteractionController.presentPreview` 即 Quick Look；
  「分享」= `UIActivityViewController` 即系统分享面板；两者都 `dispatch_async` 到主队列，
  失败只 `NSLog` 不抛 —— 与 Android 的 `ACTION_VIEW`/`ACTION_SEND` 同一组语义），
  并在 `IosPlatform.kt` 覆写 `receivedFileActions`。
- 证据（2026-10-07，iPhone 17 Pro 模拟器）：`design/runtime-evidence/ios-i5-file-exit.txt`。
  从证据节点 `POST /v1/send-file` 发一张 5104 字节的 PNG → 点「接收」→ 卡片上出现
  `Button '打开' {{32,720},{58,48}}` 与 `Button '分享' {{102,720},{58,48}}`；
  点「分享」弹出系统面板（头部就是这个文件：`ios-i5-file` / `PNG 图像 · 5 KB`）；
  点「打开」弹出 Quick Look，整屏就是那张图，白字「I5 exit」与源文件内容逐字一致；
  落盘副本 sha256 `7e58f635…f7f4` 与源相同，两个动作之后进程仍在（契约「不许抛异常」成立）。
- 仍未覆盖：iPad 的 popover 分支（代码已设 `sourceView`/`sourceRect`，本机只有 iPhone 模拟器）、
  「打开」失败退到分享那条路、桌面侧同一能力（只有单测，没在有桌面的机器上手工点过）、
  系统「文件」App 里仍看不见（要开 `UIFileSharingEnabled` 并把落盘根换到 `Documents/`，
  会动到 I4 已验过的路径，是另一个决定）。

### I6 通知 ✅ 前台横幅与点击定位通过；后台不可达如实记录
- 期望：授权后收到消息弹通知；未授权时不崩。
- **未授权不崩 ✅**：权限没授予时 app 照常跑、消息照收。
- **前台横幅：查出并修掉一个真缺陷。** 修前 app 调了 `showNotification`、
  `addNotificationRequest` 以 nil error 返回，但 `usernotificationsd` 一条记录都没有、
  `DeliveredNotifications.plist` 也没新条目 —— 前台投递被 iOS 静默丢掉。
  根因：**没有装 `UNUserNotificationCenterDelegate`**。
  修法：加 `IosNotificationDelegate`（`willPresent` 返回 banner/list/sound，
  `didReceive` 处理点击）并在 init 里装到
  `UNUserNotificationCenter.currentNotificationCenter().delegate`。
  注意 delegate **必须存成字段**：那个属性是弱引用，只设不存会立刻被回收。
  修后前台横幅正常出现。
- **点通知定位到会话 ✅**：修前 `focusPeerId` 在 iOS 完全空转（点一下只把 app 打开）。
  现在 `showNotification` 把 peer id 写进通知的 `userInfo`，`didReceive` 读回来交给
  `setOnNotificationTap`，最终落到与安卓同一条终点 `RelayController.requestFocus`。
  实测：点横幅后 app 直接跳进「证据节点」会话。
- **后台收不到 ⚠️ 与安卓 C8 同一类，没有改设计。** HOME 切后台 3 秒后再发，
  证据节点收不到 ACK（app 已被挂起，收不到局域网消息）；HOME 后立刻发能收到
  （还在后台宽限窗口里）。所以「后台收到消息 → 弹通知」在现有设计下不可能成立，
  需要 UIBackgroundModes/APNs 这类大改。**如实记录，没有假装通过。**
- 仍未覆盖：冷启动路径（app 被杀掉后点通知）试过但没验成 —— **卡在工具，不是应用**。
  在前台留一条横幅 → `simctl terminate` 杀掉 → 拉下通知中心（截图确认横幅在）→
  `idb ui tap`。a11y 树里横幅确实是可点 `Button`（中心 ≈ (201,485)），但点三次都没反应，
  进程起不来、画面仍停在通知中心：`idb` 的 HID 事件到不了 SpringBoard 的锁屏/通知中心
  那一层（`osascript` 被 TCC 拒、`simctl` 没有点通知的 API）。静态看通路是通的（delegate
  在场景建立期就装上了），但没有可驱动的点法能证明，故如实记为未验。

### I7 图片与语音的内联预览 ✅ 通过（2026-10-07，iPhone 17 Pro 模拟器）
- 期望与机制同 A7b；这条只记 iOS 这一侧。
- 图片：证据节点 `POST /v1/send-file {"purpose":"image"}` 发一张 19743 字节的 PNG →
  自动接收 → 卡片上画出缩略图（绿渐变 + 白圆 + `RELAY 06`，与源逐字一致）+
  「已接收」「已存到 …/downloads/a6-photo.png/a6-photo.png」+「打开」「分享」。
  容器里落盘副本 sha256 `28b46e64…c991` 与源**逐字节相同**。
- 语音：同一个控制面发 `{"purpose":"share"}` + `.m4a`（6408 字节）→ 点「接收」后出现
  `Button '播放' {{32,667.33},{58,48}}` → 点它**没有**出现「这台设备放不了这个格式」
  （该文案只在 `platformPlayAudioFile` 返回 false 时画，所以这条界面事实就是
  `AVAudioPlayer` 构造成功且 `play()` 返回 true）。落盘副本 sha256 `71b3e5bb…386d` 与源相同。
- 过程里踩到的坑：iOS 侧一开始所有文件传输都报「传输失败：closed」，
  根因**不在 iOS**，而是常驻证据节点跑着旧类路径
  （`NoClassDefFoundError: app/relay/core/protocol/ErrFrame` at `TransferServer.kt:287`）——
  收端只看到 `TransferClient.kt:441` 那一句。重建并重启证据节点后即正常。
- 证据：`design/runtime-evidence/media-inline-preview.txt`。


## 3b. core 侧：重启后的入站去重与队列剪枝 ✅ 已跑（2026-10-07）

这一条不属于任何一台设备：它是 `NodeRuntime` / `Messenger` / `DedupStore` 这一层的
行为，三端共用。装置用的是**本仓库构建出来的 agentNode 真进程**（JVM 宿主），
不是手机 —— 完整读数见 `design/runtime-evidence/dedup-persist-jvm-e2e.txt`。

- 期望：对端重放同一帧（同一个 `msg_id` + `peer_id`），无论中间有没有重启过本进程，
  都只能被投递一次；同时落盘的记录不能只长不缩。
- 怎么做的：直接往节点 UDP 口发手写的 `msg` 信封（不走控制面 —— `/v1/send` 每次都会
  生成新 id，证明不了「重放同一帧」），然后用 `GET /v1/inbox` 数消息条数。
- 实际看到什么：
  - 首次发 X → 1 条；同一进程内再发 X → 仍是 1 条（内存去重，本来就有）；
  - **重启后**先读一次 → 0 条（消息表是内存的，这是装置前提）；
  - **重启后重放 X → 仍是 0 条**（被落盘的去重日志挡住）；紧接着发一个新 id Y → 1 条
    （对照：投递通路本身是通的，0 不是「什么都收不到」）；
  - **反例对照**：同一份二进制、同一帧、同一端口，只把 `dedup.log` 删掉再启动 →
    重放 X 被当成新消息收下（1 条）。有日志 0、没日志 1，所以挡住它的只能是那份日志。
  - 剪枝：手工种一份日志（25 小时前一行 + 60 秒前一行），启动节点后 2 秒内过期那行
    从盘上消失、没到期那行还在 —— 不是整份清空。
- 结论：**通过**（上面五条都真跑出来了）。这条 TODO 的两半（落盘、剪枝）都有了真进程证据。
- 仍未覆盖：手机端（`RelayController`）那条周期循环没在真机上跑过 —— 形状与 agentNode
  相同、同一份源码，但「照抄的形状」不是证据，真机要等满 10 分钟那一次才看得见；
  「队列项被剪掉 → 消息卡变红『发送失败』」只有单测（`MessageUi.afterPruned`），
  没有在界面上眼见为实（要真看到得让一条消息排满 7 天，或每对端超过 200 条）。

## 4. 手机 ↔ 上游基准对端 0.61.1

### C1 握手
- 期望：两端互相出现在对方的节点列表里，昵称/平台/能力标签正确。
- 注意：协议里的平台值只有 `win/mac/linux`，所以**手机在协议上伪装成 `linux`**（如实记录即可）。

### C2 文本、长文本、撤回、戳一碰
- 期望：与 A5/A8/A9 相同，双向各来一次。撤回要求双方都声明 `mrec1`（本端声明了 `tw1`/`mrec1`）。

### C3 文件双向
- 手机 → 桌面：桌面应能收到并保存。
- 桌面 → 手机：本端落盘 + 「打开/分享」出口（A7）。

### C4 桌面退出后的告别帧
- 步骤：桌面正常退出（菜单退出/关窗），立刻看手机端。
- 期望：手机端**立即**把桌面标成离线，不用等 90 秒（`exit` 广播生效）。
- 反例：仍要等约 90 秒 ⇒ 告别帧没发出去。

### C5 群消息、表格图、自定义头像、投屏、update/share、peers/scan-ranges
- 期望：**收到不崩、不谎报**。本端对这些的处理与「不做的理由」逐条写在
  [`PROTOCOL-COVERAGE.md`](PROTOCOL-COVERAGE.md)（权威）与
  [README](README.md#类型的实际支持范围)，请照那两份核对：
  群文本能收能发、`group` 元数据已处理；`gr1`/`img1`/`av1`/`shr1`/`upd1`/`dp1` 一律不声明；
  `update` / `share` / `peers` / `scan-ranges` **有意不做**（有显式分支丢弃）。
  键名以标准实现为准（文档里的 `grp1` 在实现里叫 `gr1`；文档里的 `img1` 在实现里不存在）。
- **投屏（`rv1`/`rvs1`）现在都声明、也都做了 ✅ 已跑（2026-10-09，Pixel 6 真机）**：
  实测手机广播 `["tw1","mrec1","agn1","tbl1","rv1","rvs1","shr1"]` —— `rv1` 与 `rvs1` 都在。
  `screen` 不再是「只回 ACK」，双向验收见 A17，原始读数见
  [`design/runtime-evidence/screen-mirroring-android.txt`](design/runtime-evidence/screen-mirroring-android.txt)。
- **表格图（`tbl1`）✅ 已跑（2026-10-07，Pixel 6 真机）**：桌面端发一张带表格的图片，
  本端气泡里出现「看表格文字」按钮，点开后是等宽对齐的 TSV 文字、横向可滚、不折行；
  发送端截断过（>4096B）时下面有一行说明；不带 `tableText` 的普通图片**没有**这个按钮。
  原始输出见 [`design/runtime-evidence/c5-android-table-text.txt`](design/runtime-evidence/c5-android-table-text.txt)。
  这条以前**造不出场景**：发端 `offerFiles` 没有 `tableText` 参数，收端却一直在读，本批补齐。
  仍未覆盖：横向滚动本身（只验了不折行）、其它 `purpose` 带 `tableText` 的未定义组合。
- **群「有人 @ 我」（本轮修复，没跑过）**：设置里打开「仅在有人 @ 我时提醒」，
  让桌面端在群里发一条 `mentions:["<本机 nodeId>"]` 与一条 `mentions:["@all"]` 的群文本 ——
  **两条都应当触发通知**（修复前这条开关对群消息完全不起作用）。
  群里没点名的消息则不提醒、不响铃。

### C6 手机 → 桌面发起的文件拉取
- 期望：桌面发来的文件邀请，本端接受后走 TCP 拉取，进度条推进到 100%，SHA-256 校验通过。

### C7 Agent 会话帧往返（PPE-1 P0 纵切）✅ 已跑（2026-10-07）
- 步骤：在桌面端跑起来（它在 127.0.0.1:8788 上同进程承载控制面），对手机发：
  `POST /v1/agent/session {"to":"<手机 IP>","port":17878,"sessionId":"s-1","alias":"…","state":"busy"}`
  再发几条 `POST /v1/agent/event {"to":"…","sessionId":"s-1","cursor":1,"eventType":"session.started","summary":"…"}`。
- 期望：手机端 Agent 标签页的「事件流」出现会话卡（别名/状态/事件条数）与按 `cursor` 升序的事件行；
  控制面回 `status:"sent"`（对方真回了 ACK）。
- 反向：对一个**没有**声明 `agn1` 的对端（未声明 agn1 的老版本对端）发同样的请求 ⇒ 控制面应回 **409
  `peer-unsupported`** 并提示改用 `POST /v1/send`；对端**不该**收到任何帧。
- 客户端出站与回传：客户端 `RelayController.sendAgentSession` 与 `sendAgentEvent` 出站帧在对端声明 `agn1` 时成功投递；客户端回复文本消息，AgentNode 经 UDP 接收并进入 `/v1/inbox`，客户端收到 ACK 标记 `Sent`。
- 证据（2026-10-07）：完整双向回环与能力拦截证据见 [`design/runtime-evidence/c7-agent-session-roundtrip.txt`](design/runtime-evidence/c7-agent-session-roundtrip.txt)，涵盖 `RelayControllerAgentSessionTest` 真实套接字 8 步验证（公告不污染 IM、乱序重排、幂等去重、客户端聊天回复回执、状态更新、孤儿事件占位、出站发帧及非 agn1 对端能力协商拦截）与 `ControlPlaneTest.agentSessionAndChatBidirectionalWorkflow` 控制面往返。
- 更正（2026-10-09）：本条原写「事件只在内存里，**重启手机端即清空**（§9 的存储分表属 P1.5）」——
  **该说法已过期且与代码相反**。§9 的分表已经落地，会话与事件都真落盘、启动时回读：
  - 表：`AgentSessionEntity` / `AgentEventEntity`，后者主键 `(node_id, session_id, cursor)`，
    见 `composeApp/src/commonMain/sqldelight/app/relay/db/RelayDatabase.sq:220` 与 `:235`；
  - 读写实现：`composeApp/src/commonMain/kotlin/app/relay/app/SqlDelightMessageStore.kt:255`
    `class SqlDelightAgentStore`（`loadSessions`/`loadEvents`/`saveSession`/`saveEvent`）；
  - 平台注入：`androidApp/src/main/kotlin/RelayApplication.kt:41` 与
    `desktopApp/src/main/kotlin/app/relay/desktop/Main.kt:65` 都传 `createAgentStore(platform)`，
    Android 实现是 `composeApp/src/androidMain/kotlin/app/relay/app/MessageStore.android.kt:52-55`
    `SqlDelightAgentStore(driver)`，driver 是**磁盘库** `AndroidSqliteDriver(RelayDatabase.Schema, context, "relay.db")`
    （`composeApp/src/androidMain/kotlin/app/relay/db/DatabaseDriverFactory.android.kt:13-15`）；
  - 启动回读：`composeApp/src/commonMain/kotlin/app/relay/app/RelayController.kt:269` 调用
    `restoreAgentData()`，后者把 `loadSessions()` + `loadEvents()` 合并回 state
    （`composeApp/src/commonMain/kotlin/app/relay/app/RelayControllerAgent.kt:108-121`）。
  - 写入在接收路径上：`RelayControllerAgent.kt:102-103` 的 `saveSession` / `saveEvent`。
  ⇒ 结论：**重启不清空**。此结论目前只由代码链路证明，**尚无真机「杀掉进程再进 Agent 页」的验收**；
  `scripts/e2e/e2e_agent.py` 的 R82 也只覆盖了进程内那一半（见 e2e 覆盖矩阵 §8.1 C3）。
- 已知边界：`age1` 不声明，所以只验单帧事件，不验批量投递与 `GET /v1/inbox?cursor=` 续读。

### C8 戳一碰收到时通知并定位到该会话（✅ 已跑 2026-10-07，Pixel 6 真机）
- 步骤：手机停在**别的**标签页（比如「设置」），Mac 上的 agentNode 发 `POST /v1/nudge`
  （`{"to":"192.0.2.22","port":17878}`）。
- 期望：手机弹出一条系统通知；点开应用后**直接落在与那个对端的会话里**（不用自己找）。
- 实测（`design/runtime-evidence/c8-android-nudge-focus.txt` 是原始输出）：
  - 停在「设置」页时戳一下 ⇒ 5 秒后界面**自己切进**「证据节点」会话（昵称、Agent 徽章、在线）。
  - 通知：`pkg=app.relay id=1001 importance=3 channel=relay_messages_v2 flags=AUTO_CANCEL`，
    `android.title=String (证据节点)` / `android.text=String (对方戳了你一下)`。
  - 点通知卡本体 ⇒ `topResumedActivity=app.relay/.app.MainActivity` 且界面落在那个会话里。
- 本轮**修掉**的缺陷：通知构建时**没有 `setContentIntent`**（`contentIntent=null`），
  所以点通知只会让卡片展开/收起、应用一动不动 —— 与上面这条期望相反。修法是给通知挂一个
  `PendingIntent`（`getLaunchIntentForPackage` + `FLAG_IMMUTABLE`，extra 带对端 id），
  `MainActivity` 用 `onNewIntent` 接住并 `requestFocus`，manifest 加 `launchMode="singleTop"`。
- 反例：只往聊天里写一行提示、人不在那一屏就完全看不见 —— 那是修复前的行为。
- 边界：静音时段（22:00–08:00，设置里可关）内**不打扰**；「Agent 消息通知」开关
  **不影响**戳一碰（那是屏幕后面的人按的，不是 Agent 自己说的）。
- 已知未解决：**应用在后台时可能整段不可达**（Android 冻结 cached app → 没有 ACK →
  戳一碰不排队，于是没有通知）。2026-10-07 复验发现范围比这条更大：**普通消息在后台也可能
  投不到**，发送端只拿到 `{"status":"queued"}`，要等 app 回到前台才补发 + 弹通知。
  冻结时 `ProcessRecord` 是 `curProcState=15`（CACHED_ACTIVITY）、`isFrozen=true`、
  `isFreezeExempt=false`；不是 App Standby（bucket=10 ACTIVE）也不是 Doze（`mState=ACTIVE`）。
  **时机不确定**：三轮采样分别得到 +80s 断、+30s 断、+150s 仍通（那轮 `curProcState=8`
  TRANSIENT_BACKGROUND，系统不冻它）。根因是 manifest 里**没有任何 `<service>`**，
  app 没有前台服务。要保证后台可达得加前台服务 + 常驻通知（Android 14+ 还要
  `foregroundServiceType` 与对应权限），**这是待定的产品决定**，不是顺手能修的 bug ——
  在此之前对外只能说「app 不在前台时收消息可能延迟到下次打开」。
  命令与原始输出见 `design/runtime-evidence/notify-android-policy.txt` 第六节。
  冷启动点通知那条路没造出场景（`force-stop` 会撤掉通知）。
  iOS 与桌面的点击回调都还没接，参数收下但不生效。

### C9 自己发出的图片能撤回（✅ 已跑 2026-10-07，Pixel 6 真机）
- 步骤：手机发一张图片给桌面，**长按那张图** → 应出现「撤回」。
- 期望：5 分钟内可撤回；撤回后本端显示「你撤回了一条消息」，对端图片消失；
  若撤回时对端还在拉取，它应停下并清掉 `.part`。
- 实测（`design/runtime-evidence/c9-android-recall-image.txt` 是原始输出）：
  - 手机上点「发文件」→ 系统文档选择器 → 抽屉「下载」→ `c9-photo.png`（6619 B，
    md5 `645af527c3f23258965e7e8a5a9c3d24`）→ 横幅「已发出 1 个文件」，卡片
    「c9-photo.png / 6 KB / 已发出，等对方接收」。
  - 对端（Mac 上的 agentNode）收到的原文是 `file-ctl offer`，**带 `msgId`**
    （`1d2472a5-…`）—— 撤回就靠它认人。
  - 长按卡片 ⇒ 弹出「撤回」；点它之后本端卡片消失、留一行「你撤回了一条消息」。
  - 对端紧接着收到两条帧：`file-ctl {"op":"cancel","transferId":"1b98a20c-…"}`
    与 `msg {"kind":"recall","targetId":"1d2472a5-…"}`（targetId 正是那次 offer 的 msgId）。
- 反例：长按图片**没有**撤回入口 —— 那是修复前的行为（我方 `offer` 不带 `msgId`）。
- 注意：已经落到对端磁盘的文件**不会被删**（协议只要求隐藏消息），这是刻意的。
- 已知未跑：**「撤回时对端还在拉取 → 停下并清 `.part`」没造出场景**（对端是 agentNode，
  没有界面也不会替人点头，卡片一直停在「等对方接收」，传输没开始）；「对端图片消失」
  这里只有帧级证据（`cancel` + `recall` 到达对端），目视那一步属桌面宿主面（D 组）；
  群图/文本/语音撤回、以及超过 5 分钟后是否真的不给入口，都没跑。

## 5. 桌面宿主面（本仓库 `:desktopApp:run`）

> 这一节的 D1–D7 是**摘要**。逐项的判读标准、证据模板和签字表在
> [`design/host-surface-checklist.md`](design/host-surface-checklist.md)：
> 那里把「输入法六项」（A 节）和「宿主面六项」（B 节）拆成可逐条签字的形式，
> 并写明哪些有单测兜底、哪些只能人眼判。**那张签字表目前是空的 —— 尚未签字。**

### D1 启动与三档窗口
- 期望：1100×720 是三栏；缩到 760 宽变双栏（rail + 侧栏 + 会话）；再窄**回落到手机外壳**；
  输入栏在 420dp 以下降级。断点见 `DesktopMetrics`（`MEDIUM_MIN_PX = 64+300+300 = 664`）。

### D2 托盘
- 期望：关窗后进程留在托盘；托盘菜单能重新打开窗口/退出；退出时发 `exit` 告别帧。

### D3 深链
- 步骤：`relay://192.168.1.5:17878`（以及不合法写法 `relay://garbage`）。
- 期望：合法地址进节点列表；不合法的**明确拒绝**，不能被半推半就塞成 `garbage:0`。

### D4 多窗口
- 期望：把某个会话「在新窗口打开」；两个窗口的会话内容一致，关掉一个不影响另一个。

### D5 省电后台档
- 期望：打开后心跳按慢档走（对端看到「在线」但刷新间隔变长）；关掉回到协议默认档；
  重启应用后**设置还在**（主题、省电档、开机自启三者互不覆盖 —— 曾出过「改完主题，
  自启设置丢了」）。

### D6 下载目录出口
- 期望：「打开下载目录」指向的确实是 `dataDirectory/downloads`，与写入方
  `RelayController` 的 `downloadDir` 是同一个目录（曾指错过）。

### D6b 收到的文件能打开、能分享（桌面侧）（**还没跑过**）

背景：Android 已在 Pixel 6 上验过、iOS 已在模拟器上验过（见 A7 / I5）；桌面侧
`DesktopReceivedFileActions` 有 9 条行为单测，但**「点一下真的把文件交给系统」这一步
没有人手工看过**。桌面没有系统分享面板（macOS 的「共享」菜单长在 Finder 里，AWT 没有封装），
所以桌面上的「分享」= 在文件管理器里定位到那个文件 —— 期望看到 Finder 弹出并选中它，
而不是期望出现一个分享面板。

准备：
1. 起桌面端：`JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :desktopApp:run`
   （在仓库根目录下；网络任务加 `-PrelayMirror=aliyun`）。
2. 起一个能发文件的发送方。最省事的是本仓库的 agentNode（同一个 `agentNode.jar`）：
   `agentNode/build/install/agentNode/bin/agentNode --nick 发送方 --udp-port 17893 --http-port 8799 --data-dir /tmp/relay-accept-d6b`
   （若 17893/8799 已被常驻节点占着，换一对端口）。
3. 让桌面端认识它：桌面端「节点」页 →「添加节点」→ `127.0.0.1:17893`。
   桌面端的 nodeId 从 `settings.json` 里读，或直接在「设置 → 关于」里看。

步骤与期望：

| # | 做什么 | 期望看到什么 |
| --- | --- | --- |
| 1 | 造一个一眼能认出来的文件：`magick -size 240x160 gradient:'#2D6A4F-#95D5B2' -pointsize 22 -fill white -gravity center -annotate 0 'D6b desktop' /tmp/d6b.png` | 文件存在，几 KB |
| 2 | `curl -s -X POST http://127.0.0.1:8799/v1/send-file -H 'Authorization: Bearer <token>' -d '{"to":"<桌面端 nodeId>","path":"/tmp/d6b.png","name":"d6b.png","purpose":"share"}'` | 返回 `"status":"offered"` 与一个 `transferId`；**收到文件不会自动接受**，这是设计 |
| 3 | 在桌面端会话里找到那张报价卡，点「接收」 | 卡片从「等你接收」变成「已接收」，并显示「已存到 …/relay/downloads/d6b.png/d6b.png」 |
| 4 | 看卡片底部是否出现两个按钮 | **「打开」与「分享」两个 `TextButton` 都在**。只有一个或一个都没有 ⇒ 平台没给出 `receivedFileActions`（`null` 时界面**故意不画**按钮，见 `PlatformContracts.kt`），要回头查 `DesktopPlatform.receivedFileActions` |
| 5 | 点「打开」 | 系统默认看图应用打开，画面就是那张绿色渐变图、白字 `D6b desktop`。**打不开时它会退到「分享」而不是报错** —— 若直接弹了 Finder，说明这台机器没有该类型的默认应用（`Desktop.open` 抛了），也是契约内的行为，但要记下来 |
| 6 | 点「分享」 | Finder 打开并**选中** `d6b.png` 本身（`Desktop.browseFileDirectory`）。只打开所在目录、没选中文件 ⇒ 退化到了 `browseDirectory` 那条兜底路径，记下来 |
| 7 | 两个动作都点完之后 `pgrep -fl 'app.relay.desktop.MainKt'` | 进程仍在。契约写明实现**不许抛异常**，做不到就什么都不发生并自己留痕（桌面没有日志框架，诊断走 `println("relay: …")`，见 `DesktopReceivedFileActions.TAG`） |
| 8 | 核对落盘副本：`shasum -a 256 /tmp/d6b.png <dataDirectory>/downloads/d6b.png/d6b.png` | 两个 sha256 相同。多一层同名目录是设计（每笔传输先按传输名建目录，见 `TransferManager.kt`） |

判读要点：
- 第 4 步是**最容易出问题的一步**：桌面是平板壳与桌面壳共用的一条路
  （`DesktopPaneActions.receivedFileActions` 是「设备事实」不是动作），只接移动端那条路
  等于平板永远没有这个入口。
- 第 5、6 步失败时**不要**把它记成「按钮没反应」：先看 `println` 里那行 ASCII 诊断
  （`relay: ...`），它区分了「没有桌面环境（headless）」「宿主不支持这个动作」「动作抛异常」
  三种情况。
- 证据写进 `design/runtime-evidence/desktop-d6b-file-exit.txt`，截图留本地。
  > **2026-10-18 核对**：这份证据文件**已不存在** —— `design/runtime-evidence/` 下查无 `desktop-d6b-file-exit.txt`（该目录现有 80 个文件，含 `r2-desktop-capture-*` 等，但没有这一份）。
  > 本条结论**保持原样**（不重写历史），只如实标注：证据链已断，D6b 这条现在只有文字结论、没有可核对的原始输出。

### D7 拖出文件
- 期望：把文件卡拖到系统文件管理器/聊天窗口，能落成真实文件。

### D8 输入法六项（**仪器已就位，六项都还没跑过**）
- 步骤：设置窗（托盘菜单 → 设置…）→ 顶部第三个分段「输入法自检」。
- 六项：候选窗跟随光标、内联预编辑、上屏进回调、中英切换、退格删拼音、失焦是 commit 还是丢弃。
  逐项的「看什么/通过/不通过」见 `design/host-surface-checklist.md` 的 A 节。
- 那一页是**诊断仪器**，不是产品功能：它把输入法在 Compose 里真正留下的状态（文本、组合区间、
  选区、上屏回调次数、失焦前后值）显示出来，把「候选窗没跟着光标」这类感觉变成可复述的事实。
- 期望：六项逐条判读并签字。**注意边界** —— 候选窗位置只能人眼看，仪器读不到它的坐标；
  失焦那行如果显示「失焦后没有回调」，那是**真的丢弃了预编辑**，不是「还没来」（待定期 600ms 后才收）。
- 证据：`runtime-evidence/host-A-<平台>.txt`（截图留在本地）。

## 5b. 机器夹具（`scripts/e2e/`）读数

这一节记的是**机器夹具**在真机上跑出来的读数，与上面「人点出来的」条目分开记：夹具能覆盖
界面/协议/落盘，覆盖不到权限弹窗与系统出口。夹具与它跑出来的产品缺陷写在
[`docs/plans/2026-10-09-relay-e2e-fixture-program.md`](docs/plans/2026-10-09-relay-e2e-fixture-program.md)。

跑法：`python3 scripts/e2e/run_all.py`（或单跑 `python3 scripts/e2e/e2e_<功能>.py --phone <手机 IP> --host-ip <本机 IP> --secs 40 --tcp-port 179xx`）。

> **这九个夹具演的是 legacy 对端。** 被测端默认开着加密数据面时，legacy 发现不启动、17879/17881/17882
> 也不监听 —— 夹具广播的 `entry` 没人解，读到的是「握手建不起来」而不是功能缺陷。跑之前先在设置里
> 关掉加密数据面并**重启节点**（端口账见 `scripts/e2e/README.md` §2.1）；加密数据面自己的验收在 A21。

**跑前跑后各对一次** `adb -s SERIAL-TEST-01 shell dumpsys package app.relay | grep lastUpdateTime`：
不一致说明中途被重装了 APK（`install -r` 会杀掉应用），这一轮读数作废。

**九夹具同轮全绿（2026-10-10 全量套件 run11，`/tmp/e2e-all-run11.log`，`run_all EXIT=0`）**：
`9/9 PASS` —— messaging 38（140.9s）、discovery 85（488.8s）、files 96（162.7s）、
media 82（242.5s）、group 106（586.3s）、share 50（231.6s）、agent 61（123.3s）、
search 33（139.5s）、screen 22（2.0s）。跑前跑后 `lastUpdateTime` 都是 `2026-10-10 08:51:48`
（APK 未被重装 ⇒ 读数有效）。**这是最终夹具代码上的官方读数**（run10 之后又修掉了
`scroll_list_to_top` 判据提前收手那一处读法缺陷，见下段）；run11 的 group 比 run10 快了
约 150s，正是"先看视口、不无条件回顶"省下来的。

**九夹具同轮全绿（2026-10-10 全量套件 run10，`/tmp/e2e-all-run10.log`，`run_all EXIT=0`）**：
`9/9 PASS` —— messaging 38（130.4s）、discovery 85（489.4s）、files 96（176.0s）、
media 82（239.3s）、group 106（735.0s）、share 50（232.6s）、agent 61（123.9s）、
search 33（133.0s）、screen 22（1.9s）。跑前跑后 `lastUpdateTime` 都是 `2026-10-10 08:51:48`
（APK 未被重装 ⇒ 读数有效）。这一轮之前的两处红已各自定位并修掉：run8 的 files/media 双红是
**产品缺陷**（`threads` 的跨线程数据竞争，启动即崩，见计划文档 §5.12）；run9 的 group 唯一红
`group-send-landed` 是**夹具注入缺陷**（`adb shell input text` 概率性吞字符，8/8 全灭，
见计划文档 §2.1/§4 与下面 `e2e_messaging.py` 那一行）。

**run10 之后又清掉一处夹具读法缺陷（2026-10-10）**：run10 全绿后单独复跑 group 复现了 run11 式的
3 条红（`group-reonline-numerator`、`group-chat-opens`、`group-inbound-chat-opened`）。真因是
`scroll_list_to_top()` 的判据**会提前收手**——长列表滑动有惯性余量，签名在第 17/18 拍短暂稳定就停了，
而真机实测从底回顶要 19 拍，旧判据 **2/4 次停在真顶之外**（目标群行恰在其上方一格）。同一轮里
`open_chat 'grpdb109c'` 稍后第 1 次尝试就成功 ⇒ 行一直在列表里，**不是产品缺陷**。改法：判据改成
churn 稳健（粗采样 + 到顶候选处再滑一拍确认），并让 `open_chat`/`_conv_row` **先看当前视口**、
找不到才回顶（回顶一次 12–34s，而 `_poll_row` 每 0.6s 调一次，原来 10s 的轮询窗口只够跑一次迭代，
`group-reonline-numerator` 的抖动就是这么来的）。修后单跑 **group PASS 106**（`/tmp/group-run14.log`）、
messaging **PASS 38**（`/tmp/messaging-run14.log`），全量套件 **run11 9/9 PASS**。详见计划文档 §2.1 末尾两行。

| 夹具 | 最近读数 | 说明 |
| --- | --- | --- |
| `e2e_discovery.py` | **PASS 85**（2026-10-10 run11，`/tmp/e2e-all-run11.log:327`） | R16–R27 全绿：mDNS 自动发现（R18）、手动添加节点（R19，含收键盘+弹层滑动）、退出即离线（R26）、节点页/设置页可达（R24/R25）。**Pixel 6 上 mDNS 接收侧不需要 `CHANGE_WIFI_MULTICAST_STATE`**，这一条由此回答（`AndroidManifest.xml:51-62` 的遗留疑问）。 |
| `e2e_messaging.py` | **PASS 38**（2026-10-10 run11，`/tmp/e2e-all-run11.log:129`；单跑 run14 同读数 `/tmp/messaging-run14.log`） | 短/长文本、TCP 帧形状、去重、撤回、戳一碰；run2 的 15 条级联与 ENVERR 全清（根因是会话列表记住滚动位置 + 一条 adb 命令塞上千个 `input keyevent`）。注入侧见计划文档 §2.1/§4：`adb_input_text` 已从「整串 `input text`」改成**逐键 `input keyevent`（chunk=2）**，因为整串注入在 Pixel 6 上概率性吞字符。 |
| `e2e_search.py` | **PASS 33**（2026-10-10 run11，`/tmp/e2e-all-run11.log:1933`） | 会话内浮层 + 会话列表检索、清除搜索后列表复原。 |
| `e2e_files.py` | **PASS 96**（2026-10-10 run11，`/tmp/e2e-all-run11.log:562`） | R35–R38：入向 offer 的接收/拒绝两条出口、TCP 拉取 + 进度 + SHA-256（校验通过才 `rename .part`）、同一进程内断点续传（第二次 `pull` 带 `offset > 0`）、杀进程重启后 `sweepStaleParts` 不留 `.part`。**run8 曾是 FAIL 21（`failed=handshake-alive`）**，真因是产品启动即崩（计划文档 §5.12），修后转绿。 |
| `e2e_media.py` | **PASS 82**（2026-10-10 run11，`/tmp/e2e-all-run11.log:811`） | R49–R66：图片内联预览（≤20MB 自动接收；dump 里没有文字/desc，靠「结构痕迹 + 点击开出查看图片弹层」两条一起证）、语音播放、表格文字、撤回、戳一碰、PK。**run8 曾是 FAIL 24（`failed=handshake-alive`）**，同上由 §5.12 的修复转绿。 |
| `e2e_agent.py` | **PASS 61**（2026-10-10 run11，`/tmp/e2e-all-run11.log:1863`） | R80–R82：夹具扮成声明 `ag1`+`agn1` 的 agent 宿主，发 `agent-session`/`agent-event` 帧，断言手机「Agent」页的会话卡与按 `cursor` 升序的事件行；含对未声明 `agn1` 的对端回 409 的负控。 |
| `e2e_group.py` | **PASS 106**（2026-10-10 run11，`/tmp/e2e-all-run11.log:1424`；单跑 run14 同读数 `/tmp/group-run14.log`） | 建群/群信息/入站 info 改名/加人/移人/群文本扇出/@ 入口全绿（run6 唯一那条 `mention-draft-sent-as-group-text` 是夹具读原始草稿 `'@all '`、产品会 `RelayControllerGroups.kt:251 val body = text.trim()`）。run10 是**会话列表两处读法修**（`scroll_list_to_top` 滑到不动为止、`goto_messages_list` 认出并清掉搜索浮层）之后的复跑；全量套件 run6 里那 8 条红就是这两处读法错。run11 是**第三处读法修**（`scroll_list_to_top` 判据提前收手 → churn 稳健确认；`open_chat`/`_conv_row` 先看视口不再无条件回顶）之后的复跑，用时从 run10 的 735s 降到 586s。**run9 曾唯一红 `group-send-landed`**（`框里=None`，8/8 次注入全被吞字，且 `_fanout_phase` 提前 `return` 带走后面 4 条 fan-out 断言），随逐键注入修好。已修的产品缺陷见计划文档 §5.3、§5.6、§5.8。 |
| `e2e_share.py` | **PASS 50**（2026-10-10 run11，`/tmp/e2e-all-run11.log:1553`） | R88 开关/目录/权限三档、R89 对端柜视图与入站 `share` 帧、R90 CIDR 探活（**要求目的地址是 `--host-ip`** 的真单播）、R91 入站 `scan-ranges` 吸收/来源/续传、R92 `av1` 宣告面、R93/R94。跑前确保 UDP **17878** 未被外部节点占用，否则夹具的 `--udp-listen` sink 会 `EADDRINUSE`。R90 开始前会**清空手机上残留的网段**（历轮夹具留下的 /24 会让 `立即探测` 扫上几百台，单播 entry 等不到；run5 的 2 条红就是这么来的），R91 的 ACK 断言已改成「gossip 帧有意不回 ACK」，回发判据改成「制造一次**对端上线**」（吸收入站网段本身不触发分享，见计划文档 §4/§5.9）。**夹具会先按返回键退出任何残留页面**（上一轮别的夹具可能把手机留在聊天页，而聊天页没有底部导航）。 |
| `e2e_screen.py` | `run_all` 里跑的是 `--mode watch`（**PASS 22**、1.9s，2026-10-10 run10）；**真双向已跑绿**：`--mode viewer --ui-assist` **PASS 24**（`/tmp/screen-viewer4.log`，手机当 Sharer、真 MediaProjection、60 帧/8 种尺寸）、`--mode sharer --ui-assist` **PASS 24**（`/tmp/screen-sharer3.log`，夹具当 Sharer、手机当 Viewer、40 帧假 JPEG） | `run_all` 的 `--mode watch` **不能替代 A17**：那 22 条是本地自检 + `handshake-alive`，**不建立投屏会话**；真读数见 A17。跑 sharer 需要 `--ui-assist`（发起入口在手机 UI 里：先敲门把会话顶到列表最上面，再点会话行 → 「屏幕协助」）。这一轮逼出的产品缺陷（TCP 回退未捕获异常打死 app）见计划文档 §5.10。 |

## 6. 已知无法在本机验证的项

这一节不是待办，是**证据边界声明**，请勿在对外材料里说成「已验证」：

- 所有真机权限弹窗、系统分享/打开、通知栏表现。
- 相机/扫码的**画面与识别**：授权框、返回键、拒绝授权横幅已在 Pixel 6 上跑过（见 A15 与
  `design/runtime-evidence/scan-a15-android-permission.txt`）；取景画面与「把码读出来」
  没有跑过。生成侧见 A14。
- 广播帧在 Wi-Fi 省电模式下的接收（A3 的已知未验项）。
- iOS 文件出口的边角（I5：iPad popover、「打开」失败退到分享、桌面侧同一能力只跑了单测、
  系统「文件」App 里仍看不见）。
- **桌面输入法行为**（D8 六项）与**宿主面人工项**（HiDPI 缩放、托盘、拖放、深链两条路径、
  多窗口、休眠唤醒）：只能在真实窗口里由人签字，无头渲染里没有输入法、没有托盘、没有休眠。
  其中一部分有单测兜底（`desktopApp/src/test/` 六个文件），但**单测证明不了行为**。
  清单见 [`design/host-surface-checklist.md`](design/host-surface-checklist.md)。
- 手机侧进程被杀时的告别帧（A12，没有生命周期钩子）。
- 外网侧的 IPv6 / 跨出口链路 —— 那部分有单独的手册：
  [agentNode/tools/CROSS-NET-RUNBOOK.md](agentNode/tools/CROSS-NET-RUNBOOK.md)。

## 8. 公网（TLS + pin）、长连接与跨网文件（2026-10-18 新增）

> 与 A 系列同格式。**没跑过的如实标「未跑」** —— 单测绿 ≠ 真机通过。

### N1 公网直连走 TLS + 双向 pin —— 单测绿，真机未跑
- 判据（单测）：`LinkAcceptPolicyTest`（接受裁决表）、`TlsPinTest`、`LinkCloseReasonTest`。
- 步骤：两台设备分处不同网络（蜂窝 ↔ 家庭宽带），手机扫码配对 ⇒ `/v1/peers` 应 `linkConnected=true`、`linkCarrier=tls`，`linkDenyReason` 为空。
- 反例检查：故意把主机 pin 换成错的 ⇒ 必须 `pin-mismatch`，且**不回退明文**。
- 状态：**真机未跑**（需要两台不同网络的设备，且主机 `tlsPort` 从公网可达）。

### N2 明文永不出局域网 —— 单测绿
- `plaintextLinkForbidden` 走 `isLanAddress`；公网对端的明文连接在 accept 被回 `not-lan`；地址变化后的复查**只**摘「声明明文且已不在局域网」的连接。
- 证据：`IpAddressPrivateTest`、`LinkAcceptPolicyTest`、`CapabilityDeclarationTest` 里那条「GUA-同/64 是局域网、真公网明文必摘」。

### N3 换网立即重连（要求 ≤10s）—— 真机已跑 ≈3s
- 步骤：长连接建立后关 Wi-Fi 切蜂窝（或反向），读 `/v1/peers` 的 `linkDialAttempts` 与 `linkConnected` 恢复时间。
- 证据：task-37 真机实测 ≈3s；设计与治理见 [`docs/cross-network-keepalive-design.md`](docs/cross-network-keepalive-design.md)。

### N4 跨网文件传输 —— 代码链路绿，真机未跑
- 步骤：不同网络的手机与主机互发 ≥50MB 文件，中途断网再续 ⇒ 断点续传生效、SHA-256 校验通过、尾部不丢字节。
- 证据（单测）：`CrossNetworkTransferPolicyTest`、`CrossNetworkTlsTransferTest`。
- 状态：**真机发文件未跑**（这是本轮明确留白的验收项）。

### N5 外部来源配对必须二次确认 —— 真机已跑（2026-10-18）
- 步骤：`adb shell am broadcast -a app.relay.TEST_INJECT_QR --es qr relay://<ip>:<port>`，再 `am start -a android.intent.action.VIEW -d relay://<ip>:<port>`。
- 期望：出现确认框；**点「取消」后 `settings.json` 的 `manualPeers` 不变**；再注入并点「允许」才出现新节点。
- 证据：task-54 真机逐条验证；e2e 里是 `scripts/e2e/e2e_workspace_collaboration.py` 的 `qr-pairing-confirmed`（脚本**真的点**确认框，不用后门）。

### N6 长连接的「谁先关的」可诊断 —— 单测绿
- `closeReason`（`dead-timeout` / `peer-closed` / `replaced` / `recheck` / `stopped`）+ `closeCount` + `closeDetail` 三个字段同时出现在宿主 `/v1/peers` 与手机 `files/relay/diag/link.json`。
- 证据：`LinkCloseReasonTest`。

## 9. 本轮新增的「无法在本机验证」项（补充 §6）

- **公网 TLS 的真实握手**（N1）：需要两台不同网络的设备与可达的 `tlsPort`。
- **跨网大文件的真机传输**（N4）：代码与单测已绿，真机未跑。
- **iOS 端到端 TLS**：烟测构建（`CODE_SIGNING_ALLOWED=NO`）没有 keychain 容器，`SecItemAdd` 返回 `-25291` ⇒ 必须有**签过名**的构建才能验；在此之前 iOS 侧没有 TLS 传输，公网长连接被策略层拒绝（fail-closed）。
- **路由器 IPv6 入站放行**：属于用户网络环境，本应用不做打洞；换一个网络就要重新确认。

---

## 10. Agent-IM 里程碑 M1–M4（2026-10-30）

协议纯化、插件宿主网关、共享界面三件套与跨端派单网道的验收判据（文字证据：`design/runtime-evidence/` 下
`2026-10-30-m1-protocol-purification-and-debts.md`、`2026-10-29-m2-plugin-host-gateway-smoke.md`、
`2026-10-29-m3-shared-ui-redesign.md`、`2026-10-30-m4-cross-host-invoke-mesh.md`）：

1. **群文本白名单 14 键**：`@agent` 消息带 `agentIntent` 可通全链路；带 `reasoning` 的帧被整帧
   拒收（键已退场）；`card` 超 `CARD_BYTES`（24 KiB）判 bad-payload，发送侧超限**拒绝不截断**。
2. **插件全生命周期**：echo 插件（`examples/plugins/echo-team-plugin/`）放进插件目录 → 桌面启动
   约 15 秒自动注册 online；派单后事件流（含卡片）进台账；`kill -9` 后**等满 90 秒**
   （`offlineAfter`）确认翻 offline、主程序 `closeCount` 不增；守护进程模式凭 0600 token 文件
   重注册回 online。
3. **注册鉴权负路径**：无 token / 错 token 的注册请求被拒（401/503），本机任意进程不能自我入驻。
4. **卡片折叠**：任务推进时 DB 新增同 `cardId`、`rev+1` 的**新行**（旧行不重写）；UI 只渲染最新
   `rev`、不新增可见气泡。
5. **授权面板**：仅插件宿主可编辑；非宿主只读且不显示本机路径（路径不进协议）；保存后 `rev+1`
   广播到全群。
6. **上下文胶囊**：键入 `@agent` 或回复 agent 发言时唤起；粘帖文本不误触发；多 @ 只绑首个执行
   主体；点选会话可精确投递（会话已不存在则如实不投，不静默换投）。

**M4 跨端派单网道（读数：`design/runtime-evidence/2026-10-30-m4-cross-host-invoke-mesh.md`）**：

7. **Layer 4 双节点仿真**（`:app:jvmTest` 的 `TwoNodeAgentInvokeTest`）：跨端派单到达
   （执行器逐字拿到 prompt）；群流零跨端中间文本 —— 两端库本回路新增行**全部是看板卡**，
   判据可区分「没产生」与「被判坏帧删掉」；宿主断连后租约在 `deadlineMs` 到期原子翻
   Timeout（超时卡带 [重试]/[降级为本地执行]）；审批防重跨端回路（首个判定生效 rev+1
   广播全群、晚到点击幂等丢弃、丢弃 ≠ 消息卡住）。
8. **`/v1/agent/invoke` 路由**（真桌面、真打包）：缺必填字段 ⇒ 400 `bad-request`；
   群里没有这个 agent ⇒ 404 `agent-not-found`；自派 ⇒ 200 + invokeId，随后租约翻 Done、
   看板卡进群、插件台账 `state=done`。`/v1/agent/run` 原语义一字未动（路由对账裁定见
   执行追踪器 M4 节：run 是执行状态宣告帧，invoke 是带租约的派发 RPC，不构成平行端点）。
9. **`@agent` 完整回路**（M2 遗留复测）：手机胶囊 @ → 意图帧 → 宿主授权（可信设备档
   放行）→ 执行桥 → 插件执行（台账 `state=done`、prompt 逐字、会话标题 = 剥掉 @ 之后的
   任务话原文）→ 卡片进群 → 手机渲染卡片、**零假横幅**、群流零跨端中间文本；桌面
   FTS 不变量（`MessageFts_docsize` ≡ `MessageEntity`）顺带复核。
10. **真机抓出的三处缺陷的验收方法**（各有反向验证，红→绿）：
    - 插件身份匹配用了 slug（真机现象：HTTP 200 + invokeId 但零会话零卡片、三处静默）
      ⇒ 修后 `PluginForAgentMatchTest` 4 条 + 真机同流程全通；
    - @ 意图回路 prompt 丢失（真机读数：插件会话标题为空）⇒ `AgentIntentPromptAndGrantBridgeTest`；
    - 「没有执行」横幅与执行不同源（修前装机横幅先出）⇒ 真机同流程零横幅 + 守卫钉住
      「带意图的发送跳过发送侧本地自检」（授权归宿主）。

**如实降级**：双桌面跨端（A 派单 → B 执行）的真机验收**未做** —— 只有一台桌面；该条由
Layer 4 仿真覆盖，真机环节为桌面自派 + 手机 `@agent` 回路（M5 矩阵同步记账，不包装）。
已知边界：宿主拒绝跨端意图时发起端暂无回执（`PROTOCOL-COVERAGE.md` §五）。

---

## 7. 单条记录模板

```text
### A7 收到的文件能打开、能分享 —— 通过
设备：Pixel 6 / Android 15（build APK 2026-10-07 HEAD 7b8ba0e+）
步骤：桌面 0.61.1 发 img-01.jpg → 文件卡点「打开」→ 再点「分享」→ 选相册
实际：打开落在系统相册；分享面板列出 8 个应用；content:// URI 授权正常
反例检查：未出现 FileUriExposedException；按钮不是灰死状态
证据：runtime-evidence/e2e-20-android-open-share.txt（截图留在本地）
```


---

## A18 · 身份恢复（L1 导出/导入 + L2 对端拉回）

**L1（口令备份，全量快照）**：设置 → 数据与存储 → 备份与恢复。
1. 导出：输口令 → 分享面板拿到 `.rlrb` 文件；核对文件大小与条数提示（含消息条数）。
2. 换台设备（或卸载重装）导入同一文件：输错口令必须被拒（AUTH_FAILED），输对口令后
   节点自动重启，对端节点表里它仍是**原来的 nodeId**（pin 不变）；**设置（昵称/通讯录/群）
   与聊天记录一起回来**，自己发过的消息恢复后仍显示为自己发的（mine 不翻转）。
3. 反向验证：把备份文件改一个字节再导入 ⇒ 必须拒绝；老的身份包（只含身份）仍可导入，
   且导入后会向信任根里的对端发起 L2 拉取（设置页重启后离线队列无堆积）。

**L0（平台存活存储）**：
- iOS：卸载重装（换库或新目录）后冷启动，身份自动回来（钥匙串），节点表无需重新配对；
  同一 iCloud 账号下第二台设备首装会自动收养同一身份（Q10 已知限制，见 README）。
- Android：重装后冷启动自动找回；若触发 A6 属主断裂（读 Downloads 抛
  SecurityException），`files/relay/diag/` 诊断可见提示，改走设置页 L1 导入（Q6 主预期）。

**L2（对端拉回）**：恢复完成后，恢复方向在线的旧对端发 `recovery-req`；
判据 = 对端会话里历史条目按消息 id 幂等回来（同批重发不重复），旧版本对端
（不认识帧）时恢复方 5 秒窗口内放弃且**离线队列必须为空**。
