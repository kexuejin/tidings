# 触发式编排与插件分支：静态/单测验证读数（2026-11-09）

> **这份证据只证明「分支在 JVM 单测 + 源码门禁层面全绿」。它不是真机验收，也不是 luciola 链路验收。**
> 本轮没有真手机、没有真宿主、没有真网络帧、没有手机 `run-as` 落盘、没有真实 UI 动作。
> 按仓库纪律（`AGENTS.md §5`、记忆中的 2026-11-05 约定），不得把下列读数反填进 `ACCEPTANCE.md`
> 的真机表格，也不得据此声称三阶段「已交付」。

## 1. 被测对象

| 项 | 值 |
|---|---|
| worktree | `.agents/worktrees/feat/agent-trigger-plugin` |
| 分支 | `feat/agent-trigger-plugin` |
| HEAD | `121d8227`（`docs(spec): §0 矩阵与 §9 状态按实现同步——三阶段落地对账`） |
| 相对 `main` | 验证时 ahead 15 / behind 0；`main` HEAD `346f972a` |
| 读数后的提交 | 按 §7.1 拆成 4 条提交（`121d8227`、`121d8227`、`121d8227` + 本证据这条），详见 §5；所有读数都是**这 4 条之前**的工作树上跑出来的 |
| 机器 | <host>-156.local，Gradle 9.8.0 |
| 验证时工作树 | dirty（强制重跑时 7 个本轮改动文件；写完证据与挂链后增至 9，见 §5）；门禁日志均如实标 `HEAD=121d8227 (dirty)` |

规格文件：`docs/AGENT-TRIGGER-AND-PLUGIN-SPECIFICATION.md`（三阶段 C1–C13）。

## 2. 本轮修复了什么（相对分支 HEAD 的未提交改动）

1. **恒真探针**：`TriggerEventDrainTest` 里 `TestAwait.condition("重放的有界观测窗") { true }`
   让「重放不得二次派发」的观测窗变成空等。改为确定性的第二事件 `evt-2` 收口：
   等 `evt-2` 被同一条消费循环处理完，再断言 `evt-1` 恰好 1 次、`handled.size == 2`。
2. **固定 `delay` 赌时序**：`DesktopHostSchedulerTest` 原有 12 处 B 类信号
   （`delay(25)` / `Thread.sleep(200)` 后断言）。全部改为 `TestAwait.condition()`
   （等到可观测信号）与 `TestAwait.quiet()`（有界窗口内持续确认禁止信号不出现）；
   工作池排空用 `CountDownLatch(4)`，不用 `inFlight == 0` 这种会假绿的瞬态探针。
   改后该文件 `delay(` 残留 0 处。**没有加 `env-test-allowlist` 白名单。**
3. **软链接安全修复回迁**：分支把 `WorktreeSandboxExecutor` 退回成只扫顶层
   `Files.isSymbolicLink()` 的旧写法，漏掉 `prepareSandboxHome()` 的
   `.config/opencode` 二级软链接挂载，会让 `deleteRecursively()` 下钻真实 `$HOME`。
   已恢复不传 `FOLLOW_LINKS` 的 `Files.walkFileTree()` 全量清空 `.sandbox-home`，
   并补 `prepareSandboxHome(..., hostHome = ...)` 的嵌套挂载与两条回归钉子
   （顶层 `.dsh` + 嵌套 `.config/opencode`）。
4. **文档同步**：`AGENTS.md §2.8` 与主工作区逐字一致（diff 为空）；规格文档中与实现
   不符的表述逐条同步（`filter` 规则链已落地、`DriverLineLimits` 单行熔断、
   `DesktopHostScheduler` 已建、C8 独立只读通道、`targetConvId` 未协议化、软链接修复已入分支）。

## 3. 最终一次强制重跑（本证据的主读数）

命令（`AGENTS.md §1` 全量清单 + `--rerun-tasks`，保证不是缓存复用）：

```bash
./gradlew :core:jvmTest :app:jvmTest :cli:test :agent-contract:test :orchestration:test \
  :composeApp:jvmTest :composeApp:testAndroidHostTest :agentNode:test :desktopApp:test \
  :cjkLiteralGate :constructionTraceGate :staleCommentRefGate :domainComposeFreeGate \
  :envDependentTestGate :designSpecDrift :designTokenUsageGate :designStateSlotGate \
  :designLocaleParityGate :jlinkModulesGate --rerun-tasks --continue --max-workers=1
python3 tools/unused-imports.py --check
python3 tools/check-design-palette.py --css-vars
```

读数：

```
=== FORCED FULL VERIFY 2026-11-09 09:39:37 ===
BUILD SUCCESSFUL in 7m 44s
97 actionable tasks: 97 executed
GRADLE_EXIT=0
OK：没有未使用的 import。                                   IMPORTS_EXIT=0
OK：原型 CSS 变量无死引用、深色无漏覆盖                     PALETTE_EXIT=0
=== END 2026-11-09 09:47:22 ===
```

`97 executed / 97 actionable` 是这轮的关键：九个测试任务全部实际执行
（日志里每条都是 `> Task :xxx` 而非 `UP-TO-DATE`）。此前一次常规全量跑
（09:32）虽也 `GRADLE_EXIT=0`，但九个测试任务全是 `UP-TO-DATE`，因此不作为
交付读数，只留作过程记录。

### 3.1 测试报告汇总（663 份 XML，全部由本轮写出）

报告 mtime 区间 09:40:19–09:45:39，**早于 09:39:39 的报告数 = 0**，无旧结果混入。

| 模块 | 报告数 | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|---:|
| core | 179 | 899 | 0 | 0 | 32 |
| app | 129 | 854 | 0 | 0 | 0 |
| cli | 3 | 7 | 0 | 0 | 0 |
| agent-contract | 5 | 19 | 0 | 0 | 0 |
| orchestration | 27 | 144 | 0 | 0 | 0 |
| composeApp（jvmTest 137 + testAndroidHostTest 51） | 188 | 1090 | 0 | 0 | 0 |
| agentNode | 65 | 491 | 0 | 0 | 1 |
| desktopApp | 67 | 367 | 0 | 0 | 3 |
| **合计** | **663** | **3871** | **0** | **0** | **36** |

本轮改动的测试类（均在本轮重新执行）：

| 测试类 | tests | failures | errors |
|---|---:|---:|---:|
| `WorktreeSandboxExecutorTest` | 20 | 0 | 0 |
| `DesktopHostSchedulerTest` | 8 | 0 | 0 |
| `TriggerEventDrainTest` | 6 | 0 | 0 |
| `TriggerChannelWiringGuardTest`（结构守卫，未改但复核） | 5 | 0 | 0 |

### 3.2 十一条门禁读数

| 门禁 | 结果 |
|---|---|
| `cjkLiteralGate` | 通过（当前 72 = 基线 72，只降不升） |
| `constructionTraceGate` | 扫描 19 根，命中 0；基线 0 |
| `staleCommentRefGate` | 扫描 25 根；broken 0 / ambiguous 0 |
| `domainComposeFreeGate` | 131 个领域文件，0 处 Compose import |
| `envDependentTestGate` | **扫描 623 个测试文件；A/B 类 0 个**（修复前 `DesktopHostSchedulerTest.kt` 报 B=12） |
| `designSpecDrift` | tokens 与代码逐键一致；18 组件规格 / 40 状态槽 / 6 门禁引用完整 |
| `designTokenUsageGate` | colors 34/47、elevation 3/3、iconSizes 6/6、shape 5/5、spacing 17/17、typography 11/11、weights 1/1；棘轮无退化 |
| `designStateSlotGate` | 68/68 覆盖（基线 65），0 缺口档位无降级 |
| `designLocaleParityGate` | values-en 缺失 0 / 多余 0 / 含中文 0 / 空串 0 |
| `jlinkModulesGate` | 镜像 12 模块；代码依赖 5 模块全在镜像内 |
| `unused-imports.py --check` | OK，无未使用 import |

`check-design-palette.py --css-vars`：`prototype/relay.html` 定义 64 变量 / 936 处
`var()`（带兜底 2），`:root` 颜色 35 / 深色覆盖 34 / 白名单 1，行内硬编码色 39 处（仅盘点）。

### 3.3 稳定性门禁（`AGENTS.md §1`：改了并发/测试替身必跑）

本轮改了调度器时序用例并新增共享 `TestAwait` 助手，因此单独跑：

```
=== STABILITY GATE 2026-11-09 09:33:12 ===
=== run 1/3: :core:jvmTest :app:jvmTest ===  PASS
=== run 2/3: :core:jvmTest :app:jvmTest ===  PASS
=== run 3/3: :core:jvmTest :app:jvmTest ===  PASS
BUILD SUCCESSFUL in 6m 12s
STABILITY_EXIT=0
```

注意：该门禁只重复跑 `core` + `app` 的异步重灾区，**不覆盖** `orchestration` /
`agentNode` 的本轮改动；那两个模块的全量执行读数在 §3.1。

### 3.4 写完证据与 ACCEPTANCE 挂链之后的门禁复核

本证据文件与 `ACCEPTANCE.md §5g` 是在强制重跑之后写入的。为免「验完又改文档没人复验」，
对最终树重跑了 9 条门禁（同样带 `--rerun-tasks`）：

```
=== FINAL GATES 2026-11-09 09:50 ===
BUILD SUCCESSFUL in 45s
9 actionable tasks: 9 executed
GATES_EXIT=0
OK：没有未使用的 import。   IMPORTS_EXIT=0
```

读数与 §3.2 一致：`constructionTraceGate` 0 命中、`staleCommentRefGate`
broken 0 / ambiguous 0、`envDependentTestGate` A/B 0、`designSpecDrift` OK。
**没有产品源码在这两处文档编辑中被改动**，测试报告无需重跑。

## 4. 明确「不是通过」的项与已知噪声

- `:desktopApp:verifyLuciolaNative` / `:verifyPackagedLuciolaNative` **SKIPPED**：
  `resources/common/native` 没有 native 库，日志原话「这不是通过 —— 契约没有验」。
  要验需设 `TENON_LUCIOLA_NATIVE` 后重跑 `copyLuciolaNative`。
- `:core` 配置期提示「luciola 原生库未找到 —— 依赖真引擎的用例将被跳过（不是失败）」，
  与 36 条 `skipped` 的口径一致，不能算作通过。
- 编译期 warning（非失败）：`DesktopExtension.kt` 的 unnecessary safe call、
  若干 `No cast needed`；Gradle 9.8 对 Gradle 10 的弃用告警。本轮未处理。
- 原始日志在本机 `/tmp/tenon-trigger-verify-rerun.log`、
  `/tmp/tenon-trigger-stability.log`、`/tmp/tenon-trigger-verify-final.log`，
  **属会话产物不入库**；本文件是入库的读数摘要。

**本轮完全没跑**：真手机 / 真桌面宿主 / 真网络帧 / 手机 `run-as` 落盘 / 真实 UI 动作 /
`scripts/e2e/` 夹具 / luciola 原生契约校验 / 公网 TLS+pin / 跨网文件。按 2026-11-05 的
验收口径，这些仍是空白，不得由单测绿推定。

## 5. 提交拆分与推送前自检

读数记录时 HEAD 是 `121d8227`；之后按 `AGENTS.md §7.1`「一件事一个 commit」把 9 个文件
拆成 4 条提交，每条都能独立编译、门禁通过，并自带「为什么」：

| # | SHA | 主题 | 文件数 |
|---|---|---|---:|
| 1 | `121d8227` | `fix(orchestration)` 触发测试改确定性观测，恒真探针与 12 处固定 `delay` 全清 | 3 |
| 2 | `121d8227` | `fix(agentNode)` 沙箱产物清理恢复全量清空 `.sandbox-home`，堵住嵌套软链接下钻 | 3 |
| 3 | `121d8227` | `docs(spec)` 三阶段状态按实现逐条对账 | 1 |
| 4 | 本条提交 | `docs(evidence)` 记录验证读数并挂 `ACCEPTANCE.md §5g` | 2 |

`TestAwait.kt` 与本证据文件先前一直是未跟踪状态（`??`）；`git show HEAD` 确认
**已提交的历史里没有任何地方引用 `TestAwait`**，所以旧提交本身能编译，新助手是随
第 1 条完整引入的。若当初只提交被跟踪文件，两个 orchestration 测试会立刻编译不过。
4 条提交之后工作树干净：暂存 0 / 未暂存 0 / 未跟踪 0。

### 5.1 `AGENTS.md §7.0` 四条推送自检（本轮实跑）

- **① 本机身份（噪声过滤器）**：`origin/main` 为 `029e174a`，**落后本地 `main` 24 条**，
  按 `origin/main..HEAD` 会把那 24 条的改动也算进「增量」，噪声很大。改按真实增量
  `main..HEAD` 重跑，**只有 2 条命中**，都是形状噪声：文件名
  `+++ b/docs/AGENT-TRIGGER-AND-PLUGIN-SPECIFICATION.md` 与规格 ID
  `SPEC-ORCHESTRATION-2026-11-03`。无序列号、家网段、个人路径。
- **② 提交邮箱**：`kexuejin <168250+kexuejin@users.noreply.github.com>`，noreply 形态。
- **③ 被忽略却已入库**：0 条。
- **④ 公共仓依赖解析**：`settings.gradle.kts:39` 的 `mavenLocal()` 被
  `if (System.getenv("LUCIOLA_LOCAL") == "1")` 包住、默认关闭；分支没有改这个文件，
  不是 mavenLocal-only 坐标。注意这是**读配置得出的结论**，没有真在干净机器上解析一次。

## 6. 合并对账：对主工作区未提交状态做 merge-tree 干跑

主工作区此刻有 **78 个已改 + 30 个未跟踪**文件，直接 merge 会把别人正在做的改动搅进去。
为把「10 个重叠文件」从猜测变成事实，做了一次不改任何 ref 的干跑：用临时
`GIT_INDEX_FILE` 把主工作区脏状态写成一棵 tree，`git commit-tree` 造一个
**不指向任何 ref 的临时快照提交**（parent = 本地 `main`），再跑
`git merge-tree --write-tree --messages <快照> feat/agent-trigger-plugin`。

- **A) 干净 `main` vs 分支：EXIT=0，零冲突** —— 分支 19 条提交本身可干净合进已提交的 `main`。
- **B) 脏 main 快照 vs 分支：EXIT=1，恰好 4 个冲突**。所以真正会拦住合并的是 4 个，不是 10 个。

| 冲突文件 | 类型 | 两侧各改了什么 | 裁决建议 |
|---|---|---|---|
| `agentNode/.../WorktreeSandbox.kt` | content | 主工作区 919 行：已有 `walkFileTree` 安全清理 + `prepareSandboxHome(hostHome=...)`，**没有** C11/C12；分支 987 行：同一份安全清理 **+** `DriverLineLimits` 单行熔断 + `OsSandboxGuard` opt-in 包装 | 三方合并，两边功能都要留；`deleteArtifactsSafely` 段两侧本应一致，**不得退回顶层 `isSymbolicLink()` 写法**（§2.8 血泪禁令） |
| `agentNode/.../WorktreeSandboxExecutorTest.kt` | content | 两侧**都有** `deleteArtifactsSafelyDoesNotFollowSymlinksIntoHostHome` 钉子，只差注释与断言消息措辞（17 行差异） | 保留一条钉子即可，取分支版更明确的断言消息；别把两条重复测试都留下 |
| `docs/AGENT-TRIGGER-AND-PLUGIN-SPECIFICATION.md` | **add/add** | 主工作区那份是**未跟踪的旧稿**（389 行，状态「演进提案/规划中」）；分支是已提交的新稿（396 行，「三阶段已落地」）。旧稿独有 44 行**全部是过时状态表述**，没有新内容 | 取分支版；旧稿先另存备份再处理，不要直接删（它没进版本库） |
| `docs/THIRD-PARTY-AGENT-DEVELOPMENT-KIT.md` | content | base 是「榫卯 Tenon」；主工作区脏改动把它**改回「榫卯 Tenon」**（产品命名进行中）；分支只加了「未知键显式拒绝（严格解析）」那一段 | 保留主工作区的命名裁决，再把分支新增的那一段手工并进去；别让分支版把命名改动冲掉 |

**自动合并成功但不等于语义正确**的 4 个：`SandboxedCliDriver.kt`、`RelayDatabase.sq`、
`DesktopExtension.kt`、`DesktopWorkerOrchestrator.kt`。其中 `RelayDatabase.sq` 最需要复核：
主工作区改了 `MessageEntity` 的 SELECT 列（`notice` / `screen_json` / `card_json` /
`card_payload`、`LIMIT 1→20`）并加 `selectConversationSummary`；分支加的是
`SchedulerTaskState` / `TriggerEvent` 的 CREATE 与查询，区域不重叠所以文本自动合并。
迁移链目前不撞号（主工作区最新 `20.sqm`、版本断言 21；分支新增 `21.sqm`、断言 22），
但**合并后必须重跑 `:app:jvmTest` 与 schema 版本断言**，不能只看「没冲突」。
另外 2 个重叠文件 `AGENTS.md`（两侧已逐字一致）与 `ACCEPTANCE.md`（分支只加 §5g）
自动合并无冲突。

**本轮没有执行 merge、push 或重写历史。** 顺序应当是：人工按上表裁决 → 在干净工作区
合并 → 重跑 §3 的全量验证 → 再谈推送。
