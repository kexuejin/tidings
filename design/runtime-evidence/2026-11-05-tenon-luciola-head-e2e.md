# 2026-11-05 榫卯 Tenon / HEAD 真 luciola 全链路 E2E 读数

> 「代码写完了」不等于「验证过了」。本文只记**真手机 + 真桌面宿主 + 真网络帧 + 真 UI 动作 +
> 双端磁盘落盘**的读数；进程内回环、`--dry-run`、只看 HTTP 200、只看截图，都不计入验收。

记录时间：2026-11-05 18:05 +0800。仓库工作树在 `b9ca96ce` 期间保持干净（本轮**未改任何产品源码**，
只新增本证据文件与 `ACCEPTANCE.md` 的读数段落）。

---

## 0. 结论先行

| 面 | 结论 | 读数 |
|---|---|---|
| **luciola 加密数据面（真机 ↔ 真宿主）** | ✅ **PASS** | 两轮各 `34 PASS / 0 FAIL / 3 NOTE`，含真实点「接收」、102400 B 文件双端 sha256 一致、本批次新增 `[luciola] accept-in / channel push` 日志 |
| **宿主重启后数据面复核** | ✅ **PASS** | 第二次 `34 PASS / 0 FAIL` 跑在重启后的 L5 宿主上，手机端自动重新发起加密通道 |
| **新批次控制面（插件生命周期 / CLI 席位 / 注册网关 / Webhook HMAC）** | ⚠️ **功能项 PASS，持久化项 FAIL** | phase1 `34/36`、phase2 `7/11`；全部 6 个 FAIL 指向同一个真实缺陷（§6.1） |
| **legacy 10 夹具** | ❌ **不再作为验收路径** | 本轮 `0/10 PASS`（环境单向不通 + 夹具前提要求关闭加密数据面），且用户已明确「legacy 后续必须废弃」，见 §7 |
| **整体** | ⚠️ **部分通过，不是全绿** | 数据面可签 PASS；控制面因 `PluginStateManager` 持久化缺陷**不签 PASS**，需修复后复验 |

---

## 1. 被测对象与环境

| 项 | 值 |
|---|---|
| 仓库 HEAD | `b9ca96ce 2026-11-05T17:14:34+08:00 docs(brand): 全面升级为「榫卯 Tenon」…` |
| 与 `e54148d7` 的关系 | `e54148d7..b9ca96ce` 仅 docs 变更，无 `.kt/.kts/.java/.swift/.rs/.py/.sh` 改动；`origin/main..HEAD = 89`，未 push |
| 手机 | Pixel 6，serial `<device-serial>`，Android 15，包 `com.kapp.tenon.debug`（`RELAY_E2E_PKG`） |
| 手机 IP / nodeId | `192.0.2.22` / `e4bd7b99…` |
| 宿主 | `/Applications/Tidings.app`（当前 HEAD 的 `createDistributable` 产物），Mac `192.0.2.82` |
| 宿主 nodeId / 控制面 | `46c23456-6e74-4a7c-aab1-20a1d1284cd6`，`127.0.0.1:8788`（`/healthz`） |
| 手机监听端口 | `17878 / 17880 / 17888`，**无 `17879`**（`17879` 是 legacy 明文 TCP 口） |
| 宿主监听端口 | TCP `17878`（luciola 信道）/ `17880` / `17888` / `127.0.0.1:8788` |
| `/v1/peers` 手机项 | `online=true`、`legacyDiscovery="off"` |
| 宿主 luciola 指纹 | `[luciola] host configured port=17878 fp=sha256:c35a3` |
| 配对对端指纹 | `sha256:668c6069bdbdd6074f80f3111bf5bb69bcaefdaad107d75e5ffae15ff85d91ab` |

前置门禁 `scripts/relay-liveness.sh --quiet` 返回 `IDLE / rc=0`（无并发写入）。
`adb devices` 同时挂了 `emulator-5554`，全程用 `RELAY_E2E_SERIAL=<device-serial>` 指定真机。

---

## 2. 真 luciola 宿主是怎么起来的（这一步不写就等于没验证）

桌面宿主有**双开关**：`settings.json` 里 `experimentalLuciola` 默认 `true`（手机侧键缺失即生效），
但 `DesktopLuciolaHost.envEnabled()` 还要求进程 env `TIDINGS_LUCIOLA=1`。
第一次按旧配方重启时**漏了这个 env**，宿主实际跑在 legacy（`17879/17880/17881/17882`、
`/v1/peers` 里 `linkCarrier:"plaintext"`、日志无 `[luciola] host configured`）——
该轮读数**作废**，没有拿它凑证据。

补上 env 后又撞到真实缺陷：`UniFFI API checksum mismatch: try cleaning and rebuilding your project`。
定位过程：

- 打包的 `luciola-jvm-0.9.0-71bf9c83…jar` 期望 contract version `29`，内含 71 个 API checksum；
  与 Gradle 缓存里发布的 0.9.0 jar 的常量**完全一致**（不是仓库改过绑定）。
- `/Applications/Tidings.app/Contents/app/resources/native/libluciola_uniffi.dylib`
  （3,796,784 B，sha256 `2a33229ee95b…`）与仓库资源副本
  （3,800,736 B，sha256 `e88e07bfc840…`）都有 `10/71` 项不匹配。
- 从 luciola 仓库 tag `v0.9.0`（`e4b3327`）离线构建出匹配 dylib：
  `/tmp/e2e-20261105/luciola-v090/target/release/libluciola_uniffi.dylib`
  （3,607,120 B，sha256 `7905b1e72cd1…`，contract `29`，`71 checked, 0 mismatch` → `VERDICT=MATCH`）。

最终**已验证可复现的启动配方**：

```bash
env -u NODE_OPTIONS -u PYTHONPATH -u BASH_ENV \
  TIDINGS_LUCIOLA=1 \
  TIDINGS_LUCIOLA_NATIVE=/tmp/e2e-20261105/luciola-v090/target/release \
  TENON_WEBHOOK_SECRET_E2E_SIGNED=luciola-e2e-hmac-20261105 \
  /Applications/Tidings.app/Contents/MacOS/Tidings --background
```

`TIDINGS_LUCIOLA_NATIVE` 是**运行时覆盖**；仓库内打包的 dylib 未改动（本轮不改源码）。
这构成一个待办：**打包用的 native 资源必须替换为与 0.9.0 绑定匹配的构建**，否则全新安装的用户一开
luciola 就会崩在 UniFFI 校验上。

真实加密通道是**手机主动发起**的（这台 Mac 的 Mac→LAN 单向不通，见 §6.3）：

```
[xport] in state=connected detail=sha256:668c6069…
[xport] in trusted=true fp=sha256:668c6069… pairing=true
```

三次宿主进程的 ESTABLISHED 连接（均 `192.0.2.82:17878 → 192.0.2.22:<ephemeral>`）：

| 宿主 | pid | 手机端口 | 日志 |
|---|---|---|---|
| L3（首个真 luciola） | 6699 | 56120 | `luciola-host-L3.log` |
| L4（带 HMAC 密钥重启） | 19452 | 60254 | `luciola-host-L4.log` |
| L5（持久化重启） | 20522 | 34320 | `luciola-host-L5.log` |

---

## 3. 方法与判据

- 每一批次都用新的唯一 marker（`LUC-E2E-<epoch>`）与新生成的 102400 B 二进制 payload。
- 双端 ground truth：宿主侧 `/healthz`、`/v1/peers`、`/v1/send`、`/v1/send-file`、
  `/v1/transfers`、`/v1/plugin/states` + 宿主 stdout 日志；手机侧
  `run-as … files/relay/stores/messages.log`、`files/relay/downloads/**`、
  `uiautomator dump` 与真实 `input tap`。
- **offer ≠ 文件送达**：`/v1/send-file` 立刻回 `status:"offering"` 只是登记。真实字节必须等手机端
  点「接收」后由 `[luciola] channel push` 推过去，再以手机侧文件的 sha256 判读。
- C8 只检查**本次点击之后新增**且带本批次 `transferId` 前缀的 luciola 日志，
  避免拿上一批次的 `accept-in` 凑数。
- 插件席位的 ground truth 用 `/v1/plugin/states`（已安装/已 seed），**不是** `/v1/plugins`
  （在线注册表，只有活着的插件进程）。上一版脚本查错了端点，产生 6 条假阴性，已修正。

---

## 4. 数据面逐项读数

### 4.1 run2（L3 宿主，marker `LUC-E2E-1793872797`）

```
34 PASS / 0 FAIL / 3 NOTE    退出码 0
payload  luciola-e2e-1793872797.bin  102400 B  sha256=88698ad4ac0685af85790ac6f650e72735a9b830a47f171ae03b8326dd37997f
transfer 429d50e4-2fbb-4b5a-85b9-944845cbffb3
```

| 判据 | 实际 |
|---|---|
| A1–A9 装置面 | 全 PASS：healthz 200、nodeId 对齐、UDP 17878、宿主 `[luciola] configured/discovered`、手机无 17879、`legacyDiscovery=off` |
| B 文本 | `/v1/send` → `status=sent`、宿主 `[send] … acked=true type=msg`、手机 `messages.log` 出现 marker |
| C 文件 | `/v1/send-file` → `offering` + transferId；手机 UI dump 出现文件卡与「接收」按钮 `(343,1963)`；真实 tap；宿主 `/v1/transfers` `accepted=true`；宿主新增 `[luciola] accept-in transfer=429d50e4 known=true push=true files=1` |
| C 字节 | 手机 `files/relay/downloads/luciola-e2e-1793872797.bin/luciola-e2e-1793872797.bin`，102400 B，sha256 与本地**逐字节一致** |
| D 插件 | `/v1/plugin/states` 里 6 个内置席位全部 `status=installed enabled=true`（`claude-cli/codex-cli/agy-cli/dsh-cli/opencode-cli/echo-mock`），共 8 个已装（另两个是在线注册的两个 bridge） |
| E 反向 | 手机发起的加密通道被接受、积压帧在该信道上 `acked=true` |

### 4.2 run3（L5 宿主，marker `LUC-E2E-1793873067`）— 两次真实重启后的复核

```
34 PASS / 0 FAIL / 3 NOTE    退出码 0
payload  luciola-e2e-1793873067.bin  102400 B  sha256=6be049730506d991c5cb17b64784db5d928dd66619530630dd287b9cfcee5190
transfer 15353967-a13d-41a3-8aca-748e1ced23f8
```

与 run2 同判据全绿；`C4 /v1/transfers` 这次返回的正是**本批次** transferId（run2 的 C4 判据写得宽松，
返回的是上一批次记录，故 run2 的 C4 只算弱证据，硬证据由 C4b/C4c/C4d 与 C5–C8 承担）。

另有一次探索性手动批次 `LUC-E2E-1793872114`（sha256 `d1c51a7baa4d…`，transfer `909f0a6e…`）：
点「接收」前 `/v1/transfers` 为 `accepted=false`、手机 downloads 下无文件；点后变 `accepted=true`、
出现 `[luciola] channel push start`、文件落盘且哈希一致。这一组是「offer 不等于送达」的直接对照。

---

## 5. 控制面逐项读数（同一个真 luciola 宿主）

为满足「全部走 luciola」的方向，控制面回路从早期那个未开 luciola 的宿主**整体重跑**到 L4/L5 上。
`TENON_WEBHOOK_SECRET_E2E_SIGNED` 对应 sourceId `e2e-signed`（`SourceWebhookRegistry` 从进程 env 读密钥）。

### 5.1 phase1（L4，`control-plane-phase1-luciola.log`）

```
total=36 passed=34 failed=2
```

- ✅ 已装 5 个 CLI 席位 + echo-mock 的 HTTP 与磁盘 manifest 双向核对（A4/A5）。
- ✅ `valid.zip` 安装 → 目录/manifest/payload 落盘 → states 可见（B1–B3）。
- ✅ 负控：非法 id、缺 manifest、Zip Slip（`security-violation`，且未写出逃逸文件）、缺归档、
  settings 非对象、toggle 缺 bool（B4–B9/B12/B16/B7/B8）。
- ✅ 插件注册网关：无 token/错 token 401、注册 + `baseUrl`、重复端口 409 `port-conflict`、
  非法 `../attack` 400、注销与重复注销 404（C1–C9）。
- ✅ Webhook：generic 归一化、坏 JSON 400、GitHub `issues.opened` 归一化、
  **HMAC 三项**（缺签名 401 / 错签名 401 / 正确签名 200）（D1–D6）。
- ❌ `B10 settings persisted to plugins_state.json`、`B13 toggle off persisted in disabled set`
  —— 同一个持久化缺陷，见 §6.1。

> 注意：B15/B17 里「磁盘上已清除」的断言因磁盘状态本来就空而**弱通过**，
> 不能反过来当成「持久化已验证」。

### 5.2 phase2（L5，真实重启后，`control-plane-phase2-luciola.log`）

重启前由真实 HTTP 写入：安装 `valid.zip` → settings `{"persist":"proof","round":"1"}` →
toggle off；此时 `/v1/plugin/states` 内存态为
`enabled=False, status=disabled, settings={"persist":"proof","round":"1"}`。
重启后：

```
total=11 passed=7 failed=4
P1 插件目录存活                         PASS
P2 settings 跨重启保留                  FAIL  actual={}  （期望 {"persist":"proof","round":"1"}）
P3 disabled 跨重启保留                  FAIL  actual enabled=True / installed
P4 状态路径是普通 JSON 文件             FAIL  is_dir=True is_file=False
P5 状态文件包含已存 settings            FAIL  content=(not a file)
D4–D8 HMAC 五项（含 alt 头 / 未签名源） 全 PASS
P6 清理测试插件                         PASS
```

重启后 `/v1/plugin/states` 的 row 实测：
`{'pluginId': 'e2e-verify-plugin', 'enabled': True, 'status': 'installed', 'settings': {}}`
—— 内存状态全丢，插件目录仍在。这是**跨进程、跨重启**的真实缺陷，不是断言写错。

---

## 6. 真实缺陷与边界（不掩盖）

### 6.1 `PluginStateManager`：状态文件路径被当成目录（阻塞控制面验收）

现象：`~/Library/Application Support/捎话 Tidings/plugins-state.json` 是一个**空目录**（`drwxr-xr-x`），
不是 JSON 文件；HTTP 全部 200、内存状态即时生效，重启即丢。

根因链（本轮只读源码，未改）：

- `DesktopExtension.kt` 构造 `PluginStateManager(File(dataDir, "plugins-state.json"))`；
- `PluginStateManager.stateFile()` 因传入路径以 `.json` 结尾，直接把它当 stateFile 返回；
- `saveState()` 先 `if (!dataDirectory.exists()) dataDirectory.mkdirs()` —— 把**这个文件本身**
  建成目录；随后 `tempFile.writeText(...)` / `renameTo(file)` 抛异常，被
  `catch (_: Throwable) { tempFile.delete() }` **静默吞掉**。

建议修法（待用户决定是否动手）：构造参数改传目录（如 `File(dataDir, "plugins")` 或 `dataDir`），
并迁移/清理已存在的同名目录；`saveState()` 不应再对 stateFile 所在路径做 `mkdirs()`，
异常也不应无声吞掉。修完必须重跑 §5.1/§5.2 才能签 PASS。

### 6.2 打包 native 与 0.9.0 绑定的 UniFFI 校验不匹配

见 §2。运行时用 `TIDINGS_LUCIOLA_NATIVE` 绕过，但**全新安装的宿主仍会崩**。
仓库资源副本与 `/Applications` 打包副本都不匹配，需重建替换并加入打包校验。

### 6.3 这台 Mac 的 LAN 单向不通

phone → Mac 可达；Mac → `192.0.2.0/24` 主动发起报 `errno 65 No route to host`
（公网与 loopback 正常，非命令沙箱问题，`dangerouslyDisableSandbox` 已排除）。
日志里持续出现：

```
[xport] out 192.0.2.22:17878 state=retrying detail=handshake failed: no candidate address reachable
```

真链路靠**手机主动建连**成立；宿主侧主动外连在本机无法验收。这也是 legacy 夹具
`run_all.py` 本轮 `0/10 PASS`（`RUNALL_EXIT=2`，普遍卡在 `sendto`/`handshake-alive`）的环境原因。

### 6.4 诊断语义易误读（不是缺陷，但会让结论写错）

- `Messenger.kt` 在 `tcpOk=false` 时把诊断串写成 `outcome=sent-via-udp`，但同时
  `noteSendChannel(peerId, SendChannel.TRANSPORT)`。**`sent-via-udp` ≠ 走 legacy 明文**；
  本轮 `/v1/peers` 是 `legacyDiscovery=off`、手机无 17879 监听，且有
  `[luciola] accept-in / channel push`，判据以这些为准。
- `/v1/plugins`（在线注册表）与 `/v1/plugin/states`（已安装/已 seed）不是一回事，
  用错端点会产生 6 条假阴性。

### 6.5 其他已知边界

- `WorkflowPluginEngine` 在 main sources 里没有 `/v1/workflow/*` 生产接线（本轮未验，非新回归）。
- 控制面默认 `token = ""`，loopback 路由**设计上 fail-open**；插件注册网关另用
  `TIDINGS_PLUGIN_TOKEN`，两者不要混判。

---

## 7. 方向决策：legacy 废弃

用户明确要求「现在都需要用新的 luciola 的来处，legacy 后续必须废弃」。据此：

- 本轮所有**验收读数**都来自 luciola 宿主 + luciola 手机；曾短暂把手机 `settings.json` 写成
  `experimentalLuciola=false` 做诊断，已按原文件恢复（原文件无该键 ⇒ 默认 `true`）。
- `scripts/e2e/` 的 10 个 legacy 夹具**不再作为验收路径**：它们的前提
  （`scripts/e2e/README.md` §2.1「先把加密数据面关掉并重启节点」）与方向直接冲突，
  且本机 LAN 单向限制让它们连握手都建不起来。本轮 `0/10` 记在这里，**不删除、不假装通过**。
- 后续工作项：把夹具迁移到 luciola 数据面，或直接下线 legacy 套件与对应文档段落；
  在那之前 `ACCEPTANCE.md` §5b 的旧读数仅作历史留档。

---

## 8. 未覆盖 / 不能签 PASS 的项

诚实列出，避免把「部分跑通」写成「全绿」：

- A21 表格里这些**本轮没跑**：双向 ≥50MB 文件、错误配对密钥 `bad_secret`、
  陌生连接 30s 断连、未升级旧客户端的 legacy 拉取回退、关开加密数据面的端口退役对照。
- 物理人交互项（`docs/physical-acceptance-sop.md`）：相机扫码对扫、系统「打开/分享」出口、
  通知栏真横幅、IME 六项、后台不可达等，本轮未自动替代。
- iOS 真机、公网 TLS/pin + 跨网长连接，未覆盖。
- 控制面持久化缺陷未修，**控制面不签 PASS**。

---

## 9. 复现命令与证据清单

```bash
# 数据面（HOST_LOG 可指向当次宿主日志）
LUC_E2E_HOST_LOG=/tmp/e2e-20261105/luciola-host-L5.log \
  python3 /tmp/e2e-20261105/luciola_e2e.py

# 控制面（宿主需带 TENON_WEBHOOK_SECRET_E2E_SIGNED 启动）
WEBHOOK_SECRET=luciola-e2e-hmac-20261105 python3 /tmp/e2e-20261105/control_plane_e2e.py
WEBHOOK_SECRET=luciola-e2e-hmac-20261105 python3 /tmp/e2e-20261105/control_plane_phase2.py
```

| 证据 | 路径 |
|---|---|
| 数据面结构化结果（run2 / run3） | `/tmp/e2e-20261105/luciola-e2e-results-run2.json`、`…run3.json` |
| 数据面 stdout（run2 / run3） | `/tmp/e2e-20261105/luciola-e2e-run2.log`、`…run3.log` |
| 控制面读数 | `/tmp/e2e-20261105/control-plane-phase1-luciola.log`、`…phase2-luciola.log` |
| 宿主原始日志 | `/tmp/e2e-20261105/luciola-host-L3.log` / `L4.log` / `L5.log` |
| 匹配 dylib 的校验 | `/tmp/e2e-20261105/luciola-v090/target/release/libluciola_uniffi.dylib`（71/71 checksum 匹配） |
| 手机侧原始读数 | `run-as com.kapp.tenon.debug` 下 `files/relay/stores/messages.log`、`files/relay/downloads/**` |
| 上次同机型 luciola 先例 | `design/runtime-evidence/2026-11-03-luciola-090-e2e.md` |

脚本为仓库外临时工具（`/tmp/e2e-20261105/`），不是 `scripts/e2e/` 里的产品代码；
仓库内本轮**无源码改动**，`git status --short` 为空（除本证据文件与 ACCEPTANCE.md 读数）。

---

## 10. 结论

可以签 PASS 的只有：**当前 HEAD 的真 luciola 加密数据面，在 Pixel 6 真机与真实桌面宿主之间，
文本、文件 offer、手机端真实接收、102400 B 字节落盘与哈希一致，两轮 + 重启后复核均成立**。

不能签 PASS 的：**新批次控制面的持久化语义**（真实缺陷，6 条 FAIL 同一根因）、
**打包 native 的 UniFFI 一致性**、以及 §8 里所有本轮未覆盖的物理/大文件/配对负控项。

legacy 夹具读数只作历史留档，按用户方向后续废弃。
