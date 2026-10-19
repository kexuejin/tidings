# AGENTS.md — 在本仓动手的约束

> 这份文件写给**任何在本仓改代码的 agent（以及人）**。它不是入门教程 —— 入门看
> [README.md](README.md) 与 [ARCHITECTURE.md](ARCHITECTURE.md)。这里只放三类东西：
> **会判红的**、**会静默丢数据的**、**真机上翻过车的**，以及它们的来历。
>
> 为什么要有它：本仓最贵的一类缺陷不是「编译不过」，而是**发送侧一切正常、接收侧
> 什么都没有**。它在单测全绿、门禁全绿的情况下照样上线，只在真机上以「手机端怎么
> 没收到」的形式出现。下面每一条都对应一次这样的现场 —— 数字都是真机读数。

---

## 0. 地图

| 模块 | 是什么 |
|---|---|
| `:core` | 协议定义、编解码、传输通道、离线队列、会话编排、持久化。**跨平台共享内核** |
| `:composeApp` | UI 表现层（会话/聊天/节点/Agent 看板/设置/自适应布局） |
| `:agentNode` | Agent 控制面：本地回环 HTTP API（Ktor），桌面形态下被 `desktopApp` 同进程引用 |
| `:androidApp` / `:iosApp` | 原生壳：扫码、前台服务、SAF / security-scoped 文件契约 |
| `:desktopApp` | 桌面入口：窗口、托盘，常驻承载 `:agentNode` |

源集：`commonMain` / **`jvmSharedMain`**（JVM 与 Android 共享）/ `jvmMain` / `androidMain` /
`iosMain`，各配一个 `*Test`。**`jvmSharedMain` 是 K2 的接缝** —— 新的传输实现写这里，
不要复制成两份；只有真的平台相关（Keychain、JSSE 初始化）才下沉到 `jvmMain` / `iosMain`。

---

## 1. 动手前先跑这些

```bash
# 五个模块的测试
./gradlew :core:jvmTest :composeApp:jvmTest :composeApp:testAndroidHostTest \
          :agentNode:test :desktopApp:test --continue

# 门禁（缺一不可）
./gradlew :cjkLiteralGate :constructionTraceGate :staleCommentRefGate \
          :designSpecDrift :designTokenUsageGate :designStateSlotGate \
          :designLocaleParityGate :jlinkModulesGate --continue
python3 tools/unused-imports.py --check
```

动了 UI / 设计 token / 截图夹具，还要跑 `designScreenDiffGate`、`designIconGate`、
`designConvergeGate`、`designFormPairingGate`、`designPreviewPairingGate`、
`designDupFrameGate`、`designDpDebtGate`（都在 `tools/design-contract-gate.gradle.kts` 里注册）。

**加 `--continue`**：不加的话第一个红就停，你看到的「绿」可能只是没跑到。

---

## 2. 硬约束

### 2.1 门禁各自守什么，破了怎么办

| 门禁 | 守什么 | 破了怎么办 |
|---|---|---|
| `cjkLiteralGate` | 生产源码里不许新增**硬编码中文文案**（注释不算，词法器区分状态） | 文案进 `composeResources/values*/strings.xml` |
| `constructionTraceGate` | 注释里不许留**日期 / 内网 IP / 私人绝对路径 / 模型名** | 删掉或改成通用说法 |
| `staleCommentRefGate` | 注释里不许写 `X.kt:NN`（重构必腐坏） | 换成**符号名** |
| `designSpecDrift` / `designTokenUsageGate` / `designStateSlotGate` / `designLocaleParityGate` | 设计契约：规格漂移、token 采用率、状态槽覆盖、多语言对账 | 见 `design/` 与 `tools/design-*.py` |
| `jlinkModulesGate` | 桌面 jlink 的模块清单与依赖一致 | 更新 `build.gradle.kts` 的模块列表 |
| `tools/unused-imports.py --check` | 没有未使用的 import | 删掉 |

**基线是棘轮（只许降不许升）**。`*Baseline` 任务会重写基线文件 —— 只在「确实该新增、
且有人评审过」时用，并在提交信息里写清理由。为了让门禁变绿而重刷基线是本仓明确禁止的。

### 2.2 协议：严格白名单 + 帧预算

**群文本走严格字段白名单**（`CodecMsg.GROUP_TEXT_KEYS`）：多一个键，对端 `hasOnlyKeys`
直接判 extra-field **整帧拒收**。

> **加一个可选键，必须同时加进白名单。** 只加 data class 属性 + 编码器，本端发得出去、
> 本地落库成功、`SendOutcome.SENT` —— 接收侧一条都没有。`senderId` 第一版就是这么漏的。

**旧节点不认识新键 ⇒ 整帧丢弃。** 所以在本仓「加可选键」不是向后兼容，而是**两端必须同版本**。
写提交信息时要写清这一点。

**帧预算是攻击面的一部分，而且发送侧不检查预算不会收到任何错误**：

| 帧 | UDP | TCP |
|---|---|---|
| 单聊 `kind:"text"` | 800 B | 4096 B |
| 群文本 `kind:"group-text"` | 800 B | **32 KiB** |
| 群文本 `reasoning` | — | 24 KiB |
| `agent-event` body | — | 64 KiB |
| **整帧硬上限** | — | 64 KiB |

- 发送侧必须自己截断（`NodeOutbound.clampGroupText` / `clampGroupReasoning`），让「不产出
  注定被拒的帧」成为**不变量**，而不是「希望调用方别写太长」。
- **同一份上限要在发送、接收、队列重放三处一致**。队列重放必须用 `Codec.decodeTcp`；
  用 `Codec.decode`（UDP 那套）会把长消息**从队列里删掉** —— 比发不出去更糟。
- Golden vectors（`GOLDEN_VECTORS` / `EXTENSION_VECTORS` / `OLD_NODE_EXTENSION_VERDICTS`）
  **不要手改**，它们是与上游逐字节对齐的产物。

### 2.3 数据库：迁移与 FTS 不变量

**`.sq` 里的 DDL 只在新建库时执行。** 任何 schema / 触发器 / 索引的改动，**必须同时加一个
`.sqm`**，否则**已存在的库永远拿不到**。

> 真身：FTS 触发器的修复只改了 `.sq`，装新版本**只换代码不换库** —— 老用户升级后索引
> 照样堆孤儿。产品层面等于没修。

- SQLDelight 版本号 = **迁移文件数 + 1**（9 个迁移 ⇒ 版本 10）。加 `.sqm` 要同步改
  `ScreenHistoryRecordTest` 里那条**故意写死**的版本断言（它存在就是为了逼你想一遍迁移）。
- **不变量：`MessageFts_docsize` 行数 ≡ `MessageEntity` 行数。** 违反它 = 磁盘被吃光
  （现场：真实消息 941 条 / 索引 4,554,999 条 / 库 21 GB）。
- `INSERT OR REPLACE` 的**隐式删除不触发 `AFTER DELETE`** ⇒ 重写同一条消息前必须先显式
  执行 `insertMessageDropStaleIndex`（FTS5 的 `'delete'` 命令）。
- `AFTER UPDATE` 触发器必须带 `WHEN old.text IS NOT new.text`：交付状态、回执、文件卡片
  的更新与检索无关，动索引只会白写。
- **迁移要自包含**：不要假设某张表存在（老库与迁移夹具都可能没有）。用 `IF NOT EXISTS` 补建。
- 修完存量数据后 `VACUUM` 才会把空间还给系统；只删行不会。
- **加了 `.sqm` 就等于放弃回滚**：旧构建打不开新版本的库（SQLDelight 不做降级迁移），
  启动时直接崩在 `createMessageStore` → `openPlatformDatabase`。真机上验证过：
  把旧 APK 装回红米，进程起不来。**要在真机上对比新旧版本行为，必须换台设备或先备份库。**

### 2.4 归属与命名：一个事实只有一个来源

- **agent 的发送者标签只有一个来源**：`agentSenderIdFor()`（会转小写）。现拼
  `"agent:" + kind` 会让同一个 agent 在两端显示成两个人（真机库里两种拼写各 801 / 113 条）。
  唯一例外是**本地驱动**那条（它答的是它自己，`agent:<driverId>`）。
- **用户自己说的话 = 用户的 nodeId**，不是 agent。转发进群时**不要另算发送者**：
  本机落库用什么，线上就用什么。
- **合成名（`node-<8 位>`）是「还不知道它是谁」的占位**，一旦知道真名就要顶掉，而且
  **三处都要**：节点表（`PeerRegistry.replacesSyntheticName`）、通讯录便条
  （`manualPeers[].name`）、群成员列表（`group.memberNicks`）。启动时要对一遍账 ——
  不能等用户重新配对才换。
- 群成员的昵称是**纯展示**：不参与成员判定与权限，越界键拒整帧。

### 2.5 传输与安全

- **公网必须 TLS + 双向 pin，验不过就拒**（fail-closed）。**没有「降级成明文出网」这条路**；
  也不要用一个开关来表达安全策略。
- **没有需要用户配置的连接开关。** 产品立场：用户只关心「哪张网都能无感连上」。
  加设置项之前先问：这是不是产品缺陷？（本仓删掉过「跨网通道」开关。）
- 握手用的 `socket.soTimeout` **建连后必须复位** —— 不复位会让每条连接每 8.1 秒断一次，
  而症状看起来像「对端把我踢了」。
- Android 上访问**非默认网络**（蜂窝/Wi-Fi 之外的网）必须**绑网**：路由主表只装默认网络。
  用 `NetworkBinder` / `AndroidNetworkBinder`（公网地址先 `requestNetwork` 再 `bindSocket`）。
- 判「局域网」**只有一条实现**：`IpAddressUtils.isLanAddress`。**IPv6 的 GUA ≠ 公网可达** ——
  中国宽带家庭 LAN 常发全局地址，只认私网会把局域网明文长连接静默关掉。
- 诊断字段要么如实、要么不存在：`dialLastError` 这类「只在失败时写、成功时不清」的字段
  会给出 `connected=true` + `lastError=connect-timeout` 这种自相矛盾的读数。

---

## 3. 「静默丢失」清单 —— 本仓最贵的一类缺陷

**共同形态**：发送侧编码成功、本地落库成功；接收侧什么都没有。日志里最多一次 decode
失败。**没有异常、没有回执、用户只会说「没收到」。**

**「没收到」有两种时间形态，别只看一种就下结论**：

1. **当场就没了** —— 发送侧以为发出去了（`SendOutcome.SENT`），接收侧整帧丢弃。
2. **过一会儿才到** —— 接收侧拒收 ⇒ 发送侧等不到 ACK ⇒ 那条留在队列里**反复重试**，
   直到接收端能收下为止。于是「用户当时没收到」与「库里最终有这条」同时成立。

真机上把这两种读反过一次：红米库里三条 >4096 的群消息，信封 `ts` 是 18:35–19:03，
但**落库时刻全是 19:57:06**（红米换上新构建之后补发的）。
**判据：看落库时刻（`messages.log` 的 `at` 列），不要看信封 `ts`。**

| # | 成因 | 真机表现 |
|---|---|---|
| 1 | 载荷带了白名单外的键 | 整帧 extra-field 拒收 |
| 2 | 正文超过接收侧字节预算 | 整帧 bad-payload 拒收（现场 140 条群消息，最大 9902 字节） |
| 3 | 队列重放用了 UDP 的预算 | 从队列里**被删掉** |
| 4 | `INSERT OR REPLACE` 留下孤儿索引 | 磁盘被吃光（21 GB） |
| 5 | 无条件 `AFTER UPDATE` 触发器 | 同上，更快 |
| 6 | 合成名没被真名顶掉 | 用户看到一台陌生设备 |

**动协议 / 传输 / 存储之前，先问：这次会不会新增第 7 种？** 如果会，那条路径需要一个
「发送侧不产出注定被拒的帧」的不变量，而不是一次截断。

---

## 4. 测试纪律

- **每条修复都要反向验证**：把旧行为放回去，看新测试**变红**，再恢复。变不红 = 这条测试
  没在测东西。（本仓有一条测试就是这么发现「其实一直假绿」的。）
- **当心假绿**，两种真实踩过的形态：
  1. **源码级守卫被自己的注释绊倒** —— 注释里正当提到那个函数名，守卫就红了。守卫要
     只看活代码（剥掉 `//` 之后的部分）。
  2. **断言了不可区分的东西** —— 「队列被清空」既可能是补发成功、也可能是被判坏帧删掉。
     要断言能区分两者的量（例如「补发的必须是同一条 id」）。
- **源码级守卫在本仓是正当手段**：用来守「多处必须一致」这类类型系统表达不了的纪律
  （如「三条转发路径都要带同一个发送者」）。但它必须能区分对错，别写成永远为真。
- 测试文件里的中文**不受 CJK 门禁约束**（门禁只扫生产源码），可以放心写中文断言消息。
- 并发/时序类测试不要用固定 `delay` 赌时间：用轮询 + `withTimeout`（见 `LinkDialDiagnosticsTest`）。

---

## 5. 真机验证纪律

- **`closeCount` 是「链路有没有抖」的唯一判据**：换网、断网、冷启动全程应当为 **0**。
- `Timings.offlineAfter = 90s`：完全断网后节点最多再显示 90 秒「在线」。**别等 10 秒就判缺陷。**
  （要判它真的会翻，等过 90 秒再看 —— 30/60/100 秒三档是标准做法。）
- 屏幕方向/尺寸会让 uiautomator 坐标变；**坐标一律从 dump 里现取**，别写死。
- `adb shell input text` **会丢字符**、注入不了中文；断言别用输入内容做唯一标识。
- 装机与验证流程：
  - 产物统一放 `~/Downloads/relay-build-<date>/`；**报告前核对 sha256**。
  - APK：`adb -s <serial> install -r -d <apk>`；
    桌面端：`osascript -e 'quit app "Tidings"'` → 替换 `/Applications/Tidings.app` → `open -a`。
  - **不要只看「装上了」**：在新产物里 grep 一个只有新代码才有的符号名，证明装的是新的。
  - 手机状态探针：`files/relay/diag/link.json`（链路）、`files/relay/messages.json`（会话）、
    `files/relay/stores/known-peers.json`（已知对端）、`files/relay/stores/messages.log`（原始信封）。
- 桌面库在 `~/Library/Application Support/捎话 Tidings/`：`relay.db` 是 SQLite，可以直接 `sqlite3` 查。

---

## 6. 文档纪律

- **README 是产品口径**：回答「现在是什么样」，不是「怎么变成这样的」。不要写日期、
  真机读数、内部函数名、路线图代号（P4）、「已交付/未落地」这类过程叙述。
- **限制要如实，但站在用户视角说**：「Agent 附件只能看不能保存」，不是「缺 `fileId`/路径」。
  如实 ≠ 暴露实现。**每条限制都要保留**，只是换说法。
- **改了协议事实要同步的地方**（漏一处就是文档在撒谎）：`PROTOCOL-EXTENSION.md`、
  `PROTOCOL-COVERAGE.md`、`ARCHITECTURE.md`、`SECURITY.md`、`ACCEPTANCE.md`、
  `scripts/e2e/README.md`。最常见的错误是**同一份文档内部自相矛盾** ——
  §7 的表还写着 4096，而同一份文档的 §7.1 说 32 KiB。
- 验收证据的正式去处是 **`design/runtime-evidence/`**：**文字入库、截图不入库**。
  不要在 `docs/` 下新建证据目录（那份是工作材料，已被忽略）。
- 引用证据文件时**先确认它真的在版本库里** —— 引用一个被忽略的路径等于断链。
- 改了协议还要看 `PROTOCOL-RATIONALE.md`（设计理由）与 `ACCEPTANCE.md`（真机怎么验）。

---

## 7. git 纪律

- **绝不入库**：密钥（`link-key.pk8` / `link-cert.der` / `link-pub.spki`）、`*.log` / `*.db` /
  `*.apk` / `*.dmg` / `*.aab`、构建产物、`.workbuddy/`、工作材料（`docs/plans/`、`PLAN.md`、
  `docs/research/`、`docs/runtime-evidence/`）。
- `.gitignore` **只增不删**。加规则时顺手确认「原来的规则一条都没被拿掉」。
- `git add -A` 不会加被忽略的文件，但**别据此推断**「没提交过被忽略的东西」——
  提交前用 `git ls-files | while read f; do git check-ignore -q "$f" && echo "$f"; done` 复查一次。
- 按「一件事一个 commit」拆。**信息里写「为什么」**：现场读数、反向验证结果、
  「老库/老端会怎样」。只写「做了什么」的提交信息在这个仓没有价值。
- `.sq` / 协议 / 传输的改动，提交信息里必须写清兼容性后果。

---

## 8. 常见陷阱速查

| 你要做的事 | 别忘 |
|---|---|
| 给群文本加可选键 | 加进 `GROUP_TEXT_KEYS` + 两端同版本 + 测试走**解码**而不是只断言 JSON 里有这个键 |
| 改 schema / 触发器 / 索引 | 加一个 `.sqm` + 同步版本断言 + 迁移自包含 + 修完 `VACUUM` |
| 改帧上限 | 发送侧截断 + 接收侧校验 + 队列重放用 `decodeTcp`，三处一致 |
| 想加设置开关 | 先问这是不是产品缺陷（本仓删掉过「跨网通道」开关） |
| 写用户可见文案 | 进 `composeResources/values*/strings.xml`，别硬编码中文 |
| 在注释里写行号 / 日期 / 内网 IP | 都不允许；用符号名 |
| 写「多处必须一致」的纪律 | 加一条**能区分对错**的源码级守卫测试 |
| 修完一个缺陷 | 反向验证：把旧行为放回去，看测试变红 |
| 新增文件 | 确认它是源码 / 测试 / 正式文档，不是临时产物 |
| 报告真机结果 | 先 grep 新产物里的新符号，证明装的是新代码 |

---

## 9. 深文档索引

| 想知道 | 看 |
|---|---|
| 系统怎么分层、模块职责 | [ARCHITECTURE.md](ARCHITECTURE.md) |
| 协议扩展（本仓加的可选键与消息类型） | [PROTOCOL-EXTENSION.md](PROTOCOL-EXTENSION.md) |
| 上游协议覆盖到哪、哪些有意不做 | [PROTOCOL-COVERAGE.md](PROTOCOL-COVERAGE.md) |
| 每条协议约束**为什么**是这样 | [PROTOCOL-RATIONALE.md](PROTOCOL-RATIONALE.md) |
| 安全模型、信任根、传输两档 | [SECURITY.md](SECURITY.md) |
| 真机怎么验、每条判据是什么 | [ACCEPTANCE.md](ACCEPTANCE.md) |
| 提交规范、门禁命令 | [CONTRIBUTING.md](CONTRIBUTING.md) |
| 跨网/保活/公开传输的设计与实测 | `docs/cross-network-*.md`、`docs/public-transport-security.md` |
| Agent 协议（PPE-1） | `docs/PROTOCOL-AGENT-WORKSPACE.md` |
| 真机证据（文字） | `design/runtime-evidence/` |

---

## 10. 一句话总结

> 在本仓，「发送成功」不等于「对方收到」，「测试绿」不等于「测到了」，
> 「装了新版本」不等于「库也升级了」。**每一条都有人在真机上为此付过代价。**
