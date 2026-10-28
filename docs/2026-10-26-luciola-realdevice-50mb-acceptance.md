# 真机验收：≥50MB 走 luciola 加密信道，sha256 一致，17879/17881 不在

日期：2026-10-26。goal 第 (3) 步的真机落点。

设备：Pixel 6（Android 15，arm64）`<device-serial>` ↔ macOS（`<host>.local`，192.0.2.82）。
两端都在 luciola 模式（`experimentalLuciola=true`），宿主用 release 引擎
（`TIDINGS_LUCIOLA_NATIVE=<cargo-target>/release`）。

## 结果

```
# 发送端（macOS）
$ shasum -a 256 /tmp/luciola-filetest/big50.bin
a0227abc351658b69a9e170f1d3097a988ee7fc9d94dfffa7a0b0ae7d92a5a05

# 接收端（Pixel 6，应用私有目录）
$ adb shell run-as com.kapp.tidings.debug sha256sum \
    files/relay/downloads/big50.bin/big50.bin
a0227abc351658b69a9e170f1d3097a988ee7fc9d94dfffa7a0b0ae7d92a5a05   # 52,428,800 字节
```

宿主日志（同一次传输）：

```
[luciola] accept-in transfer=52552698 known=true push=true files=1
[luciola] channel push start transfer=52552698 peer=b2c3d4e5 files=1
[luciola] channel push file=74bf0953 sent=52428800/52428800
```

耗时约 90s（≈0.55 MB/s）。**这不是引擎上限**（release 引擎的画面通路单帧 3ms 量级），
是窗口 ACK 与重连的动力学问题，记在待办里。

## 端口判据（两端同时）

```
# 宿主 TCP 监听：17880(长连接) / 17882(跨网投屏 TLS，待退役) / 17888(画面信道) / 8788(控制面)
$ lsof -nP -a -p 17149 -iTCP -sTCP:LISTEN
*:17880  *:17882  *:17888  127.0.0.1:8788
# 信道连接（双向各一条）
192.0.2.82:57658->192.0.2.22:17878
192.0.2.82:17878->192.0.2.22:48288

# 手机 TCP 监听
$ adb shell ss -tlpn
*:17880  *:17882  0.0.0.0:17888 …

# 两端都没有 17879 / 17881 的 LISTEN
$ adb shell 'ss -tlpn | grep -E ":(17879|17881)"'   # 空
```

**17879（legacy 明文文件）与 17881（跨网文件 TLS）在真机两端都不再监听**，
而 50MB 仍然完整到达 —— 这正是 goal 第 (3) 步的两个判据同时成立。

## 途中修掉的一个真缺陷：只扫过码的对端，二进制帧归属不到

50MB 第一次跑死活不动：宿主日志显示 `channel push start` 之后没有任何进展，
接收端一个字节都不落盘，两边都不报错。

根因在**入站二进制帧的归属**（`LuciolaTransportFactory.endpointOfFingerprint`）：
它原来只查 Rust 的 TrustStore 指纹账本 —— 那条只有在**对端反过来配对我们**时才写入。
而手机只扫了桌面二维码（`pairPeer` 只写本机的「地址 ↔ 公钥」表 `endpoints`），
于是会话被信任（信任判据查 endpoints）、帧却归属不到对端（只查 TrustStore）
⇒ `InboundListener` 按"查不到端点就丢弃"的纪律把每一个文件块都扔了。

修法：归属改为**两条来源**，TrustStore 查不到时用 `endpoints`（地址 ↔ 公钥）反查 ——
指纹已经唯一认定了设备，按地址兜底不会串台；精确端口优先，端口对不上再按地址。
回归用例：`EndpointOfFingerprintTest`（只配对、不写 TrustStore 也能归属；
没配对过的陌生人一个端点都不给）。

顺带补了三条可检索的归属日志（每条都是踩过一次的"静默"）：
`accept <id> channel=<bool>`、`channel inbound declined: selfFch1=… peerCaps=…`、
`channel push start/file=… sent=…/…`。

## 真机环境上必须记住的两件事

1. 手机里旧包 `com.kapp.tidings`（uid 10347）会常驻占用 **UDP 17878**，
   导致新装的应用报「UDP 端口 17878 没能监听」。用 `pm disable-user --user 0`
   腾出来（可逆）。
2. `adb shell input tap` 是**设备像素**坐标：1080×2400 上底部导航在 y≈2200，
   文件卡片的「接收」按钮在 y≈1900 —— 盲点会落在消息列表上。
   **先 `uiautomator dump` 读 bounds 再点**，并且在输入法弹出后要重新 dump（布局会滚）。
