# `scripts/e2e/` —— 捎话真机 e2e 夹具

这套夹具回答的问题是单测回答不了的那个：**在真手机 + 真对端这一层，功能是不是真的通了。**
背景与来由写在 `docs/plans/2026-10-09-relay-e2e-fixture-program.md`；
「哪些功能还差夹具」写在 `docs/plans/2026-10-09-relay-e2e-coverage-matrix.md`。

一句话记住它的位置：单测（900+）跑在进程内回环，**永远到不了真机 + 真对端**。
投屏那三个根因（`sessionId` 不是 UUIDv4、`token` 不是 32 位 hex、ktor socket 一次性绑定）
就是在 204 个测试全绿的情况下让整个功能 100% 失效的。夹具就是为了照到这一层。

---

## 1. 目录里现在有什么

```
scripts/e2e/
  relay_e2e.py       # 共享库：协议编解码 + UDP/TCP 替身 + adb 驱动 + 断言 + 结果打印
  run_all.py         # 统一入口：按派活顺序逐个跑 e2e_*.py，汇总退出码
  e2e_messaging.py   # 短文本 UDP / 长文本 TCP / 离线补发（A5 A6 A12 C2）
  e2e_discovery.py   # 发现 / 配对 / 节点面（矩阵 §2 B 段 R16–R27）
  e2e_files.py       # 文件柜与文件传输（R28–R48）
  e2e_media.py       # 图片/语音等媒体消息（矩阵 §2 E 段 R49–R66）
  e2e_group.py       # 群聊：建群 / 归位 / 扇出 / 署名 / need 防风暴 / 邀请与移出（§2 G 段 R67–R79）
  e2e_share.py       # 共享文件柜（§2 I 段 R88–R99；当前多为诚实的负向夹具，见文件头）
  e2e_agent.py       # Agent 档会话与事件流（R80–R87）
  e2e_search.py      # 检索（R100–R102）
  e2e_screen.py      # 屏幕协助双向（A17）
  e2e_workspace_collaboration.py  # 工作区协作面：外部来源配对二次确认（N5 的
                                  # qr-pairing-confirmed，脚本真的点确认框）与授权面板
  README.md          # 本文件
```

> **M4 的 `agi1` 帧族（`agent-invoke` / `agent-invoke-result`）与 `/v1/agent/invoke`
> 路由没有真机夹具**。跨端派单的覆盖在 JVM 双节点仿真（`app/src/jvmTest/.../TwoNodeAgentInvokeTest.kt`：
> 到达 / 零刷屏 / 断连租约超时 / 审批防重）与控制面契约测试（`agentNode/src/test/.../AgentInvokeRouteTest.kt`：
> 400/404/503 失败面），真机读数在 `design/runtime-evidence/2026-10-30-m4-cross-host-invoke-mesh.md`
> （invoke 路由三档读数 + 手机 `@agent` 完整回路）。要给派单补真机夹具，按 §5 的规矩
> 新增 `e2e_agent_invoke.py`，不要改既有文件。

**行数故意不写在这里**：本文件曾经写「`relay_e2e.py` 1118 行」，而它早已是 1321 行 ——
硬编的数字一定会烂掉。要准确清单跑 `wc -l scripts/e2e/*.py`。

新夹具一律命名 `e2e_<feature>.py`：`run_all.py` 按名字自动发现，加夹具**不需要**改 `run_all.py`。
派活顺序与每个夹具要覆盖的验收条目在 `docs/plans/2026-10-09-relay-e2e-coverage-matrix.md` §9；
但那份矩阵**本身有错**（行号漂移、把死代码当「UI 存在」），写夹具时以代码为准，见 §5.5。

`relay_e2e.py` 是从 `scripts/relay-screen-fixture.py`（已在 Pixel 6 上跑通过的那份）
抽出来的，协议层**照抄**它，不要另起一套。抽出来的每条不变量都带注释说明
「错了会怎样」，因为那些坑的现象都是无声的（丢帧、等你超时，而不是报错）。

---

## 2. 环境前提（跑之前逐条确认）

| 前提 | 怎么确认 | 说明 |
|---|---|---|
| 真手机插着、`adb` 里有设备 | `adb devices` 出现 `<serial>\tdevice` | 没有设备时夹具会走到退出码 **2**（`relay_e2e.py:421 require_device()`） |
| 手机 IP | 默认 `192.0.2.22`（`relay_e2e.py:104 DEFAULT_PHONE`，Pixel 6 `SERIAL-TEST-01`） | 换了网络要显式传 `--phone` |
| 本机 Mac 的 LAN IP | 默认 `192.0.2.82`（`relay_e2e.py:105 DEFAULT_HOST_IP`，`en1`） | **必须和手机同一个网段**：它会被写进夹具 profile 的 `host` 字段，空字符串会让手机整帧看不明白（`relay_e2e.py:165-174 set_host()`） |
| 对端空闲 | `scripts/relay-liveness.sh --quiet; echo $?` | **跑任何夹具之前先跑它**。`0` = 对面空闲，可以开始；`1` = 对面正在写，等它停；`2` = 用法错误。外部进程可能占用 `UDP *:17878`、`TCP *:17878`（加密数据面的监听口）与 `TCP *:17879`（legacy），它跑着的时候夹具的握手会被抢 |
| 手机装了被测包 | `adb shell pm list packages \| grep app.relay` | 包名 `app.relay` |
| 手机和本机在同一 Wi-Fi、没开 VPN | 手机节点页顶部同步条变绿 | VPN 会让 mDNS/广播收不到（界面会出 `disc_vpn_warning`） |

> 本机若还要跑桌面轨（`:desktopApp:run`），先确认默认端口没被占用：
> `lsof -nP -iUDP:17878 -iTCP:17878 -iTCP:17879`（加密数据面开启时监听的是 `TCP 17878`，不是 17879）。
> 构建要 `JAVA_HOME=/opt/homebrew/opt/openjdk@21`。

### 2.1 夹具占哪些端口，以及被测端要先回到 legacy

夹具演的是**老协议的替身**：它广播 `entry`、在 `--tcp-port` 上等 4 字节大端分帧。所以端口账要分开算：

| 端口 | 谁占 | 说明 |
|---|---|---|
| `UDP 17878` | 被测端（发现）+ 夹具（`UdpPeer` / `--udp-listen` sink） | 夹具往这个端口广播 `entry`；`e2e_share.py` 要求它没被外部节点占用 |
| `TCP 17878` | 被测端（加密信道的监听口，与发现端口**同号不同协议**） | 只在加密数据面开启时存在；不是给夹具拨的 |
| `TCP 17879` | **legacy 档**的被测端（明文 TCP 帧），也是夹具自己的 `--tcp-port` 默认值 | 加密数据面开启后被测端**不再监听** 17879；17881/17882 同理退役 |
| `TCP 179xx` | 夹具（`--tcp-port`） | 本机同时跑桌面轨或别的夹具时用它避开 17879，例如 `--tcp-port 17920` |

**前提：被测端要先把加密数据面关掉并重启节点。** 默认开着时，被测端既不启动 legacy 发现、也不监听 17879/17881/17882 —— 夹具广播的 `entry` 没人解，长文本也没有 TCP 那条落点，结果是「夹具连握手都建立不起来」，而不是「夹具抓到了缺陷」。这不是夹具退步：这九个夹具验的就是 legacy 路，加密数据面自己的验收在 `ACCEPTANCE.md` 的 A21。

---

## 3. 怎么跑

### 3.1 跑单个夹具

```bash
# 1) 先确认对面空闲（exit 0 才继续）
scripts/relay-liveness.sh --quiet || exit 1

# 2) 跑一个夹具
python3 scripts/e2e/e2e_messaging.py --phone 192.0.2.22 --host-ip 192.0.2.82 --secs 90
```

统一 CLI（`relay_e2e.py:1030-1078 main_wrapper()` 提供，所有夹具一致）：

```
python3 scripts/e2e/e2e_<feature>.py --phone <手机IP[:UDP端口]> [--host-ip <本机LAN IP>]
                                   [--secs N] [--dry-run]
```

| 参数 | 默认 | 含义 |
|---|---|---|
| `--phone` | `192.0.2.22` | 手机 IP，可带 `:UDP端口`；也接受 `[v6]:port` 写法 |
| `--host-ip` | `192.0.2.82` | 本机 LAN IP，写进夹具 profile 的 `host`；非合法 IPv4 直接退出码 2 |
| `--secs` | `90` | 本夹具的总预算秒数（等待窗口的上界），不是超时后强杀 |
| `--dry-run` | 关 | 只做参数与纯本地自检：**不碰网络、不碰设备**，退出码 0 |

> **注意**：契约文档写 `--phone` 必填，但共享库给了默认值（`relay_e2e.py:1039`），
> 不传也能跑起来、且会去连 `192.0.2.22`。**建议永远显式传**，否则你在别的网段会
> 得到一个「连不上」的假失败。

### 3.2 跑全部夹具

```bash
python3 scripts/e2e/run_all.py                    # 逐个跑，最后一张汇总表
python3 scripts/e2e/run_all.py --dry-run          # 每个夹具都只做本地自检
python3 scripts/e2e/run_all.py --only messaging --only screen
python3 scripts/e2e/run_all.py --list             # 只列出会跑的夹具顺序
```

它按 `PREFERRED_ORDER`（矩阵 §9 的派活顺序）排序，新夹具不加进那个表也能跑到，
排在后面。汇总表每行是 `夹具 / 结论 / 退出码 / 用时`，结论列取值：

| 结论 | 含义 |
|---|---|
| `PASS` / `FAIL` | 夹具打了契约 result 行 |
| `PASS?` | 退出 0 但**没打** result 行（夹具没走到 `report()`）—— 算过，但看得见 |
| `DRYRUN` | `--dry-run` 下正常现象：dry-run 本来就不走 `report()` |
| `ENVERR` / `TIMEOUT` | 环境问题，**不是**功能失败 |

退出码取最坏的一个：**2 > 1 > 0**，与单个夹具同一套语义。

两处刻意的行为差异，说明一下：

- **对端忙算退出码 2，不算 1。** §2 里那段 shell 是 `relay-liveness.sh --quiet || exit 1`，
  但「对端忙」是环境没准备好，报 1 会让人去查一个不存在的 bug。要等它停就加
  `--wait-busy 120`（每 5 秒轮询一次）。确认无其他常驻节点运行时用 `--skip-liveness`。
- **`--secs` 是传给夹具的预算秒数，`--timeout` 是 run_all 自己的墙钟上限**（默认 900s）。
  夹具挂死时由 run_all 杀掉并按 `TIMEOUT` 计入环境问题。

等价的手写循环（不想用 run_all 时，语义一致）：

```bash
scripts/relay-liveness.sh --quiet || exit 1
rc=0
for f in scripts/e2e/e2e_*.py; do
  echo "=== $f ==="
  python3 "$f" --phone 192.0.2.22 --host-ip 192.0.2.82 || rc=$?
done
echo "overall exit=$rc"
```

### 3.3 没有真机时：`--dry-run`

```bash
python3 scripts/e2e/e2e_messaging.py --dry-run
```

它**不碰网络、不碰设备**，只做参数解析与纯本地断言（共享库提供
`relay_e2e.py:979-1028 local_self_checks()`：信封字段是否齐全、`v` 是否等于 1、
短文本是否落在 800 B 内、长文本是否真的非走 TCP 不可、profile 字段是否超
`Limits`、4 字节大端分帧能否原样往返、`RELIABLE` 集合是否含 `msg`）。
退出码 **0**。改夹具的人在没插手机时靠它确认自己没把夹具写崩。

**每个夹具都必须实现 `--dry-run`**，这是硬要求（契约 §1.2）。写法是夹具自己在
`run(args)` 开头判断，共享库只负责挂这个 flag：

```python
def run(args):
    if args.dry_run:
        local_self_checks(short_text="你好", long_text=long_text(1200))
        return 0                       # ← 必须在这里返回，别落到 report()
    ...
    return None                        # → main_wrapper 收尾自动 print report()

if __name__ == "__main__":
    sys.exit(main_wrapper(run, description="e2e: 消息收发"))
```

---

## 4. 怎么读输出

### 4.1 三种行

```
[EVID] host-ip=192.0.2.82 phone=192.0.2.22:17878 nodeId=3f9a1c02   ← 证据，给人看的
[ ok ] short-envelope-fits-udp-packet — 214B <= 1200B                   ← 断言通过
[FAIL] long-envelope-forces-tcp — 780B > 1200B                          ← 断言失败
```

- `[EVID]` 是**证据行**（`relay_e2e.py:129 log()`），随便多少行，方便和断言行分开抓。
- `[ ok ]` / `[FAIL]` 是**断言行**（`relay_e2e.py:135 check()`），每出一条就打印一条，
  同时累积进总数。

### 4.2 结尾恰好一行 `result`

`relay_e2e.py:146-153 report()` 打印**恰好一行**，全 PASS 与有失败两种形状：

```
result PASS 12
result FAIL 12 failed=long-envelope-forces-tcp,profile-caps-within-limits
```

`failed=` 后面是逗号分隔的**失败名单**（断言名），不是错误码。
**判读规矩：抓最后一行、只看 `result` 那一行**；中间的行是给定位用的，不是结论。
一个夹具 PASS 的标准就是这一行是 `result PASS <n>` 且 `<n> > 0`
（`n = 0` 说明夹具一条断言都没跑，那是夹具写空了，不是通过）。

### 4.3 退出码

| 退出码 | 含义 | 什么时候出现 |
|---|---|---|
| **0** | PASS | `report()` 无失败；`--dry-run` 自检通过 |
| **1** | FAIL | `report()` 有失败（`relay_e2e.py:151` 返回 1） |
| **2** | **用法或环境错误** | `EnvError`（如 `--host-ip` 不是 IPv4）、`FixtureError`（如 `host-ip` 为空、`type` 超长）、`KeyboardInterrupt`，以及**任何未捕获异常**（会打 traceback 后返回 2）。`adb devices` 里没有设备也走这条 |

CLI 上一般这样连读，别只看退出码：

```bash
out=$(python3 scripts/e2e/e2e_messaging.py --phone 192.0.2.22 2>&1); rc=$?
echo "$out" | tail -1
[ "$rc" -eq 0 ] && echo "PASS" || echo "不通过（$rc）"
```

退出码 **2 不是功能失败** —— 那是「你还没准备好」。看到 2 先回去查 §2 的前提表。

> 既有投屏夹具 `scripts/relay-screen-fixture.py` 的结论行是
> `result PASS|PARTIAL|FAIL frames N`，**多一个 `PARTIAL` 第三态**。新夹具按契约只有
> PASS/FAIL 两态；把投屏挂进统一 CLI 时（`e2e_screen.py`）要显式决定 PARTIAL 映射成什么。

---

## 5. 写新夹具时的规矩

### 5.1 只新增，不改既有文件（硬规矩）

**夹具只允许新增 `scripts/e2e/` 下的文件。不得修改任何既有文件** ——
包括 `scripts/relay-screen-fixture.py`、`scripts/relay-liveness.sh`、
仓库根目录下的任何源码或测试、以及本 README 以外的任何文档。

这条不是洁癖：既有夹具已经在真机上跑通、协议层是「踩过坑才对的那一套」，
改它等于把投屏的验收证据作废。要在共享层加东西，就改 `scripts/e2e/relay_e2e.py`
（它本身就是新增文件）；要挂投屏，就新写 `e2e_screen.py` 调它，而不是改原夹具。

顺带：**`e2e_<feature>.py` 之间互相不 import**（契约 §1.1）。共享的都在
`relay_e2e.py` 里，夹具之间没有依赖，才能各自单独跑。

### 5.2 用共享库，别自己抄一份协议

必须用（不要重新实现）：

| 用途 | API | 位置 |
|---|---|---|
| 证据行 / 断言 / 结尾 `result` | `log()` / `check(name, ok, detail="")` / `report()` | `relay_e2e.py:129/135/146` |
| 时间戳、信封、编码 | `now_ms()` / `envelope(mtype, payload)` / `encode(env)` / `utf8_len(s)` | `:176/180/201/206` |
| 假装对端的 profile | `profile_caps(...)` | `:210` |
| UDP 打招呼 / 告别 | `send_entry(sock, host, port, alive=False)` / `send_env(...)` | `:267/285` |
| TCP 分帧 | `tcp_frame(obj, body=b"")` / `tcp_msg_frame(env)` / `tcp_msg_ack(...)` / `ack_env(...)` / `read_tcp_frame(conn, deadline)` | `:291/303/312/317/352` |
| 现成的对端替身 | `UdpPeer` / `TcpFrameServer` | `:627/832` |
| adb | `adb(*args)` / `require_device()` / `app_pid()` | `:399/421/432` |
| 界面驱动 | `uiautomator_dump()` / `ui_all()` / `find_ui(text=...)` / `tap_text(label)` / `tap_node(node)` / `press_back()` / `clear_focused_text(n)` | `:436/497/527/548/541/582/586` |
| 截图 | `screencap(path)` | `:593` |
| 纯本地自检（给 `--dry-run`） | `local_self_checks(short_text, long_text, ...)` | `:979` |
| CLI 与异常收敛 | `main_wrapper(fn, description=...)` | `:1030` |

**点坐标必须由 uiautomator 解析 bounds 求出**，不许目测 —— 投屏那一批就因为目测
点坐标误触过返回键。用 `tap_text` / `tap_node`，不要 `adb shell input tap x y` 写死。

### 5.3 别踩的坑（写在共享库注释里的那些）

- `v` 必须严格等于 `1`（`PROTOCOL_VERSION`），对不上手机整帧丢弃、**连 reason 都只在日志里**。
- 信封六个字段一个都不能少：`v / type / id / from / ts / payload`。
- `id` 每帧唯一：手机侧 `dedupStore` 按它去重，重复 id 会被当重传。
- 文本判据是 **UTF-8 字节**不是字符：`> 800 B` 走 TCP（夹具这条腿是 **legacy 17879**；加密数据面下走加密信道，17879 已退役，见 §2.1）；**单聊** `> 4096 B` 被拒，
  **群文本** `> 32 KiB` 才被拒（`Limits.GROUP_TEXT_TCP_LIMIT`，见 `PROTOCOL-EXTENSION.md` §7.1）。
- 出站单包上限 1200 B、入站硬上限 4096 B（`UDP_MAX_PAYLOAD` / `UDP_MAX_INBOUND`）。
- 文本走 TCP 后要等 `msg-ack`，精确超时 **7000 ms**（`TCP_ACK_TIMEOUT_MS`）。
- `entry` 的 `profile.host` 必须非空字符串，否则手机看不懂整帧。
- profile 的 `platform` 只能是 `win / mac / linux` —— 协议里没有 `mobile`，手机端自己伪装成 `linux`。
- 一个进程只有**一个** `FIXTURE_NODE`（`relay_e2e.py:161`），进程内稳定、跨进程不同。

### 5.4 一个夹具的最小骨架

```python
#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import sys
from relay_e2e import (main_wrapper, log, check, profile_caps, send_entry,
                       local_self_checks, long_text, T_MSG, K_TEXT)

def run(args):
    if args.dry_run:                       # 不碰网络、不碰设备
        local_self_checks(short_text="e2e-hello", long_text=long_text(1200))
        return 0

    import socket
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    prof = profile_caps(nick="E2E夹具", caps=["tw1", "mrec1", "agn1", "tbl1"],
                        tcp_port=17879, host=args.host_ip)
    send_entry(sock, "255.255.255.255", args.phone_udp, alive=False, profile=prof)
    log("已广播 entry，等手机回 alive")
    ...
    check("phone-saw-us", True, "手机节点列表出现夹具节点")
    return None                            # 返回值 None → main_wrapper 自动 report()

if __name__ == "__main__":
    sys.exit(main_wrapper(run, description="e2e: <功能名>"))
```

`args` 上可用的字段：`args.phone_ip`、`args.phone_udp`、`args.host_ip`、
`args.secs`、`args.dry_run`、`args.fixture_node`（由 `main_wrapper` 注入）。

---

### 5.5 共享库的二次踩坑（写完第一个夹具才暴露的那些）

下面每条都是夹具作者**真的被咬过**才发现的，`relay_e2e.py` 的注释里没有：

- **`main_wrapper` 不定义 `args.nick`。** 它只注入 `args.phone_ip` / `args.phone_udp` /
  `args.fixture_node` / `args.secs` / `args.dry_run`。夹具要昵称就自己加 `--nick`
  （`extra_args` 那条路），别指望共享库给。
- **`long_text(total, head="L")` 的 `total` 是「重复次数」不是「字节预算」。**
  `head` 传多字符字符串时长度会成倍膨胀 —— 传 `head="长正文", total=1500` 实测得到
  **13412 字节**，直接把 `long-text-within-tcp-limit` 这类自检顶红。要精确字节就传单字符
  `head`，或者自己按 `utf8_len()` 收口。
- **`profile_caps(host=None)` 会回落到 `FIXTURE_HOST`**，而 `--dry-run` 下那是空串
  ⇒ 抛 `FixtureError`。`host` 必须显式传，别依赖默认。
- **已知对端的源地址/端口漂移 ⇒ reliable 帧被静默丢弃，连 ACK 都没有**
  （`composeApp/src/commonMain/kotlin/app/relay/app/Messenger.kt:96-149`）。
  夹具全程必须用**同一个源端口**；压测前重新 `entry` 一次最稳。
- **重试要换 envelope `id` 但保持同一个 `cursor`。** 手机侧 `dedupStore` 按 `(id, from)`
  去重，而 `mergeAgentEvent` 按 `cursor` 去重 —— 只有这样重试才是幂等的。
- **夹具进程的 `FIXTURE_NODE` 必须声明 `ag1`**，否则 Agent 页只会画空状态并 `return`
  （`RelayUiState.kt:85 isAgent = CAP_AGENT in caps`、`agentPeers = peers.filter{isAgent}`，
  再由 `AgentConsoleScreen.kt:191-200` 提前返回）。缺 `ag1` 时你会以为「UI 坏了」。

---

## 6. 做完一轮之后的收尾

1. 把读到的数字补进 `ACCEPTANCE.md` 对应条目，证据落到
   `design/runtime-evidence/<条目>-<平台>.txt`（格式见 `ACCEPTANCE.md` §0）。
2. 覆盖矩阵（`docs/plans/2026-10-09-relay-e2e-coverage-matrix.md`）里那一行的
   「现在被什么覆盖」从「人工验收项」改成「机器夹具」。
3. 摄像头对屏、OS 文件出口、通知后台可达性、iOS 真机本地网络授权这几项
   **不要**写成夹具，照 `docs/physical-acceptance-sop.md` 走人工；
   签完字把 `design/host-surface-checklist.md` 第 6 节的空表填上
   （它现在是空的，而 sop §4 已经打了勾 —— 详见矩阵 §8.2）。


---

## 身份恢复（2026-10-25）

L2 两张帧（`recovery-req`/`recovery-data`）对旧节点是未知类型 ⇒ 静默丢弃（Q5 设计）。
`e2e` 脚本如需覆盖：恢复方发 req 后 5 秒窗口内应收到分批 `recovery-data`
（批间 100 ms），旧端场景断言「无响应且离线队列为空」。
