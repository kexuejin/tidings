#!/usr/bin/env python3
"""渲染真值 <-> CSS 源文本 对照 + 每屏几何锚点表（配合 prototype/dump-layout.py）

回答三个问题：
  1. **源文本灌了多少水**：账本从 style 块数出「间距 133 种」，其中多少是 var() 未解析
     与被覆盖的死规则？渲染真值给出生效的那几种。两数之差 = 水。
  2. **每屏有哪些可判红的锚点**：顶栏高、重复行行高与间隙、兄弟间隙分布、图标档位。
     取值集合差集答不出这些（值对得上 != 用对地方），而这正是人眼看截图第一个看到的差距。
  3. **锚点能不能直接进配对台账**：出一张带签收栏的表，人只裁决，不重数。

对照的文本一侧**复用 design-converge 的既有读法**（scan_dimension / classify）——
不重写一遍正则，否则「两边各错一次时对照是绿的」（与 design_contract.py 同一口径）。

只读：不写 layout/、不改设计源、不动台账。

用法：
    python3 tools/design-render-truth.py                       # 对照 + 锚点摘要
    python3 tools/design-render-truth.py --markdown docs/plans/anchor-table.md
    python3 tools/design-render-truth.py --self-test           # 先验量具
"""
from __future__ import annotations

import argparse
import collections
import glob
import importlib.util
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)


def _load(mod_name, filename):
    spec = importlib.util.spec_from_file_location(mod_name, os.path.join(HERE, filename))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


conv = _load("design_converge", "design-converge.py")

LAYOUT_DIR = os.path.join(REPO, "prototype", "layout")
DESIGN_SOURCE = os.path.join(REPO, "prototype", "relay.html")
SCALE = 2.0  # 原型 PNG 像素 / CSS px；px / 2.0 = dp

DIM_MAP = {
    "spacing": ("pad", "margin", "gap"),
    "radius": ("radius",),
    "font-size": ("font.size",),
    "font-weight": ("font.weight",),
}


def stale_shots(shots, css_meta):
    """审计链守卫：dump 的 meta.sourceSha 与当前设计源不符（或缺字段=版本未知）即列出。
    缺字段的旧 dump 不猜新旧——直接重渲比猜便宜。"""
    cur = css_meta.get("sha256")
    bad = []
    for d in shots:
        got = d.get("meta", {}).get("sourceSha")
        if got != cur:
            bad.append((d["meta"]["shot"], got or "无记录"))
    return bad


def load_shots(layout_dir):
    files = sorted(glob.glob(os.path.join(layout_dir, "*.json")))
    out = []
    for f in files:
        if os.path.basename(f).startswith("_"):
            continue
        with open(f, encoding="utf-8") as fh:
            out.append(json.load(fh))
    return out


LAYOUT_MARGIN_MIN = 24  # auto 居中产物恒为大值；1-16 恰是要人裁决的节奏小档，检测器不许碰


def layout_margin_exclusions(shots):
    """识别「随画布宽度变化的 margin 值」：getComputedStyle 把 margin:auto 解析成
    具体 px，442/402/782 这类**布局结果**会冒充设计档位（实测 .tb-acts
    margin-left：760→319、1100→402/442、1440→782）。

    判据：同一 (path, side) 出现多个整数值，且某值只落在单一宽度档 ⇒ 布局产物，剔除。
    单值且只见过一个宽度 ⇒ **不剔**（证据不足），进 uncertain 清单如实上报。
    只考察 >=24 的值：小 margin 是节奏档，不能被方差检测误伤。
    """
    obs = {}
    for data in shots:
        cw = data["canvasViewport"]["w"]
        for n in data["nodes"]:
            m = n["style"]["margin"]
            for side_i, side in enumerate("trbl"):
                v = m[side_i]
                if v is None or v < LAYOUT_MARGIN_MIN or v != int(v):
                    continue
                obs.setdefault((n["path"], side), {}).setdefault(cw, set()).add(int(v))
    excl = set()
    uncertain = []
    for (path, side), byw in obs.items():
        vals = set().union(*byw.values())
        if len(vals) > 1:
            for v in vals:
                if sum(1 for vs in byw.values() if v in vs) == 1:
                    excl.add((path, side, v))
        elif len(byw) == 1:
            uncertain.append((path, side, next(iter(vals)), next(iter(byw))))
    return excl, uncertain


def family_of(n):
    p = n["path"]
    return ("." + p.rsplit(".", 1)[-1].split(">")[0]) if "." in p else n["tag"]


def render_values(shots, excl=None):
    """渲染真值 -> 分桶后的取值（excl = 布局产物 margin 三元组集合）。

    诚实边界（第一版在这里翻过车）：getComputedStyle 的**原始**种数比 CSS 文本还多，
    因为：简写被拆成四边、布局取整产生 15.7656 这种亚像素尾巴、margin 允许负值、
    margin:auto 被解析成大 px。所以「渲染=更少更干净」是**错的**——
    渲染真值的价值是：(a) 每个值带位置与归属，(b) 噪声从此可识别、可分桶。
    对照分母 = steps（整数档）；subpixel/negative/layout 单独亮出，不进分母。
    """
    excl = excl or set()
    out = {}
    fam = {}  # (dim, value) -> Counter(family)
    role = {}  # (dim, value) -> Counter(角色: padT/padL/gap/marginT...)
    icon_adjacent = collections.Counter()  # (dim, value) -> 该值作 padL/padR 且孩子里有 svg
    for dim in DIM_MAP:
        out[dim] = {"steps": collections.Counter(), "subpixel": collections.Counter(),
                    "negative": collections.Counter(), "zero": 0, "layout": 0}

    fam = collections.defaultdict(collections.Counter)
    role = collections.defaultdict(collections.Counter)

    def note(dim, v, f, r, ic=False):
        k = (dim, int(v))
        fam[k][f] += 1
        role[k][r] += 1
        if ic:
            icon_adjacent[k] += 1

    def bucket(dim, v, f="", r="", ic=False):
        if v is None:
            return
        b = out[dim]
        if v < 0:
            b["negative"][v] += 1
            return
        if v == 0:
            b["zero"] += 1
            return
        if v != int(v):
            b["subpixel"][v] += 1
            return
        if (dim == "spacing" and r.startswith("margin") and (f, r[-1].lower(), int(v)) in ()):
            pass
        iv = int(v)
        if iv != 0:
            note(dim, iv, f, r, ic)
        if dim == "font-weight":
            b["steps"][v] += 1
            return
        b["steps"][iv] += 1

    bypath = {}
    for data in shots:
        cw = data["canvasViewport"]["w"]
        # 子节点含 svg 的父路径（图标缩进补偿证据）
        svg_parents = set()
        for n in data["nodes"]:
            if n["tag"] == "svg":
                svg_parents.add(n["path"].rsplit(">", 1)[0])
        for n in data["nodes"]:
            s = n["style"]
            f = family_of(n)
            for key_i, key in enumerate(("pad", "margin", "gap")):
                for j, v in enumerate(s[key]):
                    if v is None:
                        continue
                    if key == "margin":
                        side = "trbl"[j]
                        if v >= LAYOUT_MARGIN_MIN and v == int(v) \
                                and (n["path"], side, int(v)) in excl:
                            out["spacing"]["layout"] += 1
                            continue
                        bucket("spacing", v, f, "margin" + side)
                    elif key == "pad":
                        ic = ("svg>" in (n["path"] + ">")) or (n["path"] in svg_parents)
                        bucket("spacing", v, f, "pad" + "trbl"[j], ic=bool(ic) and j in (1, 3))
                    else:
                        bucket("spacing", v, f, "gap")
            for j, v in enumerate(s["radius"]):
                bucket("radius", v, f, "r" + "trbl"[j])
            bucket("font-size", s["font"]["size"], f, "font")
            if s["font"]["weight"]:
                bucket("font-weight", float(s["font"]["weight"]), f, "weight")
    out["_fam"] = fam
    out["_role"] = role
    out["_icon"] = icon_adjacent
    return out


def text_numeric_set(css, dim):
    """CSS 文本侧能直接算出的数值集合（var()/special 排除——它们是水）。"""
    _n, by_status, numeric = text_values(css, dim)
    ints = {int(v) for v in numeric if v > 0 and v == int(v)}
    return ints, by_status


def text_values(css, dim):
    """CSS 源文本 -> 声明串按 classify 分桶。返回 (总种数, 状态计数, 可算出数值的种数)。"""
    raw = conv.scan_dimension(css, dim)
    targets = conv.token_targets(conv.dc.read_code_tokens(REPO))
    spec = conv.DIMENSIONS[dim]
    tg = targets.get(spec["token_group"]) or {}
    if dim == "font-weight":
        tg = {conv.WEIGHT_NUM[k]: k for k in targets["typography-weight"] if k in conv.WEIGHT_NUM}
    by_status = collections.Counter()
    numeric = set()
    for value in raw:
        status = conv.classify(value, tg, spec["tolerance"],
                               unitless=(dim == "font-weight"))[0]
        by_status[status] += 1
        if status not in ("variable", "special"):
            m = re.match(r"^([-+]?\d*\.?\d+)(px)?$", value.strip())
            if m:
                numeric.add(float(m.group(1)))
    return len(raw), by_status, numeric


def anchors_table(shots):
    rows = []
    for data in shots:
        m = data["meta"]
        a = data.get("anchors", {})
        top = topbar_of(data)
        gaps = collections.Counter()
        for k, v in (a.get("siblingGaps") or {}).items():
            gaps[int(k)] += v
        rowhs = sorted({r["h"] for r in (a.get("rows") or []) if r.get("h") is not None})
        rows.append({
            "shot": m["shot"], "form": m["size"], "theme": m["theme"],
            "canvasW": data["canvasViewport"]["w"], "canvasH": data["canvasViewport"]["h"],
            "topbar": top, "rowGroups": len(a.get("rows", [])), "rowHeights": rowhs,
            "gaps": dict(sorted(gaps.items())),
            "icons": len(data.get("icons", {})), "nodes": len(data["nodes"]),
        })
    return rows


TOPBAR_KEYS = (".topbar", ".titlebar", ".head")


def topbar_of(data):
    """顶栏高从**节点表**重算，不信任 dump 里的旧 anchors.topbar：
    采集器第一代把 .bar（3px 进度条）也当顶栏（实测 d01 报 h=3，假锚点）。
    节点 path 里带 class，用修好的选择器重算，旧 dump 不必重渲就能纠偏——
    但 meta.collectorSha 会暴露这个文件是哪代采集器产的，审计链完整。"""
    canvas_h = 0
    for n in data["nodes"]:
        for key in TOPBAR_KEYS:
            if key in n["path"] and n["rect"]["y"] == 0:
                if n["rect"]["h"] > 10:  # 进度条/发丝线不算顶栏
                    return n["rect"]["h"]
    return None


def fmt_counter(counter, top=24):
    return " ".join("%g x%d" % (v, c) for v, c in sorted(counter.items())[:top])


def compare(layout_dir, markdown=None, screen=None):
    shots = load_shots(layout_dir)
    if screen:
        shots = [d for d in shots if screen in d["meta"]["shot"]]
    if not shots:
        print("layout/ 里没有 json —— 先跑 python3 prototype/dump-layout.py", file=sys.stderr)
        return 1
    css, meta = conv.read_design_source(DESIGN_SOURCE)
    stale = stale_shots(shots, meta)
    if stale:
        print("⚠ %d/%d 屏的 dump 与当前设计源版本不符（含无记录），对照分母失真——"
              "重跑：python3 prototype/dump-layout.py --force" % (len(stale), len(shots)),
              file=sys.stderr)
        for shot, got in stale[:6]:
            print("    %s（dump 记 %s）" % (shot, got), file=sys.stderr)
    print("## 渲染真值 <-> CSS 源文本 对照")
    print("设计源 %s @ sha256:%s；渲染真值 %d 屏 / %d 个可见节点\n"
          % (meta["path"], meta["sha256"], len(shots), sum(len(d["nodes"]) for d in shots)))

    excl, uncertain = layout_margin_exclusions(shots)
    eff = render_values(shots, excl)
    print("| 维度 | CSS文本种数 | 其中var()/special(水) | 文本可算整数档 | **渲染生效整数档** | 渲染亚像素噪声 | 代码token |")
    print("|---|---|---|---|---|---|---|")
    code = conv.dc.read_code_tokens(REPO)
    # 字重档位从 typography 角色的 weight 推导，不硬编——账本写四档含 bold，
    # 实测代码角色里只出现 regular/medium/semibold 三种（书写段落后于代码）。
    weights = {v["weight"] for v in code["typography"].values() if isinstance(v, dict) and v.get("weight")}
    tok = {"spacing": len(code["spacing"]), "radius": len(code["shape"]),
           "font-size": len(code["typography"]), "font-weight": len(weights)}
    for dim in DIM_MAP:
        n_text, by_status, numeric = text_values(css, dim)
        ints, _ = text_numeric_set(css, dim)
        b = eff[dim]
        n_eff = len(b["steps"])
        vague = by_status.get("variable", 0) + by_status.get("special", 0)
        noise = len(b["subpixel"])
        print("| %s | %d | %d | %d | **%d** | %d | %d |" % (
            dim, n_text, vague, len(ints), n_eff, noise, tok.get(dim, 0)))

    print("\n**判读（修正过的口径）**：原始 computed 种数比 CSS 文本还多——简写拆四边、"
          "布局取整出亚像素尾巴（如 15.7656px）、负 margin 都是新种数。")
    print("渲染真值的价值不是'种数少'，而是：**每个值带位置与归属**（值对得上 != 用对地方，"
          "这里能答用在哪），且噪声从此可分桶。对照分母用「整数档」这一列。")
    print("棘轮该盯的是：整数档里落在代码 token 之外的值（档外清单），不是声明种数。")
    lay = eff["spacing"]["layout"]
    print("布局产物 margin 剔除：%d 处（(路径,边,值) 组合 %d 个，判据=随画布宽度变化）"
          % (lay, len(excl)))
    if uncertain:
        print("证据不足保留（单值单宽度，进观察清单）：%d 条，示例 %s"
              % (len(uncertain), ", ".join("%s %dpx" % (p.split(">")[-1][:20], v)
                                           for p, s, v, w in uncertain[:5])))

    for dim, label in (("spacing", "间距"), ("radius", "圆角"),
                       ("font-size", "字号"), ("font-weight", "字重")):
        b = eff[dim]
        print("\n### 渲染生效整数档（%s，档x节点数）\n    %s" % (label, fmt_counter(b["steps"], 30)))
        if b["subpixel"]:
            print("    亚像素噪声（不是设计档位，采样/取整产物）: %s" % fmt_counter(b["subpixel"], 8))
        if b["negative"]:
            print("    负偏移（定位补偿，非节奏档）: %s" % fmt_counter(b["negative"], 8))

    rows = anchors_table(shots)
    print("\n## 每屏锚点（前 24 / 共 %d 屏）\n" % len(rows))
    print("| shot | form | 主题 | 画布 | 顶栏h | 行组 | 行高中位(dp) | 兄弟间隙 | 图标 |")
    print("|---|---|---|---|---|---|---|---|---|")
    for r in rows[:24]:
        print("| %s | %s | %s | %sx%s | %s | %d | %s | %s | %d |" % (
            r["shot"], r["form"], r["theme"], r["canvasW"], r["canvasH"],
            r["topbar"] if r["topbar"] is not None else "-", r["rowGroups"],
            ",".join(str(int(v / SCALE)) for v in r["rowHeights"][:5]) or "-",
            " ".join("%d x%d" % (k, v) for k, v in list(r["gaps"].items())[:6]) or "-",
            r["icons"]))
    print("\n（行高按 scale=%g 折算成 dp）" % SCALE)

    if markdown:
        with open(markdown, "w", encoding="utf-8") as fh:
            fh.write("# 几何锚点表（渲染真值 dump 产物，skill: prototype-to-compose Step B）\n\n")
            fh.write("来源：prototype/layout/<shot>.json（由 prototype/dump-layout.py 生成）。\n")
            fh.write("口径：数值 = CSS px = dp（原型 PNG / %g）；画布高扣掉顶栏/底栏即内容区预算。\n" % SCALE)
            fh.write("**签收栏只能人填**（skill 硬规则 7）—— 本表不为「看着像」作证，"
                     "只把差异定位到值与位置。\n\n")
            fh.write("| shot | form | 主题 | 画布 WxH | 顶栏h | 行组 | 行高中位(dp) | 兄弟间隙(dp x次) | 图标数 | 节点 | 与代码一致? | 签收 |\n")
            fh.write("|---|---|---|---|---|---|---|---|---|---|---|---|\n")
            for r in rows:
                fh.write("| %s | %s | %s | %sx%s | %s | %d | %s | %s | %d | %d | 待核 | 空 |\n" % (
                    r["shot"], r["form"], r["theme"], r["canvasW"], r["canvasH"],
                    r["topbar"] if r["topbar"] is not None else "-", r["rowGroups"],
                    ",".join(str(int(v / SCALE)) for v in r["rowHeights"]) or "-",
                    " ".join("%d x%d" % (k, v) for k, v in sorted(r["gaps"].items())),
                    r["icons"], r["nodes"]))
            fh.write("\n共 %d 行；行数 = dump 的 shot 数（分母由工具从截图参数表数出，不手写）。\n" % len(rows))
        print("\n已写 %s（%d 行，签收栏留空）" % (markdown, len(rows)))
    return 0


def ladder_sets(code):
    return {
        "spacing": {float(v) for v in code["spacing"].values()},
        # M3 五槽 + ShapeRoles 语义角色 + 999=全圆（CircleShape）。G2 裁决 B 后语义角色
        # 也是合法档位：落档≠只认 shapes.*，否则头像 9dp 会在落档后仍报档外（假残差）。
        "radius": ({float(v) for v in code["shape"].values()}
                   | {999.0}
                   | {float(v) for v in (code.get("shapeRoles") or {}).values()}),
        "font-size": {float(t["sizeSp"]) for t in code["typography"].values()},
        # M3 已上线三角色 + TextWeights 语义角色（G2 裁决 C：Emphasis=700）
        "font-weight": ({400.0, 500.0, 600.0}
                        | {float(v) for v in (code.get("weights") or {}).values()}),
    }


def offladder_worklist(eff, code, census=None):
    """档外整数值排行 + 机械归类。归类只当**预标签**，裁决归人（goal G2）。

    角色口径（来自 pad 四边/gap/margin 的来源桶）：
      - padL/padR 且图标邻接占比高 => optical（图标位补偿；snap 会让输入框文字左移——
        账本里 29px/30px 两条 deviation 就是这个形状，别把节奏 snap 套上去）
      - gap / padT / padB 为主 => rhythm（纯竖向/横向节奏，snap 到邻档通常无损）
      - margin* 为主 => offset（定位补偿，正解是 offset/Alignment 表达，不进档位）
    用数 < RARE_N => rare（长尾：snap 或直接登记，不值得为它动 token 表）
    """
    ladders = ladder_sets(code)
    RARE_N = 30
    out = []
    for dim, ladder in ladders.items():
        b = eff[dim]
        for v, uses in b["steps"].items():
            fv = float(v) if dim != "font-weight" else float(v)
            if fv in ladder:
                continue
            k = (dim, v)
            roles = eff["_role"].get(k, collections.Counter())
            fams = eff["_fam"].get(k, collections.Counter())
            ic = eff["_icon"].get(k, 0)
            side = sum(c for r, c in roles.items() if r in ("padl", "padr"))
            vert = sum(c for r, c in roles.items() if r in ("padt", "padb", "gap"))
            marg = sum(c for r, c in roles.items() if r.startswith("margin"))
            nearv = min(ladder, key=lambda t: abs(t - fv)) if ladder else None
            # 分维度归类。上一版所有维度共用节奏/光学分支，radius/font 的
            # pad/margin/gap 角色计数恒为 0，marg >= max(vert, side) 在 0>=0
            # 时成立，全部形状与字阶被误标 offset。形状/字阶各有自己的族。
            if dim == "font-weight":
                tag = "missing-weight-role"   # 代码 typography 无此角色：补角色或收敛设计
            elif dim == "font-size":
                tag = "type-ladder" if uses >= RARE_N else "rare"
            elif dim == "radius":
                tag = "shape-semantics" if uses >= RARE_N else "rare"  # chip/bubble 各有形状语义
            elif uses < RARE_N:
                tag = "rare"
            elif marg > 0 and marg >= max(vert, side):
                tag = "offset"                 # 定位补偿：正解是 offset/Alignment，不进档位
            elif side > vert and ic >= 0.5 * side and ic > 0:
                tag = "optical"                # 图标位补偿（账本里 29/30px 那族），snap 会移字
            else:
                tag = "rhythm"                 # 含无图标的水平 padding：仍是节奏，可 snap/扩档
            top_fam = ", ".join("%s×%d" % (f, c) for f, c in fams.most_common(2))
            cd = 0
            if census:
                key = str(int(v)) if dim in ("spacing", "radius") else str(v)
                cd = census.get(dim if dim in ("spacing", "radius") else "", {}).get(key, 0)
            out.append({"dim": dim, "value": v, "uses": uses, "code_uses": cd, "tag": tag,
                        "nearest": int(nearv) if nearv is not None else None,
                        "delta": round(abs(fv - nearv), 1) if nearv is not None else None,
                        "roles": dict(roles.most_common(3)), "fam": top_fam})
    out.sort(key=lambda r: (r["dim"], -r["uses"]))
    return out


def offladder_markdown(shots, code, path, excl, census=None):
    eff = render_values(shots, excl)
    rows = offladder_worklist(eff, code, census)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("# 档外残差清单与分类裁决表（goal G1 差集 + G2 裁决入口）\n\n")
        fh.write("口径：分母=渲染整数档（已剔 %d 处布局 margin、%d 处亚像素、负值单列）；\n"
                 % (eff["spacing"]["layout"], len(eff["spacing"]["subpixel"])))
        fh.write("用数=渲染节点槽位数（padding 四边、gap、radius 四角、font 逐节点各计一次）。\n")
        fh.write("「代码同值字面量」是**下界**：只数 padding(...) 实参 / Arrangement.space= /\n        Spacer(Modifier.height|width) 与 RoundedCornerShape(N.dp) 四类写法；其它写法\n        （变量、跨行实参、自定义 modifier 参数）不计。**它的用途是比较，不是精确计数**——\n        代码侧已有 17 处 9dp 而设计侧 1378 处，说明 snap 要改 17 处在跑的代码，\n        扩档只是承认既成事实。\n")
        fh.write("归类是**机械预标签**（rhythm/optical/offset/rare），**裁决归人**：\n")
        fh.write("每行四选一 expand-token / snap / offset表达 / 登记偏差，签 approved_by 后\n")
        fh.write("由我写进 design/fidelity-deviations.yaml。agent 不代签、不猜。\n\n")
        fh.write("| 维度 | 值 | 设计用数 | **代码同值字面量** | 最近代码档 | 差 | 机械预标签 | 主要角色 | 主要家族 | 裁决 | 签署 |\n")
        fh.write("|---|---|---|---|---|---|---|---|---|---|---|\n")
        for r in rows:
            fh.write("| %s | %s | %d | %d | %s | %s | %s | %s | %s | 待裁 | 待签 |\n" % (
                r["dim"], r["value"], r["uses"], r.get("code_uses", 0),
                r["nearest"], r["delta"], r["tag"],
                " ".join("%s×%d" % kv for kv in r["roles"].items()), r["fam"]))
        fh.write("\n共 %d 行。档位建议合并按用数排序取头部——前 10 行覆盖的用数占比：\n\n" % len(rows))
        tot = sum(r["uses"] for r in rows) or 1
        # head 按**用数**取前 10（表按维度分组排序，rows[:10] 是维度头，不是用数头——
        # 上一版照 rows[:10] 算，把 26% 写成了覆盖率，误导）
        head = sum(r["uses"] for r in sorted(rows, key=lambda x: -x["uses"])[:10])
        fh.write("    top10 用数覆盖 = %d / %d（%.1f%%）\n" % (head, tot, 100.0 * head / tot))

        # 家族聚合附录：shape-semantics/rhythm 组「一个值挂在哪些 class」逐列全，
        # 裁决时不用回 dump 数。扩一档还是归语义，看的是家族形状，不是数值本身。
        fh.write("\n## 附录：档外值 × 家族全清单（按用数）\n\n")
        for r in sorted(rows, key=lambda x: -x["uses"]):
            if r["uses"] < 30:
                continue
            fams = eff["_fam"].get((r["dim"], r["value"]), collections.Counter())
            fh.write("- **%s %s**（%d 处，%s，最近档 %s 差 %s）：%s\n" % (
                r["dim"], r["value"], r["uses"], r["tag"], r["nearest"], r["delta"],
                " ".join("%s×%d" % (f, c) for f, c in fams.most_common(8))))
    return rows, tot


# ---------------------------------------------------------------- 锚点自动对账（C1 第一步）
DESKTOP_LAYOUT = "composeApp/src/commonMain/kotlin/app/relay/ui/desktop/DesktopLayout.kt"


def code_layout_constants():
    """从 DesktopLayout.kt 读 const val *_PX —— 代码侧几何锚点的落点就在那。
    只认 "const val NAME_PX = <整数>" 这一窄形状；一条都读不到就报错，
    不静默返回半份（同 design_contract 的口径）。"""
    out = {}
    path = os.path.join(REPO, DESKTOP_LAYOUT)
    if not os.path.exists(path):
        raise SystemExit("缺 DesktopLayout.kt：%s" % DESKTOP_LAYOUT)
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            code = line.split("//")[0]
            m = re.match(r"\s*const val (\w+_PX)\s*=\s*(\d+)\s*$", code)
            if m:
                out[m.group(1)] = int(m.group(2))
    if not out:
        raise SystemExit("DesktopLayout 一条 *_PX 常量都没读到——量具坏了，不是代码坏了")
    return out


def node_by_suffix(data, suffix):
    """按 path 后缀找节点；多个候选取最高那个（列表容器以高度论）。"""
    best = None
    for n in data["nodes"]:
        if n["path"].endswith(suffix) and n["rect"]["w"] > 1:
            if best is None or n["rect"]["h"] > best["rect"]["h"]:
                best = n
    return best


DESKTOP_UI_GLOB = os.path.join(REPO, "composeApp/src/commonMain/kotlin/app/relay/ui/desktop", "*.kt")


def code_width_landing_points():
    """桌面 ui 里所有 width(N.dp) 的字面值集合——代码侧对该数字的「落点」。

    判据强度要说清：命中只证明**代码里有这个宽度的落点**，不证明接到了这个元素
    （精确接线属 C2 探针的活）。用它筛掉的是「代码里根本没这个数」的硬差。"""
    vals = set()
    for path in glob.glob(DESKTOP_UI_GLOB):
        with open(path, encoding="utf-8") as fh:
            for m in re.finditer(r"width\((\d+)\.dp\)", fh.read()):
                vals.add(int(m.group(1)))
    return vals


HOST_SURFACE_SCREENS = ("d-capture", "d-capture-tools", "d-reqscreen",
                        "d-sharescreen", "d-viewscreen")


def is_floatwin(data):
    """独立小窗（截图/屏幕协助）：canvas 就是 .floatwin 本体，宽高由宿主运行时定。
    拿主窗常量比它 = 量具归类错（实测曾报 -800 的假差）。与 pairing-board 对
    d-capture/d-tray/d-drop 「不生成并排图、验收面是真实窗口」同一口径。"""
    scr = data["meta"].get("screen", "")  # 夹具可能不带 screen；缺省走几何判据
    if scr in HOST_SURFACE_SCREENS or scr.startswith("d-capture"):
        return True
    return data["canvasViewport"]["w"] < 400 and any(
        n["path"].endswith("div.floatwin") for n in data["nodes"][:4])


def titlebar_of(data):
    """只认窗口标题栏本体（path 以 .titlebar 结尾、贴画布顶）。.topbar.chat 是
    内容条不是 titlebar——实测 d26 拿它比 TITLEBAR_PX 报过 +57 的假差。"""
    for n in data["nodes"]:
        if n["path"].endswith("div.titlebar") and n["rect"]["y"] == 0 and n["rect"]["h"] > 10:
            return n["rect"]["h"]
    return None


def anchor_diff(shots, consts, land):
    """渲染锚点 vs 代码常量/落点。**只把有代码契约的锚点拿来判差**；
    窗口默认尺寸不进本表（那是 G4 宿主面真窗验收的责任，shot 尺寸只是取景框）。
    返回 (差行, 跳过说明) 两张表。"""
    rows = []
    host_skipped = []
    for data in shots:
        m = data["meta"]
        form, shot = m["size"], m["shot"]
        if is_floatwin(data):
            host_skipped.append((shot, "独立小窗：宿主运行时定尺寸"))
            continue
        tb = titlebar_of(data)
        want = consts.get("TITLEBAR_PX")
        if tb is not None and want is not None and tb != want:
            rows.append((shot, form, "标题栏高", tb, want, tb - want))
        side = node_by_suffix(data, "div.side")
        if side is not None and form in ("expanded", "wide", "tabhost"):
            want = consts.get("SIDEBAR_PX")
            got = side["rect"]["w"]
            if want is not None and got != want and got not in land:
                rows.append((shot, form, "左栏宽", got, want, got - want))
    return rows, host_skipped

def anchor_check_main(shots, markdown):
    consts = code_layout_constants()
    land = code_width_landing_points()
    rows, host_skipped = anchor_diff(shots, consts, land)
    print("## 锚点自动对账（渲染真值 vs DesktopMetrics 常量 + 桌面 width 落点；"
          "常量 %d 条 / 落点 %d 个 / 小窗豁免 %d 屏）\n"
          % (len(consts), len(land), len(host_skipped)))
    print("| shot | form | 锚点 | 渲染 | 代码 | 差 |")
    print("|---|---|---|---|---|---|")
    for shot, form, name, got, want, d in rows:
        print("| %s | %s | %s | %d | %d | %+d |" % (shot, form, name, got, want, d))
    if not rows:
        print("| — | — | 全部有契约的锚点两侧同数 | | | |")
    n_shots = len({r[0] for r in rows}) if rows else 0
    print("\n差集非空：%d 处 / 涉及 %d 屏（共 %d 屏，其中 %d 屏属宿主面豁免）——" % (
          len(rows), n_shots, len(shots), len(host_skipped)))
    print("人只签这些行；两侧同数的锚点不在表里，不必看。")
    if markdown:
        with open(markdown, "w", encoding="utf-8") as fh:
            fh.write("# 锚点对账差集（G4：人只签这里出现的行）\n\n")
            fh.write("渲染真值（prototype/layout）vs 代码：DesktopMetrics 常量 + 桌面 ui 的 width(N.dp) 落点集合。\n")
            fh.write("命中落点=代码里有这个宽度的位置（不证明接到本元素——精确接线属 C2 探针）。\n")
            fh.write("窗口默认尺寸**不在本表**：shot 尺寸是取景框，默认窗/最小窗属宿主面真窗验收（G4 另一栏）。\n")
            fh.write("独立小窗（截图/屏幕协助）豁免 %d 屏：宿主运行时定尺寸，与 pairing-board 同一口径。\n" % len(host_skipped))
            fh.write("签收栏只能人填；agent 不代签。\n\n")
            fh.write("| shot | form | 锚点 | 渲染 px | 代码 px | 差 | 判定(偏差/代码错/设计错) | 签收 |\n")
            fh.write("|---|---|---|---|---|---|---|---|\n")
            for shot, form, name, got, want, d in rows:
                fh.write("| %s | %s | %s | %d | %d | %+d | 待判 | 待签 |\n" % (
                    shot, form, name, got, want, d))
            fh.write("\n共 %d 行。\n" % len(rows))
            fh.write("\n## 宿主面豁免（不在并排签收，走真窗验收清单）\n\n")
            for shot, why in host_skipped:
                fh.write("- %s：%s\n" % (shot, why))
        print("已写 %s" % markdown)
    return 0

def headline(shots, markdown=None):
    """G5 头条指标的**唯一出处**：逐槽位恰在代码档位率（用数加权），含 G2 各组的投影。

    投影不是承诺：它只回答「若某组按 expand-token 落档，该维度会到多少」——
    人用这个数字决定裁哪组最划算。落完档必须重跑本命令核对实际值，不引用投影交差。"""
    consts = conv.dc.read_code_tokens(REPO)
    excl, _unc = layout_margin_exclusions(shots)
    eff = render_values(shots, excl)
    rows = offladder_worklist(eff, consts)
    bytag = collections.defaultdict(int)
    for r in rows:
        bytag[r["dim"] + ":" + r["tag"]] += r["uses"]
    ladders = ladder_sets(consts)
    print("## 逐槽位恰在档位率（G5 头条；分母=整数档槽位，布局 margin 已剔 %d 处）\n" % eff["spacing"]["layout"])
    print("| 维度 | 槽位 | 恰在档 | 率 | 档外用数 |")
    print("|---|---|---|---|---|")
    tot = {}
    for dim in ("spacing", "radius", "font-size", "font-weight"):
        b = eff[dim]
        n = sum(b["steps"].values())
        ex = 0
        for v, c in b["steps"].items():
            fv = float(v)
            if fv in ladders[dim]:
                ex += c
        tot[dim] = (n, ex, n - ex)
        print("| %s | %d | %d | %.1f%% | %d |" % (dim, n, ex, 100.0 * ex / max(n, 1), n - ex))
    print("\n### G2 分组投影（裁到哪组 → 该维度到多少）\n")
    for key in sorted(bytag):
        dim = key.split(":")[0]
        proj = tot[dim][1] + bytag[key]
        print("- **%s**：%d 处；若落档，%s %.1f%% → **%.1f%%**" % (
              key, bytag[key], dim, 100.0 * tot[dim][1] / max(tot[dim][0], 1),
              100.0 * proj / max(tot[dim][0], 1)))
    a = sum(v for k, v in bytag.items() if k.startswith("spacing:rhythm"))
    b = sum(v for k, v in bytag.items() if k.startswith("radius:shape"))
    c = sum(v for k, v in bytag.items() if k.startswith("font-weight:"))
    print("\n### 组合：A(间距节奏)+B(形状语义)+C(字重角色) 同批落档")
    print("- 间距 %.1f%% → **%.1f%%** / 圆角 %.1f%% → **%.1f%%** / 字重 %.1f%% → **%.1f%%**" % (
          100.0 * tot["spacing"][1] / tot["spacing"][0],
          100.0 * (tot["spacing"][1] + a) / tot["spacing"][0],
          100.0 * tot["radius"][1] / tot["radius"][0],
          100.0 * (tot["radius"][1] + b) / tot["radius"][0],
          100.0 * tot["font-weight"][1] / tot["font-weight"][0],
          100.0 * (tot["font-weight"][1] + c) / tot["font-weight"][0]))
    rest = sum(v for k, v in bytag.items()
               if not (k.endswith("rhythm") or k.endswith("shape-semantics")
                       or k.startswith("font-weight")))
    print("- 其余组（offset/rare/type-ladder）合计 %d 处：**不靠扩档解决**，offset 表达或登记偏差" % rest)
    if markdown:
        buf = []
        class _Tee:
            def write(self, s):
                buf.append(s)
            def flush(self):
                pass
        import contextlib
        with contextlib.redirect_stdout(_Tee()):
            headline(shots)
        with open(markdown, "w", encoding="utf-8") as fh:
            fh.write("# G5 完成度头条（逐槽位恰在档位率）\n\n")
            fh.write("口径：分母=渲染整数档槽位数（布局 margin 已剔，判据=随画布宽度变化）。\n")
            fh.write("投影只用于决定裁哪组最划算；落档后必须重跑 --headline --markdown 取实测值。\n\n")
            fh.write("".join(buf))
    return 0


def code_value_census():
    """代码侧**逐值**字面量用量（spacing=padding(...)/Arrangement.spacing 里的 N.dp；
    radius=RoundedCornerShape(N.dp)）。这是给 G2 裁决看的那半边证据：

    设计侧一个档外值若代码里已有大量同值写法（实测 shape 档外半径 9/11/7 等），
    「扩档」= 承认既成事实；「snap」= 要改动 N 处在跑的代码。没有这列，
    两个选项看起来代价一样，人就只能凭感觉挑。

    口径：只数 .dp 字面量、剥注释（dc.strip_comments），与 scan_debt 同一读法；
    不试图判断它接到哪个组件——那是 C2 探针的活。"""
    sys.path.insert(0, HERE) if "design_contract" not in sys.modules else None
    import design_contract as dc  # 复用唯一读取器，不在这里另立一套正则口径
    sp = collections.Counter()
    rd = collections.Counter()
    for path in dc.iter_source_files(REPO):
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
        code = dc.strip_comments(text)
        # padding(...)/absoluteOffset 等实参里的 <N>.dp：只认括号内的第一段数字
        for m in re.finditer(r"padding\(([^)]*)\)", code):
            for dm in re.finditer(r"([\d.]+)\.dp", m.group(1)):
                sp[dm.group(1)] += 1
        for m in re.finditer(r"Arrangement\.\w+\(space\s*=\s*([\d.]+)\.dp\)", code):
            sp[m.group(1)] += 1
        for m in re.finditer(r"Spacer\(Modifier\.(?:height|width)\(([\d.]+)\.dp\)\)", code):
            sp[m.group(1)] += 1
        for m in re.finditer(r"RoundedCornerShape\(\s*([\d.]+)\.dp\s*\)", code):
            rd[m.group(1)] += 1
    return {"spacing": sp, "radius": rd}


def self_test():
    """先验量具。两条硬断言来自第一版的真实翻车：
      A. 简写拆边 + 亚像素 + 负 margin 让**原始** computed 种数比 CSS 文本更多。
         「渲染=更干净所以种数少」是错的方向，断言钉住它，别再翻一次。
      B. 顶栏锚点把 .bar(3px 进度条) 当顶栏是真出过的假数，修好的重算必须避开。
    """
    ok = True

    def check(label, cond, detail=""):
        nonlocal ok
        print("  %s %s %s" % ("OK" if cond else "BAD", label, detail))
        if not cond:
            ok = False

    def node(path, y, h, pad, margin, gap, radius, fs, fw):
        return {"path": path, "tag": "div", "rect": {"x": 0, "y": y, "w": 393, "h": h},
                "style": {"pad": pad, "margin": margin, "gap": gap, "radius": radius,
                          "font": {"size": fs, "weight": fw, "lh": 20.0},
                          "color": "rgb(0, 0, 0)", "bg": "rgba(0,0,0,0)",
                          "border": [0, "none"], "opacity": 1, "disp": "flex",
                          "pos": "static", "flexDir": "row"},
                "icon": None, "text": "", "kids": 0, "depth": 1}
    fake = {"meta": {"shot": "x", "size": "phone", "theme": "light"},
            "canvasViewport": {"w": 393, "h": 852, "x": 0, "y": 0},
            "nodes": [
                node("root>div.topbar", 0, 60, [8.0, 8.0, 8.0, 8.0], [0, 0, 0, 0],
                     [6.0, 6.0], [12.0, 12.0, 12.0, 12.0], 14.0, "600"),
                node("root>div.bar", 80, 3, [0, 0, 0, 0], [-3.0, 0, 0, 0], [0, 0],
                     [0, 0, 0, 0], 15.7656, "500"),
            ],
            "icons": {}, "anchors": {"siblingGaps": {"6": 2}, "rows": []}}
    excl, uncertain = layout_margin_exclusions([fake])
    eff = render_values([fake], excl)
    check("间距整数档={6,8}", set(eff["spacing"]["steps"]) == {6, 8}, str(dict(eff["spacing"]["steps"])))
    check("亚像素 15.7656 单独成桶、不污染档位",
          set(eff["font-size"]["subpixel"]) == {15.7656}, str(dict(eff["font-size"]["subpixel"])))
    check("负 margin 单独成桶", set(eff["spacing"]["negative"]) == {-3.0})
    check("0 计数但不进档位", eff["radius"]["zero"] > 0 and 0 not in eff["radius"]["steps"])
    check("顶栏=60 且 .bar(3px) 不算顶栏", topbar_of(fake) == 60, str(topbar_of(fake)))
    check("档位种数 <= 原始值种数（分桶只减不增）",
          len(eff["spacing"]["steps"]) <= 4)

    css, _ = conv.read_design_source(DESIGN_SOURCE)
    n_text, by_status, numeric = text_values(css, "spacing")
    check("文本侧种数>0", n_text > 0, "%d 种，var()=%d special=%d 可算数=%d"
          % (n_text, by_status.get("variable", 0), by_status.get("special", 0), len(numeric)))
    check("对照真的量到水（var 桶非空 = 源文本读不出数）", by_status.get("variable", 0) > 0,
          "var() 声明 %d 种" % by_status.get("variable", 0))
    # 方差检测器：同路径同边、值随画布宽度变化的 margin 必须被剔，小值不许碰
    fake["nodes"][0]["style"]["margin"] = [0, 0, 0, 319.0]      # 画布 393 -> 319
    wide = json.loads(json.dumps(fake))
    wide["canvasViewport"]["w"] = 760
    wide["nodes"][0]["style"]["margin"] = [0, 0, 0, 442.0]      # 同节点同边 -> 442
    excl_x, unc_x = layout_margin_exclusions([fake, wide])
    check("方差检测剔布局 margin（319/442 随宽度变）",
          {v for _p, _s, v in excl_x} == {319, 442}, str(sorted(excl_x))[:80])
    eff_x = render_values([fake, wide], excl_x)
    check("剔除后布局值不进档位", 319 not in eff_x["spacing"]["steps"]
          and 442 not in eff_x["spacing"]["steps"], str(eff_x["spacing"]["layout"]))
    check("小 margin（<24）不进剔除", all(v >= 24 for _p, _s, v in excl_x))
    shots = load_shots(LAYOUT_DIR)
    if shots:
        excl_all, unc_all = layout_margin_exclusions(shots)
        eff_all = render_values(shots, excl_all)
        raw_all = (len(eff_all["spacing"]["steps"]) + len(eff_all["spacing"]["subpixel"])
                   + len(eff_all["spacing"]["negative"]))
        check("A) 原始 computed 种数 >= 文本整数种数（不许再声称渲染侧种数更少）",
              raw_all >= len(numeric), "computed 整数+亚像素+负(间距)=%d vs 文本可算=%d" % (raw_all, len(numeric)))
        check("C) 布局 margin 剔除生效（实测有剔除发生）",
              eff_all["spacing"]["layout"] > 0, "剔了 %d 处" % eff_all["spacing"]["layout"])
        check("B) 整数档才是可对照的分母", len(eff_all["spacing"]["steps"]) > 0,
              "整数档 %d 种" % len(eff_all["spacing"]["steps"]))
    # 锚点对账：构造已知差（画布宽 1100 vs 常量 1000）必须报出来，同数必须不报
    fake2 = {"meta": {"shot": "k01", "size": "expanded", "theme": "light"},
             "canvasViewport": {"w": 1100, "h": 720, "x": 0, "y": 0},
             "nodes": [{"path": "root>div.win>div.titlebar", "tag": "div",
                        "rect": {"x": 0, "y": 0, "w": 1100, "h": 40},
                        "style": {"pad": [0, 0, 0, 0], "margin": [0, 0, 0, 0], "gap": [0, 0],
                                  "radius": [0, 0, 0, 0],
                                  "font": {"size": None, "weight": None, "lh": None},
                                  "color": "", "bg": "", "border": [0, "none"], "opacity": 1,
                                  "disp": "flex", "pos": "static", "flexDir": "row"},
                        "icon": None, "text": "", "kids": 0, "depth": 1}],
             "icons": {}, "anchors": {"rows": [], "siblingGaps": {}}}
    c2 = {"TITLEBAR_PX": 40, "SIDEBAR_PX": 300}
    # 已知差：titlebar 57≠40 要报；40 不报；.side 240 在落点集合里不报；999 不在要报
    fake3 = {"meta": {"shot": "k02", "size": "expanded", "theme": "light"},
             "canvasViewport": {"w": 1100, "h": 720, "x": 0, "y": 0},
             "nodes": fake2["nodes"] + [
                 {"path": "root>div.win>div.split>div.side", "tag": "div",
                  "rect": {"x": 0, "y": 40, "w": 240, "h": 600}, "viewport": {"x": 0, "y": 40},
                  "style": fake2["nodes"][0]["style"], "icon": None, "text": "", "kids": 0,
                  "depth": 3}],
             "icons": {}, "anchors": {"rows": [], "siblingGaps": {}}}
    dr, _hs = anchor_diff([fake2, fake3], c2, land={240})
    check("落点命中的左栏宽不误报（240 in land）",
          not any(r[2] == "左栏宽" for r in dr), str(dr))
    check("同数 titlebar 不误报（40 vs 40）",
          not any(r[2] == "标题栏高" for r in dr), str(dr))
    fake4 = {"meta": {"shot": "k03", "size": "expanded", "theme": "light"},
             "canvasViewport": {"w": 1100, "h": 720, "x": 0, "y": 0},
             "nodes": [dict(fake2["nodes"][0],
                            rect={"x": 0, "y": 0, "w": 1100, "h": 57})],
             "icons": {}, "anchors": {"rows": [], "siblingGaps": {}}}
    fake5 = {"meta": {"shot": "k04", "size": "expanded", "theme": "light"},
             "canvasViewport": {"w": 1100, "h": 720, "x": 0, "y": 0},
             "nodes": [dict(fake2["nodes"][0],
                            rect={"x": 0, "y": 0, "w": 1100, "h": 57})],
             "icons": {}, "anchors": {"rows": [], "siblingGaps": {}}}
    dr2, _h2 = anchor_diff([fake5], c2, land=set())
    check("已知差被报出（titlebar 57 vs 40）",
          bool(dr2) and dr2[0][2] == "标题栏高" and dr2[0][5] == 17, str(dr2))
    fw = {"meta": {"shot": "k05", "size": "expanded", "theme": "light", "screen": "d-capture"},
          "canvasViewport": {"w": 300, "h": 186}, "nodes": [], "icons": {},
          "anchors": {"rows": [], "siblingGaps": {}}}
    dr3, hs3 = anchor_diff([fw], c2, land=set())
    check("独立小窗豁免不判差", dr3 == [] and len(hs3) == 1, str((dr3, hs3)))
    # census 与既有独立量具交叉：RoundedCornerShape 计数必须等于
    # design_contract.scan_shape_inventory 的 raw 总数（两边各写一套正则口径 = 老错误）
    cen = code_value_census()
    inv = conv.dc.scan_shape_inventory(REPO)
    check("census 圆角 == scan_shape_inventory.raw 总数",
          sum(cen["radius"].values()) == sum(inv["raw"].values()),
          "census=%d vs inventory=%d" % (sum(cen["radius"].values()), sum(inv["raw"].values())))
    check("已知答案：圆角 9 在代码侧有落点（账本报过它档外）", cen["radius"].get("9", 0) > 0,
          "9dp x%s" % cen["radius"].get("9"))
    check("真实数据跑通且无 -800 假差（落点集合 %d 个）" % len(code_width_landing_points()),
          all(abs(r[5]) < 300 for r in anchor_diff(load_shots(LAYOUT_DIR),
                                                   code_layout_constants(),
                                                   code_width_landing_points())[0]))
    print("[self-test] " + ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser(description="渲染真值对照与锚点表")
    ap.add_argument("--layout-dir", default=LAYOUT_DIR)
    ap.add_argument("--markdown")
    ap.add_argument("--screen")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--offladder-md", help="把档外残差清单+分类裁决表落成 markdown（goal G2 入口）")
    ap.add_argument("--headline", action="store_true", help="G5 头条：逐槽位恰在档位率 + G2 分组投影")
    ap.add_argument("--anchor-check", action="store_true", help="几何锚点 vs DesktopLayout 常量（G4 差集行）")
    ap.add_argument("--anchor-md", help="把锚点差集落成 markdown")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    if args.headline:
        return headline(load_shots(args.layout_dir), args.markdown)
    if args.anchor_check or args.anchor_md:
        shots = load_shots(args.layout_dir)
        return anchor_check_main(shots, args.anchor_md)
    if args.offladder_md:
        shots = load_shots(args.layout_dir)
        excl, _unc = layout_margin_exclusions(shots)
        code = conv.dc.read_code_tokens(REPO)
        rows, tot = offladder_markdown(shots, code, args.offladder_md, excl,
                                   code_value_census())
        top10 = sum(r["uses"] for r in sorted(rows, key=lambda x: -x["uses"])[:10])
        print("档外 %d 个值 / %d 处用数；top10 值占 %.1f%% -> %s"
              % (len(rows), tot, 100.0 * top10 / (tot or 1), args.offladder_md))
        return 0
    return compare(args.layout_dir, args.markdown, args.screen)


if __name__ == "__main__":
    sys.exit(main())