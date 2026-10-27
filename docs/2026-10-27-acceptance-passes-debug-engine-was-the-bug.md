# 50MB 验收通过：86-89 MiB/s —— 之前十几轮全在 debug 引擎上测

日期：2026-10-27。**这是把「加密信道慢」这条持续十几轮的误判彻底推翻的一轮。**

## 结果

```
CHANNEL_FILE_E2E size=50MB elapsed=580ms mbps=86.21 sha=da201d2c8a50762a   PASS
CHANNEL_FILE_E2E size=50MB elapsed=564ms mbps=88.65 sha=da201d2c8a50762a   PASS
CHANNEL_FILE_E2E size=50MB elapsed=560ms mbps=89.29 sha=da201d2c8a50762a   PASS
CHANNEL_FILE_E2E size=50MB elapsed=568ms mbps=88.03 sha=da201d2c8a50762a   PASS
```

50MB 走加密信道 **560-580ms（86-89 MiB/s）**，sha256 一致，四次连续通过。
修复前是 **0.15-0.16 MiB/s** —— 差 **538 倍**。

## 根因：验收任务硬编码了 debug 引擎

`core/build.gradle.kts` 里：

```kotlin
tasks.withType<Test>().configureEach {
    systemProperty("jna.library.path", System.getProperty("jna.library.path") ?: luciolaNativeDir)
}

val jvmAcceptanceTest = tasks.register<Test>("jvmAcceptanceTest") {
    ...
    // ↓ 这里
    systemProperty("jna.library.path", System.getProperty("jna.library.path") ?: ".../cargo-target/debug")
}
```

后面的显式设置**覆盖**了前面统一的 release 路径。而**同一个文件上面**就写着：

> 优先 release：debug 引擎的 AES-GCM/握手是未优化的，同一条数据面实测慢 6-8 倍
> （投屏单帧 p95：release 6ms / debug 29ms；50MB 文件 3s vs 46s）。
> **拿 debug 的数去验收会得到「加密信道就是慢」的错误结论** —— 而真机装的是 release。

自己写下的警告，自己没执行。

## 这个 bug 造成的连锁误判

| 我看到的现象 | 实际原因 |
|---|---|
| 吞吐 0.15 MiB/s，翻遍协议层找不到瓶颈 | debug 引擎慢 |
| "帧进了 Rust 队列却没发出去（11776 入队 vs 941 发出）" | 旧 debug 引擎没有新计数器 / 行为不同 |
| 上游 Rust 的三个修复（取消安全 recv、写失败不丢帧、统计字段）"修完吞吐没变" | **验收根本没执行它们**，加载的是另一个二进制 |
| 装 0.4.6 的 6 字段 LinkStats 后 `diagnostics()` 输出空串 | debug 旧引擎只有 4 字段 → 读 stats 抛 `BufferUnderflowException` |

## 关键教训（可复用）

1. **「性能问题」先确认被测二进制是不是你以为的那个。**
   本轮之前还有一次同类误判：用 Mac 的量具去解释 Android 的现象（差 26 倍）。
   判据：`jna.library.path` / `LD_LIBRARY_PATH` / 打包进包里的那份 `.so`，
   三者可以完全不同，而且不匹配时**加载阶段不报错**，只在第一次跨 FFI 读结构体时炸。

2. **诊断代码不许把异常吞成"正常但为空"。**
   `runCatching { session.stats() }.getOrNull() ?: return` 让「读不到计数」和
   「真的没有会话」输出完全一样（都是空串）。现在无会话时会打印
   `none(inboundSession=…,out=…,inByEp=…)`，读失败时打印
   `statsErr=BufferUnderflowException`。**空串不是诊断，是噪音。**

3. **uniffi 的 Kotlin 绑定与本地库必须同版**：改 Rust 结构体后，只
   `cargo build --release` + 重发 AAR **不够** —— 还要重跑
   `uniffi-bindgen --library <dylib> --language kotlin` 并把生成的
   `bindings/luciola-kmp/src/uniffiJvm/.../luciola_uniffi.kt` 覆盖进仓库再发布，
   否则 jar 里的绑定还是旧字段。

## 本轮改动

| 文件 | 改动 |
|---|---|
| `core/build.gradle.kts` | `jvmAcceptanceTest` 的 `jna.library.path` 改用 `luciolaNativeDir`（release 优先），并在注释里写明这次事故 |
| `SecureChannelTransport.diagnostics()` | 空快照说明原因；`stats()` 失败时打印异常而不是静默 return；新增 `enq/deq` 两格 |
| `LinkStatsBindingContractTest`（新，2 条） | 守住「绑定与本地库同版」：读全部 6 个字段；空快照不许返回空串 |
| 上游 `luciola` `8683b60` | 取消安全 recv + 写失败不丢帧 |
| 上游 `luciola` `394525e` | `frames_enqueued` / `frames_dequeued` 导出字段 |
| 上游 `luciola` 未提交 | 版本 0.4.6 + 重新生成的 Kotlin 绑定 |

## 全套状态

```
core      tests= 719  fail=0
coreAcc   tests=   1  fail=0     ← 50MB 验收（86-89 MiB/s）
app       tests= 681  fail=0
compose   tests= 469  fail=0
desktop   tests= 182  fail=0
cargo test -p luciola  21 passed
```

## 对 goal 的影响

goal 第 (3) 步「真机 ≥50MB sha256 一致」的**JVM 侧**证据现在从 46s/1.09 MiB/s 提升到
**0.56s/88 MiB/s**，而且是 release 引擎上的真实读数。真机那一份（Android，90s/0.55MB/s）
需要重新测 —— 它用的 AAR 也是 0.4.5/0.4.6 混着的，同样的道理要核对。

## 引擎身份核对（防止再犯同类错误）

JVM 验收修好之后，顺手把「手机装的到底是哪份引擎」也核对了一遍 ——
判据是**文件 hash 而不是版本号或 strings**：

```
APK  lib/arm64-v8a/libluciola_uniffi.so   201eab4d40395f99094199fb9ecf2d40d5f15316c14e187901a5d669ca90fa04
AAR  jni/arm64-v8a/libluciola_uniffi.so   201eab4d40395f99094199fb9ecf2d40d5f15316c14e187901a5d669ca90fa04
```

两者一致 ⇒ 手机上的 debug 包用的就是 0.4.6 那份引擎（含取消安全 recv、写失败不丢帧、
新统计字段）。**这一条必须每次发版后重做**：AAR 换了而 APK 没重新构建时，两者会静默
地不同，而任何东西都不会报错。

## 还没做的一件事

**真机 50MB 需要重测一次**。上一轮真机读数是 90s / 0.55 MB/s，那时用的是带
「32 倍重传放大」的旧 `SecureChunkSender`；那个缺陷在 `a3a8fc30` 已修，
而真机有真实 RTT，这笔账比回环上大得多。现在引擎身份已核对，重测的结论可信。
