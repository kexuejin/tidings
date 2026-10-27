# 真机 50MB 重测：90s → 2.89s（31 倍），sha256 一致，三端口全退役

日期：2026-10-27。Pixel 6（Android 15）↔ macOS，两端 luciola 模式，两边都是 0.4.6 引擎。

## 结果

```
# 宿主
$ shasum -a 256 /tmp/luciola-filetest/big50.bin
a0227abc351658b69a9e170f1d3097a988ee7fc9d94dfffa7a0b0ae7d92a5a05

# 手机（应用私有目录）
$ adb shell run-as com.kapp.tidings.debug sha256sum files/relay/downloads/big50.bin/big50.bin
a0227abc351658b69a9e170f1d3097a988ee7fc9d94dfffa7a0b0ae7d92a5a05   # 52,428,800 字节
```

**耗时：上次 90s（0.55 MB/s）→ 这次 2.89s（≈17 MiB/s），快 31 倍。**

耗时不用秒表，直接读新加的 `[chunk]` 汇总（它把读、发、等 ACK 三段分开记）：

```
[chunk] total=52428800 windows=25 timeouts=0 chunksSent=800 readMs=39 sendMs=92 waitMs=2761
```

- `chunksSent=800`，而 50MB / 64KiB **正好 800 块** ⇒ **零重传放大**；
- 修复前同一个量具给出的是 `chunksSent=12768`（需要 399 块时）——**32 倍放大**；
- 39 + 92 + 2761 = **2892ms**。

## 为什么这次快

上一轮修的那个 ACK 缺陷在**真机上**的代价比回环大得多：

原来的 `SecureChunkSender` 一看到 `upTo > acked` 就 break，于是每轮只前进**一块**、
却把整窗 32 块重发一遍。回环 RTT ≈ 0.1ms，这个浪费被掩盖了；真机 WiFi RTT ≈ 100ms，
**每前进一块就要付一个 RTT**，于是 800 块要 800 个 RTT ≈ 90s —— 与实测完全吻合。

修好之后：25 个窗口 × 一个 RTT(≈110ms) ≈ 2.75s，`waitMs=2761` 就是这 25 个 RTT。

**下一步若要更快**：吞吐现在被「窗口大小 / RTT」卡住（2MiB / 110ms ≈ 18 MB/s）。
把 `windowChunks` 从 32 提到 128（8MiB in-flight）理论上能到 ~70 MB/s，
代价是丢块时的重传量变大。这是纯参数权衡，不再是缺陷。

## 端口判据（两端同时，这次含 17882）

```
宿主 TCP LISTEN：*:17880  *:17888  *:17878  127.0.0.1:8788
手机 TCP LISTEN：*:17880  *:17888
两端都没有 17879 / 17881 / 17882
```

**17882 这次有了真机证据**：它此前只有 JVM 层的用例
（`2026-10-27-luciola-retire-17882.md` 里标注了"真机 lsof 复核未做"）。现在补上了。

## 途中发现的一个诊断缺陷（诚实记录）

我上一轮加的 `reachability` 判据在真机上**误报**了：

```
{'nodeId': 'b2c3d4e5-…', 'nick': 'Pixel 6', 'online': True,
 'reachability': 'legacy-peer-invisible', 'legacyDiscovery': 'off'}
```

一个真正在线、且正在走加密信道的 luciola 对端被判成"老设备不可见"。原因：
这条 peer 记录是从磁盘恢复的陈旧快照（`profileRev=0`、`caps` 缺失），
而我的判据是 `caps 不含 lnk1+fch1 ⇒ 老端`。**陈旧档案 ≠ 老端**。

正确做法应当是"只在**本次运行收到过**的 profile 上判能力位"，或者干脆在 caps 缺失时
返回 `unknown` 而不是 `legacy-peer-invisible`。这条留作待办，
现在已知它会误报，不能再拿它当证据。

## 状态

- 引擎身份：APK 内 `lib/arm64-v8a/libluciola_uniffi.so` 与 0.4.6 AAR 中的那份
  sha256 相同（`201eab4d…`），两端都是含全部修复的引擎；
- 四套 JVM 测试 + 50MB 回环验收（86-89 MiB/s）全绿；
- 真机 50MB：2.89s、sha256 一致、三个 legacy 端口全无监听。
