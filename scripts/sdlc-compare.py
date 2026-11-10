#!/usr/bin/env python3
"""多调用方案对比基准采集器。

只采地面真相：task.log 原始 stdout 的 usage 行、patch.diff、result.json、relay.db。
不估算 token、不把受理回执当产物、不把不可用链路写成已测。
证据可回溯：每条读数带 source 路径，关键文件带 sha256。

跑法见 docs/TEST-PLAN-AGENT-CALL-COMPARISON.md。
"""

import argparse
import glob
import hashlib
import json
import os
import sqlite3
import sys
import urllib.error
import urllib.request
from collections import Counter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ARTIFACTS = os.environ.get("E2E_ARTIFACTS", "/tmp/tenon-ws/artifacts")
CONTROL = os.environ.get("E2E_CONTROL", "http://127.0.0.1:8788")
DOC = os.path.join(ROOT, "docs", "TEST-PLAN-AGENT-CALL-COMPARISON.md")

SCHEMES = {
    "single": {
        "entry": "POST /v1/agent/hook/dispatch",
        "chain": "cli-direct",
        "source": "agentNode/src/main/kotlin/com/kapp/tenon/agent/ControlPlaneHookRoutes.kt:385-405",
        "enable_when": "控制面 8788 + CLI 驱动注册；复用 sdlc-lifecycle.py 的 --run 产物",
    },
    "chain": {
        "entry": "POST /v1/agent/chain",
        "chain": "stage-chain",
        "source": "agentNode/src/main/kotlin/com/kapp/tenon/agent/ControlPlaneInvokeRoutes.kt:98-150",
        "enable_when": "桌面宿主装配 chainDispatcher 后真跑 stages，产出 result.json",
    },
    "team": {
        "entry": "POST /v1/agent/invoke",
        "chain": "orchestrator",
        "source": "agentNode/src/main/kotlin/com/kapp/tenon/agent/ControlPlaneInvokeRoutes.kt:67-94",
        "enable_when": "群 + 工作区宿主真入驻席位 + 手机 @ 派单（--tier real）",
    },
}

# 无埋点或需额外探针，一律如实标 NA，不允许估算代替。
UNMEASURABLE = {
    "cost-usd": "无 model/provider 单价表；不允许按估算单价乘出假成本",
    "heal-rounds": "heal loop 未结构化落盘",
    "memory-survives-restart": "ActiveTicket / AgentSessionRegistry 纯内存，需重启探针",
    "memory-hit-rate": "无记忆命中埋点",
    "human-interaction-events": "卡片动作无客户端回执埋点，真机点击需人工记录",
}

METRIC_GROUPS = ("effect", "tokens", "context", "cards", "interaction")


# ---------------------------------------------------------------------------
# 工具
# ---------------------------------------------------------------------------

def sha256_file(path):
    try:
        with open(path, "rb") as fh:
            return hashlib.sha256(fh.read()).hexdigest()
    except OSError:
        return None


def read_json(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def met(value, status, source, unit="", scope="", sha=None, note=""):
    """一条带出处的读数。status: measured / unmeasurable / unavailable。"""
    return {"value": value, "status": status, "unit": unit,
            "source": source, "sha256": sha, "scope": scope, "note": note}


def na(name, status="unmeasurable", reason=""):
    return met(None, status, "n/a", note=reason or UNMEASURABLE.get(name, "无真值来源"))


# ---------------------------------------------------------------------------
# token：从原始 stdout 取证
# ---------------------------------------------------------------------------

USAGE_KEYS = ("inputTokens", "outputTokens", "totalTokens", "cacheReadTokens")


def usage_rows(task_log):
    """只认 status/step_end 且带 usage 对象的行；其他行型一律不算。"""
    rows = []
    if not os.path.isfile(task_log):
        return rows
    with open(task_log, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            line = line.strip()
            if not line.startswith("{"):
                continue
            try:
                obj = json.loads(line)
            except ValueError:
                continue
            if obj.get("type") != "status" or obj.get("phase") != "step_end":
                continue
            usage = obj.get("usage")
            if not isinstance(usage, dict):
                continue
            row = {k: int(usage.get(k) or 0) for k in USAGE_KEYS}
            row["turn"] = obj.get("turn")
            row["step"] = obj.get("step")
            # 语义断言：totalTokens 是该步上下文窗口量，不是累计消耗。
            # 不满足就不采这一行，避免把口径不同的 CLI 混进来。
            if row["totalTokens"] != row["inputTokens"] + row["outputTokens"] + row["cacheReadTokens"]:
                row["semantic_ok"] = False
            else:
                row["semantic_ok"] = True
            rows.append(row)
    return rows


def usage_metrics(rows, sources):
    """billable 只取 input+output；totalTokens 只作为窗口量与分母。"""
    good = [r for r in rows if r["semantic_ok"]]
    if not good:
        return None
    s_in = sum(r["inputTokens"] for r in good)
    s_out = sum(r["outputTokens"] for r in good)
    s_cache = sum(r["cacheReadTokens"] for r in good)
    s_window = sum(r["totalTokens"] for r in good)
    peak = max(r["totalTokens"] for r in good)
    final = good[-1]["totalTokens"]
    turns = {r["turn"] for r in good if r["turn"] is not None}
    src = "; ".join(sources[:3]) + (f" ...共 {len(sources)} 份" if len(sources) > 3 else "")
    out = {
        "usage-lines": met(len(good), "measured", src, "行", note="status/step_end 带 usage"),
        "semantic-rejected-lines": met(len(rows) - len(good), "measured", src, "行",
                                       note="口径不符被丢弃，不参与求和"),
        "input-tokens": met(s_in, "measured", src, "token"),
        "output-tokens": met(s_out, "measured", src, "token"),
        "billable-tokens": met(s_in + s_out, "measured", src, "token",
                               note="Σ(input+output)，不含 cacheRead"),
        "cache-read-tokens": met(s_cache, "measured", src, "token"),
        "context-window-tokens": met(s_window, "measured", src, "token",
                                     note="Σ totalTokens，逐步窗口量之和，不是消耗"),
        "cache-hit-ratio": met(round(s_cache / s_window, 4) if s_window else None,
                               "measured" if s_window else "unmeasurable", src, "ratio",
                               note="ΣcacheRead / ΣtotalTokens"),
        "context-peak-tokens": met(peak, "measured", src, "token"),
        "context-final-tokens": met(final, "measured", src, "token"),
        "step-count": met(len(good), "measured", src, "次"),
        "turn-count": met(len(turns), "measured", src, "次"),
    }
    return out


# ---------------------------------------------------------------------------
# 效果：patch / result.json
# ---------------------------------------------------------------------------

def patch_stats(path):
    files, added, removed = set(), 0, 0
    if not os.path.isfile(path):
        return 0, 0, 0
    with open(path, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            if line.startswith("diff --git "):
                parts = line.split(" b/", 1)
                if len(parts) == 2:
                    files.add(parts[1].strip())
            elif line.startswith("+") and not line.startswith("+++"):
                added += 1
            elif line.startswith("-") and not line.startswith("---"):
                removed += 1
    return len(files), added, removed


def artifact_dir(evidence):
    name = evidence.get("artifact")
    return os.path.join(ARTIFACTS, name) if name else None


def effect_metrics(run_dir, data):
    result_path = os.path.join(run_dir, "result.json")
    sha = sha256_file(result_path)
    acts = data.get("acts", [])
    pass_n = sum(1 for a in acts if a.get("status") == "PASS")
    real = [a for a in acts if a.get("status") in ("PASS", "FAIL")]
    seconds = sum(float(a.get("seconds") or 0) for a in acts)
    dispatches, prompts, all_rows, log_paths, patch_paths = [], [], [], [], []
    files = added = removed = 0
    for a in acts:
        ev = a.get("evidence") or {}
        d = artifact_dir(ev)
        if d and ev.get("artifact"):
            dispatches.append(ev["artifact"])
            log = os.path.join(d, "task.log")
            if os.path.isfile(log):
                log_paths.append(log)
                all_rows.extend(usage_rows(log))
            patch = ev.get("bootstrap_patch") or ev.get("patch")
            if not isinstance(patch, str) or not os.path.isfile(patch):
                patch = os.path.join(d, "patch.diff")
            if os.path.isfile(patch):
                patch_paths.append(patch)
                f, x, y = patch_stats(patch)
                files += f
                added += x
                removed += y
        prompt = ev.get("prompt")
        if isinstance(prompt, str):
            prompts.append(prompt)
    src = result_path
    out = {
        "acts-pass": met(pass_n, "measured", src, "条", sha=sha),
        "acts-total": met(len(real), "measured", src, "条", sha=sha),
        "duration-sec": met(round(seconds, 2), "measured", src, "秒", sha=sha),
        "dispatch-count": met(len(set(dispatches)), "measured", src, "次",
                              note="去重后的 t-* 产物目录数"),
        "patch-files": met(files, "measured", "; ".join(patch_paths[:2]), "个"),
        "patch-added": met(added, "measured", "; ".join(patch_paths[:2]), "行"),
        "patch-removed": met(removed, "measured", "; ".join(patch_paths[:2]), "行"),
        "commits-count": met(len(data.get("device_commits") or []), "measured", src, "条", sha=sha),
        "prompt-chars-total": met(sum(len(p) for p in prompts), "measured", src, "字符"),
        "prompt-chars-max": met(max((len(p) for p in prompts), default=0), "measured", src, "字符"),
        "prompt-sha256-list": met([hashlib.sha256(p.encode()).hexdigest()[:16] for p in prompts],
                                  "measured", src, "摘要", note="验三同：任务书没变"),
        "dispatch-rounds": met(len(set(dispatches)), "measured", src, "次"),
        "same-card-id-reused": na("same-card-id-reused", "unavailable",
                                  "直驱链无卡片出口；chain 档按同 cardId 的 rev 序列判"),
    }
    base = data.get("base_tests") or {}
    out["tests-pass"] = met(base.get("pass"), "measured" if base else "unmeasurable", src, "条",
                            note="基线测试；幕内测试读数见各幕判据")
    out["tests-fail"] = met(base.get("fail"), "measured" if base else "unmeasurable", src, "条")
    out.update(usage_metrics(all_rows, log_paths) or {
        k: na(k, "unmeasurable", "该 CLI 的 task.log 没有可采的 usage 行")
        for k in ("usage-lines", "input-tokens", "output-tokens", "billable-tokens",
                  "cache-read-tokens", "cache-hit-ratio", "context-peak-tokens",
                  "context-final-tokens", "step-count", "turn-count")
    })
    out["cost-usd"] = na("cost-usd")
    out["heal-rounds"] = na("heal-rounds")
    out["memory-survives-restart"] = na("memory-survives-restart")
    out["memory-hit-rate"] = na("memory-hit-rate")
    return out


# ---------------------------------------------------------------------------
# 界面：relay.db 卡片
# ---------------------------------------------------------------------------

def desktop_db():
    override = os.environ.get("E2E_DESKTOP_DIR")
    if override:
        return os.path.join(override, "relay.db")
    support = os.path.join(os.path.expanduser("~"), "Library", "Application Support")
    for name in ("榫卯 Tenon", "捎话 Tidings"):
        candidate = os.path.join(support, name, "relay.db")
        if os.path.isfile(candidate):
            return candidate
    return os.path.join(support, "榫卯 Tenon", "relay.db")


def card_metrics(run_dir, data, db_path):
    db_sha = sha256_file(db_path)
    if not os.path.isfile(db_path):
        return {k: na(k, "unavailable", "卡片库不存在：" + db_path)
                for k in ("card-rows-in-window", "card-type-histogram", "card-rev-max",
                          "card-state-sequence", "card-action-buttons",
                          "card-artifact-entries", "agent-state-banners")}
    result_path = os.path.join(run_dir, "result.json")
    end_ms = os.path.getmtime(result_path) * 1000
    total_sec = sum(float(a.get("seconds") or 0) for a in data.get("acts", []))
    start_ms = end_ms - (total_sec + 120) * 1000
    try:
        con = sqlite3.connect("file:" + db_path + "?mode=ro", uri=True, timeout=5.0)
        rows = con.execute(
            # 最新载荷（与 sdlc-lifecycle.py 的 LATEST_CARD_SQL 同一条表达式）：
            # card_json 是首帧锚、永不刷新，只读它会把每张卡都读成 rev=1/proposed，
            # `card-rev-max` 恒为 1、`card-state-sequence` 恒为 proposed —— 指标本身
            # 就在撒谎（2026-11-10 真机读库实测：payload 里 32 张卡带 delivered，
            # card_json 里 0 张）。
            "select timestamp, coalesce(card_payload, card_json), agent_state from MessageEntity "
            "where timestamp >= ? and timestamp <= ? order by timestamp asc",
            (start_ms, end_ms + 120000),
        ).fetchall()
        whole_db_cards = con.execute(
            "select count(*) from MessageEntity "
            "where coalesce(card_payload, card_json) is not null"
        ).fetchone()[0]
        con.close()
    except (OSError, sqlite3.Error) as exc:
        return {k: na(k, "unavailable", "卡片库读取失败：" + str(exc))
                for k in ("card-rows-in-window", "card-type-histogram", "card-rev-max",
                          "card-state-sequence", "card-action-buttons",
                          "card-artifact-entries", "agent-state-banners",
                          "window-message-count", "whole-db-card-count")}
    # 反向探针：窗口里一条消息都没有 ⇒「卡片 0」是时间窗错位，不是链路没出卡。
    # 不设这道守卫，读数 0 就是假绿（与「pgrep 命不中 ⇒ 无孤儿进程」同一类错）。
    window_ok = bool(rows)
    cards, states, types, actions, artifacts = [], [], Counter(), 0, 0
    for _, raw, agent_state in rows:
        if agent_state:
            states.append(agent_state)
        if not raw:
            continue
        try:
            card = json.loads(raw)
        except (TypeError, ValueError):
            continue
        cards.append(card)
        if card.get("type"):
            types[str(card["type"])] += 1
        acts = card.get("actions")
        artifacts_list = card.get("artifacts")
        if isinstance(acts, list):
            actions += len(acts)
        if isinstance(artifacts_list, list):
            artifacts += len(artifacts_list)
    revs = [int(c.get("rev") or 0) for c in cards]
    seq = [(c.get("cardId"), c.get("rev"), c.get("state")) for c in cards]
    scope = "run 时间窗 [%.0f, %.0f] ms；卡片库本机快照" % (start_ms, end_ms)
    src = db_path
    card_status = "measured" if window_ok else "unavailable"
    window_note = "" if window_ok else (
        "时间窗内无任何消息：可能是链路本身不写 MessageEntity（直驱链预期），"
        "也可能是时间窗错位；两者无法自动区分，故卡片读数标 NA 而非 0")
    return {
        "window-message-count": met(len(rows), "measured", src, "条", scope, db_sha,
                                    note="反向探针：为 0 则下列卡片读数全部降级"),
        "whole-db-card-count": met(whole_db_cards, "measured", src, "条",
                                   "全库快照（非本 run）", db_sha),
        "card-rows-in-window": met(len(cards), card_status, src, "条", scope, db_sha,
                                   note=window_note or "直驱链无卡片出口时为 0 是链路性质"),
        "card-type-histogram": met(dict(types), card_status, src, "分布", scope, db_sha, note=window_note),
        "card-rev-max": met(max(revs, default=0), card_status, src, "rev", scope, db_sha, note=window_note),
        "card-state-sequence": met(seq[:20], card_status, src, "序列", scope, db_sha,
                                   note=window_note or
                                   "每张卡的（最新 rev, 终态）；库只留最新载荷，历史 rev 不留存。"
                                   "只列前 20 条，完整序列见 comparison.json"),
        "card-action-buttons": met(actions, card_status, src, "个", scope, db_sha, note=window_note),
        "card-artifact-entries": met(artifacts, card_status, src, "个", scope, db_sha, note=window_note),
        "agent-state-banners": met(len(states), card_status, src, "条", scope, db_sha, note=window_note),
    }


def preview_wired():
    """静态真值：唯一生产渲染入口是否传了预览槽。"""
    p = os.path.join(ROOT, "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/chat/ChatMessageList.kt")
    try:
        with open(p, encoding="utf-8") as fh:
            src = fh.read()
        wired = "onPreviewArtifact" in src and "onPreviewArtifact =" in src
    except OSError:
        wired = False
    return {
        "preview-entry-wired": met(wired, "measured", p, "bool", sha=sha256_file(p),
                                   note="false = 产物预览入口整块不画（客户端现状）"),
        "human-interaction-events": na("human-interaction-events"),
    }


# ---------------------------------------------------------------------------
# 链路可用性：真探测，不假跑
# ---------------------------------------------------------------------------

def probe_host():
    """空 stages 探测 chain 执行器是否装配；空 stages 不会真起链。"""
    req = urllib.request.Request(
        CONTROL + "/v1/agent/chain",
        data=json.dumps({"stages": []}).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    # 本地回环必须绕过系统代理，否则宿主没起时会收到代理层的 502，
    # 被误读成「控制面异常」。
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(req, timeout=3) as resp:
            return {"reachable": True, "http": resp.status, "chain-executor": "present"}
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", "replace")[:160]
        if exc.code == 503:
            return {"reachable": True, "http": exc.code, "chain-executor": "absent", "body": body}
        if exc.code == 400:
            return {"reachable": True, "http": exc.code, "chain-executor": "present", "body": body}
        return {"reachable": True, "http": exc.code, "chain-executor": "unknown", "body": body}
    except Exception as exc:
        return {"reachable": False, "chain-executor": "unknown", "error": str(exc)[:120]}


# ---------------------------------------------------------------------------
# 汇总
# ---------------------------------------------------------------------------

def collect(scheme, run_dir, db_path, label=None):
    label = label or scheme
    result_path = os.path.join(run_dir, "result.json")
    if not os.path.isfile(result_path):
        return {"scheme": scheme, "label": label, "status": "unavailable", **SCHEMES[scheme],
                "reason": "没有 result.json：" + run_dir,
                "enable_when": SCHEMES[scheme]["enable_when"], "metrics": {}}
    data = read_json(result_path)
    metrics = {}
    metrics.update(effect_metrics(run_dir, data))
    metrics.update(card_metrics(run_dir, data, db_path))
    metrics.update(preview_wired())
    return {"scheme": scheme, "label": label, "status": "measured", **SCHEMES[scheme],
            "run_dir": os.path.abspath(run_dir), "anchor": data.get("anchor"),
            "source": data.get("source"), "stage": data.get("stage"),
            "tier": data.get("tier"), "result_sha256": sha256_file(result_path),
            "metrics": metrics}


def unavailable(scheme, reason, label=None, status="unavailable"):
    return {"scheme": scheme, "label": label or scheme,
            "status": status, **SCHEMES[scheme],
            "reason": reason, "enable_when": SCHEMES[scheme]["enable_when"], "metrics": {}}


def write_outputs(out_dir, runs, host):
    os.makedirs(out_dir, exist_ok=True)
    payload = {
        "generated_at": __import__("datetime").datetime.now().isoformat(timespec="seconds"),
        "control_plane_probe": host,
        "principles": ["同一任务书", "同一阶段锚点", "同一判据集合"],
        "metric_groups": list(METRIC_GROUPS),
        "unmeasurable": UNMEASURABLE,
        "runs": runs,
    }
    jpath = os.path.join(out_dir, "comparison.json")
    with open(jpath, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=2)
    lines = ["# 多调用方案对比读数", "",
             "三同原则：同一任务书 / 同一阶段锚点 / 同一判据集合。破一条则本表不给差异结论。", "",
             "## 控制面探测", "", "```", json.dumps(host, ensure_ascii=False), "```", "",
             "## 各档状态", "",
             "| 轮次 | scheme | 入口 | 链路 | 状态 | 原因 |",
             "| :--- | :--- | :--- | :--- | :--- | :--- |"]
    for r in runs:
        lines.append("| `%s` | `%s` | `%s` | `%s` | **%s** | %s |" % (
            r["label"], r["scheme"], r["entry"], r["chain"], r["status"],
            r.get("reason", "真值已采") if r["status"] != "measured" else
            "锚点 %s，证据 %s" % (r.get("anchor"), os.path.basename(r.get("run_dir", "")))))
    measured = [r for r in runs if r["status"] == "measured"]
    all_keys = []
    for r in measured:
        for k in r["metrics"]:
            if k not in all_keys:
                all_keys.append(k)
    lines += ["", "## 指标横向对比", "",
              "> 只列各档同名指标；`unavailable` / `unmeasurable` 不填数字，不给差异。", "",
              "| 指标 | " + " | ".join("`%s`" % r["label"] for r in measured) + " | 状态 |",
              "| :--- | " + " | ".join("---" for _ in measured) + " | :--- |"]
    for k in all_keys:
        vals, statuses = [], []
        for r in measured:
            m = r["metrics"].get(k)
            if m and m["status"] == "measured":
                v = m["value"]
                vals.append(str(v)[:70] if not isinstance(v, (dict, list)) else json.dumps(v, ensure_ascii=False)[:70])
                statuses.append("measured")
            else:
                vals.append("—")
                statuses.append((m or {}).get("status", "unavailable"))
        lines.append("| `%s` | %s | %s |" % (k, " | ".join(vals), "/".join(sorted(set(statuses)))))
    lines += ["", "## 不可测项（如实留白，不估算）", "",
              "| 指标 | 原因 |", "| :--- | :--- |"]
    for k, why in UNMEASURABLE.items():
        lines.append("| `%s` | %s |" % (k, why))
    lines += ["", "## 证据", "", "完整读数（含每条 source 与 sha256）：`comparison.json`。", "",
              "关键证据文件：" ]
    for r in measured:
        lines.append("- `%s`：`%s` sha256=`%s`" % (
            r["scheme"], r.get("run_dir"), r.get("result_sha256")))
    mpath = os.path.join(out_dir, "comparison.md")
    with open(mpath, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")
    return jpath, mpath


# ---------------------------------------------------------------------------
# 自检
# ---------------------------------------------------------------------------

def dry_run():
    with open(os.path.abspath(__file__), encoding="utf-8") as fh:
        src = fh.read()
    with open(DOC, encoding="utf-8") as fh:
        doc = fh.read()
    hook = os.path.join(ROOT, SCHEMES["single"]["source"].split(":")[0])
    invoke = os.path.join(ROOT, SCHEMES["chain"]["source"].split(":")[0])
    checks = [
        ("five-metric-groups", all(g in doc and g in src for g in METRIC_GROUPS)),
        ("three-schemes-declared", all(s in src and ("`" + s + "`") in doc for s in SCHEMES)),
        ("single-entry-real", "/v1/agent/hook/dispatch" in open(hook, encoding="utf-8").read()),
        ("chain-entry-real", "/v1/agent/chain" in open(invoke, encoding="utf-8").read()),
        ("invoke-entry-real", "/v1/agent/invoke" in open(invoke, encoding="utf-8").read()),
        ("usage-semantic-assert",
         'row["totalTokens"] != row["inputTokens"] + row["outputTokens"] + row["cacheReadTokens"]' in src),
        ("billable-not-total", "billable-tokens\": met(s_in + s_out" in src),
        # 守卫字符串要拼接，否则它会命中自己源码里的字面量。
        ("no-estimated-tokens", ("per_" + "char") not in src and ("ESTI" + "MATE") not in src),
        ("no-dynamic-verdict-names",
         ("Verdict(" + '"apply:"') not in src and 'f"' not in src.split("def met(")[0]),
        ("unmeasurable-listed", all(k in src and k in doc for k in UNMEASURABLE)),
        ("cost-is-na", 'na("cost-usd")' in src and "cost-usd` 判 `unmeasurable`" in doc),
        ("evidence-has-source-and-sha", '"source": source' in src and "sha256" in src),
        ("card-window-not-whole-table", "timestamp >= ?" in src),
        ("probe-uses-empty-stages", '{"stages": []}' in src),
        ("unavailable-not-measured", "status\": \"unavailable\"" in src),
        ("three-same-principles", "同一任务书" in doc and "同一阶段锚点" in doc and "同一判据集合" in doc),
        ("doc-declares-no-fake", "不认截图" in doc and "不估算" in doc),
        ("card-zero-is-honest", "直驱链无卡片出口" in src),
        ("card-zero-has-reverse-probe", "window-message-count" in src and "window_ok = bool(rows)" in src),
        ("same-scheme-multirun-kept", "given.append((scheme, path))" in src),
        ("availability-is-probed", "available-not-run" in src and "requires-device" in src),
        ("probe-bypasses-proxy", "ProxyHandler({})" in src),
    ]
    print("=== dry-run：对比基准自检 ===")
    bad = 0
    for name, ok in checks:
        print("  [%s] %s" % ("ok" if ok else "FAIL", name))
        if not ok:
            bad += 1
    print("守卫 %d 项，%d 项红" % (len(checks), bad))
    return 0 if bad == 0 else 1


# ---------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description="多调用方案对比基准采集器")
    ap.add_argument("--run", action="append", default=[], metavar="SCHEME=DIR",
                    help="可重复，如 single=design/runtime-evidence/.../run-1")
    ap.add_argument("--out", default=os.path.join(ROOT, "design/runtime-evidence/agent-call-comparison"))
    ap.add_argument("--db", default=None, help="覆盖 relay.db 路径")
    ap.add_argument("--no-probe-host", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()
    if args.dry_run:
        return dry_run()
    host = {"skipped": True} if args.no_probe_host else probe_host()
    db_path = args.db or desktop_db()
    given = []
    for item in args.run:
        if "=" not in item:
            print("参数格式应为 SCHEME=DIR：" + item, file=sys.stderr)
            return 2
        scheme, path = item.split("=", 1)
        if scheme not in SCHEMES:
            print("未知 scheme：" + scheme, file=sys.stderr)
            return 2
        given.append((scheme, path))
    runs = []
    # 同一档允许多轮，互不覆盖：同档多轮是稳定性读数，不同档才是方案对比。
    for scheme, path in given:
        label = "%s/%s" % (scheme, os.path.basename(os.path.normpath(path)))
        runs.append(collect(scheme, path, db_path, label))
    for scheme in SCHEMES:
        if scheme in {g[0] for g in given}:
            continue
        reason = "未提供该档真实 result.json"
        status = "unavailable"
        if scheme == "chain" and host.get("chain-executor") == "absent":
            reason = "控制面真探测 503 no-chain-executor：壳层未装配 chainDispatcher"
        elif scheme == "chain" and host.get("chain-executor") == "present":
            reason = ("控制面真探测 400（空 stages 校验通过）⇒ chainDispatcher 已装配；"
                      "本轮未真跑 stages，且尚未与 single 档做等价任务映射，不给差异结论")
            status = "available-not-run"
        elif scheme == "team":
            reason = "需群 + 席位入驻 + 手机 @ 派单；harness 不自动触发，不假跑"
            status = "requires-device"
        elif host.get("reachable") is False:
            reason = "控制面不可达：" + str(host.get("error", ""))
        runs.append(unavailable(scheme, reason, status=status))
    jpath, mpath = write_outputs(args.out, runs, host)
    measured = sum(1 for r in runs if r["status"] == "measured")
    others = dict(Counter(r["status"] for r in runs if r["status"] != "measured"))
    print("采集完成：%d 档 measured；其余 %s" % (measured, others or "无"))
    print("证据：", jpath, mpath)
    for r in runs:
        m = r["metrics"]
        if r["status"] == "measured":
            print("  %s: billable=%s contextPeak=%s cacheHit=%s cardRows=%s" % (
                r["label"],
                (m.get("billable-tokens") or {}).get("value"),
                (m.get("context-peak-tokens") or {}).get("value"),
                (m.get("cache-hit-ratio") or {}).get("value"),
                (m.get("card-rows-in-window") or {}).get("value")))
        else:
            print("  %s: %s — %s" % (r["label"], r["status"], r["reason"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
