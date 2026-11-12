#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""SDLC 生命周期 harness —— 用真实 agent 在真实项目上跑完整研发周期。

被测对象不是 dsh 本身，而是「榫卯把活派给 agent、agent 真改代码、改动可验收」这条链。
目标项目是本机真实工程（默认 ~/weather-relay，含单测与 git 历史）。

## 为什么不复用 scripts/e2e-team-pipeline.py

那份五幕指挥器依赖真人建群/点手机，判据只有「本次新增目录里出现 >200B 的
patch.diff」——它证明的是「沙箱跑完了一轮」，不是「这次改动是对的」。这里每一幕
的判据都取被测对象的输出：补丁内容、测试读数、工作树、进程树、卡片库。

## 两条链必须分开判（这是本脚本存在的主要理由）

- `cli-direct`：`POST /v1/agent/hook/dispatch` → `executeCliTask` → 真 CLI →
  WorktreeSandbox → harvest patch.diff。**真 agent、真沙箱、真代码**，但按控制面
  源码注释，它「绕过群座席」，`DesktopWorkerOrchestrator` 不在场 ⇒ 工单卡、追问唤醒、
  自愈退单、合并按钮这一整层它证明不了。
- `orchestrator`：手机 `@` 派单 → `invokeExecutor` → `handlePromptAwait` →
  `DesktopWorkerOrchestrator` → 卡片/追问/自愈/合流。这才是产品的完整链。

headless tier 跑前者，orchestrator 专属幕**如实标 N/A，不报绿**。拿 202 Accepted、
截图或「宿主日志里出现过字样」当通过，都是本脚本明确禁止的假信号。

## 多 agent 扩展点（本轮只跑单 dsh，结构已留好）

`--agent` 可重复传，形成 AgentSpec 列表；每幕的 `agent_of()` 决定派给谁。扩展成 team
只需两步：给幕指定不同 agent，并把串行 `dispatch()` 换成 `/v1/agent/chain` 的责任链
受理（该路由已在宿主实现）。判据函数完全不用改——它认的是产物，不是谁产的。

## 判据纪律

1. 只认**本次新增**的沙箱产物目录（派单前拍快照，历史 t-1/t-2 不参与判定）。
2. `patch.diff` 的大小只作下限（防空跑），真判据是内容：`diff --git` 文件清单、
   实现/测试文件齐备、增删行数。
3. 故障注入幕额外判「注入的失败测试没有被删掉」——删测试把红变绿不算修好。
4. 跑前要求目标仓工作树干净；默认**不自动回滚**，收尾列出脏文件；显式 `--reset`
   才还原到跑前 HEAD。
5. 每幕读数、环境元数据、链型、PASS/FAIL/N/A 全部落盘到证据目录。

## 可反复验收：阶段锚点 + 隔离重放

被测仓的 git 历史本身就是生命周期锚点链，`--stage` 把每一档钉成**固定 sha**：

| stage | 锚点 | 起点内容 |
| --- | --- | --- |
| empty | 3792076 | 只有 package.json（连 src/ 和 test/ 都没有） |
| requirements | 464ee0b | + docs/PRD.md |
| prototype | 6bfd000 | + 可点原型 / 设计 / 任务分解 |
| implemented | 87f6089 | + 实现与 9 个测试文件 |
| current | — | 不检出，原地用 --workspace（迭代/回归档） |

`stage != current` 时每轮从只读源仓 `--source` **clone 出全新隔离工作区**
（`/tmp/sdlc-runs/<session>/<run-N>/ws`），源仓绝不 reset、绝不写入；
每轮的补丁、装置提交、测试都只活在自己那一份检出里。`--repeat N` 连续重放 N 轮，
汇总层另判五条幂等判据：同锚点 / 工作区互异 / 判据形状一致 / 每轮全绿 / 源仓仍干净。

`a1..a4` 是「从无到有」四幕（需求 → 可点原型 → 实现+单测 → 阶段验收），每幕把补丁
apply+commit 进本轮检出，下一幕的沙箱基线才看得见上一幕成果；证据键刻意叫
`bootstrap_patch` 而不是 `patch`，避免合流幕把已落地补丁再 apply 一次造成假红。

## 跑法

    python3 scripts/sdlc-lifecycle.py --dry-run
    python3 scripts/sdlc-lifecycle.py --preflight-only
    # 从空仓反复重放完整生命周期（真 dsh，两轮）
    python3 scripts/sdlc-lifecycle.py --stage empty --plan from-zero --repeat 2 --timeout 360
    # 只重放「空仓 → 需求 → 原型」短链，用于快速回归
    python3 scripts/sdlc-lifecycle.py --stage empty --plan bootstrap --repeat 3
    # 从已有原型/实现接着验，或从当前 HEAD 迭代
    python3 scripts/sdlc-lifecycle.py --stage prototype --plan from-zero
    python3 scripts/sdlc-lifecycle.py --stage current --plan iterate --act 0,1,2,3,5,6
    # 真机链（orchestrator 幕需要人守门）
    python3 scripts/sdlc-lifecycle.py --tier real --act 4,i1,i2

退出码：0 全绿 / 1 判据失败 / 2 环境或用法错误。
"""

import argparse
import json
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone

HOST = os.environ.get("TENON_HOST_URL", "http://127.0.0.1:8788").rstrip("/")
TOKEN = os.environ.get("TENON_PLUGIN_TOKEN", "")
ARTIFACTS = os.environ.get("E2E_ARTIFACTS", "/tmp/tenon-ws/artifacts")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

INJECTED_TEST = "test/sdlc-harness-injected.test.js"
REQ_DOC = "docs/SDLC-HARNESS-REQ.md"
FEATURE_IMPL = "src/utils/uvAdvice.js"
FEATURE_TEST = "test/uvAdvice.test.js"

# 宿主沙箱只保证 CLI 进程的 cwd 是一次性 git worktree，**不是强制隔离面**：
# agent 一旦拿到主仓绝对路径就能顺着 `.git` 写回宿主（见
# orchestration/.../DesktopStageChain.kt 的 Escaped write 判据）。首跑就是这么
# 逃逸的 —— prompt 里写了「在 /Users/.../weather-relay 里新增」，dsh 第一条
# 命令便 ls 那个绝对路径，worktree 零改动 ⇒ harvest patchBytes=0。
# 所以 prompt 里**绝不出现目标仓绝对路径**，并把 cwd 纪律写死在每幕前面。
# 收尾「无孤儿 CLI 进程」的匹配串必须与 CliSubprocessDriver.dshArgs 对齐，
# 否则 pgrep 永远命不中 ⇒ 判据恒绿。dry-run 用源码守卫钉住这件事。
CLI_PATTERN = "dsh --profile headless"

RUNS_ROOT = os.environ.get("E2E_RUNS_ROOT", "/tmp/sdlc-runs")

# 天气仓的 git 历史本身就是一条可复现的生命周期锚点链。每档位 = 固定 sha +
# 「此时该有什么 / 不该有什么 / 是否已有可跑单测」。从锚点检出**全新隔离工作区**
# （源仓只读，绝不 reset 主仓），就能从任意阶段反复重放生命周期。
STAGES = {
    "empty":        ("3792076", ["package.json"],
                     ["src", "test", "docs", "prototype.html", "index.html"], False),
    "requirements": ("464ee0b", ["package.json", "docs/PRD.md"],
                     ["src", "test", "prototype.html"], False),
    "prototype":    ("6bfd000", ["package.json", "docs/PRD.md", "prototype.html", "docs/DESIGN.md"],
                     ["src", "test"], False),
    "implemented":  ("87f6089", ["package.json", "src/app.js", "test/forecast.test.js", "prototype.html"],
                     [], True),
    "current":      (None, [], [], True),   # 不检出：原地用 --workspace（迭代/回归档）
}

# 幕序列预设。--act 显式指定时优先于 --plan。
PLANS = {
    # a2r = 原型可评审（人审守门）：紧跟 a2 之后、a3 之前 —— 原型写完就要能评审，
    # 而不是等实现完了再回头看。headless 档它如实标 NA（见 act_a2_review）。
    "from-zero": ["0", "a1", "a2", "a2r", "a3", "a4", "2", "3", "5", "6"],
    "bootstrap": ["0", "a1", "a2", "a2r"],              # 空仓短链，用于反复性验证
    "iterate":   ["0", "1", "2", "3", "5", "6"],
    "full":      ["0", "1", "2", "3", "4", "5", "6", "i1", "i2"],
}

SANDBOX_PREAMBLE = (
    "【执行环境约束，必须遵守】你的当前工作目录（cwd）就是本次任务专用的一次性 "
    "git worktree，里面是**本阶段该有的全部文件**——可能只有 package.json，"
    "不要假定已经存在 src/ 或 test/，需要什么目录就自己创建。"
    "所有文件读写一律使用**相对路径**，只在 cwd 内进行；"
    "禁止使用任何绝对路径，禁止访问或写入宿主主仓库目录，禁止 cd 到别处。"
    "改动只有留在 cwd 的工作树里才会被收割成补丁；写到别处等于本次任务失败。\n"
)


# ---------------------------------------------------------------------------
# 配置与结构
# ---------------------------------------------------------------------------

@dataclass
class AgentSpec:
    key: str                 # 控制面 agentKey
    cli: str                 # preflight 体检用的可执行文件名
    role: str = "worker"     # worker / reviewer —— 多 agent 扩展时的席位角色


@dataclass
class Verdict:
    name: str
    ok: bool
    detail: str = ""


@dataclass
class ActResult:
    act_id: str
    title: str
    chain: str
    status: str = "PENDING"       # PASS / FAIL / NA / ERROR
    verdicts: list = field(default_factory=list)
    evidence: dict = field(default_factory=dict)
    seconds: float = 0.0


@dataclass
class Context:
    args: argparse.Namespace
    agents: list
    out_dir: str
    base_head: str = ""
    base_dirty: set = field(default_factory=set)
    base_tests: dict = field(default_factory=dict)
    results: list = field(default_factory=list)
    device_commits: list = field(default_factory=list)
    created_files: list = field(default_factory=list)
    run_label: str = ""
    anchor: str = ""
    started_ms: int = 0


# ---------------------------------------------------------------------------
# 地面真相读数
# ---------------------------------------------------------------------------

def run_cmd(argv, cwd=None, timeout=120):
    try:
        p = subprocess.run(argv, cwd=cwd, capture_output=True, text=True, timeout=timeout)
        return p.returncode, p.stdout, p.stderr
    except (OSError, subprocess.SubprocessError) as e:
        return 127, "", str(e)


def git_state(ws):
    rc, head, _ = run_cmd(["git", "-C", ws, "rev-parse", "HEAD"])
    _, porcelain, _ = run_cmd(["git", "-C", ws, "status", "--porcelain"])
    return (head.strip() if rc == 0 else ""), set(porcelain.splitlines())


def test_summary_num(text, key):
    """node --test 汇总行的两种形态都要认，结论不许依赖机器上的 Node 版本：

    - TAP 老格式：`# pass 90`（TAP 报告器）
    - spec 报告器（Node 20+ 默认）：`ℹ pass 90`
    两种都没匹配到才返回 None —— 解析不出来一律当失败，不猜。
    """
    m = re.search(r"^#\s*" + key + r"\s+(\d+)", text, re.M)
    if m:
        return int(m.group(1))
    m = re.search(r"^ℹ\s+" + key + r"\s+(\d+)", text, re.M)
    return int(m.group(1)) if m else None


def npm_test(ws, timeout=180):
    """解析 node --test 的汇总行（TAP 与 spec 两种格式，见 test_summary_num）。"""
    rc, out, err = run_cmd(["npm", "test"], cwd=ws, timeout=timeout)
    text = out + "\n" + err
    def num(key):
        return test_summary_num(text, key)
    dur = re.search(r"^[#ℹ]\s*duration_ms\s+([\d.]+)", text, re.M)
    return {
        "rc": rc,
        "tests": num("tests"),
        "pass": num("pass"),
        "fail": num("fail"),
        "skipped": num("skipped"),
        "duration_ms": float(dur.group(1)) if dur else None,
        "tail": text.strip().splitlines()[-8:],
    }


def injected_test_verdict(ws, rel=INJECTED_TEST):
    """显式单文件运行注入测试，不依赖 `npm test` 的默认收集行为。

    从零链路里 agent 自建的 test/ 布局（可能带 index.js）会让 `node --test test/`
    漏收集注入文件，于是「注入没造成红灯」被误判、且沙箱基线里根本没有这个文件。
    判据必须直接指向被测文件本身。
    """
    rc, out, err = run_cmd(["node", "--test", rel], cwd=ws, timeout=120)
    text = out + "\n" + err

    def num(key):
        return test_summary_num(text, key)

    return {"rc": rc, "pass": num("pass"), "fail": num("fail"),
            "skipped": num("skipped"), "tail": text.strip().splitlines()[-6:]}


def parse_patch(path):
    """不靠文件大小判成果：读 diff --git 清单、增删行、实现/测试文件齐备性。"""
    try:
        with open(path, encoding="utf-8", errors="replace") as fh:
            body = fh.read()
    except OSError as e:
        return {"error": str(e), "bytes": 0, "files": []}
    files = re.findall(r"^diff --git a/(\S+) b/(\S+)$", body, re.M)
    names = [b for _, b in files]
    added = len([ln for ln in body.splitlines()
                 if ln.startswith("+") and not ln.startswith("+++")])
    removed = len([ln for ln in body.splitlines()
                   if ln.startswith("-") and not ln.startswith("---")])
    return {
        "bytes": len(body.encode("utf-8")),
        "files": names,
        "added": added,
        "removed": removed,
        "has_test": any(("test" in n or "spec" in n) for n in names),
        "has_impl": any(not ("test" in n or "spec" in n or n.endswith(".md")) for n in names),
        "mentions_req_doc": REQ_DOC in body,
        "mentions_injected_test": INJECTED_TEST in body,
        "body_head": body[:600],
    }


def snapshot_artifacts():
    try:
        return set(os.listdir(ARTIFACTS))
    except OSError:
        return set()


def wait_for_new_artifact(before, timeout, poll=3.0):
    """只认派单后新增的目录；上一轮的 patch.diff 不许把这一幕判绿。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        for name in sorted(snapshot_artifacts() - before, reverse=True):
            patch = os.path.join(ARTIFACTS, name, "patch.diff")
            if os.path.isfile(patch) and os.path.getsize(patch) > 0:
                return {"dir": name, "patch": patch}
        time.sleep(poll)
    return None


# ---------------------------------------------------------------------------
# 控制面（只做派单与只读查询；判据不在 HTTP 响应里）
# ---------------------------------------------------------------------------

def call(method, path, payload=None, timeout=30.0):
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


EXPECTED_LANDING = {"1": REQ_DOC, "2": FEATURE_IMPL}


def dispatch_timeout_diagnostics():
    """超时不等于「没跑完」：区分 agent 还在跑 / 进程已死但宿主没收到结束事件。

    反复验收必须能归因——否则一次 LLM 生成卡住和一次沙箱失效长得一模一样。
    """
    try:
        _, body = call("GET", "/v1/agent/sessions", timeout=5)
        states = [s.get("state") for s in body.get("sessions", [])] if isinstance(body, dict) else []
    except Exception as e:
        states = ["<查询失败:" + str(e)[:60] + ">"]
    _, alive, _ = run_cmd(["pgrep", "-f", CLI_PATTERN])
    live = len([ln for ln in alive.splitlines() if ln.strip()])
    newest, mtime = "", 0.0
    try:
        for name in os.listdir(ARTIFACTS):
            p = os.path.join(ARTIFACTS, name, "task.log")
            if os.path.isfile(p) and os.path.getmtime(p) > mtime:
                newest, mtime = name, os.path.getmtime(p)
    except OSError:
        pass
    age = round(time.time() - mtime, 1) if mtime else None
    return (f"会话state={states or '空'}；活dsh进程={live}；"
            f"最近task.log={newest or '无'}（{age}s 前写入）")


def dispatch(ctx, agent, prompt, ws_path, timeout, expect_path=None):
    """直驱链派单。202 只代表受理；只认本次新增的沙箱补丁。

    expect_path 给定时同时观察主工作区：若文件直接落在主仓而没有 patch.diff，
    说明发生了**沙箱逃逸**（agent 顺着绝对路径写回宿主，worktree 零改动 ⇒
    宿主日志 `harvest ... nothing to harvest`），立即早停把事实交给判据，
    不干等超时。注意这不等于「没进沙箱」：task.log 的 session.cwd 是
    /private/tmp/tenon-ws/t-1-xxx，沙箱确实建了，只是没关住写路径。
    """
    before = snapshot_artifacts()
    landed_path = os.path.join(ws_path, expect_path) if expect_path else None
    landed_before = bool(landed_path and os.path.isfile(landed_path))
    code, body = call("POST", "/v1/agent/hook/dispatch", {
        "agentKey": agent.key,
        "prompt": prompt,
        "wsPath": ws_path,
    }, timeout=60)
    if code != 202:
        hint = "（试 --agent-key 指定 driverId，例如 cli-dsh）" if code == 404 else ""
        return None, f"派单 HTTP {code} {body}{hint}"
    deadline = time.time() + timeout
    while time.time() < deadline:
        for name in sorted(snapshot_artifacts() - before, reverse=True):
            patch = os.path.join(ARTIFACTS, name, "patch.diff")
            if os.path.isfile(patch) and os.path.getsize(patch) > 0:
                return {**{"dir": name, "patch": patch}, "http": code, "accepted": body}, ""
        if landed_path and not landed_before and os.path.isfile(landed_path):
            return {"no_patch": True, "dir": None, "http": code, "accepted": body,
                    "landed_in_main_worktree": expect_path}, ""
        # 通用逃逸早停：agent 可能写到 expect_path 之外的文件（从零链路尤其常见）。
        # 只要派单期间工作树相对基线出现新增脏行，就是越过沙箱 cwd 写回了宿主工作树。
        _, dirty = git_state(ws_path)
        leaked = sorted(dirty - ctx.base_dirty)
        if leaked:
            rel = expect_path or leaked[0].split(None, 1)[-1].strip()
            return {"no_patch": True, "dir": None, "http": code, "accepted": body,
                    "landed_in_main_worktree": rel, "escaped_files": leaked}, ""
        time.sleep(3)
    return None, (f"{timeout}s 内本次派单没有产出 patch.diff（不是通过，是没跑完）；"
                  + dispatch_timeout_diagnostics())


def no_patch_verdicts(ctx, act_id, art):
    """agent 真干了活、但改动逃逸到主工作树：能力成立、隔离链不成立，分开判。

    绝不因为「文件确实产出了」就把这一幕判绿 —— 宿主主工作树零污染是产品口径。
    判据取「主仓相对基线新增脏文件」这个地面真相，而不是会话台账的 wsId。
    """
    ws = ctx.args.workspace
    rel = art["landed_in_main_worktree"]
    try:
        with open(os.path.join(ws, rel), encoding="utf-8", errors="replace") as fh:
            body = fh.read()
    except OSError:
        body = ""
    needs_ac = act_id in ("1", "a1")
    ac_ok = all(f"AC{i}" in body for i in range(1, 5))
    produced = bool(body) and (not needs_ac or ac_ok)
    size = len(body.encode("utf-8"))
    ctx.created_files.append(rel)
    _, dirty = git_state(ws)
    escaped = sorted(dirty - ctx.base_dirty)
    v = [
        Verdict("agent-produced-artifact", produced,
                f"dsh 真产出 {rel} {size}B；AC1..AC4 {'齐全' if ac_ok else '缺失'}"),
        Verdict("patch-harvested", False,
                "本次没有 patch.diff；宿主日志 `harvest ... nothing to harvest, patchBytes=0`"),
        Verdict("sandbox-no-escape", False,
                f"主工作树被写入：{escaped[:6]}；一次性 worktree 零改动，"
                "即 agent 越过 cwd 写回宿主（沙箱逃逸，非「未建沙箱」）。"
                "产品链有 DesktopStageChain 的 Escaped write 判据兜底，直驱链没有；"
                "编排器的工单卡/追问/自愈/合流这一层直驱链也不得冒充"),
    ]
    return v, {"agent": ctx.agents[0].key, "no_patch": True, "landed": rel,
               "bytes": size, "escaped_files": escaped,
               "verdict": "agent capability OK / sandbox containment NOT OK"}


# 卡片「最新载荷」的 SQL 表达式（T-2d 两列分工，D-021）。
#
# `card_json` 是这张卡的**首帧锚**：写进去之后永不刷新（`SqlDelightMessageStore`
# 读侧注释写明）；`card_payload` 才是最新 rev 的载荷，NULL = 从未翻面。
#
# 2026-11-10 真机读库实测（宿主库 + 手机库两份都对过）：
#   - `card_json like '%"delivered":true%'` → **0 张**
#   - `card_payload like '%"delivered":true%'` → **32 张**
#   - 同一条工单 `chain-tmwaa019b`：card_json rev=1/proposed/artifacts=[]，
#     card_payload rev=4/prototype/artifacts=[prototype.html delivered=true]
#
# 所以「卡片库命中 0 张带已投递原型的卡」**不是**产品没落库，是判据只读了首帧
# 那一列 —— 它会永远判红，而且永远指向一个不存在的缺陷。三处读取（本文件 /
# `sdlc-compare.py` / `e2e-team-pipeline.py`）必须共用这一条表达式，各写一遍
# 迟早有一处漏改（本仓最常见的分叉形态）。
LATEST_CARD_SQL = "coalesce(card_payload, card_json)"


def cards_since(conv_id, since_ms, db_path):
    """orchestrator 层观测：卡片落在 MessageEntity（取**最新载荷**，不是首帧锚）。"""
    col = LATEST_CARD_SQL
    try:
        con = sqlite3.connect("file:" + db_path + "?mode=ro", uri=True, timeout=5.0)
        rows = con.execute(
            "select timestamp, " + col + " from MessageEntity where conv_id = ? "
            "and timestamp > ? and " + col + " is not null order by timestamp asc",
            (conv_id, since_ms),
        ).fetchall()
        con.close()
    except (OSError, sqlite3.Error):
        return []
    out = []
    for ts, raw in rows:
        try:
            out.append({"ts": ts, "card": json.loads(raw)})
        except (TypeError, ValueError):
            continue
    return out


def card_reader_self_check():
    """判据先验证自己：拿一个「首帧 rev=1 / 载荷 rev=4 且已投递」的合成库跑一遍。

    为什么必须有这一条：读错列是**静默**故障 —— 判据红了，指向的是产品；而产品
    其实是好的。这种错误只能靠"让读侧在一个已知答案的库上先答一次"来堵。合成库
    的形状照抄真机两列分工（card_json 永不变、card_payload 带 artifacts）。
    """
    import tempfile

    with tempfile.TemporaryDirectory(prefix="card-reader-") as tmp:
        db = os.path.join(tmp, "relay.db")
        con = sqlite3.connect(db)
        con.execute(
            "create table MessageEntity (id text primary key, conv_id text, timestamp integer, "
            "card_json text, card_payload text)"
        )
        first = json.dumps({"cardId": "c1", "rev": 1, "state": "proposed", "artifacts": None})
        latest = json.dumps({
            "cardId": "c1", "rev": 4, "state": "prototype",
            "artifacts": [{"name": "prototype.html", "delivered": True}],
        })
        con.execute(
            "insert into MessageEntity values ('m1', 'group:g', 2000, ?, ?)", (first, latest),
        )
        con.commit()
        con.close()
        got = cards_since("group:g", 1000, db)
    if len(got) != 1:
        return False, "合成库应命中 1 张卡，实得 " + str(len(got))
    card = got[0]["card"]
    if card.get("rev") != 4:
        return False, "读到的 rev=" + str(card.get("rev")) + "（应为最新载荷 4，不是首帧 1）"
    arts = card.get("artifacts") or []
    if not any(a.get("delivered") is True for a in arts if isinstance(a, dict)):
        return False, "读到的 artifacts 里没有 delivered=true（读成首帧那一列了）"
    return True, "合成库（首帧 rev1 / 载荷 rev4+delivered）读回 rev4，列没读错"


def desktop_db():
    """卡片库路径：产品改名后新旧目录都可能存在，取真实存在的那个。

    `DesktopPlatform.kt:DESKTOP_APP_DIR` 现已从「捎话 Tidings」改成「榫卯 Tenon」
    〔旧名降为 LEGACY_DESKTOP_APP_DIR，首次启动自动迁移〕。写死旧名会让
    --tier real 的卡片观测永远读不到记录、判据静默超时。
    """
    override = os.environ.get("E2E_DESKTOP_DIR")
    if override:
        return os.path.join(override, "relay.db")
    support = os.path.join(os.path.expanduser("~"), "Library", "Application Support")
    # 迁移不是原子切换：新旧两个目录都可能真实存在，按存在性探测才不会读空。
    for name in ("榫卯 Tenon", "捎话 Tidings"):
        candidate = os.path.join(support, name, "relay.db")
        if os.path.isfile(candidate):
            return candidate
    return os.path.join(support, "榫卯 Tenon", "relay.db")


# 系统浏览器包名白名单：判据是「前台 Activity 属于浏览器」，不是截图里看着像网页。
# 只认这个集合能让「打开了 App 自己的某个页面」不会被误判成「原型打开了」。
BROWSER_PACKAGES = (
    "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
    "org.mozilla.firefox", "org.mozilla.firefox_beta", "com.brave.browser",
    "com.microsoft.emmx", "com.opera.browser", "com.opera.mini.native",
    "com.tencent.mtt", "com.UCMobile", "com.quark.browser", "com.android.browser",
    "com.miui.browser", "com.huawei.browser", "com.heytap.browser",
    "com.vivo.browser", "com.sec.android.app.sbrowser", "com.transsion.browser",
)

E2E_PHONE_PKG_DEFAULT = "com.kapp.tenon.debug"


def _sha256_bytes(data):
    import hashlib

    return hashlib.sha256(data).hexdigest()


def patch_html_artifacts(since_ms):
    """本次 run 之后生成的最新 patch.diff 里**新增**的网页产物：[(rel, sha256, bytes)]。

    为什么从补丁里重建而不是读沙箱文件：沙箱是一次性 worktree，跑完就没了；补丁是
    harness 唯一的持久地面真相（`[sandbox] harvest ... patch=NNNN B` 那行日志指向它）。
    重建规则照 git 的 add-file 格式：`+++ b/<rel>` 之后所有 `+` 行去掉首字符，
    `\\ No newline at end of file` 只表示结尾没有换行、不算内容。
    """
    root = "/tmp/tenon-ws/artifacts"
    newest = None
    for dirpath, _dirs, names in os.walk(root):
        if "patch.diff" not in names:
            continue
        path = os.path.join(dirpath, "patch.diff")
        try:
            mtime = os.path.getmtime(path)
        except OSError:
            continue
        if mtime * 1000 < since_ms:
            continue
        if newest is None or mtime > newest[0]:
            newest = (mtime, path)
    if newest is None:
        return []
    try:
        with open(newest[1], "r", encoding="utf-8", errors="replace") as fh:
            lines = fh.read().splitlines()
    except OSError:
        return []

    out, rel, buf, no_newline = [], None, [], False

    def flush():
        if rel and buf:
            body = "\n".join(buf) + ("" if no_newline else "\n")
            raw = body.encode("utf-8")
            out.append((rel, _sha256_bytes(raw), len(raw)))

    for line in lines:
        if line.startswith("+++ b/"):
            flush()
            rel, buf, no_newline = line[6:], [], False
        elif line.startswith("diff --git"):
            flush()
            rel, buf, no_newline = None, [], False
        elif rel and line.startswith("\\"):
            no_newline = True
        elif rel and line.startswith("+"):
            buf.append(line[1:])
    flush()
    return [a for a in out if a[0].endswith((".html", ".htm"))]


def phone_html_files():
    """手机沙箱下载目录里的网页文件：[(path, sha256)]。读不到返回 []。"""
    pkg = os.environ.get("E2E_PHONE_PKG", E2E_PHONE_PKG_DEFAULT)
    # 用**一条** shell 串：`-name "*.html"` 里的通配符不能让远端 sh 先展开。
    rc, out, _ = adb([
        "shell",
        'run-as ' + pkg + ' find files/relay/downloads -type f -name "*.htm*"',
    ])
    if rc != 0:
        return []
    files = []
    for path in (ln.strip() for ln in out.splitlines()):
        if not path or "/" not in path:
            continue
        # 路径必须**加引号**：同名文件带 `(1)`/`(2)`，裸着传给远端 sh 就是语法错误
        # （实测：三份文件只读回一份，缺的正是带括号的那两份 —— 而它们才是本轮产物）。
        rc2, out2, _ = adb(["shell", "run-as " + pkg + " sha256sum '" + path.replace("'", "'\\''") + "'"])
        if rc2 != 0:
            continue
        digest = out2.strip().split(" ")[0] if out2.strip() else ""
        if len(digest) == 64:
            files.append((path, digest.lower()))
    return files


def adb(args, timeout=30):
    """真机 adb。E2E_ADB_SERIAL 缺省时用唯一连接设备（多设备时必须显式给）。"""
    serial = os.environ.get("E2E_ADB_SERIAL", "").strip()
    argv = ["adb"] + (["-s", serial] if serial else []) + list(args)
    return run_cmd(argv, timeout=timeout)


_FOCUS_PATTERNS = (
    r"(?:mResumedActivity|topResumedActivity|ResumedActivity)[^\n]*?\s([A-Za-z][\w.]*)/",
    r"mCurrentFocus[^\n]*?\s([A-Za-z][\w.]*)/",
    r"mFocusedApp[^\n]*?\s([A-Za-z][\w.]*)/",
)


def foreground_package():
    """当前前台 Activity 的包名。

    用 `dumpsys` 而不是截图：截图判不了「这是浏览器还是 App 自己」，而包名是系统事实 ——
    与「不认截图、只认地面真相」同一条纪律。

    两个 dumpsys 面 + 三条焦点行型：`activity activities` 在一次转场中间可能暂时
    没有 Resumed 行（实测偶发一次空读），此时退到 `dumpsys window` 的焦点行。
    读不到就返回空串 —— 调用方按失败计，不猜。
    """
    notes = []
    for cmd in (["shell", "dumpsys", "activity", "activities"],
                ["shell", "dumpsys", "window"]):
        label = " ".join(cmd[1:])
        rc, out, err = adb(cmd)
        if rc != 0:
            notes.append(label + " rc=" + str(rc) + " " + str((err or "").strip().splitlines()[:1]))
            continue
        for pat in _FOCUS_PATTERNS:
            m = re.search(pat, out)
            if m:
                return m.group(1), notes + [label]
        notes.append(label + " 无焦点行")
    return "", notes


# ---------------------------------------------------------------------------
# 幕
# ---------------------------------------------------------------------------

def act0_preflight(ctx):
    v = []
    code, body = call("GET", "/v1/peers", timeout=5)
    v.append(Verdict("control-plane", code == 200, f"GET /v1/peers -> {code} {str(body)[:80]}"))

    for agent in ctx.agents:
        path = shutil.which(agent.cli)
        v.append(Verdict(f"cli:{agent.key}", path is not None, f"{agent.cli} -> {path or 'not in PATH'}"))
    cred = os.path.expanduser("~/.dsh/.credentials.yaml")
    v.append(Verdict("dsh-credentials", os.path.isfile(cred),
                     f"{cred} {'存在' if os.path.isfile(cred) else '缺失'}"))

    ws = ctx.args.workspace
    exists = os.path.isdir(os.path.join(ws, ".git"))
    v.append(Verdict("workspace-is-git", exists, ws))
    stage = ctx.args.stage
    anchor, expected, forbidden, has_tests = STAGES[stage]
    if exists:
        ctx.base_head, ctx.base_dirty = git_state(ws)
        v.append(Verdict("workspace-clean", not ctx.base_dirty,
                         f"HEAD={ctx.base_head[:12]} 脏行={len(ctx.base_dirty)}"))
        if anchor:
            v.append(Verdict("anchor-checked-out", ctx.base_head.startswith(anchor),
                             f"stage={stage} 期望锚点 {anchor}；实际 {ctx.base_head[:12]}"))
        present = [p for p in expected if os.path.exists(os.path.join(ws, p))]
        v.append(Verdict("stage-files-present", len(present) == len(expected),
                         f"期望 {expected}；实到 {present}"))
        leaked = [p for p in forbidden if os.path.exists(os.path.join(ws, p))]
        v.append(Verdict("stage-files-absent", not leaked,
                         f"本阶段不该有 {forbidden}；实际多出 {leaked}"))
        t = npm_test(ws)
        ctx.base_tests = t
        if has_tests:
            v.append(Verdict("baseline-tests-green",
                             t["rc"] == 0 and t["fail"] == 0 and (t["pass"] or 0) > 0,
                             f"pass={t['pass']} fail={t['fail']} skipped={t['skipped']} duration={t['duration_ms']}ms"))
        else:
            v.append(Verdict("baseline-has-no-implementation",
                             not os.path.isdir(os.path.join(ws, "src")),
                             f"stage={stage}「还没有实现」即正确基线；"
                             f"npm test rc={t['rc']} 只作读数，不拿预期红判失败"))
        if stage != "current":
            _, src_dirty = git_state(ctx.args.source)
            v.append(Verdict("source-repo-untouched", not src_dirty,
                             f"{ctx.args.source} 脏行={len(src_dirty)}"))
            v.append(Verdict("workspace-is-isolated",
                             os.path.abspath(ws) != os.path.abspath(ctx.args.source),
                             f"检出目录 {ws} ≠ 只读源仓 {ctx.args.source}"))

    if ctx.args.tier == "real":
        rc, out, _ = run_cmd(["adb", "devices"])
        serials = [ln.split("\t")[0] for ln in out.splitlines()[1:] if "\tdevice" in ln]
        v.append(Verdict("adb-device", bool(serials), f"在线设备 {serials}"))
        v.append(Verdict("desktop-db", os.path.isfile(desktop_db()), desktop_db()))

    v.append(Verdict("artifacts-dir", os.path.isdir(ARTIFACTS), ARTIFACTS))
    return v, {"base_head": ctx.base_head, "base_dirty": sorted(ctx.base_dirty),
               "base_tests": ctx.base_tests}


PROMPTS = {
    "1": ("需求确认", "cli-direct",
          lambda _ws: f"新增 {REQ_DOC}：为「紫外线出行建议」特性写需求说明，"
                      "必须包含至少 4 条编号的、可用自动化测试验证的验收标准（AC1..AC4），"
                      "写清 UV 指数分级边界（0-2 弱 / 3-5 中等 / 6-7 强 / 8+ 极强）与非法输入处理。"
                      "只写这一个文件，不要改实现与既有测试。"),
    "2": ("新功能实现", "cli-direct",
          lambda _ws: f"本项目是 ESM（package.json type=module），必须用 import/export，不许用 require。"
                      f"新增 {FEATURE_IMPL}，导出纯函数 uvAdvice(uv)：0-2 返回 '弱'、3-5 '中等'、"
                      "6-7 '强'、8 及以上 '极强'，非法输入返回 null。"
                      f"新增 {FEATURE_TEST}，用 node:test 覆盖边界 0/2/3/5/6/7/8/11 与非法输入。"
                      "运行 npm test 必须全绿。不要删改任何既有文件或既有测试。"),
    "5": ("缺陷定位修复", "cli-direct",
          lambda _ws: f"仓库里有一个新加的失败测试 {INJECTED_TEST}，先运行 npm test 看失败堆栈，"
                      f"在 {FEATURE_IMPL} 里补上测试要求的导出并让全量测试转绿。"
                      "**严禁修改、删除或跳过这个注入测试文件**，也不许改其他既有测试。"
                      "只改实现文件。完成后确保 npm test 全绿。"),
}


def apply_patch(ws, patch):
    """把沙箱补丁落到工作区（与 DesktopPatchMerge 同语义：apply，不 commit）。"""
    rc, out, err = run_cmd(["git", "-C", ws, "apply", "--check", patch])
    if rc != 0:
        return False, "apply --check: " + (out + err).strip()[:300]
    rc, out, err = run_cmd(["git", "-C", ws, "apply", patch])
    return rc == 0, (out + err).strip()[:300]


def device_commit(ws, msg, ctx):
    """装置动作：把合流/注入结果提交，让下一次沙箱基线看得见。

    产品里这件事由 refs/tenon/base 滚动快照承担；目标仓没有该 ref，宿主回落
    HEAD，所以 harness 用一次带 harness: 前缀的显式提交代替，并逐条记账供还原。
    这是装置，不是被测行为，证据里单独列出来。
    """
    rc, _, err = run_cmd(["git", "-C", ws, "add", "-A"])
    if rc != 0:
        return False, err.strip()[:200]
    _, porcelain, _ = run_cmd(["git", "-C", ws, "status", "--porcelain"])
    if not porcelain.strip():
        return True, "无需提交（内容与基线一致）"  # 幂等：重放同一幕不该判红
    rc, _, err = run_cmd(["git", "-C", ws, "commit", "-m", "harness: " + msg])
    if rc == 0:
        ctx.device_commits.append(msg)
    return rc == 0, err.strip()[:200]


def cli_act(ctx, act_id):
    """直驱链：真 agent 真沙箱真补丁；判据取补丁内容，不取受理回执。"""
    _, _, prompt_fn = PROMPTS[act_id]
    agent = ctx.agents[0]
    ws = ctx.args.workspace
    prompt = SANDBOX_PREAMBLE + prompt_fn(ws)
    art, err = dispatch(ctx, agent, prompt, ws, ctx.args.timeout,
                        expect_path=EXPECTED_LANDING.get(act_id))
    if art is None:
        return [Verdict("dispatch-harvest", False, err)], {"error": err}
    if art.get("no_patch"):
        return no_patch_verdicts(ctx, act_id, art)

    info = parse_patch(art["patch"])
    v = [Verdict("patch-is-real", info["bytes"] >= 200 and bool(info["files"]),
                 f"{art['dir']} {info['bytes']}B files={info['files']} +{info['added']}/-{info['removed']}")]
    if act_id == "1":
        v.append(Verdict("requirement-doc-produced", REQ_DOC in info["files"],
                         f"期望 {REQ_DOC}；实际 {info['files']}"))
    elif act_id == "2":
        v.append(Verdict("feature-files-produced",
                         FEATURE_IMPL in info["files"] and FEATURE_TEST in info["files"],
                         f"期望 {FEATURE_IMPL} 与 {FEATURE_TEST}；实际 {info['files']}"))
    elif act_id == "5":
        v.append(Verdict("injected-test-not-deleted",
                         INJECTED_TEST not in info["files"] and not info["mentions_injected_test"],
                         "补丁里不许出现对注入测试的删改"))
        v.append(Verdict("feature-impl-changed", FEATURE_IMPL in info["files"],
                         f"期望改动 {FEATURE_IMPL}；实际 {info['files']}"))
        ok, detail = apply_patch(ws, art["patch"])
        v.append(Verdict("fix-applied", ok, detail))
        if ok:
            t = npm_test(ws)
            v.append(Verdict("tests-green-after-fix",
                             t["rc"] == 0 and t["fail"] == 0 and
                             (t["pass"] or 0) > (ctx.base_tests.get("pass") or 0),
                             f"pass={t['pass']} fail={t['fail']} (基线 {ctx.base_tests.get('pass')})"))
            probe = injected_test_verdict(ws)
            v.append(Verdict("injected-test-now-green",
                             probe["rc"] == 0 and (probe["fail"] or 0) == 0
                             and (probe["pass"] or 0) > 0,
                             f"node --test {INJECTED_TEST}: pass={probe['pass']} fail={probe['fail']}"))
            dc, dd = device_commit(ws, "fix injected defect", ctx)
            v.append(Verdict("fix-committed-to-baseline", dc, dd))
    return v, {"agent": agent.key, "artifact": art["dir"], "patch": info, "prompt": prompt[:300]}


def act3_merge(ctx):
    """合流：headless tier 按 DesktopPatchMerge 同语义 apply，再打基线提交。"""
    ws = ctx.args.workspace
    if ctx.args.tier == "real":
        return orchestrator_gate(ctx, "在手机上点 [合并此补丁]", "merge", 600)
    # 全生命周期的每一次派单补丁都要落地：只合最近一个会把需求文档丢掉。
    # 按幕序 apply，与 DesktopPatchMerge 同语义（apply，不 commit）。
    patches = []
    for r in ctx.results:
        if isinstance(r.evidence.get("patch"), dict) and r.evidence.get("artifact"):
            candidate = os.path.join(ARTIFACTS, r.evidence["artifact"], "patch.diff")
            if os.path.isfile(candidate) and candidate not in patches:
                patches.append(candidate)
    if not patches:
        return [Verdict("patch-available", False, "没有可合流的本次补丁")], {}
    v = []
    applied = []
    for p in patches:
        rc, _, _ = run_cmd(["git", "-C", ws, "apply", "--check", p])
        ok, detail = apply_patch(ws, p) if rc == 0 else (False, "apply --check 未通过")
        # 判据名不许带本次任务的动态 artifact id：--repeat 的
        # identical-verdict-shape 逐轮比对判据名，带 id 会永远判红。
        v.append(Verdict("apply-patch", ok,
                         os.path.basename(os.path.dirname(p)) + ": " + (detail or "ok")[:160]))
        if ok:
            applied.append(p)
        else:
            break
    head, dirty = git_state(ws)
    v.append(Verdict("worktree-changed", bool(dirty - ctx.base_dirty),
                     f"新增脏行 {sorted(dirty - ctx.base_dirty)[:6]}"))
    t = npm_test(ws)
    v.append(Verdict("post-merge-tests-green",
                     t["rc"] == 0 and t["fail"] == 0 and
                     (t["pass"] or 0) > (ctx.base_tests.get("pass") or 0),
                     f"pass={t['pass']} (基线 {ctx.base_tests.get('pass')}) fail={t['fail']}"))
    if len(applied) == len(patches):
        dc, dd = device_commit(ws, "merge feature from sandbox", ctx)
        v.append(Verdict("baseline-visible-to-next-sandbox", dc, dd))
        ctx.created_files.extend([FEATURE_IMPL, FEATURE_TEST, REQ_DOC])
    return v, {"patches": patches, "applied": applied, "head": head,
               "dirty": sorted(dirty), "tests": t,
               "device_commits": list(ctx.device_commits)}


def act4_followup(ctx):
    """追问唤醒只存在于编排器链；headless 直驱链没有活跃工单，如实标 N/A。"""
    if ctx.args.tier != "real":
        return None
    return orchestrator_gate(ctx, "在同一工单追问微调，观察是否复用原工单而非新开单", "followup", 600)


def act6_verdict(ctx):
    """收尾对账：工作树、测试、进程树、证据齐备性。"""
    ws = ctx.args.workspace
    head, dirty = git_state(ws)
    t = npm_test(ws)
    v = [
        Verdict("final-tests-green", t["rc"] == 0 and t["fail"] == 0 and t["skipped"] == 0,
                f"pass={t['pass']} fail={t['fail']} skipped={t['skipped']} duration={t['duration_ms']}ms"),
        Verdict("no-test-count-regression", (t["pass"] or 0) >= (ctx.base_tests.get("pass") or 0),
                f"now={t['pass']} base={ctx.base_tests.get('pass')}"),
    ]
    rc, out, _ = run_cmd(["pgrep", "-f", CLI_PATTERN])
    strays = [ln for ln in out.splitlines() if ln.strip()]
    v.append(Verdict("no-orphan-cli-process", not strays, f"pgrep 命中 {len(strays)} 行"))
    prior = [r for r in ctx.results if r.status == "FAIL"]
    v.append(Verdict("no-failed-acts", not prior, f"失败幕 {[r.act_id for r in prior]}"))
    return v, {"head": head, "dirty": sorted(dirty), "tests": t}


def orchestrator_gate(ctx, title, kind, timeout):
    """真人守门：控制面没有群发送路由，群里那一步由人在手机上做，脚本只观测。

    观测不到 = 超时如实失败，绝不因为「请求发出去了」报通过。
    """
    print(f"*** 人守门：{title}（最多 {timeout}s）***", flush=True)
    db = desktop_db()
    group = os.environ.get("E2E_GROUP", "")
    conv = "group:" + group if group else ""
    since = int(time.time() * 1000)
    deadline = time.time() + timeout
    seen = []
    while time.time() < deadline:
        for item in cards_since(conv, since, db):
            card = item["card"]
            ctype = str(card.get("type", ""))
            blob = json.dumps(card, ensure_ascii=False)
            if kind == "merge" and ("merged" in ctype or "已合并" in blob):
                seen.append(card)
            elif kind == "followup" and card.get("rev", 0) > 1:
                seen.append(card)
            elif kind == "handback" and ("Handed back" in blob or "退单" in blob or "blocked" in blob):
                seen.append(card)
        if seen:
            break
        time.sleep(3)
    return ([Verdict(f"orchestrator-{kind}", bool(seen),
                     f"卡片库 {db} conv={conv} 观测到 {len(seen)} 张目标卡")]
            if seen else
            [Verdict(f"orchestrator-{kind}", False,
                     f"{timeout}s 内卡片库没有出现目标卡（超时按失败计，不跳过）")]), {"cards": len(seen)}


def act_i2_abort(ctx):
    """硬打断只在 orchestrator 链成立；判据是进程树归零，不是卡片翻色。"""
    if ctx.args.tier != "real":
        return None
    print("*** 人守门：在手机上对执行中的工单发送 stop/停 ***", flush=True)
    deadline = time.time() + 300
    gone = False
    while time.time() < deadline:
        rc, out, _ = run_cmd(["pgrep", "-f", CLI_PATTERN])
        if not [ln for ln in out.splitlines() if ln.strip()]:
            gone = True
            break
        time.sleep(2)
    return ([Verdict("process-tree-removed", gone,
                     "pgrep 归零" if gone else "300s 内仍有 dsh 进程（打断未落地）")],
            {"pgrep_clean": gone})


def act_i1_handback(ctx):
    """超纲退单跑在编排器的自愈循环里；直驱链进不去，headless 如实标 NA。"""
    if ctx.args.tier != "real":
        return None
    return orchestrator_gate(ctx, "派一个超纲任务，观察 Handed back / blocked 退单卡", "handback", 900)


def read_text(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as fh:
            return fh.read()
    except OSError:
        return ""


def has_js(root):
    for _, _, files in os.walk(root):
        if any(f.endswith(".js") for f in files):
            return True
    return False


# 从无到有：空仓 → 需求 → 可点原型 → 可测实现。
# 每一幕都必须把补丁 apply + commit 进**本轮隔离工作区**，下一幕的沙箱基线才看得见
# 上一幕产物。证据键用 bootstrap_patch（不是 patch），避免 act3_merge 把已落地的
# 补丁再 apply 一次造成「文件已存在」的假红。
BOOTSTRAP = {
    "a1": ("需求立项",
           lambda: "当前是空仓，只有 package.json，docs/ 目录需要你创建。"
                   "新增 docs/PRD.md：为「极简天气预报应用」写一份**精简**需求说明，"
                   "至少 6 条编号的、可自动化验证的验收标准（AC1..AC6），"
                   "覆盖当前温度、未来 7 天预报、摄氏/华氏切换、空气质量指数、非法输入、缓存。"
                   "全文控制在 120 行以内，重点是可测 AC，不要写长篇背景。"
                   "只写这一个文件，不要创建实现代码或测试。",
           "docs/PRD.md"),
    "a2": ("原型与设计",
           lambda: "新增 prototype.html：单文件可交互天气原型（纯 HTML+CSS+JS，不依赖构建工具）。"
                   "必须包含当前温度、7 天预报列表、摄氏/华氏切换按钮，点击切换时温度数值真实变化；"
                   "交互逻辑写在 <script> 里，至少 3 个可点元素。"
                   "全文控制在 200 行以内，够用即可，不要堆样式。只写这一个文件。",
           "prototype.html"),
    "a3": ("代码实现与单测",
           lambda: "实现最小可运行天气应用：在 src/ 下新增至少一个 ESM 模块"
                   "（package.json 的 type=module，必须用 import/export，不许 require），"
                   "在 test/ 下用 node:test 新增 3 到 5 条单元测试，覆盖正常值、边界值、非法输入。"
                   "实现控制在 150 行以内，测试够覆盖即可。运行 npm test 必须全绿。"
                   "不要改 package.json，不要删改任何已有文件。",
           None),   # 不锁死文件名：判据按「补丁里确有 src/ 与 test/ 文件」验收
}


def bootstrap_act(ctx, act_id):
    """从零链路的一幕：真派单 → 真补丁 → 落地成下一幕可见的基线。"""
    title, prompt_fn, expect = BOOTSTRAP[act_id]
    agent = ctx.agents[0]
    ws = ctx.args.workspace
    prompt = SANDBOX_PREAMBLE + prompt_fn()
    art, err = dispatch(ctx, agent, prompt, ws, ctx.args.timeout, expect_path=expect)
    if art is None:
        return [Verdict("dispatch-harvest", False, err)], {"error": err}
    if art.get("no_patch"):
        return no_patch_verdicts(ctx, "1" if act_id == "a1" else act_id, art)
    patch = art["patch"]
    text = read_text(patch)
    info = parse_patch(patch)
    v = [Verdict("patch-is-real", info["bytes"] >= 200 and bool(info["files"]),
                 f"{art['dir']} {info['bytes']}B files={info['files']} +{info['added']}/-{info['removed']}")]
    if expect:
        v.append(Verdict("expected-file-produced", expect in info["files"],
                         f"期望 {expect}；实际 {info['files']}"))
    if act_id == "a1":
        v.append(Verdict("prd-has-numbered-ac", all(f"AC{i}" in text for i in range(1, 5)),
                         "补丁正文需含 AC1..AC4 可测验收标准"))
    elif act_id == "a2":
        v.append(Verdict("prototype-is-interactive-html",
                         "<html" in text and "<script" in text
                         and any(s in text for s in ("function", "=>", "addEventListener"))
                         and info["added"] >= 40,
                         f"added={info['added']}；需有 <script> 与可执行交互逻辑"))
    elif act_id == "a3":
        v.append(Verdict("impl-and-tests-both-produced",
                         any(n.startswith("src/") for n in info["files"])
                         and any(n.startswith("test/") for n in info["files"]),
                         f"files={info['files']}"))
    ok, detail = apply_patch(ws, patch)
    v.append(Verdict("stage-landed", ok, detail))
    if ok:
        dc, dd = device_commit(ws, f"bootstrap {act_id}: {title}", ctx)
        v.append(Verdict("stage-visible-to-next-sandbox", dc, dd))
        if act_id == "a3":
            t = npm_test(ws)
            v.append(Verdict("bootstrap-tests-green",
                             t["rc"] == 0 and t["fail"] == 0 and (t["pass"] or 0) > 0,
                             f"pass={t['pass']} fail={t['fail']} skipped={t['skipped']}"))
    return v, {"bootstrap_patch": info, "artifact": art["dir"], "prompt": prompt[:300]}


def act_a4_acceptance(ctx):
    """从零链路阶段验收：四类产物齐备 + 单测全绿 + 三幕都真的落进了基线。"""
    ws = ctx.args.workspace
    missing = [p for p in ("docs/PRD.md", "prototype.html")
               if not os.path.isfile(os.path.join(ws, p))]
    src_ok = has_js(os.path.join(ws, "src"))
    test_ok = has_js(os.path.join(ws, "test"))
    t = npm_test(ws)
    rc, log, _ = run_cmd(["git", "-C", ws, "log", "--format=%s", "-12"])
    stages = [ln for ln in log.splitlines() if ln.startswith("harness: bootstrap")]
    v = [
        Verdict("from-zero-artifacts-complete",
                not missing and src_ok and test_ok,
                f"缺文件={missing} src有js={src_ok} test有js={test_ok}"),
        Verdict("from-zero-tests-green",
                t["rc"] == 0 and t["fail"] == 0 and (t["pass"] or 0) > 0,
                f"pass={t['pass']} fail={t['fail']} skipped={t['skipped']}"),
        Verdict("three-stages-in-baseline", len(stages) >= 3,
                f"git log 中 harness: bootstrap 提交 {len(stages)} 条"),
    ]
    return v, {"missing": missing, "tests": t, "bootstrap_commits": stages}


def prototype_bytes_landed(since_ms):
    """本轮补丁里的网页产物，在手机上能不能按**字节**找到同一份。

    返回 `(Verdict | None, note)`：不适用时给 None（证据里只记 note），**不拿「没验」
    冒充「通过」** —— 这条判据只回答一个问题：「用户打开的那类文件里，有没有一份是
    本轮产物本身」。它堵的是下面这一类事故：投递通道把**上一轮**的同名文件当成本轮的
    交上去（2026-11-10 真机：卡片 48.7 KB，机上最新的 prototype 是上一轮的 21 KB）。
    """
    want = patch_html_artifacts(since_ms)
    if not want:
        return None, "本次补丁里没有网页产物：内容指纹不适用"
    files = phone_html_files()
    if not files:
        return None, "adb 读不到手机下载目录：内容指纹不适用（不是通过）"
    for rel, digest, size in want:
        hit = [path for path, got in files if got == digest]
        if hit:
            return (
                Verdict(
                    "prototype-artifact-bytes-landed", True,
                    f"{rel}（{size}B）逐字节落到手机：{hit[0]}",
                ),
                "",
            )
    names = ", ".join(rel for rel, _d, _s in want)
    sample = "; ".join(path for path, _d in files[:3])
    return (
        Verdict(
            "prototype-artifact-bytes-landed", False,
            f"补丁里的 {names} 在手机上找不到同 sha256 的文件（机上网页文件 {len(files)} 份："
            f"{sample}）",
        ),
        "",
    )


def main_tree_new_entries(since_ms):
    """主仓根**下一层**本轮新出现的未跟踪文件 —— 有没有东西被写进主树。

    沙箱隔离只覆盖**走 worktree 的派单**：任何 cwd 落在主仓的进程（手工探针、
    直连 CLI、早先建的会话）写出来的文件，此前没有任何判据会响。2026-11-10 真机
    发生过一次（`probe3.html` 直落仓库根），事后靠人跑 `git status` 才看出来；
    而那一轮的 a2r 四条判据全是绿的 —— 它们只看手机侧，没看源仓这一侧。

    只看根级一层、只看普通文件：证据与产物本来就该写在 `design/`、`scripts/`
    这类子目录里，把它们算进来会把正常开发判成红。git 不可用时返回 None，
    由证据说明不适用，绝不冒充通过。
    """
    rc, out, err = run_cmd(
        ["git", "status", "--porcelain", "--untracked-files=normal"], cwd=ROOT, timeout=180)
    if rc != 0:
        tail = ((err or out).strip().splitlines() or [""])[-1]
        return None, "git status 失败，主树写入判据不适用：" + tail[:200]
    hits = []
    for line in out.splitlines():
        if not line.startswith("?? "):
            continue
        rel = line[3:].strip().strip('"')
        if not rel or "/" in rel:
            continue
        path = os.path.join(ROOT, rel)
        if not os.path.isfile(path):
            continue
        try:
            ts = int(os.stat(path).st_mtime * 1000)
        except OSError:
            continue
        if ts >= since_ms:
            hits.append(rel)
    return hits, ""


def _last_logcat_window(tail_lines=4000):
    """读 logcat 尾部并用**设备自己的时钟**划出时间窗。

    不拿本机时间减设备日志时间：两边时钟可能差几十秒，而这条判据判的正是
    「多久没消息」，差一点就会把「刚好翻面」漏成绿。
    """
    rc, out, err = adb(["logcat", "-d", "-t", str(tail_lines)], timeout=90)
    if rc != 0:
        return None, "logcat 读取失败：" + str((err or out).strip().splitlines()[:1])
    stamps = []
    for line in out.splitlines():
        m = re.match(r"^\d\d-\d\d (\d\d:\d\d:\d\d\.\d+)", line)
        if not m:
            continue
        try:
            from datetime import datetime
            stamps.append(datetime.strptime("2000-" + m.group(1), "%Y-%H:%M:%S.%f"))
        except ValueError:
            continue
    if not stamps:
        return None, "logcat 尾部没有可解析的时间戳"
    return (out, max(stamps)), ""


def peer_idle_stays_online(window_s=180):
    """空闲期间「在线」不许自己掉。

    判的是**链路**，不是某条报文到没到。2026-11-10 真机：Pixel 6 前台、进程活着、
    链路 `linkConnected=true / closeCount=0`，却因为 90 秒没有 UDP 心跳被判离线，
    界面挂出「宿主设备睡眠 · 命令已安全排队」，宿主侧补发队列只涨不降。
    根因两层：无 legacy 发现的引擎既收不到 presence（入站没人处理），也发不出
    （安全信道的会话键是 `ip:tcpPort`，代码拿 `ip:udpPort` 去 send ⇒ 全部 send failed）。

    地面真相读两处：手机 `diag/link.json`（客户端自己的事实）+ 手机 logcat 的
    `[sweep] offline` 翻面日志。没有设备/读不到就返回 None，绝不冒充通过。
    """
    pkg = os.environ.get("E2E_PHONE_PKG", E2E_PHONE_PKG_DEFAULT).strip()
    rc, out, err = adb(["exec-out", "run-as " + pkg + " cat files/relay/diag/link.json"], timeout=60)
    if rc != 0 or not out.strip().startswith("{"):
        return None, "读不到手机 diag/link.json（没有设备 / 不是 debug 包），判据不适用"
    try:
        diag = json.loads(out)
    except Exception as exc:  # noqa: BLE001 - 诊断文件坏了就说不适用，不猜
        return None, "diag/link.json 解析失败：" + str(exc)[:120]
    peers = [p for p in (diag.get("peers") or []) if isinstance(p, dict)]
    linked = [p for p in peers if p.get("linkConnected") is True]
    if not linked:
        # 没有在册活跃链路时，hole 与 flips 都是空集，原式会判绿 —— 而这条判据
        # 只在「有链路、只是没人说话」时成立。双端同传输形态但链路没建起来
        # （dial 全超时 / linkTotal=0）是另一种故障，不适用时只记证据、不给绿。
        return None, ("手机侧无在册活跃链路（linkTotal="
                      + str(diag.get("linkTotal", "?")) + "，peers=" + str(len(peers))
                      + "）；本判据只在有链路但空闲时成立，空集不冒充通过")
    hole = [
        (str(p.get("nodeId", ""))[:8], p.get("linkConnected"))
        for p in peers
        if p.get("linkConnected") and not p.get("online")
    ]
    window, note = _last_logcat_window()
    if window is None:
        return None, "无法用 logcat 划时间窗：" + str(note)
    log_text, device_now = window
    from datetime import timedelta
    edge = device_now - timedelta(seconds=window_s)
    flips = []
    for line in log_text.splitlines():
        if "[sweep] offline" not in line:
            continue
        m = re.match(r"^\d\d-\d\d (\d\d:\d\d:\d\d\.\d+)", line)
        if not m:
            continue
        try:
            from datetime import datetime
            at = datetime.strptime("2000-" + m.group(1), "%Y-%H:%M:%S.%f")
        except ValueError:
            continue
        if at >= edge:
            flips.append(line.split("System.out: ")[-1][:80])
    ok = not hole and not flips
    detail = (
        "最近 " + str(window_s) + "s 内无翻面，链路在册的对端全部 online"
        if ok else
        "链路在册却判离线：" + str(hole) + "；翻面日志：" + str(flips[-3:])
    )
    return Verdict("peer-idle-stays-online", ok, detail), (
        "peers=" + str(len(peers)) + " flips=" + str(len(flips)))


def _prototype_cards(conv, since_ms, db_path):
    """本次会话里带**网页原型**产物、且发出方声明 `delivered=true` 的卡。"""
    out = []
    for item in cards_since(conv, since_ms, db_path):
        card = item["card"]
        for art in card.get("artifacts") or []:
            if not isinstance(art, dict):
                continue
            name = str(art.get("name", ""))
            is_html = name.endswith(".html") or name.endswith(".htm") or art.get("mime") == "text/html"
            if is_html and art.get("delivered") is True:
                out.append((card, art))
    return out


def act_a2_review(ctx):
    """阶段 A 的人审守门：原型真的到了客户端、能从卡上打开、打开后是活的。

    为什么单独一幕：原来的 a2 只判「worktree 里写出了 `<html>` / `<script>` 字节」——
    那只证明 agent 会写文件，**不证明有人能看见并评审**。缺这一幕，
    原型阶段的 `Approve & Develop` 就是盲批（2026-11-10 盘点结论）。

    直驱链（headless）没有群卡片、没有产物投递、也没有手机 —— 如实标 NA，
    绝不拿「文件存在」冒充「人能看到」。
    """
    if ctx.args.tier != "real":
        return None

    group = os.environ.get("E2E_GROUP", "").strip()
    db = desktop_db()
    conv = "group:" + group if group else ""
    since = (ctx.started_ms or int(time.time() * 1000)) - 60_000
    cards = _prototype_cards(conv, since, db) if conv else []
    # 先验读侧：判据红了要能确定「是产品没出卡」，而不是「判据读错了列」。
    # 这一条读错列时必然红，且文案直接指出是读侧问题（2026-11-10 踩过一次）。
    reader_ok, reader_detail = card_reader_self_check()
    v = [
        Verdict("card-reader-self-check", reader_ok, reader_detail),
        Verdict("prototype-card-delivered", bool(cards),
                (f"conv={conv} 卡片库命中 {len(cards)} 张带已投递网页原型的卡")
                if conv else
                "需要 E2E_GROUP 才能定位群会话；不跳过、不猜"),
    ]
    # 预览入口：真跑客户端路由单测 —— 判的是「.html 认得出、已投递+已落盘才给入口、
    # 网页原型走系统打开」，不是「源码里有个 if」。
    rc, out, err = run_cmd(
        ["./gradlew", ":composeApp:jvmTest",
         "--tests", "*PrototypeReviewEntryTest*",
         "--tests", "*ArtifactPreviewRoutingTest*",
         "--console=plain", "-q"],
        cwd=ROOT, timeout=900)
    tail = (out + err).strip().splitlines()[-3:]
    v.append(Verdict("prototype-preview-entry-tested", rc == 0,
                     "gradle :composeApp:jvmTest 通过（路由与入口判据）" if rc == 0
                     else "rc=" + str(rc) + " " + str(tail)))

    # 打开后是活的：人点「在浏览器打开原型」之后，前台 Activity 必须是浏览器包名。
    print("*** 人守门：在手机上点卡片上的「在浏览器打开原型」，确认原型可交互 ***", flush=True)
    budget = min(ctx.args.timeout, 300)
    deadline = time.time() + budget
    pkg, note, last = "", [], ""
    while time.time() < deadline:
        pkg, note = foreground_package()
        last = pkg or last
        if pkg in BROWSER_PACKAGES:
            break
        pkg = ""
        time.sleep(3)
    v.append(Verdict("prototype-opened-in-browser", bool(pkg),
                     ("前台包名 " + pkg) if pkg else
                     str(budget) + "s 内前台不是浏览器（最后读到："
                     + (last or str(note)) + "）；超时按失败计，不跳过"))
    # 第四判据：落盘的那份必须是**本轮产物本身**（按 sha256 认，不按文件名认）。
    # 不适用（补丁里没网页 / 没有 adb）时只记证据，不冒充通过。
    fp_verdict, fp_note = prototype_bytes_landed(since)
    if fp_verdict is not None:
        v.append(fp_verdict)
    # 第五判据：源仓这一侧也要看。前四条都只读手机与卡片库 —— 「agent 把文件写进
    # 主树」这种越界，四条全绿也照样发生（同上）。
    root_hits, root_note = main_tree_new_entries(since)
    if root_hits is not None:
        v.append(Verdict(
            "prototype-no-main-tree-write",
            not root_hits,
            "仓库根本轮无新建文件" if not root_hits else
            "本轮在仓库根新建了未跟踪文件：" + ", ".join(root_hits) +
            "（沙箱隔离只覆盖 worktree 派单，写主树的进程绕过了它）",
        ))
    evidence = {"group": group, "cards": len(cards), "foreground": pkg or note}
    if fp_note:
        evidence["fingerprint"] = fp_note
    if root_note:
        evidence["main_tree"] = root_note
    # 第六判据：前五条都在判「东西有没有送到」，没有一条判「链路会不会自己掉」。
    # 2026-11-10 那轮正是这一格空的：文件送到了、原型也打开了，而界面同时挂着
    # 「宿主设备睡眠」、待发队列停摆。不适用（没有设备）时只记证据，不给绿。
    idle_verdict, idle_note = peer_idle_stays_online()
    if idle_verdict is not None:
        v.append(idle_verdict)
        evidence["peer_presence"] = idle_note
    else:
        evidence["peer_presence"] = "不适用：" + str(idle_note)
    return v, evidence


ACTS = {
    "0": ("环境体检", "local", act0_preflight),
    "a1": ("需求立项（空仓）", "cli-direct", lambda c: bootstrap_act(c, "a1")),
    "a2": ("原型与设计", "cli-direct", lambda c: bootstrap_act(c, "a2")),
    "a2r": ("原型可评审（人审守门）", "orchestrator", act_a2_review),
    "a3": ("代码实现与单测", "cli-direct", lambda c: bootstrap_act(c, "a3")),
    "a4": ("从零阶段验收", "local", act_a4_acceptance),
    "1": ("需求确认", "cli-direct", lambda c: cli_act(c, "1")),
    "2": ("新功能实现", "cli-direct", lambda c: cli_act(c, "2")),
    "3": ("审查与合流", "local", act3_merge),
    "4": ("追问续接", "orchestrator", act4_followup),
    "5": ("缺陷定位修复", "cli-direct", lambda c: cli_act(c, "5")),
    "6": ("收尾验收", "local", act6_verdict),
    "i1": ("超纲退单", "orchestrator", act_i1_handback),
    "i2": ("硬打断", "orchestrator", act_i2_abort),
}


def inject_failing_test(ctx):
    """故障注入：写一个真实会红的 ESM 测试，并提交进沙箱基线。

    目标仓没有 refs/tenon/base，宿主沙箱回落 HEAD；只写文件不提交的话沙箱
    根本看不见这个测试，agent 会去修一个不存在的 bug。历史 drill 提交用的
    就是同一招，这里照做并记账。
    """
    ws = ctx.args.workspace
    if not os.path.isfile(os.path.join(ws, FEATURE_IMPL)):
        return False, {"error": f"缺少 {FEATURE_IMPL}；缺陷修复幕依赖新功能幕，请先跑幕 2 与幕 3"}
    path = os.path.join(ws, INJECTED_TEST)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("""// harness 故障注入：断言一个尚未实现的导出，必须靠改实现转绿。
import test from 'node:test';
import assert from 'node:assert/strict';
import { uvAdviceLabel } from '../src/utils/uvAdvice.js';

test('uvAdviceLabel 带防护建议文案', () => {
  assert.match(uvAdviceLabel(9), /防护/);
  assert.match(uvAdviceLabel(1), /无需/);
});
""")
    npm = npm_test(ws)
    probe = injected_test_verdict(ws)
    is_red = (probe["fail"] or 0) > 0
    if is_red:
        # 提交进基线，下一次沙箱才看得见这个测试（否则 agent 去修一个不存在的 bug）。
        ok, _ = device_commit(ws, "inject failing test into base", ctx)
        is_red = ok
    ctx.created_files.append(INJECTED_TEST)
    return is_red, {"npm_test": npm, "injected_probe": probe}


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

def dry_run(ctx):
    """纯本地自检：不碰网络、不碰设备、不碰 LLM。"""
    print("=== dry-run：幕表与判据自检 ===")
    checks = []
    checks.append(("act-table", len(ACTS) == 14 and all(k in ACTS for k in
                   ["0", "a1", "a2", "a2r", "a3", "a4", "1", "2", "3", "4", "5", "6", "i1", "i2"])))
    ids = [k for k in ACTS]
    checks.append(("act-ids", len(set(ids)) == len(ids)))
    sample = "+const x = 1;\n" * 40
    with tempfile.TemporaryDirectory() as tmp:
        p = os.path.join(tmp, "_selfcheck.patch")
        with open(p, "w", encoding="utf-8") as fh:
            fh.write("diff --git a/src/a.js b/src/a.js\n--- a/src/a.js\n+++ b/src/a.js\n" + sample)
            fh.write("diff --git a/test/a.test.js b/test/a.test.js\n--- a/test/a.test.js\n"
                     "+++ b/test/a.test.js\n" + sample)
        info = parse_patch(p)
    checks.append(("patch-parser", info["has_impl"] and info["has_test"] and info["added"] >= 80))
    checks.append(("chain-split", {c for _, c, _ in ACTS.values()} ==
                   {"local", "cli-direct", "orchestrator"}))
    checks.append(("headless-does-not-claim-orchestrator",
                   all(ACTS[k][1] == "orchestrator" for k in ["4", "i1", "i2"])))
    checks.append(("device-commits-tracked", "device_commits" in Context.__dataclass_fields__))
    # 首跑根因守卫：prompt 里一旦出现目标仓绝对路径，agent 就会顺着它写回宿主
    # （worktree 零改动 ⇒ patchBytes=0）。用哨兵路径把这件事钉在自检里。
    sentinel = "/tmp/sdlc-sentinel-workspace"
    leaks = [k for k, (_, _, fn) in PROMPTS.items()
             if sentinel in fn(sentinel) or "/Users/" in fn(sentinel)]
    checks.append(("prompts-do-not-leak-abs-path", not leaks))
    checks.append(("sandbox-preamble-has-cwd-discipline",
                   "相对路径" in SANDBOX_PREAMBLE and "绝对路径" in SANDBOX_PREAMBLE
                   and "worktree" in SANDBOX_PREAMBLE))
    with open(__file__, encoding="utf-8") as fh:
        src = fh.read()
    checks.append(("cli_act-prepends-preamble",
                   "prompt = SANDBOX_PREAMBLE + prompt_fn(ws)" in src))
    # 「无孤儿进程」判据的反向对齐：从驱动的 dshArgs 重建期望命令行前缀，
    # 与 CLI_PATTERN 比对。驱动改了参数而这里没跟上，自检必须红。
    driver = os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "agentNode", "src", "main", "kotlin", "com", "kapp", "tenon", "agent",
        "drivers", "CliSubprocessDriver.kt")
    try:
        with open(driver, encoding="utf-8") as fh:
            dsrc = fh.read()
        m = re.search(r"fun dshArgs\(prompt: String\): List<String> =\s*listOf\(([^)]*)\)",
                      dsrc, re.S)
        expected = "dsh " + " ".join(re.findall(r'"([^"]+)"', m.group(1))) if m else ""
        aligned = bool(expected) and expected.startswith(CLI_PATTERN)
    except OSError:
        aligned, expected = False, "<驱动源码不可读>"
    checks.append(("cli-process-pattern-aligns-with-driver", aligned))
    # 可反复验收机制的结构守卫：锚点必须是固定 sha、幕序列不许引用不存在的幕、
    # 从零链必须按依赖顺序、从零 prompt 不许带绝对路径、bootstrap 证据键不许叫 patch
    # （叫 patch 会被合流幕重复 apply，制造「文件已存在」的假红）。
    checks.append(("stage-anchors-are-fixed-shas",
                   all(a is None or re.fullmatch(r"[0-9a-f]{7}", a)
                       for a, *_ in STAGES.values())))
    checks.append(("all-plans-only-reference-known-acts",
                   all(all(k in ACTS for k in plan) for plan in PLANS.values())))
    fz = PLANS["from-zero"]
    checks.append(("from-zero-plan-respects-stage-order",
                   all(fz.index(x) < fz.index(y) for x, y in
                       zip(["a1", "a2", "a3", "a4"], ["a2", "a3", "a4", "2"]))))
    checks.append(("bootstrap-prompts-have-no-abs-path",
                   all("/Users/" not in fn() and "~/" not in fn()
                       for _, fn, _ in BOOTSTRAP.values())))
    checks.append(("bootstrap-prepends-preamble",
                   "prompt = SANDBOX_PREAMBLE + prompt_fn()" in src))
    # 从零实现幕只按「补丁里确有 src/ 与 test/ 文件」判，不许锁死文件名：
    # 首跑 agent 写 src/weather.js 就被硬编码的 src/app.js 判红过一次。
    checks.append(("bootstrap-a3-judges-by-content-not-filename",
                   BOOTSTRAP["a3"][2] is None and "if expect:" in src))
    checks.append(("bootstrap-evidence-key-is-not-patch",
                   '"bootstrap_patch": info' in src and
                   "isinstance(r.evidence.get(\"patch\"), dict)" in src))
    # 故障注入不许依赖 npm test 的默认收集：从零链路里 agent 自建的 test/ 布局
    # 会漏收集注入文件（实测 `node --test test/` 只跑 5 条，单文件却 fail=1）。
    checks.append(("injection-probes-explicit-file",
                   'run_cmd(["node", "--test", rel]' in src
                   and '"injected_probe": probe' in src
                   and '"injected-test-now-green"' in src))
    # 拼接写法：守卫自己的字面量不能被自己当成「动态判据名」命中。
    dynamic_marker = "Verdict(" + '"apply:"'
    checks.append(("no-dynamic-verdict-names",
                   dynamic_marker not in src and 'Verdict("apply-patch", ok' in src))
    # 原型评审守门（a2r）：只读这一段源码判定，理由有三 ——
    # 1) 它必须在 headless 档直接返回 None（NA），不许拿「文件字节存在」冒充「人能看到」；
    # 2) 「已投递」必须从卡片库读，不许靠 worktree 里有没有这个文件；
    # 3) 「打开后是活的」必须认设备前台包名，不许认截图。
    m_rev = re.search(r"def act_a2_review\(ctx\):(.*?)\n(?=def )", src, re.S)
    a2r = m_rev.group(1) if m_rev else ""
    checks.append(("review-gate-is-real-tier-only",
                   bool(a2r) and 'ctx.args.tier != "real"' in a2r))
    checks.append(("review-gate-judges-delivery-by-card-not-bytes",
                   "_prototype_cards" in a2r and 'art.get("delivered") is True' in src))
    checks.append(("review-gate-judges-browser-by-package-not-screenshot",
                   "BROWSER_PACKAGES" in a2r and "foreground_package" in a2r))
    # 4) 判据读卡片必须读**最新载荷**那一列，不许读首帧锚（2026-11-10 踩过一次：
    #    只读 card_json 时每条判据都指向 rev=1/proposed，把「产品没落库」这个假缺陷
    #    当真了 2 小时）。读侧还得先在自己的合成库上答对一次，否则红点永远指错方向。
    checks.append(("card-reader-uses-latest-payload",
                   "coalesce(card_payload, card_json)" in src
                   and "def card_reader_self_check" in src
                   and "card-reader-self-check" in a2r))
    # 5) 内容指纹只认 sha256：按文件名认就等于没认（同名文件在一条会话里会积累）。
    fp = re.search(r"def prototype_bytes_landed\(since_ms\):(.*?)\n(?=def )", src, re.S)
    fp_src = fp.group(1) if fp else ""
    checks.append(("review-gate-fingerprint-is-content-not-filename",
                   bool(fp_src) and "patch_html_artifacts" in fp_src
                   and "phone_html_files" in fp_src
                   and "digest" in fp_src))
    # 6) 不适用 ≠ 通过：没补丁 / 没 adb 时返回 None，由证据说明，绝不给绿。
    checks.append(("review-gate-fingerprint-never-silently-passes",
                   "return None, " in fp_src and "fingerprint" in a2r))
    # 7) 前四条判据全在手机侧 —— 「产物写进主树」这类越界它们一条都看不见。
    #    这一条把源仓侧也纳入守门，且不可不适用（git 不在才允许记 note）。
    mt = re.search(r"def main_tree_new_entries\(since_ms\):(.*?)\n(?=def )", src, re.S)
    mt_src = mt.group(1) if mt else ""
    checks.append(("review-gate-watches-main-tree-writes",
                   bool(mt_src) and '"/" in rel' in mt_src
                   and "prototype-no-main-tree-write" in a2r))
    # 8) 前六条都在判「东西有没有送到」，没有一条判「链路会不会自己掉」。
    #    2026-11-10 那轮正是这一格空的：文件送到了、原型打开了，界面同时挂着
    #    「宿主设备睡眠」。判据必须读**链路事实**（link.json）与**翻面日志**，
    #    且无设备时返回 None —— 静默给绿比没有判据更糟。
    pi = re.search(r"def peer_idle_stays_online\(window_s=180\):(.*?)\n(?=def )", src, re.S)
    pi_src = pi.group(1) if pi else ""
    checks.append(("review-gate-watches-link-liveness",
                   bool(pi_src) and "link.json" in pi_src and "[sweep] offline" in pi_src
                   and "return None, " in pi_src
                   and '"peer-idle-stays-online"' in pi_src
                   and "peer_idle_stays_online()" in a2r))
    # 9) 没有活跃链路时，hole / flips 天然都是空集，判据会「因为什么都没看见」
    #    而给绿。真机换包后 linkTotal=0、dial 全超时那轮正是这个形状。
    checks.append(("review-gate-idle-requires-live-link",
                   bool(pi_src) and "if not linked:" in pi_src
                   and "linkTotal" in pi_src and "return None, " in pi_src))
    for name, ok in checks:
        print(f"  [{'ok' if ok else 'FAIL'}] {name}")
    print("\n幕表（链型 / 判据层）：")
    for k, (title, chain, _) in ACTS.items():
        print(f"  {k:>3}  {title:<10} {chain}")
    return 0 if all(ok for _, ok in checks) else 1


def write_evidence(ctx):
    os.makedirs(ctx.out_dir, exist_ok=True)
    payload = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "tier": ctx.args.tier,
        "stage": ctx.args.stage,
        "anchor": ctx.anchor,
        "source": getattr(ctx.args, "source", ""),
        "run_label": ctx.run_label,
        "workspace": ctx.args.workspace,
        "host": HOST,
        "agents": [a.__dict__ for a in ctx.agents],
        "base_head": ctx.base_head,
        "base_tests": ctx.base_tests,
        "device_commits": ctx.device_commits,
        "created_files": sorted(set(ctx.created_files)),
        "environment": {
            "python": sys.version.split()[0],
            "dsh": shutil.which("dsh"),
            "opencode": shutil.which("opencode"),
            "adb": shutil.which("adb"),
        },
        "acts": [asdict(r) for r in ctx.results],
    }
    with open(os.path.join(ctx.out_dir, "result.json"), "w", encoding="utf-8") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=2)
    lines = [
        f"# SDLC 生命周期 harness 读数（{payload['generated_at']}）",
        "",
        f"- tier：`{ctx.args.tier}`；stage：`{ctx.args.stage}`（锚点 `{ctx.anchor or 'HEAD'}`）；"
        f"轮次：`{ctx.run_label or 'single'}`；agent：{', '.join(a.key for a in ctx.agents)}",
        f"- 工作区：`{ctx.args.workspace}`；只读源仓：`{getattr(ctx.args, 'source', '')}`",
        f"- 基线 HEAD `{ctx.base_head[:12]}`，基线测试 pass={ctx.base_tests.get('pass')} fail={ctx.base_tests.get('fail')}",
        f"- 装置提交（非被测行为）：{ctx.device_commits or '无'}",
        "",
        "| 幕 | 标题 | 链型 | 结论 | 判据读数 |",
        "| --- | --- | --- | --- | --- |",
    ]
    for r in ctx.results:
        detail = " / ".join(f"{v.name}={'ok' if v.ok else 'FAIL'}" for v in r.verdicts)
        lines.append(f"| {r.act_id} | {r.title} | {r.chain} | {r.status} | {detail or '—'} |")
    failed = [r.act_id for r in ctx.results if r.status == "FAIL"]
    lines += ["", f"**结论**：{'FAIL 幕 ' + ', '.join(failed) if failed else '全部判据通过'}。",
              "",
              "> `cli-direct` 幕只证明「真 agent 真沙箱真补丁」，不证明编排器的工单卡/追问/自愈/合并按钮；",
              "> `orchestrator` 幕在 headless tier 下如实标 NA，两者不得互相冒充。",
              ""]
    with open(os.path.join(ctx.out_dir, "summary.md"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines))
    return payload


def prepare_workspace(args, run_dir):
    """从阶段锚点检出全新隔离工作区；源仓只读，绝不 reset 主仓。

    返回 (workspace_path, anchor_sha)。失败直接抛，由 main 判环境错——
    绝不拿「检出失败」当「baseline 不对」继续往下跑。
    """
    source = os.path.abspath(os.path.expanduser(args.source))
    anchor = STAGES[args.stage][0]
    if anchor is None:
        return os.path.abspath(os.path.expanduser(args.workspace)), ""
    if not os.path.isdir(os.path.join(source, ".git")):
        raise RuntimeError("源仓不是 git 仓库：" + source)
    os.makedirs(run_dir, exist_ok=True)
    dest = os.path.join(run_dir, "ws")
    if os.path.exists(dest):
        raise RuntimeError("隔离工作区已存在，拒绝复用（会污染重放）：" + dest)
    rc, _, err = run_cmd(
        ["git", "clone", "--quiet", "--no-local", "--single-branch", source, dest], timeout=180)
    if rc != 0:
        raise RuntimeError("clone 失败：" + err.strip()[:200])
    rc, _, err = run_cmd(["git", "-C", dest, "checkout", "-q", "-B", "sdlc-run", anchor])
    if rc != 0:
        raise RuntimeError("checkout 锚点 " + anchor + " 失败：" + err.strip()[:200])
    _, dirty = git_state(dest)
    if dirty:
        raise RuntimeError("检出后工作树不干净：" + str(sorted(dirty)[:5]))
    return dest, anchor


def run_once(ctx, order):
    """跑一轮完整幕序列，返回 (green, exit_code)。"""
    args = ctx.args
    print("=== 轮次 " + ctx.run_label + " stage=" + args.stage
          + " anchor=" + (ctx.anchor or "HEAD") + " ws=" + args.workspace + " ===", flush=True)
    for k in order:
        title, chain, fn = ACTS[k]
        t0 = time.time()
        print("\n=== 幕 " + k + " · " + title + " [" + chain + "] ===", flush=True)
        if k == "5":
            injected, t = inject_failing_test(ctx)
            probe = t.get("injected_probe", {}) if isinstance(t, dict) else {}
            print("  故障注入 " + INJECTED_TEST + ": 红=" + str(injected)
                  + " 单文件fail=" + str(probe.get("fail"))
                  + " npm_test_pass=" + str((t.get("npm_test") or {}).get("pass")), flush=True)
            if not injected:
                ctx.results.append(ActResult(
                    k, title, chain, "FAIL",
                    [Verdict("fault-injection-red", False, "注入没有造成红灯：" + str(t))]))
                continue
        try:
            raw = fn(ctx)
        except Exception as e:
            r = ActResult(k, title, chain, "ERROR", [Verdict("exception", False, repr(e))])
            ctx.results.append(r)
            print("  !! 幕 " + k + " 抛异常 " + repr(e), flush=True)
            continue
        if raw is None:
            r = ActResult(k, title, chain, "NA",
                          [Verdict("not-applicable", True,
                                   "该链型在 headless tier 不成立，如实标 NA（不报绿）")])
        else:
            verdicts, evidence = raw
            status = "PASS" if all(v.ok for v in verdicts) else "FAIL"
            r = ActResult(k, title, chain, status, verdicts, evidence)
            for v in verdicts:
                print("  [" + ("ok" if v.ok else "FAIL") + "] " + v.name + " — " + v.detail, flush=True)
        r.seconds = round(time.time() - t0, 2)
        ctx.results.append(r)
        print("  => 幕 " + k + " " + r.status + " (" + str(r.seconds) + "s)", flush=True)
        if k == "0" and r.status != "PASS":
            print("\n!!! preflight 不成立 —— 后续幕没有意义，停在这里。", flush=True)
            write_evidence(ctx)
            return False, 2

    write_evidence(ctx)
    _, dirty = git_state(args.workspace)
    if args.reset and ctx.base_head:
        run_cmd(["git", "-C", args.workspace, "reset", "--hard", ctx.base_head])
        removed = []
        for rel in sorted(set(ctx.created_files)):
            fp = os.path.join(args.workspace, rel)
            if os.path.isfile(fp):
                os.remove(fp)
                removed.append(rel)
        print("  --reset：工作区已还原到 " + ctx.base_head[:12] + "；清理 " + str(removed), flush=True)
    elif dirty:
        print("  工作区留有 " + str(len(dirty)) + " 条改动（未自动回滚；要还原加 --reset）："
              + str(sorted(dirty)[:6]), flush=True)

    failed = [r.act_id for r in ctx.results if r.status in ("FAIL", "ERROR")]
    print("\n=== 结局 " + ctx.run_label + " === 幕数 " + str(len(ctx.results))
          + "，失败 " + str(failed or "无") + "；证据 " + ctx.out_dir, flush=True)
    for r in ctx.results:
        print("  幕 " + r.act_id + ": " + r.status)
    return not failed, (1 if failed else 0)


def write_repeat_summary(out_dir, args, runs):
    """反复验收的幂等判据：同锚点 / 独立工作区 / 判据形状一致 / 每轮全绿 / 源仓零污染。"""
    os.makedirs(out_dir, exist_ok=True)
    anchors = {r["anchor"] for r in runs}
    workspaces = [r["workspace"] for r in runs]
    shapes = [tuple(r["verdict_names"]) for r in runs]
    _, src_dirty = git_state(os.path.abspath(os.path.expanduser(args.source)))
    checks = [
        ("same-anchor-across-runs", len(anchors) == 1, "锚点集合 " + str(sorted(anchors))),
        ("each-run-has-fresh-workspace", len(set(workspaces)) == len(runs),
         str(len(set(workspaces))) + "/" + str(len(runs)) + " 个独立路径"),
        ("identical-verdict-shape", len(set(shapes)) == 1,
         "判据名集合 " + str(len(set(shapes))) + " 种"),
        ("all-runs-green", all(r["green"] for r in runs),
         " / ".join(r["label"] + "=" + ("PASS" if r["green"] else "FAIL") for r in runs)),
        ("source-repo-still-clean", not src_dirty,
         args.source + " 跑完 " + str(len(runs)) + " 轮后脏行=" + str(len(src_dirty))),
    ]
    payload = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "stage": args.stage, "repeat": len(runs),
        "checks": [{"name": n, "ok": ok, "detail": d} for n, ok, d in checks],
        "runs": runs,
    }
    with open(os.path.join(out_dir, "repeat-summary.json"), "w", encoding="utf-8") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=2)
    lines = ["# 反复验收汇总（stage=" + args.stage + "，共 " + str(len(runs)) + " 轮）", "",
             "| 判据 | 结果 | 读数 |", "| --- | --- | --- |"]
    lines += ["| " + n + " | " + ("ok" if ok else "FAIL") + " | " + d + " |"
              for n, ok, d in checks]
    lines += ["", "| 轮次 | 工作区 | 幕数 | 结论 | 耗时 |", "| --- | --- | --- | --- | --- |"]
    lines += ["| " + r["label"] + " | `" + r["workspace"] + "` | " + str(r["acts"])
              + " | " + ("PASS" if r["green"] else "FAIL") + " | "
              + str(round(r["seconds"], 1)) + "s |" for r in runs]
    lines += ["", "> 每轮都从同一固定锚点检出全新隔离工作区；源仓只读，反复跑不互相污染。", ""]
    with open(os.path.join(out_dir, "repeat-summary.md"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines))
    print("\n=== 反复验收判据 ===", flush=True)
    for n, ok, d in checks:
        print("  [" + ("ok" if ok else "FAIL") + "] " + n + " — " + d, flush=True)
    return all(ok for _, ok, _ in checks)


def main():
    ap = argparse.ArgumentParser(description="SDLC 生命周期 harness（真 agent / 真项目 / 可反复验收）")
    ap.add_argument("--tier", choices=["headless", "real"], default="headless")
    ap.add_argument("--stage", choices=list(STAGES), default="current",
                    help="从哪个生命周期锚点检出：empty/requirements/prototype/implemented/current")
    ap.add_argument("--source", default=os.path.expanduser("~/weather-relay"),
                    help="只读源仓（阶段锚点所在）；stage!=current 时每轮从它 clone 出隔离工作区")
    ap.add_argument("--plan", choices=list(PLANS), default=None,
                    help="幕序列预设：from-zero/bootstrap/iterate/full；--act 显式指定优先")
    ap.add_argument("--repeat", type=int, default=1, help="同一锚点连续重放轮数（每轮全新隔离工作区）")
    ap.add_argument("--workspace", default=os.path.expanduser("~/weather-relay"),
                    help="stage=current 时原地使用的仓")
    ap.add_argument("--agent", action="append", default=None,
                    help="agentKey，可重复传以扩展多 agent；默认 dsh")
    ap.add_argument("--agent-key", default=None, help="覆盖首个 agent 的控制面 key（如 cli-dsh）")
    ap.add_argument("--act", default=None, help="只跑指定幕，逗号分隔；优先于 --plan")
    ap.add_argument("--timeout", type=int, default=900, help="每幕等待沙箱产物的预算秒数")
    ap.add_argument("--out", default=None, help="证据目录；缺省 design/runtime-evidence/sdlc-lifecycle-<时间戳>")
    ap.add_argument("--preflight-only", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--reset", action="store_true", help="收尾显式还原工作区到跑前 HEAD（会丢弃本次改动）")
    args = ap.parse_args()
    if args.repeat < 1:
        print("!!! --repeat 必须 >= 1", flush=True)
        return 2

    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    out_dir = args.out or os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "design", "runtime-evidence", f"sdlc-lifecycle-{stamp}")
    raw_agents = args.agent or ["dsh"]
    agents = [AgentSpec(key=k, cli={"dsh": "dsh", "opencode": "opencode", "codex": "codex",
                                   "claude": "claude", "agy": "agy"}.get(k, k))
              for k in raw_agents]
    if args.agent_key:
        agents[0].key = args.agent_key
    if args.dry_run:
        return dry_run(Context(args=args, agents=agents, out_dir=out_dir))

    order = (args.act.split(",") if args.act else
             (["0"] if args.preflight_only else (PLANS[args.plan] if args.plan else PLANS["full"])))
    for k in order:
        if k not in ACTS:
            print(f"!!! 未知幕 {k}（可用：{','.join(ACTS)}）", flush=True)
            return 2

    print(f"=== SDLC lifecycle stage={args.stage} plan={args.plan or 'custom'} "
          f"repeat={args.repeat} tier={args.tier} agents={[a.key for a in agents]} ===", flush=True)
    runs = []
    for i in range(1, args.repeat + 1):
        label = ("run-" + str(i)) if args.repeat > 1 else "single"
        run_out = os.path.join(out_dir, label) if args.repeat > 1 else out_dir
        try:
            ws, anchor = prepare_workspace(args, os.path.join(RUNS_ROOT, stamp, label))
        except RuntimeError as e:
            print("!!! 隔离检出失败：" + str(e), flush=True)
            return 2
        args.workspace = ws
        ctx = Context(args=args, agents=agents, out_dir=run_out,
                      run_label=label, anchor=anchor)
        t0 = time.time()
        green, _ = run_once(ctx, order)
        runs.append({
            "label": label, "workspace": ws, "anchor": anchor, "green": green,
            "acts": len(ctx.results), "seconds": time.time() - t0,
            "verdict_names": [r.act_id + ":" + v.name
                              for r in ctx.results for v in r.verdicts],
            "failures": [r.act_id for r in ctx.results if r.status in ("FAIL", "ERROR")],
        })
    if args.repeat > 1:
        ok = write_repeat_summary(out_dir, args, runs)
        print("\n=== 反复验收 " + ("PASS" if ok else "FAIL") + " ===", flush=True)
        return 0 if ok else 1
    return 0 if runs[0]["green"] else 1


if __name__ == "__main__":
    sys.exit(main())
