# iOS 端到端消息打通（goal 第 (2) 步达成）

日期：2026-10-27。iPhone 17 Pro 模拟器 ↔ 桌面 macOS。

## 证据

桌面 `POST /v1/send` 的响应：

```json
{"status":"sent","to":"ee8ea7fe-…",
 "lastSend":"outcome=sent-via-udp elapsed=3ms endpoints=[192.168.2.82:17978 acked 2ms]
             tcp=not-tried redial=no link=false peerOnline=true",
 "note":"对方已 ACK"}
```

iOS 模拟器 stdout：

```
[msg] inbound from=d4e5f6a7-1311-4549-a84b-991eb8fb715d conv=d4e5f6a7-… len=22
```

也就是：**消息经 luciola 加密信道到达 iOS，并被落进会话存储**（不是只"发出去了"）。

## 前置：两个环境障碍（都已解决）

**① 同机串话** —— 模拟器与桌面共享网络栈、都在 17878，对端拨 `<ip>:port` 打到的是自己。
解决：`LuciolaTransportFactory` 增加 `channelListenPort`（发现端口不变），
模拟器用 `defaults write com.kapp.tidings luciola.channelPort -int 17978` 注入。
证据：宿主日志从 `pin lookup 192.168.2.82:17878` 变成 `…:17978`，`lsof` 里
`Tidings *:17978` 就是模拟器自己的监听。

**② 配对** —— 模拟器没有相机、也没有触控注入，App 也没注册 URL scheme，
所以"扫码/从相册导入"这条路无法自动化。

做法（不新增产品行为）：
1. 让 iOS 侧临时打印自己的二维码载荷（`pairingInfo()` 的 pub + secret，**验完已删除**）；
2. 把这两项写进桌面自己的状态文件 `<dataDir>/luciola/state.json`
   （`endpoints["<ip>:<port>"]`、`pairingSecrets[...]`），等于"桌面扫过这张码"；
3. `POST /v1/dev/pair {host, port}`（仅在 `TIDINGS_DEV_ROUTES=1` 时注册）——
   它只负责发配对自证帧，**不写信任**；没有码里那份密钥，对端的 PairingAdmission 会拒，
   所以它不能被用来绕过配对。

## 踩到的坑：base64url 必须有填充

写入 state.json 时用的是无填充的 base64url（43/22 字符），结果**整张信任表变空**、
`pinned=false`、配对返 false。原因：

```kotlin
endpoints = ...apply { state.endpoints.forEach { (k, b64) -> runCatching { put(k, Base64.UrlSafe.decode(b64)) } } }
```

`Base64.UrlSafe` 要求正确填充（43→44 加一个 `=`），解码抛错被 `runCatching` 吞掉，
于是"有配置但一条都没加载"。

**这是现有代码的一处静默失败**：状态文件损坏时不会报错，只会让所有 pin 消失
（症状是"配过对的设备又要重配"）。建议后续在 catch 里打一行日志，
与项目"不许把异常吞成正常"的纪律一致。当前未改，先记录。

## 测试污染（复现过两次）

跑 `:agentNode:test` 前**必须**停掉本机跑着的桌面宿主/模拟器 App —— 它们占用固定端口
（8788/17878），会让 `ControlPlaneTest.sendByIpHandshakesThenDeliversAndGetsAcked`
报 `expected:<1> but was:<2>`。停掉后 141 个用例全过。这与仓库既有的
"测试不许依赖环境"是同一条纪律。
