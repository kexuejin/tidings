# 投屏独立加密会话（goal 第 4 步的数据面）

日期：2026-10-26。`feat/luciola-discovery`。

## 做出来的东西

| 层 | 类 | 作用 |
|---|---|---|
| 线格式 | `ScreenChannelFrame`（commonMain） | 魔数 `TDSC` + 长度，把 screen-* 协议的字节封成一帧，让传输层一眼分开 Envelope / 文件块 / 画面 |
| 会话 | `SecureScreenChannel`（jvmSharedMain） | 一条**专用**的 luciola `SecureSession`；对外只暴露 `ScreenWire`（两条字节流），screen-* 协议不知道底下是加密信道 |
| 协商 | `ScreenChannelNegotiation` + `MsgTypes.CAP_SCREEN_CHANNEL` (`scr1`) | 双方都声明 **且** 对端档案里有 `screenPort` 才走新数据面 |
| 档案 | `Profile.screenPort`（可选键） | 画面信道的监听端口；null = 老端/未开，继续走 17879/17882 |
| 钩子 | `SecureChannelTransport.isBinaryFrame` | 让"哪些帧是二进制数据面"可注入；默认仍是文件块，行为逐字不变 |

**为什么另开一条会话**：信道层的 `listen` 一次只接受一条连接，消息/文件那条已经把
luciolaPort 占住了；而投屏是连续、低延迟、允许丢一步但不能堵的流量。共用一条队列的
结果是「投屏一开、消息延迟跳起来、文件传输停住」。

## 证据（JVM，可复核）

`core/src/jvmTest/.../ScreenChannelComparisonTest`：同一段拉取式流量（viewer 发 4 字节
请求、sharer 立刻回一帧，与 `screen-next`/`screen-frame` 同形）分别在
**legacy 明文 socket** 与 **luciola 画面会话**上跑 40 帧 × 100KiB × 10fps：

```
SCREEN_ARM engine=/workspace/RustBuild/cargo-target/release
SCREEN_ARM legacy-plaintext      offered=40 delivered=40 onTime=1.000 fps=9.99 p95=6.0ms mean=1.1ms
SCREEN_ARM luciola-screen-channel offered=40 delivered=40 onTime=1.000 fps=9.98 p95=6.0ms mean=3.1ms
```

判据（三条都断言在测试里）：
1. 按时到达率 ≥ legacy × 0.9（实测 1.000 vs 1.000）；
2. 实际帧率 ≥ legacy × 0.9（9.98 vs 9.99）；
3. p95 延迟 ≤ 帧间隔的一半（6.0ms vs 上限 50ms）。

**为什么不拿回环明文的绝对延迟当分母**：那条路是内存拷贝（p95 1-2ms，本身抖动就够
把 1.111× 的预算吃光），拿它做基准只会得到「加密信道永远差几毫秒」的假结论；
投屏的要求是连续 10fps 不掉帧，所以延迟判据落在「一帧在自己帧间隔里到」。

`SecureScreenChannelTest` 另有三条：37B / 300KiB 双向字节流完整、Sharer→Viewer 回程、
关掉之后不再发。全部走真实引擎 + 真实 TCP 回环，不 mock、不用固定端口。

## 途中的一个**量具**问题（差点得出错误结论）

第一次对比是用 `cargo-target/debug` 的引擎跑的：画面会话单帧 26ms、legacy 1ms，
看上去「加密信道就是慢」。换成 **release** 引擎后同一段流量是 3.1ms —— 差 8 倍。
debug 的 AES-GCM/握手是未优化的，而真机装的是 release。

已把 `core/build.gradle.kts` 的 `jna.library.path` 默认改成**优先 release**（存在就用，
否则退回 debug），并在对比用例里把实际引擎目录打进报告，避免以后再拿 debug 的数做验收。

## 还没接上的（下一轮）

1. 把 `SecureScreenChannel` 接进 `NodeRuntime`：Sharers 起画面监听、Viewer 的
   `screenWireAttempts` 把它排在 legacy 之前；`RelaySettings.toProfile` 在 luciola 模式
   写 `screenPort` 并声明 `scr1`（`caps(screenOverChannel)` 已就位，默认 false ⇒
   今天的行为一点没变）。
2. 收掉 17882（跨网投屏 TLS）—— 与 17879/17881 同一批，等画面链路接通后再关。
3. 真机连续 5 分钟验收（被宿主控制面的独立缺陷挡着）。

## 另一个待办（本轮读数顺带发现的）

50MiB **文件**验收仍是 ~0.9MB/s（55s），与引擎 debug/release 无关：读数显示传输途中
会话重连 `conns=15`、窗口整窗重传，说明瓶颈在"窗口 ACK + 重连"的动态，不在加解密。
投屏这条（无 ACK、纯流）在 release 下是 3ms/帧，可作对照。
