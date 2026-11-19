#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""真机 E2E：群内 `@员工 一句话` 的派单链路（方案 §7 第 5/6 条）。

**为什么是脚本而不是手工点**：这两条验收的每一步都是「构造状态 → 触发 → 断言」，
手工做一次的证据既不可复跑、也无法在改动后重放。这个脚本把整条链路固化下来：
夹具（员工档案 + 群席位 + 执行授权 + 项目目录）→ 手机端发 @ → 断言宿主侧的卡片状态机与分类码。

## 用法

    python3 tools/e2e/agent_dispatch_e2e.py                        # 默认 hello 场景 + 受控替身
    python3 tools/e2e/agent_dispatch_e2e.py --scenario weather     # 天气场景：接入 Open-Meteo 真实 API
    python3 tools/e2e/agent_dispatch_e2e.py --scenario team        # 团队场景：@团队名 → 责任链
    python3 tools/e2e/agent_dispatch_e2e.py --scenario planner     # 主理人裁决：断言 source=llm（要 DeepSeek 凭证）
    python3 tools/e2e/agent_dispatch_e2e.py --worker codex         # 用本机真 codex CLI
    python3 tools/e2e/agent_dispatch_e2e.py --keep-host            # 跑完不关宿主（接着人手看）

四个场景共用同一条宿主编排链，只差「派什么活 / 项目夹具是什么 / 断言什么」：
  · hello（默认）：一句 `add hello to README`，通用替身改一行 README —— 验编排链本身。
  · weather：一句 `Integrate Open-Meteo`，项目夹具预填本机 weather-relay，天气替身写出
    一个真的实现 WeatherProvider 契约、调 Open-Meteo 免 key 接口的 provider + 测试，
    断言扩展到「新 provider 文件 + 测试全绿 + live API 拿到真实温度」。
  · team：@团队名（`@e2e-team`）触发 TeamExecutionResolver 的**整队入口** → 编译责任链。
    夹具写团队档案 + 团队席位 + 执行档 v3（1 worker=codex + 1 reviewer=dsh，异厂商过门），
    两棒各用一个受控替身（worker 造 diff、reviewer 只打 `VERDICT: PASS`），终局必须是
    review_ready 而非 done。这是单兵直派之外唯一能验到「责任链编译 + 多棒交接 + 审查档」的路径。
  · planner：与 team 同一套夹具，额外断言**形状是主理人裁的**（`planDecision.source=llm`，
    日志 + 飞行记录两处），而不是回落编译链 —— 棒数对这两者没有分辨力，只数棒会假绿。

前置（脚本会检查，缺了直接失败而不是猜）：
  · 一台 adb 在线的 Android 真机，装的是 **debug** 包 `com.kapp.tenon.debug`；
  · 桌面 debug 宿主已构建（`desktopApp/build/compose/binaries/main/app/Tenon-Debug.app`）；
  · 两侧已配对（信任库非空）—— 配对是安全动作，脚本不代劳、也不放宽。

## worker 为什么可替换

这两条验收验的是**宿主编排链**：身份键 → 执行档 → 逐成员授权 → 项目目录 → 工单 →
卡片状态机 → 产物收割。外部 CLI 只是链尾的一格。把它接进断言，环境一变（CLI 包坏了 /
没登录 / 上游限流）整条链路就红成一片，而产品其实是对的 —— 于是「验收」变成了「碰运气」。

* `--worker stub`（默认）：`TENON_CODEX_CLI` 指向 `tools/e2e/stub_cli.sh`，行为确定
  （改一行 README + 睡 4s + 输出一条 agent_message）。走的是**产品的同一条装配与派单代码**，
  只有最后一格换成了替身。
* `--worker codex`：用本机真 `codex`，先跑一次真实一句话探活（探不通就直接红，不猜）。

## 判据纪律

* **断言取被测对象的输出**：宿主库里的卡 JSON（`coalesce(card_payload, card_json)`
  —— `card_json` 是首帧锚、永不刷新，只读它会把「已经 blocked」看成「还在 proposed」，
  这是本脚本第一版真踩过的坑）、宿主日志里的 `[OCGO-BRIDGE] … cliDriver=`。
* 失败也留证：证据目录里有 ui dump、宿主日志、库快照、以及 sandbox 的 `task.log` 尾巴。
"""

from __future__ import annotations

import argparse
import glob
import json
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PKG = "com.kapp.tenon.debug"
ACTIVITY = f"{PKG}/com.kapp.tenon.MainActivity"
HOST_APP = os.path.join(ROOT, "desktopApp/build/compose/binaries/main/app/Tenon-Debug.app")
HOST_BIN = os.path.join(HOST_APP, "Contents/MacOS/Tenon-Debug")
STUB_CLI = os.path.join(ROOT, "tools/e2e/stub_cli.sh")
STUB_WEATHER_CLI = os.path.join(ROOT, "tools/e2e/stub-weather-cli.sh")
STUB_REVIEWER_CLI = os.path.join(ROOT, "tools/e2e/stub-reviewer-cli.sh")
WEATHER_SOURCE = os.path.expanduser("~/weather-relay")
HOST_PORT = 18788
DATA_DIR = os.path.expanduser("~/Library/Application Support/榫卯 Tenon Debug")
SETTINGS = os.path.join(DATA_DIR, "settings.json")
POLICIES = os.path.join(DATA_DIR, "agent-exec-policies.json")
TEAM_EXECUTION = os.path.join(DATA_DIR, "team-execution.json")
DB = os.path.join(DATA_DIR, "relay.db")
SANDBOX_ARTIFACTS = "/tmp/tenon-ws/artifacts"
SCRATCH = "/tmp/tenon-e2e-project"
TOKEN = "e2e"
EMP_ID = "emp-e2e-01"
# 夹具的执行档底座。stub 与真 codex 都是「codex 的调用形态」，所以 driverKey 不变 ——
# 两种模式之间只差一个 TENON_CODEX_CLI，派单代码路径完全相同。
DRIVER_KEY = "codex-cli"

# 天气场景的派单原文。**用英文**：手机端 adb input text 注入不了中文（AGENTS 已记），
# 而且天气替身按 "Open-Meteo" 关键词命中分支；这句话本身就是人给 agent 的真实需求。
WEATHER_PROMPT = "Integrate Open-Meteo live weather provider into the app"

# 团队场景的夹具身份。三处键必须一致，否则 fail-closed（见 TeamExecutionResolver）：
#   席位 agentId == 执行档 leaderAgentId；执行档 dirId == 群指针目录；
#   worker/reviewer 的 agentId 必须在席位 teamMembers 里。
TEAM_NAME = "e2e-team"            # 团队名（档案 name / 席位 teamName），ASCII 无空格
TEAM_LEADER = "e2e-team-lead"     # 领队 agent 键（团队对外单一席位窗口）
# @ 用的 token：**必须是团队名**，不能是组员 token。
# 入口语义（甲案）：`@团队名` / `@主理人` = 整队接活（编译责任链）；`@某个组员` = 单兵，
# 只让它干这一件事、不展开审查棒。用 `@coder` 触发团队链路会被正确地当成单兵派单，
# 于是「终局 review_ready」这条断言必然假红。
TEAM_MENTION = TEAM_NAME
TEAM_CODER = "m-e2e-coder"        # worker，driverKey=codex（stub_cli.sh 顶）
TEAM_REVIEWER = "m-e2e-reviewer"  # reviewer，driverKey=dsh（stub-reviewer-cli.sh 顶，异厂商）
TEAM_PROMPT = "add hello to README"


class E2e:
    def __init__(self, serial: str, group_name: str, keep_host: bool, worker: str,
                 scenario: str = "hello", offline: bool = False, reject: bool = False) -> None:
        self.serial = serial
        self.group_name = group_name
        self.keep_host = keep_host
        self.worker = worker
        self.scenario = scenario
        self.offline = offline
        self.reject = reject
        # planner 与 team 共用同一套团队夹具与责任链：差别只在多两条断言
        # （裁决来源必须是 llm，而不是回落编译链 —— 棒数对这两者没有分辨力）。
        self.team_fixture = scenario in ("team", "planner")
        self.host: subprocess.Popen | None = None
        self.out = time.strftime("/tmp/tenon-e2e-%Y%m%d-%H%M%S")
        os.makedirs(self.out, exist_ok=True)
        self.failures: list[str] = []
        self.group_id = ""
        # POLICIES 夹具还原用的快照（item：seed 写 anyMember，teardown 必须精确摘掉）。
        self.policies_seeded = False
        self.policies_keys: tuple[str, ...] = ()
        self.policies_original: dict = {}
        self.policies_raw_before: str | None = None

    # ---------------------------------------------------------------- 基础

    def run(self, *cmd: str, check: bool = True, timeout: int = 60,
            env: dict | None = None, cwd: str | None = None) -> str:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, env=env, cwd=cwd)
        if check and p.returncode != 0:
            raise RuntimeError(f"{' '.join(cmd)} -> {p.returncode}\n{p.stdout}\n{p.stderr}")
        return p.stdout

    def adb(self, *args: str, check: bool = True) -> str:
        return self.run("adb", "-s", self.serial, *args, check=check)

    def http(self, method: str, path: str, body: dict | None = None) -> dict:
        data = json.dumps(body) if body is not None else None
        args = ["curl", "-s", "-m", "8", "-X", method, f"http://127.0.0.1:{HOST_PORT}{path}"]
        if data is not None:
            args += ["-H", "content-type: application/json", "-d", data]
        # curl 连不上是**正常的探测结果**（宿主刚被停掉），不是脚本错误：
        # 判据写成「拿到 ok=true」而不是「curl 退出码 0」。
        raw = self.run(*args, check=False)
        try:
            return json.loads(raw or "{}")
        except json.JSONDecodeError:
            return {"_raw": raw}

    def check(self, ok: bool, label: str, detail: str = "", fatal: bool = False) -> bool:
        print(("  [ok]   " if ok else "  [FAIL] ") + label + (f" — {detail}" if detail else ""))
        if not ok:
            self.failures.append(label + (f": {detail}" if detail else ""))
            if fatal:
                # 关键断言失败后直接短路上抛：继续往下等只会再空耗几分钟并堆出
                # 一堆没有根因的伴随失败（首张卡/harvest/终态就是这类）。
                raise RuntimeError(f"关键断言失败：{label}" + (f" — {detail}" if detail else ""))
        return ok

    # ---------------------------------------------------------------- 前置

    def preflight(self) -> None:
        print("== 前置")
        print(f"  scenario = {self.scenario}   worker = {self.worker}")
        self.check(os.path.isdir(HOST_APP), "桌面 debug 宿主已构建")
        if self.scenario == "weather":
            self.check(os.path.isdir(WEATHER_SOURCE),
                       f"天气源工程在位（{WEATHER_SOURCE}）")
        if self.scenario == "planner":
            src = self.planner_credential_source()
            self.check(bool(src), "主理人裁决有 DeepSeek 凭证",
                       "env DEEPSEEK_API_KEY 与 ~/.dsh/.credentials.yaml 都没有 ⇒ Planner 恒回 null，"
                       "这一单会静默走回落编译链（本场景要验的正是「没有回落」）")
            if src:
                print(f"  [ok]   主理人凭证来源：{src}")
        self.check(self.probe_worker(), f"worker 可用（{self.worker}）")
        self.adb("shell", "svc", "power", "stayon", "true")
        self.adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        self.adb("shell", "wm", "dismiss-keyguard")
        pkgs = self.adb("shell", "pm", "list", "packages")
        self.check(f"package:{PKG}" in pkgs, f"真机装的是 debug 包 {PKG}")
        # **前台必须是榫卯**：别的 app 占着前台时，uiautomator dump 读到的是别人的
        # 界面，症状是「消息列表里找不到群」这类假红（2026-11-19 现场：设备上另一个
        # 同协议族的 debug 包占着前台，连续两轮假红）。拉起来并核对焦点，不猜。
        self.adb("shell", "am", "start", "-n", ACTIVITY)
        time.sleep(2.5)
        focus = self.adb("shell", "dumpsys", "window")
        if PKG not in focus:
            focused = next((ln.strip() for ln in focus.splitlines()
                            if "mCurrentFocus" in ln and "null" not in ln), "(unknown)")
            raise RuntimeError(
                "榫卯不在前台（当前焦点：" + focused + "）。"
                "停掉占前台的应用后重跑 —— 否则后续 UI 步骤读到的是别人的界面。"
            )
        pins = os.path.join(DATA_DIR, "stores/peer-pins.json")
        self.check(os.path.exists(pins) and len(json.load(open(pins)).get("pins", {})) > 0,
                   "宿主侧信任库非空（已配对）")

    # WorkBuddy 的 shell 会注入的"毒环境"：继承给宿主进程后各坏各的 ——
    #   * CODEBUDDY_API_KEY_HELPER_DISABLED=1：真值判断，字符串 "0" 也算禁用 ⇒
    #     ~/.codebuddy/settings.json 的 apiKeyHelper（codebuddy 唯一取 token 的路）被
    #     静默关掉，派单报 "Authentication required"（2026-11-17 实测）。
    #     必须整键剥掉，设成 0 没用。
    #   * NODE_OPTIONS / PYTHONPATH / BASH_ENV：语言层 safe-delete shim，长驻进程
    #     会在批量删除守卫处崩（见项目记忆「safe-delete shim 会被长驻服务继承」）。
    # 真实使用（Finder 启动宿主）不带这些变量；这里剥是为了让脚本环境和真实环境同形。
    HOST_ENV_POISON = (
        "CODEBUDDY_API_KEY_HELPER_DISABLED",
        "NODE_OPTIONS", "PYTHONPATH", "BASH_ENV",
    )

    def stub_cli(self) -> str:
        """当前场景 worker 棒该用哪个替身二进制。天气场景用会写 Open-Meteo provider 的那个。"""
        return STUB_WEATHER_CLI if self.scenario == "weather" else STUB_CLI

    def host_env(self) -> dict:
        """宿主进程环境：stub 模式把 CLI 二进制换成替身（产品的 env 覆盖通路）。

        团队场景顶两家：worker=codex（`TENON_CODEX_CLI`）造 diff，reviewer=dsh
        （`TENON_DSH_CLI`）只打一句 VERDICT: PASS、不写文件。厂商门要求 reviewer≠worker
        （dsh=deepseek vs codex=openai），所以审查棒的替身必须挂在 dsh 这个键上。
        """
        env = {k: v for k, v in os.environ.items() if k not in self.HOST_ENV_POISON}
        if self.worker == "codex":
            # 真 codex 在工具/Agent 沙箱里拉起时，默认的 `--sandbox workspace-write`
            # 会再套一层 seatbelt ⇒ 内层 sandbox-exec 被内核硬拒
            # (`sandbox_apply: Operation not permitted`)，派单零补丁。产品早有逃生口
            # (CliSubprocessDriver.codexSandboxMode)：关掉 CLI 内层沙箱，隔离边界只剩
            # 本仓的一次性 worktree。设上它真 CLI 就能在当前环境真写文件，无需树外 Terminal。
            # 已在工具沙箱内实测：workspace-write ⇒ NO FILE；danger-full-access ⇒ WROTE PROOF_OK。
            env["TENON_CODEX_SANDBOX"] = "danger-full-access"
        if self.worker == "stub":
            env["TENON_CODEX_CLI"] = self.stub_cli()
            if self.scenario == "weather":
                # 沙箱会把 $HOME 重定向到 .sandbox-home，stub 不能用 $HOME/weather-relay 找基座。
                # SCRATCH 是固定绝对路径、里面就是 prepare_scratch 拷好的完整 weather-relay。
                env["TENON_WEATHER_SOURCE"] = SCRATCH
                if self.offline:
                    env["TENON_E2E_OFFLINE"] = "1"
            if self.team_fixture:
                env["TENON_DSH_CLI"] = STUB_REVIEWER_CLI
        return env

    @staticmethod
    def _codex_probe_reply(stdout: str) -> str:
        """从 codex exec --json 的 JSONL 里取助手侧文字，不认死某一种行型。

        产品侧 CliLineEventMapper 认的文本字段是 text / delta / response，另有嵌套
        item.text 与 message.content[].text；用户侧回声（prompt）要排除，否则探活
        提示词里那句 "reply with the single word ok" 的 ok 会自己证明自己。
        """
        parts = []
        for line in (stdout or "").splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                continue
            if not isinstance(obj, dict):
                continue
            if str(obj.get("type") or "") in ("user_message", "input", "prompt"):
                continue
            for key in ("text", "delta", "response"):
                v = obj.get(key)
                if isinstance(v, str) and v:
                    parts.append(v)
            item = obj.get("item")
            if isinstance(item, dict):
                if str(item.get("type") or "") not in ("user_message", "input"):
                    for v in (item.get("text"), item.get("content")):
                        if isinstance(v, str) and v:
                            parts.append(v)
            msg = obj.get("message")
            if isinstance(msg, dict) and isinstance(msg.get("content"), list):
                for entry in msg["content"]:
                    if isinstance(entry, dict) and isinstance(entry.get("text"), str):
                        parts.append(entry["text"])
        return " ".join(parts)

    def probe_worker(self) -> bool:
        """探活：真的跑一句话。**不猜**——「装是装了」不等于「能派单」。"""
        if self.worker == "stub":
            stub = self.stub_cli()
            if not (os.path.isfile(stub) and os.access(stub, os.X_OK)):
                print(f"  [FAIL] 替身不存在或不可执行：{stub}")
                return False
            # 团队场景还要探审查棒替身（dsh 形态），缺一个整链都会在 reviewer 棒 blocked。
            if self.team_fixture and not (
                os.path.isfile(STUB_REVIEWER_CLI) and os.access(STUB_REVIEWER_CLI, os.X_OK)
            ):
                print(f"  [FAIL] 审查棒替身不存在或不可执行：{STUB_REVIEWER_CLI}")
                return False
            # **在独立临时目录跑，绝不用 SCRATCH**：stub 探活会往 README 追加一行
            # （它是靠改文件让 harvest 有 diff 的），写进 SCRATCH 就把派单项目的工作树
            # 弄脏 —— 单兵场景无所谓，团队责任链强制 worktree clean ⇒ 直接 blocked
            # ("Working tree not clean (1 changes)")，第五轮的假红就是这么来的。
            import tempfile
            probe_dir = tempfile.mkdtemp(prefix="tenon-probe-")
            try:
                env = dict(os.environ)
                if self.scenario == "weather":
                    env["TENON_WEATHER_SOURCE"] = SCRATCH
                p = subprocess.run(
                    [stub, "exec", "--json", "--sandbox", "workspace-write", "probe"],
                    capture_output=True, text=True, timeout=30, cwd=probe_dir, env=env,
                )
                # 两个信号都要：只看 "agent_message" 字样、不看退出码，替身报错退出
                # 却碰巧打了一行也会探活假绿。
                return p.returncode == 0 and "agent_message" in (p.stdout or "")
            finally:
                shutil.rmtree(probe_dir, ignore_errors=True)
        binary = shutil.which("codex")
        if binary is None:
            print("  [FAIL] PATH 上没有 codex")
            return False
        os.makedirs(SCRATCH, exist_ok=True)
        if not os.path.isdir(os.path.join(SCRATCH, ".git")):
            self.run("git", "init", "-q", SCRATCH)
            self.run("git", "-C", SCRATCH, "config", "user.email", "e2e@local")
            self.run("git", "-C", SCRATCH, "config", "user.name", "e2e")
            self.run("git", "-C", SCRATCH, "add", "-A")
            self.run("git", "-C", SCRATCH, "commit", "-qm", "init",
                     check=False)
        p = subprocess.run([binary, "exec", "--json", "--sandbox", "read-only",
                            "--skip-git-repo-check", "reply with the single word ok"],
                           capture_output=True, text=True, timeout=180, cwd=SCRATCH)
        reply = self._codex_probe_reply(p.stdout)
        # 退出码 + 输出双验：旧代码只看 returncode==0，CLI 零输出/空回复也算探活过。
        # 探活提示词要的就是一个 ok，在助手文字里找独立的 ok（不写死具体行型）；
        # "final" 作为兜底标记也算有实质输出。
        marker = bool(re.search(r"\bok\b", reply, re.I)) or "final" in reply.lower()
        ok = p.returncode == 0 and bool(reply.strip()) and marker
        if not ok:
            print("  codex 探活原文（前 400 字）：" + (p.stdout or p.stderr or "")[:400].replace("\n", " | "))
        return ok

    # ---------------------------------------------------------------- 宿主

    def host_down(self) -> None:
        subprocess.run(["pkill", "-f", "Tenon-Debug.app/Contents/MacOS/Tenon-Debug"],
                       capture_output=True)
        for _ in range(40):
            if not self._host_alive():
                return
            time.sleep(0.25)

    def _host_alive(self) -> bool:
        return self.http("GET", "/healthz").get("ok") is True

    def host_up(self) -> None:
        log = open(os.path.join(self.out, "host.log"), "w")
        self.host = subprocess.Popen([HOST_BIN], stdout=log, stderr=subprocess.STDOUT,
                                     env=self.host_env())
        for _ in range(60):
            if self._host_alive():
                time.sleep(1.5)
                return
            time.sleep(0.5)
        raise RuntimeError("宿主 30s 内没起来（看证据目录里的 host.log）")

    # ---------------------------------------------------------------- 夹具

    def create_group_if_absent(self) -> None:
        """群里要有个「自己 + 宿主」的群；没有就用手机建一个（产品路径，不塞数据）。"""
        settings = json.load(open(SETTINGS))
        for g in settings.get("groups", []):
            if g.get("name") == self.group_name:
                self.group_id = g["groupId"]
                print(f"  [ok]   复用已有群 {self.group_name} / {self.group_id}")
                return
        self.open_messages()
        self.tap_desc("更多")
        self.tap_text("建群")
        self.tap_text("群名")
        self.type_text(self.group_name)
        # 候选里要选「宿主那一行」：昵称取 settings 顶层 nick（宿主自报的显示名），
        # 不再写死机器名——换一台宿主就点不到了（本脚本曾因 kedeMac 写死而假红）。
        host_nick = (settings.get("nick") or "").strip()
        if host_nick:
            tapped = self.tap_text_contains(host_nick)
        else:
            # 退路：settings 没有 nick 时，列出候选文本，挑第一条非本机行并 warn。
            # 「本机」= 手机自己（它的 nick 在 memberNicks 里、键不是宿主 nodeId），
            # 连同按钮文案一并排除，免得点到手机自己或误触控件。
            host_id = settings.get("nodeId")
            local_nicks = {
                v for g in settings.get("groups", [])
                for k, v in (g.get("memberNicks") or {}).items()
                if k != host_id and v
            }
            buttons = {"更多", "建群", "群名", "创建", "取消", "确定", "返回",
                       "搜索", self.group_name, "说点什么…"}
            tapped = False
            for text in self.dump_texts():
                if text in buttons or text in local_nicks:
                    continue
                print(f"  [warn] settings 无 nick，建群候选退回第一条非本机行：{text}")
                tapped = self.tap_text_contains(text)
                if tapped:
                    break
        if not tapped:
            raise RuntimeError(f"建群候选里找不到宿主行（host nick={host_nick or '(无)'}）")
        self.tap_text("创建")
        time.sleep(3)
        settings = json.load(open(SETTINGS))
        hit = [g for g in settings.get("groups", []) if g.get("name") == self.group_name]
        if not hit:
            raise RuntimeError("建群后宿主 settings 里没有这个群（群帧没同步？看 host.log）")
        self.group_id = hit[0]["groupId"]
        print(f"  [ok]   新建群 {self.group_name} / {self.group_id}")

    def _grant_policies(self, keys) -> None:
        """写 anyMember 授权，同时快照原文与本轮写过的 key，供 teardown 精确还原。

        为什么不整份写回快照：运行期间机主/宿主可能并发往 POLICIES 写无关 key，
        整份写回会把它们一并抹掉（还原动作自己成污染源）。这里只记
        (a) 本轮写过的 key 集合，(b) 这些 key 在本轮之前的原值；还原时只把
        本轮动过的 key 恢复原值/删除，其他 key 一律不碰。
        """
        raw = None
        if os.path.exists(POLICIES):
            try:
                with open(POLICIES, errors="ignore") as f:
                    raw = f.read()
            except OSError:
                raw = None
        self.policies_raw_before = raw
        original = {}
        if raw is not None:
            try:
                parsed = json.loads(raw)
                if isinstance(parsed, dict):
                    original = parsed
            except json.JSONDecodeError:
                pass
        self.policies_keys = tuple(keys)
        self.policies_original = {k: original[k] for k in keys if k in original}
        self.policies_seeded = True
        policies = dict(original)
        for key in keys:
            policies[key] = "anyMember"
        with open(POLICIES, "w") as f:
            json.dump(policies, f, ensure_ascii=False, indent=4)

    def _restore_policies(self) -> None:
        """还原本轮 seed 动过的 POLICIES key（精确按 key，理由见 _grant_policies）。"""
        if not self.policies_seeded:
            return
        original = self.policies_original
        if not os.path.exists(POLICIES):
            return
        try:
            with open(POLICIES) as f:
                current = json.load(f)
        except (json.JSONDecodeError, OSError):
            return
        if not isinstance(current, dict):
            return
        changed = False
        for k in self.policies_keys:
            if k in original:
                if current.get(k) != original[k]:
                    current[k] = original[k]
                    changed = True
            elif k in current:
                del current[k]
                changed = True
        if changed:
            with open(POLICIES, "w") as f:
                json.dump(current, f, ensure_ascii=False, indent=4)
        # 原本没有 POLICIES：本轮建的壳摘掉本轮 key 后若已空，就删文件回到初始态
        # （除非并发写入留下了别的 key，那种情况保留文件与别人的 key）。
        if self.policies_raw_before is None and not current:
            try:
                os.remove(POLICIES)
            except OSError:
                pass

    def seed_fixture(self) -> None:
        """按场景构造夹具，写的是宿主**自己的**落盘格式（不是猜的键名）。

        · hello/weather：单兵直派 —— 员工档案 + 群席位 + 执行授权。
        · team：团队责任链 —— 团队档案(teamProfiles) + 团队席位(entityType=team)
          + 执行档 v3(team-execution.json) + 逐棒授权。三处身份键必须对齐。
        """
        self.host_down()
        shutil.copy(SETTINGS, os.path.join(self.out, "settings.before.json"))
        if self.team_fixture:
            self._seed_team_fixture()
            return
        d = json.load(open(SETTINGS))
        node = d["nodeId"]
        d["employeeProfiles"] = [
            e for e in d.get("employeeProfiles") or [] if e.get("employeeId") != EMP_ID
        ] + [{
            "employeeId": EMP_ID,
            "agentId": EMP_ID,
            "name": "E2E 小工",
            "token": TOKEN,
            "duty": "assistant",
            "driverKey": DRIVER_KEY,
            "rev": 1,
            "updatedTs": int(time.time() * 1000),
        }]
        for g in d["groups"]:
            if g.get("groupId") != self.group_id:
                continue
            seats = [s for s in g.get("virtualAgents") or [] if s.get("employeeId") != EMP_ID]
            seats.append({
                "agentId": EMP_ID,
                "hostNodeId": node,
                "name": TOKEN,
                "entityType": "single",
                "employeeId": EMP_ID,
            })
            g["virtualAgents"] = seats
            g["rev"] = int(g.get("rev") or 1) + 1
        json.dump(d, open(SETTINGS, "w"), ensure_ascii=False, indent=4)
        self._grant_policies((EMP_ID, TOKEN, "codex"))
        print(f"  [ok]   夹具就位：档案 {EMP_ID} + 席位 @{TOKEN} + anyMember + {DRIVER_KEY}")

    def _seed_team_fixture(self) -> None:
        """团队场景：团队档案 + 团队席位 + 逐棒授权（settings/policies 在 host_down 时写）。

        执行档不在这里写 —— 它需要 `dirId`，而 dirId 是 host_up 之后 bind 出来的，
        见 `write_team_execution()`。三处身份键在此就固定好：
          席位 agentId = leaderAgentId = TEAM_LEADER；
          席位 teamMembers 的 agentId = 执行档 members 的 agentId（coder/reviewer）。
        """
        d = json.load(open(SETTINGS))
        node = d["nodeId"]
        ts = int(time.time() * 1000)
        members = [
            {"agentId": TEAM_CODER, "name": "coder", "role": "编码"},
            {"agentId": TEAM_REVIEWER, "name": "reviewer", "role": "审查"},
        ]
        # teamProfiles：主键 teamId，跨群，不含 groupId（v3 真相，旧文档的 (group,ws,leader) 已过时）
        profiles = [p for p in (d.get("teamProfiles") or [])
                    if p.get("teamId") != "tm-e2e-0001"]
        profiles.append({
            "teamId": "tm-e2e-0001",
            "name": TEAM_NAME,
            "leaderAgentId": TEAM_LEADER,
            "members": members,
            "rev": 1,
            "updatedTs": ts,
        })
        d["teamProfiles"] = profiles
        # 团队席位：entityType=team + teamName（@ 候选）+ teamMembers（解析 worker 的注册表）
        for g in d["groups"]:
            if g.get("groupId") != self.group_id:
                continue
            seats = [s for s in (g.get("virtualAgents") or [])
                     if s.get("teamId") != "tm-e2e-0001" and s.get("agentId") != TEAM_LEADER]
            seats.append({
                "agentId": TEAM_LEADER,
                "hostNodeId": node,
                "name": TEAM_LEADER,
                "entityType": "team",
                "teamId": "tm-e2e-0001",
                "teamName": TEAM_NAME,
                "teamMembers": members,
            })
            g["virtualAgents"] = seats
            g["rev"] = int(g.get("rev") or 1) + 1
        json.dump(d, open(SETTINGS, "w"), ensure_ascii=False, indent=4)
        # 逐棒授权：worker(coder) 与 reviewer 各自按 agentId 放行（不是按领队）。
        self._grant_policies((TEAM_LEADER, TEAM_CODER, TEAM_REVIEWER, "codex", "dsh"))
        print(f"  [ok]   团队夹具就位：档案 tm-e2e-0001 + 席位 @{TEAM_NAME} "
              f"+ worker={TEAM_CODER}/codex + reviewer={TEAM_REVIEWER}/dsh + anyMember")

    def write_team_execution(self, dir_id: str) -> None:
        """团队执行档 v3（主键 `(dirId, leaderAgentId)`，不含 groupId，schemaVersion=3）。

        dirId 由 bind_project_directory() 现拿 —— 必须与群指针指向的目录一致，否则
        `profileForSeat` 按 (dirId, leaderAgentId) 取不到档 ⇒ NO_DRIVER。恰好 1 worker，
        reviewer 厂商 deepseek(dsh) ≠ worker 厂商 openai(codex)，过保存/派单两道厂商门。
        宿主按 mtime/长度失效懒加载，运行期写文件即可读到（不必 host_down）。
        """
        doc = {
            "schemaVersion": 3,
            "teams": [{
                "dirId": dir_id,
                "leaderAgentId": TEAM_LEADER,
                "updatedAt": int(time.time() * 1000),
                "members": [
                    {"agentId": TEAM_CODER, "kind": "worker", "driverKey": "codex", "models": []},
                    {"agentId": TEAM_REVIEWER, "kind": "reviewer", "driverKey": "dsh", "models": []},
                ],
            }],
        }
        if os.path.exists(TEAM_EXECUTION):
            shutil.copy(TEAM_EXECUTION, os.path.join(self.out, "team-execution.before.json"))
        json.dump(doc, open(TEAM_EXECUTION, "w"), ensure_ascii=False, indent=4)
        print(f"  [ok]   团队执行档就位：dirId={dir_id} + 1 worker(codex) + 1 reviewer(dsh)")

    def restore_fixture(self) -> None:
        """把夹具**外科式摘掉**（夹具是测试资产，不该留在机主的档案里）。

        为什么不是「整份拷回 `settings.before.json`」：那份快照可能是**上一轮留下的脏状态**
        （本轮第一版就这么把一条旧夹具又还原回来了 —— 还原动作本身成了污染源），
        而且整份拷回会把运行期间宿主自己写的合法改动（群 `rev`、目录登记）一起抹掉。
        只按 `employeeId` 摘档案与席位，语义确定、可重复执行。
        `--keep-host` 时不动（那是留给人看现场的）。
        """
        if self.keep_host:
            return
        self.host_down()
        # POLICIES 与本轮夹具同生共死：seed 写进去的 anyMember 授权必须精确摘掉，
        # 否则机主 policies 里永久残留一堆 e2e key（见 _grant_policies）。
        self._restore_policies()
        if not os.path.exists(SETTINGS):
            return
        d = json.load(open(SETTINGS))
        touched = False
        if self.team_fixture:
            # 团队档案按 teamId、席位按 leader agentId（都是 e2e 专用 id）摘。
            teams = d.get("teamProfiles") or []
            kept_teams = [t for t in teams if t.get("teamId") != "tm-e2e-0001"]
            if len(kept_teams) != len(teams):
                d["teamProfiles"] = kept_teams
                touched = True
            for g in d.get("groups", []):
                seats = g.get("virtualAgents") or []
                kept_seats = [s for s in seats
                              if s.get("teamId") != "tm-e2e-0001" and s.get("agentId") != TEAM_LEADER]
                if len(kept_seats) != len(seats):
                    g["virtualAgents"] = kept_seats
                    g["rev"] = int(g.get("rev") or 1) + 1
                    touched = True
            # 执行档：有 before 快照就还原（不带就删掉这条 e2e 档）。
            if os.path.exists(TEAM_EXECUTION):
                before = os.path.join(self.out, "team-execution.before.json")
                if os.path.exists(before):
                    shutil.copy(before, TEAM_EXECUTION)
                else:
                    try:
                        tex = json.load(open(TEAM_EXECUTION))
                        tex["teams"] = [t for t in tex.get("teams", [])
                                        if not (t.get("dirId") == getattr(self, "dir_id", None)
                                                and t.get("leaderAgentId") == TEAM_LEADER)]
                        json.dump(tex, open(TEAM_EXECUTION, "w"), ensure_ascii=False, indent=4)
                    except (json.JSONDecodeError, OSError):
                        pass
        else:
            profiles = d.get("employeeProfiles") or []
            kept = [e for e in profiles if e.get("employeeId") != EMP_ID]
            if len(kept) != len(profiles):
                d["employeeProfiles"] = kept
                touched = True
            for g in d.get("groups", []):
                seats = g.get("virtualAgents") or []
                kept_seats = [s for s in seats
                              if s.get("employeeId") != EMP_ID and s.get("agentId") != EMP_ID]
                if len(kept_seats) != len(seats):
                    g["virtualAgents"] = kept_seats
                    g["rev"] = int(g.get("rev") or 1) + 1
                    touched = True
        if touched:
            json.dump(d, open(SETTINGS, "w"), ensure_ascii=False, indent=4)
        print("  [ok]   夹具已从宿主设置摘除" if touched else "  [ok]   宿主机设置里无夹具残留")

    def prepare_scratch(self) -> None:
        """把派单目录备成当前场景的干净基线。

        · hello/team：一个只有一行 README 的新 git 仓库（team 用最小项目把责任链本身验干净，
          不叠天气变量）。
        · weather：从本机 weather-relay 拷一份（含 .git 与 90 条测试的历史基线）。
          **整份替换**而不是增量改 —— 上一轮替身写进来的 provider / app.js 注入必须清掉，
          否则本轮替身再写同样内容就是空补丁，harvest 割不到东西，卡片「完成」却无产物。
        """
        if self.scenario != "weather":
            # **每次重建**，不是「没有 .git 才 init」：责任链要求 worktree 干净，上一轮
            # harvest/探针写进 README 的改动留着 ⇒ 下一轮直接 blocked
            # ("Working tree not clean (1 changes)")。最小仓库重建成本可忽略，换来确定性。
            if os.path.isdir(SCRATCH):
                shutil.rmtree(SCRATCH)
            os.makedirs(SCRATCH)
            self.run("git", "init", "-q", SCRATCH)
            with open(os.path.join(SCRATCH, "README.md"), "w") as f:
                f.write("e2e\n")
            self.run("git", "-C", SCRATCH, "config", "user.email", "e2e@local")
            self.run("git", "-C", SCRATCH, "config", "user.name", "e2e")
            self.run("git", "-C", SCRATCH, "add", ".")
            self.run("git", "-C", SCRATCH, "commit", "-qm", "init")
            return
        # weather：整份替换，保留源工程的 git 历史作为 harvest 基线。
        if not os.path.isdir(WEATHER_SOURCE):
            raise RuntimeError(f"天气源工程不存在：{WEATHER_SOURCE}")
        if os.path.isdir(SCRATCH):
            shutil.rmtree(SCRATCH)
        shutil.copytree(WEATHER_SOURCE, SCRATCH)
        head = self.run("git", "-C", SCRATCH, "rev-parse", "--short", "HEAD").strip()
        print(f"  [ok]   天气项目夹具就位：{WEATHER_SOURCE} → {SCRATCH} @ {head}")

    def bind_project_directory(self) -> str:
        snap = self.http("POST", "/v1/host/directories",
                         {"path": SCRATCH, "displayName": self.scenario})
        # **按 path 精确匹配，不取 [0]**：宿主可能已有多个登记目录，返回的第一个未必是本次
        # 这条（实测污染成旧目录 ⇒ 群指针指向 A、项目文件在 B，团队执行档按 dirId 取不到档
        # ⇒ "chain executor unavailable or no workspace bound"）。macOS /tmp 会解析成
        # /private/tmp，用 realpath 归一后再比。
        want = os.path.realpath(SCRATCH)
        hit = [d for d in snap["directories"]
               if os.path.realpath(d.get("path") or "") == want]
        if not hit:
            raise RuntimeError(f"登记目录后快照里找不到 {want}：{snap['directories']}")
        dir_id = hit[0]["dirId"]
        self.http("POST", "/v1/host/group-directory", {"groupId": self.group_id, "dirId": dir_id})
        print(f"  [ok]   项目目录 {dir_id}（{self.scenario}）已登记并绑到群")
        return dir_id

    # ---------------------------------------------------------------- 手机 UI

    def save_ui_final(self) -> None:
        """把最后一帧 UI 树存进证据目录（成功/失败都调）。

        成功态也要留：卡片视觉排布、按钮分层、长 diff 的可读性只有 UI dump 能评估，
        只有失败才留就等于「跑绿了反而没证据」。dump 失败不打断主流程 —— 它是留证，
        不是断言；但失败要响亮打印，不能静默吞。
        """
        try:
            root = self.dump()
            path = os.path.join(self.out, "ui-final.xml")
            with open(path, "w") as f:
                f.write(ET.tostring(root, encoding="unicode"))
            print(f"  [ok]   末帧 UI 已留证：{path}")
        except Exception as exc:
            print(f"  [warn] 末帧 UI 留证失败（不影响场景断言）：{exc}")

    def open_messages(self) -> None:
        self.adb("shell", "input", "keyevent", "KEYCODE_HOME")
        self.adb("shell", "am", "start", "-W", "-n", ACTIVITY)
        time.sleep(1.5)
        for _ in range(3):
            if self.dump_texts():
                return
            time.sleep(1)

    def dump(self) -> ET.Element:
        # uiautomator dump 偶发失败/空文件：重试一次；仍失败则把原始输出留证到证据目录
        # 并明确 warn + 抛异常。绝不静默返回空 hierarchy 当正常——空树会让 tap_* 找不到
        # 节点，症状变成难查的假红/假绿。
        cmd = ["adb", "-s", self.serial, "shell", "uiautomator", "dump", "/sdcard/e2e-ui.xml"]
        stderr = ""
        for _ in range(2):
            p = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
            stderr = (p.stderr or "") + (p.stdout or "")
            raw = self.adb("exec-out", "cat", "/sdcard/e2e-ui.xml", check=False)
            try:
                return ET.fromstring(raw)
            except ET.ParseError:
                continue
        with open(os.path.join(self.out, "uiautomator-dump.err"), "w") as f:
            f.write(stderr)
        print(f"  [warn] uiautomator dump 两次失败，原始输出已留证：{stderr[:300]}")
        raise RuntimeError("uiautomator dump 两次都失败（看证据目录 uiautomator-dump.err）")

    def dump_texts(self) -> list[str]:
        return [t for t in (n.get("text") or "" for n in self.dump().iter("node")) if t.strip()]

    @staticmethod
    def bounds(node: ET.Element) -> tuple[int, int]:
        raw = node.get("bounds", "")
        m = re.match(r"\[(\d+),(\d+)]\[(\d+),(\d+)]", raw)
        if not m:
            # 解析失败必须响亮失败：返回 (0,0) 会去 tap 屏幕左上角，误触且无从查起。
            raise ValueError(f"bounds 解析失败：{raw!r}")
        x1, y1, x2, y2 = (int(v) for v in m.groups())
        return ((x1 + x2) // 2, (y1 + y2) // 2)

    def tap_node(self, node: ET.Element) -> None:
        x, y = self.bounds(node)
        self.adb("shell", "input", "tap", str(x), str(y))

    def tap_text(self, text: str, contains: bool = False) -> bool:
        for n in self.dump().iter("node"):
            t = (n.get("text") or "").strip()
            if (contains and text in t) or (not contains and t == text):
                self.tap_node(n)
                time.sleep(0.9)
                return True
        return False

    def tap_text_contains(self, text: str) -> bool:
        return self.tap_text(text, contains=True)

    def tap_desc(self, desc: str) -> bool:
        for n in self.dump().iter("node"):
            if (n.get("content-desc") or "").strip() == desc:
                self.tap_node(n)
                time.sleep(0.9)
                return True
        return False

    def type_text(self, text: str) -> None:
        # 逐字符：`input text` 在真机上会吞字（AGENTS 已记）。本脚本只用 ASCII。
        # **空格单独走 keyevent 62**：`input text " "` 会被 shell 解析成空实参，
        # 直接抛 `Argument expected after "text"`（实测）。
        for ch in text:
            if ch == " ":
                self.adb("shell", "input", "keyevent", "62")
            else:
                self.adb("shell", "input", "text", ch)
            time.sleep(0.18)

    def open_group(self) -> None:
        self.open_messages()
        # 上一轮可能把界面留在群会话页（尤其手打 @ 时的候选弹出层）：am start 对已前台的
        # activity 不会重置回消息列表。找不到群名就按 BACK 退回列表再试，别直接假红。
        if not self.tap_text(self.group_name):
            for _ in range(3):
                self.adb("shell", "input", "keyevent", "KEYCODE_BACK")
                time.sleep(0.8)
                if self.tap_text(self.group_name):
                    break
            else:
                raise RuntimeError(f"消息列表里找不到群 {self.group_name}")
        time.sleep(2)

    def send_mention(self, prompt: str, mention: str | None = None) -> None:
        """@ 一个 token 再发 prompt，走点候选（可靠、确定的产品路径）。

        · mention=None：单兵，点 @e2e 候选。
        · mention=TEAM_MENTION：团队名（`@e2e-team`）—— 候选里可点，命中
          `TeamExecutionResolver` 的**整队入口**（`@团队名` / `@主理人`），编译
          [worker]+[reviewer] 责任链。组员 token（`@coder`）现在是**单兵**语义，
          点了不会拉链 —— 团队场景必须点团队名。

        为什么不用手打 @团队名：adb 逐字符输入 `@` 会弹候选层，后续字符被拦截，
        实测把 `@e2e-team ...` 打成了 `@所有人 ...`（群里那条误发消息为证）。点候选
        没有这个时序问题。
        """
        placeholder = "说点什么…"
        # 展开的卡片会盖住输入框（真机现场）：先收，再滚到底，最后才判断「界面变了」。
        for _ in range(3):
            if placeholder in self.dump_texts():
                break
            self.adb("shell", "input", "keyevent", "KEYCODE_BACK")
            time.sleep(0.8)
            if self.group_name not in self.dump_texts():
                self.open_group()  # BACK 退出了会话页：回到群里再收
        if placeholder not in self.dump_texts():
            self.adb("shell", "input", "swipe", "540", "900", "540", "1900", "200")
            time.sleep(0.8)
        if not self.tap_text(placeholder):
            raise RuntimeError("找不到输入框占位符（界面变了？把 ui dump 留档核对）")
        time.sleep(0.6)
        # 点候选：单兵找 @e2e（带 @），团队组员找 coder（不带 @，点它插入 @coder）。
        # 界面上领队/单兵席位候选显示 "@name"，团队组员候选显示裸 token（见 ChatScreen
        # 的 teamMemberTargets）—— 两套显示格式，别用一个 want 去套。
        self.adb("shell", "input", "text", "@")
        time.sleep(1.2)
        want = mention if mention else f"@{TOKEN}"

        def candidate():
            return next((n for n in self.dump().iter("node")
                         if (n.get("text") or "").strip() == want), None)

        node = candidate()
        if node is None:
            # 候选面板是**固定高度的 ScrollView**（实测 768×588，只放得下 5 行）：
            # 队伍一大，整队行就落在折叠线以下，a11y dump 里根本没这个节点。
            # 上一版把它当"候选里没有"直接判假红 —— 先面板内向上翻，再找。
            self.adb("shell", "input", "swipe", "420", "1900", "420", "1500", "220")
            time.sleep(0.8)
            node = candidate()
        if node is None:
            raise RuntimeError(f"@ 候选里没有 {want}（滚动一屏后仍无；团队组员候选显示裸 token、不带 @）")
        self.tap_node(node)
        time.sleep(0.6)
        self.type_text(prompt)
        if not self.tap_desc("发送"):
            raise RuntimeError("找不到发送按钮")
        time.sleep(1.0)

    # ---------------------------------------------------------------- 断言

    def cards(self, conv: str, since: int = 0) -> list[dict]:
        """按时间序取卡片，**读 coalesce(card_payload, card_json)**。

        `card_json` 是首帧锚、永不刷新（只读它会把已经 blocked 的卡看成还在 proposed ——
        本脚本第一版就这么错了一次，红在了错的地方）。

        **只读连接的瞬态失败按「本轮无卡」处理，不向上炸**：宿主刚重启的窗口里
        （`-shm`/`-wal` 正被重建/恢复），一次性 `mode=ro` 连接会拿到
        `unable to open database file`（实测第一轮 team 场景就死在这一下，而产品链路
        其实在正常跑）。采样循环每 0.4s 重试一次，它本来就是为容忍瞬态存在的 ——
        异常穿过去等于把「环境没准备好」读成「产品没出卡」。真正的缺陷不受掩盖：
        卡真不出现时 fatal 等待照样超时红。
        """
        con = None
        try:
            con = sqlite3.connect(f"file:{DB}?mode=ro", uri=True)
            rows = list(con.execute(
                "select coalesce(card_payload, card_json) from MessageEntity "
                "where conv_id=? and timestamp>=? and card_json is not null order by timestamp",
                (conv, since)))
        except sqlite3.OperationalError as exc:
            if not getattr(self, "_db_warned", False):
                self._db_warned = True
                print(f"  [warn] 卡片读取暂不可用（{exc}），按本轮无卡处理，等采样循环重试")
            return []
        finally:
            if con is not None:
                con.close()
        out = []
        for (raw,) in rows:
            try:
                out.append(json.loads(raw))
            except (json.JSONDecodeError, TypeError):
                continue
        return out

    def latest_card(self, conv: str, since: int = 0) -> dict | None:
        got = self.cards(conv, since)
        return got[-1] if got else None

    def wait_for(self, what: str, timeout: float, probe, fatal: bool = False):
        deadline = time.time() + timeout
        while time.time() < deadline:
            got = probe()
            if got:
                return got
            time.sleep(0.4)
        if fatal:
            # 关键等待（首张卡 / blocked 卡）超时就判失败：旧代码只 warn 返回 None，
            # 调用点拿 None 继续 downstream 的 180/240s 空等，根因被淹没。
            raise RuntimeError(f"等 {what} 超时（{timeout:.0f}s）")
        print(f"  [warn] 等 {what} 超时（{timeout:.0f}s），继续按现状断言")
        return None

    def watch_card(self, conv: str, since: int, timeout: float) -> list[tuple[str, int]]:
        """按 0.4s 采样卡片状态 —— **状态序列本身就是判据**（不是同一张卡的三个样）。

        卡是覆盖发布的，历史版本不落库（只存 last payload + 首帧锚），所以这条断言必须
        **在运行期间**采，事后读库只能看到终局。
        """
        seen: list[tuple[str, int]] = []
        deadline = time.time() + timeout
        while time.time() < deadline:
            card = self.latest_card(conv, since)
            if card:
                pair = (str(card.get("state") or ""), int(card.get("progress") or 0))
                if not seen or seen[-1] != pair:
                    seen.append(pair)
                # review_ready 是团队责任链的终态（审查通过待合并），和 done 一样要停采样：
                # 不加它，团队链会在这里空等到超时（实测多耗 4 分钟，状态其实早就采到了）。
                if pair[0] in ("failed", "blocked", "review_ready") or (pair[0] == "done" and pair[1] >= 100):
                    break
            time.sleep(0.4)
        return seen

    def dump_failure_evidence(self, card: dict | None) -> None:
        """非 done 时把「为什么」摆到台面上：卡面 banner + 分类码 + sandbox task.log 尾巴。"""
        if card:
            banner = (card.get("banner") or {}).get("text")
            print(f"  卡面 banner：{banner}")
            print(f"  blockedCode：{card.get('blockedCode')}")
        logs = sorted(glob.glob(os.path.join(SANDBOX_ARTIFACTS, "*", "task.log")),
                      key=os.path.getmtime, reverse=True)
        if logs:
            shutil.copy(logs[0], os.path.join(self.out, "task.log"))
            tail = open(logs[0], errors="ignore").read()[-1500:]
            print(f"  sandbox task.log（{logs[0]}）尾巴：\n{tail}")

    def latest_artifact(self, since_epoch: float) -> str | None:
        """取本轮派单**新产生**的沙箱产物目录（mtime 晚于派单起点）。

        不按「最新」裸取：artifacts 目录里堆着历史产物，误读上一次的 task.log 会假绿
        —— 那正是「两集合都空即通过」的近亲：读到了别人的证据。
        """
        cands = []
        for d in glob.glob(os.path.join(SANDBOX_ARTIFACTS, "*")):
            if os.path.isdir(d) and os.path.getmtime(d) >= since_epoch - 2:
                cands.append(d)
        return max(cands, key=os.path.getmtime) if cands else None

    def patch_containing(self, needle: str, min_epoch: float = 0) -> str:
        """查所有非空 patch.diff，返回第一份含 [needle] 且 mtime 晚于 [min_epoch] 的正文。

        打回轮产物与首轮产物并存时，按**内容特征**找比按 mtime 找更稳 —— mtime 贴着
        打回起点时时间窗会把两份搅在一起。但 [min_epoch] 必须卡住历史跑的残留产物
        （artifacts 目录不在 prepare_scratch 里清），否则上一轮打回的标记会造成假绿。
        空串=没找到，调用方判红。
        """
        for d in sorted(glob.glob(os.path.join(SANDBOX_ARTIFACTS, "*")),
                        key=os.path.getmtime, reverse=True):
            if not os.path.isdir(d) or os.path.getmtime(d) < min_epoch - 2:
                continue
            p = os.path.join(d, "patch.diff")
            if os.path.isfile(p) and os.path.getsize(p) > 0:
                text = open(p, errors="ignore").read()
                if needle in text:
                    return text
        return ""

    def latest_patch_text(self, min_epoch: float, max_epoch: float = float("inf")) -> str:
        """取时间窗内**最新非空** patch.diff 正文；team 两棒里 reviewer patchless，按 mtime 挑。

        取不到返回空串 —— 调用方必须把空串判红，绝不拿空集当通过（本仓的高频假绿形态）。
        """
        best, best_mt = "", -1.0
        for d in glob.glob(os.path.join(SANDBOX_ARTIFACTS, "*")):
            if not os.path.isdir(d):
                continue
            mt = os.path.getmtime(d)
            if mt < min_epoch - 2 or mt >= max_epoch + 2:
                continue
            p = os.path.join(d, "patch.diff")
            if os.path.isfile(p) and os.path.getsize(p) > 0:
                if mt > best_mt:
                    best_mt = mt
                    best = open(p, errors="ignore").read()
        return best

    def weather_artifact_checks(self, artifact_dir: str | None) -> None:
        """天气场景专属：断言产物里真有 Open-Meteo provider 代码 + 测试绿 + live 温度。

        证据取自 worker 真实执行的产物（task.log / patch.diff / files），
        不是从脚本自己的变量里读 —— 断言取被测对象输出，不是自证。

        证据分两层，按 worker 选：
        * **通用层（stub + 真 codex 都要过）**：harvest 割出的 patch.diff 里真有
          Open-Meteo 接入代码。真 codex 不会替身那样打 `E2E_EVIDENCE` 标记，
          但它的代码产物会被 harvest 割进 patch —— 这才是两种 worker 的公约数。
        * **替身专有层（仅 stub）**：`E2E_EVIDENCE ...` 行里的 tests_pass / live_temp，
          这是确定性替身特意埋的自证标记，真 CLI 不会输出，不能拿去断言真 worker。
        """
        text = ""
        patch = ""
        if artifact_dir:
            log = os.path.join(artifact_dir, "task.log")
            if os.path.exists(log):
                text = open(log, errors="ignore").read()
            diff = os.path.join(artifact_dir, "patch.diff")
            if os.path.exists(diff):
                patch = open(diff, errors="ignore").read()
        # 不查 files/ 快照目录：真机沙箱 harvest 只留 task.log + patch.diff + report.json，
        # files/ 是本机早期 artifact 的结构，拿它当判据会假红。代码证据从 patch.diff 取。

        # 通用层：代码产物证据（对 stub 与真 CLI 都成立）。
        # 只看 patch.diff 正文，绝不叠加 task.log：替身把 prompt 和 "Open-Meteo"
        # 字样整句回声进 task.log，patch 为空时叠加文本也会绿（结构性假绿）。
        self.check(bool(re.search(r"open[-_ ]?meteo|OpenMeteo", patch, re.I)),
                   "harvest 产物里真有 Open-Meteo 接入代码",
                   "patch.diff 里找不到 open-meteo 字样 ⇒ 产物不是天气实现", fatal=True)

        if self.worker != "stub":
            print("  [info] 真 CLI worker：跳过替身专有的 E2E_EVIDENCE 标记断言，"
                  "产物判据以上面的 patch 证据 + 主流程非空 harvest 为准")
            return

        # 替身专有层：确定性替身埋的自证标记
        self.check("E2E_EVIDENCE provider_written=1" in text,
                   "派单产出真的写了 Open-Meteo provider",
                   "task.log 里没有 provider_written=1（替身没命中分支？）")
        # tests_pass 与 provider_written 在同一行（替身的一条 E2E_EVIDENCE 里带两个标记），
        # 所以这里不能写死 "E2E_EVIDENCE tests_pass=" 紧邻 —— 用 \b 定位到标记本身。
        m = re.search(r"\btests_pass=(\d+)", text)
        self.check(bool(m) and int(m.group(1)) > 0,
                   "新增 provider 的测试全绿",
                   f"tests_pass={m.group(1) if m else '(无)'}")
        if not self.offline:
            live = re.search(r"\blive_ok=1\s+live_temp=([-\d.]+)", text)
            self.check(bool(live), "live Open-Meteo 调用拿到真实温度",
                       f"live_temp={live.group(1) + ' C' if live else '(无，检查沙箱网络档)'}")

    def scenario_dispatch(self) -> None:
        if self.scenario == "weather":
            label = "天气：@员工 接入 Open-Meteo ⇒ 新 provider + 测试绿 + live 温度"
        elif self.scenario == "planner":
            label = "主理人裁决：@团队名(整队) ⇒ Planner 定形状（source=llm）⇒ 链或单棒"
        elif self.scenario == "team":
            label = "团队：@团队名(整队) ⇒ 责任链 worker(codex) → reviewer(dsh) → review_ready"
        else:
            label = "场景 5：群内 @员工 一句话 ⇒ 卡片 proposed → working → 终态"
        print(f"== {label}")
        artifact_epoch = time.time()  # 派单起点：之后的新产物才算本轮的
        self.open_group()
        since = int(time.time() * 1000) - 1000
        if self.scenario == "weather":
            prompt = WEATHER_PROMPT
        elif self.team_fixture:
            prompt = TEAM_PROMPT
        else:
            prompt = "add hello to README"
        # 团队场景点@团队名（整队入口 ⇒ 编译责任链），其余场景 @ 单兵 token。
        self.send_mention(prompt, mention=TEAM_MENTION if self.team_fixture else None)
        conv = f"group:{self.group_id}"
        first = self.wait_for("首张卡", 90, lambda: self.latest_card(conv, since), fatal=True)
        self.check(bool(first), "派单后群里出现了卡片",
                   f"首帧 state={first.get('state') if first else None}", fatal=True)
        # proposed = 「团队已就位，8s 后自动开工」。**不点按钮**：倒计时是宿主协程里的
        # `delay(TEAM_COUNTDOWN_MS)`，不是 UI 帧驱动的 —— 让它自己走完，才是产品路径。
        seen = self.watch_card(conv, since, 240 if self.team_fixture else 180)
        print(f"  状态序列：{seen}")
        states = [s for s, _ in seen]
        if self.scenario == "team":
            # 团队责任链的状态机是 executing → review_ready（不是单兵的 proposed→working）：
            # 责任链没有 8s 点将倒计时，编译完直接 executing。套单兵状态名会假红。
            self.check("executing" in states, "执行期（executing）被观测到", f"seq={states}")
        elif self.scenario == "planner":
            # 0 审查棒合法 ⇒ 单棒（proposed→working）；2 棒 ⇒ 链（executing）。
            # 两档都合法，断言「有执行期」，不替主理人选一条形状。
            self.check(("executing" in states) or ("working" in states),
                       "执行期被观测到（链=executing，单棒=working）", f"seq={states}")
        else:
            self.check("proposed" in states, "点将期（proposed）被观测到", f"seq={states}")
            self.check("working" in states, "执行期（working）被观测到",
                       f"seq={states}；替身的 sleep 就是为了让这一档可观测")
        terminal = seen[-1] if seen else ("", 0)
        # 非成功终态先把「为什么」留证，再做致命断言 —— fatal 检查会短路上抛，
        # 证据必须赶在它之前落盘。
        if terminal[0] not in ("review_ready", "done"):
            self.dump_failure_evidence(self.latest_card(conv, since))
        # 成功终态有两档：`review_ready`（有产物，等机主合并 —— 渲染层也画成已完成）
        # 与 `done`（零补丁但有正文）。把「等合并」当成失败是错的：那条路正是设计。
        self.check(terminal[0] in ("review_ready", "done") and terminal[1] >= 100,
                   "卡片终局是成功态且 100%", f"终局={terminal}", fatal=True)
        log = open(os.path.join(self.out, "host.log"), errors="ignore").read()
        # 产物面：以本轮 patch.diff 真文件为准（fail-closed）—— 日志行只作附加读数。
        # 旧判据只看日志里 "harvest ... +N -M in K file" 且 K>0，那行可能是上一轮的，
        # 本轮没割出产物也能绿。取本轮产物目录用派单起点的 artifact_epoch，找不到
        # 产物目录 / patch.diff 一律判失败，不拿日志行数当产物证据。
        patches = re.findall(r"harvest .*?\+(\d+) -(\d+) in (\d+) file", log)
        # 团队责任链有两棒：worker 造 patch、reviewer 按设计不写文件（见
        # stub-reviewer-cli.sh）。latest_artifact 按 mtime 取最新目录，team 场景最新那个
        # 通常是 reviewer 棒，裸取会把本轮真实产物误判成空 —— 这是脚本假红，不是产品
        # 没割出产物。取本轮内**最新的非空** patch.diff：单兵/天气只有一个产物目录，
        # 语义与原来完全一致；team 则跳过 patchless 的审查棒，落到 worker 那棒。
        patch_path = ""
        patch_bytes = 0
        for d in sorted(
            (p for p in glob.glob(os.path.join(SANDBOX_ARTIFACTS, "*"))
             if os.path.isdir(p) and os.path.getmtime(p) >= artifact_epoch - 2),
            key=os.path.getmtime, reverse=True,
        ):
            candidate = os.path.join(d, "patch.diff")
            if os.path.isfile(candidate) and os.path.getsize(candidate) > 0:
                patch_path = candidate
                patch_bytes = os.path.getsize(candidate)
                break
        self.check(patch_bytes > 0,
                   "产物真被收割（本轮 patch.diff 非空）",
                   f"patch={patch_path or '(本轮无产物目录)'} 字节数={patch_bytes}；"
                   f"harvest 行={patches[-1] if patches else '(无)'}", fatal=True)
        if self.scenario == "team":
            # 团队责任链走 StageChain，驱动解析证据是 [CHAIN] 日志，不打单兵的
            # [OCGO-BRIDGE] invoke 行 —— 拿单兵判据来验团队会假红。
            # **终态卡先于 [CHAIN] 汇总行落盘**（实测：rev=6 acked=true 在前，
            # "all reviewers passed" 在后两行）—— 采样循环看到 review_ready 就返回，
            # 此刻读日志可能还没有汇总行。给这个次序差一个有界重试窗，不是赌固定 delay。
            deadline = time.time() + 10
            chain_ok = False
            while time.time() < deadline:
                log = open(os.path.join(self.out, "host.log"), errors="ignore").read()
                if "[CHAIN]" in log and "reviewers passed" in log:
                    chain_ok = True
                    break
                time.sleep(0.5)
            self.check(chain_ok, "责任链审查棒全部通过（[CHAIN] 日志）",
                       "" if chain_ok else "日志里没有 [CHAIN] ... reviewers passed ⇒ 责任链没跑完")
        elif self.scenario == "planner":
            # 形状由裁决定：链走 StageChain（[CHAIN]），0 审查棒走单棒（invoke）。
            # 只要求「其中一条真的跑过」，不许两条都没有 —— 那是"派了但没跑"。
            # 与 team 同一条次序差：终态卡先落库，[CHAIN] 汇总行晚两行 —— 有界重读。
            deadline = time.time() + 10
            while time.time() < deadline:
                log = open(os.path.join(self.out, "host.log"), errors="ignore").read()
                if "[CHAIN]" in log or "[OCGO-BRIDGE] invoke" in log:
                    break
                time.sleep(0.5)
            ran_chain = "[CHAIN]" in log
            ran_single = "[OCGO-BRIDGE] invoke" in log
            self.check(ran_chain or ran_single,
                       "裁决之后的执行面真的跑过（链或单棒）",
                       f"chain={ran_chain} single={ran_single}；两者皆无 ⇒ 派单没落地")
        else:
            # 单兵 @员工 走编排器工单（DesktopWorkerOrchestrator），不再经 DesktopExtension
            # 的 invoke 执行器 —— 那条 `[OCGO-BRIDGE] invoke` 日志在这条链上根本不产生
            # （CLI 派单已移交编排器，老断言因此永远假红）。执行面证据改为「本轮
            # task.log 有替身回声」：驱动真的被调起并留下了产物行。身份键解析错了的话
            # sandbox 都建不出来，task.log 不存在。
            ran = any(
                "stub worker:" in open(a, errors="ignore").read()
                for a in glob.glob(os.path.join(SANDBOX_ARTIFACTS, "t-*", "task.log"))
                if os.path.getmtime(a) >= artifact_epoch - 2
            )
            self.check("cliDriver=null" not in log,
                       "日志里没有 cliDriver=null（身份键/配置键仍混用的那行不在）",
                       "日志里出现 cliDriver=null ⇒ 身份键/配置键仍混用")
            self.check(ran, "编排器真的调起了驱动（本轮 task.log 有替身回声）",
                       "本轮产物目录里没有带 stub worker 回声的 task.log ⇒ 派单没落到驱动")
        if self.scenario == "weather":
            self.weather_artifact_checks(self.latest_artifact(artifact_epoch))
        if self.scenario == "team":
            self._team_chain_checks(conv, since, terminal, log, artifact_epoch)
        if self.scenario == "planner":
            self._planner_checks(log, artifact_epoch)

    def _team_chain_checks(self, conv: str, since: int, terminal: tuple, log: str,
                           artifact_epoch: float) -> None:
        """团队专属断言：真的走了责任链（不是单兵直派冒充），且两棒各司其职。

        判据取被测对象的输出：
          * 卡面 `items[]` 两棒（worker + reviewer）—— 责任链的结构证据；
          * 终态必须 `review_ready`（团队链必经审查档），`done` 说明根本没进链；
          * task.log 里 worker 与 reviewer 两个替身各留一句 agent_message；
          * reviewer 打出 VERDICT: PASS（审查棒真的判了，不是没跑）。
        """
        card = self.latest_card(conv, since) or {}
        items = card.get("items") or []
        self.check(len(items) >= 2, "卡面责任链有两棒（worker + reviewer）",
                   f"items 长度={len(items)}；单兵直派只有 1 段、不会进责任链")
        self.check(terminal[0] == "review_ready",
                   "团队链终局是 review_ready（审查通过待合并）",
                   f"终局={terminal}；团队链必经审查档，done 通常是没进链/零补丁短路")
        # 审查棒的 VERDICT 与两棒的 worker/reviewer 回声，取自本轮所有 task.log。
        # 按派单起点的 artifact_epoch 精确过滤：旧判据用「15 分钟内」的魔法数，
        # 上一轮 15 分钟内跑过 team 时，旧 task.log 也能满足 worker/reviewer/verdict
        # ⇒ 假绿。mtime 早于本轮起点的一律不算证据。
        verdict = False
        worker_said = reviewer_said = False
        for art in glob.glob(os.path.join(SANDBOX_ARTIFACTS, "t-*", "task.log")):
            if os.path.getmtime(art) < artifact_epoch:
                continue
            txt = open(art, errors="ignore").read()
            verdict |= "VERDICT: PASS" in txt
            worker_said |= "stub worker:" in txt
            reviewer_said |= "reviewer stub:" in txt
        self.check(worker_said, "worker 棒真的执行并留了说法",
                   "" if worker_said else "task.log 里没有 stub worker 回声")
        self.check(reviewer_said, "reviewer 棒真的执行并留了说法",
                   "" if reviewer_said else "task.log 里没有 reviewer stub 回声")
        self.check(verdict, "审查棒打出 VERDICT: PASS",
                   "" if verdict else "责任链没有拿到审查通过判据")

    # ---------------------------------------------------------------- planner 场景

    def planner_credential_source(self) -> str | None:
        """主理人裁决要一份 DeepSeek 凭证。

        两处来源与产品侧 `DshCredentials` 同序（显式 env > `~/.dsh/.credentials.yaml`）：
        脚本按同一顺序探，探不到就直接说清，不让 Planner 静默回落到编译链。
        """
        if (os.environ.get("DEEPSEEK_API_KEY") or "").strip():
            return "env:DEEPSEEK_API_KEY"
        cred = os.path.expanduser("~/.dsh/.credentials.yaml")
        if not os.path.isfile(cred) or os.path.getsize(cred) == 0:
            return None
        for line in open(cred, errors="ignore"):
            if line.strip().startswith("DEEPSEEK_API_KEY:"):
                value = line.split(":", 1)[1].strip()
                return cred if value else None
        return None

    def latest_plan_record(self, since: float) -> str | None:
        """本轮新落盘、且带 `planDecision` 的飞行记录路径。

        取**产品自己落的盘**（`flight-records/<taskId>.json`）而不是日志：
        日志会轮转，记录才是事后复核的持久证据。
        """
        for p in sorted(glob.glob(os.path.join(DATA_DIR, "flight-records", "*.json")),
                        key=os.path.getmtime, reverse=True):
            if os.path.getmtime(p) < since:
                continue
            try:
                rec = json.load(open(p))
            except (OSError, json.JSONDecodeError):
                continue
            if rec.get("planDecision"):
                return p
        return None

    def _planner_checks(self, log: str, artifact_epoch: float) -> None:
        """**裁决真的被采纳了**，不是回落编译链。

        棒数在这里没有分辨力 —— 回落编译链同样出两棒，旧判据会被"两次都回落"骗过。
        判据必须是**决策来源**，而且要两条：
          * 宿主日志 `PLAN … source=llm`：过程证据（本轮日志，派单那一刻写的）；
          * 飞行记录 `planDecision.source == "llm"`：持久证据（可事后复核）。
        只留一条时，「记录被下一条覆盖」「日志轮转」都能把假绿藏起来。
        """
        plan_lines = [ln.strip() for ln in log.splitlines() if "source=" in ln and "PLAN" in ln]
        source_is_llm = bool(re.search(r"\bsource=llm\b", log))
        self.check(source_is_llm,
                   "宿主日志里裁决来源是 llm",
                   f"日志里没有 source=llm ⇒ 这一单走了回落编译链。PLAN 行={plan_lines[-1] if plan_lines else '(无)'}"
                   "；检查 DEEPSEEK_API_KEY / 网络 / 形状门是否把建议否掉了")
        path = self.wait_for("带 planDecision 的飞行记录", 45,
                             lambda: self.latest_plan_record(artifact_epoch))
        plan = {}
        if path:
            shutil.copy(path, os.path.join(self.out, os.path.basename(path)))
            plan = (json.load(open(path)) or {}).get("planDecision") or {}
        self.check(plan.get("source") == "llm",
                   "飞行记录的 planDecision.source 是 llm",
                   f"实际 source={plan.get('source')!r}；记录={os.path.basename(path) if path else '(本轮没有带 planDecision 的记录)'}")
        self.check(bool(plan.get("workerAgentId")),
                   "记录里写下了这一单下场的承办人",
                   f"planDecision={plan}；点将之后承办人与锚点可能不同，这正是要留的证")
        # 0 审查棒与 N 棒都合法：只报读数，不断言形状。
        print(f"  [info] planDecision = {json.dumps(plan, ensure_ascii=False)}")

    # ---------------------------------------------------------------- review_ready 打回

    def scenario_reject_revision(self) -> None:
        """在 review_ready 卡上真机点「打回」→ 抽屉填反馈 → 提交，验证宿主接住。

        判据（区分「真重派」与「翻 stale」）：
        · 断的链路：宿主查不到工单 → `flipStaleChainCard` → **同一个 cardId** 翻成
          aborted + hint banner，不会有新卡。
        · 通的链路：`ChainRetry.reject` 按注册表同形状重派 → **新 chainId → 新 cardId**，
          新卡走 executing→review_ready。
        所以「出现一张 cardId 不同、state 非 aborted 的新卡」就是打回被接住的直接证据。
        """
        if not self.team_fixture:
            print("  [skip] 打回验证只在团队链场景（review_ready 卡）执行")
            return
        print("== 场景 7：review_ready 卡点「打回」⇒ 抽屉填反馈 ⇒ 宿主重派新链")
        conv = f"group:{self.group_id}"
        # 打回前的终态卡（scenario_dispatch 刚跑完，最新就是那张 review_ready）。
        before = self.latest_card(conv)
        old_id = str((before or {}).get("cardId") or "")
        self.check(before is not None and before.get("state") == "review_ready" and old_id,
                   "打回前群里有一张 review_ready 终态卡", f"cardId={old_id} state={(before or {}).get('state')}",
                   fatal=True)
        since = int(time.time() * 1000) - 1500
        # P1 基线：打回前的首轮产物**不该**带反馈标记。取的是打回起点之前的最后一份
        # patch.diff（min_epoch=0, max_epoch=since/1000），与新链产物时间窗隔开。
        old_patch = self.latest_patch_text(0, since / 1000)
        revision_mark = "revision applied: human rejection feedback honored"
        self.check(revision_mark not in old_patch,
                   "首轮产物不含反馈标记（对照组干净）",
                   f"首轮 patch {len(old_patch)}B；意外含标记 ⇒ 对照组失效")

        # 真机交互：点「打回」按钮 → 抽屉展开 → 点「补充单测」芯片填反馈 → 点「提交打回」。
        # 失败时留 UI dump，别盲改文案（设备 locale=zh-Hans-CN）。
        if not self.tap_text("打回"):
            with open(os.path.join(self.out, "ui-reject-button.xml"), "w") as f:
                f.write(self.adb("exec-out", "cat", "/sdcard/e2e-ui.xml", check=False))
            self.check(False, "找到 review_ready 卡上的「打回」按钮", "UI dump 已留 ui-reject-button.xml",
                       fatal=True)
        time.sleep(0.6)
        if not self.tap_text("补充单测"):
            with open(os.path.join(self.out, "ui-reject-drawer.xml"), "w") as f:
                f.write(self.adb("exec-out", "cat", "/sdcard/e2e-ui.xml", check=False))
            self.check(False, "打回抽屉里的「补充单测」反馈芯片", "UI dump 已留 ui-reject-drawer.xml",
                       fatal=True)
        time.sleep(0.4)
        if not self.tap_text("提交打回"):
            with open(os.path.join(self.out, "ui-reject-submit.xml"), "w") as f:
                f.write(self.adb("exec-out", "cat", "/sdcard/e2e-ui.xml", check=False))
            self.check(False, "打回抽屉里的「提交打回」按钮", "UI dump 已留 ui-reject-submit.xml",
                       fatal=True)

        # 等新链卡：cardId 不同（新 chainId）且 state 非 aborted。stale 翻面是同 cardId，
        # 不会出现在这里 —— 这就是「接住 vs 翻 stale」的分水岭。
        def new_chain_card():
            return [
                c for c in self.cards(conv, since)
                if str(c.get("cardId") or "") != old_id
                and str(c.get("state") or "") != "aborted"
            ]

        got = self.wait_for("打回后的新链卡", 240, new_chain_card, fatal=True)
        new_id = str((got[-1] or {}).get("cardId") or "") if got else ""
        self.check(bool(got) and new_id != old_id,
                   "打回被宿主接住：重派出新 chainId 的卡（不是把旧卡翻 stale）",
                   f"旧={old_id} 新={new_id} 状态={(got[-1] or {}).get('state') if got else '(无卡)'}")

        # 等新链跑到终态再交还控制权：否则紧随其后的「收回目录授权」场景派单时，
        # 这条还在跑的新链会被算进它的 since 窗口，干扰 blocked 卡断言。
        def new_chain_terminal():
            return [
                c for c in self.cards(conv, since)
                if str(c.get("cardId") or "") == new_id
                and (str(c.get("state") or "") == "review_ready"
                     or (str(c.get("state") or "") == "done" and int(c.get("percent") or 0) >= 100))
            ]

        terminal = self.wait_for("打回新链的终态", 240, new_chain_terminal)
        self.check(bool(terminal),
                   "打回重派的链跑到 review_ready 终态",
                   f"新链 {new_id} state={(terminal[-1] or {}).get('state') if terminal else '(无)'}")
        last = terminal[-1] if terminal else (got[-1] if got else {})
        banner = str((last.get("banner") or {}).get("text") or "")
        print(f"  新链卡：cardId={new_id} state={last.get('state')} banner={banner[:80]}")

        # P1（反馈效果真绿）：打回轮的 patch.diff 必须带上反馈标记 —— 只证明「反馈被
        # 注入 instruction」不够（stub 首轮/反馈轮产物一模一样时会造成假绿）。产物是
        # 被测对象输出，标记来自 stub 对 "HUMAN REJECTION FEEDBACK" 前置段的识别。
        # 不按时间窗取新链产物：首轮 patch 的 mtime 可能贴着打回起点，时间窗 ±2s 容差
        # 会把两份产物搅在一起。改用**内容特征**筛——标记串只可能来自打回轮（首轮
        # 对照组已断言干净），全窗口找带标记的那份，找到即反馈真影响了产物。
        deadline = time.time() + 30
        new_patch = ""
        while time.time() < deadline:
            new_patch = self.patch_containing(revision_mark, since / 1000)
            if new_patch:
                break
            time.sleep(0.5)
        self.check(bool(new_patch),
                   "打回反馈真的影响了产物（新链 patch 带反馈标记，非首轮原样复制）",
                   f"新链 patch {len(new_patch)}B 含反馈标记；"
                   f"首轮 {len(old_patch)}B 含标记={revision_mark in old_patch}；"
                   f"新链 agent_message 回声见 host.log")

        # P0：旧链卡必须就地翻成 aborted（BoardCardFlip.stale 摘掉 actions），否则它与
        # 新卡同写「等你合并或打回」，用户点旧卡的「采纳并应用修改」会合入已被打回的
        # 旧分支。判据只认同一个 cardId 变成 aborted —— 新卡另起 cardId，混不进来。
        def old_card_flipped():
            return [
                c for c in self.cards(conv, since)
                if str(c.get("cardId") or "") == old_id
                and str(c.get("state") or "") == "aborted"
            ]

        flipped = self.wait_for("旧链卡翻面", 60, old_card_flipped)
        self.check(bool(flipped),
                   "打回后旧链卡翻成 aborted（摘掉合并按钮，不会误合旧补丁）",
                   f"旧卡 {old_id} state={(flipped[-1] or {}).get('state') if flipped else '(未翻面)'}")
        old_banner = str(((flipped[-1] or {}).get("banner") or {}).get("text") or "") if flipped else ""
        print(f"  旧链卡：cardId={old_id} state=aborted banner={old_banner[:80]}")

    def scenario_dir_not_allowed(self, dir_id: str) -> None:
        print("== 场景 6：收回目录授权再派一次 ⇒ blocked 卡带 DIR_NOT_ALLOWED")
        self.http("POST", "/v1/host/whitelist", {"dirId": dir_id, "allowed": False})
        try:
            self.open_group()
            since = int(time.time() * 1000) - 1000
            # 团队场景没有单兵 @e2e 席位，用 @团队名（整队入口）重派；其余场景 @e2e。
            self.send_mention("again", mention=TEAM_MENTION if self.team_fixture else None)
            conv = f"group:{self.group_id}"
            got = self.wait_for("blocked 卡", 90, lambda: [
                c for c in self.cards(conv, since) if c.get("state") == "blocked"
            ], fatal=True)
            if self.scenario == "planner":
                # planner 场景可能落在链上（banner 指 workspace），也可能被裁决成
                # 0 审查棒的单棒（带 DIR_NOT_ALLOWED 分类码）—— 两档都合法，判
                # 「至少说出了是目录问题」，不替它选一条路。
                banners = " ".join(str((c.get("banner") or {}).get("text") or "")
                                   for c in (got or []))
                codes = [c.get("blockedCode") or "" for c in (got or [])]
                self.check(bool(got) and (
                    "DIR_NOT_ALLOWED" in codes
                    or "workspace" in banners.lower()
                    or "directory" in banners.lower()
                ), "收回授权后出 blocked 卡并指明目录/workspace 问题",
                    f"code={codes or '(无)'} banner={banners[:120] or '(无)'}")
            elif self.scenario == "team":
                # 团队路径的目录拒绝在 `profileForSeat` 阶段就 fail-closed，出的是通用
                # blocked 卡（banner "chain executor unavailable or no workspace bound"），
                # **不带 blockedCode** —— 带 DIR_NOT_ALLOWED 分类码的是单兵退单卡那条分支。
                # 这里按团队路径的真实语义断言：出 blocked 卡 + banner 点明 workspace 问题。
                banners = " ".join(str((c.get("banner") or {}).get("text") or "")
                                   for c in (got or []))
                self.check(bool(got) and ("workspace" in banners.lower() or "directory" in banners.lower()),
                           "团队路径收回授权后出 blocked 卡且 banner 指明 workspace",
                           f"banner={banners[:120] or '(无)'}")
            else:
                codes = [c.get("blockedCode") or "" for c in (got or [])]
                self.check("DIR_NOT_ALLOWED" in codes, "blocked 卡带 DIR_NOT_ALLOWED 分类码",
                           f"实际拿到的 code={codes or '(无)'}")
        finally:
            # 无论等待/断言是否抛异常，都必须放开白名单：旧代码把 allowed=True 放在
            # 正常尾路径，中间一抛异常机主的目录就永久停在 allowed=False（状态污染）。
            self.http("POST", "/v1/host/whitelist", {"dirId": dir_id, "allowed": True})

    # ---------------------------------------------------------------- 收尾

    def teardown(self) -> None:
        self.adb("shell", "svc", "power", "stayon", "false", check=False)
        self.restore_fixture()
        if not self.keep_host:
            self.host_down()
        print(f"\n证据目录：{self.out}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL", ""))
    ap.add_argument("--group", default="e2e-dispatch")
    ap.add_argument("--worker", choices=("stub", "codex"), default="stub")
    ap.add_argument("--scenario", choices=("hello", "weather", "team", "planner"), default="hello",
                    help="hello=改 README 验编排链；weather=接入 Open-Meteo 真实天气 API；"
                         "team=@团队名走责任链(worker+reviewer)→review_ready；"
                         "planner=同 team 的夹具，额外断言裁决来源是 llm（不是回落编译链）")
    ap.add_argument("--offline", action="store_true",
                    help="weather 场景：跳过 live Open-Meteo 探针（完全离线复跑，不断言真实温度）")
    ap.add_argument("--reject", action="store_true",
                    help="team 场景：review_ready 卡上真机点「打回」并验宿主重派新链（本次改动的核心出口）")
    ap.add_argument("--keep-host", action="store_true")
    args = ap.parse_args()

    serial = args.serial
    if not serial:
        out = subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout
        devices = [l.split()[0] for l in out.splitlines()[1:] if l.strip().endswith("device")]
        if len(devices) != 1:
            print(f"需要恰好一台在线的真机（当前 {devices}）；用 --serial 指定")
            return 2
        serial = devices[0]

    e2e = E2e(serial, args.group, args.keep_host, args.worker,
              scenario=args.scenario, offline=args.offline, reject=args.reject)
    try:
        e2e.prepare_scratch()
        e2e.preflight()
        e2e.host_down()
        e2e.create_group_if_absent()
        e2e.seed_fixture()
        e2e.host_up()
        dir_id = e2e.bind_project_directory()
        e2e.dir_id = dir_id  # 供 teardown 里团队执行档的精确清理
        if e2e.team_fixture:
            e2e.write_team_execution(dir_id)
        e2e.scenario_dispatch()
        if args.reject:
            e2e.scenario_reject_revision()
        e2e.scenario_dir_not_allowed(dir_id)
        # 成功态也留末帧 UI：视觉排布评估不依赖「恰好失败」这一次运气。
        e2e.save_ui_final()
    except Exception as exc:  # 任何一步崩了都要留证，不要静默
        e2e.check(False, "脚本执行", repr(exc))
        e2e.save_ui_final()
    finally:
        e2e.teardown()

    if e2e.failures:
        print("\n未通过：")
        for f in e2e.failures:
            print("  - " + f)
        return 1
    print("\n全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
