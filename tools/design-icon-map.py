#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""图标映射表生成器（只读）：原型 stroke symbol ↔ 代码图标，逐条带状态。

skill `prototype-to-compose` / references/icon-layer.md 的"第 4 张映射表"实现：
图标是四层里唯一"语义对上了就算对上了"的一层 —— 语义相同、几何不同（fill vs stroke）
必须**显式占一行**，否则整套换掉都没人看得出来。

产出（默认打印；--markdown 落文件；--json 落机器可读台账）：
  原型 symbol（含调用次数）→ 代码图标（来源 vector/图标库 + 规格 fill/stroke）
  → 状态 {复用 / 重绘 / 替代 / 缺 / 未使用} + 尺寸档

**「调用次数」必须数全原型引用一个 symbol 的每一种写法**，否则状态表会凭空多出
"未使用"的 symbol 来。这份原型的四种写法（第 23 轮补全，前三种此前只数了第一种）：

  ic('name')        —— 模板里直接调用（206 处）
  icon:'name'       —— NAV / DVIEWS / TABS 这类数据数组的字段
  i:'name'          —— 截图工具条 `CAP_TOOLS` 的字段（`{t:'箭头', i:'capture-arrow'}`）
  #i-name"          —— 直接 `<use href="#i-name">`

还有一类**静态数不出名字**的调用：`ic(n.icon)` / `ic(v.icon)`（名字来自数据字段）。
它们计入 `dynamicCalls`，不计入任何 symbol —— 假装它们不存在会让"未使用"这个结论失真。

`未使用` 判据 = **四种写法一处都没有**。这类 symbol 不需要代码对应物，
因此**不进保真分母**（棘轮只钉被引用的 symbol）；反过来，一旦某个 symbol 从"未使用"
变成"被引用"，棘轮会以提示形式说出来，不会静默。

条数断言：映射表行数 ≥ 工具从设计源数出的"被引用 symbol 数"（不许漏行）。

每产品配置 = 文件头 4 个常量。
"""
from __future__ import annotations

import argparse
import collections
import hashlib
import json
import os
import re
import sys

DESIGN = "prototype/relay.html"
CODE_ROOT = "."
# 代码侧图标库前缀（grep 口径）
ICONS_USAGE = ["composeApp/src/commonMain", "desktopApp/src/main"]
VECTOR_DIR = "composeApp/src/commonMain/composeResources/drawable"

# 原型 symbol 名 → 代码图标候选（Material 名 / ic_ 名）。人工维护的语义桥，
# 命中只算"候选"，状态仍按几何与来源判定；未命中的 symbol 一律进"缺"由人裁决。
SEMANTIC = {
    "chat": ["ic_chat_bubble", "Icons.Outlined.Chat", "Icons.AutoMirrored.Filled.Chat"],
    "group": ["ic_groups", "Icons.Outlined.Groups", "Icons.Outlined.People"],
    "list": ["Icons.Outlined.List", "Icons.Outlined.ViewList"],
    "lan": ["ic_lan_search", "Icons.Outlined.Lan"],
    "terminal": ["ic_terminal", "Icons.Outlined.Terminal"],
    "qr": ["ic_qr_code_scanner", "Icons.Outlined.QrCodeScanner", "Icons.Outlined.QrCode2"],
    "gear": ["ic_settings", "Icons.Outlined.Settings"],
    "send": ["ic_send", "Icons.AutoMirrored.Filled.Send", "Icons.Outlined.Send"],
    "clip": ["ic_attachment", "Icons.Outlined.AttachFile"],
    "save": ["ic_save"],
    "plus": ["Icons.Outlined.Add", "ic_add_node"],
    "minus": ["ic_minus", "Icons.Outlined.Remove"],
    "smile": ["ic_smile", "Icons.Outlined.EmojiEmotions"],
    "search": ["ic_search", "Icons.Outlined.Search"],
    "back": ["ic_arrow_back", "Icons.AutoMirrored.Filled.ArrowBack", "Icons.Outlined.ArrowBack"],
    "chev": ["Icons.AutoMirrored.Filled.KeyboardArrowRight", "Icons.Outlined.ChevronRight"],
    "chevd": ["ic_chevd", "Icons.Outlined.ExpandMore", "Icons.Outlined.KeyboardArrowDown"],
    "check": ["ic_check_double", "Icons.Outlined.Check", "Icons.Outlined.DoneAll"],
    "clock": ["ic_clock_pending", "Icons.Outlined.Schedule", "Icons.Outlined.AccessTime"],
    "alert": ["ic_alert", "Icons.Outlined.Warning", "Icons.Outlined.ErrorOutline"],
    "bot": ["ic_bot", "Icons.Outlined.SmartToy", "Icons.Outlined.Bot"],
    "user": ["ic_user", "Icons.Outlined.Person", "Icons.Outlined.AccountCircle"],
    "file": ["Icons.Outlined.InsertDriveFile", "Icons.Outlined.Description"],
    "folder": ["Icons.Outlined.Folder", "Icons.Outlined.FolderOpen"],
    "box": ["Icons.Outlined.Inventory", "Icons.Outlined.Inventory2"],
    "refresh": ["Icons.Outlined.Refresh"],
    "info": ["ic_info", "Icons.Outlined.Info"],
    "key": ["ic_key", "Icons.Outlined.Key", "Icons.Outlined.VpnKey"],
    "shield": ["Icons.Outlined.Shield", "Icons.Outlined.Security"],
    "bell": ["Icons.Outlined.Notifications"],
    "moon": ["Icons.Outlined.DarkMode", "Icons.Outlined.Nightlight"],
    "sun": ["ic_sun", "Icons.Outlined.LightMode", "Icons.Outlined.WbSunny"],
    "trash": ["ic_trash", "Icons.Outlined.Delete", "Icons.Outlined.DeleteOutline"],
    "warn": ["ic_warn", "Icons.Outlined.Warning"],
    "link": ["Icons.Outlined.Link"],
    "wifi": ["ic_wifi", "Icons.Outlined.Wifi"],
    "batt": ["ic_batt", "Icons.Outlined.BatteryStd"],
    "lock": ["ic_lock", "Icons.Outlined.Lock"],
    "close": ["ic_close", "Icons.Outlined.Close"],
    "copy": ["ic_copy", "Icons.Outlined.ContentCopy"],
    "external": ["Icons.Outlined.OpenInNew"],
    "down": ["Icons.Outlined.FileDownload", "Icons.Outlined.Download"],
    "pk": ["ic_pk"],
    "nudge": ["ic_nudge", "Icons.Outlined.TouchApp"],
    "scissors": ["ic_scissors", "Icons.Outlined.ContentCut", "Icons.Outlined.Crop"],
    "image": ["ic_image", "Icons.Outlined.Image"],
    "up": ["ic_up", "Icons.Outlined.FileUpload", "Icons.Outlined.Upload"],
    "drop": ["ic_drop", "Icons.Outlined.Inbox"],
    "eye": ["ic_eye", "Icons.Outlined.Visibility"],
    "hdd": ["ic_hdd", "Icons.Outlined.HardDrive", "Icons.Outlined.Storage"],
    "window": ["ic_window", "Icons.Outlined.WebAsset", "Icons.Outlined.OpenInFull"],
    "hand": ["ic_hand", "Icons.Outlined.PanTool", "Icons.Outlined.Draw"],
    "speaker": ["ic_speaker", "Icons.Outlined.VolumeUp"],
    "grid": ["ic_grid", "Icons.Outlined.GridView"],
    "more": ["ic_more", "Icons.Outlined.MoreVert"],
    "filter": ["ic_filter", "Icons.Outlined.FilterList"],
    "sort": ["ic_sort", "Icons.Outlined.Sort"],
    "capture-select": ["ic_capture_select", "Icons.Outlined.Mouse"],
    "capture-rect": ["ic_capture_rect", "Icons.Outlined.CropFree", "Icons.Outlined.Highlight"],
    "capture-arrow": ["ic_capture_arrow", "Icons.Outlined.ArrowForward", "Icons.AutoMirrored.Filled.ArrowForward"],
    "capture-mosaic": ["ic_capture_mosaic", "Icons.Outlined.BlurOn", "Icons.Outlined.Mosaic"],
    "text-select": ["Icons.Outlined.TextFields"],
    "screen": ["ic_screen_share", "Icons.Outlined.ScreenShare", "Icons.Outlined.Monitor"],
}


class IconMapError(RuntimeError):
    pass


def sha12(text: str) -> str:
    return hashlib.sha256(text.encode()).hexdigest()[:12]


# ── 设计侧 ────────────────────────────────────────────────────────
# 原型引用一个 sprite symbol 的**每一种写法**。少一种，状态表就会凭空多出"未使用"的
# symbol —— 第 23 轮实测：只数第一种时 4 个 symbol（capture-arrow / capture-mosaic /
# capture-select / text-select）被误判为 0 次调用，它们其实写在截图工具条的数据数组里
# （`{t:'箭头', i:'capture-arrow'}`，`prototype/relay.html:4598-4600`）。
REF_FORMS = (
    r"ic\('{n}'\)",          # 模板里直接调用
    r"\bicon:\s*'{n}'",      # NAV / DVIEWS / TABS 数据字段
    r"\bi:\s*'{n}'",         # 截图工具条 CAP_TOOLS 数据字段
    r'#i-{n}"',              # 直接 <use href="#i-name">
)
# 名字来自数据字段的调用：`ic(n.icon)` / `ic(v.icon)` / `ic(${...})`。
DYNAMIC_CALL = re.compile(r"ic\(\s*(?!['\"])[^)]{0,60}?\)")


def count_refs(raw: str, name: str) -> int:
    return sum(len(re.findall(fmt.format(n=re.escape(name)), raw)) for fmt in REF_FORMS)


def read_prototype(path: str):
    raw = open(path, encoding="utf-8").read()
    m = re.search(r"<svg style=\"display:none\".*?</svg>", raw, re.S)
    if not m:
        raise IconMapError(f"{path} 找不到 sprite <svg style=\"display:none\"> 块")
    symbols = {}
    dupes = collections.Counter()
    tags = 0
    for sid, body in re.findall(r"<symbol id=\"([^\"]+)\"[^>]*>(.*?)</symbol>", m.group(0), re.S):
        name = sid[2:] if sid.startswith("i-") else sid
        tags += 1
        dupes[name] += 1
        els = re.findall(r"<(path|circle|rect)\b", body)
        d = re.findall(r'd="([^"]+)"', body)
        # 重名 symbol：**后定义的赢**（浏览器与 <use> 都按后者），所以这里覆盖是"取到
        # 实际渲染的那一条"；但必须把重名本身报出来 —— 静默覆盖就是分母悄悄变小。
        symbols[name] = {"elements": collections.Counter(els), "paths": d}
    calls = collections.Counter({n: count_refs(raw, n) for n in symbols})
    dynamic = collections.Counter(m.strip() for m in DYNAMIC_CALL.findall(raw))
    stroke = re.search(r"svg\{[^}]*stroke-width:\s*([\d.]+)", raw)
    sizes = collections.Counter(re.findall(r"svg\{width:(\d+)px", raw))
    return {"symbols": symbols, "calls": calls, "dynamic": dynamic,
            "strokeWidth": (stroke.group(1) if stroke else None), "sizes": sizes,
            "symbolTags": tags,
            "duplicateSymbols": {k: v for k, v in dupes.items() if v > 1}}


# ── 代码侧 ────────────────────────────────────────────────────────
def read_code_icons(root: str):
    vec_dir = os.path.join(root, VECTOR_DIR)
    vectors = {}
    if os.path.isdir(vec_dir):
        for fn in sorted(os.listdir(vec_dir)):
            if not fn.endswith(".xml"):
                continue
            x = open(os.path.join(vec_dir, fn), encoding="utf-8").read()
            vectors[fn[:-4]] = {
                "kind": "vector",
                "fillPaths": len(re.findall(r'android:fillColor(?!="@android:color/transparent")', x)),
                "strokePaths": len(re.findall(r'android:strokeColor', x)),
            }
    usage = collections.Counter()
    for sub in ICONS_USAGE:
        base = os.path.join(root, sub)
        for dp, _, fns in os.walk(base):
            if "/build/" in dp + "/":
                continue
            for fn in fns:
                if not fn.endswith(".kt"):
                    continue
                for line in open(os.path.join(dp, fn), encoding="utf-8"):
                    code = line.split("//")[0]
                    usage.update(re.findall(r"Icons\.(?:AutoMirrored\.)?(?:Filled|Outlined)\.[A-Za-z0-9_]+", code))
                    usage.update("Res.drawable." + v for v in re.findall(r"Res\.drawable\.(ic_[a-z0-9_]+)", code))
    return vectors, usage


def count_call_sites(root: str):
    """图标**调用点**分类计数：库图标（Icons.*）vs 自绘资源（Res.drawable.ic_*）。

    与 `usage` 的区别：usage 按图标名聚合（用来判 symbol 状态），这里数**调用点**
    （用来算消费率）。两个数不同源：一个图标被调用 4 次算 4 个调用点，但只算 1 个名字。
    goal ③ 要的"图标消费率进棘轮"量的是这个——**消费率 = 资源调用点 / 全部调用点**。
    """
    lib = res = 0
    for sub in ICONS_USAGE:
        base = os.path.join(root, sub)
        for dp, _, fns in os.walk(base):
            if "/build/" in dp + "/":
                continue
            for fn in fns:
                if not fn.endswith(".kt"):
                    continue
                for line in open(os.path.join(dp, fn), encoding="utf-8"):
                    code = line.split("//")[0]
                    lib += len(re.findall(r"Icons\.(?:AutoMirrored\.)?(?:Filled|Outlined)\.[A-Za-z0-9_]+", code))
                    res += len(re.findall(r"Res\.drawable\.(ic_[a-z0-9_]+)", code))
    return {"library": lib, "resource": res, "total": lib + res,
            "rate": (res / (lib + res)) if (lib + res) else 0.0}


# ── 状态判定 ──────────────────────────────────────────────────────
def norm_paths(d):
    return tuple(sorted(x.strip() for x in d))


def classify(sym, cand_vectors, usage, proto_paths, referenced: bool = True):
    """→ (status, target, note)

    判定顺序（每一条都对应一种真实混淆）：
      ⓪ 原型四种引用写法一处都没有 → 未使用（**不进保真分母**）
      ① 几何完全一致 → 复用（path 归一化相等；只对同形态 path 有效）
      ② 语义桥指向的 vector **文件存在** → 复用/重绘：
         - 该 vector 全 stroke（与原型同语言）→ **复用**（即使是 circle/rect 换算来的，
           笔触语言一致就算搬过来了；形态差异只是 VDrawable 表达方式）
         - 该 vector 是 fill 语言（如既有 Material 单 path）→ **重绘**（需按 stroke 重画）
      ③ 语义桥指向图标库且确有调用 → 替代（库字形 ≠ stroke，必须显式记账）
      ④ 其余 → 缺（显式报，不静默丢）

    ⓪ 必须排在 ①②③④ 之前：sprite 里定义过、原型一次没用过的 symbol（`box`/`alert`/
    `filter`/`lock`/`sun`），给它判「复用/重绘/缺」都是**在给一个不存在的问题找答案** ——
    第 16 轮正是按「有对应物」的口径给这 5 个各生成了一颗 vector，而它们的代码调用点
    全部是 0：画了白画。
    """
    if not referenced:
        return ("未使用", "",
                "原型四种引用写法（ic('x') / icon:'x' / i:'x' / #i-x）一处都没有 ⇒ 无需代码对应物")
    for name, meta in cand_vectors.items():
        if meta.get("paths") and norm_paths(meta["paths"]) == norm_paths(proto_paths):
            return "复用", f"vector:{name}", "path 逐条一致"
    for name in SEMANTIC.get(sym, []):
        if name.startswith("ic_") and name in cand_vectors:
            if cand_vectors[name].get("strokePaths", 0) > 0:
                return "复用", f"vector:{name}", "stroke 语言与原型一致（circle/rect 已换算为等价 path）"
            return "重绘", f"vector:{name}", "同语义 vector 是 fill 语言（需按原型 stroke 重绘）"
    for name in SEMANTIC.get(sym, []):
        if name in usage:
            return "替代", name, "图标库字形替代 stroke 原型（语言不同，进偏差账本或重绘）"
    for name in SEMANTIC.get(sym, []):
        if name.startswith("ic_") and name not in cand_vectors:
            return "缺", name, "语义桥指向 vector 但文件不存在"
    return "缺", "", "无任何对应：既无 vector 也无库图标命中"


def build(root_dir: str):
    design_path = os.path.join(root_dir, DESIGN)
    proto = read_prototype(design_path)
    symbols, calls = proto["symbols"], proto["calls"]
    dynamic, stroke_w, sizes = proto["dynamic"], proto["strokeWidth"], proto["sizes"]
    scaffold = os.path.join(root_dir, CODE_ROOT)
    vectors, usage = read_code_icons(scaffold)
    # 合并两处读取：read_code_icons 给笔触语言（fillPaths/strokePaths），这里补 path 数据
    # （几何比对要用）。**必须同一份字典** —— 两个字典分开过一次，
    # classify 拿到的手上没有 strokePaths，于是"复用"判据静默失效（缺档明明已生成仍报缺）。
    vdir = os.path.join(scaffold, VECTOR_DIR)
    if os.path.isdir(vdir):
        for fn in os.listdir(vdir):
            if fn.endswith(".xml"):
                x = open(os.path.join(vdir, fn), encoding="utf-8").read()
                vectors.setdefault(fn[:-4], {})["paths"] = re.findall(r'android:pathData="([^"]+)"', x)
    vec_full = vectors
    rows = []
    for name, meta in sorted(symbols.items()):
        n = calls.get(name, 0)
        status, target, note = classify(name, vec_full, usage, meta["paths"], referenced=n > 0)
        rows.append({"symbol": name, "calls": n, "elements": dict(meta["elements"]),
                     "status": status, "target": target, "note": note})
    unused_proto = sorted(set(calls) - set(symbols))
    code_only = sorted(set(vectors) - {r["target"].split(":")[-1] for r in rows if r["target"].startswith("vector")})
    return {
        "source": {"path": DESIGN, "sha256": sha12(open(design_path, encoding="utf-8").read())},
        "strokeWidth": stroke_w, "sizeSlots": dict(sizes), "dynamicCalls": sum(dynamic.values()),
        "dynamicForms": dict(dynamic.most_common(6)),
        "symbolTags": proto["symbolTags"], "duplicateSymbols": proto["duplicateSymbols"],
        "symbols": len(symbols), "calledSymbols": sum(1 for n in calls.values() if n > 0),
        "unusedSymbols": sorted(n for n, c in calls.items() if c == 0),
        "calls": dict(calls),
        "rows": rows, "unusedProtoNames": unused_proto, "codeOnlyVectors": code_only,
        "codeUsage": dict(usage.most_common()),
        "callSites": count_call_sites(scaffold),
    }


def render_md(data):
    dup = data["duplicateSymbols"]
    dup_s = ("；**重名 symbol**：" + "、".join(f"`{k}` 定义 {v} 次（后定义的赢，唯一名因此少 {v - 1} 个）"
                                             for k, v in sorted(dup.items()))) if dup else ""
    unused_s = "、".join(f"`{s}`" for s in data["unusedSymbols"]) or "（无）"
    L = ["# 图标映射表（原型 stroke ↔ 代码图标 · 只读生成）", "",
         f"设计源 `{data['source']['path']}` @ sha256:{data['source']['sha256']}；"
         f"sprite **{data['symbolTags']} 个 `<symbol>` 标签 / {data['symbols']} 个唯一名**"
         f"（被引用 {data['calledSymbols']} 个，未使用 {len(data['unusedSymbols'])} 个：{unused_s}）"
         f"{dup_s}；动态调用 {data['dynamicCalls']} 处名字来自数据字段、无法静态归到某个 symbol"
         f"（{data['dynamicForms']}）；"
         f"笔触 stroke-width:{data['strokeWidth']}；尺寸档 {sorted(data['sizeSlots'], key=int)}。", "",
         "| symbol | 调用 | 元素 | 状态 | 代码目标 | 说明 |", "|---|---|---|---|---|---|"]
    order = {"复用": 0, "重绘": 1, "替代": 2, "缺": 3, "未使用": 4}
    for r in sorted(data["rows"], key=lambda r: (order[r["status"]], -r["calls"])):
        els = " ".join(f"{k}×{v}" for k, v in sorted(r["elements"].items()))
        L.append(f"| {r['symbol']} | {r['calls']} | {els} | **{r['status']}** | {r['target'] or '—'} | {r['note']} |")
    cnt = collections.Counter(r["status"] for r in data["rows"])
    L += ["", f"状态分布：{dict(cnt)}；保真分母 = 被引用 symbol **{data['calledSymbols']}** 个"
          f"（`未使用` 的 {len(data['unusedSymbols'])} 个不计入）；"
          f"行数 {len(data['rows'])} ≥ 被调用 symbol 数断言："
          f"{'过' if len(data['rows']) >= data['calledSymbols'] else '不过'}", ""]
    cs = data.get("callSites") or {}
    if cs:
        L += ["## 图标消费率（棘轮：只许升）", "",
              f"资源调用点 **{cs['resource']}** / 全部 **{cs['total']}** = "
              f"**{cs['rate']:.1%}**（库图标调用点 {cs['library']}）", "",
              "状态表只说明「有没有自绘资源」，不说明「代码有没有用它」——"
              f"{len(data['rows'])} 行全绿而代码仍写 `Icons.Outlined.Folder` 时状态表看不出来。"
              "这个数才是 goal ③ 的量。降了门禁报红。", ""]
    if data["codeOnlyVectors"]:
        L += ["代码侧存在但未被任何原型 symbol 认领的 vector（画了白画/或原型无对应语义）：",
              "、".join(data["codeOnlyVectors"]), ""]
    return "\n".join(L)


def self_test():
    fails = []
    # 已知答案先验：sprite 里定义的每个 symbol 必须成行；i-chat 必须映射到 ic_chat_bubble 系
    d = build(".")
    names = {r["symbol"] for r in d["rows"]}
    assert "chat" in names and "capture-rect" in names, "已知 symbol 缺行"
    chat = next(r for r in d["rows"] if r["symbol"] == "chat")
    assert chat["status"] in ("复用", "重绘"), f"chat 应命中 vector，实际 {chat}"
    # 条数断言
    assert len(d["rows"]) >= d["calledSymbols"], "行数必须 ≥ 被调用 symbol 数"
    # 未命中语义桥的 symbol 必须落"缺"而不是静默消失
    fake = classify("nonexistent-xyz", {}, {}, [])
    assert fake[0] == "缺", "无对应必须显式报缺"
    # ── 第 23 轮新增：引用写法数全 / 未使用 / 重名，三件都必须有已知答案先验 ──
    # ① 四种写法：只数 ic('x') 会漏掉写在数据数组里的 4 个（第 23 轮实测）
    for n in ("capture-arrow", "capture-mosaic", "capture-select", "text-select"):
        assert n in d["calls"] and d["calls"][n] > 0, \
            f"「{n}」只出现在 i:'{n}' 这种写法里，漏掉它就会把它误判成未使用"
    # ② sprite 标签数 vs 唯一名：`i-image` 定义了两次，折叠必须是**被报出来的**折叠
    tag_n, uniq_n = d["symbolTags"], d["symbols"]
    collapse = sum(v - 1 for v in d["duplicateSymbols"].values())
    assert tag_n == uniq_n + collapse, \
        f"标签数 {tag_n} 与唯一名 {uniq_n} 的差必须恰好等于重名折叠数 {collapse}"
    assert d["duplicateSymbols"], "这份 sprite 有重名 symbol（i-image ×2），检测器必须报出来"
    # **第 48 轮**：原型 2026-10-13 再次换版（sha c51db35449f9 → 8c1076aa19a6）：新增
    # `i-chevd`/`i-nudge`/`i-pk`/`i-save`/`i-scissors` 五个 symbol，并把 `i-image` **又定义了一次**
    # （同名 id 第二次出现 ⇒ 标签 64 / 唯一名 63，折叠数 1）。已知答案 63/62 → 64/63 是跟着
    # 权威源走，不是放宽判据：重名本身仍被 `duplicateSymbols` 咬住（上一行），
    # 标签数与唯一名之差必须恰好等于折叠数（再上一行）。
    assert (tag_n, uniq_n) == (64, 63), \
        f"sprite 标签/唯一名 {tag_n}/{uniq_n} 与冻结原型（sha 8c1076aa19a6）的已知答案 64/63 不符"
    # ③ 未使用 = 四种写法一处都没有；这 6 个是已知答案（第 16 轮曾给前 5 个各生成一颗死 vector）。
    # **第 47 轮**：原型 2026-10-12 换版（sha 4f46e83cfffd → c51db35449f9）后，`clock` 从
    # "被引用"掉进了"未使用"—— 旧稿 `nodeRow` 的离线副标题里写着
    # `(p.online ? '在线' : '离线 ' + ic('clock') + (p.lastSeen || '刚刚'))`，
    # 新稿把那一整段副标题砍了（节点行只留 头像 + 昵称 + IP），于是全稿只剩 sprite 里
    # 那一行定义。**这不是量具变钝，是原型真的不再画它**：`ic_clock_pending` 在代码侧
    # 仍有 3 处真消费（ChatScreen 投递态、NodesScreen、DesktopSettingsChrome），
    # 所以这颗 vector 也不能删。已知答案从 5 改成 6 是跟着权威源走，不是放宽判据。
    assert set(d["unusedSymbols"]) == {"alert", "box", "clock", "filter", "lock", "sun"}, \
        f"未使用集合应恰为 6 个已知 symbol，实际 {d['unusedSymbols']}"
    assert d["calledSymbols"] == uniq_n - len(d["unusedSymbols"]), \
        "被引用数 + 未使用数 必须等于唯一名数（没有第三种归宿）"
    # ④ 未使用不进保真分母：同一 symbol 传 referenced=False 必须判未使用
    assert classify("box", {}, {}, [], referenced=False)[0] == "未使用", "未引用必须判未使用"
    # ⑤ 动态调用不再假装是 0（ic(n.icon) / ic(v.icon) 这类）
    assert d["dynamicCalls"] > 0, "这份原型有 ic(n.icon) 这类调用，动态计数不该是 0"
    # ⑥ 棘轮：symbol 从"被引用"掉进"未使用"是**提示**不是报红（原型不再引用它）
    b_ref = {"source": {"sha256": "x"}, "symbols": {"box": "重绘"}, "unused": [],
             "missing": [], "codeOnlyVectors": []}
    c_unused = {"source": {"sha256": "x"}, "symbols": {}, "unused": ["box"],
                "missing": [], "codeOnlyVectors": []}
    assert compare(c_unused, b_ref)[0] == [], \
        "原型不再引用的 symbol 不该报红（它已不在保真分母里）"
    assert any("不再引用" in n for n in compare(c_unused, b_ref)[1]), \
        "掉进未使用必须留一条提示，不能静默"
    # ⑦ 反方向：从"未使用"变成被引用 = 分母变大，必须提示
    b_un = {"source": {"sha256": "x"}, "symbols": {}, "unused": ["chat"],
            "missing": [], "codeOnlyVectors": []}
    c_ref = {"source": {"sha256": "x"}, "symbols": {"chat": "复用"}, "unused": [],
             "missing": [], "codeOnlyVectors": []}
    assert any("保真分母变大" in n for n in compare(c_ref, b_un)[1]), \
        "未使用→被引用必须提示重记基线"
    # 降级必须报红（复用→替代 也算，不只是 →缺）
    base_sig = {"source": {"sha256": "x"}, "symbols": {"folder": "复用"}, "missing": [],
                "codeOnlyVectors": []}
    cur_sig = {"source": {"sha256": "x"}, "symbols": {"folder": "替代"}, "missing": [],
               "codeOnlyVectors": []}
    iss, _ = compare(cur_sig, base_sig)
    assert iss, "复用降级为替代必须报红（只卡『缺』会漏掉这类回归）"
    ok_sig = {"source": {"sha256": "x"}, "symbols": {"folder": "复用"}, "missing": [],
              "codeOnlyVectors": []}
    assert compare(ok_sig, base_sig)[0] == [], "状态不变不该报红"
    # 消费率棘轮：降必须报红、升只提示、不变不吭声
    rate_base = {"source": {"sha256": "x"}, "symbols": {}, "missing": [], "codeOnlyVectors": [],
                 "callSites": {"library": 80, "resource": 20, "total": 100, "rate": 0.20}}
    rate_down = {"source": {"sha256": "x"}, "symbols": {}, "missing": [], "codeOnlyVectors": [],
                 "callSites": {"library": 90, "resource": 20, "total": 110, "rate": 20 / 110}}
    rate_up = {"source": {"sha256": "x"}, "symbols": {}, "missing": [], "codeOnlyVectors": [],
               "callSites": {"library": 70, "resource": 30, "total": 100, "rate": 0.30}}
    assert any("消费率下降" in i for i in compare(rate_down, rate_base)[0]), \
        "资源调用点占比下降必须报红（有人把 painterResource 换回 Icons.*）"
    assert compare(rate_up, rate_base)[0] == [], "消费率上升不该报红"
    assert any("消费率上升" in n for n in compare(rate_up, rate_base)[1]), \
        "消费率上升必须提示重记基线（否则基线永远停在低值）"
    assert compare(rate_base, rate_base)[0] == [], "消费率不变不该报红"
    # 基线没有 callSites 时（老基线）不许因为"读不到"就报红或静默通过
    assert compare({"source": {"sha256": "x"}, "symbols": {}, "missing": [],
                    "codeOnlyVectors": []}, base_sig)[0] == [], "无 callSites 的当前值不该报红"
    assert compare(rate_down, {"source": {"sha256": "x"}, "symbols": {}, "missing": [],
                               "codeOnlyVectors": []})[0] == [], "基线无 callSites 时跳过该维度"
    print("self-test: OK（已知 symbol 成行、条数断言、缺判定、降级报红、消费率三向、"
          "四种引用写法、重名折叠守恒、未使用集合、未使用不进分母、动态调用非零）"
          if not fails else fails)
    return 0


def signature(data):
    """棘轮签名：**被引用**的 symbol → 状态（"缺"必须为零），外加代码侧未认领 vector 集合。

    `未使用` 的 symbol **不进 `symbols`** —— 棘轮只钉保真分母（被引用的 symbol）。
    把「sprite 里定义过、原型一次没用过」的 5 个算进分母，会让分母虚高、
    也会让它们的「复用/重绘」状态在账面上替一个不存在的问题背书。
    但 `unused` 仍写进签名：**符号进/出这个集合是要说话的**（见 compare）。
    """
    cs = data.get("callSites") or {}
    return {
        "source": data["source"],
        "symbols": {r["symbol"]: r["status"] for r in data["rows"] if r["status"] != "未使用"},
        "allSymbols": {r["symbol"]: r["status"] for r in data["rows"]},
        "unused": data["unusedSymbols"],
        "missing": sorted(r["symbol"] for r in data["rows"] if r["status"] == "缺"),
        "codeOnlyVectors": data["codeOnlyVectors"],
        # 消费率棘轮：**只许升不许降**。这条是 goal ③ 的核心量——
        # 状态表（复用/重绘/替代/缺）只说明"有没有资源"，不说明"代码有没有用它"：
        # 62 行全绿 + 代码仍在调 Icons.Folder，状态表一点看不出来。
        "callSites": cs,
    }


def compare(cur, base):
    """→ (issues, notes)。缺档不许新增；已认领的 symbol 不许退回"缺"。"""
    issues, notes = [], []
    bs = (base.get("source") or {}).get("sha256")
    if bs and cur["source"]["sha256"] != bs:
        notes.append(f"设计源已变（基线 sha {bs} → 当前 {cur['source']['sha256']}）")
    # 严格度序：复用（有与原型同语言的 vector）> 重绘（同语义但 fill 语言）> 替代（库花字）> 缺
    # **任何降级都报红**，不只是降到"缺"——只卡"缺"会漏掉一整类回归：
    # 删掉 ic_folder.xml 后 folder 退到"替代"（库花字顶上了），门禁悄然变绿。
    # 这是量具先验（临时删文件）实测出来的洞，不是推测。
    RANK = {"复用": 3, "重绘": 2, "替代": 1, "缺": 0}
    cur_unused = set(cur.get("unused") or [])
    for sym in cur["missing"]:
        if sym not in (base.get("missing") or []):
            issues.append(f"原型 symbol「{sym}」没有归宿（缺）：既无 vector 也无库图标命中")
    for sym, st in (base.get("symbols") or {}).items():
        if sym in cur_unused:
            # 从"被引用"掉进"未使用"：原型不再引用它 ⇒ 不进保真分母，只提示。
            notes.append(f"symbol「{sym}」原型已不再引用（{st} → 未使用）：不再计入保真分母")
            continue
        now = cur["symbols"].get(sym)
        if now is None:
            notes.append(f"基线里的 symbol「{sym}」在当前 sprite 中不存在（原型可能删了它）")
        elif RANK.get(now, 0) < RANK.get(st, 0):
            issues.append(f"symbol「{sym}」由 {st} 降级为 {now}"
                          f"（对应资源被删或改名；要么补回资源，要么在账本登记这次有意降级）")
    for sym in cur["symbols"]:
        if sym in set(base.get("unused") or []):
            notes.append(f"symbol「{sym}」从「未使用」变成被引用（{cur['symbols'][sym]}）："
                         f"保真分母变大，请重记基线")
    # 消费率棘轮：资源调用点占比只许升。降 = 有人把 painterResource 换回 Icons.*，
    # 或新代码直接用了库图标——状态表看不出来（资源还在，只是没人用）。
    bcs = base.get("callSites") or {}
    ccs = cur.get("callSites") or {}
    if bcs:
        br, cr = bcs.get("rate", 0.0), ccs.get("rate", 0.0)
        if cr < br - 1e-9:
            issues.append(
                f"图标消费率下降：资源调用点 {bcs.get('resource')}/{bcs.get('total')}"
                f"（{br:.1%}）→ {ccs.get('resource')}/{ccs.get('total')}（{cr:.1%}）"
                f"；要么把新调用改回 painterResource，要么在账本登记")
        elif cr > br + 1e-9:
            notes.append(f"图标消费率上升：{br:.1%} → {cr:.1%}"
                         f"（资源 {bcs.get('resource')}→{ccs.get('resource')}，"
                         f"库 {bcs.get('library')}→{ccs.get('library')}）"
                         f"；请重跑 --write-baseline 抬基线")
    return issues, notes


def main():
    ap = argparse.ArgumentParser(description="图标映射表生成器（只读）")
    ap.add_argument("--repo", default=".")
    ap.add_argument("--markdown")
    ap.add_argument("--json")
    ap.add_argument("--check", action="store_true", help="棘轮：缺档不许新增")
    ap.add_argument("--write-baseline", action="store_true")
    ap.add_argument("--baseline", default="tools/design-icon-baseline.json")
    ap.add_argument("--dry", action="store_true", help="与 --apply 相对；本工具从不需要，兼容门禁调用")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    root = os.path.abspath(args.repo)
    data = build(root)

    if args.write_baseline:
        bp = os.path.join(root, args.baseline)
        os.makedirs(os.path.dirname(bp), exist_ok=True)
        open(bp, "w", encoding="utf-8").write(
            json.dumps(signature(data), ensure_ascii=False, indent=1) + "\n")
        print(f"[icon-map] 基线已写入 {args.baseline}")
    if args.check:
        bp = os.path.join(root, args.baseline)
        if not os.path.exists(bp):
            print(f"[icon-map] FAIL — 缺基线 {args.baseline}（先跑 :designIconBaseline）")
            return 1
        issues, notes = compare(signature(data), json.load(open(bp, encoding="utf-8")))
        for n in notes:
            print("  ⚠", n)
        for i in issues:
            print("  -", i)
        if issues:
            print(f"[icon-map] FAIL — {len(issues)} 条（symbol 不许降级、图标消费率不许下降）")
            return 1
        cnt = collections.Counter(r["status"] for r in data["rows"])
        print(f"[icon-map] OK — 保真分母 {data['calledSymbols']} 个被引用 symbol 全部有归宿 "
              f"{dict(cnt)}（`未使用` {len(data['unusedSymbols'])} 个不计入："
              f"{'、'.join(data['unusedSymbols'])}）"
              + ("（有警示）" if notes else ""))
        return 0
    md = render_md(data)
    if args.markdown:
        open(os.path.join(root, args.markdown), "w", encoding="utf-8").write(md + "\n")
        print(f"[icon-map] 已写 {args.markdown}")
    if args.json:
        open(os.path.join(root, args.json), "w", encoding="utf-8").write(
            json.dumps(data, ensure_ascii=False, indent=1) + "\n")
        print(f"[icon-map] 已写 {args.json}")
    # --check/--write-baseline 是门禁调用，不该顺带打整张表（会淹掉构建日志）
    if not (args.markdown or args.json or args.check or args.write_baseline):
        print(md)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except IconMapError as e:
        print(f"[icon-map] ERROR: {e}", file=sys.stderr)
        sys.exit(2)
