# luciola 投屏延迟：工程结论与测量纪律（过程记录合并稿）

日期：2026-10-27。这份代替了排查过程中散落的 20 余份"第 N 轮"记录 ——
只留**可复用的结论、判据与陷阱**，过程叙述不进仓库。

最终数据、判据与门禁见 `2026-10-27-screen-5min-acceptance-0.4.6.md` 与
`2026-10-27-screen-criterion-redefined-with-gate.md`。

## 1. 最终结论

| 项 | 结果 |
|---|---|
| 帧率 | 4.908 vs legacy 4.909 fps = **99.98%**，39 个窗口零降档 |
| 延迟 | 55.16ms vs legacy 36.57ms = **1.51×** |
| 第二段（字节搬运） | luciola **0-4ms** vs legacy 3-8ms —— 这一段我们更快 |
| 最差窗口 | luciola 258ms vs legacy 304ms —— luciola 更稳 |

原判据"延迟 ≤ 1.11× legacy"**经算术证明不可达**：即使把跨 FFI 的成本完全归零
（手机 tx 从 13.0ms 回落到 legacy 的 2.75ms），luciola 也只到
`55.16 − 10.25 = 44.91ms = 1.23×`，仍 > 1.11×。

## 2. 量具口径（这一项最容易出错）

| 量具 | 量什么 | 不能用来做什么 |
|---|---|---|
| `rxseg` | 发 screen-next → 收到帧头 / 帧头 → JPEG 齐 | — |
| `txseg` | 泵内一次 send 的 wrap 与投递耗时 | **不能**与 Mac 上的数比（见下） |
| `txsplit` | 手机侧节流等待 vs 采样编码 | — |
| `txbackoff` | 队列满后的退避次数 | — |

**纪律：量具必须与被测平台一致。** 早期用 Mac 上 130µs 的 `send` 去解释 Android
的 3.5ms，得出过一串错误结论；反过来也踩过：用 `tx`（采样→写完，混入并行工作）
做跨通路比较，它会被任意"让泵等待"的改动轻易改善。

## 3. 四轮"一帧一次 send"的失败原因（都别再试）

| 做法 | 失败原因 |
|---|---|
| 泵里 `withTimeoutOrNull` 等后续块 | Unconfined 上会让出，泵停摆（只发出 78B） |
| 自建 `WholeFrameWriteChannel` | ktor `writeFully` 写进它自己的内部 flush 缓冲，绕不过 |
| `readAvailable(offset>0)` 累积读 | 该重载**会试图填满**，第二次读永远等不到 |
| 在泵里按协议帧边界重组 | 与测试夹具的字节流假设冲突，4 个画面测试全红 |

**成功的那次**：每次 `readAvailable(offset == 0)` 读进独立 scratch 块，再用
`ByteReadChannel.availableForRead`（非挂起）把**已到达的**字节一次取完再 send。
段数 659/10s → 96（一帧一段），第二段归零。

## 4. 已撤回的结论（别再从这些出发）

| 曾认为 | 实际 |
|---|---|
| 加密慢（22ms 是加密代价） | 加密一次 49KiB 只要 **143µs** |
| `ByteChannel` 容量 4096 造成背压 | 真实 `CHANNEL_MAX_SIZE` 是 **1 MiB** |
| 队列满后退避是主因 | 真机 `txbackoff backoffs=0` |
| Android `send` 是 8-10ms 的结构性固定税 ⇒ 架构下限 | **单独测 `send(78KB)` 只要 0.59ms**；投屏中是 5.3ms（并发态） |
| 1.11× 可达 | 算术证明不可达（1.23× 是零 FFI 的天花板） |

## 5. 测试夹具的四条配对纪律（进程内起两条真实 luciola 信道）

缺一条就会卡在 `pairing rejected reason=pairing_timeout` 或"握手完成但帧进不来"：

1. **两端都要监听**（`listenPort > 0`）——只拨不听的一端会让对端等配对帧等到超时；
2. **必须调 `requestPairing`** —— `pairPeer` 只记"我扫过你的码"，**不发任何帧**；
3. **两端都要有 registry 条目** —— 入站帧按 from/握手指纹反查端点，查不到就丢弃；
4. **发起方要用扫码式配对**（带 secret）—— 只写信任表不够，`!trusted` 直接拒收。

第 5 条（另一次踩到）：**`SecureChannelTransport` 默认只把 `FileChunkFrame` 当二进制
数据面帧**，其余非 Envelope 字节会被丢弃 —— 夹具若灌裸字节，Rust 侧 `recv` 有计数、
JVM 侧一帧都收不到。

## 6. 传输窗口的容量判据

**窗口必须显著小于 transport 的队列深度。** Rust 出站队列是 `mpsc::channel(64)`：

- 窗口 32（2MiB）真机 2.89s / 零重传放大；
- 窗口 64（= 队列容量）**真机卡死**：接收端只写到 4,194,304 字节（正好一个窗口）就
  再无进展——填窗把队列吃满后 `try_send` 持续失败、每 2ms 退避一次，而真实链路不可能
  在 2ms 内腾出一格，退避循环与 ACK 等待互相拖死。回环上队列排空是微秒级，掩盖了这个失效。

更根本的判据：**"队列满"应当是背压信号（等可写通知），而不是"退避若干毫秒再试"**。

## 7. FFI 现状（有数、但没定论）

| 调用 | 空闲 | 投屏中 |
|---|---|---|
| `isConnected()`（零字节） | 7µs ~ 295µs | **2.6-4.9ms** |
| `send(78KB)` | **591µs** | 3.9-6.9ms |
| 同一调用在宿主 macOS | ≈0µs | 0.4-0.55ms |

手机 `top` 显示 800% CPU 里 671% idle ⇒ 不是 CPU 饱和。把入站回调改成非阻塞投递
（架构更正确）**没有改善**这两个数 ⇒ 卡住 FFI 调用线程的不是我们的 `runBlocking`。

剩余解释只剩两条（都指向"热点路径不该用 JNA"）：JNA 的 upcall/downcall 互斥；
或 ART 上 JNA 分派本身在某些状态变慢。**判别实验做了 4 轮没做成，所以
"优化上游能拿回多少"目前仍无证据 —— 在拿到对照之前不要动上游。**

## 8. 两个测量陷阱

1. **Rust 的 `println!`/`eprintln!` 都不进 Gradle 的测试结果 XML**
   （system-err 长度 0；system-out 里只有 Kotlin 的 `System.out`）。
   证明 Rust 侧计数只能用 uniffi 导出字段，不能靠打点看日志。
2. **`MutableSharedFlow` 的订阅时序是"静默零收"的高发点**：`replay=0` 时早到的帧会被
   无声丢弃，而 `tryEmit` 还返回 true。凡是"Rust 说收到了、JVM 说没收到、两边都不报错"，
   先查订阅者有没有挂上。
3. **uniffi 的 Kotlin 绑定与本地库必须同版**：改 Rust 结构体后只 `cargo build` + 重发
   AAR 不够，还要重跑 `uniffi-bindgen` 并覆盖仓库里的 `luciola_uniffi.kt`，
   否则读 `stats()` 会 `BufferUnderflowException`。`LinkStatsBindingContractTest` 守这一条。

## 9. 若要继续优化，顺序是

1. 先修好"在宿主上造出真实入站压力并确认收集器真的在收"的夹具，拿到
   "投屏中 vs 投屏中但链路空闲"的 `isConnected()` 对照；
2. 对照成立才动上游（非阻塞回调投递 → JNI 直调）；
3. 每次改上游都要核对 APK 内 `.so` 与 AAR 那份的 sha256 —— 这个会话里已经有过一次
   "改完 Rust 重发 AAR，跑完才发现跑的是 debug 引擎"的教训（差 538 倍）。
