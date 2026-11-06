# 跨网长连接空闲抖动 —— 真机验证记录

> 日期：2026-10-19
> 设备：REDMI K80（蜂窝 2001:db8:…，移动网）↔ demo-mac-97（公网 2001:db8:…）
> 构建：desktopApp 14:57:30（含 soTimeout 修复）

## 缺陷（修前）

真机现象：跨网 TLS 长连接**每 8.1 秒断一次**，`linkConnected` 周期性 True/False 跳；
而只要一直有消息来往就稳。

实测周期（修前，逐 0.5 秒采样）：

```
state changes: [(0.6,'up'),(6.0,'down'),(8.7,'up'),(14.2,'down'),(16.9,'up'),...]
up intervals:  [8.1, 8.2, 8.2, 7.6, 10.3, 7.6, 8.2, 8.2, 8.1, 7.6]
down durations:[2.7, 2.7, 3.3, 2.7, 1.6, 2.7, 2.7, 2.7, 3.2, 2.7]
⇒ 连上约 5.4 秒后必定断开（5.4 + 2.7 = 8.1）
```

判据：桌面侧 `closeCount=0` + `closeReason=peer-closed` ⇒ **不是 Mac 断的**，是客户端侧关的。

## 根因

`LinkTls.jvmShared.kt` 的 `connect()` 把 `soTimeout` 设成握手超时（5 秒）后**从未复位**：

```kotlin
socket.soTimeout = timeoutMs.toInt()   // 5s，本意只管握手
socket.startHandshake()
// ...握手成功后这一行从来没有被复位
```

空闲 5 秒 ⇒ `inputStream.read` 抛 `SocketTimeoutException` ⇒
`receiveFrame` 用 `runCatching{}.getOrNull()` 把**任何**异常折成 `null` ⇒
pump 把 `null` 读成"对端关了这条连接" ⇒ 关连接 + 退避 + 重拨。

"一发消息就稳"正是因为那时有字节、读不会超时。

同文件里裸流那条路径（`connectRaw`、`startRawServer`）**早就**把 `soTimeout` 归零了，
只有分帧这条漏了。

## 修复

1. **主修复**：握手成功后 `socket.soTimeout = 0`，空闲判定交给应用层原有的 45 秒 `deadAfterMs`。
2. **防御纵深**：`readFully` 单独接住 `SocketTimeoutException` 并继续读，不再把它折成"断开"。

注入验证：只撤掉 ① → 新增的 `anIdleConnectionSurvivesLongerThanTheHandshakeTimeout` **变红**；
恢复 → 变绿。两层都撤 → 仍绿（说明 ② 是替代层而非叠加层，注释已按此事实写明）。

## 修后实测（真机）

```
空闲 90 秒，每 0.5 秒采样：changes = NONE，final = UP
再连续采样 60 秒，每 10 秒：est=1 / link=True / closeReason=None / closeCount=0（6/6 全稳定）
⇒ 累计 150 秒空闲无一次断连（修前 90 秒会断 ~11 次）
```

投递仍走长连接：

```
verify-stable-1  sent | outcome=sent-via-link elapsed=117ms
verify-stable-2  sent | outcome=sent-via-link elapsed=19ms
verify-stable-3  sent | outcome=sent-via-link elapsed=25ms
```

连接形态（修后）：

```
TCP [2001:db8:2020:cb44:4805:e245:f001:2]:17880 -> [2001:db8:2031:bbe6:7493:3ff:feb0:de9e]:46912 (ESTABLISHED)
linkConnected=true  linkCarrier=tls  pinSource=pairing  closeCount=0
```

## 回归门

`core/src/jvmTest/.../TlsLinkTransportTest.kt`
:: `anIdleConnectionSurvivesLongerThanTheHandshakeTimeout`
—— 客户端侧挂一个阻塞读、空转 2.5 秒（握手超时给 1 秒），再下行一帧，要求那**同一次**读取拿到它。

**必须读客户端那一侧**：`soTimeout` 是 per-socket 的，而缺陷在 `connect()` 里；
服务端那条路（`startServer`）从不设置它 —— 只测服务端读永远绿，这正是这个 bug
能在真机上活下来却没有任何单测拦它的原因。
