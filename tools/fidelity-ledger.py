#!/usr/bin/env python3
"""自由度账本 + 还原上限 + 偏差账本校验（skill: prototype-to-compose / Step 1 第二份产物）

回答一个具体问题：**原型的美感装得进代码的 token 空间吗？**
不靠感觉，靠数：设计侧每个维度用了多少种取值（全部从设计源直接数），代码侧能落点几个 token，
两者的 min 就是这一维度的还原上限。上限 < 1 的维度必须有一条"决定"或"偏差"记录。

三件事：
  1. measure 设计侧（可运行 HTML 原型 + 截图脚本的 tier 表）与代码侧（design_contract 唯一读取器）
  2. 算还原上限，逐维度给判定
  3. 校验 design/fidelity-deviations.yaml（位置真实存在、理由与批准人非空、棘轮只往收敛方向走）

退出码：0 = 无硬违规；1 = 有硬违规（账本畸形 / 位置不存在 / 理由或批准人空缺 / 棘轮反向）。
未被记录的"装不下维度"默认只报 WARN（还没决定是合法状态，工具负责让它可见）；
加 --strict 后按硬违规处理（CI 接门禁时用这一档）。

账本文件只支持受限子集：顶层一个列表，元素是"标量键值对"，不支持嵌套与行内注释。
"""
from __future__ import annotations

import argparse
import collections
import hashlib
import inspect
import json
import os
import re
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import design_contract as dc  # noqa: E402  唯一读取器：代码侧 token 只能从它来

# ---------------------------------------------------------------- 每产品配置
CODE_ROOT = "."                                    # 代码侧根（design_contract 的 --root 口径）
DESIGN_SOURCES = ["prototype/relay.html"]          # 设计侧可运行原型
SHOT_TABLE = "prototype/shoot-relay.py"            # tier → 窗口尺寸的权威口径
LEDGER_REL = "design/fidelity-deviations.yaml"
BASELINE_REL = "tools/fidelity-ledger-baseline.json"

KINDS = ("deviation", "expand-token", "converge-design")
M3_RADII = (4, 8, 12, 16, 28)  # 代码侧 shape token 的五个档位
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
WHERE_RE = re.compile(r"^(?P<path>[^\s:][^:]*?):(?P<line>\d+)$")

# 维度定义：(显示名, 设计侧取值, 代码侧读取键, 是否上门禁)
# 代码侧 None = 该维度在代码里没有可落点的 token（上限 0，必须有决定记录）
DIMENSIONS = [
    ("字号", "fontSize", "typography", True),
    ("间距/gap", "spacingUnion", "spacing", True),
    ("圆角", "radius", "shape", True),
    ("字重", "fontWeight", "weightRoles", True),
    ("尺寸定值", "sizeLiteral", None, True),
    ("图标", "icons", "iconsDeclared", True),
    ("断点/tier", "tiers", "breakpoints", True),
]
# 只记录、不设上限的观感维度（它们的"自由"不需要 token 承接）
INFO_DIMENSIONS = ["动效", "交互态", "内联style", "css变量"]


class LedgerError(Exception):
    """账本畸形或违规 —— 一律显式抛出，不静默跳过。"""


# ------------------------------------------------------------------ 工具函数
def _read(repo: Path, rel: str) -> str:
    p = repo / rel
    if not p.is_file():
        raise LedgerError(f"缺少设计源文件：{rel}（预期 {p}）")
    return p.read_text(encoding="utf-8")


def _strip_css(css: str) -> str:
    return re.sub(r"/\*.*?\*/", "", css, flags=re.S)


def _counter(css: str, pattern: str) -> collections.Counter:
    return collections.Counter(
        re.sub(r"\s+", " ", m.group(1).strip()) for m in re.finditer(pattern, css)
    )


def _ratio(css: str, pattern: str) -> str:
    """该属性有多少处走了 var()。设计侧的"设计系统完成度"就看这个。"""
    vals = [re.sub(r"\s+", " ", m.group(1).strip()) for m in re.finditer(pattern, css)]
    if not vals:
        return "0/0"
    return f"{sum('var(' in v for v in vals)}/{len(vals)}"


# ------------------------------------------------------------- 设计侧（数出来）
def measure_css(css: str, html: str) -> Dict[str, Any]:
    """从 CSS 文本量设计侧取值。**自检与生产必须走这一条**——

    原先 `measure_design_from_text`（自检用）是另写一份的简化实现，只量 4 个维度，
    于是"给尺寸定值加分层"这类改动**自检根本覆盖不到**：自检跑的是另一段代码。
    自检跑不到生产代码 = 自检证明不了任何事。现在两条路径共用本函数。
    """
    font = _counter(css, r"font-size\s*:\s*([^;{}]+)")
    pad = _counter(css, r"padding(?:-(?:top|right|bottom|left))?\s*:\s*([^;{}]+)")
    gap = _counter(css, r"(?:^|[;{\s])(?:row-|column-)?gap\s*:\s*([^;{}]+)")
    radius = _counter(css, r"border-radius\s*:\s*([^;{}]+)")
    weight = _counter(css, r"font-weight\s*:\s*([^;{}]+)")
    size_lit = _counter(
        css, r"(?:^|[;{\s])(?:min-|max-)?(?:width|height)\s*:\s*([^;{}]+)"
    )
    # 尺寸定值必须分两层数，否则分母被"结构性写法"灌水：
    #   `min-width:0`（flex 收缩惯用法，60 处）与 `width:100%`（撑满父级，25 处）
    #   **不是设计取值**——它们不表达"多大"，只表达"允许收缩/撑满"。
    # 把它们算进"设计侧取值种数"会让自由度缺口看起来比实际大（分母灌水），
    # 进而误导"必须扩 token"的结论。实测：85 种里 105 处是这两类。
    _STRUCTURAL = re.compile(r"^(?:0|auto|100%|100vh|100vw|[\d.]+(?:em|rem|%|vh|vw))$")
    size_fixed = collections.Counter(
        {k: v for k, v in size_lit.items() if re.fullmatch(r"[\d.]+px", k)}
    )
    size_struct = collections.Counter(
        {k: v for k, v in size_lit.items() if not re.fullmatch(r"[\d.]+px", k)}
    )
    # 定值再分档：<100px 是图标/控件尺寸（token 候选），≥100px 是布局容器宽度
    # （响应式断点职责，不是"尺寸 token"能承接的——`460px` 侧栏宽属 DesktopLayout 的
    #  breakpoint 域，归到尺寸 token 会让图标尺寸与布局宽度混在一张表里）。
    size_ctl = collections.Counter(
        {k: v for k, v in size_fixed.items() if float(k[:-2]) < 100}
    )
    size_layout = collections.Counter(
        {k: v for k, v in size_fixed.items() if float(k[:-2]) >= 100}
    )
    radius_sites = sum(radius.values())

    out: Dict[str, Any] = {}
    out["fontSize"] = {"kinds": len(font), "sites": sum(font.values()),
                       "top": font.most_common(5), "var": _ratio(css, r"font-size\s*:\s*([^;{}]+)")}
    union = set(pad) | set(gap)
    out["spacingUnion"] = {
        "kinds": len(union), "sites": sum(pad.values()) + sum(gap.values()),
        "padding": {"kinds": len(pad), "sites": sum(pad.values()),
                    "var": _ratio(css, r"padding(?:-(?:top|right|bottom|left))?\s*:\s*([^;{}]+)")},
        "gap": {"kinds": len(gap), "sites": sum(gap.values()),
                "var": _ratio(css, r"(?:^|[;{\s])(?:row-|column-)?gap\s*:\s*([^;{}]+)")},
        "top": collections.Counter({k: pad[k] + gap[k] for k in union}).most_common(5),
    }
    out["radius"] = {"kinds": len(radius), "sites": radius_sites, "top": radius.most_common(5)}
    out["fontWeight"] = {"kinds": len(weight), "sites": sum(weight.values()), "top": weight.most_common(5)}
    out["sizeLiteral"] = {
        "kinds": len(size_fixed), "sites": sum(size_fixed.values()),
        "top": size_fixed.most_common(5),
        # 分母 = 定值档（token 候选）。结构写法单列，不计入自由度缺口。
        "structuralKinds": len(size_struct), "structuralSites": sum(size_struct.values()),
        "structuralTop": size_struct.most_common(3),
        "controlKinds": len(size_ctl), "controlSites": sum(size_ctl.values()),
        "layoutKinds": len(size_layout), "layoutSites": sum(size_layout.values()),
        "rawKinds": len(size_lit), "rawSites": sum(size_lit.values()),
    }

    icons = len(re.findall(r"<symbol\b", html))
    sw = re.search(r"stroke-width\s*:\s*([\d.]+)", css)
    stroke_off = len(re.findall(r"stroke-width\s*:\s*(?!1\.7)", css))
    out["icons"] = {"kinds": icons, "sites": len(re.findall(r"<use\b", html)),
                    "strokeWidth": sw.group(1) if sw else None,
                    "strokeWidthOverrides": stroke_off,
                    "note": "设计侧一套自洽笔触语言" if sw else "无明显笔触规格"}

    styles = re.findall(r"data-style\s*=\s*\"([^\"]+)\"", html)
    out["_inlineStyleDefs"] = len(styles)

    out["動效"] = None  # 占位，避免 typo 静默通过
    out["motion"] = {
        "transition": len(re.findall(r"\btransition\s*:", css)),
        "animation": len(re.findall(r"\banimation\s*:", css)),
        "keyframes": len(re.findall(r"@keyframes", css)),
    }
    out["states"] = {
        "hover": len(re.findall(r":hover\b", css)),
        "focus": len(re.findall(r":focus\b", css)),
        "active": len(re.findall(r":active\b", css)),
    }
    out["cssVars"] = {
        "defined": len(set(re.findall(r"(--[a-z0-9-]+)\s*:", css))),
        "refs": len(re.findall(r"var\(\s*(--[a-z0-9-]+)", css)),
    }
    return out


def measure_design(repo: Path, design_rel: str, shots_rel: str) -> Dict[str, Any]:
    """磁盘版：读设计源 → 共用 measure_css → 再补只有磁盘才有的维度（断点/tier）。"""
    html = _read(repo, design_rel)
    blocks = re.findall(r"<style[^>]*>(.*?)</style>", html, re.S)
    if not blocks:
        raise LedgerError(f"{design_rel} 里找不到 <style> 块：设计侧取值只能从 CSS 数，不许凭印象填")
    css = _strip_css("\n".join(blocks))
    out = measure_css(css, html)

    tiers: Dict[str, set] = collections.defaultdict(set)
    shots_text = _read(repo, shots_rel)
    # 截图脚本里的 shot 元组与 inspect_shots() 共用同一口径
    for m in re.finditer(
        r'\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*(\d+)\s*,\s*(\d+)',
        shots_text,
    ):
        tiers[m.group(2)].add(m.group(5) + "x" + m.group(6))
    out["tiers"] = {"kinds": len(tiers),
                    "sites": sum(len(v) for v in tiers.values()),
                    "map": {k: sorted(v) for k, v in sorted(tiers.items())}}
    return out


# ------------------------------------------------------------- 代码侧（只经读取器）
def measure_code(repo: Path, code_root: str = CODE_ROOT) -> Dict[str, Any]:
    root = repo / code_root
    code = dc.read_code_tokens(str(root))
    breakpoints: Optional[int] = None
    hits = [
        p
        for p in root.rglob("*.kt")
        if "breakpoint" in p.name.lower() and "/build/" not in str(p)
    ]
    if hits:
        vals = set()
        for p in hits:
            vals |= set(re.findall(r"(\d+)\.dp", p.read_text(encoding="utf-8")))
        breakpoints = len(vals)
    return {
        "typography": len(code["typography"]),
        "spacing": len(code["spacing"]),
        "shape": len(code["shape"]),
        "elevation": len(code["elevation"]),
        "colorsLight": len(code["colors"]["light"]),
        "weightRoles": len({t["weight"] for t in code["typography"].values()}),
        "iconsDeclared": len(json.loads((repo / code_root / "design/design-spec.json").read_text(encoding="utf-8"))["icons"]),
        "breakpoints": breakpoints,
        "debt": dc.scan_debt(str(root)),
        "offScaleRadii": sorted(
            (k for k in dc.scan_shape_inventory(str(root))["raw"]
             if int(float(k)) not in M3_RADII),
            key=lambda s: float(s),
        ),
    }


def upper_bounds(design: Dict[str, Any], code: Dict[str, Any]) -> List[Dict[str, Any]]:
    rows = []
    for label, dkey, ckey, gated in DIMENSIONS:
        d = design[dkey]
        dk = d["kinds"]
        if ckey is None:
            ck, note = 0, "代码侧没有可落点的 token"
        else:
            ck = code[ckey]
            note = "未测到" if ck is None else ""
        if ck is None:
            bound = None
        else:
            bound = min(dk, ck)
        rows.append({
            "dimension": label, "designKinds": dk, "codeTokens": ck,
            "upperBound": bound, "gated": gated and bound is not None and bound < dk,
            "note": note, "design": d,
        })
    return rows


# ------------------------------------------------------------------ 账本解析
def parse_ledger(text: str) -> List[Dict[str, str]]:
    """受限 YAML 子集。嵌套不是"没解析到"，而是显式报错：空值键后面跟着更深缩进 = 嵌套。"""
    entries: List[Dict[str, str]] = []
    cur: Optional[Dict[str, str]] = None
    pending: Optional[tuple] = None  # (键, 缩进)：刚写下的空值键
    for raw in text.splitlines():
        line = raw.rstrip()
        if not line.strip() or line.strip().startswith("#"):
            continue
        indent = len(line) - len(line.lstrip())
        if pending and indent > pending[1]:
            raise LedgerError(
                f"账本不支持嵌套/多行值：{pending[0]} 是空值键，随后出现了更深缩进的内容 —— {raw!r}"
            )
        if line.lstrip().startswith("- "):
            cur = {}
            entries.append(cur)
            line = line.lstrip()[2:]
            if not line.strip():
                pending = None
                continue
        elif cur is None:
            raise LedgerError(f"账本第一行必须是列表项（'- '），实际：{raw!r}")
        if ":" not in line:
            raise LedgerError(f"账本条目不支持嵌套/多行，必须是 键: 值 —— {raw!r}")
        k, _, v = line.strip().partition(":")
        cur[k.strip()] = v.strip().strip('"').strip("'")
        pending = (k.strip(), indent) if not v.strip() else None
    return entries


def load_ledger(repo: Path, rel: str) -> List[Dict[str, str]]:
    p = repo / rel
    if not p.is_file():
        return []
    return parse_ledger(p.read_text(encoding="utf-8"))


def validate_ledger(entries: List[Dict[str, str]], repo: Path,
                    labels: List[str]) -> List[str]:
    problems: List[str] = []
    for i, e in enumerate(entries, 1):
        tag = f"账本第 {i} 条（{e.get('dimension', '?')}）"
        kind = e.get("kind", "")
        if kind not in KINDS:
            problems.append(f"{tag}: kind={kind!r} 非法，只能是 {KINDS}")
        if e.get("dimension") not in labels:
            problems.append(f"{tag}: dimension={e.get('dimension')!r} 不在已测维度 {labels} 内")
        if not e.get("reason"):
            problems.append(f"{tag}: reason 为空 —— 没有理由的降级就是无记录降级")
        if not DATE_RE.match(e.get("date", "")):
            problems.append(f"{tag}: date={e.get('date')!r} 不是 YYYY-MM-DD")
        if not e.get("approved_by"):
            problems.append(f"{tag}: approved_by 为空 —— agent 不许代签，未批准即未成立")
        where = e.get("where", "")
        if not where:
            problems.append(f"{tag}: where 为空")
            continue
        if kind == "deviation":
            m = WHERE_RE.match(where)
            if not m:
                problems.append(f"{tag}: deviation 的 where 必须是 <文件>:<行>，实际 {where!r}")
                continue
            f = repo / m.group("path")
            if not f.is_file():
                problems.append(f"{tag}: where 指向的文件不存在：{m.group('path')}")
                continue
            n = int(m.group("line"))
            total = len(f.read_text(encoding="utf-8", errors="replace").splitlines())
            if n < 1 or n > total:
                problems.append(f"{tag}: 行号 {n} 超界（{m.group('path')} 共 {total} 行）")
            if not e.get("design") or not e.get("actual"):
                problems.append(f"{tag}: deviation 必须写清 design / actual")
            elif e["design"] == e["actual"]:
                problems.append(f"{tag}: design 与 actual 相同（{e['design']}）不是偏差")
    return problems


# ------------------------------------------------------------------ 棘轮基线
def baseline_path(repo: Path, rel: str) -> Path:
    return repo / rel


# 参与"量出数字"的函数。基线必须记住**是哪把量具量出来的**：
# 修量具（如尺寸定值分层 85→65）后基线不重记，棘轮下限仍是旧量具的数——
# 于是设计侧可以从 65 一路长回 85 而不报红（棘轮静默放宽）。
# 这与 §3.4「只卡基线已有的组」同族：**度量面变了，基线没跟着变**。
_INSTRUMENT_FUNCS = ("measure_css", "measure_design", "measure_code", "upper_bounds")


def instrument_fingerprint() -> str:
    """量具指纹 = 参与量测的函数的源码 sha256（前 12 位）。改量具必变。"""
    src = "\n\n".join(inspect.getsource(globals()[n]) for n in _INSTRUMENT_FUNCS)
    return hashlib.sha256(src.encode("utf-8")).hexdigest()[:12]


def check_instrument(base: Dict[str, Any]) -> List[str]:
    """基线里记的量具指纹与当前不一致 ⇒ 硬违规，要求重记基线。

    老基线（没有该字段）也算不一致：宁可强制重记一次，也不要让一把改过的
    量具配一份旧基线继续发绿。
    """
    want = instrument_fingerprint()
    got = base.get("instrument")
    if got == want:
        return []
    return [
        f"量具已变，基线未重记：基线由量具 {got or '（未记录，早于本字段）'} 量出，"
        f"当前量具 {want}。棘轮下限可能高于或低于真实值 ⇒ 先 --write-baseline 重记，再判。"
    ]


def baseline_regressions(rows: List[Dict[str, Any]], base: Dict[str, Any],
                         entries: List[Dict[str, str]]) -> List[str]:
    """**第五十个洞**：重记基线时，这个函数列出「比旧基线差」的方向。

    本工具的 `--write-baseline` 原来**没有任何守卫**：`--check` 会把「设计侧取值种类
    变多」报红（设计侧只许收敛），但只要跑一次 `--write-baseline`，扩宽就被无声无息地
    记进基线，没有 reason、没有痕迹 —— 同一个棘轮，正门是锁着的，后门是敞开的。
    这正是 `check_ratchet()` 自己那条判据的绕行口。

    **为什么这个洞在 2026-10-12 才暴露**：用户亲自改原型（新增 `m-nodedetail` 整屏
    与配套 CSS）⇒ 设计侧「字号 16→18 / 间距 130→133 / 尺寸定值 65→66」三项同时变多。
    这三条本身是**权威源变了**、不是代码退化，但当时没有任何机制能区分这两种情形：
    分不清就只能一律重记，而重记等于把闸门拆掉。

    守卫形状与 `design-token-usage.py`（第二十个洞）逐字同族：重记时若变差，
    **必须给 `--reason`**，变差清单与 reason 一起写进基线备查。
    """
    bad: List[str] = []
    dims = base.get("dimensions", base)
    for r in rows:
        b = dims.get(r["dimension"])
        if not b:
            continue
        if r["designKinds"] > b.get("designKinds", r["designKinds"]):
            bad.append(
                f"设计侧扩宽：{r['dimension']} 取值种类 {b['designKinds']} → {r['designKinds']}"
                f"（设计侧只许收敛；扩宽只可能来自原型被改，须说明改的是哪一屏哪条 CSS）"
            )
        if r["codeTokens"] is not None and b.get("codeTokens") is not None \
                and r["codeTokens"] < b["codeTokens"]:
            bad.append(
                f"代码侧 token 减少：{r['dimension']} {b['codeTokens']} → {r['codeTokens']}"
                f"（token 只许增补）"
            )
    if "deviations" in base:
        cur = sum(1 for e in entries if e.get("kind") == "deviation")
        if cur > base["deviations"]:
            bad.append(f"偏差条数上涨：{base['deviations']} → {cur}（已知偏差只许减）")
    return bad


def check_ratchet(rows: List[Dict[str, Any]], base: Dict[str, Any],
                  entries: List[Dict[str, str]]) -> List[str]:
    problems: List[str] = []
    dims = base.get("dimensions", base)  # 兼容早期把维度直接放顶层的基线
    for r in rows:
        b = dims.get(r["dimension"])
        if not b:
            continue
        if r["designKinds"] > b.get("designKinds", r["designKinds"]):
            problems.append(
                f"棘轮反向：设计侧「{r['dimension']}」取值种类 {b['designKinds']} → {r['designKinds']}（设计侧只许收敛）"
            )
        if r["codeTokens"] is not None and b.get("codeTokens") is not None \
                and r["codeTokens"] < b["codeTokens"]:
            problems.append(
                f"棘轮反向：代码侧「{r['dimension']}」token 数 {b['codeTokens']} → {r['codeTokens']}（token 只许增补）"
            )
    if "deviations" in base:
        cur = sum(1 for e in entries if e.get("kind") == "deviation")
        if cur > base["deviations"]:
            problems.append(
                f"棘轮反向：偏差条数 {base['deviations']} → {cur}（已知偏差只许减）"
            )
    return problems


# ------------------------------------------------------------------ 报告
def render(rows: List[Dict[str, Any]], design: Dict[str, Any], code: Dict[str, Any],
           entries: List[Dict[str, str]], problems: List[str], strict: bool) -> str:
    out: List[str] = ["# 自由度账本（设计侧取值 vs 代码侧 token）", ""]
    out.append("| 维度 | 设计侧取值种数 | 代码侧 token | 还原上限 | 判定 |")
    out.append("|---|---|---|---|---|")
    for r in rows:
        b = "未测到" if r["upperBound"] is None else str(r["upperBound"])
        if r["upperBound"] is None:
            verdict = "量具未测到，不下结论"
        elif r["upperBound"] >= r["designKinds"] and r["codeTokens"]:
            verdict = "装得下"
        else:
            verdict = "**装不下**，需决定（扩 token / 收敛设计 / 登记偏差）"
        out.append(
            f"| {r['dimension']} | {r['designKinds']} | "
            f"{'—' if r['codeTokens'] is None else r['codeTokens']} | {b} | {verdict} |"
        )
    out += ["", "## 只记录、不设上限的维度", ""]
    out.append(
        f"- 动效：transition {design['motion']['transition']} / animation {design['motion']['animation']} "
        f"/ keyframes {design['motion']['keyframes']}"
    )
    out.append(
        f"- 交互态：hover {design['states']['hover']} / focus {design['states']['focus']} "
        f"/ active {design['states']['active']}"
    )
    out.append(
        f"- CSS 变量：定义 {design['cssVars']['defined']} 个、引用 {design['cssVars']['refs']} 处"
    )
    out.append("")
    out.append("## 代码侧欠债（与消费率棘轮同源）")
    out.append(f"- 字面量：{json.dumps(code['debt'], ensure_ascii=False)}")
    out.append(f"- 档外圆角半径：{code['offScaleRadii']}")
    out.append("")
    out.append(f"## 账本（{len(entries)} 条）")
    if not entries:
        out.append("- 空")
    for e in entries:
        out.append(
            f"- [{e.get('kind')}] {e.get('dimension')} @ {e.get('where')}："
            f"{e.get('design')} → {e.get('actual')}｜{e.get('reason')}｜批准 {e.get('approved_by') or '（空缺）'} {e.get('date')}"
        )
    unaccounted = [r["dimension"] for r in rows if r["gated"]
                   and not any(e.get("dimension") == r["dimension"] for e in entries)]
    out += ["", "## 判定", ""]
    if unaccounted:
        level = "违规" if strict else "待决策（未阻断，--strict 下为红）"
        out.append(f"- **{level}**：以下维度装不下且没有任何记录：{unaccounted}")
    if problems:
        out.append(f"- **硬违规 {len(problems)} 条**：")
        out += [f"  - {p}" for p in problems]
    if not problems and not unaccounted:
        out.append("- 无硬违规，且所有装不下的维度都有记录")
    return "\n".join(out)


# ------------------------------------------------------------------ 自检
def self_test() -> List[str]:
    """喂已知缺陷，确认每一条都会被抓到；再喂良性输入，确认不误报。"""
    fails: List[str] = []

    def expect(cond: bool, msg: str) -> None:
        if not cond:
            fails.append(msg)

    # 设计侧：3 种字号 / 4 种 padding / 1 种圆角（全部字面量）
    fake = """<style>
      .a{font-size:11px;padding:8px 10px;border-radius:8px}
      .b{font-size:11.5px;padding:9px 11px}
      .c{font-size:12.5px;gap:5px}
      svg{stroke-width:1.7}
    </style><symbol id="x"/><use href="#x"/>"""
    d = measure_design_from_text(fake)
    expect(d["fontSize"]["kinds"] == 3, "自检：字号种数应数出 3")
    expect(d["spacingUnion"]["padding"]["kinds"] == 2, "自检：padding 种数应数出 2")
    expect(d["spacingUnion"]["gap"]["kinds"] == 1, "自检：gap 种数应数出 1")
    expect(d["spacingUnion"]["kinds"] == 3, "自检：padding∪gap 去重后应数出 3")
    expect(d["icons"]["kinds"] == 1, "自检：symbol 数应数出 1")
    expect(d["_inlineStyleDefs"] == 0, "自检：data-style 定义数应为 0")

    # 尺寸定值分两层：结构写法（min-width:0 / width:100%）不是设计取值，
    # 不许计进自由度分母。**这条是量具先验**——不分层时实测分母被灌水 105 处
    # （0×60 + 100%×25 + 1em×2），85 种里只有 65 种是真实定值。
    fake2 = """<style>
      .a{min-width:0;width:16px;height:16px}
      .b{width:100%;max-width:640px}
      .c{min-width:0}
      svg{stroke-width:1.7}
    </style><symbol id="x"/>"""
    d2 = measure_design_from_text(fake2)
    sl = d2["sizeLiteral"]
    expect(sl["kinds"] == 2, f"自检：尺寸定值应只数 px 定值 2 种，实际 {sl['kinds']}")
    expect(sl["structuralKinds"] == 2, "自检：min-width:0 与 width:100% 应归结构写法")
    expect(sl["structuralSites"] == 3, f"自检：结构写法应 3 处，实际 {sl['structuralSites']}")
    expect(sl["controlKinds"] == 1 and sl["layoutKinds"] == 1,
           "自检：16px 归控件档、640px 归布局档")
    expect(sl["rawKinds"] == 4, f"自检：raw 应为 4 种，实际 {sl['rawKinds']}")

    rows = upper_bounds(
        {"fontSize": {"kinds": 3}, "spacingUnion": {"kinds": 3}, "radius": {"kinds": 1},
         "fontWeight": {"kinds": 1}, "sizeLiteral": {"kinds": 2}, "icons": {"kinds": 1},
         "tiers": {"kinds": 1}},
        {"typography": 2, "spacing": 8, "shape": 5, "weightRoles": 1,
         "iconsDeclared": 1, "breakpoints": 3, "debt": {}, "offScaleRadii": []},
    )
    gated = {r["dimension"] for r in rows if r["gated"]}
    expect("字号" in gated, "自检：字号 3 > 2 应判装不下")
    expect("间距/gap" not in gated, "自检：间距 3 < 8 不应判装不下")
    expect("尺寸定值" in gated, "自检：尺寸定值 2 > 0 应判装不下")

    # 良性账本：装不下的维度都有记录、位置真实存在
    entries = [
        {"kind": "converge-design", "dimension": "字号", "where": "维度:字号",
         "design": "3 种", "actual": "2 角色", "reason": "把 11.5/12.5 收成 11/12 两档",
         "approved_by": "kexuejin", "date": "2026-10-09"},
        {"kind": "expand-token", "dimension": "尺寸定值", "where": "维度:尺寸定值",
         "design": "2 种", "actual": "无 token", "reason": "补一组尺寸 token",
         "approved_by": "kexuejin", "date": "2026-10-09"},
    ]
    labels = [r["dimension"] for r in rows]
    expect(not validate_ledger(entries, Path("/tmp"), labels), "自检：良性账本不应报错")

    bad = [
        dict(entries[0], kind="whatever"),
        dict(entries[0], dimension="不存在的维度"),
        dict(entries[0], reason=""),
        dict(entries[0], approved_by=""),
        dict(entries[0], date="10/09"),
    ]
    for b in bad:
        expect(validate_ledger([b], Path("/tmp"), labels),
               f"自检：畸形条目应报红 —— {b}")
    dev = {"kind": "deviation", "dimension": "字号", "where": "no/such/file.kt:12",
           "design": "9px", "actual": "8.dp", "reason": "就近取档", "approved_by": "kexuejin",
           "date": "2026-10-09"}
    expect(any("不存在" in p for p in validate_ledger([dev], Path("/tmp"), labels)),
           "自检：偏差位置不存在应报红")
    dev2 = dict(dev, where=__file__ + ":999999")
    expect(any("超界" in p for p in validate_ledger([dev2], Path("/tmp"), labels)),
           "自检：行号超界应报红")

    # 棘轮：设计侧变多 / token 变少 / 偏差变多 必须反向报红
    base = {"dimensions": {"字号": {"designKinds": 3, "codeTokens": 2}}, "deviations": 1}
    expect(check_ratchet([{"dimension": "字号", "designKinds": 4, "codeTokens": 2}], base, []),
           "自检：设计侧取值种类变多应报红")
    expect(check_ratchet([{"dimension": "字号", "designKinds": 2, "codeTokens": 2}], base, []) == [],
           "自检：设计侧收敛不应报红")
    expect(check_ratchet([{"dimension": "字号", "designKinds": 3, "codeTokens": 1}], base, []),
           "自检：代码侧 token 变少应报红")
    dev_entry = {"kind": "deviation", "dimension": "字号"}
    expect(check_ratchet([{"dimension": "字号", "designKinds": 3, "codeTokens": 2}], base,
                         [dev_entry]) == [],
           "自检：偏差条数不变（1=1）不应报红")
    expect(check_ratchet([{"dimension": "字号", "designKinds": 3, "codeTokens": 2}], base,
                         [dev_entry, dev_entry]) != [], "自检：偏差条数上涨（2>1）应报红")

    # 第五十个洞：`--write-baseline` 的守卫必须与 `check_ratchet` 同方向。
    # 正门（--check）锁着、后门（--write-baseline）敞开，是同一个洞。
    # 先验：同一份数据，check_ratchet 报红的，baseline_regressions 必须也报红。
    for name, cur_rows, cur_entries in (
        ("设计侧扩宽", [{"dimension": "字号", "designKinds": 4, "codeTokens": 2}], []),
        ("代码侧 token 减少", [{"dimension": "字号", "designKinds": 3, "codeTokens": 1}], []),
        ("偏差条数上涨", [{"dimension": "字号", "designKinds": 3, "codeTokens": 2}],
         [dev_entry, dev_entry]),
    ):
        expect(bool(check_ratchet(cur_rows, base, cur_entries)),
               f"自检：{name} 应被 check_ratchet 报红（前置条件）")
        expect(bool(baseline_regressions(cur_rows, base, cur_entries)),
               f"自检：{name} 重记基线时必须被守卫拦下，不能只靠 --check")
    # 反向：收敛 / 不变 时守卫必须放行，否则它会把正常重记也拦死。
    expect(baseline_regressions([{"dimension": "字号", "designKinds": 2, "codeTokens": 2}],
                                base, []) == [],
           "自检：设计侧收敛时重记基线不应被拦")
    expect(baseline_regressions([{"dimension": "字号", "designKinds": 3, "codeTokens": 2}],
                                base, [dev_entry]) == [],
           "自检：偏差条数不变（1=1）时重记基线不应被拦")
    expect(baseline_regressions([{"dimension": "字号", "designKinds": 3, "codeTokens": 3}],
                                base, []) == [],
           "自检：代码侧 token 增补时重记基线不应被拦")

    # 量具指纹：基线由旧量具量出时，棘轮下限是旧值 —— 设计侧可以从真实值
    # 一路长回灌水值而不报红。**这是"度量面变了、基线没跟着变"的洞。**
    # 先验：当前量具 + 空指纹基线 ⇒ 必报红；当前量具 + 当前指纹 ⇒ 不报红。
    fp = instrument_fingerprint()
    expect(check_instrument({}) != [],
           "自检：基线无指纹（旧基线）必须报红要求重记")
    expect(check_instrument({"instrument": fp}) == [],
           "自检：指纹一致不应报红")
    expect(check_instrument({"instrument": "deadbeef0000"}) != [],
           "自检：指纹不一致必须报红")
    # 指纹必须真的跟着量具源码走：改一个字符就应换指纹
    expect(len(fp) == 12, f"自检：指纹应为 12 位，实际 {len(fp)}")

    # 账本解析：不支持嵌套必须报错而不是静默丢字段
    try:
        parse_ledger("- kind: deviation\n  nested:\n    deep: 1\n")
        fails.append("自检：缺失的嵌套语法应抛错")
    except LedgerError:
        pass
    return fails


def measure_design_from_text(html: str) -> Dict[str, Any]:
    """自检用：只吃文本，不碰磁盘。

    **必须走 measure_css**（与生产同一条路径）。曾经这里另写了一份只量 4 个维度的
    简化实现，结果是"给生产加维度/加分层，自检覆盖不到"——自检绿而生产错。
    """
    blocks = re.findall(r"<style[^>]*>(.*?)</style>", html, re.S)
    css = _strip_css("\n".join(blocks))
    out = measure_css(css, html)
    # tiers 只有磁盘路径才读 shot 表；文本路径给个空表，保证键存在（调用方不必判 None）
    out.setdefault("tiers", {"kinds": 0, "sites": 0, "map": {}})
    return out


# ------------------------------------------------------------------ main
def main() -> int:
    ap = argparse.ArgumentParser(description="自由度账本 + 还原上限 + 偏差账本校验")
    ap.add_argument("--repo", default=".", help="仓库根（原型与代码都在它下面）")
    ap.add_argument("--root", default=CODE_ROOT, help="代码侧根，传给 design_contract")
    ap.add_argument("--design", action="append", default=None, help="设计源（可重复）")
    ap.add_argument("--shots", default=SHOT_TABLE)
    ap.add_argument("--ledger", default=LEDGER_REL)
    ap.add_argument("--baseline", default=BASELINE_REL)
    ap.add_argument("--check", action="store_true", help="与基线比对棘轮（默认行为）")
    ap.add_argument("--write-baseline", action="store_true", help="写/刷新棘轮基线")
    ap.add_argument("--reason", default=None,
                    help="重记基线时若出现变差方向，必须给这段说明（第五十个洞）")
    ap.add_argument("--strict", action="store_true", help="未记录的装不下维度按违规处理")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--markdown", metavar="PATH")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        fails = self_test()
        for f in fails:
            print("FAIL", f)
        print(f"self-test: {'OK' if not fails else 'FAILED'}（{len(fails)} 处）")
        return 0 if not fails else 1

    repo = Path(args.repo).resolve()
    design_rel = (args.design or DESIGN_SOURCES)[0]
    design = measure_design(repo, design_rel, args.shots)
    code = measure_code(repo, args.root)
    rows = upper_bounds(design, code)
    labels = [r["dimension"] for r in rows]

    entries = load_ledger(repo, args.ledger)
    problems = validate_ledger(entries, repo, labels)
    unaccounted = [r["dimension"] for r in rows if r["gated"]
                   and not any(e.get("dimension") == r["dimension"] for e in entries)]

    bp = baseline_path(repo, args.baseline)
    if args.write_baseline:
        old = json.loads(bp.read_text(encoding="utf-8")) if bp.is_file() else {}
        bad = baseline_regressions(rows, old, entries)
        if bad and not args.reason:
            print("[fidelity-ledger] FAIL — 重记基线会把以下变差方向记进基线，"
                  "必须给 --reason 说明为什么可以接受：", file=sys.stderr)
            for b in bad:
                print(f"  · {b}", file=sys.stderr)
            print("\n重记基线不是把红灯擦掉的手段。设计侧扩宽通常意味着**原型被改**"
                  "（用户改了稿、新增了屏/CSS）—— 请在 --reason 里写清改的是哪一屏哪条 CSS；"
                  "若确认是代码退化，应当去修代码而不是重记基线。", file=sys.stderr)
            return 2
        bp.parent.mkdir(parents=True, exist_ok=True)
        blob = {
            "instrument": instrument_fingerprint(),
            "dimensions": {r["dimension"]: {"designKinds": r["designKinds"],
                                            "codeTokens": r["codeTokens"]} for r in rows},
            "deviations": sum(1 for e in entries if e.get("kind") == "deviation"),
        }
        if bad:
            blob["regressions"] = bad
            blob["regressionReason"] = args.reason
        bp.write_text(json.dumps(blob, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"棘轮基线已写入 {bp}")
        if bad:
            print(f"  ⚠ 本次重记记下了 {len(bad)} 条变差的方向（已连同 reason 写进基线）：")
            for b in bad:
                print(f"    · {b}")
    elif bp.is_file():
        base = json.loads(bp.read_text(encoding="utf-8"))
        problems += check_instrument(base)
        problems += check_ratchet(rows, base, entries)

    report = render(rows, design, code, entries, problems, args.strict)
    print(report)
    if args.markdown:
        Path(args.markdown).write_text(report + "\n", encoding="utf-8")
    if args.json:
        print(json.dumps({
            "design": design, "code": code,
            "rows": [{k: v for k, v in r.items() if k != "design"} for r in rows],
            "entries": entries, "problems": problems, "unaccounted": unaccounted,
        }, ensure_ascii=False, indent=2, default=str))
    hard = bool(problems) or (args.strict and bool(unaccounted))
    print(f"\nresult: {'FAIL' if hard else 'OK'}"
          f"（硬违规 {len(problems)} 条，待决策 {len(unaccounted)} 个维度）")
    return 1 if hard else 0


if __name__ == "__main__":
    sys.exit(main())
