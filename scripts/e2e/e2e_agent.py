"""Agent 控制台 E2E 夹具：agent-session / agent-event 帧往返 + 手机「Agent」页渲染。

覆盖矩阵：`docs/plans/2026-10-09-relay-e2e-coverage-matrix.md` §2 H 段 R80–R82
（R83/R84/R85 见文末「覆盖与边界」）。

做什么
------
夹具扮成一个**声明了 ag1 + agn1 的 agent 宿主节点**（caps 与
`core/src/commonMain/kotlin/com/kapp/tidings/core/protocol/MessageTypes.kt:150` 的
`AGENT_CAPS` 逐项一致），在 LAN 上与手机握手，然后直接把 `agent-session` /
`agent-event` 信封发到手机的 UDP 17878，最后用 uiautomator dump 断言手机
「Agent」页显示成什么样。

**为什么要声明 ag1**：手机侧 `RelayUiState.kt:85`
`val isAgent get() = MsgTypes.CAP_AGENT in caps`、`:728`
`agentPeers = peers.filter { it.isAgent }`，而 `AgentConsoleScreen.kt:191-200`
在 `agents.isEmpty()` 时只画空状态就 `return@Column` —— 夹具不带 ag1 的话，
手机连会话卡片那一整块都不会渲染，本夹具全部 UI 断言都会以「什么都没有」
的形式失败，而根因和 Agent 协议毫无关系。agn1 则是 PPE-1 §4 的先决条件
（对端没声明 agn1 就不该发 agent-* 帧）。

为什么不走 agentNode 的 HTTP 控制面
-----------------------------------
`POST /v1/agent/session`、`POST /v1/agent/event`、`GET /v1/inbox` 都属于
JVM agentNode（`agentNode/src/main/kotlin/com/kapp/tidings/agent/ControlPlane.kt`），
要跑它们得先编 Kotlin、再起一个承载它的进程（历史上是 `agentNode` 的独立 CLI
入口，2026-10-14 起改由桌面端同进程承载）。夹具契约
（`docs/plans/2026-10-09-relay-e2e-fixture-program.md:61`）要的是
「`agent-session` 往返、事件流 cursor 单调」——这是 **wire 层**的事；
控制面那一层已有单测（`ControlPlaneTest.kt:224` 帧形状、`:263-295` 无 agn1 时
409、`:140-170` `/v1/inbox` 两个方向）。而且 409 降级文案与 `/v1/inbox` 的响应
都**不是手机可观测的**：夹具断言不了它们。所以本夹具只走 wire，不起任何 JVM，
对 bare phone 也不引入额外前置依赖。

用法
----
    python3 scripts/e2e/e2e_agent.py --dry-run                      # 只跑自检，不碰网络与设备
    python3 scripts/e2e/e2e_agent.py --phone 192.168.2.22 --host-ip 192.168.2.82
    python3 scripts/e2e/e2e_agent.py --skip-ui                      # 只跑 wire 段（adb 被别的夹具占着时）
    python3 scripts/e2e/e2e_agent.py --no-burst                     # 跳过 201 帧的 takeLast(200) 压测
    python3 scripts/e2e/e2e_agent.py --tcp-port 17880               # 17879 被 Teahouse.app 占着时

退出码 0=PASS / 1=FAIL / 2=用法或环境错误；结尾恰好一行 `result ...`。

覆盖与边界（对齐 coverage-matrix.md §2 H 段）
-------------------------------------------
覆盖：
- **R80**（agent-session 往返，会话卡）：announce A（单会话）→ 会话卡出现 alias/title/state；
  第二个会话 B ⇒ 摘要行换成最近更新的 B、A 的别名消失、▾ 出现；
  单会话时 ▾ 不画（源码 :427-428 明说那是死控件）；补宣告让 `announced` 翻回 true。
  往返的那一半由 ACK 证明（`session-a-acked` / `events-all-acked`：ACK 是按 `ackFor` 认的）。
- **R81**（事件流：cursor 升序 + 去重 + takeLast(200)）：
  乱序发 cursor 900002/900003/900001 ⇒ 界面必须按 900001→900002→900003 排；
  同 cursor 重发（换 envelope id）⇒ 条数不变且保留先到的那条（`mergeAgentEvent` 判重）；
  201 帧 ⇒ 「只显示最近 20 条（共 200 条）」，同一行钉死 MAX_SHOWN_EVENTS 与 takeLast(200)。
- **R82** 只覆盖「进程内持久化到 state 层」这一半（事件能跨会话切换后仍按 cursor 排好、
  重启前的一致性）。**跨进程重启后的恢复不覆盖**：夹具没有重启 app 再断言的能力
  （`restoreAgentData()` 那条路要 `am force-stop` + 重进 Agent 页，而这一步在
  三个夹具共用一台手机时无法串行保证；且矩阵 §8.1 C3 关于「事件是否真的落 SQLDelight」
  本身还是个未核实项，见 `SqlDelightMessageStore.kt:274/306`）。

不覆盖（诚实说明，不是遗漏）：
- **R83**（对端未声明 agn1 ⇒ 409 降级文案）：409 是 **agentNode 控制面**的响应
  （`ControlPlane.kt:474-512`），手机侧根本没有这一层，夹具断言不到；
  它已有单测 `ControlPlaneTest.kt:263-295`。夹具能做的最接近的事是**不带 agn1 握手**，
  但那样手机侧 `handleSendAgentSession/Event` 会在发送侧 return null，
  与「控制面 409」不是同一件事，硬凑会得到一个名字对不上事实的绿。
- **R84**（agent 队列/审批 UI）：夹具可以发 `agent-queue`/`agent-approval` 帧
  （手机侧 `RelayControllerMessages.kt:205-213` 有接收分支、`AgentApprovalCards.kt` 有 UI），
  但矩阵把 R84 归给 `agq1` 的 P1 能力，且批准/拒绝的**回路**（`respondApproval` →
  `sendAgentAck`）在夹具这侧只能观察到「收到一条 agent-ack」——那需要一个
  能点的审批卡 + 一轮回程，超出本夹具「R80–R82 为主」的范围；留给后续夹具。
- **R85**（agent-run / agent-cancel / agent-ack）：`agent-run`/`agent-cancel` 在
  `composeApp/src/commonMain` 里**没有任何接收分支**（`RelayControllerMessages.kt:195-216`
  只处理 session/event/approval/ack/queue），发过去会被 ACK 然后静默丢弃；
  断言「丢弃」只能断言「界面上没有」，那是零信息量的绿。
- **R86**（agent-artifact）：矩阵自己写着**无实现**，夹具无从下手。
- **R87**（Agent 控制台 UI 渲染）只在「有会话时」这一支上被本夹具间接覆盖；
  空状态/截图门禁不在这里。
- `GET /v1/inbox?since=<epochMillis>`：同上，属于 agentNode 控制面
  （`ControlPlane.kt:559-587`），单测在 `ControlPlaneTest.kt:140-170`。
"""

import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import relay_e2e as R  # noqa: E402


# ---------------------------------------------------------------------------
# 常量：每一个都带判据，不是随手写的数字
# ---------------------------------------------------------------------------

# 夹具对端声明的能力。逐项抄 `MessageTypes.kt` 的 AGENT_CAPS
# （ag1、tw1、agn1、agq1、aws1、agr1）。少一个 ag1 ⇒ 手机不把它当 agent 节点（见模块 docstring）；
# 少一个 agn1 ⇒ 手机侧 `handleSendAgentSession/Event` 的 `peerDeclares` 门会
# 直接 return null，但那只是**发送**侧的门，收帧侧不看 caps（见文末边界）。
# aws1（PPE-1 增补 D1/D2/D3）：没声明它的对端收不到 agent-session 的四个新键
# （`NodeOutbound.sendAgentSession` 的 cap 门控会把新键置 null），也收不到 agent-workspace 帧。
# agr1（增补 D7）：授权回路；宿主策略非 auto 时，未授权设备的 agent 帧在投递前就被拦下。
FIXTURE_CAPS = [R.CAP_AGENT, R.CAP_MOBILE_TEXT, R.CAP_AGENT_SESSION, R.CAP_AGENT_QUEUE,
              R.CAP_AGENT_WORKSPACE, R.CAP_AGENT_GRANT]

# 会话状态标签。夹具只发 busy/error 两档：`SessionStateLabel`（AgentConsoleScreen.kt:546-563）
# 只对这四档做映射，发一个协议没定义的状态（比如 "running"）会原样显示，
# 那样断言到的是「原样透传」而不是「映射对了」。
STATE_BUSY = "busy"
STATE_ERROR = "error"

# 一次最多画多少条事件 —— 抄 `AgentConsoleScreen.kt:565` 的 MAX_SHOWN_EVENTS。
# 夹具要用它算「只显示最近 N 条（共 M 条）」里的 N。
MAX_SHOWN_EVENTS = 20

# 事件上限 —— 抄 `RelayController.kt:737` 的 MAX_AGENT_EVENTS。
# 超过这个数，`mergeAgentEvent` 会 `sortedBy{cursor}.takeLast(max)` 丢掉旧的那条。
MAX_AGENT_EVENTS = 200

# 压测帧数 = 上限 + 1，这样「共 %d 条」一定在 200 与 201 之间二选一：
# 上限生效 ⇒ 共 200 条；上限没生效（或被改成 takeLast(∞)）⇒ 共 201 条。
BURST_EVENTS = MAX_AGENT_EVENTS + 1

# 单帧 ACK 等待。手机上 `handleReliable` 是先回 ACK 再入库，LAN 上往返是个位数毫秒；
# 2s 是「丢了」而不是「慢」的判据。
ACK_TIMEOUT_S = 2.0
ACK_TRIES = 3

# 会话游标基数：故意取大且互不连续，避免与「把 cursor 当时间戳用」的实现撞上
# （PPE-1 §6.2 明确 cursor 只在同一 (节点,会话) 内可比，不是时间戳）。
CURSOR_A1, CURSOR_A2 = 800001, 800002
CURSOR_B1, CURSOR_B2, CURSOR_B3 = 900001, 900002, 900003
CURSOR_U1 = 700001

# 手机默认 zh（`values/strings.xml`），`values-en/` 是英文字典。
# Android 对其它语言一律回落到 `values/` ⇒ 只有 en* 走英文，其余全走中文。
_LABELS_ZH = {
    "timeline": "事件流",
    "empty": "还没有 Agent 节点",
    "switch": "切换会话",
    "close": "关闭",
    "announced_missing": "只收到事件，没收到会话宣告",
    "busy": "进行中",
    "error": "出错",
    "cursor": "游标 %d",
    "count": "%d 条事件",
    "shown": "只显示最近 %d 条（共 %d 条）",
    "offline": "Agent 离线",
}
_LABELS_EN = {
    "timeline": "Event Stream",
    "empty": "No Agent Nodes",
    "switch": "Switch session",
    "close": "Close",
    "announced_missing": "Events received without session announcement",
    "busy": "Busy",
    "error": "Error",
    "cursor": "Cursor %d",
    "count": "%d events",
    "shown": "Showing latest %d events (of %d)",
    "offline": "Agent Offline",
}


# ---------------------------------------------------------------------------
# 小工具
# ---------------------------------------------------------------------------

def _budget(args, cap):
    """给某一阶段多少秒 —— 与 e2e_messaging.py 同一条口径。"""
    return max(1.0, min(float(cap), float(args.secs)))


def phase(title):
    print("\n== %s ==" % title, flush=True)


def _brief(texts, limit=240):
    """把可见文本压成一行，塞进 check 的 detail 里方便现场定位。"""
    joined = " | ".join(t for t in texts if t)
    return joined[:limit]


def _labels(args):
    """按手机语言挑一套界面文案。

    不假定中文：`values/` 是中文、`values-en/` 是英文，其它语言回落中文。
    读 `persist.sys.locale`，读不到再读 `ro.product.locale`。
    """
    locale = ""
    for prop in ("persist.sys.locale", "ro.product.locale"):
        try:
            locale = (R.adb("shell", "getprop", prop) or "").strip()
        except Exception:  # adb 本身不通时别在这里炸；后面 require_device 会给出正经错误
            locale = ""
        if locale:
            break
    R.log("phone locale=%r" % locale)
    return _LABELS_EN if locale.lower().startswith("en") else _LABELS_ZH


# ---------------------------------------------------------------------------
# 纯本地自检（--dry-run 与真跑都跑）
# ---------------------------------------------------------------------------

def _local_fixture_checks(args, label):
    """夹具自己的自检：不碰网络、不碰设备，纯粹验夹具发出去的东西是合法的。

    这些检查存在的理由很具体：R80/R81 的失败模式里有一半是**夹具自己**写错了
    （多一个键、cursor 写成字符串、caps 超限），而现场症状是手机毫无反应 ——
    和「手机有 bug」一模一样。宁可在这里红。
    """
    session = R.envelope("agent-session", {
        "sessionId": "s-local", "alias": "a", "agentKind": "codex",
        "state": STATE_BUSY, "title": "t", "startedAt": R.now_ms(),
    }, node_id=R.FIXTURE_NODE)
    event = R.envelope("agent-event", {
        "sessionId": "s-local", "cursor": CURSOR_B1, "eventType": "tool.started",
        "summary": "s",
    }, node_id=R.FIXTURE_NODE)

    R.check("fixture-agent-caps-declare-agn1", R.CAP_AGENT_SESSION in FIXTURE_CAPS,
            "caps=%s" % (FIXTURE_CAPS,))
    R.check("fixture-agent-caps-declare-ag1", R.CAP_AGENT in FIXTURE_CAPS,
            "缺 ag1 ⇒ 手机侧 agentPeers 为空、Agent 页只剩空状态")
    R.check("fixture-agent-caps-within-limit", len(FIXTURE_CAPS) <= R.LIMITS["caps"],
            "%d 项，上限 %d" % (len(FIXTURE_CAPS), R.LIMITS["caps"]))

    # hasOnlyKeys：Codec.validateAgentSession/Event 用的是白名单，多一个键 ⇒ **整帧**被拒
    # （`Codec.kt:360-402`）。夹具最容易手滑加上 ts/from 之类的时间戳字段。
    sess_keys = set(session["payload"])
    evt_keys = set(event["payload"])
    # D2 之后 agent-session 是"必需子集 + 忽略预算内未知键"，但**夹具仍然守白名单**：
    # 宽容是给线上未知对端的，发送端（夹具）多写一个键在测试里照样判红（父稿 §2.3 第 4 条）。
    R.check("fixture-session-payload-keys-whitelisted",
            sess_keys <= {"sessionId", "alias", "agentKind", "state", "title", "startedAt",
                          "agentId", "wsId", "wsTitle", "wsPath"},
            "payload 键=%s" % sorted(sess_keys))
    R.check("fixture-event-payload-keys-whitelisted",
            evt_keys <= {"sessionId", "cursor", "eventType", "summary", "body", "ref"},
            "payload 键=%s" % sorted(evt_keys))
    R.check("fixture-session-payload-has-no-null",
            all(v is not None for v in session["payload"].values()),
            "encodeDefaults=false 之下显式 null 与「不发这个键」在手机侧不是一回事："
            "null ⇒ JsonNull ⇒ stringField 判空串")
    R.check("fixture-event-cursor-is-json-number",
            isinstance(event["payload"]["cursor"], int)
            and not isinstance(event["payload"]["cursor"], bool),
            "Codec.intOf 对 JsonPrimitive.isString 返回 null（Codec.kt:122-126）⇒ "
            "cursor 写成 \"900001\" 会被判缺字段")

    # 帧大小：信封走 UDP，超 UDP_MAX_PAYLOAD 就得改走 TCP，而手机侧的 TCP 通道
    # 要等对端 TCP 服务在，夹具这边只保证 profile 里那个端口不假。
    sizes = []
    for env in (session, event):
        sizes.append(len(R.encode(env).encode("utf-8")))
    R.check("fixture-frames-fit-udp-payload", max(sizes) <= R.UDP_MAX_PAYLOAD,
            "最大 %d 字节，UDP 上限 %d" % (max(sizes), R.UDP_MAX_PAYLOAD))

    # 事件流必须真的**乱序发**，否则「按 cursor 升序」这条断言是白送的：
    # 顺序发 + 顺序显示，任何实现都能过。
    send_order = [CURSOR_B2, CURSOR_B3, CURSOR_B1]
    R.check("fixture-event-send-order-is-not-cursor-order",
            send_order != sorted(send_order),
            "发送顺序 %s ⇒ 界面上必须是 %s，否则顺序断言无意义"
            % (send_order, sorted(send_order)))

    # PPE-1 增补 D3 / D7 的形状自检（同样不碰网络）：这三帧的取值域是**封闭**的，
    # 夹具自己写错一个取值，手机侧就是整帧拒收 —— 现场症状又是"手机毫无反应"。
    ws_frame = R.envelope("agent-workspace", agent_workspace_payload(
        "ws-local", ws_title="本地工作区", ws_path="/tmp/local", agent_kind="DSH",
        online=True, session_count=0, at=R.now_ms(),
    ), node_id=R.FIXTURE_NODE)
    R.check("fixture-workspace-payload-keys-whitelisted",
            set(ws_frame["payload"]) <= {"wsId", "wsTitle", "wsPath", "agentId", "agentKind",
                                         "online", "sessionCount", "at"},
            "payload 键=%s" % sorted(ws_frame["payload"]))
    R.check("fixture-workspace-online-is-real-bool",
            isinstance(ws_frame["payload"]["online"], bool),
            "codec 拒字符串 \"true\"（JsonPrimitive.isString）⇒ 必须发 JSON 布尔字面量")
    grant_frame = R.envelope("agent-grant-request",
                             agent_grant_request_payload(agent_key="dsh-cli", reason="自检"),
                             node_id=R.FIXTURE_NODE)
    R.check("fixture-grant-request-keys-whitelisted",
            set(grant_frame["payload"]) <= {"agentKey", "reason", "at"},
            "payload 键=%s" % sorted(grant_frame["payload"]))
    R.check("fixture-agent-caps-declare-agr1", R.CAP_AGENT_GRANT in FIXTURE_CAPS,
            "没声明 agr1 的宿主不会把 agent 数据投给这台夹具（沉默 = 未授权）")

    burst = list(range(1, BURST_EVENTS + 1))
    R.check("fixture-burst-cursors-strictly-increasing",
            all(b > a for a, b in zip(burst, burst[1:])),
            "1..%d" % BURST_EVENTS)
    R.check("fixture-burst-exceeds-cap", BURST_EVENTS > MAX_AGENT_EVENTS,
            "%d > %d ⇒ 「共 %d 条」与「共 %d 条」可区分"
            % (BURST_EVENTS, MAX_AGENT_EVENTS, MAX_AGENT_EVENTS, BURST_EVENTS))
    R.check("fixture-label-table-covers-locale",
            set(_LABELS_ZH) == set(_LABELS_EN) and "shown" in label,
            "中英文字典键一致（%d 条）" % len(_LABELS_ZH))


def local_self_checks(args, nick, short_text, long_text, label):
    """本地自检总入口：共享库的 11 条 + 本夹具自己的若干条。

    共享库那份用 `caps=FIXTURE_CAPS`：它用同一份 caps 去构造 profile，
    于是「caps 是否超限」「platform 是否合法」这两条验的就是夹具真发出去的那个 profile。
    """
    R.local_self_checks(short_text, long_text, nick=nick,
                        tcp_port=args.tcp_port, host=args.host_ip, caps=FIXTURE_CAPS)
    _local_fixture_checks(args, label)


# ---------------------------------------------------------------------------
# 发送：reliable 帧 + 等 ACK + 可重发
# ---------------------------------------------------------------------------

def send_reliable(peer, mtype, payload, tries=ACK_TRIES, timeout=ACK_TIMEOUT_S):
    """发一帧 reliable 帧并等手机的 ACK。返回 (env, 尝试次数) 或 (None, tries)。

    为什么要等 ACK：UDP 没有重传。丢一帧的后果不是「少一条事件」这么轻 ——
    后面「共 200 条」那类断言会因为丢一帧而假红，而根因在网络上，不在被测代码里。

    重发为什么换 envelope id 而保持 payload（含 cursor）不变：
    - 换 id：手机侧 `Messenger.handleReliable` 按 `(id, from)` 去重（`dedupStore`），
      同 id 重发会被静默丢掉，等不到第二个 ACK。
    - 不换 cursor：手机侧 `mergeAgentEvent` 按 cursor 去重
      （`ChatInteractions.kt:211-221`），所以重发在**状态层是幂等的**，
      不会因为重试而多出一条事件。这两层去重正好让重试变安全。
    """
    for attempt in range(1, tries + 1):
        env = peer.env(mtype, payload)
        peer.send(env)
        if peer.wait_ack(env["id"], timeout) is not None:
            return env, attempt
    return None, tries


def agent_session_payload(session_id, alias=None, agent_kind=None, state=None,
                          title=None, started_at=None, agent_id=None,
                          ws_id=None, ws_title=None, ws_path=None):
    """§6.1 的 payload。字段名与 `Codec.agentSessionPayload` 逐字一致，
    可选字段为 None 时**不发这个键**（`encodeDefaults=false`）。

    尾部四参数是 PPE-1 增补 D1/D6 的四个可选键（cap `aws1`）：手机侧对没声明
    `aws1` 的发送方会把新键置 null（`NodeOutbound` 的 cap 门控），所以夹具 caps
    里带着 `aws1` —— 不带就没法验"手机真的按工作区分组"这一档。"""
    payload = {"sessionId": session_id}
    if alias is not None:
        payload["alias"] = alias
    if agent_kind is not None:
        payload["agentKind"] = agent_kind
    if state is not None:
        payload["state"] = state
    if title is not None:
        payload["title"] = title
    if started_at is not None:
        payload["startedAt"] = int(started_at)
    if agent_id is not None:
        payload["agentId"] = agent_id
    if ws_id is not None:
        payload["wsId"] = ws_id
    if ws_title is not None:
        payload["wsTitle"] = ws_title
    if ws_path is not None:
        payload["wsPath"] = ws_path
    return payload


def agent_workspace_payload(ws_id, ws_title=None, ws_path=None, agent_id=None, agent_kind=None,
                            online=None, session_count=None, at=None):
    """PPE-1 增补 D3 `agent-workspace` 的 payload（键序 = `Payloads.kt` 声明序）。

    `online` 必须是真布尔（codec 拒字符串）；`session_count` 是非负整数或 None。
    这帧在 RELIABLE 里（见 `MsgTypes.AGENT_WORKSPACE` 的声明处），所以夹具用
    `send_reliable` 发它、等手机 ACK 是合法的。"""
    payload = {"wsId": ws_id}
    if ws_title is not None:
        payload["wsTitle"] = ws_title
    if ws_path is not None:
        payload["wsPath"] = ws_path
    if agent_id is not None:
        payload["agentId"] = agent_id
    if agent_kind is not None:
        payload["agentKind"] = agent_kind
    if online is not None:
        payload["online"] = bool(online)
    if session_count is not None:
        payload["sessionCount"] = int(session_count)
    if at is not None:
        payload["at"] = int(at)
    return payload


def agent_grant_request_payload(agent_key=None, reason=None, at=None):
    """PPE-1 增补 D7 `agent-grant-request`（设备 → 宿主，cap `agr1`）。"""
    payload = {}
    if agent_key is not None:
        payload["agentKey"] = agent_key
    if reason is not None:
        payload["reason"] = reason
    if at is not None:
        payload["at"] = int(at)
    return payload


def agent_event_payload(session_id, cursor, event_type, summary=None, body=None, ref=None):
    """§6.2 的 payload。`cursor` 必须是 JSON 整数（Codec.intOf 拒字符串）。"""
    payload = {"sessionId": session_id, "cursor": int(cursor), "eventType": event_type}
    if summary is not None:
        payload["summary"] = summary
    if body is not None:
        payload["body"] = body
    if ref is not None:
        payload["ref"] = ref
    return payload


# ---------------------------------------------------------------------------
# UI 侧
# ---------------------------------------------------------------------------

def scroll_down(step=600):
    """往上划一屏（露出下面的内容）。

    这里出现的是**手势路径**坐标，不是点击目标 —— 和共享库的
    `chat_scroll_up`（relay_e2e.py:1160，硬编码 540 900→540 1660）同一种做法。
    本夹具里所有**点击**仍然一律走 uiautomator dump 的 bounds（`tap_node`），
    一个有坐标的 tap 都没有。
    """
    R.adb("shell", "input", "swipe", "540", "1600", "540", str(1600 - step), "260")


def scroll_to_top(times=3):
    for _ in range(times):
        R.adb("shell", "input", "swipe", "540", "1000", "540", "1700", "260")


def wait_text(substr, timeout=8.0, scrolls=0):
    """等某个子串出现；必要时往下划再找（Agent 页是 verticalScroll，内容可能被顶下去）。"""
    deadline = time.monotonic() + timeout
    for i in range(scrolls + 1):
        if R.ui_has(substr):
            return True
        if time.monotonic() >= deadline:
            return False
        if i < scrolls:
            scroll_down()
        time.sleep(0.6)
    return R.ui_has(substr)


def open_agent_tab(timeout=10.0, label=None):
    """把手机切到「Agent」页。

    底部页签的字面量就是 `screen_agents`（AppNavigation.kt:95-99）= "Agent"，
    和页面顶栏的 `agent_title` **同一个字面量**。所以只能靠 y 坐标区分：
    `bottom=True` 取的正是 y 最大的那个（relay_e2e.find_node 的 bottom 语义），
    这条技巧和共享库 `goto_messages_list` 找「消息」页签是同一招。

    页签在聊天详情页里是不存在的，所以先 back 几次退到底部导航。
    """
    timeline = (label or _LABELS_ZH)["timeline"]
    empty = (label or _LABELS_ZH)["empty"]
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        nodes = R.ui_all()
        if R.find_node(nodes, text=timeline, exact=False) or R.find_node(nodes, text=empty, exact=False):
            return True
        tab = R.find_node(nodes, text="Agent", bottom=True)
        if tab is not None and tab.center[1] > 1200:
            R.tap_node(tab)
            time.sleep(1.2)
            nodes = R.ui_all()
            if R.find_node(nodes, text=timeline, exact=False) or R.find_node(nodes, text=empty, exact=False):
                return True
        R.press_back()
        time.sleep(0.6)
    return False


def switch_node(label):
    """会话卡片右上角那个 ▾。它的 contentDescription 就是 `agent_session_switch_title`，
    只有 `sessions.size > 1` 时才画（AgentConsoleScreen.kt:429-438）。"""
    return R.find_ui(desc=label["switch"], timeout=2.0)


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

def run(args):
    mark = R.uniq_marker("AGENT")
    # 昵称每次现取：同一台手机上如果还留着上一轮夹具的 peer 记录，
    # 同名节点会让人分不清「这一轮的会话」和「上一轮的残影」。
    nick = args.nick or R.uniq_marker("e2eagent")

    session_a = "s-a-" + mark
    session_b = "s-b-" + mark
    session_u = "s-u-" + mark          # 只发事件、永不宣告（announced=false 那条路）
    session_d = "s-d-" + mark          # 压测 takeLast(200) 用
    alias_a = "refactor-" + mark
    alias_b = "reviewer-" + mark
    alias_u = "late-" + mark
    title_a = "重构执行器 %s" % mark
    title_b = "代码审查器 %s" % mark

    # 事件摘要里的序号与 cursor 的先后**故意错开**（EV2 的 cursor 最小）：
    # 这样「界面上的顺序」只能由 cursor 决定，不可能被发送顺序解释掉。
    sum_a1, sum_a2 = "EVA1-" + mark, "EVA2-" + mark
    sum_b1, sum_b2, sum_b3 = "EVB1-" + mark, "EVB2-" + mark, "EVB3-" + mark
    sum_dup = "EVBDUP-" + mark          # 与 sum_b2 同 cursor 的重发，界面**不该**出现它
    sum_u1 = "EVU1-" + mark
    burst_prefix = "EVZ-"

    label = _LABELS_ZH if args.dry_run else (_labels(args) if not args.skip_ui else _LABELS_ZH)

    # 共享库那份自检要一段「短文本」与一段「超 UDP 上限、但仍在 TCP 上限内」的长文本。
    # 短文本直接借事件摘要的形态（同一类 UTF-8 中文+ASCII 混合）。
    # 长文本用默认的 ASCII 头：`long_text(total, head)` 是 `head * (total-len(tail))` 的
    # **重复次数**，head 一给多字符（比如中文词）总长就翻倍 —— 实测传 head="长正文" 时
    # 1500 会造出 13412B，直接把 `long-text-within-tcp-limit` 顶红。
    # 所以这里 head 保持单字符，并让 total 就是字节数（§6.2 的 body 上限按 utf8ByteLength 算）。
    short_text = sum_b2
    long_text = R.long_text(R.TEXT_UDP_LIMIT + 700, tail_marker=mark)

    local_self_checks(args, nick, short_text, long_text, label)

    if args.dry_run:
        print("[dry-run] 不发任何帧、不碰 adb：以上自检 + 共享库的本地自检就是全部范围。", flush=True)
        print("[dry-run] 真跑会依次做：握手 → Agent 页 → 会话 A/B → 乱序事件 → cursor 去重 "
              "→ 未宣告会话 → 压测 %d 帧 → ▾ 选择器切换 → exit 离线。" % BURST_EVENTS, flush=True)
        return 0

    r = R

    # ---- 设备与前台 --------------------------------------------------------
    # --skip-ui 时不碰 adb：这台机器上三个夹具共用一台手机，uiautomator 是争用资源。
    if not args.skip_ui:
        phase("设备")
        r.require_device()
        # 先把 App 提到前台再问 pid：`app_pid()` 就是 `pidof com.kapp.tidings`，而安装/上一次
        # force-stop 之后它本来就该是空的。原顺序（先问 pid 再 ensure）会把「夹具自己
        # 刚装完 APK」记成一条 FAIL —— 2026-10-09 真机首跑就是这么假红了一条。
        r.ensure_app_foreground()
        pid = r.app_pid()
        r.check("phone-app-running", bool(pid),
                "把 App 提到前台后 pidof com.kapp.tidings = %r" % (pid,))

    peer = r.UdpPeer(args.phone_ip, args.phone_udp, nick=nick, caps=FIXTURE_CAPS,
                     tcp_port=args.tcp_port, host=args.host_ip)
    server = None
    try:
        tcp_note = "已占用 TCP :%d（手机真要往回发消息时有地方可落）" % args.tcp_port
        try:
            server = r.TcpFrameServer(port=args.tcp_port).start()
        except r.EnvError as exc:
            # TCP 不是本夹具的被测面（agent-* 帧全程走 UDP），而且这台机器上
            # Teahouse.app 常年占着 17879。绑不上就如实记一笔，别把整轮判成环境错误。
            server = None
            tcp_note = "TCP :%d 没绑上（%s）—— 本轮只走 UDP，手机主动发起的 TCP 收不到" % (
                args.tcp_port, exc)
        r.log(tcp_note)

        # ---- 握手 ----------------------------------------------------------
        phase("握手")
        peer.start()
        profile = peer.handshake(secs=_budget(args, 20))
        r.check("peer-handshake", profile is not None,
                "20s 内没握上手：先看 --host-ip 是不是本机 LAN IP，再看 ver 是否 ≤16 字符")
        if profile is None:
            return None
        r.log("phone profile: nick=%r caps=%s platform=%s tcpPort=%s"
              % (profile.get("nick"), profile.get("caps"), profile.get("platform"),
                 profile.get("tcpPort")))
        r.check("phone-declares-agn1", r.CAP_AGENT_SESSION in (profile.get("caps") or []),
                "caps=%s（PPE-1 §4：对端没声明 agn1 就不该发 agent-* 帧）"
                % (profile.get("caps"),))

        if args.skip_ui:
            return wire_only(peer, args, session_a, session_b, session_d, sum_b2, mark)

        # ---- Agent 页 ------------------------------------------------------
        phase("Agent 页")
        opened = open_agent_tab(label=label)
        r.check("agent-tab-opened", opened,
                "可见文本：%s" % _brief(r.ui_texts()))
        # 先滚回顶部：芯片行在页面**顶部**，而 `wait_text(scrolls=n)` 是向下找 ——
        # 上一轮跑会把页面停在很下面（会话卡很长），不先归位就永远找不到顶上的芯片。
        # 2026-10-09 三跑就是这里假红了一条 `agent-peer-listed`。
        scroll_to_top()
        listed = wait_text(nick, timeout=_budget(args, 20), scrolls=1)
        r.check("agent-peer-listed", listed,
                "夹具节点 %r 没出现在 Agent 页；可见文本：%s" % (nick, _brief(r.ui_texts())))

        # 必须**显式点自己那枚节点芯片**。Agent 页有多个节点时顶部是一排 FilterChip
        # （`AgentConsoleScreen.kt:198-226`），而「当前选中」由 `selectedAgentId`
        # （`App.kt:398-411` 的 remember，跨轮不重置）记住 —— 上一轮跑留下的节点可能仍是
        # 选中项，于是整页显示的是**别人**的会话与事件。2026-10-09 第二次真机跑就是这样
        # 假红了 15 条：本轮节点明明刚握上手（在线），屏幕上却是上一轮那个已离线节点的 4 个会话。
        chip = R.find_ui(text=nick, exact=True, timeout=_budget(args, 10))
        if chip is None:
            scroll_to_top()
            chip = R.find_ui(text=nick, exact=True, timeout=_budget(args, 10))
        if chip is not None:
            R.tap_node(chip)
            time.sleep(0.8)
        # 选中生效的可观测判据：离线横幅不在（夹具节点几秒前刚握手，选中它就不会画横幅）。
        r.check("agent-peer-selected-is-online", chip is not None
                and not any(label["offline"] in t for t in r.ui_texts()),
                "点自己那枚芯片（desc/text=%r，找到=%s）后不该还是「%s」态 —— 离线态说明"
                "选中的仍是上一轮留下的节点；可见文本：%s"
                % (nick, chip is not None, label["offline"], _brief(r.ui_texts(), 300)))

        # ---- 会话 A：单会话 --------------------------------------------------
        phase("会话 A（单会话）")
        env, tries = send_reliable(peer, "agent-session", agent_session_payload(
            session_a, alias=alias_a, agent_kind="codex", state=STATE_BUSY,
            title=title_a, started_at=r.now_ms()))
        r.check("session-a-acked", env is not None,
                "手机没 ACK agent-session（agent-session ∈ RELIABLE）：%d 次尝试" % tries)

        r.check("session-a-visible", wait_text(alias_a, timeout=_budget(args, 15), scrolls=1),
                "会话卡标题 = alias ?: sessionId（AgentConsoleScreen.kt:420）；"
                "可见文本：%s" % _brief(r.ui_texts()))
        r.check("session-a-state-label", r.ui_has(label["busy"]),
                "state=busy 应显示 %r（SessionStateLabel，:546-563）" % label["busy"])
        r.check("session-a-title-visible", r.ui_has(title_a),
                "§6.1 的 title 应原样显示；可见文本：%s" % _brief(r.ui_texts()))

        # 单会话时 ▾ 不该画 —— 源码注释（:427-428）明说「只有一个 session 时它是个死控件」。
        # 这一条只发过 A 一个会话，是验它最干净的时机。
        single_no_switch = switch_node(label) is None
        r.check("single-session-hides-switch", single_no_switch,
                "只有一个会话时 ▾（desc=%r）不应存在" % label["switch"])

        # A 的两条事件：cursor 升序发，先建立「会话 A 有 2 条」这个事实。
        ok = True
        for cur, summ, etype in ((CURSOR_A1, sum_a1, "session.started"),
                                 (CURSOR_A2, sum_a2, "tool.started")):
            e, t = send_reliable(peer, "agent-event", agent_event_payload(
                session_a, cur, etype, summary=summ))
            ok = ok and e is not None
        r.check("session-a-events-acked", ok, "两条事件都该被 ACK")
        r.check("session-a-events-counted", wait_text(label["count"] % 2,
                                                      timeout=_budget(args, 15), scrolls=1),
                "应有 %r；可见文本：%s" % (label["count"] % 2, _brief(r.ui_texts())))

        # ---- 会话 B：摘要行显示最近更新的那个 ---------------------------------
        phase("会话 B（两个会话，摘要行只画一个）")
        time.sleep(1.2)  # updatedAt 是 epoch ms，两条宣告拉开一点确保排序可判定
        env, tries = send_reliable(peer, "agent-session", agent_session_payload(
            session_b, alias=alias_b, agent_kind="claude", state=STATE_ERROR, title=title_b))
        r.check("session-b-acked", env is not None, "尝试 %d 次" % tries)

        r.check("session-b-summary-is-newest", wait_text(alias_b, timeout=_budget(args, 15), scrolls=1),
                "agentSessionsOf 按 updatedAt 降序（RelayUiState.kt:796-799）⇒ 摘要行该换成 B；"
                "可见文本：%s" % _brief(r.ui_texts()))
        scroll_to_top()
        texts = r.ui_texts()
        r.check("session-a-not-on-summary", alias_a not in texts,
                "只画一个会话（AgentConsoleScreen.kt:373-381）⇒ A 的别名不该同时出现；"
                "可见文本：%s" % _brief(texts))
        r.check("multi-session-shows-switch", switch_node(label) is not None,
                "两个会话时 ▾（desc=%r）该出现" % label["switch"])

        # ---- 事件：cursor 顺序 + 去重 ----------------------------------------
        phase("事件流：cursor 升序 + cursor 去重")
        acked = []
        for cur, summ, etype in ((CURSOR_B2, sum_b2, "tool.started"),
                                 (CURSOR_B3, sum_b3, "assistant.text"),
                                 (CURSOR_B1, sum_b1, "session.started")):
            e, t = send_reliable(peer, "agent-event", agent_event_payload(
                session_b, cur, etype, summary=summ))
            acked.append(e is not None)
        r.check("events-all-acked", all(acked),
                "三条都该被 ACK（含 cursor 最小的那条）；实际 %s" % acked)

        r.check("events-counted", wait_text(label["count"] % 3, timeout=_budget(args, 15), scrolls=1),
                "应有 %r；可见文本：%s" % (label["count"] % 3, _brief(r.ui_texts())))
        # 收集位置前必须把**最新**那条事件滚进可视区，而不是 `scroll_to_top()`。
        # 三跑就是这么假红的：`游标 900003` 在同一屏上找得到、`EVB3-…` 却不在 ——
        # `scroll_to_top()` 把事件卡顶出了屏幕底部，`uiautomator dump` 里第 3 行整行缺失，
        # 于是 idx["hi"] == -1、顺序断言无谓地红。三条事件行是相邻的，滚到最新那条
        # 可见时前两条也在屏内。
        newest_visible = wait_text(sum_b3, timeout=_budget(args, 15), scrolls=2)
        time.sleep(0.6)
        texts = r.ui_texts()
        if not newest_visible:
            r.check("events-newest-row-visible", False,
                    "最新一条事件（%r）怎么滚都进不了可视区；可见文本：%s"
                    % (sum_b3, _brief(texts, 300)))
        idx = {}
        for key, summ in (("lo", sum_b1), ("mid", sum_b2), ("hi", sum_b3)):
            idx[key] = texts.index(summ) if summ in texts else -1
        r.check("events-render-in-cursor-order",
                idx["lo"] >= 0 and idx["mid"] > idx["lo"] and idx["hi"] > idx["mid"],
                "发送顺序是 mid,hi,lo（cursor %d,%d,%d），界面必须按 cursor 排成 lo,mid,hi；"
                "实测位置 %s；可见文本：%s"
                % (CURSOR_B2, CURSOR_B3, CURSOR_B1, idx, _brief(texts)))
        r.check("event-cursors-labeled", r.ui_has(label["cursor"] % CURSOR_B3),
                "AgentEventRow 画 %r（AgentConsoleScreen.kt:531）" % (label["cursor"] % CURSOR_B3))

        # 重发同一个 cursor：换个 envelope id（否则被 dedupStore 按 (id,from) 丢掉），
        # 摘要换成 DUP。手机侧 mergeAgentEvent 应当判重并**保留先前那条**。
        env, tries = send_reliable(peer, "agent-event", agent_event_payload(
            session_b, CURSOR_B2, "tool.started", summary=sum_dup))
        r.check("duplicate-cursor-acked", env is not None, "尝试 %d 次" % tries)
        time.sleep(1.5)
        scroll_to_top()
        texts = r.ui_texts()
        r.check("duplicate-cursor-not-appended", label["count"] % 3 in texts,
                "同 cursor 重发不该变成 4 条；可见文本：%s" % _brief(texts))
        r.check("duplicate-cursor-keeps-first",
                sum_dup not in texts and sum_b2 in texts,
                "mergeAgentEvent 见同 cursor 直接 return null（ChatInteractions.kt:211-221）"
                "⇒ 保留先到的那条；可见文本：%s" % _brief(texts))

        # ---- 未宣告的会话：announced=false -----------------------------------
        phase("未宣告会话（announced=false）")
        env, tries = send_reliable(peer, "agent-event", agent_event_payload(
            session_u, CURSOR_U1, "tool.failed", summary=sum_u1))
        r.check("unannounced-event-acked", env is not None, "尝试 %d 次" % tries)
        r.check("unannounced-warning-shown",
                wait_text(label["announced_missing"], timeout=_budget(args, 15), scrolls=1),
                "会话是事件自动建出来的（RelayControllerAgent.kt:90 附近 announced=false）⇒ "
                "卡片该显示 %r；可见文本：%s"
                % (label["announced_missing"], _brief(r.ui_texts())))
        scroll_to_top()
        texts = r.ui_texts()
        r.check("unannounced-session-not-invented",
                session_u in texts and sum_u1 in texts,
                "没有 alias 时标题只能落到 sessionId（:420 的 `alias ?: sessionId`）——"
                "这是「如实显示 id」而不是「编了个名字」；可见文本：%s" % _brief(texts))

        # 补发一次真宣告：announced 必须翻回 true，红字必须消失。
        # 这一条只有这里能测：单测里 announced 是个构造参数，没有「同一条会话先事件后宣告」的路径。
        env, tries = send_reliable(peer, "agent-session", agent_session_payload(
            session_u, alias=alias_u, agent_kind="codex", state=STATE_BUSY, title="迟到的宣告"))
        r.check("unannounced-reannounce-acked", env is not None, "尝试 %d 次" % tries)
        time.sleep(1.5)
        scroll_to_top()
        texts = r.ui_texts()
        r.check("unannounced-warning-cleared",
                alias_u in texts and label["announced_missing"] not in texts,
                "补宣告后 alias 该顶上标题、红字该消失；可见文本：%s" % _brief(texts))

        # ---- 压测 takeLast(200) ---------------------------------------------
        if args.no_burst:
            R.log("--no-burst：跳过 %d 帧压测，R81 的 takeLast(%d) 本轮不覆盖"
                  % (BURST_EVENTS, MAX_AGENT_EVENTS))
        else:
            phase("压测：%d 帧事件 ⇒ takeLast(%d)" % (BURST_EVENTS, MAX_AGENT_EVENTS))
            e, t = send_reliable(peer, "agent-session", agent_session_payload(
                session_d, alias="burst-" + mark, agent_kind="codex", state=STATE_BUSY))
            r.check("burst-session-acked", e is not None, "尝试 %d 次" % t)
            # 压测前重新 entry 一次：手机侧对已知对端会走 registry.touch，
            # 判定离线后再来的 reliable 帧会被**静默丢弃**（Messenger.kt:96-149），
            # 症状就是「后面几十帧全没 ACK」。
            peer.send_entry(probe=True)
            time.sleep(1.2)

            sent, lost, streak = 0, 0, 0
            # 201 帧是**逐帧等 ACK**发出去的（每帧一次 UDP 往返，实测 ≈0.52s/帧），
            # 所以这一段的预算必须按「帧数 × 往返」给，不能拿 90s 去套 —— 2026-10-09
            # 真机首跑就是 90s 只发出 172/201，丢帧数其实是 0（纯 pacing，不是产品缺陷）。
            deadline = time.monotonic() + _budget(args, BURST_EVENTS * 1.2)
            for i in range(1, BURST_EVENTS + 1):
                if time.monotonic() >= deadline:
                    R.log("压测到预算上限，已发 %d/%d 帧" % (sent, BURST_EVENTS))
                    break
                e, t = send_reliable(peer, "agent-event", agent_event_payload(
                    session_d, i, "tool.started",
                    summary="%s%03d-%s" % (burst_prefix, i, mark)),
                    tries=2, timeout=1.5)
                if e is None:
                    lost += 1
                    streak += 1
                    if streak >= 5:
                        R.log("连续 5 帧没 ACK —— 中止压测，先把握手/离线这条线查清楚再回来")
                        break
                else:
                    streak = 0
                    sent += 1
            r.check("burst-frames-all-acked", lost == 0 and sent == BURST_EVENTS,
                    "发出 %d/%d，丢 %d（丢帧会让下面那条「共 N 条」假红）"
                    % (sent, BURST_EVENTS, lost))
            if lost == 0 and sent == BURST_EVENTS:
                # 「只显示最近 20 条（共 200 条）」这一行同时钉死了两件事：
                # MAX_SHOWN_EVENTS=20 的显示上限，和 takeLast(200) 的内存上限。
                # 上限若没生效，这里会显示「共 201 条」。
                want = label["shown"] % (MAX_SHOWN_EVENTS, MAX_AGENT_EVENTS)
                got = wait_text(want, timeout=_budget(args, 20), scrolls=3)
                r.check("burst-take-last-200-keeps-cap", got,
                        "应在 %d 条里只留 %d 条，即出现 %r；可见文本：%s"
                        % (BURST_EVENTS, MAX_AGENT_EVENTS, want, _brief(r.ui_texts(), 400)))

        # ---- 选择器：切换会话 ------------------------------------------------
        phase("▾ 选择器：切到会话 A")
        node = switch_node(label)
        r.check("switch-affordance-tappable", node is not None,
                "▾（desc=%r）在 %r；可见文本：%s"
                % (label["switch"], node, _brief(r.ui_texts(), 200)))
        if node is not None:
            r.tap_node(node)
            time.sleep(1.2)
            texts = r.ui_texts()
            r.check("picker-dialog-opened", label["close"] in texts,
                    "选择器标题与 ▾ 的 desc 是同一个字符串，所以只能靠「关闭」按钮认对话框；"
                    "可见文本：%s" % _brief(texts))
            # 对话框里 A 的别名只可能来自选择器那一行（摘要行此刻是 D/B）。
            opt = R.find_ui(text=alias_a, exact=True, timeout=4.0)
            r.check("picker-lists-session-a", opt is not None,
                    "选择器该列出全部会话；可见文本：%s" % _brief(r.ui_texts()))
            if opt is not None:
                r.tap_node(opt)
                time.sleep(1.2)
                scroll_to_top()
                texts = r.ui_texts()
                r.check("picker-switch-shows-a-session",
                        alias_a in texts and sum_a2 in texts,
                        "onPick ⇒ nav.selectAgentSession（resolveAgentSessionId 命中已选 id）；"
                        "可见文本：%s" % _brief(texts))
                r.check("picker-switch-hides-other-session",
                        sum_b2 not in texts,
                        "切走之后不该还画着 B 的事件；可见文本：%s" % _brief(texts))

        # ---- 收尾：exit ⇒ 手机在 ~2s 内判离线 --------------------------------
        phase("收尾：exit")
        peer.send_exit()
        offline = wait_text(label["offline"], timeout=_budget(args, 12), scrolls=0)
        r.check("peer-offline-after-exit", offline,
                "exit 是唯一能在 ~2s 内把对端翻成离线的可靠手段（UDP 的另一条是 90s 超时）；"
                "可见文本：%s" % _brief(r.ui_texts()))
        return None
    finally:
        try:
            if peer is not None:
                peer.stop()
        except Exception:
            pass
        try:
            if server is not None:
                server.stop()
        except Exception:
            pass


def wire_only(peer, args, session_a, session_b, session_d, sum_short, mark):
    """--skip-ui：不发 UI 断言，只验 wire 段（帧被 ACK、会话宣告被接受）。

    存在的理由：三个夹具共用一台手机时 adb 与 uiautomator 是争用的，
    而 wire 段（手机到底认不认这些帧）不依赖屏幕。它不能替代真跑 ——
    本夹具要覆盖的是「手机显示成什么样」。
    """
    phase("wire-only（--skip-ui）")
    ok = True
    for sid, alias in ((session_a, "refactor-" + mark), (session_b, "reviewer-" + mark),
                       (session_d, "burst-" + mark)):
        e, t = send_reliable(peer, "agent-session", agent_session_payload(
            sid, alias=alias, agent_kind="codex", state=STATE_BUSY))
        ok = ok and e is not None
    R.check("wire-agent-session-accepted", ok,
            "agent-session ∈ RELIABLE，手机收下就会 ACK（ACK 是 ackFor 认出来的，不是靠 type 猜）")
    e, t = send_reliable(peer, "agent-event", agent_event_payload(
        session_a, CURSOR_A1, "tool.started", summary=sum_short))
    R.check("wire-agent-event-accepted", e is not None, "尝试 %d 次" % t)

    # PPE-1 增补 D3：agent-workspace 也在 RELIABLE 里（见 MsgTypes 声明处 —— 首版按
    # 尽力而为实现，帧根本进不了手机 inbound），所以它同样该被 ACK。这条判红 =
    # 手机没接这帧（codec 拒收 / 手机端未升级），不是"尽力而为没送到"。
    e, t = send_reliable(peer, "agent-workspace", agent_workspace_payload(
        "ws-" + mark, ws_title="线级自检工作区", ws_path="/tmp/e2e-" + mark,
        agent_kind="DSH", online=True, session_count=0, at=R.now_ms()))
    R.check("wire-agent-workspace-acked", e is not None,
            "agent-workspace ∈ RELIABLE（增补 D3）；没 ACK = 手机侧 codec/接收分支没接上（尝试 %d 次）" % t)

    # PPE-1 增补 D7：设备侧申请帧。手机是**设备**时它不该收 grant-request（那是
    # 设备→宿主的反向帧，手机自己就是发起方）；但手机同时是"能看懂 agent-* 的节点"，
    # codec 会收下（agr1 已声明）。这条验的是"帧合法、被 ACK"，不是"手机执行授权" ——
    # 执行点在宿主（agentNode），由 ControlPlaneTest 与 verify 脚本守。
    e, t = send_reliable(peer, "agent-grant-list", {
        "scope": "all", "total": 0, "at": R.now_ms(),
    })
    R.check("wire-agent-grant-list-acked", e is not None,
            "agent-grant-list ∈ RELIABLE（增补 D7）；手机 caps 需含 agr1（尝试 %d 次）" % t)

    peer.send_exit()
    return None


def extra_args(ap):
    ap.add_argument("--nick", default="",
                    help="夹具节点昵称；空 = 每轮自动取 e2eagent<6位>（同一台手机上便于分辨残影）")
    ap.add_argument("--tcp-port", type=int, default=R.TCP_PORT_DEFAULT,
                    help="夹具自己的 TCP 端口（写进 profile 的 tcpPort）；"
                         "17879 被 Teahouse.app 占着时换一个（默认 %d）" % R.TCP_PORT_DEFAULT)
    ap.add_argument("--skip-ui", action="store_true",
                    help="只跑 wire 段（不发 uiautomator 断言）——adb 被别的夹具占着时的降级路径")
    ap.add_argument("--no-burst", action="store_true",
                    help="跳过 %d 帧压测（该段验 R81 的 takeLast(%d)）" % (BURST_EVENTS, MAX_AGENT_EVENTS))
    ap.set_defaults(secs=300)


if __name__ == "__main__":
    sys.exit(R.main_wrapper(run, description=__doc__, extra_args=extra_args))
