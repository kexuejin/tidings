# 跨网链路验收手册（外网侧三项）

三项本机做不了的验收：IPv6-only、跨出口、真机 4G。
这份手册把三项写成**别人拿着就能跑**的形式：命令、判读口径、需要谁提供什么入口。

配套的工具在 `agentNode/tools/`：三个零依赖 Python 单文件 + 一个浏览器页面。

| 工具 | 干什么 | 什么时候用 |
|---|---|---|
| `ws-relay-probe.py` | 打一次 WS：握手、发 entry、收桌面应答、算 SHA-256 | 连通性、字节级正确性 |
| `ws-soak.py` | 把一条 WS 挂住 N 分钟，看会不会被静默掐掉 | 长期挂着会不会断 |
| `net-shim.py` | 在两个 TCP 端点之间插一段坏网络 | 本机复现弱网（不需要外部网络） |
| `ws-link-check.html` | 浏览器里的分步自检页，填地址点按钮 | 没有 Python 的机器/手机上人工跑 |

> 三个工具都不要把 token 写在命令行上（`--token` 会让它出现在 `ps` 里）。
> 用环境变量注入：`export RELAY_AGENT_TOKEN="$(cat ~/.relay/agent-token)"`。

---

## 0. 先跑这一段：确认「对面有人在」

三项外网验收的前置都是**桌面节点在线**。先在本机确认，否则外网那边失败时
分不清是链路问题还是「对面根本没人」。

```bash
cd agentNode
export RELAY_AGENT_TOKEN="$(cat "$HOME/.relay/agent-token")"
python3 tools/ws-relay-probe.py "wss://<你的公网域名>/relay-ws/v1/dgram" \
    --entry --node-id "pre-$(date +%H%M%S)" --timeout 20
```

判读口径：

- 看到 `{"type":"peers"}` → 隧道和鉴权都通，控制面在。
- 看到 `{"type":"alive","nick":"<你的节点昵称>"}` → 桌面节点应答了，数据面在。**三项验完都要靠这条证明"数据真的到了对面"。**
- 只有 peers 没有 alive → 隧道活着但桌面节点没接上，或 nodeId 撞了。
  **nodeId 必须每次换一个**：对端按 nodeId 去重回复，同一个 id 重复发会拿到沉默，
  那是协议行为不是故障。
- `升级被拒：HTTP/1.1 401` → token 不对。

---

## 1. IPv6-only

### 需要谁提供什么

一个**没有 IPv4 通路**的网络 + 一台能连上去的机器。三种任选：

- 一台只有 v6 的 VPS（最省事，命令行环境，工具能直接跑）；
- 一部手机开 5G 并把 IPv4 关掉（见第 3 节的关 v4 做法），用浏览器打开自检页；
- 别人家的宽带（必须对方确认「只有 v6」——运营商的 v6 一般是双栈并存，
  「有 v6 地址」不等于「v6-only」）。

**本机双栈做不了这一项。** 2026-10-04 已在本机证明「v6 路径本身通」：
`curl -6 --noproxy '*'` 打到 Cloudflare 的 `2606:4700:3031::ac43:b8ef:443`，
WS 握手 + entry + 桌面 alive 全部往返正常（见
`design/runtime-evidence/cross-net-02-ipv6.txt`）。缺的是「没有 v4」这一半。

> **不要在跑 agentNode 的这台机器上用 pf/iptables 掐掉出站 v4 来模拟。**
> cloudflared 正是从这台机器出站连 Cloudflare 边缘的 v4 地址，掐掉之后隧道自己先死；
> 而「客户端 v6-only」与「服务端出隧道不可用」两个变量会缠在一起，验不干净。
> 真要模拟，只在**别的**机器上做。

### 命令

```bash
# ① 先确认这台机器真的只有 v6 —— v4 应该是「连不上」而不是「连上了」
curl -4 --noproxy '*' -s --max-time 8 https://api.ipify.org ; echo " <- 期望：超时/失败"
curl -6 --noproxy '*' -s --max-time 8 https://api64.ipify.org ; echo " <- 期望：2xxx: 开头的地址"

# ② 控制面通不通（无凭据返回 401 就是通的）
curl -6 --noproxy '*' -s -o /dev/null -w "http=%{http_code} 远端=%{remote_ip}\n" \
    --max-time 15 https://<你的公网域名>/relay/v1/peers

# ③ 数据面端到端（这一步才算数）
python3 tools/ws-relay-probe.py "wss://<你的公网域名>/relay-ws/v1/dgram" \
    -6 --entry --node-id "v6only-$(date +%H%M%S)" --timeout 20
```

> **`--noproxy '*'` 不能省。** 本机有 `HTTP_PROXY=127.0.0.1:7890`，带 `-6` 的 curl
> 照样先连代理，于是它报的 `remote_ip` 是代理地址——量具测的是代理不是网络。
> 2026-10-04 就是这样把「有 v6 出口」误判成「没有」的。
> Python 工具不走环境代理，不受这条影响。

### 判读口径

| 现象 | 结论 |
|---|---|
| ① v4 超时 + v6 出 v6 地址；③ 握手成功且收到 `alive` | ✅ IPv6-only 通过 |
| ① v4 也连上了 | 这不是 v6-only 环境，换一台再来 |
| ③ 收到 `peers` 没有 `alive` | v6 通了但桌面没接上，回去看第 0 节 |
| ③ 握手就失败，但 v4 下同一命令成功 | **这条要当 bug 查**，不是网络问题 |

### 记录

把 ①②③ 的完整输出贴进 `design/runtime-evidence/`。

---

## 2. 跨出口（VPS / 陌生 Wi-Fi）

### 需要谁提供什么

**一台不属于当前网络的机器。** 优先 VPS（命令行、可重复、不打扰别人）；
用陌生 Wi-Fi 时要问一句「这是不是运营商 NAT 后面的网络」——如果是，
你验的是「CGNAT 能不能过」，比 VPS 有意思，但结论要分开写。

不需要 root，不需要改任何配置。

### 命令

**方式 A（本机跑，VPS 只做跳板，推荐）** —— 不用把仓库拷到 VPS，
`ssh -L` 是纯端口搬运，TLS 的 SNI 与 `Host` 仍取自 URL：

```bash
# ① 出口 IP 必须是 VPS 的，且与本机不同 —— 没这一行，「跨出口通过」等于没换过出口
ssh vps 'curl -4 --noproxy "*" -s --max-time 10 https://api.ipify.org; echo'

# ② 起正向转发（Cloudflare 的 v4 边缘地址）
ssh -N -L 127.0.0.1:18952:104.21.19.33:443 vps &

# ③ 连通性 + 数据面（--connect-to 只换实际拨号地址，URL 不变）
python3 tools/ws-relay-probe.py "wss://<你的公网域名>/relay-ws/v1/dgram" \
    --connect-to 127.0.0.1:18952 --entry --node-id "xnet-$(date +%H%M%S)" --timeout 30

# ④ 挂 10 分钟
python3 tools/ws-soak.py "wss://<你的公网域名>/relay-ws/v1/dgram" \
    --connect-to 127.0.0.1:18952 --minutes 10 --ping-every 60 --read-timeout 20
```

方式 A 不会干扰本机网络，出问题直接 `kill %1` 掉转发即可。
探针首行应打印 `对端 104.21.19.33:443`（或等价地址）——走转发时对面仍是
Cloudflare，这是对的，验的是「从另一个出口够到 Cloudflare」。

**方式 B（直接在外部机器上跑）** —— 陌生 Wi-Fi / 热点 / VPS 上直接执行，
不加 `--connect-to`：

```bash
export RELAY_AGENT_TOKEN="<从 $HOME/.relay/agent-token 拷来的>"
cd agentNode

# ① 记录你的出口 IP，和本机的不一样才算「跨出口」
curl -4 --noproxy '*' -s --max-time 10 https://api.ipify.org ; echo

# ② 连通性 + 数据面
python3 tools/ws-relay-probe.py "wss://<你的公网域名>/relay-ws/v1/dgram" \
    --entry --node-id "xnet-$(date +%H%M%S)" --timeout 20

# ③ 挂 10 分钟（外网建议比本机短，因为网络本身会抖）
python3 tools/ws-soak.py "wss://<你的公网域名>/relay-ws/v1/dgram" \
    --minutes 10 --ping-every 60 --read-timeout 20
```

### 判读口径

- **凭据里必须有 ① 的出口 IP，且与本机家宽出口 IP 不同。**
  没有这一行的「跨出口验证通过」等于没换过出口。
  家宽出口地址每次都可能变（**家宽是动态地址，跑的时候
  以当时为准**，别拿这个当固定值对照）。
- ② 必须收到 `alive`（不只是 `peers`）——否则不能说明数据到了对面。
- ③ 必须出现 `结论：✅ 撑满了全程`。出现 `结论：❌ 没撑住` 时，
  **记录断链时刻和 `断链：…` 那一行的原文**（是「读失败：Connection reset」
  还是「收到 CLOSE，code=… reason=…」），这两种指向完全不同的原因：
  前者是网络/代理把它掐了，后者是服务端主动收（空闲回收或心跳超时）。
- RTT 明显高于本机的 **353ms 基线**（家宽实测，`cross-net-03-soak-30min.txt`）
  不是失败，记下来作为**该出口自己的基线**。
  但不要跨网络直接比：VPS 链路的转发比 Wi-Fi 多一跳，两者的 RTT 不可互相比较。
- 挂机期间**不要**在桌面端操作——那会引入本地干扰，让这次测量不干净。

---

## 3. 真机 4G

### 需要谁提供什么

一部 Android 手机 + 一张能上网的 SIM 卡。没有卡就用手机的 Wi-Fi 做
「陌生 Wi-Fi」版本（并把结论分开写，别混进 4G 的结论里）。

> **不想装 App、不想编译 adb 的话，直接跳到第 4 节。**
> 自检页面直接用本目录的 `ws-link-check.html`（也可以经隧道暴露成公网地址，
> 见第 4 节），手机浏览器打开、填 token、点按钮、截图即可，
> 同样能定位「断在哪一段」。第 4 节能查到的是链路层；下面这一节能查到的是
> **App 自身**在 4G 上的行为（重连、大消息、代理适配），两者不能互相替代。

### 步骤

```bash
# ① 插上手机，确认 adb 认到
adb devices -l

# ② 装（模拟器上的要先卸掉，否则端口冲突）
./gradlew :androidApp:installDebug
```

③ 手机上打开 App → **设置 → 连接**，把 `baseUrl` 从默认的
`http://192.168.1.5:8788`（局域网地址，出网必然不通）改成：

```
https://<你的公网域名>/relay
```

> 这一步是最容易漏的：默认值是局域网 IP，手机一切到 4G 就必然连不上，
> 而现象是「节点一直离线」——很容易误判成链路坏了。
> 设置页的输入框带字段名（`baseUrl` / `timeoutMs` / …），
> 见 `composeApp/src/commonMain/kotlin/app/relay/feature/settings/SettingsScreen.kt:282`。

④ 关掉 Wi-Fi，**只留 4G**。

⑤ 逐条验：

| 要验的 | 怎么验 | 判读 |
|---|---|---|
| 节点列表 | 桌面 App 的在线节点里能看到手机 | 看得到 |
| 发消息 | 手机 → 桌面，桌面收到 | 收到且内容一字不差 |
| 反向 | 桌面 → 手机，手机收到 | 同上 |
| 大消息 | 桌面发一条 ≥ 1 MiB 的消息（带图片/文件） | 收全，**内容逐字节对得上**，不是「看着差不多」 |
| 挂着不掉 | 手机放兜里 10 分钟，再发一条 | 还能发；发不出去就是被静默掐了 |

⑥ 收工：

```bash
adb logcat -d | grep -iE "relay|ws" > /tmp/relay-verify/adb-4g.log
adb devices -l   # 记下结果
```

截图存 `design/runtime-evidence/`，命名 `cross-net-04-4g-<序号>-<场景>.png`。

### 真机 4G 的特殊判读

- **切网瞬间不要当成失败**。4G 基站切换、Handover 都会断一次连接，
  客户端应该自己重连。要看的是「断了之后有没有自己回来」：
  断 → 等 → 重新出现在节点列表 / 能收发 ⇒ 过。
- **大消息要用摘要判读，不能靠眼睛**。1 MiB 分界那条 bug 当初就是
  「看着收到了」骗过去的。要么在桌面端把收到的消息存盘后算 SHA-256，
  要么走 `ws-link-check.html`。
- 手机连不上时先分清是**代理**问题：4G 下很多网络要求走系统代理，
  WS 库若没适配就会全部失败。这属于客户端实现缺口，不要记成「链路不通」。

---

## 4. 真丢包下的 TCP 重传正确性

这一项**不需要外部网络**，但需要 `NET_ADMIN`。本机（macOS）拿不到，
所以写成一份可以直接照抄执行的设计，而不是一句"未做"。

完整勘查与命令见 `design/runtime-evidence/cross-net-04-packet-loss.txt`，
这里只列交接要点：

- **阻塞点**：macOS 没有 `tc/netem`；`sudo` 被沙箱拒执行（报
  `Operation not permitted`，**不是缺密码**）；`pfctl` 要 root；
  Docker Desktop 装了但守护进程没启动。
- **三条解除路径**：启动 Docker Desktop（容器里 `tc netem loss`）／
  一台 Linux VPS（可与第 2 节「跨出口」复用同一台）／在界面授权 sudo 后用
  `pf` 的 `match … randomization` 做概率丢包。
- **不要用 toxiproxy 之类的应用层代理顶替**：它掐连接或注延迟，TCP 段一个没丢，
  重传根本没发生，结果不能算通过。同理 `--stall-ms`、代理挖洞再补、掐连接，
  测的都不是这一项。

判读四条，全满足才算过：

1. **`dropped > 0`** 且 `dropped/sent` 与配置值同量级——这是丢包真实发生的地面真相。
   `dropped == 0` 时其余结论一律作废，"没发现损坏"不构成证据。
2. **上下行 SHA-256 相同**（不能比字节数），512 KiB 与 4 MiB 各一轮。
3. **吞吐没塌**——TCP 正常重传只是变慢，不是卡死。
4. **丢包率 5% 起，最多 10%**；再高就分不清是网络还是应用的问题了。

---

## 5. 没有 Python 的机器（手机 / 别人的电脑）

用 `ws-link-check.html`。它不依赖命令行，浏览器里打开即可，**手机 Safari / Chrome 直接能用**。

### 用隧道把它暴露成公网地址

手机直接开本地文件不方便时，可以经隧道把这个页面暴露出去：

    https://<你的公网域名>/relay-check/ws-link-check.html

做法是在跑 agentNode 的机器上起一个静态服务器，再让隧道路由指过去：

```bash
cd <仓库路径>/agentNode
nohup python3 -m http.server 8891 --bind 127.0.0.1 >/tmp/relay-check.log 2>&1 &
# 核对：应 http=200 且与源文件字节一致
curl -s --noproxy '*' -o /tmp/check.html -w 'http=%{http_code} bytes=%{size_download}\n' \
  https://<你的公网域名>/relay-check/ws-link-check.html
cmp /tmp/check.html tools/ws-link-check.html && echo "字节一致"
```

`strip_path_prefix` 剥掉前缀后，落到 8891 上的路径应当能取到本文件；
取不到就检查隧道那侧的前缀配置。

### 怎么用

1. 页面上三格依次填：控制面地址（`https://<你的公网域名>/relay`）、
   数据报通路（`wss://<你的公网域名>/relay-ws/v1/dgram`）、
   agentNode token。**token 留空只能验到「隧道通了」**，填了才能验到桌面应答。
2. 点开始，看它走 ①控制面 → ②WS 握手 → ③发 entry → ④等桌面回音。
3. 页面**故意等满 14 秒**才下结论：entry 的回复在 0..8 秒内随机抖动，
   早下结论会把「还没到」读成「不会来」。
4. 整页截图。

它不传任何数据到第三方，纯本地页面。

### 判读口径

| 页面结论 | 含义 | 下一步 |
|---|---|---|
| ① 不过（HTTP 到不了） | 更早那几段就断了 | 查 DNS、4G 是否允许长连接、隧道是否还在。**先别碰 WebSocket** |
| ② 不过（WS 握手失败） | 隧道通了但网关段不行 | 查 8789 是否在监听、`/relay-ws` 路由是否还在 |
| ④ 桌面没有回音 | 隧道通了，桥到桌面那段没通 | 在跑 agentNode 的电脑上查桌面节点是否在跑、UDP 17878 是否被占 |
| ④ 收到 `alive` / `peers` | **全链路通** | 这条网络能直接够到桌面节点，日常聊天与文件传输都走它 |

HTTP 预检失败时页面仍会继续跑 WebSocket——这是故意的：控制面和网关是两个
独立入口，前者挂了后者照样可能通，停在第一步就下结论会误杀一条能用的链路。

---

## 6. 交回来时要带的东西

每项跑完，落到 `design/runtime-evidence/cross-net-1X-<项>-<YYYYMMDD>.txt`：

1. **命令原文**（能复制粘贴的那几行，不是描述）；
2. **时间**；
3. **输出读数原文**（探针首行的地址族与两端地址、帧类型与字节数、
   挂机的 `存活/计划` 与 RTT 三元组）；
4. **结论**，以及按上文口径判断的理由；
5. 这一项特有的凭据：IPv6-only 要 v4 失败 / v6 成功的双行输出，
   跨出口要有 VPS 的出口 IP，真机 4G 要整页截图 + 网络类型（4G/5G）+ 大致位置，
   真丢包要有 `tc -s qdisc` 的 `dropped/sent` 原始计数。

三份都回来之后，本文件归档。