# 隐私扫描：本地设备 / 路径 / 第三方应用 / 过程数据

扫描对象：`git ls-files` 全部已跟踪文件（扫描时的 `main` 顶端）。
扫描口径：本仓是**公开仓**（`github.com/kexuejin/tidings`），判据是「这条读数能不能指向
一台具体设备、一个人、或那个人装了什么」。

> 本文自身已脱敏：设备序列号 / 主机名 / 用户名一律打码成 `<device-serial>`、`<host>`、
> `<user>`，只保留「哪一类、命中多少、在哪个文件」。

## 0. 结论

**有，7 类。** 其中 4 类能唯一指向具体设备或人，而且**从初始提交起就在历史里**：

| 类别 | 命中行 | 文件 | 历史深度 | 风险 |
|---|---|---|---|---|
| 设备序列号 | 22 | 19 | 自初始提交（11 个提交） | **P1 唯一标识** |
| 主机名 | 55 | 21 | 49 个提交 | **P1 唯一标识** |
| 个人路径 `/Users/<user>` | 2 | 1 | 少数提交 | **P1 唯一标识** |
| 本机工作区路径 `/Volumes/<vol>/…` | 21 | 13 | 多数提交 | P2 环境指纹 |
| ISP IPv6 前缀（`2408:…`，中国联通） | 55 | 15 | 多数提交 | P2 运营商+地区 |
| 真 nodeId 前缀 | ~50 文件 | — | 自初始提交 | P2 与主机名互查 |
| 第三方应用包名 | 4 | 3 | 本轮新增 1 处 | P2 装了哪些 App |
| 过程数据（PID / lastUpdateTime / ps / dumpsys / 模拟器 UDID） | — | ~30 | 多数提交 | P3 |

已推送情况：本地 148 个提交，**58 个未推、90 个已在 GitHub**。序列号、主机名、家网段
在 `origin/main` 的树里都还在（分别命中 19 / 21 / 32 个文件）。

## 1. P1：唯一标识（必须改）

### 1.1 设备序列号（22 行 / 19 文件）

`ACCEPTANCE.md`、`core/.../DiscoveryProfileRefreshTest.kt`（**测试夹具钉着真机序列号**）、
`design/runtime-evidence/` 下 14 个证据文件、`scripts/e2e/README.md`、`scripts/e2e/relay_e2e.py`。

这是全仓最硬的一条：序列号 + 型号可以直接定位到一台具体机器，且与下面 1.2 的主机名同现
在同一份文档里（"谁的手机 ↔ 谁的电脑"配对成立）。

处置：`<device-serial>`（文档）/ `SERIAL-TEST-01`（测试夹具，长度与形态无关，只当字符串）。

### 1.2 主机名（55 行 / 21 文件）

- **生产源码 KDoc 里也有**：`RelayUiState.kt`、`SenderIdentity.kt`、`KnownPeerFileStore.kt`；
- 文档：`ACCEPTANCE.md`、`PROTOCOL-EXTENSION.md`、3 个 `design/runtime-evidence/*`；
- 测试：`ExecDeviceRowsTest`、`GroupMentionWithRealDataTest`、`ManualPeerAddressUpdateTest`、
  `QrPayloadBudgetTest`、`RelayUiStateTest`、`SelfHostedAgentMentionTest`、`SenderIdentityTest`、
  `RealDataNameVerifyTest`、`RealDataTriggerVerifyTest`、`PairingEntryPointsGuardTest`、
  `KnownPeerFileStoreTest`、`GroupProtocolTest`、`PeerRegistryRestoreOfflineTest`。

处置：`<host>-97` / `<host>-156.local`（文档）；测试里用 `demo-mac-97` 这类明显合成的名字。

### 1.3 个人路径（2 行 / 1 文件）

`design/runtime-evidence/ios-i5-file-exit.txt`：`export PATH="/Users/<user>/Library/Python/3.9/bin:…"`
以及一条 `StaticText '已存到 /Users/<user>/Library/Developer/CoreSimulator/…'` 的 UI dump。

处置：`/Users/<user>` → `/Users/ci`，模拟器 UDID 一并打码。

## 2. P2：环境指纹

### 2.1 本机工作区路径（21 行 / 13 文件）

`/Volumes/<vol>/source/teahouse-mobile` 出现在：`scripts/bridge/agent_relay_bridge_template.py`
（**默认参数值**）、`scripts/run-live-dsh-flow.py`（2 处）、`scripts/e2e/e2e_workspace_collaboration.py`、
`scripts/verify-dsh-workspace-session.py`（注释里）、`prototype/relay.html`（2 处 mock 文本）、
`prototype/check-refs.py`、`docs/desktop-removable-volume-tcc-investigation.md`（4 处，
还写了"外置 USB 卷"）、`docs/public-transport-security.md`（第三方参考实现路径）、
`design/spec/dsh-tidings-bridge-spec.md`，以及 4 个测试夹具。

处置：`/workspace/teahouse-mobile`（脚本默认值改成必填或 `$PWD`）；测试夹具同上。

### 2.2 ISP IPv6 前缀（55 行 / 15 文件）

`2408:…::` 是中国联通的 IPv6 段，与"某个城市的某个家宽"绑定。13 个测试文件 + 2 个文档。

处置：`2001:db8::`（RFC 3849 文档段）。注意：这些用例靠"GUA 不算局域网"做判据，
`2001:db8::/32` 同样是 GUA，语义不变。

### 2.3 真 nodeId 前缀（约 50 个文件）

`node-46c2…` 这类合成名在 KDoc 与测试里当示例（`QrPairing.kt`、`RelayController.kt` 等），
而 `46c2…` 正是那台桌面的一个真实身份前缀 —— 与主机名互查即可确认"这台机器 = 这个人"。

处置：`node-<8 位合成 hex>`，或直接 `node-aaaaaaaa`。

### 2.4 第三方应用（4 行 / 3 文件）

- `com.example.photoapp`（`ACCEPTANCE.md`、`2026-10-28` 证据：前台抢占导致
  UI 注入失败）；
- `com.example.otherapp`（`2026-10-28` 证据：同一台设备上的另一个应用反复抢前台）；
- `design/runtime-evidence/e2e-20-android-open-share.txt` 也有一处。

「装了哪些 App」本身是画像信息，与序列号同现时尤其如此。

处置：`<前台应用A>` / `com.example.otherapp`。

## 3. 不改的（已经是占位，别误伤）

- `/Users/demo`、`/Users/me`、`/Users/x`、`/Users/someone`、`/Users/work`、`/Users/workspace`、
  `/Users/a&b` —— 明显是占位或语法示例；
- `my-project`、`new-feature-repo`；
- `mac-mini`（`QrPairing.kt` 等 KDoc 里的 `mac-mini`）—— 通用硬件名，不指向具体机器。

## 4. 特别说明：家网段 192.168.2.x **不在代码里整体替换**

命中 284 行 / 83 文件，量最大，但**代码里不能动**：这些地址是
`IpAddressUtils.isLanAddress`、子网广播目标、发现目标、跨网策略等判据的**输入**。
换成 RFC 5737 的 `192.0.2.x` 会让"这是私网地址"的前提失效（TEST-NET-1 不是私网），
一批用例会从"验证行为"退化成"验证一个假前提"。

建议口径：
- **文档 / 证据**里改成 `192.0.2.x`（那些是读数，不是判据）；
- **代码 / 测试**保留 `192.168.2.x` —— 它只说明"某个 192.168.2.0/24 的局域网存在过"，
  与"某台具体设备"没有一一对应关系（全国无数家庭是这个段）。

如果你要求连代码里也换，那就得改成 `10.20.30.x` 这类**仍然私网**的段，并逐条跑测试确认
广播/子网计算没有变。工作量比脱敏本身大一个量级，收益很小。

## 5. 重建 git 的三种做法

前提事实：**泄露已经在公开仓里**，序列号自初始提交起就在（11 个提交），主机名 49 个提交。
改当前文件只能"从现在起干净"，清不掉历史。

| 做法 | 做了什么 | 代价 |
|---|---|---|
| **A. `git filter-repo` 全历史替换 + force-push** | 148 个提交逐个重写 blob，串替换成占位；保留提交结构与提交信息 | 所有 SHA 变化；文档里引用的短 SHA（`01fcedf8`、`ae21370b`、`8ef647b6` 等）全部失效；已 clone 的人需要重新拉 |
| **B. 只脱敏当前文件，新增一个提交** | 最省事 | 历史里仍有；等于只做了一半 |
| **C. 删库重建**（本地重排成少量里程碑提交，或单提交） | 彻底 | 丢 148 个提交的历史；要重新建 GitHub 仓 |

**A / C 都必须知道的一件事**：force-push 只是让分支引用不再指向旧提交，GitHub 上的旧对象
**仍可按 SHA 直接访问**一段时间（默认不会立刻 GC），fork / PR 引用 / 第三方缓存里也可能还在。
要真正清掉，需要：删库重建，或向 GitHub Support 申请对旧对象做 GC。

## 6. 待你决定

1. 替换范围：**只 P1**（序列号 / 主机名 / 个人路径）还是 **P1+P2**（再加工作区路径、
   ISP 前缀、真 nodeId、第三方应用）？
2. 做法：A（重写历史 + force-push）/ B（只清当前）/ C（删库重建）？
3. 家网段：按建议「文档改、代码留」，还是连代码一起换？
4. 是否要我一并把 `/.workbuddy-ai/` 加进 `.gitignore`（现在只有 `/.workbuddy/` 被忽略，
   agent 记忆目录是裸露的未跟踪状态，`git add -A` 会把它带进去）。
