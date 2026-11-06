# 桌面端启动弹「Tidings 想访问可移除宗卷上的文件」排查记录

> 日期：2026-10-18 · 排查对象：`/Applications/Tidings.app`（bundle id `app.relay.desktop`，Compose Desktop / jpackage）
> 用户环境：工作目录 `/workspace/src`（外置 USB 卷，`diskutil` 报 `Device Location: External`、`Protocol: USB`）
> 结论口径：**根因已确认为「本进程在一次启动里对可移除卷做了一次文件系统访问」，但这一轮没能把这条访问钉到具体代码行**。原因是所有 Kotlin 侧可疑调用点都被逐一排除（证据在下面），而 TCC 日志把触发路径作为 `<private>` 抹掉了，且本会话无法取得 root / 无法给真机进程做 syscall 追踪。

---

## 1. 症状与硬证据：确实是 Tidings 自己干的

macOS 统一日志（`tccd`）里，每次启动都有一条可移除卷请求，**accessor 就是 Tidings 进程本身**：

```
AUTHREQ_ATTRIBUTION: msgID=9366.99,
  attribution={accessing={TCCDProcess: identifier=app.relay.desktop, pid=27559,
                 binary_path=/Applications/Tidings.app/Contents/MacOS/Tidings},
               requesting={TCCDProcess: identifier=com.apple.sandboxd, pid=9366,
                 binary_path=/usr/libexec/sandboxd}}
AUTHREQ_CTX: ... service=kTCCServiceSystemPolicyRemovableVolumes, preflight=yes
AUTHREQ_CTX: ... service=kTCCServiceSystemPolicyRemovableVolumes, preflight=no
AUTHREQ_PROMPTING: msgID=9366.100, service=kTCCServiceSystemPolicyRemovableVolumes,
  subject=Sub:{app.relay.desktop}Resp:{... pid=27559 ...}
```

### 1.1 每次启动都发生，且「启动 → 触发」的间隔随历史数据增长

| 进程 spawn（launchd） | TCC 可移除卷请求 | 间隔 |
|---|---|---|
| 07:38:13.295 | 07:38:15.722 | 2.4 s |
| 07:57:20.380 | 07:57:23.835 | 3.5 s |
| 09:31:16.355 | 09:31:22.260 | 5.9 s |
| 09:40:27.112 | 09:40:36.052 | 8.9 s |
| 10:00:38.402 | 10:00:48.263 | 9.9 s |

间隔单调增长，与启用态数据量同向（`stores/messages.log` 1.2 GB、`relay.db` 274 MB、`messages.json` 1 MB）。
说明这次访问**发生在启动恢复链的尾部**（历史越厚恢复越久），而不是进程起来的第一毫秒。
10:00 那次在授权后 10:00:51.025 / .184 / .196 还有 3 次同服务请求（已授权，不再弹窗）。

### 1.2 为什么「每次都弹」而不记住：签名不稳定

```
codesign -dv /Applications/Tidings.app
  CodeDirectory ... flags=0x10002(adhoc,runtime)
  Signature=adhoc   TeamIdentifier=not set   Internal requirements count=0
```

`tccd` 因此记录 `Failed to match existing code requirement for subject app.relay.desktop and service
kTCCServiceSystemPolicyRemovableVolumes`（cdhash 每次重新打包都变），**没有稳定 designated requirement 可以记住这次授权**，所以每次启动/每次重打都重新弹一次。这一条本身就是产品级问题（见 §4）。

---

## 2. 候选调用链逐条判定

### 2.1 应用自身数据/缓存目录 —— 排除

- `DesktopPlatform.kt:83-103`：`defaultDataDirectory()` = `~/Library/Application Support/捎话 Tidings`，
  `defaultCacheDirectory()` = `~/Library/Caches/捎话 Tidings`；`Main.kt:42-43` 取的就是这两个。
- 全仓 `user.home`（即任何 `~/...` 推导的来源）只有 4 处：`DesktopPlatform.kt:84,95`、
  `DesktopLoginItem.kt:187,203`（LaunchAgents 目录），以及 `ControlPlane.kt:1951`（`defaultGrantFile()`，
  桌面端**不走**它：`DesktopExtension.kt:295` 显式传入 `controller.dataDirectory/agent-grants.json`）。
- 运行时实测：`lsof -p 27559` → `cwd` 是 `/`，可执行体与 runtime 全在 `/Applications/Tidings.app` 下，
  **没有任何 /Volumes 句柄**；实际读写的路径是 `~/Library/Application Support/捎话 Tidings`。
- 因此「数据目录落在可移除卷」不成立。

### 2.2 持久化的分享目录（cabinet `shareRoot`）—— 该用户不成立，且代码不在启动路径

- 实测 `settings.json`：**没有 `shareRoot` 键**（`RelaySettings.kt:180` 默认 `null`）。没设过 → 没有外部路径可访问。
- 代码里 `shareRoot` 只在这些地方被当路径用：
  `RelayControllerCabinetComponent.kt:237,252-269`（入站 `ListReq`）、`:300-306`（入站 `GetReq`）——
  触发方是**对端的报文**，不是启动；`handleLoadCabinetPath`（`:124-172`）是用户点开文件柜时的导航。
  `CabinetScreen.kt:242,361`、`CabinetPane.kt:291,363,436` 只**显示**字符串。
- 结论：这条不可能在启动时触发；即使设过，也只有对端请求或用户点开时才读。

### 2.3 agent 工作区 / DSH 会话同步 —— 没有一处代码把 `wsPath` 当文件路径

- 持久化数据里**唯一的 /Volumes 值**就是工作区路径：对 `relay.db` 全库全列做 `LIKE '%/Volumes%'` 扫描，
  命中的只有 `AgentSessionEntity.ws_path` / `AgentWorkspaceEntity.ws_path`（如 `/workspace/src/teahouse-mobile`）
  与若干 `title`/正文里的同名文字；`MessageEntity`、`AgentArtifactEntity` 等**没有任何 /Volumes 文件路径**。
- 但 `wsPath` 在整个 Kotlin 侧只参与**字符串比较与显示**：
  `RelayControllerGroupsComponent.kt:45-60,73,112,132`、`RelayControllerAgentComponent.kt:204,254`、
  `RelayUiState.kt:240`、`WorkspaceBar.kt:59-60`、`App.kt:628,832,852`、`ChatScreen.kt:209`、
  `WorkspaceQrDialog.kt:72-74`、`InboxWorkspaceUnits.kt:461-468`（`contains`/`==`/`substringAfterLast`）。
  `ChatInteractions.kt:197-199,264` 是协议字段解析。
- `DesktopExtension.kt`（本轮启动相关）：`syncOnce/observeInbound/backfillMissingAgentEvents`
  （`:121-420`、`:469-535`）只做字符串匹配与消息落库，没有任何文件 IO。
- agentNode 侧：`agentNode/src/main` 全目录 **0 处** `java.io.File(` / `Files.` / `listDirectory` / `fileAttributes`。
  `ControlPlane` 的 `wsPath` 只出现在 JSON 读写（`:547,563,585,609,653,665,677,716,1354`）。
  `NativeRelayDriver` 的 `fs_read` 只是能力**声明**，`executeTask`（`:69-92`）不碰文件系统。
- 结论：**代码里不存在「按 wsPath 去 stat/遍历」的调用点**，因此不能用现有代码解释这次访问；
  但被记录到磁盘的 /Volumes 线索只有工作区路径这一种，它仍是唯一「可能被下游原生/JDK 层用到」的外部路径来源。

### 2.4 打包产物位置 / launchd 登录项 —— 排除（并另发现一个陈旧登录项）

- 运行中的进程是 `/Applications/Tidings.app/Contents/MacOS/Tidings`（内置盘），cwd `/`；
  不是从 /Volumes 或从 DMG 挂载点起的。
- `~/Library/LaunchAgents/app.relay.desktop.plist` 指向 `/Applications/捎话 Tidings.app/Contents/MacOS/捎话 Tidings`，
  而 `/Applications` 下现在只有 `Tidings.app` —— **该自启项指向的是不存在的路径**（历史改名残留）。
  这是一个真实的小 bug（自启失效），但它与本次 TCC 弹窗无关，且不在启动访问路径上。
- 读自己的 bundle（若真装在可移除卷上）不是本次 TCC 服务的典型触发条件；且本机并不住在 /Volumes。

### 2.5 文件关联 / 深链启动（args）—— 排除

- `DesktopDeepLink.parse`（`DesktopDeepLink.kt:38-66`）与 `isMessageFile`（`:69-70`）都是纯字符串判断。
- `Main.kt:185-213 handleExternalOpen`：`relay://` → `addManualPeer`；`.relaymsg` → 只开主窗；
  其余参数忽略（含 macOS 的 `-psn_…`）。**从不 stat 传入路径**。

### 2.6 其他已核对的启动路径

`Main.kt:39-133` 的装配顺序内，所有真正的文件访问只有：settings/messages/relay.db/agent-grants
（全在 dataDir）、`DesktopUiPrefs.loadPowerSaver`（dataDir）、`stores/messages.log|queue.log` 的构造读取、
`dedup.log`（全在 dataDir）、dns-sd 子进程（网络）、屏幕采集缓存（`~/Library/Caches`）。
`DesktopDialogs.chooseFile/chooseDirectory`（`DesktopServices.kt:29-50`）只在用户点「选择」时创建对话框。
`DesktopReceivedFileActions.readableFile`（`:87-95`）会 stat 路径，但只在用户点「打开/分享」时调用。
`DesktopLoginItem.currentCommand/isEnabled`（`:171-191`）只读 LaunchAgents 与自身可执行路径。

---

## 3. 本轮做过的实验（含负结果，供复现）

因为静态证据与系统证据对不上（代码里没有、系统说进程有），我做了以下实验，全部**只读/不改仓库**：

1. **atime 取证 —— 不可用**：APFS 该卷不记 atime。旁证：`ui-prefs.properties` 在 10:00:38 被读过，
   atime 仍停在 `Oct 17 22:20`。
2. **全库全列扫描 —— 完成**：见 §2.3，只有 `ws_path`。
3. **TCC 路径还原 —— 被系统抹掉**：`daemon_dict=<private>`；`syspolicyd` 无对应条目；
   kernel `Sandbox` 只记录别的进程（`duetexpertd` 等）对 `/workspace` 的访问。
4. **syscall 探针（DYLD 插桩）**：```
   cp -cR /Applications/Tidings.app /tmp/relayprobe/RelayProbe.app
   codesign --force --sign - --options runtime \
     --entitlements <(allow-dyld-environment-variables + disable-library-validation) …/RelayProbe.app
   # 数据目录用 APFS clone 隔离（cp -cR，1.4G）、换新 nodeId、换端口，user.home 指向 /tmp
   HOME=/tmp/relayprobe DYLD_INSERT_LIBRARIES=libprobe.dylib …/RelayProbe.app/Contents/MacOS/Tidings --background
   ```
   说明：原包 **不做** 「allow-dyld-environment-variables」，所以直接插桩无效（这是为什么必须复制+重签）。
   插桩只 hook 了 `stat/lstat/opendir`（hook `open/access` 会在 dyld 初始化期间递归崩溃，已实测）。
   结果：探针成功加载并记录了 17 万条调用，但 **20–30 s 内没有一次 /Volumes 访问**，
   且这段时间`log show` 里**没有该探针进程的 `kTCCServiceRemovableVolumes` 请求**。
   即：**这个隔离环境没有复现该行为**（候选差异：`--background` 窗口未显示、固定控制面端口 8788 被真进程占用导致
   `NodeRuntime.start` 直接失败、`cfprefsd` 的 NSUserDefaults 按 bundle id 走真实主目录而不是 `user.home`）。
   这是「实验环境没复现」的负结果，**不能反证真机进程无罪** —— 真机 tccd 记录是确凿的。
5. **root 级追踪 —— 不可用**：`sudo -n` 需要密码，本会话审批被禁用，无法跑 `fs_usage`/`eslogger`。

---

## 4. 能不能安全规避？本轮**不改代码**的理由

- 任务给的规避原则是「启动时不要主动 stat/遍历用户选过的外部路径；把访问推迟到用户打开该功能时」。
  逐条核对后：**当前代码里根本没有这样的启动期调用点**（§2.2/§2.3/§2.6 都是用户动作或对端报文才触发）。
  在找不到调用点的情况下加「防御性改动」，改的是不存在的路径，只会掩盖真实触发者。
- 因此本轮交付「结论 + 取证方法」，**没有改动任何生产代码**（本文件是唯一新增文件）。

### 仍然值得做的产品选项

1. **给应用一个稳定签名**（最高性价比）：现在 ad-hoc 无 Team ID → `Failed to match existing code requirement` →
   每次启动都重新弹。用稳定的 Developer ID（或至少固定签名身份）后，用户点一次「允许」就能长期生效。
2. **首次使用解释**：若确认访问是某功能（文件柜 / agent 工作区）所必需，则在该功能第一次打开时给一次解释性说明，
   并指路「系统设置 → 隐私与安全性 → 文件与文件夹 → 可移除宗卷」，而不是启动即弹。
3. **默认值保持内部盘**：`shareRoot` 默认 `null`、下载目录 = `dataDir/downloads`（已如此，保持不变）；
   绝不把外部路径写成默认值。
4. 顺手修 §2.4 的陈旧 LaunchAgent（指向 `/Applications/捎话 Tidings.app` 这个不存在的路径）——
   与本次弹窗无关，但会让「开机自启」静默失效。

---

## 5. 必须在真机上人工验证的点（本会话做不到的）

1. **拿确切的路径与 syscall**（要 root，本会话无法执行）：
   `sudo fs_usage -w -f filesys | grep -i -E "Tidings"`（或较新的 `sudo eslogger open`），
   同时从 Finder 重启 App，观察启动后 ~10 s 内第一条 `/Volumes/...` 访问。
   把命中的 path 与调用者（`Tidings` vs 子进程）贴回来即可定位到 §2 的哪一条链。
2. **确认命中项是不是工作区目录**：若路径是 `/workspace/src/<repo>`，则 §2.3 的 `ws_path` 就是来源，
   需要进一步查「是谁把 wsPath 交给了原生/JDK 层」（当前 Kotlin 侧没有这样的调用点，需要动态栈）。
3. **窗口可见性是否必要条件**：用 `--background`（只驻留托盘）冷启动一次，看是否仍弹；
   若不弹而正常启动会弹，说明触发在**首帧渲染/托盘菜单**路径上，可据此缩小到 UI 层。
4. **是否与网络/对端有关**：把外置卷上的工作区在 DSH 侧断开、或用一台没有 DSH 会话记录的干净 profile 启动，
   看是否仍弹（区分「本地持久化状态驱动」与「入站帧驱动」）。
5. **签名稳定性验证**：换成固定签名后再启动两次，确认 `System Settings → 隐私与安全性 → 文件与文件夹`
   里的「捎话 Tidings → 可移除宗卷」勾选能保持、且不再每次弹。
6. **无图形会话说明**：TCC 的 `kTCCServiceSystemPolicyRemovableVolumes` **只在有图形登录会话（aqua session）时才会弹窗**；
   SSH/headless/CI 里既不会弹也无法复现。本会话有图形会话，因此能读到 tccd 记录，但无法注入追踪（见 §3.5）。
