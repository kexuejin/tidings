#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""逐屏调色板对账：截图参考图里到底画了哪些颜色，是不是这套色板里的。

## 为什么不是像素相似度

`scaffold/design/design-spec.json` 的 `qualityGate.majorRegionSimilarity = 0.88`
本来想当「原型 vs Compose 逐屏 diff」的判据。**实测它没有分辨力**（2026-10-07，
24 张桌面参考图两两比对 276 对）：

    纯白图 ↔ desk-conversations-expanded                 region=0.961
    desk-conversations-expanded ↔ desk-nodes-expanded    0.952   ← 不同屏
    desk-conversations-expanded ↔ desk-conversations-empty 0.974  ← 不同屏
    原型 01-desk-chat.png ↔ desk-conversations-expanded  0.930   ← 跨引擎同屏
    276 对里 77.2% 都「通过」0.88；掉到 0.88 以下的只有深浅主题之别

原因很朴素：这些界面七成以上是白底，逐像素平均差被背景淹没 —— **它量的是
「这页有多白」，不是「这页像不像」**。所以本工具不量相似度，量**颜色本身**：
参考图里占比超过阈值的每个精确色值，必须是这套色板里说得出出处的颜色。

## 什么算「说得出出处」

1. **声明色** —— `ui/theme/Color.kt` / `Accents.kt` 里的 `Color(0xFF……)` 字面量，
   外加 `design-spec.json` 的 `tokens.colors` / `colorsDark`（两边取并集，因为
   规范是从代码投影出来的，谁缺了都不该让检查误报）。
   2026-10-14 补：**半透明声明色**（`Color(0x12000000)` = 7% 黑，原型 `--hair` 的发丝线）
   也算这一类，但它只能当「over」：按**它自己的 alpha** 叠在每个不透明声明色上，算出来的
   结果才是可渲染的像素色（`#EDEDED` = 它叠在 `#FFFFFF` 上）。它自己不是一个像素色，
   不能当叠层底。

2. **派生色** —— 声明色按 Material 的标准透明度叠在另一个声明色上：
   海拔 1/2/3/4/5/6/8/12dp 的 `surfaceTint` 叠层、状态层 8%/10%/16%、
   禁用容器 12% / 禁用内容 38%。`Surface(tonalElevation = 1.dp)` 出来的
   `#F4F7F6` 就是这一类，它是算出来的，不该逼人去 `Color.kt` 里抄一份。
3. **`design-palette-accepted.txt` 里的白名单** —— 截图夹具、宿主默认底、
   刻意写的纯黑这类「不是产品色板但确知从哪来」的颜色，每条都要写理由。

**不在这三类里的，就是缺陷**：最典型的是「没被赋值的 Material 3 角色回退到
基线紫色」。2026-10-07 首次跑出来的就是它 —— 桌面外壳约 11% 的像素画在
`#F3EDF7` / `#F7F2FA` / `#E6E0E9` 上，那三个正是 M3 基线的
`surfaceContainer` / `surfaceContainerLow` / `surfaceContainerHighest`。

## 用法

    python3 scaffold/tools/check-design-palette.py                 # 报告
    python3 scaffold/tools/check-design-palette.py --strict        # 有缺陷就非零退出
    python3 scaffold/tools/check-design-palette.py --refs <目录>    # 换一批图查

依赖只有 ImageMagick 的 `magick`（`histogram:info:-`），不需要 numpy/PIL；
ubuntu-latest 自带 imagemagick。
"""

from __future__ import annotations

import argparse
import json
import math
import re
import subprocess
import sys
from pathlib import Path

# Material 3 **基线**配色：某个角色没人赋值时就会回退到这些值。带上角色名，
# 报告才能直接说出「你把 surfaceContainer 画成了紫色」，而不是甩一个色值让人猜。
M3_BASELINE = {
    "light": {
        "primary": "#6750A4", "onPrimary": "#FFFFFF", "primaryContainer": "#EADDFF",
        "onPrimaryContainer": "#21005D", "secondary": "#625B71", "onSecondary": "#FFFFFF",
        "secondaryContainer": "#E8DEF8", "onSecondaryContainer": "#1D192B",
        "tertiary": "#7D5260", "onTertiary": "#FFFFFF", "tertiaryContainer": "#FFD8E4",
        "onTertiaryContainer": "#31111D", "error": "#B3261E", "onError": "#FFFFFF",
        "errorContainer": "#F9DEDC", "onErrorContainer": "#410E0B",
        "background": "#FFFBFE", "onBackground": "#1C1B1F",
        "surface": "#FFFBFE", "onSurface": "#1C1B1F",
        "surfaceVariant": "#E7E0EC", "onSurfaceVariant": "#49454F",
        "outline": "#79747E", "outlineVariant": "#CAC4D0",
        "inverseSurface": "#313033", "inverseOnSurface": "#F4EFF4",
        "inversePrimary": "#D0BCFF", "surfaceTint": "#6750A4", "scrim": "#000000",
        "surfaceBright": "#FFFBFE", "surfaceDim": "#DED8E1",
        "surfaceContainerLowest": "#FFFFFF", "surfaceContainerLow": "#F7F2FA",
        "surfaceContainer": "#F3EDF7", "surfaceContainerHigh": "#ECE6F0",
        "surfaceContainerHighest": "#E6E0E9",
    },
    "dark": {
        "primary": "#D0BCFF", "onPrimary": "#381E72", "primaryContainer": "#4F378B",
        "onPrimaryContainer": "#EADDFF", "secondary": "#CCC2DC", "onSecondary": "#332D41",
        "secondaryContainer": "#4A4458", "onSecondaryContainer": "#E8DEF8",
        "tertiary": "#EFB8C8", "onTertiary": "#492532", "tertiaryContainer": "#633B48",
        "onTertiaryContainer": "#FFD8E4", "error": "#F2B8B5", "onError": "#601410",
        "errorContainer": "#8C1D18", "onErrorContainer": "#F9DEDC",
        "background": "#1C1B1F", "onBackground": "#E6E1E5",
        "surface": "#1C1B1F", "onSurface": "#E6E1E5",
        "surfaceVariant": "#49454F", "onSurfaceVariant": "#CAC4D0",
        "outline": "#938F99", "outlineVariant": "#49454F",
        "inverseSurface": "#E6E1E5", "inverseOnSurface": "#313033",
        "inversePrimary": "#6750A4", "surfaceTint": "#D0BCFF", "scrim": "#000000",
        "surfaceBright": "#3B383E", "surfaceDim": "#141218",
        "surfaceContainerLowest": "#0F0D13", "surfaceContainerLow": "#1D1B20",
        "surfaceContainer": "#211F26", "surfaceContainerHigh": "#2B2930",
        "surfaceContainerHighest": "#36343B",
    },
}

# 「角色名 → 色值」查表时手上只有色值，所以反转一份。
M3_ROLE = {k: {v.upper(): r for r, v in roles.items()} for k, roles in M3_BASELINE.items()}

# Material 的海拔叠层透明度：alpha = (4.5 * ln(elevation + 1) + 2) / 100。
# 1dp 恰好 0.0512 —— Surface(tonalElevation = 1.dp) 出的 #F4F7F6 就是它。
ELEVATION_ALPHAS = [round((4.5 * math.log(e + 1) + 2) / 100, 6) for e in (1, 2, 3, 4, 5, 6, 8, 12)]
STATE_ALPHAS = [0.08, 0.10, 0.16, 0.12, 0.38]
ALL_ALPHAS = sorted(set(ELEVATION_ALPHAS + STATE_ALPHAS))

# 8 位 ARGB（Compose 的常规写法）与 6 位 RGB 都能读；8 位的 alpha 保留下来 ——
# 半透明声明色不能丢 alpha（丢了就成了一个不存在的纯色）。
HEX_RE = re.compile(r"0x([0-9a-fA-F]{8})\b|0x([0-9a-fA-F]{6})\b")
HIST_RE = re.compile(r"^\s*(\d+):\s*\([^)]*\)\s*#([0-9A-Fa-f]{6,8})")

THEME_FILES = [
    "composeApp/src/commonMain/kotlin/app/relay/ui/theme/Color.kt",
    "composeApp/src/commonMain/kotlin/app/relay/ui/theme/Accents.kt",
]


def repo_root() -> Path:
    return Path(__file__).resolve().parents[1]


def rgb(hexv: str) -> tuple[int, int, int]:
    """`#RRGGBB` 或 `#AARRGGBB`（半透明声明色，见 load_declared）。"""
    i = 3 if len(hexv) == 9 else 1
    return (int(hexv[i:i + 2], 16), int(hexv[i + 2:i + 4], 16), int(hexv[i + 4:i + 6], 16))


def alpha_of(hexv: str) -> float:
    """声明色自身的 alpha（6 位 = 不透明）。"""
    return int(hexv[1:3], 16) / 255 if len(hexv) == 9 else 1.0


def hexof(c: tuple[int, int, int]) -> str:
    return "#%02X%02X%02X" % c


def blend(over: str, base: str, alpha: float) -> str:
    o, b = rgb(over), rgb(base)
    return hexof(tuple(round(o[i] * alpha + b[i] * (1 - alpha)) for i in range(3)))


def load_declared(root: Path) -> dict[str, list[str]]:
    """色值 → 出处列表。代码与规范取并集：规范是从代码投影出来的，谁缺了都不该误报。"""
    declared: dict[str, list[str]] = {}

    def add(hexv: str, where: str) -> None:
        declared.setdefault(hexv.upper(), [])
        if where not in declared[hexv.upper()]:
            declared[hexv.upper()].append(where)

    for rel in THEME_FILES:
        path = root / rel
        if not path.exists():
            continue
        name = path.name
        for line in path.read_text("utf-8").splitlines():
            for m in HEX_RE.finditer(line):
                if m.group(1):  # 8 位 ARGB
                    argb = m.group(1).upper()
                    # alpha=FF 与 6 位写法等价；只有真半透明才保留 8 位形态
                    add("#" + argb if argb[:2] != "FF" else "#" + argb[2:], name)
                else:
                    add("#" + m.group(2).upper(), name)

    spec_path = root / "design/design-spec.json"
    if spec_path.exists():
        tokens = json.loads(spec_path.read_text("utf-8")).get("tokens", {})
        for group in ("colors", "colorsDark"):
            for role, value in tokens.get(group, {}).items():
                if isinstance(value, str) and value.startswith("#") and len(value) in (7, 9):
                    add(value, f"design-spec.json:{group}.{role}")

    return declared


def derived_palette(declared: dict[str, list[str]]) -> dict[str, str]:
    """所有「声明色 @ 透明度 over 不透明声明色」的结果 → 一句话说明。

    半透明声明色（`#AARRGGBB`）只当 over，且**用它自己的 alpha**（不是 Material 那套档位）：
    它自己不是可渲染像素，叠在不透明底上之后才是。
    """
    out: dict[str, str] = {}
    colors = list(declared)
    opaque = [c for c in colors if len(c) == 7]
    for over in colors:
        alphas = [alpha_of(over)] if len(over) == 9 else ALL_ALPHAS
        for base in opaque:
            if over == base:
                continue
            for a in alphas:
                out.setdefault(blend(over, base, a), f"{over} @{a:.4g} over {base}")
    return out


ACCEPTED_RE = re.compile(r"^#([0-9A-Fa-f]{6})\s+(\S+)\s+(.+)$")


def load_accepted(root: Path) -> dict[str, str]:
    """白名单：`#RRGGBB <浅|深|两态> <理由>`。

    注释行以 `# `（井号加空白）或单个 `#` 开头 —— 不能简单地把所有 `#` 开头的行
    都当注释，因为**每一条都是 `#` 开头的色值**。
    """
    path = root / "tools/design-palette-accepted.txt"
    out: dict[str, str] = {}
    if not path.exists():
        return out
    for raw in path.read_text("utf-8").splitlines():
        line = raw.strip()
        if not line or line == "#" or line.startswith("# "):
            continue
        m = ACCEPTED_RE.match(line)
        if not m:
            print(f"design-palette-accepted.txt 这一行看不懂：{raw}", file=sys.stderr)
            continue
        out["#" + m.group(1).upper()] = m.group(3)
    return out


def histogram(path: Path) -> list[tuple[str, int]]:
    """[(#RRGGBB, 像素数)]，按像素数降序。只依赖 ImageMagick。"""
    out = subprocess.run(
        ["magick", str(path), "-alpha", "off", "-format", "%c", "histogram:info:-"],
        capture_output=True,
        check=True,
    ).stdout.decode("utf-8", "replace")
    rows: list[tuple[str, int]] = []
    for line in out.splitlines():
        m = HIST_RE.match(line)
        if m:
            rows.append(("#" + m.group(2)[:6].upper(), int(m.group(1))))
    rows.sort(key=lambda r: -r[1])
    return rows


COLOR_SCHEME_RE = re.compile(r"(?:light|dark)ColorScheme\s*\(")
ROLE_ASSIGN_RE = re.compile(r"^\s*(\w+)\s*=", re.M)
ROLE_USE_RE = re.compile(r"colorScheme\.(\w+)")


def assigned_roles(root: Path) -> set[str]:
    """`Color.kt` 里 `lightColorScheme(...)`/`darkColorScheme(...)` 赋过值的角色名。

    用括号配平扫出两个块的正文，而不是全文正则 —— 全文正则会把文件里任何
    四空格缩进的赋值都当角色（这个文件里恰好只有角色，但那是巧合，不是保证）。
    """
    path = root / "composeApp/src/commonMain/kotlin/app/relay/ui/theme/Color.kt"
    if not path.exists():
        return set()
    text = path.read_text("utf-8")
    roles: set[str] = set()
    for m in COLOR_SCHEME_RE.finditer(text):
        depth, i = 0, m.end() - 1
        while i < len(text):
            if text[i] == "(":
                depth += 1
            elif text[i] == ")":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        roles.update(ROLE_ASSIGN_RE.findall(text[m.end():i]))
    return roles


def scan_role_fallbacks(root: Path, assigned: set[str]) -> list[tuple[str, int, str]]:
    """代码里用了 `colorScheme.<角色>` 却从没赋过值的角色。

    这是**必然**的 Material 基线回退：`lightColorScheme()` 会给没传的角色填自己的默认值。
    像素检查看不见小的东西（排队徽标只占一帧的 0.15%），这条不需要渲染就能看见。
    """
    uses: dict[str, list[str]] = {}
    for path in root.rglob("*.kt"):
        parts = path.parts
        if any(p.startswith(".") or p == "build" for p in parts):
            continue
        for i, line in enumerate(path.read_text("utf-8", errors="ignore").splitlines(), 1):
            for m in ROLE_USE_RE.finditer(line):
                uses.setdefault(m.group(1), []).append(f"{path.relative_to(root)}:{i}")
    return [
        (role, len(hits), hits[0])
        for role, hits in sorted(uses.items())
        if role not in assigned
    ]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=None)
    ap.add_argument(
        "--refs",
        default="androidApp/src/screenshotTestDebug/reference",
        help="参考图目录（相对仓库根）",
    )
    ap.add_argument("--min-coverage", type=float, default=0.5, help="低于这个占比的颜色忽略（%%）")
    ap.add_argument("--strict", action="store_true", help="有缺陷就非零退出")
    ap.add_argument("--quiet", action="store_true", help="只打汇总")
    args = ap.parse_args()

    root = Path(args.root).resolve() if args.root else repo_root()
    declared = load_declared(root)
    derived = derived_palette(declared)
    accepted = load_accepted(root)
    if not declared:
        print("一个声明色都没读到 —— 路径变了？", file=sys.stderr)
        return 2

    refs = sorted((root / args.refs).rglob("*.png"))
    if not refs:
        print(f"[check-design-palette] 未找到参考图目录（{root / args.refs}）。\n"
              f"开源仓库默认不包含大体积截图基准（避免 Git 体积膨胀）；\n"
              f"如需本地像素对账，请先运行 ./gradlew updateDebugScreenshotTest 录制基准。已安全跳过。")
        return 0

    # 每个色值只判一次（判据与主题无关：基线表两态都查）。
    verdict: dict[str, str] = {}

    def classify(hexv: str) -> str:
        if hexv in verdict:
            return verdict[hexv]
        if hexv in declared:
            v = "declared"
        elif hexv in accepted:
            v = "accepted"
        elif hexv in derived:
            v = "derived"
        else:
            role = M3_ROLE["light"].get(hexv) or M3_ROLE["dark"].get(hexv)
            v = f"M3 基线 {role}" if role else "unknown"
        verdict[hexv] = v
        return v

    bad_frames: list[tuple[str, float, list[tuple[str, float, str]]]] = []
    tally: dict[str, float] = {}

    for frame in refs:
        rows = histogram(frame)
        total = sum(n for _, n in rows)
        if total == 0:
            continue
        bad: list[tuple[str, float, str]] = []
        drift = 0.0
        for hexv, n in rows:
            pct = n * 100.0 / total
            if pct < args.min_coverage:
                continue
            v = classify(hexv)
            if v in ("declared", "accepted", "derived"):
                continue
            drift += pct
            bad.append((hexv, pct, v))
            tally[hexv] = tally.get(hexv, 0.0) + pct
        if bad:
            bad_frames.append((str(frame.relative_to(root)), drift, bad))

    bad_frames.sort(key=lambda f: -f[1])

    assigned = assigned_roles(root)
    fallbacks = scan_role_fallbacks(root, assigned)

    if not args.quiet:
        for name, drift, bad in bad_frames:
            print(f"\n{name}  说不出出处的颜色占 {drift:.2f}%")
            for hexv, pct, v in bad:
                print(f"    {hexv}  {pct:5.2f}%  {v}")

    print("\n" + "=" * 74)
    print(f"声明色 {len(declared)} 个（Color.kt / Accents.kt / design-spec.json 取并集）")
    print(f"派生色 {len(derived)} 个（声明色按 Material 海拔与状态层透明度叠出来的）")
    print(f"白名单 {len(accepted)} 条（design-palette-accepted.txt）")
    print(f"参考图 {len(refs)} 张，其中有说不出出处的 {len(bad_frames)} 张")
    if tally:
        print("\n说不出出处的颜色（按累计占比）：")
        for hexv, pct in sorted(tally.items(), key=lambda kv: -kv[1]):
            print(f"    {hexv}  {pct:7.2f}%  {verdict[hexv]}")

    print(f"\nColor.kt 赋过值的角色 {len(assigned)} 个")
    if fallbacks:
        print("用了但从没赋值的角色（必然回退 Material 基线）：")
        for role, n, first in fallbacks:
            print(f"    {role:26} {n:3} 处  首次 {first}")
    else:
        print("代码里用到的 colorScheme 角色全都赋过值。")

    if not bad_frames and not fallbacks:
        print("\nOK：每张参考图里占比超过 "
              f"{args.min_coverage}% 的颜色都说得出出处，也没有回退基线的角色。")
        return 0

    if bad_frames:
        worst = bad_frames[0][1]
        print(f"\n最差一张 {worst:.2f}%")
    if args.strict:
        print("FAIL：参考图里有这套色板说不出来源的颜色，或有用到却没赋值的角色。")
        return 1
    print("（未开 --strict，仅报告）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
