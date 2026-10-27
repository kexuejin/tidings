# 为什么 iOS 模拟器与桌面 App 能互发现、却不能互拨（同机测试的硬限制）

日期：2026-10-27。goal 第 (2) 步验证时撞到的环境限制，记下来省下次重走。

## 现象（两端都有证据）

同一个 Mac 上跑桌面 App 与 iOS 模拟器，两者都在 17878：

- **互发现成功**：iOS 侧打印
  `[luciola] discovered d4e5f6a7-… at 192.168.2.82:17878`（桌面），
  桌面 `/v1/peers` 里也有 `iPhone 17 Pro 192.168.2.82 online=true`。
- **拨号失败**：桌面 `/v1/send` 返回
  `outcome=queued endpoints=[-] tcp=fail(connect-failed)`，
  日志 `pin lookup 192.168.2.82:17878 pinned=false`。

## 两个原因叠在一起

**① 未配对（正常的安全行为）。** `pinned=false` ⇒ deny-by-default 拒绝拨号。
这不是缺陷；要通必须先配对。

**② 同机时端口就是冲突的（这个才是硬限制）。**
iOS 模拟器与桌面进程共享同一个网络栈，两者看到的"自己的 IP"都是 `192.168.2.82`。
而桌面拨对端用的是对端**广播出来的地址与端口**：

```
SecureChannelTransport(listenPort = udpPort)      // 信道监听端口 = 发现端口
selfProfile.luciolaPort = transport.boundPort     // 广播出去的就是它
```

于是桌面拨 `192.168.2.82:17878` 实际连到**它自己**的监听。让一边换端口也不行：
`udpPort` 同时是发现端口，两边一旦不同就再也发现不到对方（实测：桌面改 17898 后，
iOS 只发现手机、发现不到桌面）。

## 结论

**同机（模拟器 ↔ 桌面）可以验证「装配 + 发现」，无法验证「端到端消息」。**
这不是代码缺陷，是"两个进程不能同时拥有同一 IP:port"。

## 三条可行路线

| 路线 | 代价 | 说明 |
|---|---|---|
| **A. 真机 iPhone ↔ 桌面 Mac** | 中（要真机 + 签名） | 最接近真实，一台设备一台机，IP 与端口天然不冲突。**推荐** |
| B. 给工厂加独立的 `channelListenPort` | 小（十几行） | 让信道监听与发现端口解耦；顺带让"一台机器跑两个实例"成立 |
| C. 两台 Mac | 大 | 只是把 A 换成桌面 ↔ 桌面 |

## 判据（下一轮做 A 或 B 时怎么算通过）

```
iOS 侧出现: [msg] inbound from=<桌面 nodeId> conv=<...> len=69
桌面侧:     /v1/send 返回 status=sent（不是 queued）
```

只看到 `discovered` 不算通过 —— 那是本轮已经拿到的证据，别拿它冒充消息通了。
