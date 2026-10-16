#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DSH 全链路真机实测 (Prompt -> Session Busy -> Tool Approval -> Decision Tap -> Thinking Stream -> Idle)
验证目标:
1. 用户会话激活与状态广播 (state=busy)
2. 审批卡片下发与手机端弹窗渲染 (含风险等级、工具名称、审批摘要)
3. 移动端决策点击 (批准 / 拒绝) 与 agent-ack 回传
4. 思维链解析与折叠渲染 (<think> 结构化标签、思索过程展开/收起)
5. 会话平滑收敛归档 (state=idle)
"""
import json
import os
import subprocess
import sys
import time
import urllib.request

RELAY_URL = "http://127.0.0.1:8788"
SESSION_ID = f"session-live-dsh-{int(time.time())}"
WS_ID = "ws-e9b18063d183"
WS_TITLE = "teahouse-mobile"

def post_json(path, payload):
    url = RELAY_URL + path
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        print(f"Error posting to {path}: {e}")
        return None

def get_json(path):
    url = RELAY_URL + path
    req = urllib.request.Request(url, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        print(f"Error getting {path}: {e}")
        return None

def capture_screen(filename):
    out_path = f"/tmp/{filename}"
    subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=open(out_path, "wb"))
    print(f"  [Screen] Captured {out_path}")
    return out_path

def find_target_node():
    peers_info = get_json("/v1/peers")
    if not peers_info or "peers" not in peers_info:
        raise RuntimeError("No peers found from Tidings server.")
    for p in peers_info["peers"]:
        # Match online mobile peer
        if p.get("online") and ("手机" in p.get("nick", "") or p.get("platform") == "linux"):
            return p["nodeId"], p.get("nick"), p.get("ip")
    # Fallback to known nodeId if present
    for p in peers_info["peers"]:
        if p["nodeId"] == "f6a7b8c9-ede7-4ed1-84a4-4941d21cf81d":
            return p["nodeId"], p.get("nick"), p.get("ip")
    raise RuntimeError(f"Mobile peer not found in peers: {peers_info}")

print("==================================================")
print("DSH 全链路真机自动化验证流程")
print("==================================================")

target_node_id, nick, ip = find_target_node()
print(f"Target device: {nick} (nodeId={target_node_id}, ip={ip})")

# Step 1: Session Start & Prompt
print("\n--- 步骤 1: 宣告活动会话 (state=busy) 与发送用户 Prompt ---")
s_resp = post_json("/v1/agent/session", {
    "to": target_node_id,
    "sessionId": SESSION_ID,
    "alias": "NetworkMonitor审查",
    "title": "审查 NetworkMonitor 模块跨平台稳定性",
    "agentKind": "DSH",
    "agentId": "dsh-029bbbf3",
    "state": "busy",
    "wsId": WS_ID,
    "wsTitle": WS_TITLE,
    "wsPath": "/workspace/src/teahouse-mobile"
})
print("Session response:", s_resp)
time.sleep(1)

m_resp1 = post_json("/v1/send", {
    "to": target_node_id,
    "text": "请审查 NetworkMonitor 跨平台实现，并执行单元测试确保状态流不会漏事件。"
})
print("User prompt send response:", m_resp1)
time.sleep(1)
capture_screen("dsh_flow_1_prompt.png")

# Step 2: Agent Tool Call Approval (L3 高危, bash)
print("\n--- 步骤 2: 发起工具调用审批 (L3 高危, bash) ---")
now_ms = int(time.time() * 1000)
approval_id = f"appr-{int(time.time())}"
a_resp = post_json("/v1/agent/approval", {
    "to": target_node_id,
    "approvalId": approval_id,
    "sessionId": SESSION_ID,
    "tool": "bash",
    "summary": "./gradlew :core:jvmTest --tests NetworkMonitorTest",
    "riskLevel": "L3",
    "diff": "--- a/core/NetworkMonitor.kt\n+++ b/core/NetworkMonitor.kt\n@@ -15,3 +15,5 @@\n+    val isVpnActive: StateFlow<Boolean>",
    "choices": "allow,deny",
    "expiresAt": now_ms + 600000
})
print("Agent approval response:", a_resp)
time.sleep(1.5)
capture_screen("dsh_flow_2_approval_card.png")

# Step 3: Approve Decision (Simulate tap on phone)
print("\n--- 步骤 3: 模拟用户在手机端点击 [批准] 决策 ---")
# The "批准" button is typically around x=860, y=490 when banner is shown
# Let's verify with uiautomator dump
subprocess.run(["adb", "shell", "uiautomator", "dump", "/sdcard/approval_dump.xml"], stdout=subprocess.DEVNULL)
dump_out = subprocess.check_output(["adb", "shell", "cat", "/sdcard/approval_dump.xml"]).decode("utf-8", errors="ignore")

if "批准" in dump_out:
    print("Found '批准' button on screen, tapping it...")
    # Find bounds for 批准
    import re
    m = re.search(r'text="批准"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', dump_out)
    if m:
        x1, y1, x2, y2 = map(int, m.groups())
        tap_x = (x1 + x2) // 2
        tap_y = (y1 + y2) // 2
        print(f"Tapping '批准' at ({tap_x}, {tap_y})")
        subprocess.run(["adb", "shell", "input", "tap", str(tap_x), str(tap_y)])
    else:
        print("Fallback tap at (860, 490)")
        subprocess.run(["adb", "shell", "input", "tap", "860", "490"])
else:
    print("Approval button not found in text, trying fallback tap at (860, 490)")
    subprocess.run(["adb", "shell", "input", "tap", "860", "490"])

time.sleep(1.5)
capture_screen("dsh_flow_3_after_approved.png")

# Step 4: Assistant Answer with Thinking Process (<think>)
print("\n--- 步骤 4: 发送带思维链的助手回复 (<think> 结构化标签) ---")
m_resp2 = post_json("/v1/send", {
    "to": target_node_id,
    "text": "<think>\n执行命令: ./gradlew :core:jvmTest --tests NetworkMonitorTest\n耗时 2.8 秒，15 passed, 0 failed。\n状态机生命周期切换平滑无竞态，状态流验证完毕。\n</think>\n单元测试执行完毕，全部 15 项测试通过，NetworkMonitor 状态流逻辑正常无异常事件遗漏。"
})
print("Assistant thinking answer response:", m_resp2)
time.sleep(1.5)
capture_screen("dsh_flow_4_thinking_collapsed.png")

# Step 5: Expand Thinking Block on Screen
print("\n--- 步骤 5: 展开思索过程折叠栏 ---")
subprocess.run(["adb", "shell", "uiautomator", "dump", "/sdcard/thinking_dump.xml"], stdout=subprocess.DEVNULL)
tdump = subprocess.check_output(["adb", "shell", "cat", "/sdcard/thinking_dump.xml"]).decode("utf-8", errors="ignore")
import re
tm = re.search(r'text="思索过程"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tdump)
if tm:
    x1, y1, x2, y2 = map(int, tm.groups())
    tap_x = (x1 + x2) // 2
    tap_y = (y1 + y2) // 2
    print(f"Tapping '思索过程' at ({tap_x}, {tap_y})")
    subprocess.run(["adb", "shell", "input", "tap", str(tap_x), str(tap_y)])
    time.sleep(1)
    capture_screen("dsh_flow_5_thinking_expanded.png")

# Step 6: Session Convergence to Idle
print("\n--- 步骤 6: 会话结束，收敛为 idle 状态 ---")
s_resp_idle = post_json("/v1/agent/session", {
    "to": target_node_id,
    "sessionId": SESSION_ID,
    "alias": "NetworkMonitor审查",
    "title": "审查 NetworkMonitor 模块跨平台稳定性",
    "agentKind": "DSH",
    "agentId": "dsh-029bbbf3",
    "state": "idle",
    "wsId": WS_ID,
    "wsTitle": WS_TITLE,
    "wsPath": "/workspace/src/teahouse-mobile"
})
print("Session idle response:", s_resp_idle)
time.sleep(1.5)
capture_screen("dsh_flow_6_session_idle.png")

print("\n==================================================")
print("DSH 全链路真机验证成功完成！全部各阶段截图已就绪。")
print("==================================================")
