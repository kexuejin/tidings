# P2-21：硬编码中文（CJK）字面量门禁

用户可见的文案必须走多语言资源。这个门禁**只是不许再增加**，不管存量 ——
一次性清零整个仓库会立刻变红，那不是一个能提交的门禁。

## 怎么跑

```bash
./gradlew :cjkLiteralGate             # 与基线比对（CI / 提交前跑这条）
./gradlew :cjkLiteralGateSelfTest     # 词法状态机自检，26 例（改过本目录下任何脚本后跑）
./gradlew :cjkLiteralGateBaseline     # 重写基线（**会改文件**，见下）
```

三条都不需要设备 / 模拟器，主机 JVM 上跑完，秒级。连不上 `dl.google.com` 时加
`-PrelayMirror=aliyun`。

## 扫描范围

相对仓库根目录的五个根：

```
composeApp/src/commonMain
composeApp/src/androidMain
composeApp/src/iosMain
composeApp/src/jvmMain
desktopApp/src/main
```

**为什么连 androidMain / iosMain 一起扫**：文案放在平台源集里同样会发到用户手里，
只扫 `commonMain` 的门禁绕过一次目录就破了。

排除：`/build/`、`screenshotTest*/`、`*Test.kt`、`PreviewState.kt`，以及
`commonTest` / `jvmTest` / `androidUnitTest` / `androidInstrumentedTest` / `androidTest` / `test`
各测试目录。测试里写中文是正常的事。

**刻意不扫** `core/` 与 `agentNode/`：那里的中文大多是异常消息、日志与 CLI 输出，
不是"用户可见文案"；把它们拉进来会得到一份没人会照着改的清单。

## 为什么不是一个 grep

仓库里的中文注释远多于中文字符串字面量，裸 grep 的假阳性会淹掉真命中。
`cjk-literal-gate.gradle.kts` 里的 `KotlinCjkLexer` 是一个小型词法状态机，
按 Kotlin 的规则在 **代码 / 行注释 / 块注释（含 KDoc）/ 普通字符串 / 原始字符串 /
字符字面量** 之间切换，只有**字符串正文**里的 CJK 才算命中：

- `${...}` 切回代码，并用花括号配平 —— 插值里再嵌字符串也能正确归位；
- `\"`、`\'` 不结束字面量；原始字符串里的 `//` 不是注释；
- 多行原始字符串按真实行号报；
- CJK 区段含汉字、假名、谚文、全角标点与扩展 B 区（代理对）；**不含** U+2018/2019/201C/201D
  这几个弯引号 —— 它们在中英混排的注释里到处都是，算进来全是假阳性。

`:cjkLiteralGateSelfTest` 用 26 个样本把这套规则钉住（注释里的中文不算、插值外算、
转义引号、KDoc 代码样例、行号、生产文件判定）。**改代词法器后必须先跑它。**

## 基线：棘轮，按文件计数

基线文件 `cjk-literal-baseline.txt`，每行 `<允许条数>\t<相对仓库根目录的路径>`。

- 某个文件当前条数 **>** 基线 → **失败**，并按 `file:line: "片段"` 列出新增的每一处
  （最多 200 条）；
- 出现基线里没有的文件（含新文件）→ 失败；
- 当前条数 **<** 基线 → 不失败，只提示"可下调"；
- 基线里有、磁盘上已不存在的文件 → 不失败，只提示可清理。

用**条数**而不是 `file:line` 做基线，是因为并行开发时行号天天漂移，按行号存基线会
变成一场没有意义的 rebase；按条数存，只有"真的多了一句中文"才会红。

真的把一批文案资源化了之后：

```bash
./gradlew :cjkLiteralGateBaseline      # 重写基线，然后连同改动一起提交
```

想看失败长什么样、或者要复现"新增一处就红"，不用动仓库里的基线：

```bash
./gradlew :cjkLiteralGate -PcjkGateBaseline=/tmp/某个改小的基线.txt
```

## 落地时的基线（历史快照，不是当前值）

**当前值请直接看 `scaffold/tools/cjk-literal-baseline.txt`**（每行 `<条数>\t<文件>`，
门禁每次都会把「当前 / 基线」并排打出来）。2026-10-07 的实况是 **122 处 / 16 个文件**，
已经比下面这份落地快照降了一半 —— 这张表留着是为了说明棘轮是怎么起跳的，不要拿它当现状。

- 工作树：`HEAD=656125a (dirty)`，2026-10-06 取得；同一时刻 Android / iOS / core /
  desktop 四条线都在工作树里改东西，所以这个数字是那一瞬间的实测值。
- **273 处 / 28 个文件**，与基线逐文件持平。桌面 UI 占绝大多数：`ui/desktop/` 142 处 +
  `desktopApp/` 92 处 = **234 处**，是资源化收益最大的一块。
- 同时用一份独立实现的 Python 原型（同一状态机，仅用于交叉验证、不进仓库）扫同样五个根，
  逐文件条数完全一致。

| 文件（相对 `scaffold/`） | 条数 |
|---|---|
| `composeApp/src/androidMain/kotlin/app/relay/platform/AndroidMdnsAdvertiser.kt` | 9 |
| `composeApp/src/androidMain/kotlin/app/relay/platform/AndroidMdnsBrowser.kt` | 5 |
| `composeApp/src/androidMain/kotlin/app/relay/platform/AndroidPushNotificationHandler.kt` | 2 |
| `composeApp/src/androidMain/kotlin/app/relay/platform/AndroidReceivedFileActions.kt` | 7 |
| `composeApp/src/commonMain/kotlin/app/relay/app/RelayController.kt` | 5 |
| `composeApp/src/commonMain/kotlin/app/relay/app/RelaySettings.kt` | 1 |
| `composeApp/src/commonMain/kotlin/app/relay/feature/nodes/NodesScreen.kt` | 4 |
| `composeApp/src/commonMain/kotlin/app/relay/platform/PlatformContracts.kt` | 6 |
| `composeApp/src/commonMain/kotlin/app/relay/ui/desktop/DesktopChrome.kt` | 32 |
| `composeApp/src/commonMain/kotlin/app/relay/ui/desktop/DesktopComposer.kt` | 12 |
| `composeApp/src/commonMain/kotlin/app/relay/ui/desktop/DesktopConversationsPane.kt` | 18 |
| `composeApp/src/commonMain/kotlin/app/relay/ui/desktop/DesktopFilesPane.kt` | 35 |
| `composeApp/src/commonMain/kotlin/app/relay/ui/desktop/DesktopLayout.kt` | 1 |
| `composeApp/src/commonMain/kotlin/app/relay/ui/desktop/DesktopNavigation.kt` | 3 |
| `composeApp/src/commonMain/kotlin/app/relay/ui/desktop/DesktopNodesPane.kt` | 2 |
| `composeApp/src/commonMain/kotlin/app/relay/ui/desktop/DesktopShell.kt` | 12 |
| `composeApp/src/commonMain/kotlin/app/relay/ui/desktop/DesktopStatusBar.kt` | 13 |
| `composeApp/src/commonMain/kotlin/app/relay/ui/desktop/DesktopTrayMenu.kt` | 12 |
| `desktopApp/src/main/kotlin/app/relay/desktop/DesktopDragOut.kt` | 3 |
| `desktopApp/src/main/kotlin/app/relay/desktop/DesktopHosting.kt` | 3 |
| `desktopApp/src/main/kotlin/app/relay/desktop/DesktopLoginItem.kt` | 9 |
| `desktopApp/src/main/kotlin/app/relay/desktop/DesktopPeerWindow.kt` | 6 |
| `desktopApp/src/main/kotlin/app/relay/desktop/DesktopPlatform.kt` | 4 |
| `desktopApp/src/main/kotlin/app/relay/desktop/DesktopPreferences.kt` | 19 |
| `desktopApp/src/main/kotlin/app/relay/desktop/DesktopServices.kt` | 9 |
| `desktopApp/src/main/kotlin/app/relay/desktop/DesktopWindow.kt` | 33 |
| `desktopApp/src/main/kotlin/app/relay/desktop/Main.kt` | 6 |

基线文件头部记着生成时的 HEAD、扫描范围与总数；**以文件为准**，这张表只是落地时的快照，
而且它记的是 2026-10-06 那一刻的 273 处 / 28 个文件。

## 挂进构建的两种方式

现在是"显式任务"，不是 `check` 的依赖 —— 一个仓库里同时有多条线在改 UI 时，
让存量基线卡住每个人的 `check` 只会换来无脑重跑基线。要挂进 CI 就单独跑
`:cjkLiteralGate`（它自己会在违规时以非零退出码失败）。
`scaffold/build.gradle.kts` 末尾一行 `apply(from = "tools/cjk-literal-gate.gradle.kts")`
把它装进根工程。
