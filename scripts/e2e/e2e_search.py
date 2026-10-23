#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""检索夹具（真机）：会话内历史搜索（R100）+ 会话列表检索入口（R102）。

矩阵行来源：docs/plans/2026-10-09-relay-e2e-coverage-matrix.md:224-226
  R100 会话内历史搜索（关键词）→ 本夹具覆盖关键词这一路；
  R102 消息检索入口（会话列表 onSearchMessages）→ 本夹具覆盖入口 + 命中 + 无命中空态 + 清除；
  R101 全局检索（桌面壳 DesktopGlobalSearchPanel）→ **不覆盖**：那是宽屏壳专属路径，
       手机竖屏根本走不到（见文件末「不覆盖的东西」）。

夹具在做的事：夹具先扮演对端，把两条已知文本发进手机（这样才有东西可检索），
再驱动手机上的检索 UI，断言「结果集」。三件事按矩阵与任务要求依次断言：
  1. 只命中一条的查询 → 结果数恰好 1（用浮层自己画的计数与 footer 认，不靠"没看见别的"）；
  2. 无命中的查询 → 空态而不是陈旧结果（footer 必须消失）；
  3. 入口开/关 → 浮层返回键回到会话，而不是退出 app。

--------------------------------------------------------------------------
为什么本夹具不能直接用 relay_e2e 的 chat_* 助手（这是我这次踩到的真陷阱）
--------------------------------------------------------------------------
HistorySearchOverlay **不是 Dialog**：它是 ChatScreen 里聊天 Column 的**兄弟节点**
（ChatScreen.kt:359 的 Column 与 :567 的 `if (showHistorySearch) HistorySearchOverlay(...)`），
根是 `Surface(modifier.fillMaxSize())`（HistorySearchOverlay.kt:136）。于是浮层打开时，
聊天页那棵节点树**仍然在同一个窗口里、也仍然在 uiautomator dump 里**。后果两条：

  * `find_ui(cls="EditText")`（= relay_e2e.py:1049 chat_composer / :1054 chat_field_text /
    :1088 chat_type_verified）拿到的是 dump 里**第一个** EditText = **聊天输入栏**。
    照搬会把查询串打进聊天输入栏，再拿浮层的结果区去断言 —— 一个必然假失败或假通过的组合。
  * `find_ui(desc="返回")`（relay_e2e.py:501 find_node 返回 hits[0]）拿到的是**第一个**
    content-desc='返回' = **聊天页头的返回键**（ChatScreen.kt:627-631），不是浮层顶栏那个
    （HistorySearchOverlay.kt:149-154）。点了就直接离开会话 —— 正是"误触返回键"那一类事故。
    （**注意别写成 `find_ui("返回", desc=True)`**：位置参数是 `text`，那样写等于
    `text="返回", desc=True`，第二段判据退化成 `n.desc == True`，什么都找不到。
    本夹具 2026-10-09 的入口查找就是这个笔误。）

所以本文件自己按 **uiautomator bounds 的几何位置**选节点（没有一个目测坐标）：
搜索框 = 去掉底部输入栏后**唯一**的那个 EditText；返回键 = 打开浮层前后 dump 的 bounds 差分。
两个规则都"选不出就明确报错"，不猜。

--------------------------------------------------------------------------
已知的源码/文档不一致（本夹具据当前代码写，不据矩阵写）
--------------------------------------------------------------------------
  * 矩阵 R100（:224）把入口标成 `HistorySearchOverlay.kt:196/243/251/529` —— 那四行都在
    浮层**内部**（搜索框 placeholder / 「类型」/ FilterChip / 日期 sheet 标题），
    没有一行是"打开浮层的按钮"。真入口是 `ChatScreen.kt:745-749` 的 IconButton，
    contentDescription = res/strings.xml:725 `hist_search_title` =「查找聊天记录」。
  * 矩阵 R101（:225）引 `DesktopConversationsPane.kt:583`，那一行是 `emptyList()`
    （`remember(query, onSearchMessages)` 的 else 分支）；同文件 :564 在 query 非空且
    onSearchGlobal 非空时提前 return 到 `DesktopGlobalSearchPanel`，生产三处调用
    （:156/:180/:209）都传 `controller::searchGlobal` ⇒ 那一行**不可达**，
    全局检索真正可达的渲染点是 DesktopGlobalSearch.kt。
  * 矩阵 R102（:226）引 `ConversationsScreen.kt:245` = `placeholder = { Text(conv_search_hint) }`，
    正确；移动端接线在 App.kt:415-422（`onSearchMessages = controller::searchHistoricalMessages`）。
  * 「检索」当前**不是**客户端过滤已加载消息，而是真 SQL：≥3 字符走 FTS5（trigram）、
    <3 字符走 LIKE 降级，见 SqlDelightMessageStore.kt:113-193。会话列表那一屏的**行**过滤
    才是内存过滤（ConversationsScreen.kt:159-173 `conversations.filter { title/preview contains }`）。

--------------------------------------------------------------------------
不覆盖的东西（刻意不写"顺手就过"的检查）
--------------------------------------------------------------------------
  * R101 桌面全局检索：需要宽屏壳（DesktopShell）+ DesktopGlobalSearchPanel，
    手机竖屏不可达；且该面板在 docs/plans/2026-10-08-relay-adaptive-layout-audit.md:441 §8.4
    有两条已记录的布局缺陷（左栏「清空筛选」被压成 15.2dp 空框等）。夹具不碰这个面。
  * R100 里的「类型」/「日期」两个过滤器：矩阵那一格写的是「关键词 / 类型 / 日期」，
    但 FilterChip 与日期 sheet 的选中态在 uiautomator 里没有稳定的可断言锚
    （chip 的选中与否不改变 text/desc），所以只覆盖**关键词**这一路，其余如实报"未覆盖"。
  * 群会话里的检索：代码上同一条路径（convId 由 App.kt:242 的 `nav.activeSessionId` 给，
    群会话 id 形如 `group:<gid>`，见 ConversationsScreen.kt:473），但本夹具只建 1:1 会话。
  * 系统返回键在浮层打开时的行为：代码上是**离开会话**（浮层没有自己的 BackHandler，
    走 App.kt:200 的 `BackKeyHandler(enabled = chatDetailOnTop) { leaveChatDetail() }`）。
    这是可验证的预测，但不在矩阵三行里，所以只做成 --probe-system-back 的**观察**（不打 check）。

用法（真机）：
  python3 scripts/e2e/e2e_search.py --dry-run
  python3 scripts/e2e/e2e_search.py --phone 192.168.2.22 --host-ip 192.168.2.82 --secs 300
"""

from __future__ import annotations

import argparse
import os
import re
import sys
import time
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import relay_e2e as R  # noqa: E402


# ---------------------------------------------------------------------------
# 0. 与 app 侧的字符串/定位器一一对应（全部出自
#    composeApp/src/commonMain/composeResources/values/strings.xml）
# ---------------------------------------------------------------------------
BACK_DESC = "返回"                        # strings.xml:9   action_back（聊天页头与浮层顶栏同名！）
ENTRY_DESC = "查找聊天记录"                # strings.xml:725 hist_search_title（ChatScreen.kt:745-749）
HIST_SECTION = "结果"                     # strings.xml:748 hist_search_results_section（恒画）
HIST_COUNT_LAST = "条"                    # strings.xml:747 hist_search_results_count = "%1$d 条"
HIST_EMPTY = "未找到相关记录"              # strings.xml:735 hist_search_empty
HIST_EMPTY_HINT = "换个关键词试试"          # strings.xml:736
HIST_FOOTER_PREFIX = "已列出这"            # strings.xml:750 hist_search_footer_hint（**只在非空分支渲染**）
HIST_DATE_ALL = "全部日期"                 # strings.xml:733 hist_filter_date_all（浮层独有锚）
CONV_SECTION = "聊天记录"                  # strings.xml:602 conv_search_messages_section
CONV_NOMATCH_FMT = "没有匹配「%s」的会话"    # strings.xml:53  conv_no_match
CONV_CLEAR_SEARCH = "清除搜索"             # strings.xml:30  action_clear_search（空态的出口按钮）

# hist_search_results_count("%1$d 条") 落地后的节点文本形如 "1 条" / "0 条"。
COUNT_RE = re.compile(r"^(\d+)\s*条$")

# 与 relay_e2e.py `adb_input_text` 同一份白名单（那是模块私有名，不跨模块依赖私有符号）：
# 注入走**逐键 keyevent**，覆盖 `[A-Za-z0-9._@%+=:/-]`；中文会 NPE，其余 ASCII 未实测。
ADB_TEXT_RE = re.compile(r"^[A-Za-z0-9._@%+=:/-]+$")


def phase(title):
    print("\n== %s ==" % title, flush=True)


def _budget(args, cap):
    return max(1.0, min(float(cap), float(args.secs)))


# ---------------------------------------------------------------------------
# 1. 节点选择：一律从 uiautomator dump 的 bounds 算，不猜坐标
# ---------------------------------------------------------------------------
def _screen_wh(nodes):
    """屏幕尺寸代理：dump 里所有节点 bounds 的最大右下角。"""
    xs = [n.bounds[2] for n in nodes if n.bounds]
    ys = [n.bounds[3] for n in nodes if n.bounds]
    return (max(xs) if xs else 0), (max(ys) if ys else 0)


def _edittexts(nodes):
    """dump 里所有 class 含 EditText 的节点，按 y 从上到下。"""
    out = [n for n in nodes if n.center and "edittext" in n.cls.lower()]
    return sorted(out, key=lambda n: n.center[1])


def _fmt_nodes(nodes):
    return " | ".join("%s@%s" % (n.cls.split(".")[-1], n.center) for n in nodes) or "无"


def _text_inputs(nodes):
    """把 dump 里的 EditText 分成 (检索框, 聊天输入栏)，分不清就各自返回 None。

    浮层打开时 dump 里有**两个** EditText：浮层搜索框（上）与聊天输入栏（下）。
    照 `R.find_ui(cls="EditText")`（relay_e2e.py:1049/1054/1088，取 dump 里第一个）
    会把查询串打进聊天输入栏，再拿浮层的结果区去断言 —— 必然假失败或假通过。

    这里的分法**不猜坐标**，只做相对定位：
      * 输入栏 = 最靠下的那个 EditText，且它必须落在屏幕下半部（否则不能叫输入栏）；
      * 检索框 = 去掉输入栏后**恰好剩下一个** EditText —— 剩下 0 个或 ≥2 个都返回 None。
    选不出来时宁可让夹具明确报错（"--dry-run 通过、真机上一查就知道哪里不对"），也不赌。
    """
    fields = _edittexts(nodes)
    _, h = _screen_wh(nodes)
    composer = fields[-1] if (fields and h and fields[-1].center[1] > h * 0.5) else None
    rest = [n for n in fields if n is not composer]
    search = rest[0] if len(rest) == 1 else None
    if search is None:
        R.log("检索框定位失败：EditText %d 个（%s）、屏幕 h=%s ⇒ 候选 %d 个"
              % (len(fields), _fmt_nodes(fields), h, len(rest)))
    return search, composer


def _search_field(nodes):
    return _text_inputs(nodes)[0]


def _composer_field(nodes):
    return _text_inputs(nodes)[1]


def _has(nodes, needle):
    return any(needle in (n.text or "") for n in nodes)


def _has_body(nodes, needle):
    """与 `_has` 相同，但**跳过 EditText**：查询串本身就活在检索框里，
    拿 `_has(nodes, query)` 去证明"结果里有这条"是自证 —— 空结果集也会为真。
    凡是要证明"屏幕上出现了这条消息内容"的地方都必须走这个函数。"""
    return any(needle in (n.text or "") for n in nodes if "edittext" not in n.cls.lower())


def _screen_brief(nodes, limit=12):
    """屏幕上的可见文本（失败时贴进 detail：真机远程排障时这是唯一能看到的东西）。"""
    out = [(n.text or "") for n in nodes if (n.text or "").strip()]
    return " / ".join(out[:limit]) or "（无文本节点）"


def _result_count(nodes):
    """浮层结果区的 `N 条` 计数（HistorySearchOverlay.kt:337）。找不到返回 None。

    跳过 EditText：检索框里的查询串自己也可能长得像 `N 条`，那会变成自证。
    """
    for n in nodes:
        if "edittext" in n.cls.lower():
            continue
        m = COUNT_RE.match((n.text or "").strip())
        if m:
            return int(m.group(1))
    return None


def _overlay_open(nodes):
    """浮层打开的判据：三个**浮层独有**锚同时在场。

    「结果」标题恒画（HistorySearchOverlay.kt:331-341），「全部日期」是日期行的 chip，
    `N 条` 是计数 —— 三个都只可能来自浮层，聊天页与其它页签都不会画它们。
    """
    return _has(nodes, HIST_SECTION) and _has(nodes, HIST_DATE_ALL) and _result_count(nodes) is not None


def _wait_ui(pred, timeout, interval=0.6):
    """轮询 dump 直到 pred 成立。返回 (是否成立, 最后一次的 nodes)。

    必须轮询而不是 sleep 一拍：浮层的检索在 `LaunchedEffect(query, kind, from, to)` 里跑
    （HistorySearchOverlay.kt:131-134），改 query 之后**有一帧仍然显示旧结果**。
    固定 sleep 的夹具会间歇性地拿陈旧结果当真值。
    """
    deadline = time.monotonic() + max(0.0, timeout)
    nodes = R.ui_all()
    ok = pred(nodes)
    while not ok and time.monotonic() < deadline:
        time.sleep(interval)
        nodes = R.ui_all()
        ok = pred(nodes)
    return ok, nodes


def _type_query(text, tries=6):
    """把查询串注入检索框（`_search_field`），回读校验；返回 (落框文本, 聊天输入栏文本)。

    仿 relay_e2e.py:1088 chat_type_verified 的回读重打（`input text` 会随机吞字，
    relay_e2e.py:1069-1073 有真机实测数据），但**不**用它的 find_ui(cls="EditText")：
    那个会命中聊天输入栏（见文件头说明）。
    """
    for attempt in range(1, tries + 1):
        nodes = R.ui_all()
        field = _search_field(nodes)
        if not field:
            return None, None
        R.tap_node(field)
        time.sleep(0.5)
        nodes = R.ui_all()
        field = _search_field(nodes)
        if not field:
            return None, None
        cur = field.text or ""
        if cur:
            R.chat_clear(len(cur) + 4)      # relay_e2e.py:1060：MOVE_END + DEL×n，一次 shell
            time.sleep(0.3)
        R.adb_input_text(text)
        time.sleep(0.6)
        nodes = R.ui_all()
        field = _search_field(nodes)
        got = field.text if field else None
        composer = _composer_field(nodes)
        ctext = (composer.text or "") if composer else None
        if got == text:
            return got, ctext
        R.log("查询串没落进搜索框（想打 %r，框里 %r，第 %d/%d 次）—— 清空重打"
              % (text, got, attempt, tries))
    return None, None


def _back_nodes(nodes):
    return [n for n in nodes if (n.desc or "") == BACK_DESC]


def _pick_overlay_back(before_bounds):
    """挑浮层顶栏那个「返回」，靠"打开浮层前后 bounds 的重数差分"认人。

    dump 里有两个 content-desc='返回'：聊天页头（ChatScreen.kt:627-631）与浮层顶栏
    （HistorySearchOverlay.kt:149-154）。`R.find_node(desc=...)` 只给第一个（聊天页头），
    点了就离开会话。浮层是聊天 Column 的**后出现的兄弟**（ChatScreen.kt:359 → :567），
    所以"重数变多的那个 bounds"就是浮层的；要是刚好和聊天页头同 bounds（两者都在内容区
    第一行，理论上可能），则取 dump 顺序里后出现的那个 —— 注意 dump 顺序 = 组合顺序，
    这是**基于"浮层在聊天树之后发射"的推断**，不是真机实测过的。所以选不出来时返回 None，
    绝不在两个候选之间蒙一个。
    """
    nodes = R.ui_all()
    backs = _back_nodes(nodes)
    before = Counter(before_bounds)
    seen = Counter()
    fresh = []
    for n in backs:
        seen[n.bounds] += 1
        if seen[n.bounds] > before.get(n.bounds, 0):
            fresh.append(n)
    if len(fresh) == 1:
        R.log("浮层返回键 = 差分里的唯一新增 bounds=%s center=%s" % (fresh[0].bounds, fresh[0].center))
        return fresh[0]
    R.log("浮层返回键差分不唯一：打开前 %s；现在 %s —— 不蒙坐标"
          % (list(before_bounds), [n.bounds for n in backs]))
    return None


def _tap_overlay_back(before_bounds, early_node):
    node = _pick_overlay_back(before_bounds)
    src = "现场差分"
    if not node and early_node is not None and early_node.center:
        node, src = early_node, "打开浮层时记录的 bounds"
    if not node:
        return False, "两个「返回」节点无法按 bounds 差分区分，拒绝盲点"
    R.log("点浮层返回键：bounds=%s center=%s（来源：%s）" % (node.bounds, node.center, src))
    R.tap_node(node)
    return True, src


# ---------------------------------------------------------------------------
# 2. 本地自检（--dry-run 也跑；不碰设备、不碰网络）
# ---------------------------------------------------------------------------
def _local_checks(target, noise, nomatch, env, long_text):
    R.check("dry-target-marker-not-degraded-to-short-query", len(target) >= 3,
            "%d 字符 ⇒ 不走「长度 < 3 降级 LIKE」那条分支（SqlDelightMessageStore.kt "
            "的 search/searchInConversation/searchGrouped；真机上还有第二条判据："
            "Android 框架 SQLite 没有 fts5 ⇒ !fts5 时同样走 LIKE，见 FtsTolerantSchema.kt）"
            % len(target))
    R.check("dry-target-text-fits-inbound-udp-text-limit",
            R.utf8_len(target) <= R.TEXT_UDP_LIMIT,
            "%dB <= %dB（手机侧 UDP 入站文本上限）" % (R.utf8_len(target), R.TEXT_UDP_LIMIT))
    R.check("dry-planned-envelopes-fit-udp-payload",
            max(len(R.encode(env)), len(R.encode(R.envelope(
                R.T_MSG, {"kind": R.K_TEXT, "text": noise})))) <= R.UDP_MAX_PAYLOAD,
            "短文本信封都 <= %dB ⇒ 走 UDP:17878，本夹具不需要 TCP 服务端" % R.UDP_MAX_PAYLOAD)
    R.check("dry-noise-marker-not-inside-target",
            noise not in target and target not in noise and noise != target,
            "target=%r noise=%r（互不为子串，噪点消息才不会污染那条唯一命中）" % (target, noise))
    R.check("dry-nomatch-marker-never-sent",
            all(nomatch not in t and t not in nomatch for t in (target, noise)),
            "nomatch=%r 从未发给手机 ⇒ 检索它必须 0 条" % nomatch)
    R.check("dry-queries-typable-by-adb-input-text",
            all(ADB_TEXT_RE.match(q) for q in (target, noise, nomatch)),
            "三个查询串都在 `input text` 可靠注入的字符集内（relay_e2e.py:558-575）")
    R.check("dry-long-text-exceeds-udp-text-limit", R.utf8_len(long_text) > R.TEXT_UDP_LIMIT,
            "%dB > %dB（复用共享库的长文本自检参数）" % (R.utf8_len(long_text), R.TEXT_UDP_LIMIT))


# ---------------------------------------------------------------------------
# 3. 网络阶段：握手 + 种两条已知文本（夹具扮演对端）
# ---------------------------------------------------------------------------
def _send_text(peer, text, check_name, timeout):
    env = peer.env(R.T_MSG, {"kind": R.K_TEXT, "text": text})
    peer.send(env)
    ack = peer.wait_ack(env["id"], timeout)
    R.check(check_name, bool(ack),
            "text=%r id=%s ACK=%s（先等 ACK 再驱动 UI：手机没入队就去检索是假阴性）"
            % (text, env["id"][:8], "有" if ack else "无"))
    return bool(ack)


def _handshake_and_seed(args, peer, target, noise):
    phase("握手 + 种消息（先有东西可检索，再谈检索）")
    prof = peer.handshake(secs=_budget(args, 20), probe=True)
    caps = list((prof or {}).get("caps") or [])
    R.check("handshake-probe-alive", bool(prof),
            "phone profile=%s（probe entry：固定 1s 回 alive，绕过 10s 去重）"
            % (("nodeId=%s platform=%s ver=%s" % (
                str(prof.get("nodeId"))[:8], prof.get("platform"), prof.get("ver")))
               if prof else "无 —— 15s 内没握上手"))
    if not prof:
        # 握不上手 = 设备/网络环境问题，不是"检索功能坏了"：如实早退，不把后续检查刷成 FAIL。
        R.log("没有握手，后面全部跳过（不记 check）：请先确认 --phone 可达、app 在前台、且没有别的进程占用 UDP %d"
              % R.UDP_PORT)
        return False
    R.check("phone-declares-mobile-text-cap", R.CAP_MOBILE_TEXT in caps,
            "caps=%s 含 %s" % (",".join(caps) or "无", R.CAP_MOBILE_TEXT))
    ack_win = _budget(args, 8)
    _send_text(peer, target, "target-text-acked-over-udp", ack_win)
    _send_text(peer, noise, "noise-text-acked-over-udp", ack_win)
    return True


# ---------------------------------------------------------------------------
# 4. R100：会话内历史搜索
# ---------------------------------------------------------------------------
def _open_overlay(nick, args, target=None, noise=None):
    """进会话 →（先确认消息真在会话里）→ 点「查找聊天记录」→ 等浮层打开。
    返回 (ok, before_back_bounds, early_back_node)。

    **气泡必须在打开浮层之前查**：检索浮层是盖在会话之上的兄弟 Column
    （`ChatScreen.kt:359` 的 Column 与 `:567`），打开之后聊天消息就不在 dump 里了。
    2026-10-09 之前 `chat-shows-seeded-target-bubble` 被放在浮层打开之后，
    于是三次真机跑都恒红 —— 屏幕文本里只有浮层自己的「搜索当前会话 / 类型 / 结果 / 0 条」。
    """
    if not R.open_chat(nick, timeout=_budget(args, 10)):
        return False, [], None
    if target is not None:
        # 有界轮询：会话刚打开时 LazyColumn 可能还没把气泡画出来（ACK 与落库都已各自断言）
        got, nodes = _wait_ui(lambda ns: _has_body(ns, target), _budget(args, 8))
        if not got:
            R.chat_scroll_up()
            got, nodes = _wait_ui(lambda ns: _has_body(ns, target), _budget(args, 5))
        R.check("chat-shows-seeded-target-bubble", got,
                "打开浮层之前，会话里看得见 %r（也看得见噪点 %r=%s）；屏幕文本=%s"
                % (target, noise, _has_body(nodes, noise), _screen_brief(nodes)))
    time.sleep(0.6)
    before_bounds = [n.bounds for n in _back_nodes(R.ui_all())]
    # `find_ui(text=None, desc=None, …)` 的第一个位置参数是 **text**！
    # 原写法 `R.find_ui(ENTRY_DESC, desc=True, …)` 实际传的是 `text=ENTRY_DESC,
    # desc=True` —— 于是第二段判据变成 `n.desc == True`（永远假），而入口在 app 里是
    # `contentDescription`（ChatScreen.kt:745-752 的 Icon(...)）⇒ 恒找不到。
    # 真机 2026-10-09 全量 run1 的三条 search 失败（入口 → 浮层 → 后面两条级联）就是它。
    entry = R.find_ui(desc=ENTRY_DESC, timeout=_budget(args, 4))
    if not entry:
        # 兜底：将来若把它画成文字（TextButton）也还能找到
        entry = R.find_ui(text=ENTRY_DESC, timeout=1.0)
    if not entry:
        R.log("会话页找不到 content-desc=%r 的入口；当前可见文本：%s"
              % (ENTRY_DESC, " | ".join(R.ui_texts()[:24])))
        return False, before_bounds, None
    R.log("点检索入口 %r bounds=%s center=%s" % (ENTRY_DESC, entry.bounds, entry.center))
    R.tap_node(entry)
    ok, nodes = _wait_ui(_overlay_open, _budget(args, 6))
    R.log("浮层打开=%s；EditText 清单：%s" % (ok, _fmt_nodes(_edittexts(nodes))))
    if not ok:
        return False, before_bounds, None
    early = _pick_overlay_back(before_bounds)
    return True, before_bounds, early


def _history_search_phase(args, nick, target, noise, nomatch):
    phase("R100 会话内历史搜索：入口 → 单条命中 → 无命中空态 → 返回键回会话")
    # 气泡那一条由 _open_overlay 在**打开浮层之前**记录（浮层会盖住会话）
    ok, before_bounds, early_back = _open_overlay(nick, args, target=target, noise=noise)
    R.check("overlay-entry-opens-history-search", ok,
            "content-desc=%r（ChatScreen.kt:745-749）→ 浮层独有锚（%r + %r + N 条）"
            % (ENTRY_DESC, HIST_SECTION, HIST_DATE_ALL))
    if not ok:
        return

    got, ctext = _type_query(target)
    R.check("overlay-query-lands-in-search-field-not-composer",
            got == target and ctext != target,
            "搜索框落框=%r（=查询串）；聊天输入栏=%r（必须不是查询串，否则就是把查询打进了输入栏）"
            % (got, ctext))

    ok_hit, nodes = _wait_ui(
        lambda ns: _result_count(ns) == 1 and _has(ns, HIST_FOOTER_PREFIX), _budget(args, 10))
    saw_hits = ok_hit
    R.check("overlay-single-hit-count-and-footer", ok_hit,
            "查询 %r → 计数 %s 条、footer %r（N 条与 footer 都是浮层自己画的，"
            "不靠「没看见别的消息」来推断唯一性）"
            % (target, _result_count(nodes), HIST_FOOTER_PREFIX))
    R.log("命中行里带 FTS 高亮的 snippet 形式（【%s】）=%s —— 只是证据，不作断言（高亮切分方式由 fts5 snippet 决定）"
          % (target, _has(nodes, "【" + target + "】")))

    got2, _ = _type_query(nomatch)
    ok_empty, nodes2 = _wait_ui(
        lambda ns: (_result_count(ns) == 0 and _has(ns, HIST_EMPTY)
                    and not _has(ns, HIST_FOOTER_PREFIX)), _budget(args, 10))
    R.check("overlay-nomatch-empty-state-not-stale",
            ok_empty and got2 == nomatch and saw_hits,
            "查询 %r（从未发送）→ 计数 %s 条、%r=%s、footer 消失=%s；且上一步确实拿到过命中=%s；屏幕文本=%s"
            % (nomatch, _result_count(nodes2), HIST_EMPTY, _has(nodes2, HIST_EMPTY),
               not _has(nodes2, HIST_FOOTER_PREFIX), saw_hits, _screen_brief(nodes2)))

    tapped, src = _tap_overlay_back(before_bounds, early_back)
    time.sleep(1.2)
    nodes3 = R.ui_all()
    act = R.foreground_activity()
    back_in_chat = (not _overlay_open(nodes3)) and _composer_field(nodes3) is not None
    R.check("overlay-back-returns-to-chat",
            tapped and back_in_chat and (act or "").startswith(R.PKG),
            "浮层返回键(%s) → 浮层锚点消失=%s、会话底部输入栏在=%s、前台=%s（不是退出 app）"
            % (src, not _overlay_open(nodes3), _composer_field(nodes3) is not None, act))


# ---------------------------------------------------------------------------
# 5. R102：会话列表检索入口
# ---------------------------------------------------------------------------
def _convlist_phase(args, nick, target, noise, nomatch):
    phase("R102 会话列表检索入口 → 聊天记录命中 → 无命中空态 → 清除搜索")
    listed = R.goto_messages_list(max_back=5)
    time.sleep(0.8)
    nodes = R.ui_all()
    fields = _edittexts(nodes)
    R.check("convlist-search-field-is-the-only-input",
            listed and len(fields) == 1 and _search_field(nodes) is not None
            and _composer_field(nodes) is None,
            "回到会话列表=%s；EditText %d 个（%s）—— 列表页只有一个检索框、没有底部输入栏"
            % (listed, len(fields), _fmt_nodes(fields)))

    got, _ = _type_query(target)
    ok_hit, nodes = _wait_ui(
        lambda ns: _has(ns, CONV_SECTION) and _has_body(ns, target), _budget(args, 6))
    R.check("convlist-query-hits-the-message",
            ok_hit and got == target and not _has_body(nodes, noise),
            "查询 %r → 落框=%r、%r 段在=%s、命中文本在=%s；噪点 %r 不在=%s"
            "（同一屏的行过滤只比对 title/preview=最后一条=%r，所以 %r 只可能来自检索命中）；屏幕文本=%s"
            % (target, got, CONV_SECTION, _has(nodes, CONV_SECTION), _has_body(nodes, target),
               noise, not _has_body(nodes, noise), noise, target, _screen_brief(nodes)))

    got2, _ = _type_query(nomatch)
    expect = CONV_NOMATCH_FMT % (got2 or nomatch)
    ok_empty, nodes2 = _wait_ui(
        lambda ns: _has(ns, expect) and not _has_body(ns, target) and not _has(ns, CONV_SECTION),
        _budget(args, 6))
    R.check("convlist-nomatch-empty-state",
            ok_empty and got2 == nomatch,
            "查询 %r → 空态 %r 在=%s、%r 与命中段都不在（target=%s section=%s）；屏幕文本=%s"
            % (nomatch, expect, _has(nodes2, expect), target,
               _has_body(nodes2, target), _has(nodes2, CONV_SECTION), _screen_brief(nodes2)))

    cleared_via = "空态出口按钮 %r" % CONV_CLEAR_SEARCH
    tapped = R.tap_text(CONV_CLEAR_SEARCH, timeout=_budget(args, 4))
    ok_back, nodes3 = _wait_ui(lambda ns: _has_body(ns, nick) and not _has(ns, nomatch),
                               _budget(args, 4))
    if not (tapped and ok_back):
        # ① 按钮多半被输入法压住（点了但不生效）——先**确认**键盘在，再收键盘重试一次。
        if R.ime_shown():
            R.press_back()
            time.sleep(0.6)
            if R.tap_text(CONV_CLEAR_SEARCH, timeout=_budget(args, 3)):
                ok_back, nodes3 = _wait_ui(lambda ns: _has_body(ns, nick) and not _has(ns, nomatch),
                                           _budget(args, 4))
                if ok_back:
                    cleared_via = "确认键盘在 → 收键盘后重点 %r" % CONV_CLEAR_SEARCH
        # ② 真的点不动就退回键盘清理（`chat_clear` 走 MOVE_END + 分块 DEL）。
        if not ok_back:
            field = _search_field(R.ui_all())
            if field:
                R.tap_node(field)
                time.sleep(0.4)
                R.chat_clear((len(nomatch) or 0) + 8)
                time.sleep(0.8)
                cleared_via = "键盘清理（%r 按钮没生效）" % CONV_CLEAR_SEARCH
            ok_back, nodes3 = _wait_ui(lambda ns: _has_body(ns, nick) and not _has(ns, nomatch),
                                       _budget(args, 4))
        # ③ 查询已经清掉、行却不在 dump 里：先把列表页要回来，再滚着找那一行。
        #    会话列表是 LazyColumn，屏幕外的行**根本不组合**，`ui_all()` 里没有；
        #    但**不因此判过**：滚完还是没有，就是真的没有。
        if not ok_back:
            R.goto_messages_list(max_back=2)
            time.sleep(0.6)
            if R.scroll_to_text(nick, max_swipes=4, timeout=2.0):
                ok_back, nodes3 = _wait_ui(
                    lambda ns: _has_body(ns, nick) and not _has(ns, nomatch), 2.0)
                if ok_back:
                    cleared_via += "＋回列表页滚到该行"
    R.check("convlist-clear-search-restores-list",
            ok_back,
            "清空查询（%s）→ 会话行 %r 回来=%s、查询串残留=%s；屏幕文本=%s"
            % (cleared_via, nick, _has_body(nodes3, nick), _has(nodes3, nomatch),
               _screen_brief(nodes3)))


# ---------------------------------------------------------------------------
# 6. 可选探针：浮层打开时按系统返回键（只观察，不打 check）
# ---------------------------------------------------------------------------
def _probe_system_back(args, nick):
    phase("探针（--probe-system-back）：浮层打开时按系统返回键 —— 只记录观察，不作断言")
    ok, _before, _early = _open_overlay(nick, args)
    if not ok:
        R.log("探针跳过：浮层没打开")
        return
    R.press_back()
    time.sleep(1.6)
    nodes = R.ui_all()
    R.log("系统返回键观察：浮层关闭=%s、仍在会话（底部输入栏在）=%s、前台=%s"
          % (not _overlay_open(nodes), _composer_field(nodes) is not None, R.foreground_activity()))
    R.log("代码预测：浮层没有自己的 BackHandler（feature/chat 下 BackHandler 零命中），"
          "按系统返回键走 App.kt:200 的 BackKeyHandler(enabled = chatDetailOnTop) ⇒ 离开会话。"
          "若观察结果是「仍在会话=False」，说明「back 回到会话」这句只对浮层自己的返回键成立。")


# ---------------------------------------------------------------------------
# 7. main
# ---------------------------------------------------------------------------
def run(args):
    nick = "E2E" + R.uniq_marker("", 4)
    target = R.uniq_marker("hs", 6)      # 唯一命中
    noise = R.uniq_marker("nz", 6)       # 同会话里的第二条（不该被 target 查到）
    nomatch = R.uniq_marker("zz", 8)     # 从未发送 ⇒ 必须 0 条

    phase("本地自检（不碰设备、不碰网络）")
    long_text = R.long_text(1400)
    short_env, _ = R.local_self_checks(target, long_text, nick=nick,
                                      tcp_port=args.tcp_port, host=args.host_ip)
    _local_checks(target, noise, nomatch, short_env, long_text)
    R.log("本轮身份/查询串：nick=%r target=%r noise=%r nomatch=%r" % (nick, target, noise, nomatch))

    if args.dry_run:
        print()
        print("[EVID] --dry-run：只跑本地自检，未碰设备、未碰网络，因此不打印 result 行")
        print("[EVID] 真机跑法：python3 scripts/e2e/e2e_search.py --phone 192.168.2.22 "
              "--host-ip 192.168.2.82 --secs 300")
        return 0

    R.require_device()
    pid = R.app_pid()
    if not pid:
        R.log("com.kapp.tidings 没在跑，先拉起来")
        R.ensure_app_foreground()
    R.log("device=%s pid=%s" % (R.resolve_serial(), pid or "-"))

    # 前置：检索只查 DB，而落盘默认是关的（RelaySettings.kt:95 persistMessages=false，
    # 关着时 RelayControllerMessages.kt:364-365 直接 return）。全新安装/清过数据的机器
    # 上，"发了消息却检索不到"是开关语义的结果，不是检索坏了 —— 所以夹具必须自己把
    # 开关打开并**断言**读回，再谈检索。失败就如实早退，不把后面刷成级联 FAIL。
    phase("前置：打开设置里的「消息落盘」（检索只查 DB）")
    persisted, detail = R.ensure_messages_persisted(timeout=_budget(args, 15))
    R.check("precondition-messages-persisted", persisted,
            "设置 → 数据与存储 → 消息落盘：%s" % detail)
    if not persisted:
        R.log("落盘开关没打开，后面的检索全都会是 0 条（那不是检索的 bug）：已跳过剩余检查")
        return None

    peer = R.UdpPeer(args.phone_ip, args.phone_udp, nick=nick,
                     tcp_port=args.tcp_port, host=args.host_ip)
    try:
        if not _handshake_and_seed(args, peer, target, noise):
            return None
        if args.skip_ui:
            R.log("--skip-ui：只跑握手 + 种消息（网络段），驱动检索 UI 的部分跳过")
            return None
        _history_search_phase(args, nick, target, noise, nomatch)
        _convlist_phase(args, nick, target, noise, nomatch)
        if args.probe_system_back:
            _probe_system_back(args, nick)
        return None
    finally:
        try:
            peer.send_exit()      # exit 是让手机把对端翻成「离线」的唯一可靠路径（payload {}）
            R.log("已发 exit，对端应回到离线")
        except Exception as e:    # 清理失败不该盖掉真正的断言结果
            R.log("发 exit 失败：%s" % e)
        peer.stop()


def extra_args(ap):
    ap.add_argument("--tcp-port", type=int, default=R.TCP_PORT_DEFAULT,
                    help="写进夹具 profile.tcpPort（默认 %(default)s）。短文本走 UDP，"
                         "本夹具不绑 TCP 服务端 —— 这个值只进 profile 与本地自检")
    ap.add_argument("--skip-ui", action="store_true",
                    help="只做握手 + 把两条文本种进手机，不驱动检索 UI")
    ap.add_argument("--probe-system-back", action="store_true",
                    help="额外观察：浮层打开时按系统返回键会发生什么（只记录，不打 check）")
    ap.set_defaults(secs=300)


if __name__ == "__main__":
    sys.exit(R.main_wrapper(run, description=__doc__, extra_args=extra_args))
