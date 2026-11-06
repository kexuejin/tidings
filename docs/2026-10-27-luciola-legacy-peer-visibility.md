# 老用户共存：把「老设备为什么不可见」做成可读的判据

日期：2026-10-27。goal 第 (5) 步里"老用户共存/回退说明"那一截的**技术前提**。

## 问题

开启 luciola 模式后，legacy 发现整体不启动。后果是**没升级的老设备既发现不到我们、
我们也发现不到它** —— 而界面上它只是静静地显示成"离线"，与"设备关机"完全一样。

**转正（默认开）之前，这个后果必须可见**，否则用户只会看到"另一台设备莫名其妙用不了了"。

## 这一轮改了什么

### 1. 一个只读判据：`NodeRuntime.legacyDiscoveryActive`

```kotlin
val legacyDiscoveryActive: Boolean get() = legacyDiscoveryEnabled
```

控制面的 peer 诊断据此可以区分"设备关机"与"它还在老传输上"。

### 2. 控制面 `/v1/peers` 增加两格

```json
{ "reachability": "legacy-peer-invisible" | "ok", "legacyDiscovery": "off" }
```

`legacy-peer-invisible` 的判据是**能力位**，不是版本号：

```kotlin
profile.caps.contains(MsgTypes.CAP_LINK) && profile.caps.contains(MsgTypes.CAP_FILE_CHANNEL)
```

为什么不用版本号：能力位是被对端**主动声明**的，而版本串各家写法不一
（`relay-0.1.0` / `0.61.1` …）。没有这两个位 = 老端。

## 测试钉住了什么（3 条，都跑通）

| 用例 | 守住的事实 |
|---|---|
| `legacyModeReportsDiscoveryActive` | 没有自定义传输工厂 ⇒ `legacyDiscoveryActive = true`（诊断不会把能发现到的老设备误报成不可见） |
| `customTransportFactoryTurnsLegacyDiscoveryOff` | 给了传输工厂（= 走 luciola TCP 信道）⇒ `false`。**这是「老设备会失联」的根因** |
| `dataPlaneOnlyKeepsLegacyDiscoveryActive` | 只开 `fileChannelEnabled` 而不换传输 ⇒ **仍然 true** |

## 修掉了一个我自己写错的判断

第一版测试的假设是"`fileChannelEnabled = true` ⇒ legacy 发现关闭"，**跑出来是红的**。
查证后发现真正的判据是 `messengerTransportFactory == null`：

- `fileChannelEnabled` 管的是**数据面**（文件/画面走哪条路）；
- `messengerTransportFactory` 管的是**发现走哪条路**。

两者在真机上同时成立，但**语义是两件事**。这条纠正很重要，因为**"只迁数据面、保留
legacy 发现"正是共存方案之一** —— 若把两件事混着看，那个方案在代码层面会被误判为
不可行，而实际上第三条用例证明它**技术上成立**（legacy 发现照跑、数据面走新路）。

## 这也顺带澄清了共存方案的取舍

之前我判断"双发现并行与『不再创建 legacy 监听』矛盾"。**更准确的说法是**：

- 冲突的是 **UDP 监听**（legacy 发现需要 `UdpTransport` 的 UDP 口），
  而 goal 第 (5) 步点名的是 **17879/17881/17882 三个 TCP 口**；
- 也就是说：**保留 legacy 发现（UDP）并不违反"退役 17879/17881/17882"** ——
  老设备仍能被"看到"，只是数据面走不了新路（会退回 17879，而那正是要被退役的）。

所以真正的产品决策是：**老设备"看得见但传不了"（保留 UDP 发现）** vs
**"看不见也传不了"（当前行为）**。这条我写成文档，等人定夺。

## 状态

- 四套 + agentNode 全绿：core **714**（+3 新用例）/ app 681 / composeApp 469 /
  desktopApp 182 / agentNode 141。
- 默认值仍未改：延迟判据未达标（40–43ms vs 19–21ms）。
