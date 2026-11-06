#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""原型 stroke 图标 → VectorDrawable（**保留笔触语言**，不是转成填充）。

## 为什么不是 fill

Android VectorDrawable 原生支持 `strokeColor / strokeWidth / strokeLineCap /
strokeLineJoin`，Compose Multiplatform 的 XML vector 解析器同样支持。原型 sprite 正是
一套自洽的 stroke 语言（`fill:none; stroke:currentColor; stroke-width:1.7;
linecap/linejoin:round`），所以正确的搬法是**原样保留 stroke**；把它转成填充会丢掉
线宽/端点/接合，正是 `icon-layer.md` 说的"整套换掉还看不出来"那类事故。
（曾走过 svglib 描边转填充的弯路，已废弃：多一个重依赖，还主动丢掉要保的语言。）

## 这个工具实际做什么

VectorDrawable 的 `<vector>` 只接受 `<path>`（外加 group/clip-path），不认 `<circle>` /
`<rect>`。所以按元素转换：

| 原型元素 | 转换 |
|---|---|
| `<path d=…>` | 原样保留 `d` |
| `<circle cx cy r>` | 两段半圆弧：`M cx-r,cy a r,r 0 1,0 2r,0 a r,r 0 1,0 -2r,0` |
| `<rect x y width height [rx]>` | 四边 + 圆角弧（无 rx 则直角 H/V） |
| 内联 `fill=currentColor stroke=none`（样本：`i-batt` 电量条） | 转 `android:fillColor="#000000"`、去掉 stroke |

公共属性从原型 CSS 继承（`--stroke-width`），可被 symbol 内联属性覆盖。

## 不变量（写盘前检查，任一条不过就跳过该图标）

viewport 24×24、至少一条 path、路径数据非空、`</vector>` 结尾、**不得残留
`stroke-dasharray`**（VDrawable 不支持，遇到要显式报错而不是静默丢效果）。

用法：
    python3 scaffold/tools/design-icon-vectorize.py --symbol chat        # 打印 XML
    python3 scaffold/tools/design-icon-vectorize.py --list-missing       # 列出缺档
    python3 scaffold/tools/design-icon-vectorize.py --write-missing      # 只新增缺档（不覆盖）
    python3 scaffold/tools/design-icon-vectorize.py --self-test
"""
from __future__ import annotations

import argparse
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DESIGN = "prototype/relay.html"
VECTOR_DIR = "scaffold/composeApp/src/commonMain/composeResources/drawable"
DEFAULT_STROKE_W = "1.7"
TINT = "#000000"
# 透明填充必须写 8 位十六进制：Compose Multiplatform 的 XmlVectorParser 不认
# `@android:color/transparent`（那是 Android 平台资源引用），会抛
# IllegalArgumentException: Invalid color value —— 实测 101 帧渲染失败就是它。
# （Android 原生 VectorDrawable 认那个写法，但 commonMain 资源走的是 CMP 解析器。）
TRANSPARENT = "#00000000"


class VectorizeError(RuntimeError):
    pass


# ── 设计源读取 ────────────────────────────────────────────────────
def read_sprite(html: str):
    m = re.search(r"<svg style=\"display:none\".*?</svg>", html, re.S)
    if not m:
        raise VectorizeError("找不到 sprite（<svg style=\"display:none\">…</svg>）")
    sprite = m.group(0)
    symbols = {}
    for sid, body in re.findall(r"<symbol id=\"([^\"]+)\"[^>]*>(.*?)</symbol>", sprite, re.S):
        name = sid[2:] if sid.startswith("i-") else sid
        symbols[name] = body.strip()
    view_boxes = set(re.findall(r"viewBox=\"([^\"]+)\"", sprite))
    if view_boxes != {"0 0 24 24"}:
        raise VectorizeError(f"viewBox 不是全集 0 0 24 24：{sorted(view_boxes)}")
    css = re.search(r"<style[^>]*>(.*?)</style>", html, re.S)
    stroke_w = DEFAULT_STROKE_W
    if css:
        sw = re.search(r"svg\{[^}]*stroke-width:\s*([\d.]+)", css.group(1))
        if sw:
            stroke_w = sw.group(1)
    return symbols, stroke_w


def parse_elements(body: str):
    """→ [{"tag":…, "attrs":{…}}, …]（保持文档顺序）。"""
    out = []
    for tag, attrs in re.findall(r"<(path|circle|rect)\b([^>]*?)/?>", body):
        out.append({"tag": tag, "attrs": dict(re.findall(r'([\w-]+)="([^"]*)"', attrs))})
    return out


def _f(a, key, default=0.0):
    try:
        return float(a.get(key, default))
    except (TypeError, ValueError):
        raise VectorizeError(f"{key} 不是数字：{a.get(key)!r}")


def circle_to_path(a) -> str:
    cx, cy, r = _f(a, "cx"), _f(a, "cy"), _f(a, "r")
    if r <= 0:
        raise VectorizeError(f"circle 半径非正：r={r}")
    return (f"M{cx - r:g},{cy:g} "
            f"a{r:g},{r:g} 0 1,0 {2 * r:g},0 "
            f"a{r:g},{r:g} 0 1,0 {-2 * r:g},0")


def rect_to_path(a) -> str:
    x, y = _f(a, "x"), _f(a, "y")
    w, h = _f(a, "width"), _f(a, "height")
    if w <= 0 or h <= 0:
        raise VectorizeError(f"rect 尺寸非正：{w}×{h}")
    rx = _f(a, "rx", 0.0)
    ry = _f(a, "ry", rx) or rx
    if rx <= 0:
        return f"M{x:g},{y:g} H{x + w:g} V{y + h:g} H{x:g} Z"
    rx = min(rx, w / 2)
    ry = min(ry, h / 2)
    return (f"M{x + rx:g},{y:g} "
            f"H{x + w - rx:g} A{rx:g},{ry:g} 0 0 1 {x + w:g},{y + ry:g} "
            f"V{y + h - ry:g} A{rx:g},{ry:g} 0 0 1 {x + w - rx:g},{y + h:g} "
            f"H{x + rx:g} A{rx:g},{ry:g} 0 0 1 {x:g},{y + h - ry:g} "
            f"V{y + ry:g} A{rx:g},{ry:g} 0 0 1 {x + rx:g},{y:g} Z")


def to_vector(body: str, name: str, stroke_w: str) -> str:
    elements = parse_elements(body)
    if not elements:
        raise VectorizeError(f"{name}: symbol 里没有任何 path/circle/rect")
    lines = []
    for el in elements:
        a = el["attrs"]
        if "stroke-dasharray" in a:
            raise VectorizeError(f"{name}: 含 stroke-dasharray，VectorDrawable 不支持，需人工重绘虚线")
        filled = a.get("fill") == "currentColor" and a.get("stroke") == "none"
        if el["tag"] == "path":
            d = a.get("d", "").strip()
        elif el["tag"] == "circle":
            d = circle_to_path(a)
        else:
            d = rect_to_path(a)
        if not d:
            raise VectorizeError(f"{name}: {el['tag']} 转换后路径为空")
        if filled:
            lines.append(f'    <path\n        android:fillColor="{TINT}"\n'
                         f'        android:pathData="{d}" />')
        else:
            sw = a.get("stroke-width", stroke_w)
            lines.append(
                f'    <path\n'
                f'        android:strokeColor="{TINT}"\n'
                f'        android:strokeWidth="{sw}"\n'
                f'        android:strokeLineCap="round"\n'
                f'        android:strokeLineJoin="round"\n'
                f'        android:fillColor="#00000000"\n'
                f'        android:pathData="{d}" />')
    return (f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="24dp"\n'
            f'    android:height="24dp"\n'
            f'    android:viewportWidth="24"\n'
            f'    android:viewportHeight="24">\n'
            + "\n".join(lines) + "\n</vector>\n")


def invariants(xml: str):
    problems = []
    if 'android:viewportWidth="24"' not in xml or 'android:viewportHeight="24"' not in xml:
        problems.append("viewport 不是 24×24")
    if xml.count("<path") < 1:
        problems.append("零 path")
    if 'android:pathData=""' in xml:
        problems.append("存在空 pathData")
    if "stroke-dasharray" in xml:
        problems.append("残留 stroke-dasharray")
    if "@android:color/" in xml or "@android:" in xml:
        problems.append("引用了 @android: 平台资源 —— CMP 的 XmlVectorParser 不认，"
                        "渲染期抛 IllegalArgumentException（透明用 #00000000）")
    if not xml.rstrip().endswith("</vector>"):
        problems.append("未以 </vector> 结尾")
    if 'android:fillColor="#00000000"'.count and \
            "--" in xml:
        pass
    return problems


# ── 缺档清单（来自映射表工具）───────────────────────────────────────
def missing_symbols(root: str):
    """读映射表 JSON（若在）拿"缺"档；没有则退回内置清单。"""
    import json
    p = "/tmp/icon-map.json"
    if os.path.exists(p):
        data = json.load(open(p, encoding="utf-8"))
        return [r["symbol"] for r in data["rows"] if r["status"] == "缺"]
    raise VectorizeError("先跑：python3 scaffold/tools/design-icon-map.py --json /tmp/icon-map.json")


def self_test():
    fails = []

    def ok(cond, msg):
        if not cond:
            fails.append(msg)

    # circle：半径 3、圆心 (12,12) → 两段弧，且首点 = (9,12)
    d = circle_to_path({"cx": "12", "cy": "12", "r": "3"})
    ok(d.startswith("M9,12"), f"circle 起点应为 9,12：{d}")
    ok(d.count("a3,3 0 1,0") == 2, f"circle 应两段半圆弧：{d}")
    # rect 无 rx → 直角 H/V
    ok(rect_to_path({"x": "3", "y": "4", "width": "18", "height": "12"}) == "M3,4 H21 V16 H3 Z",
       "rect 直角路径不符")
    # rect 带 rx → 四条弧
    r = rect_to_path({"x": "0", "y": "0", "width": "10", "height": "10", "rx": "2"})
    ok(r.count("A2,2") == 4, f"圆角矩形应有 4 条弧：{r}")
    # 真实 symbol：chat 是两条 path，应全为 stroke
    html = open(os.path.join(".", DESIGN), encoding="utf-8").read()
    symbols, sw = read_sprite(html)
    xml = to_vector(symbols["chat"], "chat", sw)
    ok(xml.count("<path") == 2, "chat 应有 2 条 path")
    ok(xml.count('android:strokeColor="#000000"') == 2, "chat 两条都应是 stroke")
    ok(f'android:strokeWidth="{sw}"' in xml, f"strokeWidth 应继承 {sw}")
    ok(not invariants(xml), f"chat 不变量应过：{invariants(xml)}")
    ok("TRANSPARENT" in xml or "#00000000" in xml,
       "stroke path 必须用 8 位透明填充（#00000000）")
    ok("@android:" not in xml, "不得引用 @android: 平台资源（CMP 解析器不认）")
    # 内联 fill 覆盖必须保留为 fill
    batt = to_vector(symbols["batt"], "batt", sw)
    ok(batt.count('android:fillColor="#000000"') == 1, "batt 电量条应是 fill")
    # 缺档里含 circle/rect 的必须能转
    for name in ("user", "batt", "wifi"):
        if name in symbols:
            ok(not invariants(to_vector(symbols[name], name, sw)), f"{name} 不变量应过")
    print("self-test: OK（circle/rect 换算、stroke 保留、内联 fill、不变量）"
          if not fails else "FAIL:\n  " + "\n  ".join(fails))
    return 0 if not fails else 1


def main():
    ap = argparse.ArgumentParser(description="原型 stroke 图标 → VectorDrawable（保留笔触）")
    ap.add_argument("--repo", default=".")
    ap.add_argument("--symbol")
    ap.add_argument("--list-missing", action="store_true")
    ap.add_argument("--write-missing", action="store_true")
    ap.add_argument("--dry", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()

    root = os.path.abspath(args.repo)
    html = open(os.path.join(root, DESIGN), encoding="utf-8").read()
    symbols, stroke_w = read_sprite(html)

    if args.symbol:
        xml = to_vector(symbols[args.symbol], args.symbol, stroke_w)
        probs = invariants(xml)
        if probs:
            print("不变量失败: " + "; ".join(probs), file=sys.stderr)
            return 2
        sys.stdout.write(xml)
        return 0

    if args.list_missing:
        for name in missing_symbols(root):
            print(f"  {name}{'（缺 symbol）' if name not in symbols else ''}")
        return 0

    if args.write_missing:
        out_dir = os.path.join(root, VECTOR_DIR)
        os.makedirs(out_dir, exist_ok=True)
        made, skipped = [], []
        for name in missing_symbols(root):
            if name not in symbols:
                skipped.append((name, "symbol 不存在"))
                continue
            try:
                xml = to_vector(symbols[name], name, stroke_w)
            except VectorizeError as e:
                skipped.append((name, str(e)))
                continue
            probs = invariants(xml)
            if probs:
                skipped.append((name, "; ".join(probs)))
                continue
            dst = os.path.join(out_dir, f"ic_{name.replace('-', '_')}.xml")
            if os.path.exists(dst):
                skipped.append((name, "文件已存在（不覆盖）"))
                continue
            made.append((dst, xml))
            print(f"  {'(dry) ' if args.dry else ''}+ {os.path.relpath(dst, root)}"
                  f"（{xml.count('<path')} path，stroke）")
        if not args.dry:
            for dst, xml in made:
                open(dst, "w", encoding="utf-8").write(xml)
        for name, why in skipped:
            print(f"  跳过 {name}：{why}", file=sys.stderr)
        print(f"合计 新增 {len(made)} 个{'' if not args.dry else '（dry-run，未写盘）'}，跳过 {len(skipped)} 个")
        return 0

    ap.print_help()
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except VectorizeError as e:
        print(f"[icon-vectorize] ERROR: {e}", file=sys.stderr)
        sys.exit(2)
