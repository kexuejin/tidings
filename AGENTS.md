# AGENTS.md — 在本仓动手的约束

> 写给**在本仓改代码的 agent 与人**。入门看 [README.md](README.md) 与
> [ARCHITECTURE.md](ARCHITECTURE.md)；这里只放三类东西：**会判红的**、**会静默丢数据的**、
> **真机上翻过车的**。
>
> 本仓最贵的缺陷不是「编译不过」，而是**发送侧一切正常、接收侧什么都没有**：单测全绿、
> 门禁全绿、照样上线，只在真机上以「手机端怎么没收到」的形式出现。下面每条都对应一次
> 这样的现场。
>
> **读法**：§2 是判据（写代码时得在脑子里），§3 是本仓最贵的一类缺陷，§7 是提交纪律。
> **只在特定时刻用一次的操作步骤不在这里** —— 免得每次会话都替你背一遍：
> git 历史重写 → [CONTRIBUTING §5](CONTRIBUTING.md)；组件共享的四种接缝与验收 →
> [design/component-sharing.md](design/component-sharing.md)；装机与图标重注册 →
> [ACCEPTANCE §1b](ACCEPTANCE.md)。

---

## 0. 地图

| 模块 | 是什么 |
|---|---|
| `:core` | 协议定义、编解码、传输通道、离线队列、会话编排、持久化。**跨平台共享内核** |
| **`:app`** | **领域层**：控制器、UI 状态、存储、数据库（含 SQLDelight 定义）、平台契约。**零 Compose 依赖** —— 换 UI 方案时原样复用。`:domainComposeFreeGate` 与 `:cli` 共同守着这条边界 |
| `:composeApp` | UI 表现层（会话/聊天/节点/Agent 看板/设置/自适应布局）。依赖 `:app`，不含领域逻辑 |
| `:cli` | **没有 UI** 的 榫卯 (Tenon) 客户端。不是产品功能，是「领域层真的能脱离 UI」的**可执行验收物**：只链接 `:app`，classpath 上 0 个 Compose jar，`-verbose:class` 实测 AWT/Swing/Skiko 加载数为 0 |
| `:testsupport` | 源码级守卫测试共用的读盘工具（`GuardSourceScan`）。**只被 `testImplementation` 引用** —— 它曾住在 `:app` 的 main 源集，实测被打进了 `app-jvm.jar` |
| `:agentNode` | Agent 控制面：本地回环 HTTP API（JDK 内置 HttpServer），桌面形态下被 `desktopApp` 同进程引用 |
| `:agent-contract` | 驱动层**纯接口**：会话制契约、事件七型（`Artifact` 是进程内事件、不进协议帧）、驱动键归一。编排层与 `:cli` 因此只依赖契约，不必拖进控制面 |
| `:orchestration` | Agent 编排层：工单编排、责任链执行体、自愈循环、台账治理、卡片补丁、**agent 事件胶水**（原 `DesktopExtension`）。零 UI（诊断/文案走注入端口与 `RelayUiTexts` 桥） |
| `:androidApp` / `:iosApp` | 原生壳：扫码、前台服务、SAF / security-scoped 文件契约 |
| `:desktopApp` | 桌面入口：托盘常驻 + **按需创建窗口**（关窗即销毁），常驻承载 `:agentNode`。`--headless` 不组合 Compose |

源集：`commonMain` / **`jvmSharedMain`**（JVM 与 Android 共享）/ `jvmMain` / `androidMain` /
`iosMain`，各配一个 `*Test`。**`jvmSharedMain` 是 K2 的接缝** —— 新的传输实现写这里，
不要复制成两份；只有真的平台相关（Keychain、JSSE 初始化）才下沉。目前只有 `:core` 与 `:app`
配了它（`:app` 里 `SettingsStore` / `StateLock` 曾各有两个逐字重复的 actual，jvm 与 android
一字不差；现在各一份）。

> **别用 `create("jvmShared")` + `dependsOn`**：KGP 靠名字里的平台前缀推断源集归属，手工造新
> 源集会让默认层级模板失效（`iosMain`/`appleMain`/`nativeMain` 从图上消失，症状是 iOS 报一屏
> "has no actual declaration in module commonMain" —— 报错指向 commonMain，看着像 expect 写错了）。
> 用 `applyHierarchyTemplate`，写法见 `:core` 的 build 文件。

---

## 1. 动手前先跑这些

> **依赖前置：luciola 当前钉的是 `0.11.0-local.1`（未上 Maven Central）。** 本机要先在 luciola 仓库跑
> `scripts/publish-maven-local.sh`，再给**所有** gradle 命令加 `LUCIOLA_LOCAL=1`（`settings.gradle.kts`
> 里那面开关把 `mavenLocal()` 提到最前）；不带它会在依赖解析阶段直接失败，报错只说
> "Could not find io.github.kexuejin:luciola-jvm:0.11.0-local.1"，看着像坐标写错了。
> 原生库与 bindings 必须同版（`copyLuciolaNative` 从 `RustBuild/cargo-target/release` 复制，
> `:desktopApp:verifyLuciolaNative` 逐项比对 UniFFI checksum）——不一致的安装包装完启动即崩，
> 所以这道门**不许跳**，也不许为本机省事去改校验。换回公开发布版时，连这条说明一起改。

```bash
# 八个模块的测试（验收时加 --max-workers=1）
./gradlew :core:jvmTest :app:jvmTest :cli:test :agent-contract:test :orchestration:test \
          :composeApp:jvmTest :composeApp:testAndroidHostTest :agentNode:test \
          :desktopApp:test --continue --max-workers=1

# 门禁（缺一不可）
./gradlew :cjkLiteralGate :constructionTraceGate :staleCommentRefGate \
          :domainComposeFreeGate :commonJvmApiGate :envDependentTestGate :designSpecDrift \
          :designTokenUsageGate :designStateSlotGate :designLocaleParityGate \
          :jlinkModulesGate --continue
python3 tools/unused-imports.py --check
python3 tools/check-design-palette.py --css-vars
```

- **加 `--continue`**：不加的话第一个红就停，你看到的「绿」可能只是没跑到。
- **`--max-workers=1` 只在判「这批能不能交」时加**：八个测试 JVM 并行时异步用例会互相饿死
  （实测在 `UdpTransportBroadcast` / `GroupText` / `TransferPeerCancel` / `FileMessageStore`
  之间旋转，表现为「每轮换一条红、单跑恒绿」）。串行跑一次 3m45s 全绿。日常迭代可以不开。
- **改了并发 / 存储 / 测试替身，另跑 `./gradlew :testStabilityGate`**（约 3.5 分钟）：并发的
  失败是低频且轮换的，单轮全绿区分不了「没缺陷」与「这轮没撞上」。
- 动了 UI / 设计 token / 截图夹具，还要跑 `designScreenDiffGate`、`designIconGate`、
  `designConvergeGate`、`designFormPairingGate`、`designPreviewPairingGate`、
  `designDupFrameGate`、`designDpDebtGate`（都在 `tools/design-contract-gate.gradle.kts` 里注册）。

> **本清单三处同源**：本文件 §1 / README「质量门禁与验证」/ `.github/workflows/ci.yml`。改一处
> 就改三处。它们曾经发散过：README 漏掉 `:app:jvmTest` 与 `:cli:test` 还声称与本文件同口径，
> CI 照 README 抄了一遍 —— 于是 `:cli` 与 `:domainComposeFreeGate` 长期不在 CI 里跑。
> **别再加第四处副本**：`CONTRIBUTING` 就抄过一次，抄的时候丢了四条测试。

---

## 2. 硬约束

### 2.1 门禁各自守什么，破了怎么办

| 门禁 | 守什么 | 破了怎么办 |
|---|---|---|
| `cjkLiteralGate` | 生产源码里不许新增**硬编码中文文案**（注释不算） | 文案进 `composeResources/values*/strings.xml` |
| `constructionTraceGate` | 注释里不许留**日期 / 内网 IP / 私人绝对路径 / 模型名** | 删掉或改成通用说法 |
| `staleCommentRefGate` | 注释里不许写 `X.kt:NN`（重构必腐坏） | 换成**符号名** |
| `domainComposeFreeGate` | 领域层 `:app` 不许 `import compose.*`；**扫描根不存在 / 文件数不足也算失败** | 做成可注入的桥（`RelayUiTexts` 的形状），或列进 `tools/domain-compose-free.py` 的 `INDIVIDUAL_ALLOWED` 并写理由 |
| `commonJvmApiGate` | common 源集不许出现 **JVM 专有 API**（`java.*` / `kotlin.jvm.*` / `putIfAbsent` / `merge` / `computeIfAbsent` / `synchronized` / `String.format` / `@JvmName` …）。症状是 Android 与桌面全绿、**只有 iOS 编不过**，而 iOS 不在收工清单里 ⇒ 会静默躺很久 | 换 common 写法（`getOrPut` / `if (k !in m)` / `Mutex` / 字符串模板）；真只能 JVM 做的放进 `jvmMain`/`jvmSharedMain` + expect/actual；自建同名方法（`SharedMap`/`LockedMap`）列进 `tools/common-jvm-api-guard.py` 的 `ALLOWED` 并写理由 |
| `designSpecDrift` / `designTokenUsageGate` / `designStateSlotGate` / `designLocaleParityGate` | 设计契约：规格漂移、token 采用率、状态槽覆盖、多语言对账 | 见 `design/` 与 `tools/design-*.py` |
| `envDependentTestGate` | 测试结论不许依赖**这台机器**：读网卡/时钟、用固定 `delay` 赌同步都算。例外写进 `tools/env-test-allowlist.txt` 并给理由（`A/B/C/D` 口径见 `tools/env-test-scan.py --help`） | 抽纯函数+合成输入 / 用「确定会发生的事件」做探针 / 真环境测试写清理由后入账 |
| `testStabilityGate` | 同一套测试连跑 N 轮（默认 3），任何一轮红即失败。抓的是「每 3 轮红 1 次」那类竞态 | 先看红在哪几条 → 修测试替身的线程安全（`InMemoryQueueStore` / `InMemoryDedupStore` / `InMemoryMessageStore` 都已改 `MutableStateFlow`），**不要放宽断言** |
| `jlinkModulesGate` | 桌面 jlink 的模块清单与依赖一致 | 更新 `build.gradle.kts` 的模块列表 |
| `tools/unused-imports.py --check` | 没有未使用的 import | 删掉 |
| `tools/check-design-palette.py --css-vars` | 原型 `var(--x)` 不许指向没定义过、也没兜底的变量；`:root` 颜色变量不许在 `html[data-theme="dark"]` 漏覆盖。**画出来的像素查不到「没画出来的颜色」** | 改成语义相同的已定义变量；确属两态同值的颜色变量才进 `DARK_OVERRIDE_ALLOWED` 并写理由 |

**基线是棘轮（只许降不许升）**。`*Baseline` 任务会重写基线文件 —— 只在「确实该新增、且有人
评审过」时用，并在提交信息里写清理由。**为了让门禁变绿而重刷基线是本仓明确禁止的。**

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
| 群文本 `card` | — | 24 KiB（`CARD_BYTES`，超限拒绝不截断） |
| `agent-event` body | — | 64 KiB |
| **整帧硬上限** | — | 64 KiB |

- 发送侧必须自己截断（`NodeOutbound.clampGroupText`；卡片超限由宿主驱动**拒绝**不截断），
  让「不产出注定被拒的帧」成为**不变量**，而不是「希望调用方别写太长」。
- **同一份上限要在发送、接收、队列重放三处一致**。队列重放必须用 `Codec.decodeTcp`；
  用 `Codec.decode`（UDP 那套）会把长消息**从队列里删掉** —— 比发不出去更糟。
- Golden vectors（`GOLDEN_VECTORS` / `EXTENSION_VECTORS` / `OLD_NODE_EXTENSION_VERDICTS`）
  **不要手改**，它们是与上游逐字节对齐的产物。

### 2.3 数据库：迁移与 FTS 不变量

**`.sq` 里的 DDL 只在新建库时执行。** 任何 schema / 触发器 / 索引的改动，**必须同时加一个
`.sqm`**，否则**已存在的库永远拿不到**。真身：FTS 触发器的修复只改了 `.sq`，装新版本
**只换代码不换库** —— 老用户升级后索引照样堆孤儿，产品层面等于没修。

- SQLDelight 版本号 = **迁移文件数 + 1**（当前 15 个迁移 ⇒ 版本 16；别把这个数字当常量记，
  它每加一个 `.sqm` 就腐坏一次 —— 以 `RelayDatabase.Schema.version` 为准）。加 `.sqm` 要同步改
  `ScreenHistoryRecordTest` 里那条**故意写死**的版本断言（它存在就是为了逼你想一遍迁移）。
- **不变量：`MessageFts_docsize` 行数 ≡ `MessageEntity` 行数。** 违反它 = 磁盘被吃光
  （现场：真实消息 941 条 / 索引 4,554,999 条 / 库 21 GB）。
- `INSERT OR REPLACE` 的**隐式删除不触发 `AFTER DELETE`** ⇒ 重写同一条消息前必须先显式
  执行 `insertMessageDropStaleIndex`（FTS5 的 `'delete'` 命令）。
- `AFTER UPDATE` 触发器必须带 `WHEN old.text IS NOT new.text`：交付状态、回执、文件卡片的
  更新与检索无关，动索引只会白写。
- **迁移要自包含**：不要假设某张表存在（老库与迁移夹具都可能没有）。用 `IF NOT EXISTS` 补建。
- 修完存量数据后 `VACUUM` 才会把空间还给系统；只删行不会。
- **加了 `.sqm` 就等于放弃回滚**：旧构建打不开新版本的库（SQLDelight 不做降级迁移），启动
  直接崩在 `createMessageStore` → `openPlatformDatabase`。真机上验证过：把旧 APK 装回去，
  进程起不来。**要在真机上对比新旧版本行为，必须换台设备或先备份库。**

### 2.4 归属与命名：一个事实只有一个来源

- **agent 的发送者标签只有一个来源**：`agentSenderIdFor()`（会转小写）。现拼 `"agent:" + kind`
  会让同一个 agent 在两端显示成两个人（真机库里两种拼写各 801 / 113 条）。唯一例外是**本地
  驱动**那条（它答的是它自己，`agent:<driverId>`）。
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
- **没有需要用户配置的连接开关。** 用户只关心「哪张网都能无感连上」。加设置项之前先问：
  这是不是产品缺陷？（本仓删掉过「跨网通道」开关。）
- 握手用的 `socket.soTimeout` **建连后必须复位** —— 不复位会让每条连接每 8.1 秒断一次，
  而症状看起来像「对端把我踢了」。
- Android 上访问**非默认网络**（蜂窝/Wi-Fi 之外的网）必须**绑网**：路由主表只装默认网络。
  用 `NetworkBinder` / `AndroidNetworkBinder`（公网地址先 `requestNetwork` 再 `bindSocket`）。
- 判「局域网」**只有一条实现**：`IpAddressUtils.isLanAddress`。**IPv6 的 GUA ≠ 公网可达** ——
  中国宽带家庭 LAN 常发全局地址，只认私网会把局域网明文长连接静默关掉。
- 诊断字段要么如实、要么不存在：`dialLastError` 这类「只在失败时写、成功时不清」的字段
  会给出 `connected=true` + `lastError=connect-timeout` 这种自相矛盾的读数。

### 2.6 组件复用能力：一个 UI 元素只许有一处定义

**规则**：同一个界面元素，手机与桌面必须**共用同一份实现**。有独占结构就把它做成参数/接缝，
**不要把内容重抄一遍**。

- **判据**：先问这是**内容**还是**外壳** —— 把外壳换掉之后，这块像素还应不应该一样？
  应该 ⇒ 共享（列表行、详情卡、气泡、按钮、空态）；不应该 ⇒ 它是外壳（两栏、选中态、
  快捷键、菜单栏、chrome），参数化。
- **四种接缝**（代价从低到高）：参数化 → 插槽 → 适配器 → 状态提升。**优先参数化**。
- **放哪**：无领域含义进 `ui/components/`；领域内跨壳就留在 `feature/<x>/` 并提为 `internal`；
  只服务一屏就 `private`。**为了「看起来更共享」把领域组件搬进 `ui/components/` 是错的。**
- **最容易犯的错是「只共享一半」**：合并了行却留着两个列表容器、合并了叶子却留着两个模型
  类型 —— 分叉会从没合的地方长回来。合并顺序：**先统一模型，再统一组件，最后才拆外壳。**

> 真身（2026-10-20）：`ConversationRow` 曾有两份平行实现（手机 16dp 圆角白卡 + 投影，桌面整幅
> 宽平铺行 + 40% 分割线），同一个列表在同一应用里长两个样，用户连问两次「这俩不是共享的吗」。
> 而那条分割线正是「没做圆角行」的**补偿**，不是设计。合并后桌面那个文件 905 行变 686。

四种接缝的具体用法、`designScreenDiffGate` 因此变红时怎么办、五项验收 —— 见
[design/component-sharing.md](design/component-sharing.md)。

### 2.7 可复现性：构建、门禁、依赖 —— 「在我机器上是绿的」不算绿

这一节来自一次真实的推送前评审：单测全绿、门禁全绿，但**别人 clone 下来一行都编不过**。
共同形态是：**结论依赖环境，却没有任何地方把它说出来。**

1. **第三方坐标必须能从公共仓解析。** 只发到 mavenLocal 就入库，等于把「能构建」绑定到
   作者本机。判据：公共仓上的 pom 路径必须返回 200。做不到就 vendored 进仓库并校验
   sha256，或在 CONTRIBUTING 里写清前置步骤。
2. **构建脚本里不许写死个人绝对路径**（作者 home、外接卷）。原生库这类外部产物一律走
   「显式 env / -P → 仓库相对约定目录 → 找不到就**大声说**」，且**缺库时用例要跳过并打印
   怎么补**，不要让它变成运行期的 `UnsatisfiedLinkError` —— 那个报错里没有一个字指向配置。
   反例形态：把个人路径塞进 `orElse(...)` 当默认回退，配置阶段不报错、运行期才炸。
3. **门禁必须只依赖仓库内容。** 不许读 `/tmp` 里的会话状态（真身：某个设计门禁读
   `/tmp/relay-own-scope.txt`，同一个工作树在有/没有该文件时判定相反）；不许依赖被
   `.gitignore` 排除的生成物却把「缺失」判成失败。**缺输入 = 没验到**：默认 `SKIP` 并
   打印「这不是通过」，需要硬失败的入口显式 `--require-artifact`。
4. **门禁的扫描根要覆盖所有生产源集。** 一次重构把代码搬进 `jvmSharedMain` 之后，注释门禁
   的根清单没跟上，那一层就**静默不被扫描**（工具注释自己写着「对不存在的根是跳过，于是
   整层没被扫过会显示成无新增」）。改源集时同步 `tools/construction-trace.py`、
   `tools/construction-trace-gate.gradle.kts`、`tools/stale-comment-ref.py`、
   `tools/cjk-literal-gate.gradle.kts`。**源码根里也不许放 `.md`**：工作笔记会带着日期、
   行号引用和「下一步」睡在源码目录里，而所有注释门禁都只收 `.kt`。
5. **门禁清单三处同源**（见 §1 末的说明）。改一处就改三处。
6. **观测预算不是断言。** 真 UDP / 真 socket 用例里的等待上限是「愿意等多久」，不是
   「允许多慢」。全量串行跑（700+ 用例）时握手明显变慢，预算定在 10 秒会得到「每 3 轮红
   1 轮、单跑恒绿」。给足预算（见 `app/src/commonTest/.../testutil/Await.kt` 的说明），
   但**不许**用它掩盖「条件永远不成立」—— 那种用例单跑也红。

### 2.8 沙箱与文件系统：符号链接与递归删除绝对隔离（血泪禁令）

**规则**：凡是涉及沙箱产物或临时目录清理，**严禁在未清空 `.sandbox-home` 挂载前直接调用标准库的 `File.deleteRecursively()`**。

- **致命成因**：Kotlin 标准库的 `File.deleteRecursively()` 内部通过 `listFiles()` 遍历，
  遇到目录符号链接（Symlink）时会把软链接当普通目录递归下钻，**逐个 unlink 目标目录里的真实文件**，
  最后才删除软链接本身。沙箱为了让 CLI Agent 读凭据把真实 `$HOME`（`.dsh`、`.claude`、`.codex`、
  `.gemini`、`.npm`）以软链接挂进 `.sandbox-home`，任务中止或超时清理时若直接递归删除，会瞬间
  **掏空宿主机机主的真实 HOME 目录**（2026-11-06 事故：58ms 内 298 次 unlink + 189 次 rmdir 全部落在真实用户目录）。
- **防御手段**：所有产物清理必须走 `WorktreeSandboxExecutor.deleteArtifactsSafely()` ——
  对**整个 `artifactsDir`** 做一次**不传 `FOLLOW_LINKS`** 的 `Files.walkFileTree()`，在
  `visitFile` / `postVisitDirectory` 中对每个条目调用 `Files.deleteIfExists()`，把链接本体与
  普通文件一并清空；读不了条目时走 `visitFileFailed` 尽力删、不中断整棵树；末尾**只对根**
  做一次 `Files.deleteIfExists()`。**任何一步都不许调 `deleteRecursively()`。**
- **别只清 `.sandbox-home`（第二个陷阱）**：先清 `.sandbox-home`、末尾再来一次
  `artifactsDir.deleteRecursively()` 的写法，对 `.sandbox-home` **之外**的链接毫无防护 ——
  任务进程本来就能写产物目录，留一个指向 `$HOME` 的软链就够了。实测把那种写法放回去，
  `deleteArtifactsSafelyDoesNotFollowStraySymlinksOutsideSandboxHome()` 立刻红
  （宿主的 `host-secret.txt` 被删）。遍历范围必须是**整棵树**。
- **嵌套链接陷阱（别退回旧写法）**：早期实现只扫 `.sandbox-home` 的**顶层** `listFiles()` +
  `Files.isSymbolicLink()` 逐个摘链接；`prepareSandboxHome()` 后来新增了 `.config/opencode` 这类
  **二级目录下的软链接挂载**，顶层扫描抓不到，`deleteRecursively()` 会沿它下钻到真实 HOME。
  任何「只摘顶层链接」的写法都是缺陷，别用 `isSymbolicLink()` 白名单替代全量清空。
  回归钉子：`WorktreeSandboxExecutorTest.deleteArtifactsSafelyDoesNotFollowSymlinksIntoHostHome()`
  （同时覆盖顶层 `.dsh` 与嵌套 `.config/opencode` 两种挂载），以及
  `deleteArtifactsSafelyDoesNotFollowStraySymlinksOutsideSandboxHome()`
  （覆盖 `.sandbox-home` 之外的人造链接）。
- 严禁任何递归删除操作的物理目标覆盖 `$HOME` 真实根。

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
| 7 | **取值域白名单漏了一个在用值**（键在、值不在） | 整帧 bad-payload 拒收（现场：`agent-ack.kind = allow_session` / `revoke_session`） |
| 8 | **沙箱清理用标准库 `deleteRecursively()` 跟随符号链接** | 宿主机真实 HOME 凭据目录被洗劫掏空（现场：58ms 内 298 次 unlink + 189 次 rmdir） |

第 7 种与第 1 种长得像、成因不同：#1 是**多了一个键**，#7 是**键对、值不在白名单里**。
它比 #1 更难发现，因为发送侧那条构造器是「合法」的 —— `Codec.agentAckPayload(...)` 照拼，
`sendAgentAck` 不校验；拒收只发生在对端解码时。现场的形状是：用户点了「本次免审」、
界面显示已生效、宿主台账一直停在 `pending`，而两端都不报错。

**判据：任何「键 + 取值域」的字段（`kind` / `state` / `op` / `access` / `scope` / `policy`），
取值域只能有一份定义，且必须被编解码校验与所有消费方共同引用。** 本仓的落点是
`AgentAckKinds` / `AgentGrantScopes` / `AgentExecPolicies` 这类对象 —— 各写一份清单
就是在制造第 7 种。

另一个更隐蔽的陷阱：**测试绕过了编解码**。`recordInbound(makeEnvelope(...))` 直接把
信封交给台账，判据落在一半的路上，于是「应用逻辑对了、帧根本到不了」这种缺陷一路绿。
写「回执/动作」类判据时，先问：这条帧**真的过线了吗**？

**动协议 / 传输 / 存储之前，先问：这次会不会新增第 8 种？** 如果会，那条路径需要一个
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
- **装机与重注册**（产物放 `~/Downloads/relay-build-<date>/`、核对 sha256、桌面端必须
  `lsregister` 否则图标不更新、装完 grep 新符号证明装的是新代码）：见
  [ACCEPTANCE §1b](ACCEPTANCE.md)。**不要只看「装上了」。**
- 手机状态探针（`run-as` 可读）：
  - `files/relay/diag/link.json` —— 链路（`closeCount` / `carrier` / `lastError`）
  - `files/relay/stores/known-peers.json` —— 已知对端与真名
  - `files/relay/stores/messages.log` —— 原始信封（**落库时刻在 `at` 列**，不是信封 `ts`）
  - **会话的真身是 `databases/relay.db`（SQLDelight）**；`files/relay/messages.json` 只是它的
    导出，改它没用（应用会按库重新导出）。手机侧**没有 FTS 表** —— FTS 只在桌面端。
- 桌面库在 `~/Library/Application Support/榫卯 Tenon/`：`relay.db` 是 SQLite，可直接 `sqlite3` 查。
- 改手机侧的库要**先 `am force-stop`**，再用 `exec-out run-as cat` 拉、改、`push` 到
  `/data/local/tmp/` 后 `run-as cp` 写回，并删掉 `relay.db-wal` / `relay.db-shm`（否则旧的
  WAL 会把改动盖回去）。

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
  `*.apk` / `*.dmg` / `*.aab`、构建产物、agent 记忆目录（`.workbuddy/` **和** `.workbuddy-ai/`
  —— 两个名字都要写：只忽略带点的旧名，新目录会一直是裸露的未跟踪状态，一次 `git add -A`
  就把逐日笔记带进公开仓）、工作材料（`docs/plans/`、`PLAN.md`、`docs/research/`、
  `docs/runtime-evidence/`）。
- `.gitignore` **只增不删**。加规则时顺手确认「原来的规则一条都没被拿掉」。
- `git add -A` 不会加被忽略的文件，但**别据此推断**「没提交过被忽略的东西」——
  提交前用 `git ls-files | while read f; do git check-ignore -q "$f" && echo "$f"; done` 复查一次。
- 按「一件事一个 commit」拆。**信息里写「为什么」**：现场读数、反向验证结果、
  「老库/老端会怎样」。只写「做了什么」的提交信息在这个仓没有价值。
- `.sq` / 协议 / 传输的改动，提交信息里必须写清兼容性后果。

### 7.0 推送前自检（四条命令，缺一不可）

推送是这个仓唯一**不可撤销**的操作（公开仓，历史一旦上去就删不掉）。逐条跑：

```bash
# ① 这次要推的增量里有没有本机/真机身份（序列号、家网段、个人路径、真 nodeId）
#    序列号按**形态**判（12 位以上大写字母数字），不再写具体那台机器的前缀 —— 它已经从
#    仓库里清掉了，把前缀留在守卫里等于守卫自己又泄露一次。
git diff origin/main..HEAD | grep -nE "^\+.*([A-Z0-9]{12}|/Users/[a-z]|/Volumes/[A-Za-z]|192\.168\.|2408:)" | head
# ② 提交邮箱是不是 noreply（真邮箱写进公开历史后要再重写一次历史才能改）
git log origin/main..HEAD --format="%an <%ae>" | sort -u
# ③ 有没有被忽略的文件其实已入库
git ls-files | while read f; do git check-ignore -q "$f" && echo "IGNORED-BUT-TRACKED: $f"; done
# ④ 依赖能不能在别人机器上解析（mavenLocal-only 的坐标会让 CI 与新人直接编不过）
grep -nE "mavenLocal|SNAPSHOT" settings.gradle.kts gradle/libs.versions.toml
```

第 ④ 条判据是「**别的机器**上能不能解析」，不是「我这台能不能构建」。

**① 是噪声过滤器，不是判据。** `111111111111`、毫秒时间戳、`CONVERSATIONS` 这类常量都会命中
（实测全树 698 处），所以它的用法是「人逐条过一眼这几十行」，**不是**「没输出就等于干净」。
反过来，**别把它改成树级 `git grep 192.168.`**：代码里本来就有一批家网段（为什么留、留了哪些，
见 `docs/privacy-scan-2026-10-29.md` §4），树级扫描会永远红，而永远红的守卫等于没有。

**① ② 只看「还没推上去的东西」**，所以历史重写之后它们是空的 —— 那一刻要查的不是增量，而是
**已经公开的那一份**，见 §7.5。

### 7.1 「一件事」的判据（不是「一次尝试」）

**判据：把这条提交单独 `checkout` 出来，它能不能独立编译、通过门禁，并自己解释清楚为什么？**
过不了这一关，它就不该是一条提交。

反面教材（真实发生）：排查一个面板缺陷，每验证一个假设就提交一次，2.5 小时留下 6 条 ——
诊断栈、改文案、换探活方式、补原因、补 URL、拆状态。那 6 条记录的是**排查路径**，不是交付物；
只有最后那条站得住。后来整段重建为 2 条。

- **探查过程的中间步骤用 `git commit --amend`** 反复改，定稿才落一条；工作记录属于会话，
  不属于 git 历史；
- 一个功能一条提交，**测试与反向验证写进同一条**，不要「先提交实现、再提交测试」；
- 只有**独立的第二件事**（一次文档补充、一次无关重构）才另起一条。

### 7.2 不可分割的改动可以被切成「不可编译的中间态」，但必须写明

全仓改包名 / 改模块边界这类改动，任何中间态都编译不过 —— 切分成「按模块评审」仍然有价值，
**但要在提交信息里明说「只有本序列最后一条是绿的」**，不许让人以为每条都能独立 build。

### 7.3 重写历史（一）：内容不变的重写

只有在「历史本身不可评审」时才重写（提交太碎、混进不该有的文件）。这类重写**不碰任何字节**，
只重新组织提交。三条硬要求：

1. **先做一份真备份**：`git bundle create … --all`。`git branch backup/x` **不算** ——
   filter-repo 重写**所有 ref**，那个分支会被一起映射到新提交上，备份跟着一起被改掉。
2. **用树哈希证明内容零变化**，不要靠肉眼。但它**只证明末端**；而末端就是测试与门禁唯一跑的
   那份，所以此时**不必重跑测试冒充「又验了一次」**。这句话的边界必须写清 —— 否则下一个人
   会拿它给脱敏重写背书（见 §7.4-3）。
3. 用 `git push --force-with-lease`，不要 `--force`：远端若被别人动过应当拒绝，而不是把
   别人的提交冲掉。

filter-repo 会**顺手删掉 `origin` remote**（防误推），跑完要重新 `git remote add` 才能推 ——
别以为那是网络故障。

### 7.4 重写历史（二）：内容必须变的重写（脱敏）

把真机序列号、家网段、个人路径从历史里摘掉只能靠改 blob —— 于是 §7.3 那三条**全部不够**。
七条，每一条都是踩过的（完整命令见 [CONTRIBUTING §5.2](CONTRIBUTING.md)）：

1. **`--blob-callback` 给的是「函数体」，不是「函数定义」。** 写成 `def blob_callback(blob):`
   会造一个**永远不会被调用**的嵌套函数：filter-repo 正常结束、退出码 0、一个字节都没改。
   先用两条提交的临时仓验一遍替换真的生效，再动真仓。
2. **按判据扫，不要按样例扫。** 「我看到的样本」不等于「全集」—— 先按前缀把全集列出来
   （`git grep -nE "(2408|2409|240e):"`），再逐条判定要不要改。
3. **内容变了 ⇒ 必须重跑测试与门禁。** 这跟 §7.3-2 正好相反：脱敏改注释与文档，
   `constructionTraceGate` / `cjkLiteralGate` 都可能因此变红；树哈希也必然全变，证明不了任何事。
   （本仓有一次「全绿」是重写**之前**跑的，重写把一条用例弄红了，没人重跑，红了一整轮才被翻出来。）
4. **所有文档里的短 SHA 立刻失效**（一次脱敏后有 77 处）。用 `.git/filter-repo/commit-map`
   逐条重映射；映射不上的（更早的重写留下的死引用）删掉或换成符号名。**这条也管本文自己**：
   别拿真 SHA 当反例写进文档，要写就写 `<旧短 SHA>` 这种形状。
5. **`--force --tags` 会把本地备份标签一起推上去** —— 等于把旧历史又公开一遍。用
   `--force-with-lease` 且只推分支。
6. **force-push 不能召回旧对象。** 它只移动 ref，旧提交在公开仓上仍可按 SHA 访问，直到删库
   重建或联系平台做 GC。这条纪律的价值是**别让它上去**，不是「上去之后还能补救」。
7. **`--blob-callback` 一个字都不碰提交说明。** 序列号、内网 IP 经常也写在提交信息里
   （「真机 <serial> 的 APK sha256 …」），要挂 `--message-callback`（它必须 `return message`）。
   **而且你自己的提交信息也是泄露面** —— 本仓在这上面栽过两次。

### 7.5 已经公开之后怎么查（树 + 全历史）

重写之后 `origin/main..HEAD` 是空的，§7.0 的 ① ② 查不到任何东西。这时要查的是**已经公开的
那一份**，判据也不同：只查「必须为零」的形态 —— 树 / 全历史 / 从公开仓深 1 克隆复查，
三条命令见 [CONTRIBUTING §5.3](CONTRIBUTING.md)。

`192.168.x.x` **故意不在那三条里**：代码里本来就有一批家网段（理由与清单见
`docs/privacy-scan-2026-10-29.md` §4）。把它加进「必须为零」的检查，守卫会永远红。

---

## 8. 常见陷阱速查

| 你要做的事 | 别忘 |
|---|---|
| 给群文本加可选键 | 加进 `GROUP_TEXT_KEYS` + 两端同版本 + 测试走**解码**而不是只断言 JSON 里有这个键 |
| 改 schema / 触发器 / 索引 | 加一个 `.sqm` + 同步版本断言 + 迁移自包含 + 修完 `VACUUM` |
| 改帧上限 | 发送侧截断 + 接收侧校验 + 队列重放用 `decodeTcp`，三处一致 |
| **在 Kotlin 注释里写路径通配** | `/v1/*` 里的 `/*` 会**开启嵌套块注释**，把文件后半段整个吞掉 —— 症状是几百个 `Unresolved reference`，根因却是一句注释。写「`/v1/` 下的所有路由」 |
| 写「多处必须一致」的纪律 | 加一条**能区分对错**的源码级守卫测试 |
| 修完一个缺陷 | 反向验证：把旧行为放回去，看测试变红 |
| 新增文件 | 确认它是源码 / 测试 / 正式文档，不是临时产物 |
| 写桌面壳里的屏 | 先问「内容还是外壳」：内容与手机共用一份，外壳参数化 —— 照抄 `ChatScreen + ChatScreenChrome` 的形状，别照抄「复制一份再改」（§2.6） |
| 把代码搬进 `jvmSharedMain` / 新源集 | 同步四份门禁扫描根（`construction-trace.py` / `construction-trace-gate.gradle.kts` / `stale-comment-ref.py` / `cjk-literal-gate.gradle.kts`）—— 漏了不会报错，只会静默不扫（§2.7-4） |
| 新增第三方依赖 / 升级上游坐标 | 先确认**公共仓能解析**（不是「我这台能构建」）；mavenLocal-only 会让 CI 与新人直接编不过（§2.7-1） |
| 给 Profile 加可选键（端口一类） | 不只是加字段：`validateProfile` 要校验范围，**消费端还要再校验一次** —— 发现层解码 `peer.extra` 不走 `validateProfile`，越界端口会被 `toUShort()` 静默截断（§3） |
| 让门禁去读一个生成物 | 缺输入 = 没验到：默认 SKIP 并说「这不是通过」，硬失败用 `--require-artifact`；别让门禁读 `/tmp` 里的会话状态（§2.7-3） |
| 加真 UDP / 真 socket 的用例 | 等待上限是观测预算不是断言；`awaitUntil` 默认 30s，别自己写 10s 赌时序（§2.7-6） |
| 合并共享组件之后 `:designScreenDiffGate` 红了 | 那是**量具缺口**不是代码偏离：先逐字确认新值确实在原型里（贴行号）再 `--baseline --reason` 记账，别直接刷（[component-sharing.md](design/component-sharing.md)） |
| **要重写历史**（脱敏 / 重整提交） | 先想清楚 force-push 召不回旧对象；备份用 bundle 不用分支；脱敏后必须重跑门禁并重映射所有短 SHA（§7.3、[CONTRIBUTING §5](CONTRIBUTING.md)） |
| 想确认「仓库里没有本机身份」 | 别用 `git grep 192.168.` 当判据 —— 代码里本来就有一批家网段，那样查永远红。按 §7.5 的「必须为零」形态查 |

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
| 提交规范、门禁命令、**重写历史** | [CONTRIBUTING.md](CONTRIBUTING.md) |
| **组件共享的四种接缝与验收** | [design/component-sharing.md](design/component-sharing.md) |
| 跨网/保活/公开传输的设计与实测 | `docs/cross-network-*.md`、`docs/public-transport-security.md` |
| Agent 协议（PPE-1） | `docs/PROTOCOL-AGENT-WORKSPACE.md` |
| 团队折叠（Team Folder）最终方案：形态、裁决、改造量、验收 | `docs/team-folder-final-plan.md` |
| 团队创建（声明）最终方案：入口、写路径复用、裁决、验收 | `docs/team-creation-final-plan.md` |
| 工作区模型重设计：项目 / 项目目录 / 执行设备三层（D1–D16 裁决、P0–P5 路线、清库重配纪律） | `docs/project-directory-and-execution-site-model.md` |
| 多席位 / 多群 / 会话工作区一致性修正（C1–C5 细则、反向验证、真机判据） | `docs/seat-workspace-consistency-fix-plan.md` |
| 真机证据（文字） | `design/runtime-evidence/` |
| 仓库里哪些真机身份被清掉了、哪些是**有意保留**的 | `docs/privacy-scan-2026-10-29.md`（§4 是「家网段为什么不整体替换」） |

---

## 10. 一句话总结

> 在本仓，「发送成功」不等于「对方收到」，「测试绿」不等于「测到了」，
> 「装了新版本」不等于「库也升级了」。**每一条都有人在真机上为此付过代价。**