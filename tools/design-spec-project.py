#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""design-spec.json 投影器 + spec↔code 漂移门禁（供 CI 与本地门禁共用）。

为什么要这个工具
----------------
`scaffold/design/design-spec.json` 过去是**手写的设计侧文档**，于是问题不是"没有 JSON"，
而是"JSON 里的数和代码里的数可以长期不一致，而且没人会因此报红"：颜色有像素门禁压着所以
一直精致，间距/字阶/圆角没有门禁，spec 里写着 6 档间距、代码里有 8 档、消费点是 0 处，
三方各说各话。这个工具把 spec 拆成两半：

  · **投影段（tokens / usage）**：由 `design_contract.py` 从代码里读出来，`--write`
    生成、`--check` 校验。生成物天然不会和代码漂移，除非有人手改或改了代码没重跑。
  · **书写段（componentSpecs / stateSlots / gates / colorNotes / prototypeOnly /
    shapeSemantics / pendingNotes）**：人和设计写意图，工具只校验"引用完整性"和
    "意图与实测是否自洽"（如声明的槽位到底有没有消费点），不代它编数。

权威源纪律：**代码是唯一权威源，spec 向代码收敛，禁止反向覆盖**。`--write` 只会把代码的
值写进 spec；如果希望 spec 的值生效，必须先改代码（见 shapeSemantics.bubble 的 note）。

用法
----
    python3 scaffold/tools/design-spec-project.py --check    # 门禁：漂移或引用不完整即退出码 1
    python3 scaffold/tools/design-spec-project.py --write    # 重新投影（改完代码后跑）
    python3 scaffold/tools/design-spec-project.py --check -v # 逐条列出差异
"""
import argparse
import json
import os
import re
import sys
from typing import List

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import design_contract as dc  # noqa: E402

SPEC_REL = "design/design-spec.json"  # 相对 --root（默认 scaffold）
TOKEN_GROUPS = ("colors", "colorsDark", "typography", "spacing", "shape", "elevation", "iconSizes")
# 书写段的合法 status / mode，写错立刻报错而不是静默忽略
SHAPE_MODES = ("slot", "raw", "circle", "m3-internal")
REQUIRED_GATES = {
    "designSpecDrift": "spec 与代码逐键漂移（本工具 --check）",
    "designTokenUsage": "token 消费率棘轮（只降不升）",
    "designStateSlotReport": "状态槽覆盖报告",
}


class GateIssue(Exception):
    pass


# ── 读写 ────────────────────────────────────────────────────────────
def spec_path(root):
    return os.path.join(root, SPEC_REL)


def load_spec(root):
    with open(spec_path(root), encoding="utf-8") as fh:
        return json.load(fh)


def dump_spec(spec):
    # 与仓库里既有的字节形态一致（indent=2 / 非 ASCII 不转义 / 结尾换行），
    # 这样"没有实质变化"时 --write 是零 diff，可以直接当门禁用。
    return json.dumps(spec, indent=2, ensure_ascii=False) + "\n"


# ── 投影：代码 → spec ───────────────────────────────────────────────
def project_tokens(code, previous):
    """投影 tokens 段。previous 只用来保留书写段的键（不参与取值）。"""
    tokens = {
        "colors": dict(sorted(code["colors"]["light"].items())),
        "colorsDark": dict(sorted(code["colors"]["dark"].items())),
        "colorNotes": dict(previous.get("colorNotes") or {}),
        "prototypeOnly": dict(previous.get("prototypeOnly") or {}),
        "typography": {k: dict(v) for k, v in code["typography"].items()},
        "spacing": dict(code["spacing"]),
        "shape": dict(code["shape"]),
        "shapeSemantics": dict(previous.get("shapeSemantics") or {}),
        "elevation": dict(code["elevation"]),
        "iconSizes": dict(code.get("iconSizes") or {}),
        "pendingNotes": dict(previous.get("pendingNotes") or {}),
    }
    # 原 20 键核心调色板单独留痕：投影后 colors 是代码全集，但"设计批准过哪些"仍要能查。
    notes = tokens["colorNotes"]
    # 这句 note 每次重投影都刷新（键数**数出来**，写死的那个数每加一个 token 就变成假话：
    # 37 → 41 已经发生过一次）；roles 是书写段，保留既有值。
    palette_keys = len(code["colors"]["light"])
    palette = dict(notes.get("palette") or {})
    palette["note"] = (
        f"设计侧批准过的核心调色板角色。tokens.colors 现在是代码全量投影（{palette_keys} 键/主题），"
        "这个列表保留「哪些是设计明确批准过的」这一信息，不参与漂移比对。"
    )
    palette.setdefault("roles", ["primary", "onPrimary", "primaryContainer", "onPrimaryContainer",
                  "secondary", "secondaryContainer", "tertiary", "error", "errorContainer",
                  "background", "surface", "surfaceVariant", "onSurface", "onSurfaceVariant",
                  "outline", "outlineVariant", "inverseSurface", "inverseOnSurface", "scrim", "accent"])
    notes["palette"] = palette
    return tokens


def project_usage(root, code):
    """投影 usage 段：把"哪些 token 真的被消费、欠了多少字面量"变成可门禁的数。"""
    usage = dc.scan_usage(root, code)
    inv = dc.scan_shape_inventory(root)
    scale = dict(code["shape"])
    groups = {}
    for group, entries in usage.items():
        groups[group] = {}
        for name, info in sorted(entries.items()):
            entry = {"sites": info["sites"], "status": "enforced" if info["sites"] else "pending"}
            if info.get("first"):
                entry["first"] = info["first"]
            groups[group][name] = entry
    off_scale = {r: n for r, n in sorted(inv["raw"].items(), key=lambda kv: -kv[1])
                 if float(r) not in set(scale.values())}
    return {
        "note": "本段是投影，不是手写：sites 只数**真的引用了该 token** 的代码行（正则由 token 名推导）；"
                "status=enforced 表示有真实消费点，pending 表示 0 处消费、必须在 tokens.pendingNotes 里写明原因。",
        "groups": groups,
        "adoption": {g: f"{sum(1 for e in v.values() if e['status'] == 'enforced')}/{len(v)}"
                     for g, v in groups.items()},
        "shapeInventory": {
            "rawRoundedCornerShape": inv["raw"],
            "namedCornerRadii": inv["named"],
            "circleShapeSites": inv["circleShapeSites"],
            "offScale": off_scale,
            "note": "offScale = 没有任何 M3 槽位能表达的半径，这些点只能靠字面量写出来，"
                    "是「看着不精致」最直接的一类证据。",
        },
        "debt": dc.scan_debt(root),
    }


def build_projection(root, spec):
    code = dc.read_code_tokens(root)
    tokens = project_tokens(code, spec.get("tokens") or {})
    usage = project_usage(root, code)
    usage["shapeSemantics"] = {
        name: {
            "radiusDp": entry.get("radiusDp"),
            "scaleSlot": entry.get("scaleSlot"),
            "mode": entry.get("mode"),
            "rawSites": usage["shapeInventory"]["rawRoundedCornerShape"].get(str(entry.get("radiusDp")), 0),
            "slotSites": (usage["groups"].get("shape", {}).get(entry.get("scaleSlot")) or {}).get("sites", 0)
            if entry.get("scaleSlot") else 0,
            "circleShapeSites": usage["shapeInventory"]["circleShapeSites"] if entry.get("mode") == "circle" else 0,
        }
        for name, entry in (tokens.get("shapeSemantics") or {}).items()
    }
    tokens["usage"] = usage
    return code, tokens, usage


# ── 深比较 ─────────────────────────────────────────────────────────
# 易变字段：行号 / 计数这类"代码一改就变、契约含义没变"的值。它们必须**不阻塞门禁**——
# 否则任何一次无关编辑（甚至别人在同一工作树里改代码）都会把漂移门禁刷红，门禁退化成噪声。
# 契约只对"哪些 token 被采用"负责；易变字段由 --write 顺手刷新。
VOLATILE_SUFFIXES = (".sites", ".first", ".circleShapeSites")
VOLATILE_PREFIXES = ("spec.tokens.usage.shapeInventory",)


def _volatile(path):
    return path.startswith(VOLATILE_PREFIXES) or path.endswith(VOLATILE_SUFFIXES)


def deep_diff(committed, projected, path="", out=None, skipped=None):
    """逐键报告差异，路径可 grep。易变字段记进 skipped，不参与判定。"""
    if out is None:
        out = []
    if path and _volatile(path):
        if skipped is not None:
            skipped.append(path)
        return out
    if type(committed) is not type(projected) and not (
            isinstance(committed, (int, float)) and isinstance(projected, (int, float))):
        out.append(f"{path}: 类型不同 提交={type(committed).__name__} 投影={type(projected).__name__}")
        return out
    if isinstance(committed, dict):
        for key in sorted(set(committed) | set(projected)):
            if key not in committed:
                out.append(f"{path}.{key}: 提交里缺失（代码里有）={projected[key]!r}")
            elif key not in projected:
                out.append(f"{path}.{key}: 提交里有但代码里没有（投影会删掉）={committed[key]!r}")
            else:
                deep_diff(committed[key], projected[key], f"{path}.{key}", out, skipped)
    elif committed != projected:
        out.append(f"{path}: 提交={committed!r} 代码={projected!r}")
    return out


# ── 书写段校验 ──────────────────────────────────────────────────────
def validate(spec, code, usage, previews, root):
    issues = []
    tokens = spec.get("tokens") or {}

    # 1) 每个 pending token 必须有原因（"欠债要写清为什么"，不许无声存在）
    for group, entries in (usage["groups"] or {}).items():
        for name, entry in entries.items():
            if entry["status"] == "pending" and not (tokens.get("pendingNotes") or {}).get(f"{group}.{name}"):
                issues.append(f"tokens.pendingNotes 缺 {group}.{name} 的原因（0 处消费必须写明为什么）")

    # 2) shapeSemantics：声明的模式必须与实测自洽
    for name, entry in (tokens.get("shapeSemantics") or {}).items():
        mode = entry.get("mode")
        measured = (usage.get("shapeSemantics") or {}).get(name) or {}
        if mode not in SHAPE_MODES:
            issues.append(f"tokens.shapeSemantics.{name}.mode={mode!r} 不合法，应为 {SHAPE_MODES}")
            continue
        if not entry.get("note"):
            issues.append(f"tokens.shapeSemantics.{name} 缺 note（语义名必须解释它对应代码里的什么）")
        if mode == "slot":
            if entry.get("scaleSlot") not in (code["shape"] or {}):
                issues.append(f"tokens.shapeSemantics.{name}.scaleSlot={entry.get('scaleSlot')!r} 不是代码里的槽位")
            elif not measured.get("slotSites"):
                issues.append(f"tokens.shapeSemantics.{name} 声明 mode=slot / {entry['scaleSlot']}，"
                              f"但代码里 0 处消费 MaterialTheme.shapes.{entry['scaleSlot']}")
        elif mode == "raw":
            if not measured.get("rawSites"):
                issues.append(f"tokens.shapeSemantics.{name} 声明 mode=raw / {entry.get('radiusDp')}dp，"
                              f"但代码里 0 处该半径（声明与实况不符）")
        elif mode == "circle":
            if not measured.get("circleShapeSites"):
                issues.append(f"tokens.shapeSemantics.{name} 声明 mode=circle，但代码里没有 CircleShape 消费点")

    # 3) componentSpecs：引用完整性 + 证据必须真的引用 token
    specs = (spec.get("componentSpecs") or {}).get("components") or []
    if not specs:
        issues.append("componentSpecs.components 为空 —— 书写段缺失")
    screen_ids = {s.get("id") for s in (spec.get("screens") or [])}
    seen_ids = set()
    for comp in specs:
        cid = comp.get("id")
        if not cid or cid in seen_ids:
            issues.append(f"componentSpecs 组件 id 缺失或重复：{cid!r}")
        seen_ids.add(cid)
        rel = comp.get("file")
        if not rel:
            issues.append(f"componentSpecs.{cid} 缺 file")
            continue
        if not os.path.exists(dc.scan_base(root, rel)):
            issues.append(f"componentSpecs.{cid}.file 不存在：{rel}")
            continue
        for screen in comp.get("screens") or []:
            if screen not in screen_ids:
                issues.append(f"componentSpecs.{cid}.screens 引用了不存在的屏：{screen}")
        declared_states = set()
        for s in spec.get("screens") or []:
            declared_states |= set(s.get("states") or [])
        for st in comp.get("states") or []:
            if st not in declared_states and st not in ("normal", "dark", "long_text", "font2x"):
                issues.append(f"componentSpecs.{cid}.states 引用了未声明的状态：{st}")
        for prev in comp.get("previews") or []:
            if prev not in previews:
                issues.append(f"componentSpecs.{cid}.previews 引用了不存在的预览函数：{prev}")
        # requiredTokens：书写意图，必须能在该文件里找到真实证据
        slots = dc.scan_file_slots(root, [rel])[rel]
        for need in comp.get("requiredTokens") or []:
            if "." not in need:
                issues.append(f"componentSpecs.{cid}.requiredTokens 格式应为 group.name：{need!r}")
                continue
            group, name = need.split(".", 1)
            if group not in TOKEN_GROUPS:
                issues.append(f"componentSpecs.{cid}.requiredTokens 组名非法：{group!r}")
                continue
            pool = tokens.get(group) or {}
            if name not in pool:
                issues.append(f"componentSpecs.{cid}.requiredTokens 引用了不存在的 token：{need}")
                continue
            if name not in (slots.get(group) or {}):
                issues.append(f"componentSpecs.{cid} 声明必须绑定 {need}，但 {rel} 里没有任何证据行 —— "
                              f"这就是「该用 token 却写了字面量」的欠债，要么改代码要么改声明")

    # 4) stateSlots：词表与逐屏适用性
    slots_def = spec.get("stateSlots") or {}
    vocab = slots_def.get("vocabulary") or []
    if not vocab:
        issues.append("stateSlots.vocabulary 为空 —— 状态槽分母没有定义")
    vocab_ids = [v.get("id") for v in vocab]
    if len(vocab_ids) != len(set(vocab_ids)):
        issues.append("stateSlots.vocabulary 里 id 有重复")
    for v in vocab:
        if not v.get("match"):
            issues.append(f"stateSlots.vocabulary.{v.get('id')} 缺 match 正则（没有匹配规则就没法找证据）")
        else:
            try:
                re.compile(v["match"])
            except re.error as exc:
                issues.append(f"stateSlots.vocabulary.{v.get('id')}.match 不是合法正则：{exc}")
    per_screen = slots_def.get("perScreen") or {}
    for screen, entries in per_screen.items():
        if screen not in screen_ids:
            issues.append(f"stateSlots.perScreen 引用了不存在的屏：{screen}")
        for entry in entries:
            if entry.get("slot") not in vocab_ids:
                issues.append(f"stateSlots.perScreen.{screen} 引用了词表外的槽：{entry.get('slot')!r}")
            if entry.get("applicableSource") not in ("declared", "derived-keyword"):
                issues.append(f"stateSlots.perScreen.{screen}.{entry.get('slot')} 缺 applicableSource"
                              f"（declared=规范里写了；derived-keyword=从屏 states 关键字推出来的，必须诚实标注）")
    missing = [s for s in screen_ids if s not in per_screen]
    if missing:
        issues.append(f"stateSlots.perScreen 缺屏：{sorted(missing)}")

    # 4b) implementationAnchors：缺口分档的判据必须可判假
    # 这一档过去是手写的，所以校验要盯三件事：锚点文件真实存在、正则合法、
    # 且锚点指向的 (屏, 槽) 确实是分母里的一个单元——锚点写错屏名会静默失效
    # （classify 找不到它 ⇒ 该缺口被当成「没声明锚点」⇒ 默认未实现，看似保守，
    # 实际是把「已实现未验证」错报成「未实现」，会让人去写已经存在的代码）。
    anchors_def = slots_def.get("implementationAnchors") or {}
    anchor_entries = anchors_def.get("entries") or []
    if not anchors_def:
        issues.append("stateSlots.implementationAnchors 缺失 —— 缺口分档会退回手写，"
                      "而手写已被证明会写出假阳性（见该表 note）")
    na_pairs = set()
    for rule in (slots_def.get("notApplicable") or {}).get("rules") or []:
        for slot in rule.get("slots") or []:
            na_pairs.add((rule.get("screen"), slot))
    declared_pairs = {(screen, e.get("slot"))
                      for screen, entries in per_screen.items() for e in entries}
    seen_anchors = set()
    for a in anchor_entries:
        pair = (a.get("screen"), a.get("slot"))
        if pair in seen_anchors:
            issues.append(f"stateSlots.implementationAnchors 重复声明：{pair[0]}/{pair[1]}")
        seen_anchors.add(pair)
        if pair not in declared_pairs:
            issues.append(f"stateSlots.implementationAnchors.{pair[0]}/{pair[1]} "
                          f"不在分母里（屏名或槽名写错，锚点会静默失效）")
        if pair in na_pairs:
            issues.append(f"stateSlots.implementationAnchors.{pair[0]}/{pair[1]} "
                          f"是不适用槽，不该有缺口锚点")
        rel = a.get("file")
        if not rel:
            issues.append(f"stateSlots.implementationAnchors.{pair[0]}/{pair[1]} 缺 file")
        elif not os.path.exists(dc.scan_base(root, rel)):
            issues.append(f"stateSlots.implementationAnchors.{pair[0]}/{pair[1]}.file 不存在：{rel}")
        if not a.get("match"):
            issues.append(f"stateSlots.implementationAnchors.{pair[0]}/{pair[1]} 缺 match 正则")
        else:
            try:
                re.compile(a["match"])
            except re.error as exc:
                issues.append(f"stateSlots.implementationAnchors.{pair[0]}/{pair[1]}"
                              f".match 不是合法正则：{exc}")
        if not a.get("why"):
            issues.append(f"stateSlots.implementationAnchors.{pair[0]}/{pair[1]} 缺 why"
                          f"（为什么这个锚点算「已实现」，是这条分档的全部依据）")

    # 4c) previewAliases：词表按函数名找证据，而作者对"这一帧钉的是哪个槽"的声明
    # 常常写在别处（夹具 KDoc、变量名）。名字对不上时，工具会把**已有的证据**报成缺口
    # （实测：`settings/loading` 的分支在代码里叫 `restarting`，帧叫 `SettingsRestartingPreview`，
    # 词表 `(?i)loading` 一辈子看不见它 ⇒ 有图有分支的槽被报成"未实现"）。
    # 别名是逐 (屏, 槽) 的显式声明，**不放松任何全局正则**，所以它不会像前缀嵌套那样
    # 把证据漏给邻屏。校验盯四件事：指向的单元在分母里、帧真实存在、不重复、必须写 why。
    # "这条别名真的改变了什么"由 design-state-slot-report.py --check 判（那里才算得出覆盖）。
    alias_def = slots_def.get("previewAliases") or {}
    preview_names = set(previews or [])
    seen_aliases = set()
    for a in alias_def.get("entries") or []:
        pair = (a.get("screen"), a.get("slot"))
        if pair in seen_aliases:
            issues.append(f"stateSlots.previewAliases 重复声明：{pair[0]}/{pair[1]}")
        seen_aliases.add(pair)
        if pair not in declared_pairs:
            issues.append(f"stateSlots.previewAliases.{pair[0]}/{pair[1]} 不在分母里"
                          f"（屏名或槽名写错，别名会静默失效）")
        if pair in na_pairs:
            issues.append(f"stateSlots.previewAliases.{pair[0]}/{pair[1]} 是不适用槽，"
                          f"它的证据在原理上不可得，别名不该声明它")
        names = a.get("previews") or []
        if not names:
            issues.append(f"stateSlots.previewAliases.{pair[0]}/{pair[1]} 缺 previews")
        for name in names:
            if name not in preview_names:
                issues.append(f"stateSlots.previewAliases.{pair[0]}/{pair[1]} 指向不存在的"
                              f"@Preview 函数：{name}（写错帧名会静默什么都不算）")
        if not a.get("why"):
            issues.append(f"stateSlots.previewAliases.{pair[0]}/{pair[1]} 缺 why"
                          f"（这一帧为什么钉的是本槽，是这条声明的全部依据）")

    # 5) gates：三个门禁必须登记，且命令指向的脚本真实存在
    gates = spec.get("gates") or {}
    for gid, why in REQUIRED_GATES.items():
        entry = gates.get(gid)
        if not entry:
            issues.append(f"gates 缺 {gid}（{why}）")
            continue
        cmd = entry.get("command") or ""
        for m in re.finditer(r"((?:tools/|scaffold/)[\w./-]+\.(?:py|kts|sh))", cmd):
            if not os.path.exists(m.group(1)):
                issues.append(f"gates.{gid}.command 指向的脚本不存在：{m.group(1)}")
        if not entry.get("baseline"):
            issues.append(f"gates.{gid} 缺 baseline 路径")
        if not entry.get("proves"):
            issues.append(f"gates.{gid} 缺 proves（这个门禁红了代表什么，必须写清）")
    return issues


# ── 主流程 ─────────────────────────────────────────────────────────
ORDER = ["app", "targets", "tokens", "componentSpecs", "stateSlots", "gates",
         "icons", "screens", "previewMatrix", "verification", "architecture", "qualityGate"]


def ordered(spec):
    out = {}
    for key in ORDER:
        if key in spec:
            out[key] = spec[key]
    for key in spec:
        if key not in out:
            out[key] = spec[key]
    return out


def self_test() -> List[str]:
    """先验量具：易变字段必须被跳过、真漂移必须被抓住、书写段引用必须真的校验。"""
    fails: List[str] = []

    def expect(cond, msg):
        if not cond:
            fails.append(msg)

    committed = {"tokens": {"colors": {"primary": "#2D6A4F"}, "usage": {
        "groups": {"colors": {"surface": {"sites": 60, "first": "a.kt:1", "status": "adopted"}}},
        "shapeInventory": {"circleShapeSites": 25, "offScale": ["6"]},
        "debt": {"spacing": 785},
    }}}
    projected = {"tokens": {"colors": {"primary": "#2D6A4F"}, "usage": {
        "groups": {"colors": {"surface": {"sites": 61, "first": "b.kt:9", "status": "adopted"}}},
        "shapeInventory": {"circleShapeSites": 26, "offScale": ["6"]},
        "debt": {"spacing": 787},
    }}}
    skipped: List[str] = []
    issues = deep_diff(committed, projected, "spec", None, skipped)
    expect(not any("sites" in i or "first" in i for i in issues),
           "自检：行号/计数（sites、first）不该阻塞门禁")
    expect(any("shapeInventory" in s for s in skipped), "自检：shapeInventory 应整块被跳过")
    expect(any("debt.spacing" in i for i in issues),
           "自检：欠债数字变化是真信号，必须报出来")

    changed = {"tokens": {"colors": {"primary": "#1F6F5C"}, "usage": committed["tokens"]["usage"]}}
    expect(any("colors.primary" in i for i in deep_diff(changed, projected, "spec", None, [])),
           "自检：色值被改成旧青绿必须报红")

    # 证据正则由 token 名推导：形状的槽位名与代码写法不同，也要能找到证据
    pat = dc.token_reference_pattern("elevation", "card", None)
    expect(bool(re.search(pat, "RelayElevations.Card")),
           "自检：elevation.card 的证据正则必须匹配代码里的 RelayElevations.Card")
    pat2 = dc.token_reference_pattern("spacing", "md", None)
    expect(bool(re.search(pat2, "LocalAppSpacing.current.md")),
           "自检：spacing.md 的证据正则必须匹配 LocalAppSpacing.current.md")

    # 组件槽位探针：`LocalCardShape.current` 是本仓卡片圆角的**本地机制**（默认
    # `RelayShapes.large` = 手机 16dp，桌面壳覆盖成 medium = 12dp），代码里根本没有
    # `shapes.X` 字面量可匹配。探针必须为它发射一个槽位名，否则「卡片圆角走对了机制」
    # 会被判成「该用 token 却没有任何证据行」—— 正门锁着、把这件做对的事记成欠债。
    found = []
    for group, pat, fixed in dc._COMPONENT_PROBES:
        for m in re.finditer(pat, "shape = LocalCardShape.current,"):
            found.append((group, fixed if fixed else m.group(1)))
    expect(("shape", "large") in found,
           "自检：LocalCardShape.current 必须被 shape 探针认成槽位 large "
           "（默认值就是 RelayShapes.large，见 AppTheme.kt 的 LocalCardShape）")
    return fails


def main():
    ap = argparse.ArgumentParser(description="design-spec.json 投影与漂移门禁")
    ap.add_argument("--root", default=".", help="代码根目录（默认当前目录）")
    ap.add_argument("--write", action="store_true", help="重新投影 tokens/usage 并写回")
    ap.add_argument("--check", action="store_true", help="校验漂移与引用完整性（CI 用）")
    ap.add_argument("--self-test", action="store_true", help="先验量具本身（喂已知缺陷，必须报红）")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        fails = self_test()
        for f in fails:
            print("FAIL", f)
        print(f"self-test: {'OK' if not fails else 'FAILED'}（{len(fails)} 处）")
        return 0 if not fails else 1

    root = args.root
    spec = load_spec(root)
    code, tokens, usage = build_projection(root, spec)
    previews = dc.scan_preview_functions(root)

    if args.write:
        spec["tokens"] = tokens
        if "usage" in spec:
            spec.pop("usage")
        blob = dump_spec(ordered(spec))
        with open(spec_path(root), "w", encoding="utf-8") as fh:
            fh.write(blob)
        print(f"[design-spec-project] 已重新投影 {os.path.join(root, SPEC_REL)}")
        print(f"  tokens.colors={len(tokens['colors'])} colorsDark={len(tokens['colorsDark'])} "
              f"typography={len(tokens['typography'])} spacing={len(tokens['spacing'])} "
              f"shape={len(tokens['shape'])} elevation={len(tokens['elevation'])}")
        for group, ratio in usage["adoption"].items():
            print(f"  {group} 采用率 {ratio}")
        print(f"  欠债 {usage['debt']}")
        print(f"  档外圆角半径 {usage['shapeInventory']['offScale']}")
        return 0

    committed = {"tokens": spec.get("tokens") or {}}
    skipped = []
    issues = deep_diff(committed, {"tokens": tokens}, "spec", None, skipped)
    issues += validate(spec, code, usage, previews, root)
    if skipped:
        print(f"[design-spec-project] 跳过 {len(skipped)} 个易变字段（行号/计数，"
              f"不阻塞；`--write` 会顺手刷新）：{skipped[0]}")
    if issues:
        print(f"[design-spec-project] FAIL — {len(issues)} 条问题（spec 与代码漂移或书写段不完整）")
        for line in issues:
            print(f"  · {line}")
        print("\n修法：改了代码就 `--write` 重投影；书写段（componentSpecs/stateSlots/gates/"
              "shapeSemantics/pendingNotes）要人来改。")
        return 1
    print(f"[design-spec-project] OK — tokens 与代码逐键一致；书写段引用完整"
          f"（{len((spec.get('componentSpecs') or {}).get('components') or [])} 个组件规格、"
          f"{len((spec.get('stateSlots') or {}).get('vocabulary') or [])} 个状态槽、"
          f"{len(spec.get('gates') or {})} 个门禁）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
