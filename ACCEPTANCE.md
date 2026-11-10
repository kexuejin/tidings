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

### 1b. 装机与重注册（每次换产物都走一遍）

- 产物统一放 `~/Downloads/relay-build-<date>/`；**报告前核对 sha256**。
- APK：`adb -s <serial> install -r -d <apk>`。
- 桌面端：`osascript -e 'quit app "Tenon"'` → 替换 `/Applications/Tenon.app` → **重注册** → `open -a`：

  ```bash
  /System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister \
    -f -R -trusted /Applications/Tenon.app && killall Finder
  ```

  **为什么必须重注册**：LaunchServices 按 `CFBundleIdentifier` 索引图标缓存，直接替换 bundle
  **不会**让缓存失效。不补这一步，新版装上去仍显示旧图标（常常是空白/通用图标），
  看起来像「打包把图标弄丢了」—— 而实际 `Tenon.icns`、`Info.plist` 的 `CFBundleIconFile`、
  以及两处 sha256 全是对的。**分辨方法**：把 .app 拷到 `~/Applications/` 再打开，
  新路径不命中旧缓存；那里图标正常 ⇒ 是缓存问题，不是打包缺陷。
- **不要只看「装上了」**：在新产物里 grep 一个只有新代码才有的符号名，证明装的是新的。

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
  `dumpsys` 里 0 条通知、`messages.json` 里 1 条、`pidof com.kapp.tenon.debug` 仍在、logcat 有
  `W AndroidPushNotification: 通知未发出：通知权限未授予（系统设置里可改）`）：不弹、不崩、
  也不静默吞掉。连同三个通知开关（Agent 开关 / 静默时段）与戳一碰的真机判读，见
  `design/runtime-evidence/notify-android-policy.txt`。

### A3 局域网自动发现（手机 ↔ 手机）
- 期望：两台手机都开着榫卯时，几秒内各自出现在对方的「局域网发现」里。
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
  1. B 第一次点「打开相机扫码」时弹系统相机授权框（Android 是「允许榫卯拍照」，
     iOS 是「榫卯要用相机扫对方屏幕上的配对二维码…」）；
  2. 取景框里能看到**真实相机画面**，把 A 的码对准后约 1 秒内自动返回节点页；
  3. B 的节点表里出现 A（地址 = A 屏上那串 `relay://` 里的 host:port），随后能收发消息。
  4. 在取景框上按**系统返回键**（或点左上角 ×）→ 回到节点页，**不是**退出应用。
- 反例（每一种都要能在界面上看到原因，而不是"点了没反应"）：
  - 拒绝授权 → 扫码页横幅「相机权限被拒绝，请在系统设置里开启后重试。」+「再试一次」；
  - 相机被别的应用占用 / 模拟器没有相机 → 横幅「这台设备现在给不出相机画面（模拟器没有
    相机，或相机正被别的应用占用）。也可以回节点页用「填地址」把对方加进来。」；
  - 扫到别的二维码（网址、支付码）→ 横幅「这不是榫卯的配对码」；
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
  落盘设置里多出那一条；两张反例图分别给出「这个二维码不是榫卯的配对码。」与
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
| 群里发一条带 `senderId` 的 agent 转发消息，群内有**上游茶话间 0.61.2**（caps 无 `gta1`） | 它**能显示这条**，且收到的帧只含 `kind/text/groupId/groupRev/mentions/resend/replyTo`；`id` 与声明了 `gta1` 的成员收到的**完全相同**（`ts` 也相同） |
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
- **本表仍未逐条跑完**（≥50MB 双向、错误配对密钥、陌生连接 30s 断连、legacy 拉取回退都没做）。
  2026-11-05 在真 luciola 链路上跑通的那部分（装置面端口账 + 真实文本/文件/手机端接收）
  单独记在 §5c 与 [`2026-11-05-tenon-luciola-head-e2e.md`](design/runtime-evidence/2026-11-05-tenon-luciola-head-e2e.md)，
  **不要把 §5c 的通过反填进本表**。

### A22 单聊派单闭环与工作区直聊（2026-11-07 新增，65caa9bd 装机真机跑）

手机（Pixel 6）与桌面端同 LAN，两端都装 HEAD 构建后逐条判。

| 步骤 | 判据 | 状态 |
|---|---|---|
| 1:1 会话顶栏 | 出现项目药丸（当前工作区名）+「切换工作区」；旧顶栏切换入口与输入框上方意图胶囊**已消失** | ✅ 通过 |
| 工作区选择抽屉 | 标题「选择工作区」，列出全部已登记工作区与路径，单选后药丸更新 | ✅ 通过 |
| 1:1 普通文本派单 | 编排层立项并派到**药丸选中的工作区**的 agent | ✅ **通过**（11-08 第三轮，宿主编译含 D1/D2/D5 修复后真机复跑）：`[OCGO-BRIDGE] invoke agent=DSH wsId=ws-e9b18063d183 cliDriver=dsh-cli` → 沙箱 `harvest +1 -0`（patch 166 B）→ 卡片 `chain-tmw79x4l2` 以 rev 1→8 同会话原位推进 → 产物 3 个文件 `[xfer] state=Completed` → 手机点「合并此补丁」→ 宿主 `[RELAY-INVOKE] patch merged into workspace by 25222a45…; next sandbox base=c8dfafb0…` → **工作区真出现 `probe5-20261108.txt`（内容 `PROBE-OK5`）** → 手机卡面翻「已合并」 |
| `@mention` 点名 | 从原文解析 agentKey 并路由到对应驱动 | ❌ **不通过**：会话切换只改看板展示；`dsh` agentId 在插件层被 dsh-echo-bridge（参考实现）占用，真 dsh-cli 插件因裸 entrypoint 拉起即退、状态永远 `installed`，`pluginForAgent` 只匹配 online ⇒ 选不中 |
| 工作区协同群派单 | 建群后 `@agent` 意图进席位表并派单 | ⚠️ 部分：关联工作区建群**不自动声明席位**（virtualAgents 为空），意图卡在 seat-sync 重试；老群有 DSH 席位但无 `defaultWsId` ⇒ 不绑仓 |
| 跨端派单 API 直派 | `POST /v1/agent/invoke {groupId, agentId, wsId}` 显式带工作区 | ✅ 受理成功（invokeId 返回），但执行体仍落 echo 桥（缺陷 4b 的表现） |
| dsh CLI 本体 | `dsh --profile headless --json` 在仓内执行工单 | ✅ NDJSON 全链 + exit 0；同款命令直驱工作区完成 PluginHost 即退诊断工单（`6bc6e5cd`，独立验收含反向验证红→绿） |

- 证据：[`2026-11-07-unified-chat-dispatch-loop.txt`](design/runtime-evidence/2026-11-07-unified-chat-dispatch-loop.txt)、
  [`2026-11-08-a22-1to1-workspace-dispatch.txt`](design/runtime-evidence/2026-11-08-a22-1to1-workspace-dispatch.txt)（第二～四轮：D1–D4 成因、D5 根因链、闭环复验与残余 D4/D6/D7 全文读数）、
  [`2026-11-08-t1-t8-acceptance.txt`](design/runtime-evidence/2026-11-08-t1-t8-acceptance.txt)（第八轮：T1–T8 全面验收 + L0/L1 判定 + D12/F1/O3 立案全文读数）。
- 派单链路的四条缺陷（1:1 wsId 死分支 / 药丸不进帧 / mention→驱动解析失效 / 席位与 echo 桥占用）
  每条都有可复现命令与会话 id。第 3 行已于第三轮反填为 ✅（D1/D2/D3/D5 闭合）；
  **第 4 行（`@mention` 点名）仍未通过，不得反填** —— 插件占用（echo 桥 / 裸 entrypoint）
  已解，现阻塞是**席位声明**（见下行机理更新）。

#### 11-08 第八轮：T1–T8 全面验收（含 L0/L1 判定与新立案）

装机按 §1b（APK `9aa07e13…`、Tenon `b3e722ce…`、新符号核验、lsregister）。
判定一律看两端库与工作区文件，不看气泡与信封 ts。

| 项 | 判定 | 读数 |
|---|---|---|
| T1 药丸+抽屉+D8 持久 | ✅ | 药丸/单选抽屉/`lastDispatchWsId` 即时落盘；force-stop 重启后仍是所选项（`install -r` 也保持） |
| T2 七跳全链 | ✅ | `probe9-20261108.txt`=PROBE-OK9 真落工作区；沙箱基=上一单合并结果（合流自续）；**D9 宿主库复验**：最新 `Ticket accepted` 行 sender_id=`agent:46c23456…:dsh`，落库署名成立 |
| T3 就地审批 | ✅ | 控制面 POST → 审批卡 → 批准 → 手机行 approved + 宿主台账 approved 同刻；kind 白名单由「状态可被推进」证明；O3：过期审批仍画横幅（小） |
| T4 翻面持久 | ✅ | 杀进程重进仍是「已合并/进展100%」；手机 BoardCardRev=`chain-tmw7hhlai\|7` |
| T5 群作战室 | UI ✅ / 派单 ❌ / N>2 受阻 | 群 @dsh → `[NO_WAKE] groupId=…`。机理：**手机建的绑定群（新旧都一样）virtualAgents 全空**（新建 china-pass 群实测仍 NONE），`isMentioningAgent` 只认声明过的 kind ⇒ 一切 @ 派单 NotAddressed；手机 @ 选择器数据源（工作区 agent）≠群花名册 |
| T6 宿主失联容错 | 补发 ✅ / 横幅 ✗ | SIGSTOP 宿主：失联中发的 34B 文本在 SIGCONT 后补发到达、closeCount 全程 0；**但 90s 离线翻转永不发生**（5 分钟后头部仍「在线」）—— **D12 新立案**：luciola 模式下 legacy Discovery 被哑实现替换（`NodeRuntime.kt:428-436`，sweepLoop 消失），注册表无人按 `offlineAfter` 扫描 ⇒ 休眠横幅在 Android 上不可达 |
| T7 存量驱动+标签 | ✅ | dsh-cli 全程应答；今日新增 agent 行两端都只有 `agent:<host>:dsh` 一种拼写（历史双拼写是 §2.4 旧账，零新增） |
| T8 新旧互通 | A5 双向 ✅ | 旧构建对端 862 字符长文走 TCP 到宿主、宿主 865 字符回程在旧端逐字节相等；wire 改动仅可选 `senderId`（null 时不写键），`GROUP_TEXT_KEYS` 0 行 diff + golden vectors 由 L0 钉住 ⇒ 「零 wire 变更」成立；群 leg 构造性覆盖（如实记，未跑活体） |

**卡片家族规格扫描（同日，用户指出测试只见了工单卡一种）**：把发射侧全部卡片形态
（proposed/prototype/working/review_ready/blocked/aborted/merged + 崩溃检查项 +
熔断纠偏卡 + 自描述产物 + decided 知情行）按发射 JSON 形状喂进手机端解析器，
`AgentCardFamilySpecTest` 9 例逐一钉住 UI 模型事实（反向探针：砍 elapsedSec 接线 ⇒ 红）；
`StageChainTest` +2 钉发射侧动作分工。一笔误判如实纠正：受理卡的「立即执行/取消」
其实早就在（cardOf 尾部 proposed 分支 + labelKey 路由 + 宿主 start-task/cancel-task
分支全链现成），误判源头是无人使用的 options 死参数；误插的重复块当场撤销（StageChain
对 HEAD 零 diff）。真机已见形态：工单卡三态/审批卡/文件卡/休眠横幅；其余形态解析已钉、
现场驱动按需（见证据 §十五矩阵）。

**修复轮（同日 17:00）**：D7 / O3 / D12 已修并真机闭环（重装 16:56:18，
APK `79e55634…`）——D7 见上表；O3 = 输入提示条的手写 `status=="pending"` 尺子改走
`isPendingApproval`（桌面徽标同治，`desktopNavBadges` 签名加 `now`），重装后
「等待你审批工具调用…」消失；D12 = 哑发现提名为 `SweepOnlyDiscoveryEngine` 补 sweep
（与 legacy sweepLoop 同判据同节奏），重演 SIGSTOP：t=100s `online=False`、头部翻
「离线」、休眠横幅出现，SIGCONT 12s 恢复，全程 closeCount 0。钉子
`ApprovalPendingRulerGuardTest` +2 / `LegacyDiscoveryVisibilityTest` +2（含阈值反向探针）。
D13（出站卡不收敛）追踪完毕本轮不修：出站 Completed 只由收端 TCP 拉取驱动（`Served`），
三条 ARTIFACT offer 的「信道登记等推送 vs push=false 等拉取」互等形态需要 transferId
级追踪，独立一轮。全文读数见修复轮证据 §十四。

**L1 量具缺口**：四个 e2e 夹具对 luciola 手机握手全灭（legacy 明文 entry 静默消失），
对桌面可通（桌面保留 legacy 应答）——夹具 luciola 化列下一轮。**F1 立案**：luciola/legacy
两端不对称（手机全 luciola、桌面默认 legacy）⇒ 手机连不上旧构建节点与上游 0.61.1 基准，
A18 旧读数不再代表现状，需产品口径后改三份文档。**L0**：8 模块串行 + 10 门禁 +
stability（3 轮全新）+ design 组全绿；design 组三处合修按门禁自述流程记账
（.plg-ico 15px→16px 收敛、旧残树 308 文件清理、dp-debt 账本重生成）。

#### A22 第 3 行 · 11-08 第二轮的阻塞点位移（已修掉的已划掉，新增四条并列成因）

前一轮记的「落默认 codex + `wsId=null` + 药丸不进帧」**本轮不再复现**：PlainChat 分诊生效
（宿主 `[NO_WAKE] TURN_EXHAUSTED`）、wsId 正确到达宿主、驱动解析出 `dsh-cli`。剩下的阻塞是**可见面**：

| 编号 | 缺陷 | 判据（读数） | 成因位置 |
|---|---|---|---|
| D1 | 工单卡落**宿主自己的会话**，永不到手机 | `[CARD] 1:1 direct card persisted locally (conv=46c23456…)` —— 46c23456 是宿主 nodeId；手机会话 id 才是它 | `handleInvokeTask` 把帧里的 `convId`（发送方视角）原样透传，未换成 `ConvId.single(envelope.from)`；且 `AgentCardActions:287` 的 `meta==null` 分支**只本地落库不发帧** |
| D2 | 1:1 受理/终局只写宿主本地库，手机端一字不显 | 宿主库有 `Ticket accepted: executing in teahouse-mobile…` 与 `改动已就绪：1 个文件（+1 −0）`，手机库同时段**零入站行**；result 帧 879 B 已到达手机但 `lease.groupId?.let{}` 对 `null` 直接跳过 | `RelayControllerAgentInvokeLeases:127-147`、`mirrorLeases()` 纯内存 |
| D3 | patch 只投递、**从不合流** | 工作区无 `probe-20261108.txt`、`git status` 干净；`[xfer] state=Failed ×3` | `DesktopPatchMerge.apply` 全仓只有测试调用；1:1 只走 `deliverPatchBundle`，其 `toConvPeerId(46c23456)` = 自己（被 D1 污染） |
| D4 | 宿主库 `BoardCardRev` 表缺失，rev 台账恒失败 | `no such table: BoardCardRev` ×5；`user_version=21` 但 19/20.sqm 均未生效（`WorkspaceBindingEntity`/`AgentGrantRuleEntity` 仍在库） | 202MB 老库的版本号被旧血统占满，新迁移链判定「已在 21」整体跳过（两个 .sqm 同出 `4a968bf3`）。手机库同版本号**有**该表 ⇒ 不是代码漏建 |

另记一条**非缺陷但需产品口径**：1:1 直派只认节点级 `scope=agent` grant，而手机端没有「授权这台机
执行该 agent」的入口 —— 首次派单必被 `DENIED`（`agent-grants.json` 的 `requests` 留
`reason:"direct invoke"`），且被拒后手机端同样无提示（与 D2 同源）。

#### A22 第 3 行 · 11-08 第三轮：D1/D2/D3/D5 全部闭合，第 3 行反填为 ✅

上表四条修掉三条、追加一条、并新露两条。判据一律看**落库时刻与工作区文件**，不看信封 ts：

| 编号 | 现状 | 读数 |
|---|---|---|
| D1 卡片落宿主自己的会话 | **已修** | `handleInvokeTask` 的 `convId` 改用 `ConvId.single(envelope.from)`；`AgentCardActions` 的 `meta==null` 分支补发帧。宿主日志变为 `[CARD] 1:1 direct card sent (conv=25222a45…)` |
| D2 1:1 受理/终局手机端不显 | **已修** | 新增 `sendDirectText(peerId, text, card, envelopeId, senderId)` 作为 1:1 唯一带卡出口；`sayToPeer` 本机落行 + 发帧成对；入站认 `senderId`。手机日志 `[card] inbound card=… rev=3/4` + `slot found existingRev=…` 原位替换 |
| D3 patch 只投递从不合流 | **已修** | 合并改由卡片动作驱动（`HostCardActions` 的 `merge-patch` 分支），产物 `[xfer] Completed`；工作区真落 `probe5-20261108.txt` |
| D5 点「合并」毫无反应（本轮新查） | **已修** | 根因是 **agentId 大小写单向阀门**：`AgentSenderId.of` 强制小写（`agent:<host>:dsh`）→ 手机把它原样带回 → `grants.allows(agentKey="dsh")` 撞台账 `"DSH"` 判假 → 走拒绝分支 → 1:1 拒绝横幅需群、发不出去 ⇒ 静默。修法：`AgentGrants.scopeMatches` 的 AGENT/WORKSPACE 改用 `agentKeyMatches()`（`equals(ignoreCase=true)`），不放宽任何一档。钉子 `AgentGrantExecutionScopeTest` 6 例，反向探针 1 failed、复原 `shasum` 逐字一致 |
| D4 宿主 `BoardCardRev` 缺表 | **已修（第六轮）** | 不碰迁移血统：新增 `SchemaSelfHeal`，开库后无条件跑 `CREATE TABLE IF NOT EXISTS`（幂等，只补新建表，不改列/不删列/不搬数据），三端在**建完 driver、迁移走完**之后各挂一次；桌面那条 `current >= schema.version` 的提前 return 之前也要挂（D4 现场正是走这一条）。真机：换包后 `user_version` 仍 21、表出现、`no such table` 0 次；派单后 `BoardCardRev` 真有行（`chain-tmw7cjjaa\|6`）。钉子 `SchemaSelfHealTest` 4 例（含「与 20.sqm 逐字比对」防漂移），探针 3 failed / 1 failed |
| D6 宿主重启后旧卡无法合流 | **已修（第四轮）** | 两层成因、两种判据。追查先纠正了归因：这套装置上的 `chain-*` 卡**没有 `chain/*` 分支**（`git branch --list "chain/*"` 为空、`worktree list` 无链），产物是 `artifacts/<taskId>/patch.diff` —— 单棒工单形态。修法：① 真链用 `ChainWorkspaceResolve`（按 `refs/heads/chain/<id>` 反查仓，多命中即不猜）；② 单棒用 `TicketPatchLedger`（`<sandboxRoot>/ticket-patches.tsv`，发卡即落账，与产物同寿命）；③ 翻面/受阻卡投递目标改为「登记簿 → 卡片行 `peerId` → 账簿 `convKey`」，不再过 `groupIdOf`（对 1:1 恒 null）。真机复验：重启宿主（PID 2570，内存登记簿全空）后点旧卡「合并此补丁」→ `patch merged into workspace … (card chain-tmw7ahte3)`，`probe6-20261108.txt` 真落盘、卡面翻成「已合并」 |
| D7 1:1 判定文案显示裸 nodeId | **新增（文案）** | 卡面「已由 25222a45-4b84-4e9a-9870-d13f86b2a8de 处理」；昵称来自群成员表，1:1 没有群 ⇒ fallback。功能无碍 |

修 D5 之前，「点了没反应」曾被归因于「没发帧」；本轮用三条互证纠正了它：手机 `logcat` 有
`type=agent-invoke size=466 acked=true`、宿主 `dedup.log` 有该 `envelopeId`（该表纯入站 —— 宿主自己发出的
id 在里面 0 命中）、而宿主 `messages.log` 与 stdout 双双零新增。**帧到了、被静默拒绝**，才是真形态。

#### 11-08 第四轮：D6 闭合，并纠正一条归因

D6 记的是「重启后合不了」，当时归因为「链卡工作区靠内存登记簿」。本轮先验了一个前提，结果推翻了它：
**这套装置上根本没有 `chain/*` 分支**（`git branch --list` 为空），`chain-<id>` 只是工单卡的命名，
产物只有 `artifacts/<taskId>/patch.diff`。所以修法要覆盖两种形态 —— 分支反查（真链）+
产物账簿（单棒），并且账簿放在 `<sandboxRoot>` 下：**它的寿命必须与产物对齐**，
否则会出现「账簿有记录、补丁早没了」的假可用。

复验判据仍不看日志看文件：`probe6-20261108.txt` 在**重启之后**由一次点击真的落到工作区，
内容 `PROBE-OK6`，卡面同步翻成「已合并」。钉子 10 例（`ChainWorkspaceResolveTest` 5 +
`TicketPatchLedgerTest` 5）+ 装配守卫 1 条，三处都做过反向探针。

| 编号 | 现状 | 读数 |
|---|---|---|
| D7 1:1 判定文案显示裸 nodeId | **已修（修复轮）** | 渲染处对 `card.decidedBy` 过 `resolveNodeIdentity`（与消息署名同源）：self ⇒ 本机昵称、其余走昵称/群昵称/档案名、兜底 8 位短 id。真机：probe9 卡知情行 = 「已由 Pixel 6 处理」（修复前是裸串）。钉子 `SenderIdentityTest` +4 / `DecidedByWiringTest` +3，反向探针 1 failed。self 整句「你已处理」因本机编译环境对新增资源键 Unresolved 暂缓（证据 §十三），displayName 方案等效 |
| D8 药丸选中项不跨 App 重启 | **已修（第五轮）** | 成因在 UI 一行：`effectiveWsId = localActiveWsId ?: availableWorkspaces.firstOrNull()?.wsId`，而 `localActiveWsId` 只有 `rememberSaveable`。修法：`RelaySettings.lastDispatchWsId` 落盘 + 纯函数 `DispatchWorkspaceChoice.resolve`（记住值仍在本机列表才采纳，否则退回第一个），药丸显示与派单入参**共用同一条链**（此前两处各算一次）。真机：选 `teahouse-mobile` → `settings.json` 出现 `"lastDispatchWsId": "ws-e9b18063d183"` → force-stop 重启后药丸仍是它 → 派单落点 `chain-tmw7cab09 → /workspace/src/teahouse-mobile` → 合并后 `probe7-20261108.txt` = `PROBE-OK7`。钉子 `DispatchWorkspaceChoiceTest` 6 例 + `DispatchWorkspaceMemoryWiringTest` 4 例（源码守卫）；「悬空 id 不得采纳」是关键一条 —— 解绑/换机后那个 id 指向本机已不存在的仓 |
| D4 宿主 `BoardCardRev` 缺表 | **已修（第六轮）** | 见上表同名条：`SchemaSelfHeal` 幂等补建，血统不动。真机 `BoardCardRev` 有行 `chain-tmw7cjjaa\|6`，日志 `no such table` 0 次 |


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
- 期望：弹「榫卯需要在同一个 Wi-Fi 下找到其他设备…」。
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
- 多出来的同名一层（`downloads/<文件名>/<文件名>`）**当时被记成「设计」，这一条在
  2026-11-10 被推翻**：对单文件它就是「一个只装着自己的目录」，同名再传会在里面递增
  `(1)(2)`，而卡片「打开」按名字取**最旧**那份 ⇒ 用户点开的是上一轮的旧文件
  （真机现场见 `design/runtime-evidence/prototype-review-live-20261110/FINDING.md`）。
  现在单文件直落 `downloads/<文件名>`；只有文件夹 / 多文件 / 带子目录的传输才建
  `<downloads>/<rootName>/`（`TransferManager.saveDirFor` + `transferNeedsFolder`，
  判据 `core/src/commonTest/.../TransferSaveLayoutTest.kt`）。
  上面这条 iOS 读数（2026-10-07）是**当时的布局**，保留作为历史记录。
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
  「已接收」「已存到 …/downloads/a6-photo.png/a6-photo.png」+「打开」「分享」
  （2026-10-07 的布局；2026-11-10 起单文件直落 `downloads/a6-photo.png`）。
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
  （`shr1` 其后已停止广播：协议侧只有壳，宣告即假承诺，见 `PROTOCOL-COVERAGE.md` §五 ——
  上一行的「一律不声明」名单从此对 `shr1` 也成立。）
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
| 3 | 在桌面端会话里找到那张报价卡，点「接收」 | 卡片从「等你接收」变成「已接收」，并显示「已存到 …/relay/downloads/d6b.png」（2026-11-10 前是 `downloads/d6b.png/d6b.png`：单文件被套进同名目录，已修） |
| 4 | 看卡片底部是否出现两个按钮 | **「打开」与「分享」两个 `TextButton` 都在**。只有一个或一个都没有 ⇒ 平台没给出 `receivedFileActions`（`null` 时界面**故意不画**按钮，见 `PlatformContracts.kt`），要回头查 `DesktopPlatform.receivedFileActions` |
| 5 | 点「打开」 | 系统默认看图应用打开，画面就是那张绿色渐变图、白字 `D6b desktop`。**打不开时它会退到「分享」而不是报错** —— 若直接弹了 Finder，说明这台机器没有该类型的默认应用（`Desktop.open` 抛了），也是契约内的行为，但要记下来 |
| 6 | 点「分享」 | Finder 打开并**选中** `d6b.png` 本身（`Desktop.browseFileDirectory`）。只打开所在目录、没选中文件 ⇒ 退化到了 `browseDirectory` 那条兜底路径，记下来 |
| 7 | 两个动作都点完之后 `pgrep -fl 'app.relay.desktop.MainKt'` | 进程仍在。契约写明实现**不许抛异常**，做不到就什么都不发生并自己留痕（桌面没有日志框架，诊断走 `println("relay: …")`，见 `DesktopReceivedFileActions.TAG`） |
| 8 | 核对落盘副本：`shasum -a 256 /tmp/d6b.png <dataDirectory>/downloads/d6b.png` | 两个 sha256 相同。单文件直落下载根；只有文件夹 / 多文件才按传输名建目录（见 `TransferManager.saveDirFor`）。同名再传会落成 `d6b(1).png`，而卡片「打开」指向**本轮**那份 |

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

> **方向变更（2026-11-05，用户明确）：全部验证改走新的 luciola 数据面，legacy 后续必须废弃。**
> 因此本节 10 个 legacy 夹具的读数**自本日起只作历史留档，不再作为验收路径**：它们的前提
> （先关掉加密数据面并重启节点）与该方向直接冲突；本轮 `run_all.py` 也是 `0/10 PASS`
> （`RUNALL_EXIT=2`，本机 Mac→LAN 单向不通导致 `sendto` / `handshake-alive` 建不起来）。
> 真 luciola 链路的读数改看 §5c。后续动作是把夹具迁到 luciola 或直接下线，别再靠关加密面来跑。

这一节记的是**机器夹具**在真机上跑出来的读数，与上面「人点出来的」条目分开记：夹具能覆盖
界面/协议/落盘，覆盖不到权限弹窗与系统出口。夹具与它跑出来的产品缺陷写在
[`docs/plans/2026-10-09-relay-e2e-fixture-program.md`](docs/plans/2026-10-09-relay-e2e-fixture-program.md)。

跑法：`python3 scripts/e2e/run_all.py`（或单跑 `python3 scripts/e2e/e2e_<功能>.py --phone <手机 IP> --host-ip <本机 IP> --secs 40 --tcp-port 179xx`）。

> **这九个夹具演的是 legacy 对端。** 被测端默认开着加密数据面时，legacy 发现不启动、17879/17881/17882
> 也不监听 —— 夹具广播的 `entry` 没人解，读到的是「握手建不起来」而不是功能缺陷。跑之前先在设置里
> 关掉加密数据面并**重启节点**（端口账见 `scripts/e2e/README.md` §2.1）；加密数据面自己的验收在 A21。

**跑前跑后各对一次** `adb -s SERIAL-TEST-01 shell dumpsys package com.kapp.tenon.debug | grep lastUpdateTime`：
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

## 5c. 真 luciola 全链路读数（2026-11-05，HEAD `b9ca96ce`）

**不是进程内回环、不是 `--dry-run`、不是只看截图**：Pixel 6 `<device-serial>`
（`com.kapp.tenon.debug`，`192.0.2.22`）↔ `/Applications/Tenon.app`（`192.0.2.82`），
两端都在 luciola 模式；判据取宿主 stdout + 控制面 HTTP + 手机 `run-as` 磁盘文件 + 真实
`uiautomator dump` / `input tap`。逐项读数、原始日志与复现命令见
[`2026-11-05-tenon-luciola-head-e2e.md`](design/runtime-evidence/2026-11-05-tenon-luciola-head-e2e.md)。

| 判据（怎么做的） | 期望 | 实际 | 结论 |
|---|---|---|---|
| 装置面：`/healthz`、`/v1/peers`、宿主日志、`/proc/net/tcp*` | 宿主 `[luciola] configured/discovered`、手机无 `17879`、`legacyDiscovery=off` | 手机监听 `17878/17880/17888`、宿主 `17878/17880/17888`、配对指纹 `sha256:668c6069…`、`[xport] in trusted=true` | ✅ 通过 |
| `/v1/send` 真文本 → 手机 `messages.log` | `status=sent`、宿主 `acked=true`、手机库出现 marker | 两轮都命中（`LUC-E2E-1793872797` / `…3067`） | ✅ 通过 |
| `/v1/send-file` + **手机端真实点「接收」** | offer 只是登记；点完才推字节 | `accepted` `false→true`、新增 `[luciola] accept-in … push=true files=1` 与 `channel push start` | ✅ 通过 |
| 手机落盘文件 vs 本地 payload | 102400 B、sha256 完全一致 | run2 `88698ad4…`、run3 `6be04973…`，本地/手机两端一致 | ✅ 通过 |
| 六席位 CLI 插件（`/v1/plugin/states`，不是 `/v1/plugins`） | 5 个 CLI + `echo-mock` 全部 installed | `34 PASS / 0 FAIL / 3 NOTE`，两轮 + 重启后复核全绿 | ✅ 通过 |
| 控制面：安装/负控/Zip Slip/HMAC/注册网关 + **真实重启** | settings 与 disabled 跨重启保留 | phase1 `34/36`、phase2 `7/11`；`plugins-state.json` 是空目录，重启丢状态 | ❌ 不通过（同一真实缺陷，见证据 §6.1） |

- **两轮数据面各 `34 PASS / 0 FAIL / 3 NOTE`，退出码 0**；第二轮跑在两次真实宿主重启之后，
  手机端自动重新发起加密通道，链路仍成立。
- **控制面不签 PASS**：6 条 FAIL 全部指向 `PluginStateManager` 把状态文件路径建成目录、
  写盘异常被静默吞掉。HTTP 200 与内存即时生效不能替代磁盘 ground truth。
- 另有两处不掩盖的边界：打包的 `libluciola_uniffi.dylib` 与 0.9.0 绑定的 UniFFI checksum
  不匹配（`10/71`，本轮用运行时 `TENON_LUCIOLA_NATIVE` 覆盖，全新安装仍会崩）；
  这台 Mac 的 Mac→LAN 主动发起不通（`errno 65`），宿主外连持续 `retrying`，链路靠手机主动建连。
- A21 表里 ≥50MB 双向、错误配对密钥、陌生连接 30s 断连、legacy 拉取回退、物理人交互项
  **本轮没跑**，不要拿本节反填 A21 或 §2 的空白项。

## 5d. 卡片圆角/描边全量 review（2026-11-05，HEAD `b9ca96ce`，**本轮改了产品源码**）

用户先报「文件卡只有左上角是圆的」，随后要求「不只是这一个卡片问题，全部 review 一轮并修复」。
根因是 M3 1.9.0 `Surface` 的私有 `surface()` 在 modifier 链末尾**无条件** `.clip(shape)`：
即使外层气泡 `color=Transparent`、`border=null`，仍会把内层 `RelayShapes.medium`(12dp)
卡片的描边裁成自己的 8/16dp 非对称圆角。全量审计与逐项读数见
[`2026-11-05-ui-shape-full-review.md`](design/runtime-evidence/2026-11-05-ui-shape-full-review.md)，
截图见 [`2026-11-05-ui-shape/`](design/runtime-evidence/2026-11-05-ui-shape/)。

| 判据 | 实际 | 结论 |
|---|---|---|
| 全生产源码形状契约 | 222 个 `.kt`、23 处带边框卡片，0 处漏 `shape`、0 处 `shape`/`clip` 或 shadow 形状不一致 | ✅ 通过 |
| 三处确认缺陷（文件卡外层裁剪、`ScreenHistoryCard` 直角、自定义卡引用卡裁弧） | 全部修复 + 4 条纯函数单测 + 1 个全仓源码门禁；删掉修复的变异测试能当场报出文件/行号 | ✅ 通过 |
| 全量测试 + 设计门禁 + 截图门禁 | `BUILD SUCCESSFUL`，5018 tests / 0 failures / 0 errors，`:androidApp:validateDebugScreenshotTest` 通过 | ✅ 通过 |
| 真机 APK 与源码一致性 | 手机 `base.apk` sha256 `5dc6e501…` = 本地 debug APK；APK 构建晚于最后一处源码改动 | ✅ 通过 |
| 真 luciola 宿主上新 offer 的文件卡 + 四屏巡检 | `/healthz` OK、`legacyDiscovery=off`；文件卡四角 2× 放大无断口，`文件柜/节点/工作区/设置` 无新缺口 | ✅ 通过 |

- **边界**：引用卡 + 自定义卡、`ScreenHistoryCard` 没有单独真机帧（前者靠源码推理 + 单测，
  后者靠 Compose 渲染帧）；桌面专属屏与深色主题未进真机巡检。
- `designContractSelfTest` 当时在 pristine HEAD 上就红（两个工具 self-test），
  **已在 §5e 修好并纳入全量门禁**，不再是不计入项。
- 本节只覆盖 UI 形状契约，**不改变** §5c 的两个未修真实缺陷结论，也不要拿它反填 A17 / A21。

## 5e. 修完三个缺陷后的重跑（2026-11-05，HEAD `b9ca96ce`，**本轮改了产品源码**）

用户要求「把之前的问题都修复后，重新跑验证」。修的是三件：`PluginStateManager`
持久化、打包 native 的 UniFFI 契约、两个设计工具 self-test。逐项读数与命令见
[`2026-11-05-rerun-after-fixes.md`](design/runtime-evidence/2026-11-05-rerun-after-fixes.md)。

| 判据 | 实际 | 结论 |
|---|---|---|
| 数据面 R2（重启前宿主） | `34 PASS / 0 FAIL / 4 NOTE`，真手指点「接收」→ 落盘 sha256 一致 | ✅ 通过 |
| 数据面 R3（**真进程重启后**） | `34 PASS / 0 FAIL / 4 NOTE`，同上 | ✅ 通过 |
| 控制面 phase1 | `36/36`（修复前 `34/2`），B10/B13 磁盘断言转绿 | ✅ 通过 |
| 控制面 phase2（**跨进程重启**） | `11/11`（修复前 `7/4`）；冷启动日志 `[RELAY-PLUGIN] skipped e2e-verify-plugin: disabled by state manager` | ✅ 通过 |
| 全量测试 + 设计门禁 + 截图门禁 | `BUILD SUCCESSFUL`，`tests=5021 failures=0 errors=0 skipped=1` | ✅ 通过 |
| 打包 native UniFFI 契约 | 已装 `.app` 内 dylib `71 checked, 0 mismatch`，contract 29；冷启动 0 次 mismatch | ✅ 通过 |
| APK 与源码一致性 | 构建 61,810,057 B / `5dc6e501…`，手机 `base.apk` 同 sha256 | ✅ 通过 |
| **收端文件卡状态推进** | 字节落盘 + `accepted=true`，卡面仍「等你接收」、仍给「接收/拒绝」 | ❌ **新发现，未修** |
| **8MB 单文件推送** | `.part` 卡在 `2,097,152` B（= 32×64KiB 一个窗口），20s 不动 | ❌ **新发现，未修** |

- R1 的 4 条 FAIL 是**夹具问题**，不是产品问题：重装 APK 后应用停在会话列表，
  旧夹具假定已在聊天页。已改为真 UI 导航（消息 tab → 搜索 → 联系人 → 上滑），
  并把「接收」绑定到本次文件名所在卡片。R1 的 offer 帧在 `messages.log` 里本来就到了。
- 两个新缺陷都**不是**本轮三个修复引入的：它们都在收端传输/卡面链路上，
  本轮没碰那条链路。`TransferChannelCompletionTest` 的单测是绿的，真机仍然卡住 ——
  这正是「代码写完了不等于验证过了」的现场。
- 缺陷 A/B 的根因**尚未定位**（`replaceMessage` 找不到 id 时静默返回；发端 ACK 窗口
  无收尾日志），需要先补可观测性再改，本轮**不猜着改**，也不把它们签成通过。

## 5f. 缺陷 A/B 的根因与修复（2026-11-05，HEAD `b9ca96ce`，**本轮改了产品源码**）

§5e 里那两个「新发现，未修」的缺陷，本轮定位到根因、修好、并在真机上复验。
逐项读数与复现命令见
[`2026-11-05-defect-a-b-c-fix.md`](design/runtime-evidence/2026-11-05-defect-a-b-c-fix.md)。

| 判据 | 实际 | 结论 |
|---|---|---|
| **A** 收端文件卡状态推进 | 点「接收」后 **2s 内**卡面原地变「已接收 / 已存到 … / 打开」；修复前 `+2/+4/+8s` 全是「等你接收」 | ✅ 通过 |
| **B** 8MB 单文件 | `total=8388608 windows=4 timeouts=0 chunksSent=128 ackAdvanceSum=8388608`，`stall` 0 行 | ✅ 通过 |
| **B** 50MB 单文件 | `total=52428800 windows=25 timeouts=0 chunksSent=800`，点「接收」→「已接收」5.7s，两端 sha256 `d2fe8cdd…` 相等 | ✅ 通过 |
| **C** `/v1/transfers` 的 `accepted` | 传完之后仍为 `true`（修复前读成 `false`，与「没被接受」无法区分） | ✅ 通过 |
| 数据面（最终产物） | `34 PASS / 0 FAIL / 4 NOTE`，marker `LUC-E2E-1793886682`，`C4d accepted=true` | ✅ 通过 |
| 控制面 phase1 | `36/36` | ✅ 通过 |
| 控制面 phase2（**真进程重启**） | `11/11`；冷启动 `skipped e2e-verify-plugin: disabled by state manager` | ✅ 通过 |
| 九模块测试（AGENTS §1 口径，串行） | `BUILD SUCCESSFUL`，`tests=3745 failures=0 errors=0`（core 898 / app 845 / composeApp 704 / agentNode 432 / composeApp-android 378 / desktopApp 366 / orchestration 96 / agent-contract 19 / cli 7） | ✅ 通过 |
| 十条门禁 + 未用 import | 十条全部执行（非 UP-TO-DATE）`BUILD SUCCESSFUL`；`unused-imports --check` → `OK` | ✅ 通过 |
| 七条设计门禁 + 截图门禁 | 七条全部执行 `BUILD SUCCESSFUL`；`:androidApp:validateDebugScreenshotTest` `tests=306 failures=0` | ✅ 通过 |
| 打包 native UniFFI 契约 | `71 checked, 0 mismatch`，contract 29；冷启动 0 次 mismatch | ✅ 通过 |
| APK 与源码一致性 | 构建 61,810,057 B / `7072e9c8…`，手机 `base.apk` 同 sha256 | ✅ 通过 |

根因各一句（细节见证据文档）：

- **A**：`ChatMessageList` 行判定的 `remember` 键只列了 `id/renderCard/text`，而传输推进
  变的是 `message.file` ⇒ 缓存永远命中、`Delegate` 一直抱着旧 `MessageUi`。键改成整条
  `message`。守卫 `RowDecisionCacheKeyCoversMessageTest`（旧键下 2 条红）。
- **B**：`SecureChannelTransport` 的入站二进制帧闸门只认 `TDCH`（数据块），不认 `TDCA`
  （窗口确认）⇒ ACK 在进路由器之前被当 Envelope 解、失败即静默丢弃。判据收敛成
  `FileChannelRouter.isChannelFrame`（两族都认），闸门复用它。回环测试
  `ChannelFileTransferE2ETest` 直接把两个路由器对接、**绕过这道闸门**，所以一直是绿的。
  守卫 `FileChannelFrameGateTest` + `DataplaneGateUsesTheRouterPredicateTest`（旧闸门下 2 条红）。
- **C**：`accepted` 现算自登记表 `outgoing`，而那张表在传完后被摘掉 ⇒ 最成功的路径反而
  读成「没被接受」。修法是把「被接受」记进诊断账本（单调事实），读时取「账本 ∪ 活值」。
  这条是**修好 B 之后才暴露**的：以前传不完，表里那条一直在。

顺带修掉一个**一直红着但没被发现**的夹具漂移：`previewSlicesOf` 的 `NodeSlice` 漏了
`selfNodeId`（它是 `RelayUiState` 的派生属性 `get() = settings.nodeId`），
`PreviewSliceFixtureEquivalenceTest` 因此长期失败 —— 该任务在输入没变时是 UP-TO-DATE，
本轮改了 `app/core/composeApp` 才让它重跑。

跑门禁时撞到三件事，都不是产品缺陷，但都改变了「这批能不能交」的读数，记在这里：

- **`ChatReviewGuardTest` 被自己的修复顶红**。它断言 `resolveRow` 的 `remember` 键里
  含字符串 `renderCard`；缺陷 A 的修法是把键换成整条 `message`，于是这条红了。
  处理方式是**把判据收紧**而不是放松：现在要求「键覆盖整条消息」——要么直接放
  `message`（data class 结构相等，字段全覆盖），要么逐字段列全且 `renderCard` 与
  `message.file` 一个不少。回退到 `remember(message.id, message.renderCard, message.text, …)`
  实测 3 条红（含 `message.file` 那条），改回即绿。
- **`prototype/relay.html` + `shots-manifest.json` 的未提交改动被回退**。它让两条门禁红：
  `designConvergeGate`（spacing 取值 38→39、font-size 17→18，棘轮只许收敛）、
  `designScreenDiffGate`（基线记于稿子 `bfdc8f40`，改动后是 `3c022b51`）。而且改后的
  取值与实现相反（`--bubble-peer` 改 `#FFFFFF`，而 Compose 侧 peer 气泡是
  `surfaceVariant = #F0EFE9`；`--r-bubble` 改 18px，而 `chatBubbleShape` 是 16dp）。
  **回退后两条门禁即绿**（没有重投影基线）。
- **两条 `HEAD` 上就红的既有项**（与本轮改动无关，已用纯净工作树复现）：
  ① `:core:compileCommonMainKotlinMetadata` 133 条 `Unresolved reference 'github'`
  （`luciola` 只声明在 `jvmMain/androidMain/iosMain`，`commonMain` 元数据编译永远解析不到）；
  ② `python3 prototype/shoot-relay.py --verify-only` 116/116 帧全红（manifest 记的稿子是
  `5c9adeb8`，`HEAD` 的稿子是 `bfdc8f40`），而 CI 在
  `.github/workflows/ci.yml:153` 就跑这一步。②**没有修**：本机 Chrome 156 对当前稿子
  截图恒为白页（`--dump-dom` 里屏内容完整、`check-contrast.mjs` 168 次加载 0 发现），
  此时重拍只会把白图写进 manifest，比过期更糟。

仍未覆盖、不许当成通过：卡片 `Active` 中间态视觉、文件夹/续传/拒收/撤回在 luciola 推送
路径上的表现、公网 TLS+pin、跨网文件/投屏、群聊文件卡、iOS 全部。
~~另有一条**只记录未修**的观察：收到的文件落在 `downloads/<文件名>/<文件名>`~~ ——
**2026-11-10 已修**（同日真机抓到「点开的是上一轮那份」）：单文件直落下载根，
`(1)(2)` 去重仍然生效，卡片入口改为「卡之后最早落盘的那一份」而不是「整条会话第一条
同名文件」。判据：`TransferSaveLayoutTest`（布局）+ `PrototypeReviewEntryTest`（入口解析）
+ a2r 的 `prototype-artifact-bytes-landed`（sha256 逐字节）。

## 5g. 触发式编排与插件分支的静态/单测读数（2026-11-09，HEAD `ccdc5177`，**不是真机验收**）

分支 `feat/agent-trigger-plugin`（ahead 15 / behind 0）跑完 `AGENTS.md §1` 全量清单，
加了 `--rerun-tasks` 保证九个测试任务真的执行（`97 executed / 97 actionable`，7m44s）：
**3871 tests / 0 failures / 0 errors / 36 skipped**（663 份 XML，全部由本轮写出）；
11 条门禁 + `unused-imports.py --check` + `check-design-palette.py --css-vars` 全绿；
改了并发用例与测试替身，另跑 `:testStabilityGate` 3/3 PASS（6m12s）。

本轮修掉的四个真实红项/债：恒真观测窗探针、`DesktopHostSchedulerTest` 的 12 处 B 类
固定 `delay`（`envDependentTestGate` 从 B=12 降到 0，**没加白名单**）、分支回退的
软链接安全清理（恢复 `walkFileTree()` 全量清空 + 两条嵌套挂载回归钉子）、规格文档与
`AGENTS.md §2.8` 的过时表述。

**证据**：[`design/runtime-evidence/2026-11-09-agent-trigger-plugin-verify.md`](design/runtime-evidence/2026-11-09-agent-trigger-plugin-verify.md)。

**边界（不许反填进上面任何表格）**：本轮没有真手机、真桌面宿主、真网络帧、手机 `run-as`
落盘、真实 UI 动作；`verifyLuciolaNative` / `verifyPackagedLuciolaNative` 是
**SKIPPED，日志明说「这不是通过」**；36 条 skipped 含「luciola 原生库未找到」的跳过用例。
单测绿 ≠ 真机通过，三阶段也不因此变成「已交付」。合并前共有 10 个文件与主工作区未提交
改动重叠（含 `ACCEPTANCE.md` / `AGENTS.md` / `RelayDatabase.sq` 的 21.sqm → schema 22
迁移链）。对主工作区脏状态做 `git merge-tree` 干跑后确认：干净 `main` 零冲突，
**带未提交改动时恰好 4 个冲突**（`WorktreeSandbox.kt`、其测试、规格文档 add/add、
`THIRD-PARTY-AGENT-DEVELOPMENT-KIT.md` 的产品命名），裁决建议与自动合并的语义风险
见证据文件 §6。本轮没有 merge / push。

> **复核（2026-11-09 17:20，`main` 已前进到 `a74b303e`）——上一段的「干净 `main` 零冲突」已失效。**
>
> 那是 HEAD `ccdc5177` 时点的读数。此后 `main` 前进 31 条提交（本分支 ahead 28 / behind 31），
> 对**已提交的 HEAD**（不含工作区未提交改动）重跑
> `git merge-tree --write-tree --messages main HEAD`，得到 **5 个冲突**：
> `AGENTS.md`、`agentNode/.../drivers/WorktreeSandbox.kt`、
> `agentNode/.../drivers/WorktreeSandboxExecutorTest.kt`、
> `docs/AGENT-TRIGGER-AND-PLUGIN-SPECIFICATION.md`（add/add）、
> `docs/THIRD-PARTY-AGENT-DEVELOPMENT-KIT.md`。
> 合并前必须逐条裁决，**不能沿用「零冲突」这个结论**；工作区另有未提交修复，实际合并面比这更大。
> 本轮仍未 merge / push。

## 5h. 代码评审修复轮（2026-11-09，基线 `2e63e154`，**已提交 6 条 / 未推送**）

对 `main..HEAD`（55 文件 / +4960−77）做了一轮整体代码评审，产出 1 个 P0 + 14 个 P1，
随后逐条修复并落成 6 条提交（`0a7341d6` 沙箱 → `15d4461d` webhook → `c6974f52` token
断言 → `5ecea2b8` 路由/指纹 → `751aab67` 台账/调度器 → 本条文档）。改动面
**21 个文件 `+1171 / −119`**（含 2 个新文件：`CommonMainJvmApiGuardTest.kt` 与本轮证据
文件）。分支因此在 `main` 之上 **25 条**。

修掉的主要面（细节、反向钉子与「放回旧行为会红」的实跑结果见证据文件）：

- **P0 iOS 编不过**：补 `iosMain` 的 `createSchedulerStore` actual；`commonMain` 里的
  `System::currentTimeMillis` 改用 `com.kapp.tenon.core.nowMillis()`。因为 CI 从不编 iOS，
  另加了一条能在 JVM 上跑的 `CommonMainJvmApiGuardTest`（剥注释后扫 `app/src/commonMain`
  的 JVM 专属 API，含反向验证与「注释不算违规」两条）。
- **事件永久丢失 / 双派**：条件 UPDATE 原子认领（`WHERE state='pending'` + 同事务
  `changes()`）、`updateEventState` 改为返回 `Boolean`、启动时崩溃恢复遗留 `running` 行、
  先拿许可再认领、许可挂 `invokeOnCompletion`、单轮失败不杀循环、取消不再误记 FAILED。
- **同一窗口重复执行**：`nextRunAt` 用枚举出的窗口而不是 `nextAfter(now)`（负偏移下会
  派两次）、先落账再派发、`NEVER_FIRES` 哨兵取代 `now + 1 天` 的假派发、派发许可前移 +
  `withTimeout`、扫描异常不再静默吞。
- **沙箱产物泄漏**：seatbelt profile 移出 worktree（否则被 `git add -A` 收进 `patch.diff`）；
  `deleteArtifactsSafely` 改为对**整个 `artifactsDir`** 做一次 NOFOLLOW 遍历 —— 旧写法只清
  `.sandbox-home`、末尾仍调 `deleteRecursively()`，对目录里其它链接毫无防护（实测放回去，
  宿主的 `host-secret.txt` 真的被删）。
- **webhook 入站**：请求体有界读取（8 MiB → 413，旧实现是无上限 `readBytes()`，等于
  一次不需要密钥的 OOM）；幂等键按 `sourceId` 分域（旧实现会让一个源静默吞掉另一个源的事件）。
- **静默覆盖**：`TriggerRegistry.register` 冲突记日志、先注册者胜；对账指纹改用整份清单
  `toString()` 并按 pluginId 排序（旧指纹漏 schedule / prompt / filter / targetConvId，
  且随枚举顺序抖动而白跑对账）。
- **测试假绿**：`runCatching` 吞掉的 0600 断言、恒真的 ACK 断言、全文 `contains` 的结构守卫。

读数（本轮实跑，全量串行）：`BUILD SUCCESSFUL 4m14s`（82 actionable / 13 executed /
69 up-to-date，未受影响模块复用上次产物）；665 份 XML → **3892 tests / 0 failures /
0 errors / 36 skipped**；受影响的 11 个测试类共 **118 条，0 失败**；十条门禁 + 两项
Python 检查全绿（`cjkLiteralGate` 与 `envDependentTestGate` 本轮**先红后修**，均未下调
基线、未加白名单：前者 93→72=基线，后者 A/B 类 1→0）；`:testStabilityGate` 3/3 PASS
（`BUILD SUCCESSFUL 6m43s`）。

**边界**：iOS 目标**仍未真正编译过** —— `:app:compileCommonMainKotlinMetadata` 在本机失败在
依赖解析（`Could not find luciola-iossimulatorarm64-0.9.0-metadata.jar`），不是代码；
所以 P0 的修法是「代码层确定正确 + JVM 侧守卫可跑」，不是端到端编译证明。
另有三件**明确未修**并已写进规格 §0：只读通道的拦截仍是事后的、
`hookSessionId` 不与已知会话对账（需真机确认会话登记时机）、
`ProcessAgentDriver` 的单行熔断在整行分配之后。
本轮**没有** merge / push / 重写历史；`AGENTS.md §7.0` 四条推送前自检已实跑，读数见证据
文件 §11.2。

**证据**：[`design/runtime-evidence/2026-11-09-agent-trigger-plugin-review-fixes.md`](design/runtime-evidence/2026-11-09-agent-trigger-plugin-review-fixes.md)。

---

## 5i. §8 三件的收尾（2026-11-09，基线 `75ee6e4e`，**代码级 + 单测级，不是真机验收**）

`§5h` 结尾如实留了三件未修。本轮三件都做了，各一条提交：

| 提交 | 修的是什么 | 根因（不是「判据写错了」） |
|---|---|---|
| `dfa7a46b` | 行上限改在**读取方式**上生效 | `readLine` / `useLines` 对行长零限制，判据生效时整行已在堆里 |
| `29b87cbf` | hook 审批的 `sessionId` 先**对账** | 判据是「它存在」，而 `ensureSession` 会给乱序帧造占位行 —— 存在 ≠ 本节点派过 |
| 本轮 | 只读通道改成**执行前**否决 | 判据挂在 `tool.started` 上，而那是个「已经开始」的通知，没有拒绝这个动作 |

第三条的做法：`TriggerExecutor` 把 `CognitiveFlow.allowsTool` 作为纯函数交给
`CliRunRequest.toolPolicy` → `executeCliTask` 在起执行体**之前**登记到
`ControlPlane.readOnlyToolPolicies`（键 = 派单会话，`finally` 必清）→ hook 审批路由在
**免批表之前**查它，不放行就 `deny`（不弹卡）→ 收场判负（越界即失败卡，拦住了也算越界）。
没装 hook 的驱动只剩事后判负，所以 `executePluginTask` **不收** `toolPolicy`。

> **补记（2026-11-09 17:20）**：上面这条链路当时**缺一段接线**，`toolPolicy` 实际上否决不了。
> `CliSubprocessDriver` 没有把派单会话 id 注入子进程环境（`TENON_HOOK_SESSION_ID`），
> 真 CLI 的 hook 拿不到自己的 sessionId ⇒ `hookSessionTrusted` 为假 ⇒ 审批路由整段跳过
> 「查策略表」这一步，回退到 `agy-approval` 键。也就是说：单测里探针带着 sessionId 直接
> POST 时是对的，**真链路是空的**。现已补上注入（含「环境变量覆盖不得顶掉宿主值」的优先级
> 钉子），`SandboxedCliDriver` 的裸路径也补了端到端 seam 用例。补完之后 §5i 第三行的结论才成立。

读数（本轮实跑）：全量串行（`--continue --max-workers=1`）`BUILD SUCCESSFUL 4m10s`
（82 actionable / 12 executed / 70 up-to-date）→ 667 份 XML、**3915 tests / 0 failures /
0 errors / 36 skipped**（与上一轮 3892 的差额逐项对得上：+10 有界读取、+2 对账、+11 本轮）；
`:agentNode:test` 67 份 XML → **517 / 0 / 0 / 1 skipped**；`:orchestration:test` 28 份 XML →
**155 / 0 / 0**；十条门禁 + 两项 Python 检查全绿（`envDependentTestGate` 本轮**先红后修**：
只读用例第一版用「起真脚本 + 轮询策略表」，被判 B 类，改成探针驱动后 626 文件 / A/B 0，
未加白名单）。反向验证五次（退整块否决 / 退顺序 / 退判负 / 退 `finally` 清理 / 退
`toolPolicy` 传递），每次都只红对应用例，读数见证据文件 §12.3。

**仍未验（不要当成已验）**：装了 hook 的**真** CLI 在只读派单里被拒 —— 单测里那次
PreToolUse 是探针驱动发的，不是真 CLI 发的。这条只能真机验，列为待验项。
**仍存的边界**：`agy-approval` 回退键全局共享，并发审批会撞同一个会话键（规格 §0 已记）。

**`:testStabilityGate` 本轮 1/3 轮红**，红在 `:app:jvmTest` 的
`GroupDissolvePropagationTest`（`awaitUntil` 超时）。**与本轮改动无关**：`app` 只依赖
`:core`（测试侧 `:testsupport`），不依赖 `:agentNode` / `:orchestration`，`:app:jvmTest`
的产物逐字节相同；单跑该类 5/5 全绿。根因嫌疑是 `freePort()` 探 TCP 空闲端口却当 UDP
端口用（`tcpPort = udpPort + 1`）的 TOCTOU 窗口 —— 独立的测试基建缺陷，未顺手改（那是
另一件事）。**不要把这一轮说成「稳定性门禁全绿」。**

**证据**：同一份证据文件 §12。

---

## 5j. 第三轮评审后的修复（2026-11-09，基线 `b335f483`，**未提交 / 未推送**，不是真机验收）

对 `b335f483` 再 review 一轮，找出**两条推翻 §5i 结论的 P0** 与一条静默丢失链：

| 级别 | 缺陷 | 后果 |
|---|---|---|
| P0① | `CliSubprocessDriver` 没注入 `TENON_HOOK_SESSION_ID` | 只读通道的「执行前否决」在真实派单上**整段被跳过**，§5i 第三行当时并不成立 |
| P0② | `SKIP` 策略连**当期自然窗口**都不派发 | 配了 SKIP 的 cron 永不触发，且零日志（全仓 `"SKIP"` 零用例） |
| P1 | webhook 先 ACK 后落盘 / 幂等表先记账 / 持久键未按源分域 / drain 许可泄漏 / 对账无异常隔离 / 围栏可提前闭合 / 单行熔断漏杀子进程 | 事件可永久丢失、消费循环停摆、注入正文能伪装成宿主指令 |
| P2 | 路由表重建有空表窗口 / 绑定表无界 / 占位符常量两份 / cron 视野按迭代次数 | 已落盘事件被判「无路由」、信任表无界增长、8 年后的解被当成 5 年内 |

读数：全量串行强制 `--rerun-tasks` → `BUILD SUCCESSFUL 7m39s`（97 executed / 97 actionable）
→ 667 份 XML、**3945 tests / 0 failures / 0 errors / 36 skipped**（较上一轮 +30，逐项对得上）；
11 条门禁 + `unused-imports.py --check` + `check-design-palette.py --css-vars` 全绿。
每条新守卫都做过变异检查（退掉修法即红，见证据 §6），改动已全部回退，无残留标记。

**边界**：装了 hook 的**真** CLI 在只读派单里被拒，仍然**没有真机证据**（单测里那次
PreToolUse 是探针发的）；`verifyLuciolaNative` / `verifyPackagedLuciolaNative` 仍是 SKIPPED，
日志明说「这不是通过」。本轮没有 merge / push / 提交。

**合并面变化**：`main` 已到 `a74b303e`，对已提交 HEAD 干跑 `git merge-tree` 为 **5 个冲突**
（见 §5g 的复核说明）。

**证据**：[`design/runtime-evidence/2026-11-09-agent-trigger-plugin-review-round3-fixes.md`](design/runtime-evidence/2026-11-09-agent-trigger-plugin-review-round3-fixes.md)。

---

## 5k. 合并进 main（2026-11-09，`6de4b13e` ← `feat/agent-trigger-plugin`，**未推送**）

`main` 已前进到 `6de4b13e`（§5j 时是 `a74b303e`，§5g 时是 `ccdc5177`）。以 `6de4b13e`
为基准重跑 `git merge-tree`：**5 个冲突**，逐个裁决后并入。

| 冲突 | 裁决 | 依据 |
|---|---|---|
| `AGENTS.md` §2.8（2 处） | 取分支侧 | 分支侧是整树 `walkFileTree` + 全程不调 `deleteRecursively()`；main 侧只清 `.sandbox-home`、末尾仍调，对 `.sandbox-home` 之外的链接无防护 |
| `WorktreeSandbox.kt` | 取分支侧 | 同一函数的中间版 vs 终版；main 侧无独立语义 |
| `WorktreeSandboxExecutorTest.kt`（3 处） | 取分支侧 | 超集：断言更具体，且多一条 `...DoesNotFollowStraySymlinksOutsideSandboxHome()`（旧实现能过原用例，只在这条上红） |
| `docs/AGENT-TRIGGER-AND-PLUGIN-SPECIFICATION.md`（add/add） | 取分支侧 | 章节结构逐条一致，差异全是事实口径：main 侧写的是落地前现状 |
| `docs/THIRD-PARTY-AGENT-DEVELOPMENT-KIT.md` | 取分支侧 | 两边描述**相反**的宿主行为；按合并后代码裁决 —— `PluginManifest.kt` 已是 `ignoreUnknownKeys = false` 并给出 `unknown-field` / `bad-manifest` |

**合并暴露的语义冲突（git 看不见，已单独一条修）**：main 的桌面迁移入口用
`card_payload` 哨兵把起点直接抬到 `schemaVersion`（`resolveMigrateFrom` 返回
`schemaVersion`）。分支把版本从 21 抬到 22 后，任何已到 19+ 的老桌面库
`from = max(21, 22) = 22` ⇒ `migrate()` 一次都不调 ⇒ **`21.sqm` 整段被跳过**，
`TriggerEvent` / `SchedulerTaskState` 永远建不出来。症状不是启动报错，是每条 webhook
都落不了库（`no such table` → ACK 转 `503 persist-failed`，发送方永远重试）。
按 D4 立的规矩把两张表纳入 `SchemaSelfHeal`，并补漂移守卫（`21.sqm` 三条语句逐条
做归一化包含比对）。复现用例在补自愈**之前**是红的（`5 tests completed, 1 failed`）。

读数（本机实跑，合并后 HEAD `cee24e12`）：全量串行强制 `--rerun-tasks`
→ `BUILD SUCCESSFUL 7m51s`（99 executed / 99 actionable）→ 696 份 XML、
**4115 tests / 0 failures / 0 errors / 36 skipped**（比分支单跑多 170 条，是 main
这 31 条带进来的）；11 条门禁全绿；`:testStabilityGate` **3/3 PASS**（6m12s）。

**同轮修掉的门禁漏检**：`tools/unused-imports.py --check` 报
`SourceWebhookRouteTest.kt` 有一条未使用的 `assertNotNull` —— 它在分支上就有，
§5j 那句「全绿」是被管道吃掉的退出码（`python3 … | tail -8; echo $?` 读到的是
`tail` 的状态）。现已删除并改成先重定向再读 `$?`。

**边界**：这是**合并**，不是验收。§6 的边界一条没变 —— 没有真机 / 真宿主 / 真网络帧；
装了 hook 的真 CLI 在只读派单里被拒仍无真机证据；`verifyLuciolaNative` 仍是 SKIPPED。
本轮**没有 push**。

### 5k-1. 推送前脱敏（同日，`7b7f1562` 带进来的真实路径）

§7.0 ① 的人眼闸门过出来一条真的：main 侧 `7b7f1562` 把**本机绝对路径**写进了 4 个文件
（16 处）。按 [`docs/privacy-scan-2026-10-29.md`](docs/privacy-scan-2026-10-29.md) §143
的既定口径替换，不自创写法。下表**不写原值**——把要擦的字符串再抄一遍等于没擦：

| 类别 | 占位 | 处数 |
|---|---|---|
| 本机卷上的仓库绝对路径（`/Volumes/<卷名>/source/teahouse-mobile`） | `/workspace/src/teahouse-mobile` | 14 |
| 同上，另一个工作区 `china-pass` | `/workspace/src/china-pass` | 2 |
| 本机用户目录（`/Users/<用户名>/…`） | `/Users/ci/…` | 3 |
| 真机内网地址（`192.168.2.x`，仅证据文档；代码/测试按 §4 保留） | `192.0.2.22` | 3 |

只改路径与地址字符串，**读数一字未动**（`probe5`–`probe9` 的 `PROBE-OK5…9`、`chain-*`
编号、`wsId` 穿透结论、`regEp` / `state=connected` 的时序全部保持原样 —— 这些是现场
坐标，不是判据）。`/Volumes/demo/workspace` 与 `/Volumes/...` 是本来就假的示例/省略号，
按同一份文档 §3「别误伤」保持不动；`/Users/{me,someone,demo,ci,x,a,developer,work}` 同理。

**真机内网地址的归属说明**：§4 的「代码里保留」只针对**代码/测试**（它是
`isLanAddress` 之类的判据输入），文档与证据一律改 `192.0.2.x`。上一轮复核把它读成
「文档里也保留」是误读，已在
[`design/runtime-evidence/2026-11-09-agent-trigger-plugin-review-fixes.md`](design/runtime-evidence/2026-11-09-agent-trigger-plugin-review-fixes.md)
§11.2 追加更正（原读数保留不改）。

**`ws-e9b18063d183` 有意保留**：它不是秘密 —— origin/main 上已有 13 个文件在用，
包括生产代码 KDoc（`SenderIdentity.kt`、`AgentMemberName.kt`）与 `scripts/run-live-dsh-flow.py`。
单独把它从证据里擦掉，只会让证据和代码对不上，属于「改错了地方」。

**残留（如实说）**：这一步只清了**当前树**与**未推送的增量**。三处残留：

1. `7b7f1562` 的 blob 里仍有路径原值，`git log -S "/Volumes/<卷名>"` 仍能翻到 ——
   一旦推送就进入公开历史；
2. 另有 4 份**早已公开**的证据文档各带一处真机内网地址（`192.168.2.x`）
   （`2026-10-30-m5-acceptance-matrix.md`、`2026-11-03-luciola-090-e2e.md`、
   `2026-11-03-super-agent-midterm.md`、`2026-11-06-agent-capability-full-acceptance.md`）。
   它们在 `origin/main` 上就是那样，改当前树只是把「已公开的事实」从增量里藏起来，
   不解决任何问题，所以**没动**；
3. 代码/测试里的 `192.168.2.x` 按 §4 **有意保留**（是 `isLanAddress` 一类判据的输入）。

要彻底清掉 1、2 得按 §7.4 做全历史重写（`git bundle --all` 备份 + 树哈希证明 +
`--force-with-lease`），而 `7b7f1562` 同时存在于 `feat/team-folder-phase0`，重写会波及
那条线与全部 12 个工作树。**未做，等明确决定。**

---

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
- 外网侧的 IPv6 / 跨出口链路（跨网 WS 隧道方案已退役，见 legacy-cleanup-audit）。

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
- 步骤：`adb shell am broadcast -a com.kapp.tenon.TEST_INJECT_QR --es qr relay://<ip>:<port>`，再 `am start -a android.intent.action.VIEW -d relay://<ip>:<port>`。
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
4. **单卡槽原位刷新**：任务推进时同 `cardId` 的行被**原位替换**（`card_payload` 更新为最新 rev，`card_json` 保持首帧原文，表**行数不涨**）；UI 与两端库的末行同值。真机口径：数「同 cardId 的行数」恒为 1，不再是「新行追加」
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

## 10b. Agent-IM M5 审批面与免批表（2026-10-30）

`always`（「始终允许」）从「宿主有表」到「用户点得到、也收得回」的验收判据。

**本机可验证（已跑，2026-10-30 21:56：`:core:jvmTest` / `:app:jvmTest` / `:composeApp:jvmTest` /
`:agentNode:test` / `:desktopApp:test` 五个模块按用例定向跑，33 秒全绿）**：

1. **回执过线**：`AgentFramesTest.everyApprovalDecisionKindSurvivesTheWire` —— 八个 `kind` 全部
   穿过编解码，白名单外的值仍整帧拒收。（修前 `allow_session`/`revoke_session` 过不了线：
   界面显示已生效、宿主一直 `pending`，而既有测试绿是因为它绕过了编解码。）
2. **出口存在**：`RelayControllerApprovalResponseTest.alwaysDecisionSendsAlwaysAckAndApprovesTheCurrentRequest`
   —— 四态决策里 `ALLOW_ALWAYS` 发的是 `kind=always`，本地记 `approved`（宿主侧的语义是
   「先批掉这一条 + 记一条规则」，本端不发明一个自己不掌握的状态）。反向验证：把 `ackKind`
   改成 `APPROVED` ⇒ 该用例红。
3. **入口出现得有依据**：`AgentApprovalCardsTest.alwaysEntryFollowsTheHostDeclaration` —— 只在
   请求的 `choices` 含 `always` 时画那颗按钮；`choicesAreMatchedAsWordsNotSubstrings` 守
   `notalways` 不算命中。反向验证：改成无条件画 ⇒ 红。
4. **宿主替插件兜底**：`ControlPlaneTest.approvalWithoutPluginChoicesAdvertisesEveryChoiceTheHostCanHonour`
   —— 插件没声明 `choices` 时出站**帧**带四个值；`pluginDeclaredChoicesAreForwardedVerbatim`
   守插件显式声明时不被盖掉。反向验证：去掉兜底 ⇒ 前者红、后者仍绿（判据没互相糊住）。
   判据盯的是帧不是接口返回：填进台账却没填进帧，对端照样只看得到两档。
5. **回收口撤得准**：`ToolApprovalAdminTest`（6 条）+
   `AgentToolApprovalStoreTest.revokeExactlyTellsTheWildcardAgentApartFromAnyAgent` ——
   逐条撤销只删界面上那一行；`Rule.agentKey = null`（覆盖所有 agent）与
   `revoke(agentKey = null)`（不按 agent 过滤）两个相反语义不许混。反向验证：把
   `ToolApprovalAdmin.revoke` 换回 `store.revoke(...)` ⇒
   `revokingTheAllAgentsRowLeavesAgentSpecificRulesAlone` 红，其余 5 条仍绿。
6. **落盘与通知**：撤销后重启不复活；`remember` / `revoke` 都触发变更监听（设置页开着也能
   看到对端刚产生的规则，而不是等下次进页面）。

**真机（已跑，Pixel 6 + 桌面新包）**：桌面宿主 + 手机，八步全通 —— 手机「此工具始终允许」
→ L3 专用确认卡 → 宿主 `agent-tool-approvals.json` 落表 → 同工具再来一条回
`status=auto-approved`（未发帧）→ 换工具照常投递 → 桌面「设置 → 可信设备与配对 →
免批规则」可见该行 → 点撤销后表清空 → 同工具再来一条重新投递、手机端重新出现待批。

两条**必须一起记住**的前提与边界（读数见 `design/runtime-evidence/` 的 M5 验收矩阵 §7.2）：

1. **手机得先是 `owner` 才能批**：`rw` 只够派活，审批行会如实显示「只读（无权审批）」。
   这不是缺陷，是 `canApproveAgentRequest` 的 fail-closed 判据。
2. 造这条场景用的是宿主的 `POST /v1/agent/approval`（仓内没有会发审批的插件），
   走的仍是插件→宿主→手机的同一条帧。

---

## 10c. CLI 沙箱派单（升级轮 T-3b，D-024）

真机/本机怎么验「群里派的活没弄脏宿主仓」：

1. **宿主工作树零变化**：群内 `@agent` 派一条带工作区的任务（该宿主装了任一 CLI）；
   任务运行期间与结束后，宿主仓 `git status` 恒空、`git worktree list` 只剩主工作树。
   判据：`/tmp/tenon-ws/<taskId>/` 存在过（任务期间），结束后不存在。
2. **产物可对账**：`/tmp/tenon-ws/artifacts/<taskId>/` 下有 `patch.diff` / `report.json` /
   `task.log`；`report.json` 的 `patchSha256` 与 `sha256 patch.diff` 一致；report 里
   **没有** task.log 的原文（提纯判据 —— grep task.log 任一行原始输出不得命中 report）。
3. **卡面是摘要**：群流的 diff-review 卡只有统计（文件数 / +A −D），全量 diff 在文件里。
4. **中止无痕**：租约超时（或宿主中止）后，worktree 目录与 `task-<taskId>` 分支都消失；
   `ps` 里没有残留的 CLI 进程。
5. **非 git 工作区大声失败**：派单到非 git 目录的工作区 → 群内收到失败横幅（Error 指向
   沙箱建不出来），**没有**任何「在宿主仓裸跑」的副作用。

本机判据（测试名）：`WorktreeSandboxExecutorTest`（7 用例，反向验证：摘清理/摘校验红）、
`SandboxedCliDriverTest`（3 用例，反向验证：摘出卡/静默裸跑红）。真机项按「设备在线则验」
口径记账。

---

## 10d. 端到端用户旅程（2026-11-01 新增，**唯一一条按用户动作顺序写的验收**）

上面 A/C/D/T 各节都是**单点判据**：一个入口、一个读数、一条断言。它们拼不出「用户到底
经历什么」—— 真实使用是一条连续动作流，中间任何一环断了，后面全都看不到。本节的判据
一律按**用户的六个动作**排序，一次跑完，不许跳步、不许在中间插库查询当"继续"。

**场景**：手机建群拉一个 agent，派一件活，看它跑完，拿到产物。

| # | 用户动作 | 判据（都要在同一次运行里成立） | 读数落点 |
| :-- | :-- | :-- | :-- |
| 1 | 打开群，发 `@agent <任务>`（候选点选，不手打全名） | ≤15 s 气泡变「已送达」；**不许出现「重发」** | 手机库 `delivery=Sent` |
| 2 | —— | 对端在**同一秒**收到同一条文本 | 桌面库同一条 `mine=0` |
| 3 | —— | 发出的帧带 `agentIntent`（agentId / wsId / mode 三项齐全） | `messages.log` 的 out 行 |
| 4 | 等 | 群流出现**同一张**卡片：受理（带「停止」按钮）→ 执行中 → 结论（同 `cardId` 原位刷新，行数恒 1） | 两端 `MessageEntity.card_payload` |
| 5 | 点卡上的「停止」 | ≤5 s 卡片翻「已停止」（不是红色报错）；artifacts 目录清空；CLI 进程无残留 | 目录 + `pgrep` |
| 6 | 再派一件并等完成 | 卡片进「待合并」；产物卡可点开（`patch.diff` / 报告） | 手机同屏可见 |
| 7 | 点「合并此补丁」 | 宿主仓真实落盘；卡片带「已由 <设备> 处理」 | 宿主仓文件 + 卡面 |
| 8 | 再派一件，对同一文件点合并（必然冲突） | 卡片原位翻「受阻 / 没能合并这次改动」，红色横幅给出**可操作的下一步**，合并按钮保留可重试；失败卡必须落在**派单的那个群** | 手机卡面 + 宿主日志 `merge FAILED` |

**2026-11-01 18:28:15–18:28:42 首次全通**（Pixel 6 + 打包宿主）：27 秒内走完 1→4 与 6，
读数见 `design/runtime-evidence/2026-11-01-sdlc-acceptance-weather.md` §10.5。

**2026-11-01 21:42–21:53 第二轮全通（含 7、8 两条新判据）**（Pixel 6 + 打包宿主，
外部 LLM 不可达，CLI 用确定性桩但 worktree / git diff / patch / 卡片广播 / 合并全真）：

- 21:42:08 派单 → 受理卡（横幅「已受理…」+「停止」按钮）→ 21:42:48「待合并」
  （「2 个文件」摘要，无 `+N−N` 噪音、无「第 N 版」徽标）。
- 21:43:00 点合并 → `hello.txt` 落盘、内容为桩写入的新值；卡面带「已由 Pixel 6 处理」。
- 21:43:48 再派一单 → 21:44:38 点合并 → 冲突（`hello.txt: already exists`）→
  卡片**原位**翻「受阻 / 没能合并这次改动」，红色横幅给出「先把它挪开或提交，再一次合并」，
  合并按钮保留。重启 App 后状态不变（`card_payload` rev=5 / blocked）。
- 已知边界：外部 LLM 走不通是网络限制（`api.openai.com` 不可达），不是产品缺陷；
  会话事件流（stop / 错误原文）仍按工作区绑定群转发，与卡片的「派单群」是两条路，
  收敛方案见产品审核报告。
- 产物双路已收口（本轮）：agent 产物改走 `purpose=artifact`，对端**免确认**自动接收
  （≤50MB，超限退回手动领取）—— 文件卡直接显示「已接收 / 打开 / 分享」，不再挂
  「接收/拒绝」；要不要合并只在任务卡上决策一次。真机读数：take8 产物卡
  「已接收 · 已存到 …/downloads/patch.diff」。

**修掉的真缺陷（本轮）**：合并冲突失败卡曾按工作区名猜群广播（`groupsForSession`），
派单群 ≠ 工作区同名群时失败卡发到别的群，点合并的人永远看不到失败 —— 现改为按 cardId
反查卡片所在会话，失败卡回到动作发生的那个群。停止动作从「背关键词 stop」改为卡上
「停止」按钮（`abort-task`），与关键词落到同一个摘进程树动作；用户主动中止不再被域层
补一句「没有完成这次任务」的失败横幅。

**这一节存在的理由**：此前所有"验证"都是先验一点、再换一个入口验下一点，于是最关键的
断点（`@` 胶囊发出的帧没有 `agentIntent`、或本地假失败挂出「重发」）在单点判据里全都
看不出来 —— 每一条都"通过"，用户却从头到尾没见过一次完整流程。

---

## 10e. 团队编制（声明与展示）验收（2026-11-10，**真机已验：夹具 `result PASS 37`**）

> 方案：`docs/team-creation-final-plan.md`（v1 只做声明与展示）。单测与门禁全绿
> （提交 `2f472faa`…`149ea327`）；真机读数与未验项见
> [`design/runtime-evidence/2026-11-10-team-roster-realdevice.md`](design/runtime-evidence/2026-11-10-team-roster-realdevice.md)。
> 验收抓到并当场修复 1 条布局缺陷（徽标窄屏被裁剪，`16d4a0e6`）。

### 领域与广播（单测已钉，真机已复核）

- 夹具（`scripts/e2e/e2e_team_roster.py`，普通成员宿主逐 rev 驱动）：
  **result PASS 37**——主权声明（旧版本会拒收的那条，真机被接受）、群信息「团队 (3)」
  徽标、会话行「团队 3 ▸」药丸、展开 3 组员+角色、点成员行预填 `@<leader>`、
  越预算（9 组员）整帧拒收（无回执 + UI 无变化 + 无静默条目）、关闭后徽标/药丸消失、
  closeCount 全程 0。
- **看落库时刻，不看信封 ts**（AGENTS §3 的老规矩）；广播产物含团队字段已由
  `TeamDeclarationWritePathTest.theBroadcastProductStillCarriesTeamFields` 钉住
  （反向验证：去掉回填 → 红）。



### 成员宿主（依赖合并主权分支）—— 真机已验

- 非群主宿主声明席位 + 团队 → 群主端可见：**已验**（夹具扮演普通成员宿主逐 rev
  驱动，真机被接受并渲染，见 §2.2 读数）。
  **组内有旧版本成员时这条会失败**——旧端仍拒收成员发起的 rev+1，属已知混合版本
  窗口（PROTOCOL-EXTENSION §14.5），不作为缺陷记录（旧端对照需旧构建对端，未验）。
- 旧版本节点作为**管理员**改群名/资料 → 团队声明被静默清空：现状如此（旧端重编码
  不含未知字段），按已知兼容限制记录，不算新缺陷。

### UI 与回归

- 无默认工作区的席位：对话框引导态（开关置灰），不产出悬空团队。
- 多席位：顶部 chip 切换，默认选中 = 代表席位；保存写对目标席位。
- 超过 8 名组员不可再加；组员名撞群内 `@` token 就地指出冲突对象。
- 关闭开关 → 一次 rev+1 广播 → 药丸与成员行消失；`@` 仍指向该 agent。
- 回归：建群/加人/改资料/解散/工作区授权/折叠投影零回归；`closeCount` 不新增抖动。

## 10f. 团队执行（@组员 责任链）验收（2026-11-10 新增，**代码级 + 单测级，真机待验**）

> 方案：`docs/team-execution-final-plan.md` §9。单测与门禁全绿（提交 `db514353`）；
> P1/P2（按成员选模型 / 主备降级）未做——§6.2 逐驱动注入必须真机验证后才开下拉。

### 真机验法（与 v1 §10e 同一台 Pixel 6 + Mac 宿主）

1. **配置**：电脑端群信息 → 团队编制 → 为组员配执行/审查 + 驱动（对话框执行段，
   仅席位宿主可编辑）→ 保存（执行档落宿主 `team-execution.json` + 声明 rev+1 广播）。
2. **触发**：手机（任一成员）发 `@coder 修复登录超时` → 宿主编译 `[coder]+[reviewers]`
   → 链卡在群内推流（worker → reviewer 串行，审查棒带 VERDICT）。
3. **闭环**：reviewer REJECT ⇒ 卡回 worker；3 轮 ⇒ blocked（可读原因）。
4. **reviewer 直呼**：`@reviewer 审查` ⇒ 纯审查单棒（不派 worker）。
5. **未配置**：删执行档（或换未配置席位）再 `@coder` ⇒ blocked 卡"未配置执行"，
   **绝不**回落 codex 单棒。
6. **未授权**：非可信成员 `@coder` ⇒ blocked 卡"not authorized"（无执行）。
7. **旧端对照**（需旧构建，未验）：旧发送端 `@coder` 纯文本 → 新宿主仍执行
   （§4.6 关键路径）；新发送端 + 旧宿主不执行。
8. `closeCount` 全程 0；@领队 行为不变（单 agent 派单）。

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

## A26 · Agent 卡片三型交互闭环（2026-11-07 新增）

宿主（`./gradlew :desktopApp:run`，与手机同一 luciola 链路）驱动真实卡片，手机依次判读。
前置：手机已被授权（桌面「榫卯 → 授权」，或 `PUT /v1/agent/grants` 的
`grantReply` 推帧；**scope=类别 / access=档位是两个维度**）。

**① 审批卡（approval）**：
- `POST /v1/agent/hook/approval`（`{"tool":…,"summary":…,"riskLevel":"L2","diff":…}`）
  —— 它**长轮询挂起**，等手机点按；
- rw 档的设备看到的是「只读（无权审批）」（§7.5 权限矩阵：rw 不在内）；
  owner（或开了委托审批的 admin）看到**拒绝 / 批准 / 本会话免审 / 此工具始终允许**
  四颗按钮 + 语义说明；
- 点「批准」⇒ 长轮询解挂 `{decision:"allow", reason:"approved", approvalId:"hook-…"}`；
  点「此工具始终允许」⇒ 宿主免批表（`agent-tool-approvals.json`）落一条
  `(nodeId, tool)` 规则，**下一次同工具请求秒回 `allow, matched an always-allow rule`**；
- 5 分钟超时：到点未处置的卡片自动回收（真机自证：卡片数 3→2）。

**② 决策卡（ask_question）**：
- 卡只走**群路径**或已宣告会话的事件流（`no-group-bound` 时沉默不猜，task-59
  防串群裁决）——1:1 场景先建群，或 `hook/session` 宣告后经事件流投卡；
- 点选选项 ⇒ `POST` 返回 `{"status":"answered","answer":<选项>,"decidedBy":…}`。

**③ 免批表回收**：桌面「设置 → 可信设备 → 免批规则」逐条撤销（`revokeExactly`）。

**判据**：每一步对端台账与卡片状态**同帧一致**；「未确认 ≠ 已执行」不破。
**证据**：`design/runtime-evidence/upgrade-baseline-20261107.txt`（【13】【14】）、
截图 `~/Downloads/relay-build-20261107/baseline/`（b06–b18，浅色 + 深色）。
已知未遍历：Agent 控制台的会话切换器（▾）在真机上的入口路径（本轮黑盒超时）。

## A27 · luciola 唯一传输形态 + legacy 物理删除（2026-11-10，用户明确指令）

**指令**：「手机开安全信道、宿主是 legacy ⇒ 都必须是 luciola」「legacy 必须移除」。

**开关层拆除**：桌面 `DesktopLuciolaHost` 恒启用（`TENON_LUCIOLA` 门删除）；手机
`RelaySettings.experimentalLuciola` 停止被读（字段保留防老 settings.json 未知键报错），
设置页「加密信道」开关全链删除。`NodeRuntime.messengerTransportFactory` 收成**必需非空参数**——
「忘了接 luciola」从此是编译错误，不是静默回退明文；装配失败走 `UnavailableTransport` +
`LuciolaUnavailable` 横幅（reason=platform/native）。`legacyDiscoveryEnabled` 分支与
legacy 三件套入口全删。

**物理删除**：`core/.../transport/Discovery.kt`（601 行广播发现；`ManualPeer` 抽独立文件）、
`DiscoveryTargetsTest`、`SubnetBroadcastTargetsTest`。入站发现帧登记（entry/alive/presence/
profile/exit 的协议事实）由新 `PresenceIngest` 承接；五个 `Discovery*Test` git mv 为
`Presence*Test`（夹具 `IngestNode`），ReturnPath/SelfHeal 换真实 runtime 判据。

**随删除暴露并修复的五条「没人接手」回归**：
1. `tcpPort=0` 进对外 profile ⇒ `validateProfile` **整帧拒收**（app 测试 30 条超时的根因；
   实证 `decoded=Rejected`）。修：`RelaySettings.toProfile` 收口 `?: DEFAULT_TCP_PORT`。
2. 换网重播死路：`NodeRuntime.announce()` 委托哑引擎 ⇒ 零发包。修：真发一轮定向心跳。
3. `resolvePeerByHost` 同型（调 `discovery.announceTo` no-op）⇒ agentNode `/v1/send`
   46 条 peer-unreachable。修：改调 runtime 自己的 `announceTo`。
4. exit 告别帧无人发送。修：`NodeRuntime.announceExit` 对 registry+manualPeers 各发空载荷 exit。
5. 候选端点心跳（task-21 ①）与传输自愈（task-29，deadRounds≥2→restart，判据
   targets>0&&sent=0）从 legacy 搬进 `SweepOnlyDiscoveryEngine` / 心跳循环。

**判据与读数**：`:core:jvmTest` 977 条 / 0 失败 / 32 skip；`:app:jvmTest` 全量绿；
`:agentNode:test`+`:desktopApp:test`+`:cli:test`+`:orchestration:test` 全量绿；
`:composeApp:compileKotlinJvm` 通过。已知边界：`UdpTransport` 暂留 main
（`InMemoryLuciolaHost` 与测试回环传输依赖；删除前置 = 测试侧 luciola 回环，另行任务）；
真机双端同为安全信道的 `/v1/peers` 复核待下一轮真机窗口。

## A28 · 裸 UDP 传输退居测试层（2026-11-10，接 A27）

**用户令**：「legacy 的代码是不是可以删除了？legacy 的能力 luciola 应该都支持吧。」

**能力对账（删除前先钉住事实）**：legacy 生产路径在 A27 已清零——`Discovery.kt` 删除、
回退分支删除、`messengerTransportFactory` 必需参数化。luciola 覆盖 legacy 全部能力：
发现（sweep 翻面 + 定向心跳 + `PresenceIngest` 入站登记）、exit 告别帧、换网重播、
候选端点投递、传输自愈，均有专属判据测试钉住。

**本轮删除**：`UdpTransport` 类从 `core/commonMain` 退到 `core/jvmSharedMain`
（生产唯一传输形态是 luciola `SecureChannelTransport`；裸 UDP 只剩三类 JVM/Android
消费者：CLI 的内存平台、各模块 `udpTestTransport()` 回环夹具、传输行为测试）。
`ReceivedDatagram` 是协议层公共类型，移入 `DatagramTransport.kt` 留在 commonMain。
`InMemoryAppPlatform`/`InMemoryLuciolaHost` 同步从 app commonMain 移到 app
jvmSharedMain（cli 生产在用，不是纯测试夹具）。

**测试随迁（git mv 保历史）**：19 个引用裸 UDP 的 core commonTest 文件 → jvmTest；
10 个 app commonTest 文件 → jvmTest（`RelayControllerCabinetTest` 与 jvmTest 既有
文件同名不同类，改名 `RelayControllerCabinetDomainTest`）。commonMain/iosMain 从此
零代码引用裸 UDP（Android/iOS 变体编译验证通过）。

**过程教训（记入长期记忆）**：git mv 同内容文件后，Kotlin 增量编译会漏编译
"新位置"的文件——`:core:jvmTest` 一度报 920 条 0 失败的**假绿**（旧 commonTest
产物还在类路径上）。判据：数 XML 结果文件里迁移类的存在性，不只看任务 exit code。
touch 文件强制重编后，`--rerun-tasks` 全量复跑恢复真读数。

**判据与读数**：core 977 条 / 0 失败 / 32 skip（与搬迁前逐条一致，零用例丢失）；
app 968 / 0；agentNode 545 / 0；desktopApp 379 / 0；cli 7 / 0；orchestration 219 / 0；
`unused-imports.py --check` OK（顺带清掉 4 条遗留 import 与 `set_luciola_*` 孤儿资源）。
iOS 编译：曾报 `MessageStore.ios.kt:170 Unresolved RelayDatabase`，定位为该文件
缺 `import com.kapp.tenon.db.RelayDatabase`（jvm/android 同位文件都有，iOS 独缺；
CI 从不编 iOS 所以长期潜伏）。补 import 后 `compileKotlinIosArm64` 与
`compileKotlinIosSimulatorArm64` 双双通过。

### A28 收尾复核（本批改动可交判据）

- 九模块全量 `--rerun-tasks --max-workers=1`：core 977 / 0 失败 / 32 skip、app 968 / 0、
  agentNode 545 / 0、desktopApp 379 / 0、cli 7 / 0、orchestration 219 / 0、
  agent-contract 19 / 0、composeApp 1215 / 0（jvmTest + testAndroidHostTest）。
  逐个核对迁移类（`Presence*Test` / `ReturnPathTest` / `TransportSelfHealTest` /
  `RelayControllerCabinetDomainTest`）在结果 XML 中真实存在，排除 Kotlin IC 假绿。
- 十门禁全绿。其中两处红按口径修好，未刷任何基线：① 本轮新写的两处代码注释带 ISO 日期
  ⇒ 去掉日期只留结论；② 三个测试文件 `git mv` 到 `jvmTest` 后
  `tools/env-test-allowlist.txt` 仍是 `commonTest` 旧路径 ⇒ 三条路径同步，
  并删掉两条对应已物理删除测试的死条目。
- `:testStabilityGate` 连跑 3 轮全 PASS；`unused-imports.py --check`、
  `check-design-palette.py --css-vars`、`sdlc-lifecycle.py --dry-run` 全绿。
- `:androidApp:assembleDebug` 通过（56,148,407 B），供换包复验。
- **未做（别当已验）**：桌面宿主仍是旧安装包，双端同为 luciola 的真机复验须在源码宿主
  或换包后进行；`--tier real` 的 a2r 人审、幕 4 / i1 / i2 仍需手机侧配合。

## A29 · 双端 luciola 空闲在线复验 + 第六判据空集假绿修复（2026-11-10 晚）

**装配**：桌面宿主改用源码 `./gradlew :desktopApp:run --args=--headless`（旧安装包跑 legacy，
验不了本批改动），手机装 21:32 重编的 debug 包；两端日志均有 `[xport]`。
控制面在回环 **8788**；17878 是 luciola 传输端口，对它发 HTTP 只会超时（本轮踩过）。

**读数（证据 `design/runtime-evidence/presence-luciola-20261110/`）**：宿主
`peers=30 / online=1 / uptime=130s`；手机 `linkTotal=0`、活跃链路 0、宿主那条 `online=true`、
`dialAttempts=21 / dialFailures=21 / connect-timeout`；近 3000 行 logcat 里 `[sweep] offline`
0 条；界面无「宿主设备睡眠」横幅。**空闲 130s 超过 90s 阈值，旧现象未复现**。

**但不能据此判第六判据通过**：`peer_idle_stays_online()` 原式在「没有任何
`linkConnected` 对端」时 `hole` 与 `flips` 天然皆空 ⇒ 因为什么都没看见而给绿。
修法：无在册活跃链路直接返回 `None`（不适用、只记证据），与 `prototype_bytes_landed`
同口径；`dry-run` 增第 28 项守卫 `review-gate-idle-requires-live-link`，反向探针
（早退改恒假）仅该守卫判红，恢复后 28 项全绿；修复后真机重跑返回
「无在册活跃链路（linkTotal=0，peers=12）」。

**新暴露（未定因，另立案）**：双端 luciola 下链路始终建不起来 —— 手机拨号 21 连败、
目标在多个候选地址间轮换，宿主心跳 `targets=32 sent=2 linked=0`、大量
`no session / unpaired`。由此「空闲不翻面」的链路豁免可能长期不生效，这次在线稳定靠
presence 帧通了，不是链路恢复；链路建立路径须单独取证，别把「在线没翻」当链路已恢复。

**边界**：本轮只被动观测、未派单；`--tier real` 的 a2r 人审与幕 4 / i1 / i2 仍需手机侧配合。

## A30 · luciola-only 链路建立失败的端口定因与修复（2026-11-10 深夜）

**接着 A29 的未决立案**：双端 luciola 下链路始终建不起来，手机拨号连续
`connect-timeout`，宿主心跳 `targets=32 sent=2 linked=0`、满屏
`no session / unpaired`。证据与逐轮读数见
`design/runtime-evidence/luciola-sending-port-20261110/`。

### 定因

`ReceivedDatagram.port` 是**发送方源端口**。legacy 时每个节点 UDP socket 绑固定端口，
源端口就是监听端口；luciola 的会话键是「地址:监听端口」（17878），发送侧用临时端口。
删 legacy 时只有 `LuciolaDiscoveryEngine` 按 `profile.luciolaPort` 归一，其余入站登记
路径原样留下：`PresenceIngest` 的当前地址 / 候选池 / alive 回包 / 资料刷新、
`Messenger` 的可靠帧 `touch` 与 ACK、以及启动时 `knownPeerStore` /
`peerEndpointStore` 把上一版落盘的临时端口原样装回。于是出站全拨到不存在的端点，
`pinFor` 查不到会话，`touch` 的 `sameAddress`（含 udpPort）又把在线节点的心跳判成
地址漂移整帧拒收。这是「同一值两处各写回退链」的又一实例。

### 修复

归一收成 `sendingPortFor(profile, sourcePort)` 一个入口（档案 `luciolaPort` 优先，
缺失或越界回退源端口，老端行为一字不变），三条路径共用：入站登记、
候选池读口（`candidatesOf` / `allCandidates`，落盘快照因此写回正确端口）、
启动恢复（`restoreOffline`）。不动协议字段、不新增可选键、不碰帧预算；
不逐消费点打补丁。

### 判据与验证

`core/src/jvmTest/.../LuciolaSendingPortTest.kt` 七个用例覆盖节点表 / 候选池 /
alive 回包 / 资料刷新 / 可靠帧 ACK / 落盘旧候选 / 名录恢复，并各自断言老端不变。
三次反向探针分别改坏 `sendingPortFor`、`normalizedCandidates`、`restoreOffline`，
依次 4/5、1/6、1/7 判红，恢复源码后 7/7 绿。

九模块全量 `--max-workers=1` **4336 tests / 0 失败**；11 门禁全绿
（含 `cjkLiteralGate` 72=72 未刷基线、`:testStabilityGate` 连跑 3 轮全 PASS）、
`unused-imports` / `check-design-palette` / harness `dry-run` 全绿、
`:androidApp:assembleDebug` 通过。

### 真机复验

三轮换包 + 重启源码宿主（22:20 / 22:39 / 22:56 包），逐轮暴露下一条历史数据路径，
逐轮读数见 `measurements.json`。最终装配：**在线对端的当前端点与消息投递端口均为
`:17878`**，宿主日志出现 `[send] peer=25222a45 ep=192.168.2.22:17878 acked=true
type=group ... regEp=192.168.2.22:17878`（A29 时一条 `acked=true` 都没有）；
`[sweep] offline` 0 条、无「宿主设备睡眠」横幅。

**不假装修好的部分**：心跳与拨号里仍有临时端口（宿主 86 处、手机 345 处），
来自上一版落盘的历史身份与候选端点 —— 它们的 profile 不含 `luciolaPort`
（该键由运行时 `selfProfile` 补，旧档案 / 扫码合成档案没有），三条归一都依赖它；
且多数属于离线历史节点、host 常是本机 IP，改对端口也发不出去。清理需要一条
不依赖 `luciolaPort` 的独立判据（「什么端口算垃圾」是产品判断），本轮未动。
`linkTotal=0`、拨号仍停在 `:17879` `connect-timeout`，属下面 ① 的 K1 缺口。

### 仍未解（另立案；下面 ①②③ 的拍板处置见 A31）

1. **K1 拨的是被有意关闭的端口**：luciola 模式下 `legacyTcpListener=false`，宿主不绑
   17879；但 `RelaySettings.toProfile()` 仍广播 `tcpPort=17879` + `lnk1`，
   手机 K1 私网拨号必然超时 ⇒ `linkConnected` 恒 false。候选：A 契约对齐（不广播
   lnk1、在线豁免改认 luciola 会话）、B 保留 17879 给 K1 下行、C 只留判据接受 K1 废弃。
   A 要同步改 `CapabilityAndNotificationSettingsTest` 的断言，B 与「legacy 清零」相悖，
   都动到既有裁决，**本轮未擅自改**。
2. **心跳 entry 对未连入的端点仍发不出去**：luciola 的 `transport.send` 要求已配对或
   已连入的会话，而心跳遍历全部已知节点（含离线历史）逐个发 entry ⇒ `sent` 常为 0。
   在线稳定靠的是已连入会话上的 presence / 消息帧，不是链路恢复。
3. **落盘旧身份 / 候选端点里的临时端口不会自愈**：归一依赖 `profile.luciolaPort`，
   旧档案没有这个键。要清得另立一条不依赖档案的判据，并回答「历史数据里哪些端口
   算垃圾、会不会误伤自定义端口」，属产品判断，本轮未动。
4. `--tier real` 的 a2r 人审与幕 4 / i1 / i2 仍需手机侧配合；本轮只被动观测、未派单。

## A31 · 待拍板三项按「让判据认事实」收口（用户授权拍板）

**结论：A/B/C 三条候选都不选。** 取证后确认 K1 不是废弃功能：`lnk1` 至今决定
`LinkClient` 拨不拨，撤广播会连公网 K2 一起停；复活 17879 与「legacy 清零」相悖；
「接受废弃」则是把一条能修好的链路判死刑 —— 真机宿主的 **17880 TLS 接受面一直在
LISTEN**，手机对宿主 `pinSource=pairing`，两个条件都齐。

**定因**：`LinkClient.linkTargets` 的「私网 ⇒ 明文 tcpPort」以 17879 在听为前提，
而信道模式 `legacyTcpListener=false` 有意不建它；profile 里的 `tcpPort=17879`
只是 `Codec.validateProfile` 的必填占位。手机连续 229 次拨它全 `connect-timeout`
⇒ `linkConnected` 恒 false ⇒ sweep 在线豁免与心跳跳过长期不生效，A29 的第六判据
因此一直「不适用」。

**做了什么**（不动 caps、不加协议字段、不复活明文监听面）：

1. 只用档案里已有的 `luciolaPort` 判形态（Profile KDoc：null = legacy 节点）。
   私网 + luciola 监听端改走 `tlsPort` + TLS + pairing pin；老端私网明文一字不变；
   公网原样；缺 pin / 缺 tlsPort 不拨并把 `skipReason` 同步说清。
2. `DatagramTransport.canReach()`（默认 true）+ `SecureChannelTransport` 覆盖为
   「入站已接管 ∨ 外拨有会话 ∨ 配对库有 pin」；心跳三处只把可送达端点计入
   `targets`，其余记 `skipped`。用准入事实过滤，不按端口数字猜，顺带把上一轮
   遗留的临时端口从心跳出站剔除（落盘数据未清洗）。

**判据**：新增 `LuciolaLanLinkTargetTest` 6 例（反向探针 4/6 判红）、
`PresenceHeartbeatTargetsTest` 4 例（两次反向探针各 1/4 判红：摘掉心跳过滤、
安全信道恒 true），恢复后全绿。九模块全量 **4346 tests / 0 失败**；11 门禁全绿
（`cjkLiteralGate` 72=72 未刷基线）、`:testStabilityGate` 连跑 3 轮 PASS、
unused-imports / design-palette / harness dry-run 全绿、`:androidApp:assembleDebug` 通过。

**真机复验**（换包 + 重启源码宿主，证据
`design/runtime-evidence/luciola-lan-link-20261110/`）：手机
`linkTotal=1 / linkConnected=true`、`dialLastTarget=192.168.2.82:17880`、
`lastError=null`、`closeCount=0`；宿主心跳 `targets=32 sent=2` →
`targets=2 sent=0 skipped=28`，启动期一轮 `sent=0 dead=1` 后恢复；
**8.7 分钟长空闲复验**（宿主 uptime 523s）：`transport restart` 0 次、拨号停在
4 次、`[sweep] offline` 双端 0 条、无离线横幅。**第六判据
`peer-idle-stays-online` 首次真正 PASS 并二次复验通过**（`peers=12 flips=0`），
不再是 A29/A30 的「不适用」，且是在有真实链路前提下给绿、非空集假绿。

**未决**：① 心跳启动期 `targets=2 sent=0` 仍是「有 pin 但对端不监听入站」的
正常形态，长期占着 targets 时自愈仍可能误判，需把「对端能力」与「本地泵」分开；
② 落盘历史端点未清洗；③ 档案刷新丢失 `luciolaPort` 的理论回退无专门守卫；
④ `--tier real` 人审与幕 4 / i1 / i2 仍需手机侧配合（harness 如实标 NA，不冒充通过）。

## A32 · 心跳停摆判据绑到「已连上的会话」，档案刷新不再丢信道端口

A31 的未决 ①③ 这一轮清掉，② 由 ① 的修法降级为惰性数据。

**定因（①）**：`SecureChannelTransport.canReach` 判的是「入站已接管 ∨ 外拨有会话 ∨
配对库有 pin」，而 `send` 的成功条件是「有会话且握手完成」—— 判据比它预测的行为
宽了一格，宽出来的那一格恰好命中所有历史配对身份。于是 `targets=2 / sent=0`
**长期恒定**，自愈每两轮 `transport.restart()` 一次。真机旧包（pid 17260）在
00:16:30–00:21:30 连续六次重启，间隔正好 60 秒。

**定因（③）**：`touch` 采纳新档案是整份替换，而 `luciolaPort` 是运行时注入的
（`selfProfile` 从 `transport.boundPort` 取，绑端口前为 -1 ⇒ 键被省略）。
一次不带该键的刷新就把已知端口抹掉，`sendingPortFor` 回退到数据报临时源端口 ——
症状与 A30 同形，成因换成「知道过的端口被覆盖」。

**做了什么**：
1. `canReach` 只认**已连上的会话**（`isConnected()`）；pin 只说明「可以拨」。
   `DatagramTransport` 的 KDoc 立规矩：「判据要跟 `send` 的成功条件同宽，不能更宽」，
   并写明覆盖边界（无会话时 socket 全坏不触发重建）。
2. `Profile.keepingKnownChannelPort(known)` 收在 `touch` 的采纳分支：新档案缺
   `luciolaPort` 就沿用旧的。只补这一个键（`screenPort` 的 null 是真状态、
   `tlsPin` 的 null 不能顶替信任根）。
3. `[heartbeat] idle linked=… skipped=… manual=…`（60 秒限流）：修完之后 `targets=0`
   是稳态，这行用来区分「心跳在跑但目标都不可达」与「循环根本没起来」。
4. 「拨不拨」与「算不算停摆」拆成两个判据：手动地址（用户手填的 host:port）按
   `DatagramTransport.dialable`（默认 = `canReach`；安全信道 = 「有会话 ∨ 配对库有 pin」）
   决定拨不拨，是否计入 `targets` 仍看 `canReach`。裸 UDP 上两者恒真 —— `send` 在裸 UDP
   失败只可能是本机 socket 坏了（task-29 的原始判据，`TransportSelfHealTest` 钉着）；
   安全信道里没有会话的手动地址只拨不计数（对方不可达 ≠ 本机泵死）。
   第一版把手动地址整段摘出 `targets`，`TransportSelfHealTest` 立刻判红 —— 口径是被它逼出来的。

**判据**：`PresenceHeartbeatTargetsTest` 5 例 —— 三处反向探针：恢复 pin 分支 ⇒ 1/5 判红、
手动地址无条件计入 targets ⇒ 1/5 判红（这一条自己先假绿过一次：`await(sends>=2)` 恰在
第二轮边界就断言，重启还没落地；收紧到「再多跑三轮」才稳定判红）、以及重写的
`aRealStallStillRestartsTheTransport`（用 `replay=1` 在 `start()` 前塞真实 ENTRY，
不再拿手动地址凑数）。
`LuciolaSendingPortTest` 8 例（反向探针摘掉端口保留 ⇒ 1/8 判红）、
`LuciolaSecureChannelTest` 新增真信道用例（配 `TENON_LUCIOLA_NATIVE` 绝对路径后
**16/16 PASS**，反向探针 1/16 判红）。
**core 1001 tests / 0 失败 / 33 skipped**；门禁 10 个全绿（`cjkLiteralGate` 72=72 未刷
基线、`constructionTraceGate`、`staleCommentRefGate`、`domainComposeFreeGate`、
`envDependentTestGate`、四个设计门、`jlinkModulesGate`）、unused / palette / dry-run 28 项、
`:androidApp:assembleDebug` 通过。
**九模块全量在并行会话的 HostDirectory 特性落地（`e2e54ac0`）之后补跑：4374 tests /
0 失败 / 37 skipped**（core 1001、app 968、cli 7、agent-contract 19、orchestration 223、
composeApp 1215、agentNode 562、desktopApp 379）。这一轮**先清掉各模块 `build/test-results`
强制重跑**（14 个任务真的执行，不是 `UP-TO-DATE` 吃缓存），条数从结果 XML 里数、
并核对了本轮四个守卫类与用例名确实在册 —— 只信 exit code 会被旧产物骗。
core 的 33 skipped 里 16 个是真引擎用例（未配 `TENON_LUCIOLA_NATIVE` 时跳过，
配绝对路径后 16/16 绿，见下）。

**`:testStabilityGate` 没给绿，但不是本次改动的失败面**：01:21 那次（core + app 连跑
3 轮）3 轮全红 —— 2 轮 `:app:compileKotlinJvm FAILED`（并行会话未跟踪的
`RelayControllerProjectDirectoryComponent.kt` 里 `nowMillis` 未解析）、1 轮
`ClassNotFoundException`（类在测试运行中被改）。缩到 `:core:jvmTest` 同等负载连跑 3 轮
是 **2 绿 1 红**，红的是 `LinkK1Test.thePhoneOpensALinkAndTheHostDownlinksTextOverIt`
的 `withTimeout(5_000)`；这条只走回环 UDP + 长连接投递，**不进本次改动的任何分支**
（它没配手动地址，`send` 也不读 `canReach`）。机器安静后同样三轮 **全绿**，
`LinkK1Test` 单跑 **6/6 绿**。⇒ 负载敏感偶发，如实记为未解，不刷它的 5 秒断言。

**真机复验**（两轮：第一轮验 ①③，第二轮换包验带 ①b 的最终版；证据
`design/runtime-evidence/heartbeat-stall-targets-20261111/`）：
- 旧包（pid 17260）`[presence] transport restart` **每 60 秒一次，连续 6 次**。
- 第一轮新包（pid 22443）同窗口 **0 次**；宿主 uptime ≈ 8 分钟 `[heartbeat] idle
  skipped=30`（A31 的 `targets=2` 下沉到 `skipped`）、`transport restart` 0、
  `[presence] sent=0` 0、`[sweep] offline` 0、`send refused` **0**（A31 同窗口 2156）。
  手机 `linkTotal=1 / carrier=tls / dialAttempts=1 / closeCount=0`，宿主→手机三条群消息
  `acked=true`（44/54/68ms）。第六判据 `peer-idle-stays-online` 两次 PASS
  （`peers=12 flips=0`，uptime ≈ 2 分钟与 ≈ 8 分钟）。
- 第二轮最终包（手机 pid 25441 / 宿主 pid 39280）连续跑 8 分钟：手机 **21 轮心跳里稳态
  读数逐字相同** —— `[heartbeat] entry targets=1 sent=1 manual=2 linked=1 skipped=11
  failed=[192.168.2.82:17886]`。这台机器的设置里有 **2 个手填地址**，两个都在拨
  （`manual=2`），持续失败的那一个不进 `targets` ⇒ `targets` 恒等于已连上会话数 1，
  自愈不再误判；同一窗口 `transport restart` 0、`[sweep] offline` 0。宿主
  `[heartbeat] idle linked=0 skipped=30 manual=0`、`send refused` 0、`acked=true` 3 条
  （25/40ms）、`/v1/peers` 30 条 1 在线。第六判据再跑一次 PASS
  （`peers=12 / online=1 / linkConnected=1`，`diag/link.json` 在采样前 1 秒仍在刷新，
  见 `peer-idle-steady-final.txt`）。

**未决**：① 落盘历史端点不清洗 —— 已全部落进 `skipped`，不再驱动拨号或自愈，
交给既有 7 天修剪与「再出现即学回」；不为它们造「端口像不像垃圾」的新判据。
② `--tier real` 人审与幕 4 / i1 / i2 仍需手机侧配合（harness 如实标 NA）。
③ 真引擎用例需显式配 `TENON_LUCIOLA_NATIVE` 的**绝对路径**才跑：仓库里那份在
`desktopApp/resources/common/native`，但 `build.gradle.kts` 的约定目录只找
`../RustBuild/cargo-target`；给相对路径会被 gradle daemon 的 cwd 解析成
`…/daemon/9.8.0/…`，`LuciolaNativeGuard` 判「显式配置了却用不了」⇒ 16 例全红。
要不要把仓库内那份纳入约定解析顺序，另案决定。
④ `LinkK1Test` 的 5 秒 `withTimeout` 在整包 core 套件 + 并行编译时会偶发超时
（同等负载 1/3，单跑 6/6 绿，机器空闲 3/3 绿）。它不是本轮改动的分支，但「每几轮红一次」
正是稳定性门禁要盯的形态 —— 要么把它改成等到就绪再断言（而不是赌 5 秒），要么查
长连接首帧在负载下的实际时延，另案处理；本轮**没有放宽断言**来换绿。
