# luciola 加密信道数据面：≥50MB 落盘验收（goal 第 3 步证据）

日期：2026-10-26。分支 `feat/luciola-discovery`（累计 45 个提交，**未 push**）。

## 这一轮要证明的事

文件字节走 luciola 加密信道（`fch1`）而不是 legacy TCP（17879/17881）时，
**50MiB 能一字不差地传到对端磁盘上**。真机那条路当前被一个独立缺陷挡着
（宿主控制面 `ControlPlane.resolveAgentTarget` 的 `runBlocking` 拖死 16 个 handler
线程，见 `docs/2026-10-26-luciola-migration-evidence.md`），所以这里用两条**真实的**
luciola 加密信道（真实 TCP 回环、真实 Ed25519 配对、真实 64KiB 分块窗口、真实落盘
与 sha256）做替代验收：与真机的唯一差别是"跨机器 / 真实网卡"，没有 mock。

可复跑：

```
./gradlew :core:jvmAcceptanceTest        # 只跑这条 ≥50MB 验收
./gradlew :core:jvmTest                  # 单元/协议（这条验收已从单元套件里排除）
```

最近一次结果（`core/build/test-results/jvmAcceptanceTest`）：

```
CHANNEL_FILE_E2E size=50MB elapsed=46077ms mbps=1.09 sha=da201d2c8a50762a   # 通过
```

## 途中找到的四个真缺陷（每个都有可复核的现场）

第一次跑 50MB 时是"卡死在 3,866,624 / 5,242,880"。把"猜"换成读数之后，
四个缺陷逐个现形，且**每一个都不是测试写错**：

### 1. 入站帧被静默丢弃（`MutableSharedFlow` 默认 DROP_OLDEST）

Rust 的连接线程回调 `onMessage` 是非挂起的，64KiB 文件块会瞬间打满缓冲。
默认的 `extraBufferCapacity = 256` + DROP_OLDEST 在收帧协程还没调度过来时**静默丢帧**。
丢掉的块永远补不上，因为发送端只重传"未确认窗口"，而它以为已经发出去了。

> 读数：`host out=192 rejected=4380 in=75 | phone out=177 rejected=0 in=177`
> —— 192 帧"发送成功"，对端只见到 177 帧。

修法：`onBufferOverflow = SUSPEND` + `emitOrBlock()`（`tryEmit` 失败就
`runBlocking { emit }` 提供背压），并把 `blockedEmits` / `emitFailures` 记成可读计数器。

### 2. 会话被"摘表重建"导致链路互相顶替

原来 `onState("error"/"closed")` 会把会话从 `outbound` 表里 `remove`，下一次 `send`
再造一条新会话 —— 但旧会话的 `connect(autoReconnect=true)` 还在拨，于是两条链路
同时拨同一个对端；对端的 accept 循环一次只收一条，新旧连接互相顶替，
**每次顶替都丢掉尚在出站队列里的帧**。

> 读数：`out[...] conns=15 retries=7`、`listen[] conns=15` —— 传输途中重连 15 次。

修法：会话对象不再摘除，断线交给库内的 1s→60s 退避重连；`send` 在未连上时返回
false，上层按退避重试（与网关队列满时同一套语义）。

### 3. 分块按**到达顺序**落盘（乱序即写错位置）

窗口重传会让后一块先到。`ChunkFileReceiver.append(bytes)` 永远写在文件末尾，
于是乱序块被写到错误偏移 —— 长度对得上、**sha256 对不上**。

> 读数：`PHONE-PUMP-FRAME-ERROR PullException: hash-mismatch`（byte 数一致，内容错位）。

修法：`RandomAccessFile` 新增 `writeAt(atOffset, ...)`（JVM/Android 共用一份，
iOS 走 `NSFileHandle.seekToFileOffset`），接收端按**帧里的 offset** 落盘。
回归用例：`ChunkFileReceiverTest.outOfOrderChunksLandAtTheirOffsets`（2→0→1 倒着喂，
断言逐字节正确）。

### 4. 收齐之后不再回 ACK ⇒ 末帧 ACK 一丢就永久卡住

原来收齐就 `inbound.remove(...)`。发送端只认"连续前缀到 totalSize"，
末帧 ACK 丢失后它重传整窗，而重传帧无人认领、不再回 ACK。

> 读数：`acked=50593792/52428800`（96.5%），`part bytes on disk=-1` ——
> 文件其实早已转正改名，两边却都以为没传完。

修法：收齐后**不摘表**，只把"转正"做成一次性（`completeOnce`）；重传帧命中
`!fresh` 分支继续回 ACK（终值 = totalSize）。校验失败才摘表并停止回 ACK，
让发送端如实失败（ACK 的语义是"字节收齐"，不是"校验通过"）。

### 附带：配对结果 replay

`pairingResults` 从 `replay = 0` 改成 `replay = 1`：应答可能在调用方订阅前就到了
（扫码路径"先发请求、再挂着听"，回环 RTT 毫秒级），丢掉它就是"扫了码什么都没发生"。
订阅方按 `(host, port)` 过滤自己关心的那一次。

## 四套 JVM 测试（提交前全绿）

```
core:jvmTest               classes=132 tests= 683 fail=0
core:jvmAcceptanceTest     classes=  1 tests=   1 fail=0   # ≥50MB 信道验收
app:jvmTest                classes= 89 tests= 681 fail=0
composeApp:jvmTest         classes= 89 tests= 469 fail=0
desktopApp:test            classes= 35 tests= 182 fail=0
```

验收用例单独一个任务（`:core:jvmAcceptanceTest`）而不是混进 `:core:jvmTest`：
它会跑 ~40s 并持续做文件 IO，混进单元套件会把同一 JVM 里后续的 luciola 握手用例
挤到超时（实测 683 里会有 3 条 10s 超时，单独跑该用例类却全绿）。
同时把那些"一次等待"改成"重发到真收到"（`pairEventually`），与仓库里既有的
`sendUntil` 同一条纪律。

## 还没做的（下一步）

1. **真机 ≥50MB**：sha256 一致 + 两端 `lsof` 看不到 17879/17881。需要先修掉宿主
   控制面那个线程饿死缺陷（独立问题，与本迁移无关）。
2. **投屏独立会话**：screen 帧走自己的 luciola 会话，真机连续 5 分钟帧率/延迟
   ≥ legacy 的 90%。
3. **收口**：luciola 模式下不再创建 17879/17881/17882 监听；把 `experimentalLuciola`
   转正（默认开、去掉实验文案、写清老用户共存与回退）。
