#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""e2e-team-pipeline —— 人 + 多 Agent 团队流水线的 E2E 指挥（天气 App）。

不是 happy-path 演示：五幕正剧 + 三处异常注入 + 两类人守门（审批等待 / 样式
点选等待）。指挥者在守门点**挂起轮询**（审批 API / 注入池），人确认后自动续演
—— 超时按各幕语义如实判失败，绝不静默跳过。

场景与幕表见 docs/plans/2026-10-31-e2e-team-pipeline.md。
"""

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

HOST = os.environ.get("TIDINGS_HOST_URL", "http://127.0.0.1:8788").rstrip("/")
TOKEN = os.environ.get("TIDINGS_PLUGIN_TOKEN", "")
WORKSPACE = os.environ.get("E2E_WORKSPACE", os.path.expanduser("~/e2e-weather-app"))
GROUP_ID = os.environ.get("E2E_GROUP", "e2e-weather")
GATE_TIMEOUT = int(os.environ.get("E2E_GATE_TIMEOUT", "600"))


def call(method: str, path: str, payload=None, timeout: float = 30.0):
    req = urllib.request.Request(
        HOST + path,
        data=None if payload is None else json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + TOKEN},
        method=method,
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8")
            return resp.status, (json.loads(body) if body.strip() else {})
    except urllib.error.HTTPError as e:
        return e.code, {}
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        return 0, {"error": str(e)}


def step(title: str):
    print(f"\n=== {title} ===", flush=True)


def gate_wait(title: str, poll: callable, timeout: int = GATE_TIMEOUT):
    """人守门点：挂起轮询直到人的动作落地（或超时如实失败）。"""
    print(f"*** 人守门：{title}（等待最多 {timeout}s —— 手机或 HTTP 确认）***", flush=True)
    deadline = time.time() + timeout
    while time.time() < deadline:
        result = poll()
        if result is not None:
            print(f"*** 守门通过：{result}", flush=True)
            return result
        time.sleep(2)
    print(f"!!! 守门超时 —— 按幕语义如实判失败", flush=True)
    return None


def act0_probe() -> bool:
    """幕 0 · 体检：宿主栈探活 + 关键路由清点。"""
    step("幕 0 · 宿主体检")
    for path in ("/v1/peers", "/v1/agent/sessions"):
        code, _ = call("GET", path)
        print(f"  {path} -> {code}")
        if code != 200:
            print(f"!!! 宿主未就绪（{path} {code}）—— 先起 headless 桌面栈", flush=True)
            return False
    print("  控制面在 8788 应答 ✓")
    return True


def act1_workspace() -> bool:
    """幕 1 · 组队绑定：真实 git 空仓 + 工作区宣告。"""
    step("幕 1 · 组队绑定（天气 App 空仓）")
    if not os.path.isdir(os.path.join(WORKSPACE, ".git")):
        os.makedirs(WORKSPACE, exist_ok=True)
        subprocess.run(["git", "init"], cwd=WORKSPACE, check=True)
        subprocess.run(["git", "config", "user.email", "e2e@tidings.local"], cwd=WORKSPACE, check=True)
        subprocess.run(["git", "config", "user.name", "E2E"], cwd=WORKSPACE, check=True)
        with open(os.path.join(WORKSPACE, "README.md"), "w") as fh:
            fh.write("# 天气 App（E2E 团队流水线项目）\n")
        subprocess.run(["git", "add", "."], cwd=WORKSPACE, check=True)
        subprocess.run(["git", "commit", "-m", "init: 空项目起点"], cwd=WORKSPACE, check=True)
    print(f"  workspace 就绪: {WORKSPACE}")
    # 工作区宣告路由存在与否是**第 0 号发现**的一部分：没有就把缺口如实打出来。
    code, body = call("POST", "/v1/agent/workspace", {
        "to": "self", "wsId": "weather-app", "wsTitle": "天气 App",
        "wsPath": WORKSPACE, "agentKind": "antigravity",
    })
    print(f"  工作区宣告 -> {code} {str(body)[:120]}")
    if code == 404:
        print("!!! 缺口：工作区宣告 HTTP 路由未开（宣告走帧不经控制面）—— 记入 E1/E2 清单")
    return True  # 幕 1 的 git 部分总是成立；宣告缺口如实记录


def send_group(text: str, required_tag: str = None):
    """群派单：/v1/send 的群路径（agentIntent 由宿主侧 @ 解析组装）。"""
    payload = {"to": GROUP_ID, "text": text}
    if required_tag:
        payload["requiredTag"] = required_tag
    return call("POST", "/v1/send", payload)


def wait_for_card(kind: str, timeout: int = 300):
    """轮询会话台账等一张卡出现（kind 子串匹配）。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        code, body = call("GET", "/v1/agent/sessions")
        if code == 200:
            for s in body.get("sessions", []):
                code2, page = call("GET", f"/v1/agent/session-page?nodeId={s.get('nodeId')}&sessionId={s.get('sessionId')}")
                if code2 == 200 and kind in json.dumps(page):
                    return page
        time.sleep(3)
    return None


def act2_design(requirement: str) -> bool:
    """幕 2 · 设计派单：hook/dispatch 直驱 agy（沙箱 + diff 卡 + 产物）。"""
    step("幕 2 · 设计派单（antigravity via E1 桥）")
    code, body = call("POST", "/v1/agent/hook/dispatch", {
        "agentKey": "antigravity",
        "prompt": f"为天气App出设计稿与单文件HTML原型（{requirement}）。把原型写入 prototype.html。完成后结束。",
        "wsPath": WORKSPACE,
    }, timeout=60)
    print(f"  派单 -> {code} {str(body)[:120]}")
    if code != 202:
        return False
    card = wait_for_card("diff-review")
    if card is None:
        print("!!! 300s 内未见 diff-review 卡 —— 检查 CLI 执行桥（E1）是否已落", flush=True)
        return False
    print("  diff-review 卡 ✓（沙箱执行 + 产物投递链路通）")
    return True


def act3_style_gate() -> bool:
    """幕 3 · 人守门：样式点选（ask_question 卡的 options）。"""
    step("幕 3 · 人守门（原型样式确认）")
    def poll():
        # 人的点选走卡片动作通路；脚本轮询台账看 decidedActionId 出现。
        code, body = call("GET", "/v1/agent/sessions")
        if code == 200 and "decidedActionId" in json.dumps(body):
            return "已点选"
        return None
    result = gate_wait("在 ask_question 卡上点选样式（手机）", poll)
    return result is not None


def act4_implement() -> bool:
    """幕 4 · 实现派单：antigravity 按原型实现（v1 同驱动；DSH bot 接线后切 @dsh）。"""
    step("幕 4 · 实现派单（antigravity 实现）")
    code, body = call("POST", "/v1/agent/hook/dispatch", {
        "agentKey": "antigravity",
        "prompt": "按 prototype.html 的设计实现 weather.html（单文件，含7天预报数据渲染）。",
        "wsPath": WORKSPACE,
    }, timeout=60)
    print(f"  派单 -> {code} {str(body)[:120]}")
    return code == 202


def act5_review() -> bool:
    """幕 5 · 验收审查：antigravity 验收实现。"""
    step("幕 5 · 验收审查（antigravity）")
    code, body = call("POST", "/v1/agent/hook/dispatch", {
        "agentKey": "antigravity",
        "prompt": "审查 weather.html 与 prototype.html 的一致性，输出验收结论。",
        "wsPath": WORKSPACE,
    }, timeout=60)
    print(f"  派单 -> {code} {str(body)[:120]}")
    return code == 202


def act6_merge_and_notify() -> bool:
    """幕 6 · 合流 + 通知：merge-patch 动作 + 终局通知。"""
    step("幕 6 · 合流 + 通知")
    log = subprocess.run(
        ["git", "-C", WORKSPACE, "log", "--oneline", "-3"],
        capture_output=True, text=True,
    )
    print(f"  宿主仓最近提交：\n{log.stdout}")
    code, body = call("POST", "/v1/agent/hook/session", {
        "event": "stop", "conversationId": "e2e-weather",
    })
    print(f"  流水线收束宣告 -> {code}")
    return True


def inject_i1_no_tag_match() -> bool:
    """注入 I1：无人持有的标签 → R3 柔性卡（挂起 + 两动作）。"""
    step("注入 I1 · 标签无匹配（require:gpu:h100）")
    code, body = send_group("@antigravity 跑个大模型微调 require:gpu:h100")
    print(f"  派单 -> {code}")
    card = wait_for_card("tagboard", timeout=120)
    print(f"  R3 卡 {'出现 ✓' if card else '未出现 ✗'}")
    return card is not None


def inject_i2_approval_timeout() -> bool:
    """注入 I2：审批超时 → fail-closed deny（人故意不批）。"""
    step("注入 I2 · 审批超时（人不批，等 fail-closed）")
    code, body = call("POST", "/v1/agent/hook/approval", {
        "tool": "git push", "summary": "E2E：故意不批", "timeoutMs": 15000,
    }, timeout=30)
    decision = body.get("decision")
    print(f"  决定 -> {decision}（期望 deny）")
    return code == 200 and decision == "deny"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--act", help="只演某一幕（0-6 / i1 / i2）；缺省全演")
    ap.add_argument("--requirement", default="北京天气App：未来7天预报，夜间深色主题")
    ap.add_argument("--tier", choices=["headless", "real"], default="headless",
                    help="headless = 脚本扮演人（CI 回归）；real = 真机真手机（守门等真人，读数进 runtime-evidence）")
    ap.add_argument("--skip-gates", action="store_true", help="CI 冒烟：人守门点立即超时跳过")
    args = ap.parse_args()
    if args.tier == "real":
        # Tier A：守门等待的是**真人** —— 超时放宽到 30 分钟（人在手机上，时机真实）。
        global GATE_TIMEOUT
        GATE_TIMEOUT = int(os.environ.get("E2E_REAL_GATE_TIMEOUT", "1800"))
        print("=== Tier A · 真实多设备模式（守门等真人，读数将进 runtime-evidence）===")
    if args.skip_gates:
        GATE_TIMEOUT = 5

    acts = {
        "0": lambda: act0_probe(),
        "1": lambda: act1_workspace(),
        "2": lambda: act2_design(args.requirement),
        "3": lambda: act3_style_gate(),
        "4": lambda: act4_implement(),
        "5": lambda: act5_review(),
        "6": lambda: act6_merge_and_notify(),
        "i1": lambda: inject_i1_no_tag_match(),
        "i2": lambda: inject_i2_approval_timeout(),
    }
    order = ["0", "1", "2", "3", "4", "5", "6"] if not args.act else [args.act]
    if args.act and args.act.startswith("i"):
        order = [args.act]

    results = {}
    for key in order:
        results[key] = acts[key]()
    failed = [k for k, v in results.items() if not v]
    print("\n=== 结局 ===")
    for k in order:
        print(f"  幕 {k}: {'✓' if results[k] else '✗'}")
    if failed:
        print(f"失败幕：{failed} —— 失败即发现（记入 docs/plans/2026-10-31-e2e-team-pipeline.md）")
        return 1
    print("全剧终 ✓")
    return 0


if __name__ == "__main__":
    sys.exit(main())
