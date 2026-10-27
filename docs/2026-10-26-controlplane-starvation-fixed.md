# 控制面线程饿死：修掉真机验收的拦路虎 + 端口退役的真机证据

日期：2026-10-26。goal 第 (3)(4) 步的**前置障碍**。

## 一、那个"控制面整个挂了"的缺陷（已修）

真机上（Pixel 6 ↔ macOS）此前 `/healthz`、`/v1/peers`、`/v1/send-file` 全部超时，
`jcmd Thread.print` 里 **16 条 `pool-1-thread` 全卡在 `ControlPlane.resolveAgentTarget`**
的 `runBlocking` 上。根因不是"池太小"，而是：

对端不可达时 `NodeRuntime.resolvePeerByHost` 要**等满 3 秒**；而它被 `runBlocking`
包在一个 HTTP 处理器里。手机离线时每隔几秒重试一次 agent 帧 ⇒ 16 条处理器线程被
这 3 秒一个个吃掉 ⇒ 连读路由都拿不到线程。**看起来是"控制面挂了"，其实是"被自己的
阻塞调用按住了"。**

两处修法（缺一不可）：

1. **处理器改用虚拟线程**（JDK 21+，反射取 `Executors.newVirtualThreadPerTaskExecutor`，
   取不到就退回固定池）：阻塞只花一个虚拟线程，读路由永远还有空。
2. **解析失败记 5 秒**（`resolveTargetNodeId`）：同一个死对端的连续重试只付一次 3 秒。
   只记失败不记成功 —— 成功之后对端已经进 registry，`getPeer` 直接命中。

## 二、证据

回归测试 `ControlPlaneTest.healthzStaysFastEvenWhenUnreachableTargetsFillTheHandlers`：

- 6 条并发 `POST /v1/agent/event` 打向 **6 个不同**的 TEST-NET 黑洞地址
  （不同地址才绕得开失败记忆，逼它们真去解析）；
- 解析进行中连测 5 次 `/healthz`：最慢一次必须 < 1s；
- **反空转**：随后等这 6 条全部拿到 `404 peer-unreachable` —— 只有它们真的走完
  3 秒握手解析，"读还快"才说明池子没被饿死。实测本用例耗时 3.2s（= 一次解析），
  六条全部拿到 404。

真机（`./gradlew :desktopApp:run`，`TIDINGS_LUCIOLA=1`，release 引擎）：

```
$ curl -s -m5 http://127.0.0.1:8788/healthz      # 修之前：超时
{"ok":true,"nodeId":"d4e5f6a7-…","udpPort":17878,"httpPort":8788,"peers":4,"online":0,…}
real 0m0.013s
$ curl -s -m5 http://127.0.0.1:8788/v1/peers     # 0.008s（之前同样超时）
```

## 三、顺带拿到的真机证据：legacy 端口真的没在听

同一个宿主的 TCP 监听（`lsof -a -p <pid> -iTCP -sTCP:LISTEN`）：

```
TCP *:17878   ← luciola 加密信道（发现 UDP 17878 是同号的另一个协议）
TCP *:17880   ← 长连接 K2（tlsPort，消息下行走它；保留）
TCP *:17888   ← 画面信道（udpPort + 10）
TCP *:17882   ← 跨网投屏 TLS（待退役：等画面链路真机验过）
TCP 127.0.0.1:8788 ← 控制面
```

**17879 与 17881 都不在**（legacy 明文文件监听、跨网文件 TLS）—— 这是 goal 第 (5) 步
文件那一半在真机上的第一次证据，先前只有 JVM 断言。

## 四、还差什么（下一轮）

1. **手机侧要能进 luciola 模式**：现装的 `com.kapp.relay` 是 **release** 包
   （`pm dump` 里 not debuggable、设置页也没有实验开关），只能通过装 debug 变体
   （`applicationIdSuffix .debug`）并用 `run-as` 改 `files/relay/settings.json` 打开开关。
   之后还要与这台 dev 宿主**重新配对**（宿主是新的 nodeId/身份）。
2. 完成 ≥50MB 真机验收（sha256 + 两端 lsof）与 5 分钟投屏验收；
3. 然后退役 17882、把 `experimentalLuciola` 转正。

## 五、真机排障踩到的两件事（写下来省下一次）

- 手机上 **`com.kapp.tidings`（旧包，uid 10347）常驻占用 UDP 17878**，导致
  `com.kapp.relay` 启动时报「UDP 端口 17878 没能监听（Address already in use）」。
  用 `pm disable-user --user 0 com.kapp.tidings` 腾出端口（可逆）。
- `adb shell input tap` 的坐标是**设备像素**（1080×2400），底部导航在 y≈2200 而不是
  预览图里的 y≈1700；盲点会落在消息列表上。**先 `uiautomator dump` 读 bounds 再点**。
