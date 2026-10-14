#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""从 Compose 代码里读出**权威 token 值**，供 design-spec 投影与漂移门禁共用。

## 为什么要有这个模块

`scaffold/design/design-spec.json` 的 token 段是**从代码投影出来的**（见
`check-design-palette.py` 头部的口径：规范与代码取并集，因为规范可能落后）。既然是
投影，就必须只有一个投影器 —— 否则"生成规范"和"检查规范"会各写一套正则、各有一套
理解，两边同时错的时候门禁是绿的。所以：

    design_contract.py        ← 唯一的代码侧读取器（本文件）
    design-spec-project.py    ← 用 --write 生成 / 用 --check 当门禁
    design-token-usage.py     ← 消费率棘轮（同一个读取器告诉它"有哪些 token"）

## 权威源判定（2026-10-09，逐键核对）

| 组 | 权威源 | 依据 |
| --- | --- | --- |
| 颜色 | `ui/theme/Color.kt` + `Accents.kt` | 已上线两端实际取值；已有像素级门禁 `check-design-palette.py` 在压 |
| 字阶 | `ui/theme/AppTheme.kt` 的 `RelayTypography` | 15 个角色已进 MaterialTheme，改它即改上线观感 |
| 间距 | `ui/theme/Spacing.kt` 的 `AppSpacing` | 代码 8 档 > 规范 6 档，且代码是 provide 给运行时的那一份 |
| 形 | `ui/theme/AppTheme.kt` 的 `RelayShapes` | 已进 MaterialTheme.shapes |
| 影 | `ui/theme/AppTheme.kt` 的 `RelayElevations` | 与规范三档已一致（1/3/6），且代码有具名常量 |

**spec 向代码收敛，禁止反向**：把规范里的旧值写回代码等于回退已上线品牌。

## 不是通用 Kotlin 解析器

只用正则读 `ui/theme/` 下这 4 个文件的**声明式**写法（`colorScheme(...)` 具名实参、
`Typography(...)` / `Shapes(...)` 构造、`data class AppSpacing` 默认值）。这些文件
的写法很窄，正则够用；一旦解析不到期望的键数就直接报错退出，不静默返回半份结果 ——
半份结果的失败方式是"门禁绿了但其实什么都没读到"。
"""

from __future__ import annotations

import os
import re
import sys
from typing import Dict, Iterable, List, Optional, Tuple

# ── 期望键数（解析不到就报错，防止"少读了一半还是绿的"）────────────
EXPECTED_SPACING = 17  # 2026-10-15 G2 裁决 A：奇数节奏档 oxs3/oxs5/osm7/osm9/olg11/olg13（11→17）
EXPECTED_SHAPE = 5
EXPECTED_ELEVATION = 3
EXPECTED_TYPOGRAPHY = 15
EXPECTED_COLORS_LIGHT_MIN = 20
EXPECTED_COLORS_DARK_MIN = 20
# G2 裁决 B/C 新增两组语义 token。缺文件时读数为空（老仓不受影响），但**存在却读漏
# 档位**必须报错——「少读一半还是绿的」是这套量具最贵的失败方向。
EXPECTED_SHAPE_ROLES = 3
EXPECTED_WEIGHTS = 1

WEIGHTS = {
    "FontWeight.Normal": "regular",
    "FontWeight.Medium": "medium",
    "FontWeight.SemiBold": "semibold",
    "FontWeight.Bold": "bold",
    "FontWeight.Light": "light",
}

REL_THEME = "composeApp/src/commonMain/kotlin/app/relay/ui/theme"
F_COLOR = f"{REL_THEME}/Color.kt"
F_ACCENTS = f"{REL_THEME}/Accents.kt"
F_THEME = f"{REL_THEME}/AppTheme.kt"
F_SPACING = f"{REL_THEME}/Spacing.kt"


class ContractError(RuntimeError):
    """解析结果不完整 —— 必须报错，不许降级成"读到的部分就算数"。"""


def _read(root: str, rel: str) -> List[str]:
    path = os.path.join(root, rel)
    if not os.path.exists(path):
        raise ContractError(f"缺少权威源文件：{rel}")
    with open(path, encoding="utf-8") as fh:
        return fh.read().splitlines()


def _strip(line: str) -> str:
    """去掉行尾注释再匹配 —— `//` 只有在**字符串字面量之外**才算注释起点。

    Color.kt 的品牌色行几乎都带行尾注释（`val RelayPrimary = Color(0xFF2D6A4F)  // 深茶绿`），
    不去注释就整批解析不到 —— 那会让"少读一半"变成静默的绿色门禁。

    但 `line.split("//")[0]` 太钝：`"https://x.example/a"` 里的 `//` 也会被当成注释起点，
    整行后半段被吃掉。对**欠债计数**与 **token 消费率**来说那是「少读」——欠债少报、
    消费率虚高，两个方向都是**假绿**，比多读危险。所以这里按字符串状态扫一遍。
    """
    i, in_str, quote = 0, False, ""
    while i < len(line):
        c = line[i]
        if in_str:
            if c == "\\":
                i += 2
                continue
            if c == quote:
                in_str = False
        elif c in "\"'":
            in_str, quote = True, c
        elif c == "/" and line[i:i + 2] == "//":
            return line[:i]
        i += 1
    return line


_BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)


def strip_comments(text: str) -> str:
    """整段源码去掉注释，**保住行号**（块注释按它占的行数换成空行）。

    为什么要按文件而不是按行剥：KDoc 的续行以 `*` 开头、身上没有 `//`，
    按行剥根本看不见它 —— 实测 `scaffold/.../AdaptiveContent.kt:38/75` 与
    `scaffold/.../desktop/CabinetPane.kt:135/139/244` 这 5 行 KDoc 里写的 `300.dp`
    被当成"间距欠债"，`CabinetPane.kt:135` 那句原文还是「改动前的形态是
    `Modifier.width(300.dp)` 写死」—— 记录一次**已经修好的**旧写法，反被算成一处新欠债。

    反方向更危险：注释里写的 `LocalAppSpacing.current.xl` 会被当成"真的用了这个 token"，
    直接抬高消费率。所以 `scan_usage` / `scan_debt` / `scan_shape_inventory` 都改走这里。
    """
    text = _BLOCK_COMMENT.sub(lambda m: "\n" * m.group(0).count("\n"), text)
    return "\n".join(_strip(l) for l in text.split("\n"))


def iter_code_lines(path: str):
    """逐行产出「去掉注释后的代码」，行号与原文件一致。"""
    with open(path, encoding="utf-8") as fh:
        text = fh.read()
    for i, line in enumerate(strip_comments(text).split("\n"), 1):
        yield i, line


def _hex_of(literal: str) -> Optional[str]:
    m = re.fullmatch(r"Color\(0x([0-9A-Fa-f]{8})\)", literal.strip())
    if not m:
        return None
    argb = m.group(1).upper()
    # 半透明色（alpha != FF）必须保 8 位：丢掉 alpha 会把 Color(0x12000000)
    # （7% 黑发丝线）投成 #000000（纯黑），门禁于是报一个代码里根本不存在的色值。
    if argb[:2] != "FF":
        return "#" + argb
    return "#" + argb[2:]


def _parse_color_kt(root: str) -> Tuple[Dict[str, Tuple[str, int]], Dict[str, Tuple[str, int]], Dict[str, str]]:
    """返回 (named_vals, schemes, notes)。

    named_vals: `val RelayPrimary = Color(0xFF2D6A4F)` → {"RelayPrimary": ("#2D6A4F", line)}
    schemes:    {"light": {role: "#..."}, "dark": {...}} —— 角色值已解析到具体色值
    """
    lines = _read(root, F_COLOR)
    named: Dict[str, Tuple[str, int]] = {}
    for i, line in enumerate(lines, 1):
        line = _strip(line)
        m = re.match(r"\s*val\s+(\w+)\s*=\s*(Color\(0x[0-9A-Fa-f]{8}\))\s*$", line)
        if m:
            named[m.group(1)] = (_hex_of(m.group(2)) or "", i)
        m2 = re.match(r"\s*val\s+(\w+)\s*=\s*Color\.White\s*$", line)
        if m2:
            named[m2.group(1)] = ("#FFFFFF", i)

    schemes: Dict[str, Dict[str, str]] = {"light": {}, "dark": {}}
    current: Optional[str] = None
    depth = 0
    for line in lines:
        m = re.search(r"val\s+Tidings(\w+?)Colors\s*=\s*(light|dark)ColorScheme\($", line)
        if m:
            current = "light" if m.group(2) == "light" else "dark"
            depth = 1
            continue
        if current is None:
            continue
        if depth:
            # 只认 depth==1 的具名实参，注释里的 key = value 不算
            code = line.split("//")[0]
            if code.strip().startswith(")"):
                current = None
                depth = 0
                continue
            for am in re.finditer(r"(\w+)\s*=\s*(Color\(0x[0-9A-Fa-f]{8}\)|Color\.White|\w+)\s*,?", code):
                role, raw = am.group(1), am.group(2).strip()
                if raw in ("Color",):
                    continue
                val = _hex_of(raw) or ("#FFFFFF" if raw == "Color.White" else named.get(raw, (None, 0))[0])
                if val:
                    schemes[current][role] = val
    return named, schemes, {"file": F_COLOR}


def _parse_accents(root: str, color_named: Dict[str, Tuple[str, int]]) -> Dict[str, Dict[str, str]]:
    """RelayLightAccents / RelayDarkAccents 的具名构造实参。

    Accents.kt 与 Color.kt 同包，`online = RelaySuccess` 指的是 Color.kt 里的顶层 val ——
    所以具名值必须从 Color.kt 那一侧传进来，不能只在本文件里找。
    """
    lines = _read(root, F_ACCENTS)
    named = {k: v[0] for k, v in
             ((m.group(1), (_hex_of(m.group(2)) or "", 0))
              for m in (re.match(r"\s*val\s+(\w+)\s*=\s*(Color\(0x[0-9A-Fa-f]{8}\))\s*$", _strip(l)) for l in lines)
              if m)}
    named = dict(color_named)
    named["Color.White"] = ("#FFFFFF", 0)
    out: Dict[str, Dict[str, str]] = {"light": {}, "dark": {}}
    current: Optional[str] = None
    for line in lines:
        m = re.search(r"val\s+Tidings(Light|Dark)Accents\s*=\s*RelayAccents\($", line)
        if m:
            current = "light" if m.group(1) == "Light" else "dark"
            continue
        if current is None:
            continue
        code = line.split("//")[0]
        if code.strip().startswith(")"):
            current = None
            continue
        for am in re.finditer(r"(\w+)\s*=\s*(Color\(0x[0-9A-Fa-f]{8}\)|Color\.White|\w+)\s*,?", code):
            field, raw = am.group(1), am.group(2).strip()
            if field == "RelayAccents":
                continue
            hit = named.get(raw)
            val = _hex_of(raw) or (hit[0] if hit else None)
            if val:
                out[current][field] = val
    return out


def _parse_typography(root: str) -> Dict[str, Dict[str, object]]:
    lines = _read(root, F_THEME)
    out: Dict[str, Dict[str, object]] = {}
    current: Optional[str] = None
    for line in lines:
        code = _strip(line)
        m = re.match(r"\s*(\w+)\s*=\s*TextStyle\($", code)
        if m:
            current = m.group(1)
            out[current] = {}
            continue
        if current is None:
            continue
        if code.strip().startswith(")"):
            current = None
            continue
        for key, pat in (
            ("sizeSp", r"fontSize\s*=\s*(-?[\d.]+)\.sp"),
            ("lineHeightSp", r"lineHeight\s*=\s*(-?[\d.]+)\.sp"),
            ("letterSpacingSp", r"letterSpacing\s*=\s*\(?(-?[\d.]+)\)?\.sp"),
        ):
            km = re.search(pat, code)
            if km:
                v = float(km.group(1))
                out[current][key] = int(v) if v == int(v) else v
        wm = re.search(r"fontWeight\s*=\s*(FontWeight\.\w+)", code)
        if wm:
            out[current]["weight"] = WEIGHTS.get(wm.group(1), wm.group(1))
    if len(out) != EXPECTED_TYPOGRAPHY:
        raise ContractError(
            f"RelayTypography 解析出 {len(out)} 个角色，期望 {EXPECTED_TYPOGRAPHY}：{sorted(out)}")
    for role, spec in out.items():
        missing = {"sizeSp", "weight", "lineHeightSp"} - set(spec)
        if missing:
            raise ContractError(f"RelayTypography.{role} 缺字段 {sorted(missing)}")
    return out


def _parse_spacing(root: str) -> Dict[str, int]:
    lines = _read(root, F_SPACING)
    out: Dict[str, int] = {}
    inside = False
    for line in lines:
        code = _strip(line)
        if re.search(r"data class AppSpacing\($", code):
            inside = True
            continue
        if inside:
            if code.strip().startswith(")"):
                break
            m = re.match(r"\s*val\s+(\w+)\s*:\s*Dp\s*=\s*([\d.]+)\.dp", code)
            if m:
                out[m.group(1)] = int(float(m.group(2)))
    if len(out) != EXPECTED_SPACING:
        raise ContractError(f"AppSpacing 解析出 {len(out)} 档，期望 {EXPECTED_SPACING}：{sorted(out)}")
    return out


def _parse_shapes(root: str) -> Dict[str, int]:
    lines = _read(root, F_THEME)
    out: Dict[str, int] = {}
    inside = False
    for line in lines:
        code = _strip(line)
        if re.search(r"val\s+RelayShapes\s*=\s*Shapes\($", code):
            inside = True
            continue
        if inside:
            if code.strip().startswith(")"):
                break
            m = re.match(r"\s*(\w+)\s*=\s*.*?RoundedCornerShape\(([\d.]+)\.dp\)", code)
            if m:
                out[m.group(1)] = int(float(m.group(2)))
    if len(out) != EXPECTED_SHAPE:
        raise ContractError(f"RelayShapes 解析出 {len(out)} 档，期望 {EXPECTED_SHAPE}：{sorted(out)}")
    return out


def _parse_elevation(root: str) -> Dict[str, int]:
    lines = _read(root, F_THEME)
    out: Dict[str, int] = {}
    inside = False
    for line in lines:
        code = _strip(line)
        if re.search(r"object\s+RelayElevations\s*\{", code):
            inside = True
            continue
        if inside:
            if code.strip().startswith("}"):
                break
            m = re.match(r"\s*val\s+(\w+)\s*:\s*Dp\s*=\s*([\d.]+)\.dp", code)
            if m:
                name = m.group(1)
                out[{"Card": "card", "Sheet": "sheet", "Floating": "floating"}.get(name, name.lower())] = int(float(m.group(2)))
    if len(out) != EXPECTED_ELEVATION:
        raise ContractError(f"RelayElevations 解析出 {len(out)} 档，期望 {EXPECTED_ELEVATION}：{sorted(out)}")
    return out


def _parse_object_dp(root: str, filename: str, obj: str) -> Dict[str, int]:
    """读 object <obj> 的 val X: Dp = N.dp 档位（ShapeRoles/TextWeights 的通用读法）。

    文件不存在返回空 dict —— 新 token 组不该让老产品仓读不动别的 token（IconSizes 先例）。
    键名统一降首字母，与 _parse_icon_sizes 的产物形态一致（下游按 token 名大小写不敏感）。"""
    rel = f"{REL_THEME}/{filename}"
    path = os.path.join(root, rel)
    if not os.path.exists(path):
        return {}
    out: Dict[str, int] = {}
    inside = False
    for line in _read(root, rel):
        code = _strip(line)
        if re.search(rf"object\s+{obj}\s*\{{", code):
            inside = True
            continue
        if inside:
            if code.strip().startswith("}"):
                break
            # 三种形态都收：Dp 标量 / RoundedCornerShape(N.dp) / FontWeight.Bold 这类枚举。
            # 上一版漏了 Shape 形态（ShapeRoles.kt 写的是 val X: Shape = RoundedCornerShape(9.dp)），
            # 读数静悄悄变空、守卫不响 —— 「存在却读漏档位」正是这套量具最贵的失败方向。
            m = re.match(
                r"\s*val\s+(\w+)\s*:\s*\w+\s*=\s*"
                r"(?:([\d.]+)\.dp|RoundedCornerShape\(\s*([\d.]+)\.dp\s*\)|FontWeight\.(\w+))", code)
            if m:
                name = m.group(1)[:1].lower() + m.group(1)[1:]
                if m.group(2) is not None:
                    out[name] = int(float(m.group(2)))
                elif m.group(3) is not None:
                    out[name] = int(float(m.group(3)))
                elif m.group(4) is not None:
                    out[name] = {"Normal": 400, "Medium": 500, "SemiBold": 600,
                                 "Bold": 700, "Light": 300}[m.group(4)]
    return out


def _parse_shape_roles(root: str) -> Dict[str, int]:
    return _parse_object_dp(root, "ShapeRoles.kt", "ShapeRoles")


def _parse_text_weights(root: str) -> Dict[str, int]:
    return _parse_object_dp(root, "TextWeights.kt", "TextWeights")


def _parse_icon_sizes(root: str) -> Dict[str, int]:
    """`object IconSizes` 的档位（图标尺寸 token 组）。文件不存在时返回空 dict —— 
    图标尺寸是 2026-10-10 新增的一组，老产品仓还没有它，不该因此读不动其它 token。"""
    rel = f"{REL_THEME}/IconSizes.kt"
    path = os.path.join(root, rel)
    if not os.path.exists(path):
        return {}
    out: Dict[str, int] = {}
    inside = False
    for line in open(path, encoding="utf-8"):
        code = _strip(line)
        if re.search(r"object\s+IconSizes\s*\{", code):
            inside = True
            continue
        if inside:
            if code.strip().startswith("}"):
                break
            m = re.match(r"\s*val\s+(\w+)\s*:\s*Dp\s*=\s*([\d.]+)\.dp", code)
            if m:
                out[m.group(1)[:1].lower() + m.group(1)[1:]] = int(float(m.group(2)))
    return out


def read_code_tokens(root: str) -> Dict[str, object]:
    """读一份完整的代码侧 token。任何一组不完整都抛 ContractError。"""
    color_named, schemes, _ = _parse_color_kt(root)
    accents = _parse_accents(root, color_named)
    if len(schemes["light"]) < EXPECTED_COLORS_LIGHT_MIN:
        raise ContractError(f"RelayLightColors 只解析出 {len(schemes['light'])} 个角色")
    if len(schemes["dark"]) < EXPECTED_COLORS_DARK_MIN:
        raise ContractError(f"RelayDarkColors 只解析出 {len(schemes['dark'])} 个角色")

    # 扩展 token（M3 ColorScheme 里没有的键）并进对应主题，成为规范的一部分
    for theme, fields in (("light", accents["light"]), ("dark", accents["dark"])):
        for k, v in fields.items():
            schemes[theme].setdefault(k, v)

    # 新语义 token 组的守卫：文件不存在 = 老仓，读数为空是合法状态；
    # 文件存在却读漏档位 = 量具坏了，必须报错（不许拿半份读数换一个绿灯）。
    shape_roles = _parse_shape_roles(root)
    weights = _parse_text_weights(root)
    if shape_roles and len(shape_roles) != EXPECTED_SHAPE_ROLES:
        raise ContractError(
            f"ShapeRoles 解析出 {len(shape_roles)} 档，期望 {EXPECTED_SHAPE_ROLES}：{sorted(shape_roles)}")
    if weights and len(weights) != EXPECTED_WEIGHTS:
        raise ContractError(
            f"TextWeights 解析出 {len(weights)} 档，期望 {EXPECTED_WEIGHTS}：{sorted(weights)}")

    return {
        "colors": {
            "light": dict(sorted(schemes["light"].items())),
            "dark": dict(sorted(schemes["dark"].items())),
        },
        # 哪些颜色键来自 MaterialTheme.accents（而不是 colorScheme）。消费点扫描按这份
        # 名单推导 accents.x 形态 —— 名单是数出来的，不是硬编码的（硬编码漏一个新
        # 字段时，该 token 会退化成 colorScheme.x 模式、永远 0 消费）。
        "accents": {
            "light": sorted(accents["light"]),
            "dark": sorted(accents["dark"]),
        },
        "typography": _parse_typography(root),
        "spacing": _parse_spacing(root),
        "shape": _parse_shapes(root),
        "elevation": _parse_elevation(root),
        "iconSizes": _parse_icon_sizes(root),
        "shapeRoles": shape_roles,
        "weights": weights,
        "sources": {
            "colors": [F_COLOR, F_ACCENTS],
            "typography": F_THEME,
            "spacing": F_SPACING,
            "shape": F_THEME,
            "elevation": F_THEME,
            "iconSizes": f"{REL_THEME}/IconSizes.kt",
            "shapeRoles": f"{REL_THEME}/ShapeRoles.kt",
            "weights": f"{REL_THEME}/TextWeights.kt",
        },
    }


def project_tokens(code: Dict[str, object], previous: Optional[Dict[str, object]] = None) -> Dict[str, object]:
    """把代码 token 投成规范里的 `tokens` 段。

    投影规则（有意为之，不是"照抄"）：

    - `colors` 保留原规范里那些**代码里没有的**键（`queued` 这类），因为规范同时承担
      "原型哪些是仅原型"的记录职责；代码有的键一律以代码为准。
    - light 与 dark **键集对齐**：原规范 light 缺 secondary/tertiary、dark 缺 bubbleMine
      之外的键，两边不对称本身就是漂移的一半。投影后两边都取代码真值。
    - `shape` 以 M3 档名（extraSmall..extraLarge）为准 —— 那是 `MaterialTheme.shapes` 的
      实际槽位名；语义别名（bubble/card/control/chip/sheet）放 `shapeSemantics`，两者
      都是契约的一部分，但**只有档名可被代码消费**。
    """
    prev = previous or {}
    prev_tokens = prev.get("tokens", {}) if isinstance(prev, dict) else {}
    prev_colors = prev_tokens.get("colors", {}) or {}
    prev_dark = prev_tokens.get("colorsDark", {}) or {}

    light = dict(code["colors"]["light"])  # type: ignore[index]
    dark = dict(code["colors"]["dark"])  # type: ignore[index]
    # dark 里 M3 没给值的角色（代码只写了 light 一侧的派生色）继承 light 的声明色键集
    for k, v in prev_colors.items():
        light.setdefault(k, v)
    for k, v in prev_dark.items():
        dark.setdefault(k, v)

    typography = {}
    for role, spec in sorted(code["typography"].items()):  # type: ignore[union-attr]
        typography[role] = {
            "sizeSp": spec.get("sizeSp"),
            "weight": spec.get("weight"),
            "lineHeightSp": spec.get("lineHeightSp"),
            "letterSpacingSp": spec.get("letterSpacingSp", 0),
        }

    shape_scale = dict(code["shape"])  # type: ignore[arg-type]
    return {
        "colors": dict(sorted(light.items())),
        "colorsDark": dict(sorted(dark.items())),
        "typography": typography,
        "spacing": dict(sorted(code["spacing"].items(), key=lambda kv: kv[1])),  # type: ignore[union-attr]
        "shape": dict(sorted(shape_scale.items())),
        "shapeSemantics": (prev_tokens.get("shapeSemantics") or {
            # 默认语义别名（原型用的是语义名，代码用的是 M3 档名，这里把两者钉在一起）
            "bubble": {"radiusDp": 18, "scaleSlot": None, "consumed": False},
            "card": {"radiusDp": 16, "scaleSlot": "large", "consumed": True},
            "control": {"radiusDp": 12, "scaleSlot": "medium", "consumed": True},
            "chip": {"radiusDp": 999, "scaleSlot": None, "consumed": False},
            "sheet": {"radiusDp": 28, "scaleSlot": "extraLarge", "consumed": True},
        }),
        "elevation": dict(code["elevation"]),  # type: ignore[arg-type]
        "_projectedFrom": code["sources"],
    }


def compare_tokens(spec_tokens: Dict[str, object], projected: Dict[str, object]) -> List[str]:
    """逐键 diff，返回人话形式的差异行（空 = 无漂移）。"""
    diffs: List[str] = []

    def walk(path: str, want, got) -> None:
        if isinstance(want, dict) and isinstance(got, dict):
            for k in sorted(set(want) | set(got)):
                if k not in want:
                    diffs.append(f"{path}.{k}: 规范缺（代码 = {got[k]!r}）")
                elif k not in got:
                    diffs.append(f"{path}.{k}: 代码缺（规范 = {want[k]!r}）")
                else:
                    walk(f"{path}.{k}", want[k], got[k])
        elif isinstance(want, list) and isinstance(got, list):
            if want != got:
                diffs.append(f"{path}: 列表不同（规范 = {want!r}，代码 = {got!r}）")
        elif want != got:
            diffs.append(f"{path}: 规范 {want!r} ≠ 代码 {got!r}")

    # 键名映射：规范用 colors/colorsDark，投影同形；其余同名
    for group in ("colors", "colorsDark", "typography", "spacing", "shape", "elevation"):
        walk(group, spec_tokens.get(group), projected.get(group))
    # shapeSemantics 只在规范里手写；校验它的 scaleSlot / radiusDp 与代码一致
    for name, sem in (spec_tokens.get("shapeSemantics") or {}).items():
        slot = sem.get("scaleSlot")
        if slot:
            code_r = (projected.get("shape") or {}).get(slot)
            if code_r is not None and code_r != sem.get("radiusDp"):
                diffs.append(
                    f"shapeSemantics.{name}.radiusDp: 规范 {sem.get('radiusDp')!r} ≠ 代码 {slot}={code_r!r}")
        elif sem.get("consumed"):
            diffs.append(f"shapeSemantics.{name}: 标了 consumed 却没有 scaleSlot（无法被代码消费）")
    return diffs


def _detect_root() -> str:
    """默认 root：从脚本位置向上找仓库根，而不是硬编一个曾经叫 scaffold 的目录。
    硬编的陈旧默认值的失败方式是「门禁 FAIL 但原因看着像代码坏了」——实测
    `python3 tools/design_contract.py --self-test` 报「缺少权威源文件」，
    而文件明明在：真相是仓库从 scaffold/ 挪到了根，默认路径没人跟着改。"""
    here = os.path.dirname(os.path.abspath(__file__))
    probe = os.path.join("composeApp", "src", "commonMain", "kotlin", "app", "relay", "ui", "theme", "Color.kt")
    d = here
    for _ in range(4):
        if os.path.exists(os.path.join(d, probe)):
            return d
        d = os.path.dirname(d)
    return "."


def main(argv: List[str]) -> int:
    root = argv[1] if len(argv) > 1 else _detect_root()
    try:
        code = read_code_tokens(root)
    except ContractError as exc:
        print(f"FAIL：读代码 token 失败 —— {exc}")
        return 1
    print(f"代码侧 token（权威源 {root}）")
    print(f"  colors    : light {len(code['colors']['light'])} / dark {len(code['colors']['dark'])}")
    print(f"  typography: {len(code['typography'])} 角色")
    print(f"  spacing   : {len(code['spacing'])} 档 {list(code['spacing'])}")
    print(f"  shape     : {len(code['shape'])} 档 {code['shape']}")
    print(f"  elevation : {code['elevation']}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))


# ── 消费扫描：token 到底有没有被用上 ────────────────────────────────
# 「用了某产物」不能用文件存在计数 —— 这里的判据是**证据行必须真的引用了该 token**。
# 一条都不引用的 token 就是 pending（欠债），它进未实现分母，不进完成分子。

SCAN_ROOTS = [
    "composeApp/src/commonMain",
    "composeApp/src/androidMain",
    "composeApp/src/iosMain",
    "composeApp/src/jvmMain",
    "desktopApp/src/main",
]
# 主题目录本身是权威源，不算消费点；测试与截图夹具不是产品观感
SCAN_EXCLUDE = ("/build/", "screenshotTest", "Test.kt", "PreviewState.kt", "/test/", "/androidTest/")
THEME_INFIX = "ui/theme/"

# 原始字面量（欠债）模式：按组统计，棘轮只许降不许升。
#
# **`spacing` 只数「真间距调用实参里的 .dp」。** 早先它的模式是裸 `[\d.]+\.dp`，
# 于是 `.size(20.dp)`（尺寸）、`.border(2.dp)`（描边）、`RoundedCornerShape(12.dp)`（圆角）
# 全被记成"间距欠债" —— 实测 721 行里真间距只有 327 行，另外 237 行是尺寸、73 行是圆角、
# 8 行是**注释**。更坏的是圆角同时被 `shape` 与 `spacing` 各记一次 = 同一处欠债记两笔，
# 分母虚高，于是"间距迁移"永远看不到进度。尺寸另立 `dimension` 组。
DEBT_PATTERNS = {
    "shape": r"RoundedCornerShape\(\s*[\d.]+\.dp\s*\)",
    "typography": r"fontSize\s*=\s*[\d.]+\.sp",
    "color": r"Color\(0x",
    # **第四十八个洞（2026-10-12）：`elevation` 这组此前量的是反的。**
    # 旧模式 `defaultElevation\s*=\s*[\d.]+\.dp|\.shadow\(|CardDefaults\.cardElevation` 的
    # 第三个分支是**裸** `CardDefaults\.cardElevation`，于是：
    #   · **正确用法被记成欠债** —— `CardDefaults.cardElevation(defaultElevation = RelayElevations.Card)`
    #     里的 `defaultElevation` 并不跟字面量，命中它的只有 `CardDefaults.cardElevation` 这个
    #     函数名本身。全仓 8 处 `cardElevation` 调用**全部**是这种正确写法（实测
    #     `grep -rnE "cardElevation\s*\(\s*[0-9.]+\.dp|cardElevation\s*\(\s*\)"` 零命中），
    #     却 8 处全被记为欠债 —— 于是「多采用一处 token」会让欠债**上涨**，棘轮方向被写反。
    #   · **真正的字面量一个都没数到** —— `tonalElevation = 6.dp` / `shadowElevation = 2.dp`
    #     这类（`Surface` 上的高度字面量）在旧模式下零命中；它们才是「绕开 RelayElevations
    #     的写法」。基线记的 7 是**旧模式下的 7 处 `cardElevation` 调用**，与"高度欠债"无关。
    # 新模式的三个分支分别对应：显式 defaultElevation 字面量 / 旧式 `.shadow(` /
    # Surface 的 shadowElevation·tonalElevation 字面量 / 无参 `cardElevation()`（吃 M3 默认值）。
    #
    # **第五十三个洞（2026-10-14）：`…Elevation = 0.dp` 又被记成欠债，仍与第四十八个洞同族。**
    # 那个 `(?!0(?:\.0+)?\.dp\b)` 前视不是风格问题，它是这组唯一的方向护栏：
    #   · `0.dp` **不是**一个高度值，它是一句指令 —— 「不要 M3 平台阴影」。`ui/theme/CardShadow.kt`
    #     的 KDoc 明文要求页面级白卡走 `relayCardShadow()` 自绘投影**并把 `elevation` 归零**，
    #     否则 M3 的 1dp 阴影会叠在自绘阴影上。实测全仓 15 处 `defaultElevation = 0.dp`
    #     （`SectionCard` / `CabinetScreen` / `AgentApprovalCards` …）**逐条都紧挨着一个
    #     `relayCardShadow(` 调用**，即它们全是照 KDoc 写的正确代码。
    #   · 旧模式把 0.dp 算成欠债 ⇒ **照 KDoc 写正确代码会让欠债上涨**。棘轮方向第二次被写反，
    #     所以这一次不靠人工识别，直接把 0 从两条 `…Elevation = N.dp` 分支里排掉，
    #     并在已知答案夹具里钉住（`_DEBT_FIXTURE` 的 `b()`）。
    #   · 反向约束：非 0 的字面量仍必须报（`shadowElevation = 2.dp` / `tonalElevation = 6.dp`
    #     在夹具里照旧算欠债），`cardElevation()` 无参仍必须报 —— 只放行 0，不放行整组。
    "elevation": (
        r"defaultElevation\s*=\s*(?!0(?:\.0+)?\.dp\b)[\d.]+\.dp"
        r"|\.shadow\("
        r"|(?:shadowElevation|tonalElevation)\s*=\s*(?!0(?:\.0+)?\.dp\b)[\d.]+\.dp"
        r"|cardElevation\s*\(\s*\)"
    ),
    # 图标本体尺寸（Icon/Image 紧跟 Modifier.size(N.dp)）。**不数 IconButton 的容器尺寸**——
    # 那是点击热区不是图标尺寸（CaptureOverlayView 的 36dp 容器就是先例）。
    # 单列一组的原因：它此前被 spacing 吞掉，"图标尺寸消费率"根本量不出来（goal ③）。
    #
    # **第四十九个洞（2026-10-12）：旧模式 `(?:Icon|Image)\s*\(.*?Modifier\.size\(…\)` 只认单行。**
    # `.` 不匹配换行 ⇒ 只要 `Modifier.size(...)` 写在 `Icon(` 的下一行（Kotlin 里这是**主流写法**，
    # 因为格式化器会把长参数列表拆行），这一处就一处都不数。实测同一棵树：
    # 旧模式 **7** 处、新模式 **55** 处 —— 即 87% 的图标尺寸欠债对棘轮**完全不可见**，
    # 而 `iconSizes` 组的棘轮（基线 7）恰好建在这 7 处上 ⇒ 「图标尺寸消费率」这个指标
    # 一直是拿 13% 的样本算的。这与第四十八个洞同族：**量具只看得见自己看得见的形态**。
    # 新模式用「括号内不含嵌套括号」的展开式跨行匹配，并对 `(…)` 里的一层嵌套放宽；
    # 实测零过度合并（没有任何一次匹配横跨两个 `Icon(` 或两个 `Modifier.size(`）。
    "iconSizes": (
        r"(?s)(?:Icon|Image)\s*\((?:[^()]|\([^()]*\))*?"
        r"Modifier\.size\(\s*[\d.]+\.dp\s*\)"
    ),
    # 下面两组是「调用实参里的字面量」，由 `scan_debt` 用配平括号单独数（见 `_CALL_GROUPS`），
    # 这里的值只是占位，好让 `scan_debt` 的返回键集合稳定。
    "dimension": None,
    "spacing": None,
}

# 真间距调用的位置（实参里的 .dp 是间距欠债）
_SPACING_CALLS = re.compile(
    r"\.padding(?:Start|End|Top|Bottom|Horizontal|Vertical)?\s*\(|"
    r"\bPaddingValues\s*\(|\bspacedBy\s*\(|Arrangement\.\w+\s*\(|\.offset\s*\(|\bSpacer\s*\("
)
# 尺寸/描边调用的位置（实参里的 .dp 是尺寸欠债，**不是**间距）
_DIMENSION_CALLS = re.compile(
    r"\.(?:size|width|height|sizeIn|requiredSize|requiredWidth|requiredHeight|"
    r"defaultMinSize|widthIn|heightIn|border|minHeight|minWidth)\s*\("
)
_STROKE_WIDTH = re.compile(r"\bstrokeWidth\s*=\s*[\d.]+\.dp")
_DIM_LITERAL = re.compile(r"(?<![\w.])\d+(?:\.\d+)?\.dp")
# 一次调用的实参最长看多少字符；超了就判定为"括号不配平"，放弃这一次（宁可漏也不虚高）
_MAX_ARGS = 2000
# 这两组的模式是「调用」不是「字面量」，scan_debt 走配平括号分支
_CALL_GROUPS = ("dimension", "spacing")

_ACCENT_TOKENS = ("agentAccent", "agentAccentContainer", "online")
_ELEVATION_NAME = {"card": "Card", "sheet": "Sheet", "floating": "Floating"}


def token_reference_pattern(
    group: str,
    name: str,
    slot: Optional[str] = None,
    accent_names: Optional[Iterable[str]] = None,
) -> str:
    """token → 「被消费时长什么样」的正则片段。

    刻意从 token 名**推导**而不是让调用方手填 pattern：手填的 pattern 可以自证清白。
    """
    if group == "colors":
        # accent_names 由 read_code_tokens 的 "accents" 给出；_ACCENT_TOKENS 只作兜底
        # （老调用方不传时行为不变）。
        accented = set(_ACCENT_TOKENS) | set(accent_names or ())
        return (rf"accents\.{name}\b" if name in accented else rf"colorScheme\.{name}\b")
    if group == "typography":
        return rf"typography\.{name}\b"
    if group == "spacing":
        return rf"(?:LocalAppSpacing\.current|MaterialTheme\.spacing)\.{name}\b"
    if group == "shape":
        target = slot or name
        return rf"shapes\.{target}\b"
    if group == "elevation":
        return rf"RelayElevations\.{_ELEVATION_NAME.get(name, name.capitalize())}\b"
    if group == "iconSizes":
        return rf"IconSizes\.{name[:1].upper() + name[1:]}\b"
    if group == "shapeRoles":
        return rf"ShapeRoles\.{name[:1].upper() + name[1:]}\b"
    if group == "weights":
        return rf"TextWeights\.{name[:1].upper() + name[1:]}\b"
    raise ContractError(f"未知 token 组：{group}")


def scan_base(root: str, rel: str) -> str:
    """把「带 scaffold/ 前缀」的相对路径接到 root 上，避免 scaffold/scaffold 双前缀。"""
    if rel.startswith("scaffold/"):
        rel = rel[len("scaffold/"):]
    return os.path.join(root, rel)


def iter_source_files(root: str, include_theme: bool = False):
    for rel in SCAN_ROOTS:
        base = scan_base(root, rel)
        if not os.path.isdir(base):
            continue
        for dirpath, dirnames, filenames in os.walk(base):
            if "/build/" in dirpath + "/" or dirpath.endswith("/build"):
                continue
            for fn in sorted(filenames):
                if not fn.endswith(".kt"):
                    continue
                path = os.path.join(dirpath, fn)
                if any(x in path for x in SCAN_EXCLUDE):
                    continue
                if not include_theme and THEME_INFIX in path:
                    continue
                yield path


def scan_usage(root: str, code: Optional[Dict[str, object]] = None) -> Dict[str, object]:
    """每个 token 的真实消费点。返回 {group: {name: {sites, first, source}}}。"""
    code = code or read_code_tokens(root)
    accents_meta = code.get("accents")
    accent_names: Iterable[str] = (
        set(accents_meta.get("light", [])) | set(accents_meta.get("dark", []))
        if isinstance(accents_meta, dict) else ()
    )
    files = list(iter_source_files(root))
    cache: Dict[str, List[str]] = {}
    for path in files:
        with open(path, encoding="utf-8") as fh:
            # 先剥注释：注释里写的 `LocalAppSpacing.current.xl` 会被当成"真的用了这个 token"，
            # 直接抬高消费率 —— 那是假绿，比漏报危险。
            cache[path] = strip_comments(fh.read()).splitlines()

    def sites_for(pattern: str) -> Tuple[int, Optional[str]]:
        rx = re.compile(pattern)
        hits: List[str] = []
        for path, lines in cache.items():
            for i, line in enumerate(lines, 1):
                if rx.search(_strip(line)):
                    rel = os.path.relpath(path, root)
                    hits.append(f"{rel}:{i}")
        return len(hits), (hits[0] if hits else None)

    out: Dict[str, object] = {}
    for name in code["colors"]["light"]:  # type: ignore[union-attr]
        out.setdefault("colors", {})[name] = _mk(
            *sites_for(token_reference_pattern("colors", name, accent_names=accent_names))
        )
    for name in code["typography"]:  # type: ignore[union-attr]
        out.setdefault("typography", {})[name] = _mk(*sites_for(token_reference_pattern("typography", name)))
    for name in code["spacing"]:  # type: ignore[union-attr]
        out.setdefault("spacing", {})[name] = _mk(*sites_for(token_reference_pattern("spacing", name)))
    for name in code["shape"]:  # type: ignore[union-attr]
        out.setdefault("shape", {})[name] = _mk(*sites_for(token_reference_pattern("shape", name)))
    for name in code["elevation"]:  # type: ignore[union-attr]
        out.setdefault("elevation", {})[name] = _mk(*sites_for(token_reference_pattern("elevation", name)))
    for name in (code.get("iconSizes") or {}):  # type: ignore[union-attr]
        out.setdefault("iconSizes", {})[name] = _mk(*sites_for(token_reference_pattern("iconSizes", name)))
    for name in (code.get("shapeRoles") or {}):  # type: ignore[union-attr]
        out.setdefault("shapeRoles", {})[name] = _mk(*sites_for(token_reference_pattern("shapeRoles", name)))
    for name in (code.get("weights") or {}):  # type: ignore[union-attr]
        out.setdefault("weights", {})[name] = _mk(*sites_for(token_reference_pattern("weights", name)))
    return out


def _mk(sites: int, first: Optional[str]) -> Dict[str, object]:
    return {"sites": sites, "first": first}


def _balanced_args(text: str, open_idx: int) -> Tuple[str, int]:
    """从 `(` 起取配平的实参文本，返回 (文本, 起点偏移)。

    找不到收口就**放弃**（返回空串）而不是一路吃到文末：源码里 `(` 与 `)` 不等是常事
    （字符串字面量里带括号，实测 `AndroidFilePicker.kt` 剥注释后 81 比 79），
    一路吃下去会把后面所有语句的字面量都算进这一次调用的实参 —— 那是虚高。
    """
    depth = 0
    limit = min(len(text), open_idx + _MAX_ARGS)
    for j in range(open_idx, limit):
        c = text[j]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return text[open_idx:j + 1], open_idx
    return "", open_idx


def _count_literals_in_calls(text: str, call_rx: "re.Pattern", skip_spans=()) -> int:
    """数「调用实参里的 `.dp` 字面量」有多少**处**。"""
    n = 0
    for m in call_rx.finditer(text):
        args, base = _balanced_args(text, m.end() - 1)
        for lit in _DIM_LITERAL.finditer(args):
            off = base + lit.start()
            if any(a <= off < b for a, b in skip_spans):
                continue
            n += 1
    return n


def scan_debt(root: str) -> Dict[str, int]:
    """原始字面量欠债计数（棘轮的分母）。

    **单位是「处」，不是「行」。** 早先按行数（`rx.search(line)` 命中就 +1），两个后果：
    ① 同一行两处字面量只记一笔；② 跨行实参（`padding(\\n  start = 14.dp,\\n)`）整批漏掉。
    后者是「少读」= 假绿方向，所以间距/尺寸两组改成按配平括号数实参里的字面量。
    注释在量之前就被 `strip_comments` 剥掉了 —— 注释里写的 `300.dp` 不是欠债。
    """
    counts = {k: 0 for k in DEBT_PATTERNS}
    compiled = {k: re.compile(v) for k, v in DEBT_PATTERNS.items()
                if v is not None and k not in _CALL_GROUPS}
    for path in iter_source_files(root):
        with open(path, encoding="utf-8") as fh:
            text = strip_comments(fh.read())
        for k, rx in compiled.items():
            counts[k] += len(rx.findall(text))
        # 图标本体尺寸先量出来，它的字面量要从 dimension 里剔掉（否则同一处记两笔）
        icon_spans = [m.span() for m in compiled["iconSizes"].finditer(text)]
        counts["spacing"] += _count_literals_in_calls(text, _SPACING_CALLS)
        counts["dimension"] += _count_literals_in_calls(text, _DIMENSION_CALLS, icon_spans)
        counts["dimension"] += len(_STROKE_WIDTH.findall(text))
    return counts


# ── 组件级槽位扫描：某个组件文件到底消费了哪些 token ──────────────
# (group, pattern, fixed_name)：fixed_name 非 None 时忽略捕获组、直接按该名记账。
# 这一档是给「语义 local 而不是槽位名」的证据用的：`LocalCardShape.current` 渲染的是
# `RelayShapes.large`（手机 16dp），桌面壳把它覆盖成 `RelayShapes.medium`（12dp）——
# 「卡片圆角」在代码里根本没有字面量可匹配，只能靠这条固定名挂上去。
_COMPONENT_PROBES = (
    ("colors", r"colorScheme\.(\w+)\b", None),
    ("colors", r"accents\.(\w+)\b", None),
    ("typography", r"typography\.(\w+)\b", None),
    ("spacing", r"(?:LocalAppSpacing\.current|MaterialTheme\.spacing)\.(\w+)\b", None),
    ("shape", r"shapes\.(\w+)\b", None),
    ("shape", r"LocalCardShape\.current\b", "large"),
    ("elevation", r"RelayElevations\.(\w+)\b", None),
)


def scan_file_slots(root: str, rel_paths: List[str]) -> Dict[str, object]:
    """逐文件列出真实消费的 token（带证据行）。返回 {relpath: {group: {name: [line,...]}}}。"""
    out: Dict[str, object] = {}
    for rel in rel_paths:
        path = os.path.join(root, rel)
        if not os.path.exists(path):
            raise ContractError(f"componentSpecs 引用的文件不存在：{rel}")
        groups: Dict[str, Dict[str, List[int]]] = {}
        for i, line in enumerate(open(path, encoding="utf-8"), 1):
            code = _strip(line)
            for group, pat, fixed in _COMPONENT_PROBES:
                for m in re.finditer(pat, code):
                    name = fixed if fixed else m.group(1)
                    if group == "elevation":   # 代码写 RelayElevations.Card，token 名是 card
                        name = name[:1].lower() + name[1:]
                    groups.setdefault(group, {}).setdefault(name, []).append(i)
        out[rel] = groups
    return out


# ── 状态槽证据：截图预览函数名就是"这个状态被渲染过"的证据 ──────────
PREVIEW_GLOBS = (
    "androidApp/src/screenshotTest/kotlin",
    "androidApp/src/screenshotTestDebug/kotlin",
)


def scan_preview_functions(root: str) -> List[str]:
    names: List[str] = []
    for rel in PREVIEW_GLOBS:
        base = scan_base(root, rel)
        if not os.path.isdir(base):
            continue
        for fn in sorted(os.listdir(base)):
            if not fn.endswith(".kt"):
                continue
            with open(os.path.join(base, fn), encoding="utf-8") as fh:
                for line in fh:
                    m = re.search(r"fun\s+(\w+)\s*\(\s*\)", line)
                    if m and m.group(1) not in names:
                        names.append(m.group(1))
    if not names:
        raise ContractError("一条 @Preview 函数名都没扫到 —— 状态槽报告会变成空表，宁可报错")
    return names


def scan_render_targets(root: str, mapping: Dict[str, str]) -> Dict[str, str]:
    """每个预览函数**实际渲染的屏**：扫函数体里调用了哪个屏级 Composable。

    为什么需要它（2026-10-10 实测）
    ------------------------------
    预览函数名**会撒谎**。实测 139 个预览里 58 个能机械判定唯一渲染目标，其中 4 个
    名字认领的屏 ≠ 实际渲染的屏，全部是 `files` 抢 `chat` 的帧：

        ChatFilesPhonePreview / ChatFolderPhonePreview /
        ChatTransferFailedPhonePreview / ChatTransferResumePhonePreview   → 体内都是 ChatScreen

    `files` 声明 `ChatFiles`/`ChatFolder`/`ChatTransfer` 是**作者故意的别名**（这些帧渲染的
    聊天界面里带文件卡），而 `settings` ← `SettingsMore*` 是**意外嵌套**。两者在名字前缀
    上长得一模一样 —— 只有渲染目标能区分。

    判据与边界：
      · 函数体 = 从函数签名的 `{` 起做花括号配对（正则数不了嵌套，但配对是精确的）；
      · 含 `DesktopShell(` 的帧直接跳过并返回"判不出"：桌面壳一壳多视图，
        `Desk(filesView = true)` 与 `Desk()` 是同一个 Composable，渲染目标不是屏的信号；
      · 命中 **0 个或多个**屏级 Composable 都算判不出（返回 None，调用方回落到名字前缀）——
        宁可回落，也不许拿"疑似"当归属。

    返回 {预览函数名: 屏 id}，只含判得出的那些。
    """
    by_composable = {}
    for screen, composable in (mapping or {}).items():
        by_composable.setdefault(composable, []).append(screen)
    if not by_composable:
        return {}
    rx_fun = re.compile(r"fun\s+(\w+)\s*\(\s*\)")
    rx_call = re.compile(r"\b(" + "|".join(re.escape(c) for c in by_composable) + r")\s*\(")
    out: Dict[str, str] = {}
    for rel in PREVIEW_GLOBS:
        base = scan_base(root, rel)
        if not os.path.isdir(base):
            continue
        for fn in sorted(os.listdir(base)):
            if not fn.endswith(".kt"):
                continue
            with open(os.path.join(base, fn), encoding="utf-8") as fh:
                src = fh.read()
            for m in rx_fun.finditer(src):
                open_brace = src.find("{", m.end())
                if open_brace < 0:
                    continue
                depth = 0
                i = open_brace
                while i < len(src):
                    if src[i] == "{":
                        depth += 1
                    elif src[i] == "}":
                        depth -= 1
                        if depth == 0:
                            break
                    i += 1
                body = src[open_brace:i + 1]
                if "DesktopShell(" in body:
                    continue
                screens = set()
                for c in rx_call.findall(body):
                    screens.update(by_composable[c])
                if len(screens) == 1:
                    out[m.group(1)] = screens.pop()
    return out


def scan_shape_inventory(root: str) -> Dict[str, object]:
    """圆角实况清单：原始 RoundedCornerShape(N.dp) 分布、CircleShape 处数、具名参数半径。"""
    raw: Dict[str, int] = {}
    named: Dict[str, int] = {}
    circle = 0
    for path in iter_source_files(root):
        for line in open(path, encoding="utf-8"):
            code = _strip(line)
            if "CircleShape" in code:
                circle += 1
            if "RoundedCornerShape(" not in code:
                continue
            for m in re.finditer(r"RoundedCornerShape\(\s*([\d.]+)\.dp\s*\)", code):
                raw[m.group(1)] = raw.get(m.group(1), 0) + 1
            if re.search(r"RoundedCornerShape\([^)]*?(topStart|topEnd|bottomEnd|bottomStart)\s*=", code):
                for m in re.finditer(r"([\d.]+)\.dp", code):
                    named[m.group(1)] = named.get(m.group(1), 0) + 1
    return {"raw": raw, "named": named, "circleShapeSites": circle}
