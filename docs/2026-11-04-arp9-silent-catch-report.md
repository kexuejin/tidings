# ARP-9：静默 `runCatching` 普查报告（2026-11-04）

> 计划书原文（`2026-11-04-arch-debt-remediation-plan.md` ARP-9）：
> 「core/transport + core/persist + app 存储路径的 `runCatching` 无 `onFailure` 处数测量 →
> ≤20 处逐处补诊断记账，**否则只出报告不改码**（防『永远红的守卫』）」

## 结论先说

**实测 357 处静默，远超「≤20 处」的阈值 ⇒ 按计划书的后半句执行：不批量改码。**

但普查本身撞出一条比"处数"重要得多的东西：**落盘路径上的 `onFailure` 是死分支**（见下
「发现 1」）。所以本轮做了三件事：出这份报告、把那条真缺陷修掉（一行诊断 + 四个
`persist()` 的 onFailure）、给下一轮留下可执行的名单。

> 下面所有数字是**本轮修复前**的普查结果（357 处静默）。修完那 4 处 `onFailure` 后复跑
> 为 353 处（`有 onFailure` 13 → 17），差额正好是本轮动过的那四个 `persist()`。
> 复跑：`python3 tools/silent-catch-report.py`。

## 口径与复现

```bash
python3 tools/silent-catch-report.py            # 汇总表
python3 tools/silent-catch-report.py --list 写   # 列出「写类函数」里的每一处
python3 tools/silent-catch-report.py --json     # 给后续处理用
```

扫描根：`core/…/transport`、`core/…/persist`、`core` 的两个平台源集、`app/…/domain`、
`app/…/db` 与 `app` 的四个平台源集（共 9 个根）。只统计**静默**：`runCatching { }` 之后
既没有 `.onFailure { }`，也没有把 `Result` 交出去。

| 形态 | 处数 | 意思 |
| --- | --- | --- |
| 有 `onFailure` | 13 | 有痕迹 |
| 链式折默认值（`getOrNull` / `getOrDefault` / `getOrElse`） | 151 | 失败被**有意**折成「没有」 |
| 作为返回值（`= runCatching { … }`） | 5 | 交给调用方 |
| **丢弃结果** | **206** | 异常彻底消失 |

静默合计 **357**；按宿主函数名粗分：写 72 / 读 49 / 其他 236。
再收窄到「落盘」语义（`persist|save|write|appendLine|rewrite|store|delete|remove|clear|…`，
并排除 `send|close|emit|publish|announce|request`）⇒ **49 处**。

> 口径诚实说明：「写 / 读」是**按函数名猜**的启发式，只用来排序，不用来定罪。
> 逐个看上下文仍是最重要的一步 —— 下面「发现 2」就是抽查出来的。

## 发现 1：落盘路径的 `onFailure` 永远不会触发（本轮已修）

`core/…/persist/FileStores.kt` 的 `rewriteLog` 是四个 peer store 与消息库**唯一**的写入出口，
它**没有失败通道**：

```kotlin
val opened = openFresh(path) ?: return          // 拿不到句柄 → 直接返回
runCatching { deleteFile(path) }                // 删不掉 → 自己吃掉
```

于是调用方那句 `runCatching { … rewriteLog(…) }` 在磁盘满 / 目录只读 / 路径变成文件时
**不会进 `onFailure`** —— 它是死分支。症状统一成一句话：

> 改了设置、加了节点、信任了一台设备，**重启之后全没了**，而且任何地方都没有痕迹。

这直接决定了 ARP-9 的做法：照处数"补 `onFailure`"在落盘路径上是**表演**（补了也不会响）。
本轮改成在唯一知道「哪个文件、丢了多少字节」的地方说一句话：

- `rewriteLog`：`openFresh` 失败时打 `[persist] rewrite failed: cannot open <path> (<n> B dropped)`；
- 四个 `persist()`（pin / known-peer / endpoint / tag）：补 `.onFailure { reportPersistFailure(path, t) }`，
  覆盖 `append` 抛错那条路；
- 措辞抽成 `reportPersistFailure`，四个副本不许分叉（`grep '\[persist\]'` 是它唯一可靠的用法）。

判据（`core/src/jvmTest/.../PersistFailureVisibilityTest.kt`，2 例）：

1. 用**真实失败条件**（父路径是一个普通文件）触发，断言诊断里有 `[persist] rewrite failed:
   cannot open` + **文件路径** + `B dropped`，且不抛给调用方；
2. 源码门：四个 store 的 `persist()` 都必须调 `reportPersistFailure(path, t)` ——
   只修一处等于留三个缺口，而用例 1 只覆盖 `FilePeerPinStore`。

反向验证两条：抹掉 `rewriteLog` 里的诊断 ⇒ 用例 1 红；删掉 `KnownPeerFileStore` 的
`onFailure` ⇒ 用例 2 红。均已还原。

## 发现 2：剩下的 357 处绝大多数**应当**静默

抽查 12 处「丢弃结果」（随机种子固定），9 处是这类：

```kotlin
runCatching { socket.close() }                    // 关连接，失败没有后续动作可做
runCatching { connection.send(Ping) }             // 心跳，失败由「判死」逻辑兜底
runCatching { previous.close() }                  // 被替换的旧连接
```

它们**不是缺陷**：失败之后没有可执行的补救，也不改变状态机。给它们逐处加日志，代价是
把刚建好的 500 行环形缓冲（ARP-8）刷成噪音 —— 真出问题时反而更难读。

「链式折默认值」那 151 处同理，而且更明确：`load()` 读不出来就返回空表是**写进 KDoc 的纪律**
（"读不出来/写不进去一律降级为这份当空表"），不是疏忽。

**这就是不批量改码的理由**：357 处里能靠"补一句 onFailure"真正改善的，是"失败 = 用户的
东西丢了"那一小撮；其余补了只是让日志更长。

## 下一轮名单（49 处里挑，按代价排序）

| 批 | 位置 | 为什么 |
| --- | --- | --- |
| 一 | `SqlDelightMessageStore.save` ×2 / `clear` | 消息本体；写失败 = 用户以为保存了 |
| 一 | `MessageStore.{jvm,android,ios}.save` / `clear` | 同上（旧实现三条并行） |
| 一 | `SettingsStore.jvmShared.save`（iOS 钥匙串那侧待设备验证） | 「改了设置、重启回滚」是用户级投诉 |
| 一 | `RelayControllerAgentComponent.saveWorkspace` / `saveTask` | 工单/工作区是编排的持久事实 |
| 二 | `FileDedupStore.appendLine` / `writeWholeFile` | 去重索引坏了会重复下载 |
| 二 | `FileStores.appendLine` ×2 / `rewriteLog` 的 `deleteFile` | 只剩"删旧文件失败"这一条 |
| 二 | `LuciolaTransportFactory.save` ×3 / `persist` ×2 | 传输身份/租约 |
| 二 | `RecoveryPendingPeers.save`、`RelayControllerCabinetComponent.persistShareSettings`、`writeLinkDiagnostics` | 恢复与诊断自身 |

顺序有意：先"用户丢了东西会来投诉"的，后"只有排障时才看"的。每一批都要像本轮一样
**先把失败通道确认存在**（`? : return` 型的静默改了也不会响）。

## 为什么不把它接成门禁

「每处 `runCatching` 都要 `onFailure`」这条规则第一天就会产出 357 条待办，然后所有人
学会无视它 —— 这正是计划书写「防『永远红的守卫』」的意思。工具留在
`tools/silent-catch-report.py`：**报告，不是门禁**。等上面两批做完、静默降到个位数时，
再讨论要不要按文件计数设一个棘轮（像 `cjk-literal-gate` 那样）。
