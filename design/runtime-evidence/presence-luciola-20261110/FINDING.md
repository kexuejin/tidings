# 双端 luciola 空闲在线复验 · 结论与未决

## 做了什么

桌面宿主不再用 14:42 安装的旧包（那包跑 legacy，验不了今晚的改动），改为从源码
`./gradlew :desktopApp:run --args=--headless` 启动；手机装 21:32 重编的 debug 包并唤醒到
前台。两端日志都出现 `[xport]`，确认同为安全信道。控制面在回环 **8788**（不是 17878 ——
17878 是 luciola 传输端口，对它发 HTTP 会超时，本轮踩过一次）。

## 读数

| 面 | 读数 |
|---|---|
| 宿主 healthz | `ok=true / peers=30 / online=1 / pending=501 / uptime=130s` |
| 手机 `diag/link.json` | `linkTotal=0`、在册活跃链路 0、对端 12；宿主那条 `online=true`、`dialAttempts=21 / dialFailures=21 / connect-timeout` |
| 手机 logcat 近 3000 行 | `[sweep] offline` **0 条** |
| 手机界面 dump | 无「宿主设备睡眠」横幅 |

空闲观测 130 秒，超过原来 90 秒的翻面阈值，**旧现象没有复现**：在线没翻、横幅没挂、
没有翻面日志。两端传输模式对齐后，presence 帧能双向走，这是与 18:00 那轮（宿主 legacy /
手机 luciola，两个方向都死）最直接的差别。

## 但这一格不能判绿

`peer_idle_stays_online()` 原来的形状是「找出 `linkConnected && !online` 的对端 + 最近
180s 的翻面日志，两者皆空即通过」。这次 `linkTotal=0`、没有任何 `linkConnected` 对端 ——
两个集合天然都是空的，判据会**因为什么都没看见而给绿**。现场调用修复前的等价逻辑确实
会返回通过，属于典型空集假绿。

修法：`peers` 里没有 `linkConnected=true` 时直接返回 `None`（不适用，只记证据），
与 `prototype_bytes_landed` 的「不适用不给绿」同口径；`dry-run` 增加第 28 项守卫
`review-gate-idle-requires-live-link`。反向探针：把早退条件改成恒假，仅该守卫判红；
恢复后 28 项全绿。修复后在真机重跑判据，返回
`手机侧无在册活跃链路（linkTotal=0，peers=12）…`。

## 新暴露（未定因，另立案）

双端同为 luciola 时链路始终建不起来：手机拨号 21 次全 `connect-timeout`，目标在多个候选
地址（含 TUN / 虚拟网卡段与公网 IPv6）之间轮换、端口是链路端口；宿主心跳
`targets=32 sent=2 linked=0`、大量 `no session / unpaired`，入站握手出现 `early eof`。
这与早先记录的「源地址漂移让 touch 永久拒收」是同族现象，但**不能据此下结论**说是
地址漂移还是 luciola-only 后链路建立路径本身缺了监听。

由此产生一个口径问题需要正视：产品侧把「空闲不翻面」的豁免挂在**活跃链路**上，而
luciola-only 下链路长期为 0，豁免可能永不生效；这次在线稳定靠的是 presence 帧通了，
不是链路豁免。链路建立路径要单独取证（宿主实际监听面 / 手机拨号端口来源 / pin 状态），
别把「在线没翻」当成链路已恢复。

## 边界

本轮只做被动观测，没有派单：`--tier real` 的 a2r 人审、幕 4 追问 / i1 退单 / i2 硬打断
仍需手机 @ 派单与人在手机上点。证据里的地址与节点标识已脱敏。
