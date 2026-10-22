# overlayd 验证手册（怎么证明插件形式成立）

> 配套：[overlayd-contract.md](overlayd-contract.md)（契约）、[overlay-transport-design.md](overlay-transport-design.md) §3.5（形态）。
> 本文件只回答一件事：**怎么验、验什么、失败长什么样**。

## 0. 三个阶段（别混着验）

| 阶段 | 验的是什么 | 需要第二台设备吗 | 现状 |
|---|---|---|---|
| **V1 契约验收** | 插件机制本身：加载、鉴权、状态、生命周期、隔离 | ❌ 不需要 | ✅ **17/17 通过** |
| **V2 数据面** | 字节真的穿过 overlay 到对端，且没丢没错 | ✅ 需要 | ⬜ 待跑 |
| **V3 真实场景** | 关掉 IPv6 入站 / 手机蜂窝下仍可达 | ✅ 需要 | ⬜ 待跑 |

> **V1 通过 ≠ 插件能用；V2 通过才叫能用。** 两者验的是不同的东西，别拿一个的结果说另一个。

## V1 契约验收（不需要网络、不需要账号、可反复跑）

### V1.0 构建

```bash
# 工具链：tsnet v1.104.2 要求 Go >= 1.27.3（旧版会卡在 toolchain 上）
go version

# 国内网络必须绕开代理，否则 Go 的证书校验会失败（x509: OSStatus -26276）
unset HTTP_PROXY HTTPS_PROXY http_proxy https_proxy
export GOPROXY=https://goproxy.cn GOTOOLCHAIN=auto

cd /tmp/overlayd && CGO_ENABLED=0 go build -ldflags='-s -w' -o overlayd .
cd /tmp/overlay-probe2 && go build -o probe .
```

**期望**：两个二进制都产出；`overlayd` 约 20MB。

### V1.1 跑验收

```bash
cd /tmp/overlay-probe2 && ./probe
```

**期望**：末尾是 `结果：17 passed, 0 failed`。

### V1.2 每条断言在验什么（失败时怎么读）

| 断言 | 失败意味着 | 典型原因 |
|---|---|---|
| 错 token ⇒ `E_BAD_TOKEN` | 鉴权没生效 | 忘了校验 token，或把 token 当可选 |
| proto 不匹配 ⇒ `E_PROTO` | 版本协商是假的 | 做了「尽力兼容」，那比报错更危险 |
| 未知命令 ⇒ `E_UNSUPPORTED` 且连接仍可用 | 静默丢弃 | 用 default 分支直接忽略 |
| 未知字段被忽略 | 向前兼容坏了 | 用了严格反序列化 |
| `status` 与事件一致 | 两套真相 | 状态写了两处 |
| 未 up 时 `proxy` ⇒ `E_NOT_READY` | 会挂起 | 阻塞等网络，用户看到永久转圈 |
| `shutdown` 后进程退出、端口关闭 | 资源没释放 | 只关连接没退进程 |
| 空闲超时自行退出 | 会常驻 | 忘了 idle 计时，或把 proxy 算进活跃 |
| token 不出现在 stderr | 凭据泄漏 | 把 token 打进日志 |
| 只绑 `127.0.0.1` | 暴露给同网段 | 监听写成 `0.0.0.0` |

### V1.3 反向验证（证明这些测试**能**变红）

> 不做这一步，上面的绿就只是「跑过了」，不是「测到了」。

| 故意改坏什么 | 应该红哪条 |
|---|---|
| `hello` 里去掉 token 比较 | 错 token ⇒ `E_BAD_TOKEN` |
| `proto` 检查改成 `>= 1` | proto 不匹配 ⇒ `E_PROTO` |
| 未知命令改成 `return`（不断连接） | 未知命令 ⇒ `E_UNSUPPORTED` |
| 监听地址改成 `0.0.0.0` | 只绑 127.0.0.1 |
| idle 判断里把 `len(proxies)==0` 去掉 | 空闲超时后自行退出 |

## V2 数据面（需要第二台设备）

### V2.0 造对端

在**另一台设备**（或同一台机器开第二个节点）跑：

```bash
/tmp/overlay-sidecar/sidecar -hostname relay-overlay-b -dir /tmp/relay-overlay-b -echo 9099
```

浏览器点一次授权，等它打印 `RESULT echo_listen=...`。

### V2.1 拿到对端地址

在 A 侧查 `status`，从 `peers[]` 里取 B 的 `100.x` 地址（**不要手抄 IP**，用 peers 里的值）。

### V2.2 打通并量三件事

```
proxy(target = <B 的 100.x>:9099) → 得到本地端口 P
连 127.0.0.1:P，发送 50MB 随机数据，同时收回（B 是回显）
```

**三条判据，缺一不可**：

| 判据 | 通过条件 | 能区分什么 |
|---|---|---|
| **完整性** | 收回的字节数 == 发出；两边 SHA-256 一致 | 区分「跑完了」与「跑对了」——截断的连接也会“完成” |
| **吞吐** | 记录 MB/s；与直连（IPv6/局域网）对比给百分比 | 区分「能用」与「慢到不可用」 |
| **内存峰值** | 跑流量期间 A/B 的 `Sys` 或 RSS | 区分「空转 19.6MB」与「跑起来会不会爆」 |

### V2.3 失败时怎么读

| 现象 | 先查什么 |
|---|---|
| `proxy` 返回端口但连上去立刻断 | 对端 `echo_listen` 是否真的在跑；A 的 `peers` 里 B 是否 `online: true` |
| 字节数对得上但 SHA-256 不一致 | 分帧/粘包问题（本仓历史上这类最多） |
| 吞吐远低于直连 | 是否走了中继（`direct` 标志）；是否被 DERP 限速 |
| 内存持续上涨不回落 | 代理连接没回收（`close` 后 listener 是否真的关了） |

## V3 真实场景（这才叫产品级验证）

| 场景 | 操作 | 期望 |
|---|---|---|
| 路由器不放行 IPv6 入站 | 关掉入站放行，手机用蜂窝 | 仍能连上（走 overlay） |
| 对称 NAT / 企业网 | 公司 Wi-Fi | 走中继仍能连上，`direct=false` |
| 换网 | Wi-Fi ↔ 蜂窝 | 10 秒内恢复，`closeCount` 保持 0 |
| 不开 VPN | 全程不装系统 VPN | 无 VPN 图标，`VpnService` 数量不变 |
| 空闲释放 | 停用 2 分钟 | 进程退出、端口消失、内存归零 |

## 一句话

> **V1 证明「插件形式成立」，V2 证明「它真的搬得动字节」，V3 证明「用户场景下能用」。**
> 三个阶段的失败互不替代：V1 绿了不能说明 V2 能跑，V2 通了也不能说明 V3 的用户体验成立。