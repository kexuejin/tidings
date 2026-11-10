# 2026-11-05 修复后重跑：真 luciola 全链路 + 控制面（HEAD `b9ca96ce`）

本轮回答一个问题：**上一轮列的三个缺陷修完之后，重跑还能不能过。**

结论先说：三个缺陷全部修好并各自有真机/真宿主读数；数据面与控制面这次都是
`0 FAIL`。但重跑**新暴露两个真实缺陷**（收端文件卡状态不推进、8MB 推送卡在 2MiB
一个窗口），这两个不是本轮修的三个问题之一，也**没有**被修掉 —— 所以本文不会把
「收端文件卡」签成通过。

| 项目 | 读数 | 结论 |
| --- | --- | --- |
| 数据面 R2（重启前宿主） | `passed=34 failed=0 notes=4`，marker `LUC-E2E-1793881947` | ✅ 通过 |
| 数据面 R3（**真重启后**宿主） | `passed=34 failed=0 notes=4`，marker `LUC-E2E-1793882041` | ✅ 通过 |
| 控制面 phase1（同一 luciola 宿主） | `total=36 passed=36 failed=0` | ✅ 通过 |
| 控制面 phase2（**真进程重启后**） | `total=11 passed=11 failed=0` | ✅ 通过 |
| 全量测试 + 设计门禁 + 截图门禁 | `BUILD SUCCESSFUL`，`tests=5021 failures=0 errors=0` | ✅ 通过 |
| 打包 native UniFFI 契约 | `71 checked, 0 mismatch`，contract 29 | ✅ 通过 |
| **收端文件卡状态推进** | 字节落盘、`accepted=true`，卡面仍「等你接收」 | ❌ **新发现，未修** |
| **8MB 单文件推送** | `.part` 卡在 `2,097,152` B（= 1 个 2MiB 窗口） | ❌ **新发现，未修** |

---

## 1. 环境与产物（这一轮实际装到设备上的东西）

- HEAD：`b9ca96ce 2026-11-05T17:14:34+08:00`（`origin/main..HEAD = 93`，未 push）。
- 工作区：本轮改了产品源码（`PluginStateManager.kt`、`ChatBubbles.kt`、
  `ScreenHistoryCard.kt`、`desktopApp/build.gradle.kts`）与三个设计工具，
  新增 `CardShapeContractGuardTest.kt`、`tools/verify-luciola-native.py`。
- 真机：Pixel 6，`<device-serial>`，包 `com.kapp.tenon.debug`。
- 宿主：`/Applications/Tenon.app`，冷启动**不设** `TENON_LUCIOLA_NATIVE`
  （即用包里自带 dylib），`TENON_LUCIOLA=1`。

产物双向核对（不是"构建成功"就当装上了）：

```
APK  构建：61,810,057 B  sha256 5dc6e5010126dd4d9cd89d91aa5d6ae57657ea06951fe1d4d2e18119ec32c5ad
APK  已装：同 sha256（adb exec-out cat /data/app/.../base.apk | shasum -a 256）
dylib 包内：3,604,288 B  sha256 454f964e2465e03da4fdbf4af7c02bc79152cab211401f44269d799ec0469caa
            71 项 checksum，0 mismatch，contract version 29
```

> `jpackage` 会重签名，所以包内 dylib 与 `resources/common/native` 里的
> staged 库**字节不同**（3,607,120 → 3,604,288 B）。契约相同才是判据，字节相同不是。

---

## 2. 三个已修缺陷的复验读数

### 2.1 `PluginStateManager` 持久化（原 6 条 FAIL 的根因）

修复前：`plugins-state.json` 被 `mkdirs()` 建成**目录**，写盘异常被静默吞掉，
于是 HTTP 200、内存即时生效，但重启后 settings / disabled 全丢。

复验（同一个真 luciola 宿主）：

- 修复前磁盘现场：`plugins-state.json` 是 `drwxr-xr-x` 空目录。
- phase1 第一次写盘后：变成 `-rw-r--r--` 普通文件，内容 `{}` → 随后真实写入。
- `B10 settings persisted to plugins_state.json` **PASS**（`disk={'model': 'e2e-model', 'timeout': '7'}`）。
- `B13 toggle off persisted in disabled set` **PASS**（`disk=['e2e-verify-plugin']`）。
- phase1 全量：`total=36 passed=36 failed=0`（修复前 `34/2`）。

跨**真进程重启**（kill 宿主 PID → 端口释放 → 重新冷启动 → phase2）：

```
P1 插件目录存活                     PASS
P2 settings 跨重启保留              PASS  {'persist':'proof','round':'1'}
P3 disabled 跨重启保留              PASS  enabled=False status=disabled
P4 状态路径是普通 JSON 文件         PASS  is_dir=False is_file=True
P5 状态文件包含已存 settings        PASS
P6 清理测试插件                     PASS
phase2: total=11 passed=11 failed=0     （修复前 7/4）
```

额外的**冷启动独立证据**（不是靠 HTTP 断言，而是启动日志自己说的）：

```
[RELAY-PLUGIN] skipped e2e-verify-plugin: disabled by state manager
```

宿主启动时就按磁盘上的 disabled 集合跳过了插件 —— 这是"持久化真的生效"最硬的一条。

### 2.2 打包 native 的 UniFFI 契约

修复前：包里 dylib 与 `luciola-jvm` bindings 的 checksum `10/71` 不一致，
真机启动直接 `UniFFI API checksum mismatch`，加密信道起不来。

复验：

- `:desktopApp:verifyLuciolaNative`、`:desktopApp:verifyPackagedLuciolaNative` 都通过。
- 直接对**已安装**的 `.app` 内那份 dylib 再跑一遍校验器：
  `checksums: 71 checked, 0 mismatch` / `VERDICT= MATCH (contract version 29)`。
- 冷启动日志：`grep -c 'UniFFI API checksum mismatch' = 0`，
  且 `[luciola] host configured port=17878 fp=sha256:c35a3` 正常出现。

### 2.3 两个设计工具自检（原 `designContractSelfTest` 在干净 HEAD 上就红）

- `design_contract.scan_render_targets()` 原来用 `os.listdir` 只扫一层，
  预览文件在包目录里 → 归属表静默变空。现改为与
  `scan_desktop_shell_previews()` 共用递归的 `iter_preview_bodies()`，并加空扫报错。
- `design-icon-map.py --self-test` 原来把过期原型事实写死（64/63、`i-image ×2`）。
  现改为**合成重名 sprite 夹具** + 冻结当前原型 sha12 的已知答案（63/63/58）。

复验：`designContractSelfTest` 进入全量门禁一起跑，`BUILD SUCCESSFUL`。

### 2.4 卡片圆角/描边（上一轮 §5d 的修复）

本轮没有回退，全量门禁与截图门禁重跑通过（`validateDebugScreenshotTest` UP-TO-DATE，
5021 测试 0 失败）。根因与改法见 `2026-11-05-ui-shape-full-review.md`。

---

## 3. 数据面重跑：R1 的 4 条 FAIL 是夹具问题，不是产品问题

R1（`passed=27 failed=4`）四条 FAIL 全部是 `C4b` 的级联：
重装 APK 后应用停在**会话列表**（`消息 / 61 个会话 · 2 条未读`），
而旧夹具假定"已经在聊天页"，于是找不到 offer 卡 → 没点「接收」→ 后面三条必然 FAIL。

证据（不是推测）：R1 的 offer 帧其实已经到手机了 ——

```
48933  ... in {"v":1,"type":"file-ctl",... "op":"offer","transferId":"9298f532-...","rootName":"luciola-e2e-1793881338.bin",...}
```

把夹具改成**真 UI 导航**后（底部「消息」tab → 搜索图标 → 输入宿主 nick →
点联系人行 → 会话内上滑到最新），并且把「接收」按钮**绑定到本次文件名所在卡片**
（多次重跑会叠多张 offer 卡，取最低那个会点错），R2/R3 都变成 `0 FAIL`。

R2/R3 的关键读数（两轮都在**真宿主**上，第二轮还是**真进程重启后**）：

| 判据 | R2 | R3 |
| --- | --- | --- |
| 真 luciola 宿主 + 真机发现 | ✅ | ✅ |
| 手机确在 luciola 模式（只听 `17878/17880/17888`，无 `17879`） | ✅ | ✅ |
| `/v1/send` 文本 → 手机 `messages.log` 有原文帧、宿主 `acked=true` | ✅ | ✅ |
| `/v1/send-file` → offer 卡可见 | ✅ `(343, 1963)` | ✅ `(343, 1963)` |
| **真手指点「接收」** | ✅ | ✅ |
| 宿主 `accepted=false→true` | ✅ | ✅ |
| 手机落盘文件字节 **sha256 相同** | ✅ `53b8fc4a…` | ✅ `55f27efe…` |
| 宿主日志出现信道推送 | ✅ `accept-in … push=true` | ✅ `accept-in … push=true` |
| 6 个内置 CLI 席位（`/v1/plugin/states`） | ✅ `installed=8` | ✅ `installed=8` |
| 反向：手机发起的加密信道被接受 | ✅ | ✅ |

> 注意：`/v1/transfers` 里 `state` 一直写 `offered`、只有 `accepted` 变 `true`。
> 这是宿主侧字段语义，不是本轮的判据；签 PASS 用的是 `accepted` + 盘上字节。

---

## 4. 新发现缺陷 A：收端文件卡不推进（**未修**）

**现象**：手机点「接收」→ 字节真的落盘（sha256 一致）→ 宿主 `accepted=true`，
但卡面**一直**是「对方发来的文件，等你接收」，按钮还是「接收 / 拒绝」。
既没有「传输中」进度，也没有「已接收 / 打开 / 分享」。

**最小复现**（不依赖 e2e 脚本）：

```
POST /v1/send-file {to: <手机 nodeId>, path: /tmp/.../probe-state.bin, name: probe-state.bin}
# 手机 UI 出现 offer 卡 → 点「接收」
GET  /v1/transfers        → accepted=true
adb shell run-as com.kapp.tenon.debug ls -la files/relay/downloads/probe-state.bin
                          → 4096 B（完整落盘）
uiautomator dump          → 仍是 text="对方发来的文件，等你接收" + text="接收"
```

实测读数：

```
[luciola] accept fd5cdc84 channel=true files=1 peer=46c23456     ← 信道确实接管了
/v1/transfers: fd5cdc84 probe-state.bin accepted=True
downloads/probe-state.bin/probe-state.bin  4096 B
UI: 对方发来的文件，等你接收 / 接收 / 拒绝        ← 没有推进
```

**为什么值得单独记**：`TransferChannelCompletionTest` 的 KDoc 写的正是
「避免『文件已经收下落盘，但界面卡片因缺事件而停留在等你接收』」，单测是绿的；
真机上这个症状**仍然存在**。这就是本仓库那句「代码写完了不等于验证过了」的现场。

**已排除**：
- 不是夹具问题（用 HTTP + adb 直接复现，没走 e2e 脚本）。
- 不是字节没到（盘上文件大小/sha 都对）。
- 不是 `notice != null` 的早退（`notice` 只用于撤回/拍一拍/群变更）。
- 不是事件流容量（`extraBufferCapacity = 128`，只有 1 条传输）。

**尚未定位**：事件确实由 `TransferManager` 发出之后，是在
`handleOnTransfer → handleUpsertTransferCard → replaceMessage` 的哪一步丢的
（`replaceMessage` 找不到 id 时是**静默返回**，这条路径没有任何日志）。
要定位需要在这条链上补可观测性，属于下一轮的事，本轮**不猜着改**。

---

## 5. 新发现缺陷 B：8MB 单文件推送卡在一个窗口（**未修**）

**现象**：`/v1/send-file` 发 8MiB，手机点「接收」后 `.part` 长到
**正好 `2,097,152` B（2 MiB）** 就不再增长，20 秒内一动不动。

```
downloads/probe-big.bin/probe-big.bin.part   2,097,152 B   (总 8,388,608 B)
```

**为什么是 2 MiB**：`SecureChunkSender` 默认 `chunkSize = 64 KiB`、
`windowChunks = 32` → 在途窗口正好 **2 MiB**。也就是说：**发端填满一个窗口后，
一个 ACK 都没等到**，`send` 就停在那里。

对照：4 KiB / 100 KiB 的单文件都能收完 —— 说明 ACK 通路不是完全不通，
而是大文件跨窗口之后失效。

**尚未定位**：是收端 ACK 帧没发出、还是发端 `onBinaryFrame` 没收到/没记进
`outboundAcks`。宿主日志里这一笔只有：

```
[luciola] accept-in transfer=58d20aaa known=true push=true files=1
[luciola] channel push start transfer=58d20aaa peer=e4bd7b99 files=1
```

之后再无该 transfer 的任何行（也没有 `[chunk] total=… windows=…` 收尾行），
说明 `send` 协程没返回。这条同样需要补观测点后再改，本轮**不猜着改**。

---

## 6. 本轮**没有**验证到的（不许当成通过）

- 收端文件卡的 `Active` / `Completed` 两态（缺陷 A）。
- ≥2 MiB 的单文件推送（缺陷 B）。
- 文件夹、断点续传、拒绝/撤回在 luciola 推送路径下的卡面表现。
- 公网 TLS + pin、跨网文件、投屏。
- iOS 侧任何一条。

---

## 7. 复现命令与证据清单

```bash
# 数据面（HOST_LOG 指向当次宿主 stdout）
LUC_E2E_HOST_LOG=/tmp/e2e-20261105/luciola-host-R2.log \
  python3 /tmp/e2e-20261105/luciola_e2e.py

# 控制面 phase1（宿主需带 TENON_WEBHOOK_SECRET_E2E_SIGNED 启动）
HEAD_SHA=b9ca96ce WEBHOOK_SECRET=luciola-e2e-hmac-20261105 \
  python3 /tmp/e2e-20261105/control_plane_e2e.py

# 真进程重启后 phase2
WEBHOOK_SECRET=luciola-e2e-hmac-20261105 \
  python3 /tmp/e2e-20261105/control_plane_phase2.py
```

本轮证据（本地，均在 `/tmp/e2e-20261105/`）：

```
luciola-e2e-R2.log / luciola-e2e-results-R2.json     34 PASS / 0 FAIL
luciola-e2e-R3.log / luciola-e2e-results-R3.json     34 PASS / 0 FAIL（重启后）
luciola-host-R2.log                                  重启后宿主 stdout
control-plane-phase1-rerun.log                       36/36
control-plane-phase2-rerun.log                       11/11
final-gates-rerun.log                                BUILD SUCCESSFUL / 5021 tests
```

真机截图（`design/runtime-evidence/2026-11-05-rerun/phone-after-real-accept.png`，
本地证据，`.gitignore:54` 本来就不跟踪证据 PNG）——**这张图正是缺陷 A 的现场**：
三张卡（含两张已验收、字节已校验的）全都还写着「等你接收」。
