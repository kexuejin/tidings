# 「宿主设备睡眠/离线中」横幅与在线判断不一致 —— 定因与修复

日期：2026-11-10 · 设备：Pixel 6（<device-serial>，`com.kapp.tenon.debug`）· 宿主：<host>-97（192.168.2.82）

上一轮遗留问题第 1 条：「链路抖动后手机顶部挂『宿主设备睡眠/离线中 · 命令已安全排队挂起』，
同时宿主 `/v1/peers` 报该手机 `online=true, linkConnected=true`；此时点『接收』不生效
（`accepted=false`），重启手机 App 后恢复。」

## 一、先复现，再定因

第二轮复现成功（此前换网/飞行模式/杀宿主三种抖动都没造出来）。同一分钟内两侧同时采样：

| 采样 | 手机屏 | 宿主 `/v1/peers` 对手机 |
| :--- | :--- | :--- |
| t+0s | `1/2 人在线`，无横幅 | online=true link=true ago=38.3s |
| t+30s | `1/2 人在线`，无横幅 | online=true link=true ago=70.6s |
| t+60s | `0/2 人在线`，**横幅出现** | online=**false** link=true ago=103.0s |
| t+90s | `1/2 人在线`，横幅消失 | online=true link=true ago=30.3s |
| t+150s | `0/2 人在线`，**横幅又出现** | online=true link=true ago=95.2s |

手机侧日志同一时刻给出翻面原因（`adb logcat`，`System.out`）：

```
[sweep] offline 46c23456 idle=96733ms link=false
[sweep] offline 46c23456 idle=91711ms link=false
```

## 二、根因：两侧传输模式不一致，presence 在两个方向上都是死的

`legacyDiscoveryEnabled = messengerTransportFactory == null`（`NodeRuntime.kt:473`）。
宿主日志 `[xport]` **0 行**（legacy），手机日志 `[xport]` 满屏（走安全信道）——
**两端跑的不是同一套传输**。于是：

| 方向 | 机制 | 真机读数 |
| :--- | :--- | :--- |
| 宿主 → 手机 | 宿主每 30s 发 presence，但手机的 legacy `Discovery` 没启动（装的是 `SweepOnlyDiscoveryEngine`），入站帧无人处理 | 手机侧 `[presence] rx` **0 行**；手机对宿主的 `lastSeen` 只能靠偶发帧 |
| 手机 → 宿主 | 心跳走 `transport.send(bytes, ip, udpPort)`，而安全信道的会话键是 `ip:tcpPort`（真机 `dialLastTarget=192.168.2.82:17879`） | 手机 `[heartbeat] entry targets=12 sent=0`；每一条都 `[xport] out send failed … connected=false` |

**两边链路其实是活的**：手机 `diag/link.json` 写着
`linkConnected=true, linkCarrier=plaintext, dialFailures=0, closeCount=0`；
宿主侧同源 `linkConnected=true`。所以「在线」的正确证据是**链路**，不是「最近有没有 UDP 帧」。

补充：`[sweep] offline 46c23456 idle=600106ms link=true` 这条读数来自上一版给豁免加的
**10 分钟上界** —— 它把「有会话、但十分钟没人说话」判成离线，于是空闲 App 照样挂横幅。

## 三、修复

| 位置 | 改动 |
| :--- | :--- |
| `PeerRegistry.sweep` | 有活跃长连接**不因空闲翻面**，且**不再设上界**：链路自己的 Ping/Pong（`Link.kt`，15s）与关闭回调负责判死，`linkCarriers()` 又是实时读会话表，会话一关承载项即消失 |
| `PeerRegistry.sweep` 诊断 | 翻面时一行 `[sweep] offline <id> idle=…ms link=<bool>`（`link` 就是当时的豁免读数，不需要再猜） |
| `SweepOnlyDiscoveryEngine` + `NodeRuntime.sendPresenceHeartbeat` | 心跳**跳过已有链路的节点**：既因为语义上多余，也因为发不对（会话键 `ip:tcpPort`，代码给的是 `ip:udpPort`，12 个目标全 `send failed`）；只对没有链路的老对端发 ENTRY |
| `Discovery.presenceRound` | 给**已判离线**的节点补发 ENTRY（破「判离线后不再发、对方永远收不到」的死锁） |
| `Discovery` 诊断 | `[presence] rejected`（源地址漂移/离线无资料）、`[presence] rx unknown`、`[presence] tx` 仅在没发全时出声 |

## 四、验证

**单测**：`core:jvmTest` 相关 9 条全绿
（`PeerSweepLinkExemptionTest` 1 条 + `LegacyDiscoveryVisibilityTest` 8 条）。
「10 分钟上界」不做单测：伪造十分钟需要可注入时钟，`PeerRegistry` 没有 ——
与其写一条用 1ms 冒充 10 分钟的假断言，交给下面这条真机判据。

**真机（换新包后同一条件重测）**：

| 观察 | 读数 |
| :--- | :--- |
| 手机 `[sweep] offline 46c23456` 次数 | **0**（换包前同一窗口 2 次） |
| 手机屏横幅 | **无** |
| 宿主对手机 | `online=true link=true` 连续成立（ago 涨到 92.3s 仍在线；换包前 103s 必翻） |

**新判据 `peer-idle-stays-online`**（a2r 第六条）：读手机 `diag/link.json` +
最近 180s 的 `[sweep] offline` 日志，用**设备自己的时钟**划时间窗。反向探针四档实测：

| 造的情形 | 判据 | 理由文案 |
| :--- | :--- | :--- |
| 链路在册、online=true、无翻面 | 绿 | 最近 180s 内无翻面 |
| 链路在册却 online=false | 红 | 链路在册却判离线 |
| 5 秒前发生翻面 | 红 | 翻面日志命中 |
| 400 秒前翻过、最近无翻面 | 绿 | 在时间窗之外 |

`--dry-run` 守卫 **27 项全绿**（新增 `review-gate-watches-link-liveness`，
第一次跑它就抓到我把判据名写在函数里、a2r 里没有引用 —— 门禁抓门禁，符合预期）。

## 五、仍然存在的（不掩饰）

1. **手机侧收不到宿主的 presence 是事实**（`[presence] rx` 0 行）。现在靠链路兜住，
   但链路一旦掉（后台、换网），手机对宿主的判断就没有第二条证据来源。
2. **两端传输模式没有任何协商**：手机开着安全信道、宿主是 legacy，两者仍能靠 plaintext
   链路互通消息，于是「看起来能用」，只有在线状态在骗人。要么让 `caps` 暴露并且在 UI 上
   显示不匹配，要么在握手时拒绝。
3. `hosts` 侧 `[presence] rejected from=3b53ff10 src=10.233.138.118 known=172.26.15.180`
   每分钟复发：对端在 TUN/多网卡下换源地址，`touch` 的漂移规则（在线不接受换地址）
   让它的心跳永远不刷新 `lastSeen`。这是独立的一条缺陷，未修。
4. `[heartbeat] sent=0` 的日志在换新包后应该消失（跳过有链路的节点），**尚未在真机上复看**。

## 六、下一步

1. 决定 (2) 的产品口径：传输模式不匹配时，是提示、还是拒绝。这决定要不要动协议。
2. 修 (3)：漂移规则对「同一 nodeId、来自新源地址、带全量资料」的 ENTRY 应当接受并改址，
   而不是永久拒收 —— 但要让「在线节点不接受漂移」这条安全纪律不被顺手放宽。
3. 手机侧 (1)：`SweepOnlyDiscoveryEngine` 是否该复用同一条 UDP socket 收 presence，
   还是干脆给链路加一条「应用层存活帧」；这是设计题，不建议直接加代码。
