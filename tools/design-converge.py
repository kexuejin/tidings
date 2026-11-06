#!/usr/bin/env python3
"""设计侧取值收敛提案器（只读）：把原型 CSS 里成堆的字面量映射到代码侧 token 档位。

回答的是"间距归一化谁来做"里的那个缺口：`fidelity-ledger.py` 只数出
"设计侧 133 种取值 / 代码侧 8 档"，**它不给映射表**。这个工具给表——
逐条列出 取值 → 建议档 → 差值 → 状态 → 影响哪些选择器，**一个字都不改**。

三种用法：
    python3 scaffold/tools/design-converge.py --repo .                  # 打印提案表
    python3 scaffold/tools/design-converge.py --repo . --markdown <p>   # 落成评审文档
    python3 scaffold/tools/design-converge.py --repo . --check          # 棘轮：设计侧取值只许收敛
    python3 scaffold/tools/design-converge.py --self-test               # 先验量具

只读保证：本工具只 open(..., "r")，不写设计源；唯一的写动作是 --markdown/--write-baseline 指定的输出路径。

状态词表：
    exact    该取值本来就是代码侧 token（含 0）
    snap     能落到最近的 token，差值在容差内 —— 可以直接归一化
    review   差值超出容差（通常是"这个取值本身就该重定"），必须人裁决
    special  非 px 数值（%、var()、calc、em），**不参与归一化**，单独列出
"""
from __future__ import annotations

import argparse
import collections
import hashlib
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import design_contract as dc  # noqa: E402

# ── 每产品配置（照抄 fidelity-ledger.py 的形态：改这三处即可搬到别的产品）──
CODE_ROOT = "."
DESIGN_SOURCES = ["prototype/relay.html"]
BASELINE_REL = "tools/design-converge-baseline.json"

# 维度 → (CSS 属性, 代码侧 token 组, 容差)
DIMENSIONS = {
    "spacing": {
        "props": ("padding", "padding-top", "padding-right", "padding-bottom", "padding-left",
                  "gap", "row-gap", "column-gap",
                  "margin", "margin-top", "margin-right", "margin-bottom", "margin-left"),
        "token_group": "spacing",
        "tolerance": 1.5,
    },
    "radius": {
        "props": ("border-radius", "border-top-left-radius", "border-top-right-radius",
                  "border-bottom-left-radius", "border-bottom-right-radius"),
        "token_group": "shape",
        "tolerance": 1.0,
    },
    "font-size": {
        "props": ("font-size",),
        "token_group": "typography-size",
        "tolerance": 0.75,
    },
    "font-weight": {
        "props": ("font-weight",),
        "token_group": "typography-weight",
        "tolerance": 0.0,
    },
}

PX = re.compile(r"^(-?\d+(?:\.\d+)?)px$")
NUM = re.compile(r"^-?\d+(?:\.\d+)?$")


class ConvergeError(RuntimeError):
    pass


# ── 设计源解析（只读）───────────────────────────────────────────────
def _strip_comments(css: str) -> str:
    return re.sub(r"/\*.*?\*/", "", css, flags=re.S)


def read_design_source(path: str):
    """返回 (css, meta)。meta 带 sha256 前 12 位与字节数——设计源在被别人改，表必须钉在某个版本上。"""
    if not os.path.exists(path):
        raise ConvergeError(f"设计源不存在：{path}")
    raw = open(path, "r", encoding="utf-8").read()
    m = re.search(r"<style[^>]*>(.*?)</style>", raw, re.S)
    if not m:
        raise ConvergeError(f"{path} 里找不到 <style> 块")
    css = _strip_comments(m.group(1))
    meta = {
        "path": path,
        "sha256": hashlib.sha256(raw.encode("utf-8")).hexdigest()[:12],
        "bytes": len(raw.encode("utf-8")),
        "cssChars": len(css),
    }
    return css, meta


def iter_declarations(css: str):
    """产 (选择器, 属性, 值)。只认一层 {}；原型没有 @media，但真出现嵌套时整块跳过并记账。"""
    nested = 0
    for sel, body in re.findall(r"([^{}]*)\{([^{}]*)\}", css):
        sel = " ".join(sel.split())
        for decl in body.split(";"):
            if ":" not in decl:
                continue
            prop, val = decl.split(":", 1)
            prop, val = prop.strip().lower(), " ".join(val.split())
            if not prop or not val:
                continue
            if prop.startswith("@"):
                nested += 1
                continue
            yield sel, prop, val
    if nested:
        raise ConvergeError("CSS 里出现了 @ 规则，本工具只处理一层规则，请先人工确认")


def _split_top_level(val: str):
    """按**括号外的**空白拆分简写。calc(var(--status) + 10px) 必须整体保留，
    否则会被拆成 `calc(var(--status)` / `+` / `10px)` 三个假取值。"""
    parts, buf, depth = [], [], 0
    for ch in val:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if ch == " " and depth == 0:
            if buf:
                parts.append("".join(buf))
                buf = []
            continue
        buf.append(ch)
    if buf:
        parts.append("".join(buf))
    return parts


SHORTHAND_PROPS = ("padding", "margin", "gap", "row-gap", "column-gap", "border-radius")


def _values_of(prop: str, val: str):
    """拆简写：padding: 9px 11px → ['9px','11px']；calc()/var() 整体保留。"""
    if prop in SHORTHAND_PROPS and " " in val:
        parts = _split_top_level(val)
        return parts if len(parts) > 1 else [val]
    return [val]


# 原型自己的演示层（画布/注释/导航/大字模式），不是产品 UI —— 它们的取值
# 不进"还原上限"的分母（样本：#stage 的 26/60px 画布边距曾被判成产品欠债）。
CHROME_SELECTORS = ("#stage", "#chrome", ".capdiv", ".annot", "#gallery",
                    ".dnav", ".drawer", "body.font-lg", "html.font-lg")


def is_chrome(sel: str) -> bool:
    return any(c in sel for c in CHROME_SELECTORS)


def scan_dimension(css: str, dim: str):
    """→ {raw: {"count": n, "props": {prop: n}, "sites": [(sel, prop)]}}；chrome 另账。"""
    spec = DIMENSIONS[dim]
    props = set(spec["props"])
    out: dict = collections.OrderedDict()
    for sel, prop, val in iter_declarations(css):
        if prop not in props:
            continue
        if is_chrome(sel):
            continue
        for v in _values_of(prop, val):
            slot = out.setdefault(v, {"count": 0, "props": collections.Counter(), "sites": []})
            slot["count"] += 1
            slot["props"][prop] += 1
            if len(slot["sites"]) < 200:
                slot["sites"].append(f"{sel} {{{prop}}}")
    return out


# ── 代码侧 token 档位（共用唯一读取器）──────────────────────────────
def token_targets(code) -> dict:
    spacing = {int(v): f"{k}{int(v)}" for k, v in code["spacing"].items()}
    shape = {int(v): f"{k}{int(v)}" for k, v in code["shape"].items()}
    sizes: dict = collections.OrderedDict()
    weights: dict = collections.OrderedDict()
    for role, t in code["typography"].items():
        sizes.setdefault(float(t["sizeSp"]), []).append(role)
        weights.setdefault(t["weight"], []).append(role)
    return {
        "spacing": {v: k for v, k in spacing.items()},
        "shape": {v: k for v, k in shape.items()},
        "typography-size": {v: "/".join(roles[:3]) for v, roles in sizes.items()},
        "typography-weight": weights,
    }


WEIGHT_NUM = {"regular": 400.0, "medium": 500.0, "semibold": 600.0, "bold": 700.0}
WEIGHT_KEYWORD = {"normal": 400.0, "bold": 700.0}


def classify(raw: str, targets, tol: float, unitless: bool = False):
    """→ (status, target_value, delta, note)"""
    if raw in ("0", "0px"):
        return "exact", 0.0, 0.0, "0 恒可用"
    if raw.startswith("var("):
        return "variable", None, None, "已走变量：收敛时应指向对应 token 变量"
    if unitless:
        if raw in WEIGHT_KEYWORD:
            v = WEIGHT_KEYWORD[raw]
        elif NUM.match(raw):
            v = float(raw)
        else:
            return "special", None, None, "非数值字重（inherit/bolder 等），不参与归一化"
        if not targets:
            return "review", None, None, "代码侧没有该维度的 token 槽位：只能扩 token 或收敛设计"
        best = min(targets, key=lambda t: (abs(t - v), t))
        delta = v - best
        if abs(delta) <= 1e-9:
            return "exact", best, 0.0, ""
        return "review", best, delta, f"偏差 {delta:+.0f}，超出容差 {tol}"
    m = PX.match(raw)
    if not m:
        if NUM.match(raw):
            return "special", None, None, "无单位数字（多用于 flex/line-height），不参与归一化"
        return "special", None, None, "非 px 值（%/var()/calc/em），不参与归一化"
    v = float(m.group(1))
    if v < 0:
        return "special", None, None, "负值（布局补偿），不是节奏档位，不参与归一化"
    if v >= 999:
        return "special", None, None, "999px 哨兵值 = 胶囊/圆形，对应代码侧 CircleShape（chip 槽）"
    if not targets:
        return "review", None, None, "代码侧没有该维度的 token 槽位：只能扩 token 或收敛设计"
    best = min(targets, key=lambda t: (abs(t - v), t))
    delta = v - best
    if abs(delta) <= 1e-9:
        return "exact", best, 0.0, ""
    status = "snap" if abs(delta) <= tol else "review"
    return status, best, delta, "" if status == "snap" else f"偏差 {delta:+.1f}，超出容差 {tol}"


def build_report(root: str, repo: str):
    code = dc.read_code_tokens(root)
    targets = token_targets(code)
    dims = {}
    for dim, spec in DIMENSIONS.items():
        group = spec["token_group"]
        tg = targets.get(group) or {}
        if dim == "font-weight":
            tg = {WEIGHT_NUM[k]: k for k in targets["typography-weight"] if k in WEIGHT_NUM}
        raw = scan_dimension(repo_css(repo), dim)
        rows = []
        after = collections.Counter()
        for value, slot in sorted(raw.items(), key=lambda kv: -kv[1]["count"]):
            status, target, delta, note = classify(value, tg, spec["tolerance"], unitless=(dim == "font-weight"))
            label = tg.get(target, "") if target is not None else ""
            rows.append({
                "value": value, "count": slot["count"],
                "props": dict(slot["props"]), "sites": slot["sites"],
                "status": status, "target": target,
                "targetLabel": label, "delta": delta, "note": note,
            })
            if status in ("exact", "snap"):
                after[target] += slot["count"]
        dims[dim] = {
            "distinct": len(raw), "sites": sum(s["count"] for s in raw.values()),
            "targets": {str(k): v for k, v in tg.items()},
            "rows": rows,
            "after": {str(k): v for k, v in sorted(after.items())},
            "afterDistinct": len(after),
        }
    return dims


_CSS_CACHE: dict = {}


def repo_css(repo: str):
    if repo not in _CSS_CACHE:
        paths = [os.path.join(repo, p) for p in DESIGN_SOURCES]
        css, meta = read_design_source(paths[0])
        _CSS_CACHE[repo] = (css, meta)
    return _CSS_CACHE[repo][0]


def design_meta(repo: str):
    repo_css(repo)
    return _CSS_CACHE[repo][1]


# ── 报告 ───────────────────────────────────────────────────────────
STATUS_ORDER = ["exact", "snap", "variable", "review", "special"]


def summarize(dims, meta):
    lines = [f"# 设计侧取值收敛提案（只读）", ""]
    lines.append(f"设计源 `{meta['path']}` @ sha256:{meta['sha256']}（{meta['bytes']} B，CSS {meta['cssChars']} 字符）")
    lines.append("")
    lines.append("| 维度 | 设计侧取值种数 | 设计侧出现次数 | 归一化后档位 | exact | snap | variable | review | special |")
    lines.append("|---|---|---|---|---|---|---|---|---|")
    for dim, d in dims.items():
        c = collections.Counter(r["status"] for r in d["rows"])
        lines.append(f"| {dim} | **{d['distinct']}** | {d['sites']} | {d['afterDistinct']} | "
                     f"{c['exact']} | {c['snap']} | {c['variable']} | {c['review']} | {c['special']} |")
    lines.append("")
    for dim, d in dims.items():
        tg = ", ".join(f"{v}={k}" for k, v in sorted(d["targets"].items(), key=lambda kv: kv[1]))
        lines.append(f"## {dim}")
        lines.append("")
        lines.append(f"代码侧档位（{len(d['targets'])}）：{tg or '（无）'}")
        lines.append("")
        lines.append("| 取值 | 次数 | 属性分布 | 建议档 | 差 | 状态 | 说明 |")
        lines.append("|---|---|---|---|---|---|---|")
        for r in sorted(d["rows"], key=lambda r: (STATUS_ORDER.index(r["status"]), -r["count"])):
            props = " / ".join(f"{k} {v}" for k, v in sorted(r["props"].items(), key=lambda kv: -kv[1])[:3])
            delta = "" if r["delta"] in (None, 0.0) else f"{r['delta']:+.1f}"
            lines.append(f"| `{r['value']}` | {r['count']} | {props} | "
                         f"{r['targetLabel'] or ('' if r['target'] is None else r['target'])} | {delta} | {r['status']} | {r['note']} |")
        lines.append("")
        lines.append(f"归一化后分布：{', '.join(f'{k}×{v}' for k, v in sorted(d['after'].items(), key=lambda kv: float(kv[0])))}")
        lines.append("")
    return "\n".join(lines)


def signature(dims, meta):
    """棘轮签名：设计侧每个维度的取值种数 + 归一化后档位数。只许下降。"""
    rel_path = os.path.relpath(meta["path"], CODE_ROOT) if os.path.isabs(meta["path"]) else meta["path"]
    return {
        "source": {"path": rel_path, "sha256": meta["sha256"]},
        "dimensions": {dim: {"distinct": d["distinct"], "afterDistinct": d["afterDistinct"],
                             "variable": sum(1 for r in d["rows"] if r["status"] == "variable"),
                             "review": sum(1 for r in d["rows"] if r["status"] == "review"),
                             "special": sum(1 for r in d["rows"] if r["status"] == "special")}
                       for dim, d in dims.items()},
    }


def compare(cur, base):
    """→ (issues, notes)。sha 变化是**一次性警示**不是违规：原型在别人手里持续被改是常态，
    每维度刷一条红只会让人习惯性忽略门禁。真违规只有棘轮：种数上涨 / 待裁决上涨 / 变量比例倒退。"""
    issues, notes = [], []
    bs = (base.get("source") or {}).get("sha256")
    if bs and cur["source"]["sha256"] != bs:
        notes.append(f"设计源已变（基线 sha {bs} → 当前 {cur['source']['sha256']}）"
                     f"——违规判定前先确认这条变化是谁的编辑")
    for dim, b in (base.get("dimensions") or {}).items():
        c = cur["dimensions"].get(dim)
        if not c:
            issues.append(f"{dim}: 当前报告缺该维度")
            continue
        if c["distinct"] > b["distinct"]:
            issues.append(f"{dim}: 设计侧取值种数 {b['distinct']} → {c['distinct']}（只许收敛，新增了取值）")
        if c["review"] > b.get("review", 0):
            issues.append(f"{dim}: 待裁决取值 {b.get('review', 0)} → {c['review']}（只许减少）")
        if c["variable"] < b.get("variable", 0):
            issues.append(f"{dim}: 走变量的取值 {b.get('variable', 0)} → {c['variable']}（只许增加：已系统化的部分不许退回字面量）")
    return issues, notes


# ── 自检：先验量具 ─────────────────────────────────────────────────
SELF_TEST_CSS = """
.a { padding: 9px 11px; gap: 8px; }
.b { padding: 0 13px; gap: 5px; }
.c { border-radius: 9px; }
.d { border-radius: 50%; }
.e { font-size: 11.5px; font-weight: 650; }
.f { padding: var(--pad-row); margin-top: 16px; }
"""


def self_test() -> list:
    fails = []

    def expect(cond, msg):
        if not cond:
            fails.append(msg)

    dims = {}
    code = {"spacing": {"xxs": 2, "xs": 4, "sm": 8, "md": 12, "lg": 16, "xl": 24, "xxl": 32, "xxxl": 48},
            "shape": {"extraSmall": 4, "small": 8, "medium": 12, "large": 16, "extraLarge": 28},
            "typography": {"bodySmall": {"sizeSp": 12, "weight": "regular"},
                           "labelSmall": {"sizeSp": 11, "weight": "medium"},
                           "titleLarge": {"sizeSp": 22, "weight": "semibold"},
                           "displayLarge": {"sizeSp": 57, "weight": "bold"}}}
    tg = token_targets(code)
    spacing = scan_dimension(SELF_TEST_CSS, "spacing")
    expect(spacing["9px"]["count"] == 1 and spacing["11px"]["count"] == 1, "自检：简写必须拆成两个值")
    expect(spacing["var(--pad-row)"]["count"] == 1, "自检：var() 必须被数到")
    expect(classify("8px", tg["spacing"], 1.5)[0] == "exact", "自检：8px 应判 exact")
    expect(classify("9px", tg["spacing"], 1.5)[0] == "snap", "自检：9px 应判 snap 到 8")
    expect(classify("0", tg["spacing"], 1.5)[0] == "exact", "自检：0 恒 exact")
    expect(classify("var(--pad-row)", tg["spacing"], 1.5)[0] == "variable", "自检：var() 应判 variable（已系统化，不许退回字面量）")
    expect(classify("999px", tg["shape"], 1.0)[0] == "special", "自检：999px 是胶囊哨兵，不参与数值归一化")
    expect(classify("50%", tg["shape"], 1.0)[0] == "special", "自检：50% 应判 special")
    expect(classify("14px", tg["shape"], 1.0)[0] == "review", "自检：14px 离 12/16 都超容差，应判 review")
    expect(classify("20px", tg["spacing"], 1.5)[0] == "review", "自检：20px 离 16/24 都超容差，应判 review")
    w = {WEIGHT_NUM[k]: k for k in tg["typography-weight"] if k in WEIGHT_NUM}
    expect(classify("650", w, 0.0, unitless=True)[0] == "review", "自检：字重 650 超容差，应判 review 而不是 special")
    expect(classify("bold", w, 0.0, unitless=True)[0] == "exact", "自检：字重关键字 bold → 700")
    expect(classify("inherit", w, 0.0, unitless=True)[0] == "special", "自检：非数值字重应判 special")
    expect(classify("12px", {}, 1.5)[0] == "review", "自检：代码侧无槽位时必须 review 而不是静默通过")
    expect(classify("-3px", tg["spacing"], 1.5)[0] == "special", "自检：负 margin 不是节奏档位，应判 special")
    expect(_split_top_level("calc(var(--status) + 10px)") == ["calc(var(--status) + 10px)"],
           "自检：calc() 里的空格不许被当成简写分隔")
    expect(_values_of("gap", "0 12px") == ["0", "12px"], "自检：gap 的双值简写必须拆开")
    expect(_values_of("padding", "9px 11px") == ["9px", "11px"], "自检：padding 双值必须拆开")
    # compare 的棘轮判定：sha 漂移只警示、种数上涨才是违规
    cur_sig = {"source": {"path": "p", "sha256": "new123"},
               "dimensions": {"spacing": {"distinct": 41, "afterDistinct": 9,
                                          "variable": 4, "review": 13, "special": 6}}}
    ok_base = json.loads(json.dumps(cur_sig)); ok_base["source"]["sha256"] = "old123"
    iss, nt = compare(cur_sig, ok_base)
    expect(iss == [] and len(nt) == 1, "自检：sha 漂移应=1 条警示且不是违规")
    bad_base = json.loads(json.dumps(ok_base))
    bad_base["dimensions"]["spacing"]["distinct"] = 40
    iss2, _ = compare(cur_sig, bad_base)
    expect(len(iss2) == 1, "自检：设计侧种数上涨必须报违规")
    return fails


# ── main ───────────────────────────────────────────────────────────
def main():
    ap = argparse.ArgumentParser(description="设计侧取值收敛提案（只读）")
    ap.add_argument("--repo", default=".", help="仓库根（默认当前目录）")
    ap.add_argument("--root", default=CODE_ROOT, help=f"代码根（默认 {CODE_ROOT}）")
    ap.add_argument("--markdown", help="把提案表写到该路径（评审文档）")
    ap.add_argument("--json", action="store_true", help="打印 JSON")
    ap.add_argument("--check", action="store_true", help="棘轮：设计侧取值只许收敛")
    ap.add_argument("--write-baseline", action="store_true", help="重记基线")
    ap.add_argument("--baseline", help=f"基线路径（默认 {BASELINE_REL}）")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        fails = self_test()
        for f in fails:
            print("FAIL", f)
        print(f"self-test: {'OK' if not fails else 'FAILED'}（{len(fails)} 处）")
        return 0 if not fails else 1

    repo = os.path.abspath(args.repo)
    dims = build_report(args.root, repo)
    meta = design_meta(repo)
    sig = signature(dims, meta)
    base_path = args.baseline or os.path.join(repo, BASELINE_REL)

    if args.markdown:
        out = args.markdown if os.path.isabs(args.markdown) else os.path.join(repo, args.markdown)
        with open(out, "w", encoding="utf-8") as fh:
            fh.write(summarize(dims, meta) + "\n")
        print(f"[design-converge] 提案表已写入 {os.path.relpath(out, repo)}")

    if args.write_baseline:
        with open(base_path, "w", encoding="utf-8") as fh:
            json.dump(sig, fh, ensure_ascii=False, indent=2)
            fh.write("\n")
        print(f"[design-converge] 基线已重记 {os.path.relpath(base_path, repo)}")

    if args.json:
        print(json.dumps(sig, ensure_ascii=False, indent=2))

    if args.check:
        if not os.path.exists(base_path):
            print(f"[design-converge] FAIL — 缺基线 {os.path.relpath(base_path, repo)}（先 --write-baseline）")
            return 1
        base = json.load(open(base_path, encoding="utf-8"))
        issues, notes = compare(sig, base)
        for n in notes:
            print("  ⚠", n)
        for i in issues:
            print("  -", i)
        if issues:
            print(f"[design-converge] FAIL — {len(issues)} 条（设计侧取值只许收敛）")
            return 1
        print(f"[design-converge] OK — 设计侧取值未新增{'（有警示）' if notes else ''}")
        return 0

    if not args.json and not args.markdown:
        print(summarize(dims, meta))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except ConvergeError as e:
        print(f"[design-converge] ERROR: {e}", file=sys.stderr)
        sys.exit(2)
