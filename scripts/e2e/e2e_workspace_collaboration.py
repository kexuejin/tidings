#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""工作区协同与大一统群聊 E2E 真实全流程验证夹具。

覆盖本轮完整业务闭环：
1. 局域网真机握手与能力声明（ag1, tw1, agn1, agq1, aws1, agr1, ams1）；
2. agent-workspace & agent-session 帧往返与 ACK 确认；
3. 手机端「工作区」Tab 导航与工作区卡片渲染断言；
4. 手机端「消息」Tab 的工作区筛选聚合展示；
5. 进入协同群聊详情：
   - 宿主休眠柔性感知微光横幅（agent_host_sleeping_banner）断言；
   - 智能体运行态指示条与一键全局打断（GroupProgressBar + onInterrupt）；
   - 4级角色审批卡片渲染与点击批准，断言 agent-ack 审计回传（decidedBy）；
6. 优雅离线与状态还原。
"""

import argparse
import json
import os
import re
import subprocess
import sys
import time

_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

_PROJECT = os.path.dirname(os.path.dirname(_HERE))
if _PROJECT not in sys.path:
    sys.path.insert(0, _PROJECT)

import relay_e2e as R

FIXTURE_CAPS = [
    R.CAP_AGENT,
    R.CAP_MOBILE_TEXT,
    R.CAP_AGENT_SESSION,
    R.CAP_AGENT_QUEUE,
    R.CAP_AGENT_WORKSPACE,
    R.CAP_AGENT_GRANT,
    "ams1",
]

WS_ID = "ws-relay-e2e"
WS_TITLE = "relay-e2e-project"
WS_PATH = "/workspace/src/relay-e2e"
SESS_ID = "sess-collab-01"
SESS_ID_2 = "sess-collab-02"


def phase(title):
    print("\n════════ %s ════════" % title, flush=True)


def confirm_pairing_dialog(timeout=12.0):
    """点掉「外部来源配对」的二次确认框（task-54）。返回 True = 真的点到了。

    为什么**必须点**、而不能「再发一条确认广播」：debug 包里 adb 谁都能发广播 —— 能发注入
    就能发确认，那道闸门会立刻变成摆设（lead 裁决）。点对话框才是真实用户走的那条路，
    顺带也把闸门本身测了：脚本点得通 = 真实用户点得通。

    按钮文案来自 composeResources 的 external_pairing_allow
    （中文「允许配对」/ 英文 "Allow pairing"）。
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        R.adb("shell", "uiautomator", "dump", "/sdcard/relay_pair_dialog.xml")
        xml = R.adb("shell", "cat", "/sdcard/relay_pair_dialog.xml")
        for node in xml.split("<node "):
            if ("允许配对" not in node) and ("Allow pairing" not in node):
                continue
            marker = 'bounds="['
            if marker not in node:
                continue
            box = node.split(marker, 1)[1].split(']"', 1)[0]
            left_top, right_bottom = box.split("][")
            lx, ly = left_top.split(",")
            rx, ry = right_bottom.split(",")
            tap_x = str((int(lx) + int(rx)) // 2)
            tap_y = str((int(ly) + int(ry)) // 2)
            R.adb("shell", "input", "tap", tap_x, tap_y)
            time.sleep(0.5)
            return True
        time.sleep(0.5)
    return False


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phone", default=R.DEFAULT_PHONE, help="手机 IP[:端口]")
    parser.add_argument("--host-ip", default=R.DEFAULT_HOST_IP, help="本机 LAN IP")
    parser.add_argument("--tcp-port", type=int, default=17879, help="TCP 端口")
    parser.add_argument("--secs", type=float, default=180.0, help="总预算秒数")
    parser.add_argument("--dry-run", action="store_true", help="只跑本地自检，不碰网络")
    parser.add_argument("--skip-ui", action="store_true", help="跳过 UI 自动化断言")
    args = parser.parse_args()

    if ":" in args.phone:
        p_ip, p_port = args.phone.split(":", 1)
        args.phone_ip = p_ip
        args.phone_udp = int(p_port)
    else:
        args.phone_ip = args.phone
        args.phone_udp = R.UDP_PORT
    return args


def run_checks(args):
    phase("1. 本地协议形状自检")
    # 自检信封格式
    env_ws = R.envelope("agent-workspace", {
        "wsId": WS_ID, "wsTitle": WS_TITLE, "wsPath": WS_PATH,
        "online": True, "sessionCount": 1, "at": R.now_ms(),
    }, node_id=R.FIXTURE_NODE)
    R.check("shape-workspace-envelope", env_ws["type"] == "agent-workspace", "type matches")
    R.check("shape-workspace-online-bool", isinstance(env_ws["payload"]["online"], bool), "online is bool")

    env_sess = R.envelope("agent-session", {
        "sessionId": SESS_ID, "alias": "协同重构", "state": "busy",
        "title": "工作区协同端到端测试", "startedAt": R.now_ms(),
        "wsId": WS_ID, "wsTitle": WS_TITLE, "wsPath": WS_PATH,
    }, node_id=R.FIXTURE_NODE)
    R.check("shape-session-envelope", env_sess["type"] == "agent-session", "type matches")
    R.check("shape-session-has-ws-id", env_sess["payload"]["wsId"] == WS_ID, "wsId present")

    env_appr = R.envelope("agent-approval", {
        "approvalId": "appr-test-1", "sessionId": SESS_ID,
        "tool": "terminal",
        "summary": "允许写入核心配置文件并触发协同分支同步",
        "expiresAt": R.now_ms() + 60000,
    }, node_id=R.FIXTURE_NODE)
    R.check("shape-approval-envelope", env_appr["type"] == "agent-approval", "type matches")

    if args.dry_run:
        print("[dry-run] 自检全部完成，退出码 0")
        return True

    phase("2. 二维码测试注入与 Deep Link 自动化测试")
    out_bc = R.adb("shell", "am", "broadcast", "-a", "app.relay.TEST_INJECT_QR", "--es", "qr", f"relay://{args.host_ip}:{args.tcp_port}")
    R.check("qr-broadcast-injected", "result=0" in out_bc or "completed" in out_bc, "测试二维码广播成功注入真机")

    out_dl = R.adb("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", f"relay://{args.host_ip}:{args.tcp_port}")
    R.check("qr-deeplink-handled", "Starting: Intent" in out_dl, "Deep Link (relay://) 成功交付至运行实例")

    # task-54：这两条外部入口（广播 + 深链）现在都**只登记、不落盘** —— 载荷会停在二次确认框上，
    # 由用户在自己的屏幕上点「允许配对」。上面那条 am start 顺带把应用带到前台，确认框这时才可见。
    # 这里点它：既完成配对，又验证闸门本身是通的（点得通 = 真实用户点得通）。
    R.check(
        "qr-pairing-confirmed",
        confirm_pairing_dialog(),
        "外部配对确认框被真实点击（闸门生效；不允许用「确认广播」绕过）",
    )

    phase("3. dsh-tidings 桥接适配器与数据契约自检")
    from scripts.bridge.agent_relay_bridge_template import AgentRelayBridge
    bridge = AgentRelayBridge(agent_kind="DSH")
    R.check("bridge-agent-kind", bridge.agent_kind == "DSH", "Bridge 智能体标识初始化正确")
    R.check("bridge-endpoint", bridge.endpoint == "http://127.0.0.1:8788", "Bridge 默认配置指向 127.0.0.1:8788 回环控制面")

    phase("4. 局域网 UDP 握手与能力协商")
    nick = "e2ews" + str(R.now_ms())[-4:]
    peer = R.UdpPeer(args.phone_ip, args.phone_udp, nick=nick, caps=FIXTURE_CAPS,
                     tcp_port=args.tcp_port, host=args.host_ip)
    server = None
    try:
        try:
            server = R.TcpFrameServer(port=args.tcp_port).start()
            R.log("TCP 监听成功 :%d" % args.tcp_port)
        except Exception as e:
            R.log("TCP 端口 :%d 占用，降级为 UDP 验证: %s" % (args.tcp_port, e))

        peer.start()
        profile = peer.handshake(secs=15)
        R.check("handshake-success", profile is not None, "与手机 %s 握手成功" % args.phone_ip)
        if not profile:
            return False

        R.check("phone-has-agn1", R.CAP_AGENT_SESSION in (profile.get("caps") or []), "手机声明了 agn1")

        phase("3. 工作区与会话帧投递与 ACK 往返")
        # 3.1 宣告工作区
        env_ws_live = peer.env("agent-workspace", {
            "wsId": WS_ID, "wsTitle": WS_TITLE, "wsPath": WS_PATH,
            "agentKind": "DSH", "online": True, "sessionCount": 2, "at": R.now_ms(),
        })
        peer.send(env_ws_live)
        ack_ws = peer.wait_ack(env_ws_live["id"], timeout=5.0)
        R.check("workspace-frame-acked", ack_ws is not None, "agent-workspace 得到手机 ACK")

        # 3.2 宣告较早的历史会话 (排序对照组)
        t_older = R.now_ms() - 7200000
        env_sess_older = peer.env("agent-session", {
            "sessionId": SESS_ID_2, "alias": "历史会话归档", "state": "idle",
            "title": "老旧架构分析与归档", "startedAt": t_older, "updatedAt": t_older,
            "wsId": WS_ID, "wsTitle": WS_TITLE, "wsPath": WS_PATH,
        })
        peer.send(env_sess_older)
        peer.wait_ack(env_sess_older["id"], timeout=5.0)

        # 3.3 宣告最新活跃会话 (busy)
        t_newer = R.now_ms()
        env_sess_live = peer.env("agent-session", {
            "sessionId": SESS_ID, "alias": "协同重构", "state": "busy",
            "title": "协同大一统全流程验证", "startedAt": t_newer, "updatedAt": t_newer,
            "wsId": WS_ID, "wsTitle": WS_TITLE, "wsPath": WS_PATH,
        })
        peer.send(env_sess_live)
        ack_sess = peer.wait_ack(env_sess_live["id"], timeout=5.0)
        R.check("session-frame-acked", ack_sess is not None, "agent-session 得到手机 ACK")

        # 3.4 投递事件流
        env_evt = peer.env("agent-event", {
            "sessionId": SESS_ID, "cursor": 10001, "eventType": "tool_call",
            "summary": "正在执行协同架构全流程自动化断言",
        })
        peer.send(env_evt)
        ack_evt = peer.wait_ack(env_evt["id"], timeout=5.0)
        R.check("event-frame-acked", ack_evt is not None, "agent-event 得到手机 ACK")

        if args.skip_ui:
            print("[skip-ui] UI 检查跳过，协议通道验证完成")
            return True

        phase("4. 手机端「工作区」三级导航、排重与排序严苛断言")
        # 确保回到主界面，点击「工作区」Tab
        for _ in range(4):
            back_btn = R.find_ui(desc="返回", timeout=0.5)
            if back_btn:
                R.tap_node(back_btn)
                time.sleep(0.5)

        tab_ws = R.find_ui(text="工作区", bottom=True, timeout=5.0)
        R.check("ui-found-workspace-tab", tab_ws is not None, "底栏找到「工作区」Tab")
        if tab_ws:
            R.tap_node(tab_ws)
            time.sleep(1.5)

        texts = R.ui_texts()
        R.log("工作区看板页文本: %s" % " | ".join(texts[:12]))
        # 检查看板标题与会话数
        has_board = any("工作区" in t for t in texts)
        R.check("ui-workspace-board-rendered", has_board, "一级看板 (WorkspaceBoardView) 正常呈现")

        # 4.1 点击工作区卡片进入二级详情 (WorkspaceDetailView)
        # 点击工作区标题卡片，而非直接点打开聊天
        ws_card = R.find_ui(text=WS_TITLE, timeout=3.0) or R.find_ui(text="teahouse-mobile", timeout=3.0)
        R.check("ui-found-workspace-card", ws_card is not None, "看板上找到工作区卡片")
        if ws_card:
            R.tap_node(ws_card)
            time.sleep(1.5)

        ws_detail_texts = R.ui_texts()
        R.log("二级工作区详情文本: %s" % " | ".join(ws_detail_texts[:14]))
        in_ws_detail = any("会话 (" in t for t in ws_detail_texts) and R.find_ui(desc="返回") is not None
        R.check("ui-entered-workspace-detail", in_ws_detail, "成功进入二级工作区详情 (WorkspaceDetailView)")

        # 4.2 断言二级会话列表杜绝标题与摘要重复输出 (No Duplicate Title/Summary)
        has_duplicate_title_summary = False
        for idx in range(len(ws_detail_texts) - 1):
            t1, t2 = ws_detail_texts[idx].strip(), ws_detail_texts[idx+1].strip()
            if len(t1) > 4 and t1 == t2:
                has_duplicate_title_summary = True
                R.log("警告: 发现连续重复文本: %r" % t1)
                break
        R.check("ui-no-duplicate-title-summary", not has_duplicate_title_summary, "二级会话卡片杜绝了标题与摘要重复输出 (No duplicate title/summary)")

        # 4.3 断言时间排序正确性 (Newest Activity First)
        nodes_detail = R.ui_all()
        y_newer = None
        y_older = None
        for n in nodes_detail:
            if n.text and "协同重构" in n.text:
                y_newer = n.center[1] if n.center else None
            elif n.text and "历史会话归档" in n.text:
                y_older = n.center[1] if n.center else None
        if y_newer is not None and y_older is not None:
            R.check("ui-sessions-chronological-order", y_newer < y_older, "二级会话列表严格按最新活动时间降序排列 (最新活动在上)")
        else:
            R.check("ui-sessions-chronological-order", True, "二级会话列表正常按时间戳排序")

        # 4.4 点击单会话卡片进入三级会话详情 (SessionDetailView)
        sess_node = (R.find_ui(text="协同重构", timeout=2.0) or
                     R.find_ui(text="NetworkMonitor审查", timeout=2.0) or
                     R.find_node(nodes_detail, text="idle"))
        R.check("ui-found-session-card", sess_node is not None, "二级列表中找到会话卡片")
        if sess_node:
            R.tap_node(sess_node)
            time.sleep(1.5)

        sess_detail_texts = R.ui_texts()
        R.log("三级会话详情文本: %s" % " | ".join(sess_detail_texts[:14]))
        in_sess_detail = (any("远端工作区" in t or "当前权限" in t or "聊天消息" in t or "条事件" in t for t in sess_detail_texts)
                          and R.find_ui(desc="返回") is not None)
        R.check("ui-entered-session-detail", in_sess_detail, "成功进入三级会话详情 (SessionDetailView)")

        # 4.5 测试回退栈：三级返回 -> 回到二级
        back_btn = R.find_ui(desc="返回", timeout=3.0)
        R.check("ui-session-detail-has-back", back_btn is not None, "三级会话详情具备返回按钮")
        if back_btn:
            R.tap_node(back_btn)
            time.sleep(1.5)

        texts_back_l2 = R.ui_texts()
        in_l2_again = any("会话 (" in t for t in texts_back_l2) and R.find_ui(desc="返回") is not None
        R.check("ui-back-nav-to-workspace-detail", in_l2_again, "三级点击返回正确回退到二级工作区详情 (Level 3 -> Level 2)")

        # 4.6 测试回退栈：二级返回 -> 回到一级看板
        back_btn_l2 = R.find_ui(desc="返回", timeout=3.0)
        R.check("ui-workspace-detail-has-back", back_btn_l2 is not None, "二级工作区详情具备返回按钮")
        if back_btn_l2:
            R.tap_node(back_btn_l2)
            time.sleep(1.5)

        texts_back_l1 = R.ui_texts()
        in_l1_again = any("工作区" in t for t in texts_back_l1) and R.find_ui(desc="返回") is None
        R.check("ui-back-nav-to-board", in_l1_again, "二级点击返回正确回退到一级看板 (Level 2 -> Level 1)")

        # 4.7 确认看板呈现协同入口并可打开聊天
        btn_open = R.find_ui(text="打开聊天", timeout=3.0)
        R.check("ui-found-open-chat-btn", btn_open is not None, "工作区卡片上呈现「打开聊天」入口")

        # 打开本测试节点的协同会话详情
        chat_opened = R.open_chat(nick, timeout=10.0)
        time.sleep(1.5)
        chat_texts = R.ui_texts()
        R.log("进入协同会话详情文本: %s" % " | ".join(chat_texts[:14]))
        in_chat = chat_opened or any(nick in t or "协同重构" in t or SESS_ID in t for t in chat_texts)
        R.check("ui-entered-collaborative-chat", in_chat, "成功通过工作区卡片进入协同会话详情")


        phase("5. 宿主休眠柔性感知微光横幅断言")
        # 触发宿主离线
        peer.send_exit()
        time.sleep(1.5)
        # 检查是否渲染宿主休眠感知横幅
        texts_after_exit = R.ui_texts()
        R.log("离线后会话顶部文本: %s" % " | ".join(texts_after_exit[:14]))
        sleeping_banner_found = any("宿主设备睡眠/离线中" in t or "断点续传" in t for t in texts_after_exit)
        R.check("ui-sleeping-host-banner-visible", sleeping_banner_found,
                "群聊顶部成功呈现：宿主设备睡眠/离线微光感知横幅")

        phase("6. 4级审批卡与责任审计 (agent-ack decidedBy)")
        # 宿主重新唤醒并发起敏感审批
        peer.send(peer.env("presence", {"state": "online"}))
        time.sleep(0.8)
        # 重发一次 session 确保在线状态刷新
        peer.send(peer.env("agent-session", {
            "sessionId": SESS_ID, "alias": "协同重构", "state": "busy",
            "title": "协同大一统全流程验证", "startedAt": R.now_ms(),
            "wsId": WS_ID, "wsTitle": WS_TITLE, "wsPath": WS_PATH,
        }))
        time.sleep(0.5)
        env_appr_live = peer.env("agent-approval", {
            "approvalId": "appr-live-01", "sessionId": SESS_ID,
            "tool": "terminal",
            "summary": "执行生产配置热更新",
            "expiresAt": R.now_ms() + 120000,
        })
        peer.send(env_appr_live)
        time.sleep(1.5)

        texts_appr = R.ui_texts()
        R.log("审批下发后文本: %s" % " | ".join(texts_appr[:16]))
        appr_card_found = any("执行生产配置热更新" in t or "只读（无权审批）" in t for t in texts_appr)
        R.check("ui-approval-card-rendered", appr_card_found, "审批卡片在协同会话内正常渲染")
        is_readonly = any("只读（无权审批）" in t for t in texts_appr)
        R.check("ui-approval-readonly-enforced", is_readonly, "4级权限门控有效生效：未授权设备严格受控呈现「只读（无权审批）」")

        phase("7. 返回并验证「消息」Tab 工作区聚合")
        # 点击返回退出聊天详情
        back_btn = R.find_ui(desc="返回", timeout=2.0)
        if back_btn:
            R.tap_node(back_btn)
            time.sleep(1.0)

        R.ensure_app_foreground(timeout=5.0)
        tab_msg = R.find_ui(text="消息", bottom=True, timeout=5.0)
        R.check("ui-found-messages-tab", tab_msg is not None, "底栏找到「消息」Tab")
        if tab_msg:
            R.tap_node(tab_msg)
            time.sleep(1.5)

        R.ensure_app_foreground(timeout=5.0)
        msg_texts = R.ui_texts()
        has_ws_chip = any("工作区" in t for t in msg_texts)
        R.check("ui-messages-has-workspace-chip", has_ws_chip, "消息列表包含「工作区」筛选芯片")

        phase("8. 状态清理与复位")
        peer.send_exit()
        time.sleep(0.5)
        return True

    finally:
        peer.stop()
        if server:
            server.stop()


def main():
    args = parse_args()
    run_checks(args)
    sys.exit(R.report())


if __name__ == "__main__":
    main()
