# luciola 模式不再创建 legacy 监听（17879 / 17881）

日期：2026-10-26。goal 第 (5) 步的前一半；投屏那条（17882）跟着投屏数据面一起做。

## 改了什么

| 端口 | 用途 | luciola 模式（`fch1`） |
|---|---|---|
| TCP `17879` | 明文 `TransferServer`：文件 pull、>1200B 长文本、screen 首帧分发 | **不绑** |
| TCP `17881` | 跨网文件 TLS（`tlsPort + 1`） | **不起** |
| TCP `17882` | 跨网投屏 TLS（`tlsPort + 2`） | 暂时保留（投屏数据面还没迁） |
| TCP `17880` | 长连接 K2（`tlsPort`，消息下行走它） | 保留 |

两处开关在同一条判据上（`NodeRuntime.fileChannelEnabled`，只有 luciola 发现模式
确认之后才为 true）：

- `TransferManager(legacyTcpListener = !fileChannelEnabled)` —— 明文监听与跨网 TLS
  监听都由它建，所以一个参数关掉两条入口；
- `NodeRuntime.tcpBound` 的语义收紧成「**真的绑上了端口**」：
  `manager.start() == true && manager.boundPort != null`。信道模式下 `start()` 仍返回
  true（数据传输走 fch1、管理器可用），但 `boundPort` 是 null —— 不许据此宣称
  "我绑着 TCP"。

## 途中的一个真缺陷（差点让这次改动变成空转）

`TransferManager.start()` 末尾原来还有一句无条件的 `return server.start(tcpPort)`
（幂等：已绑上就返回 true）。只拦住前面那段 `if` 是不够的 —— 这一句会把刚刚刻意
不建的明文监听**又建起来**，而且测试里表现为"信道模式下 17879 仍然绑着"。
现改成 `return if (legacyTcpListener) server.start(tcpPort) else true`。

## 证据（JVM，可复核）

`core/src/jvmTest/.../NodeRuntimeTcpBindTest`：

- `luciolaChannelModeCreatesNoLegacyListeners`：`fileChannelEnabled = true` 时
  `tcpBound == false`、`tcpBoundPort == null`、明文端口**连不上**，
  且跨网监听器**一次都没被 start**（记账实现，避免"端口恰好空着"的假绿）；
- `legacyModeStillOpensBothListeners`：同一份夹具在 legacy 模式下端口必须可连、
  监听器必须被调用（`tlsPort + 1`）—— 对照组，防止上一条永远为真。

```
core:jvmTest         tests= 685 fail=0
core:jvmAcceptance   tests=   1 fail=0
app:jvmTest          tests= 681 fail=0
composeApp:jvmTest   tests= 469 fail=0
desktopApp:test      tests= 182 fail=0
```

## 真机上还要看的那一眼（等宿主控制面缺陷修好）

```
# 两端都应当**没有** 17879 / 17881 的 LISTEN 与 ESTABLISHED
lsof -nP -iTCP:17879 -iTCP:17881 -sTCP:LISTEN
```

## 代价（写清楚，不装作没有）

luciola 模式下**没有 legacy 入站**：老协议的对端既发现不到我们（发现已是 luciola），
也连不上 17879。这正是"迁移"的含义；回退靠关掉开关并重启节点（发现与信道一起回到
legacy），不是靠在这两个端口上留一条后门。
