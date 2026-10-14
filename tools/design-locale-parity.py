#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""多语言资源对账（Compose Resources 的 `values` ↔ `values-<lang>`）。

为什么需要它
------------
仓里已经有十把设计契约量具，**没有一把看多语言**。实测后果（2026-10-12）：

    HEAD（7fdfec28）时 `values` 与 `values-en` 各 573 条，**逐键对齐**；
    工作树里 `values` 涨到 746、`values-en` 涨到 713 ⇒ **33 个键只有中文**。

Compose Resources 的查找顺序是「当前语言 → 默认 `values`」。所以那 33 个键在英文
环境下**不报错、不崩**，而是**静默回落成中文** —— 一个英文用户会看到满屏中文，而
所有门禁全绿、所有截图全过（截图帧 `locale = "zh-rCN"`，根本照不到这条路）。
这属于"少读 = 假绿"那一族：没有量具，就没有人会发现。

它查四件事（每件都是一个真实的坏法）
------------------------------------
1. `missingEn`  —— 默认语言有、该语言没有的键。**回落成默认语言**（上面那条）。
2. `extraEn`    —— 该语言有、默认语言没有的键。默认语言是兜底，缺了它等于**该语言
                   独有**：换回默认语言时这处文案直接消失（`stringResource` 抛错或空白）。
3. `cjkInEn`    —— 该语言的值里**含中日韩字符**。典型是复制中文过去忘了翻。
                   （"没翻"和"翻错了"不同：这里只能判前者，但它已经能抓住绝大多数。）
4. `emptyEn`    —— 该语言的值是空串。比回落更坏：回落至少还有字，空串是空白。

棘轮口径
--------
四组都是**集合棘轮**：基线里记的是键名集合，`--check` 只报「比基线**新出现**的键」。
所以翻译掉一个键 = 集合变小 = 永远不红；新加一个只写中文的键 = 立刻红。
这比记总数强：总数相同但换了一个键的情况（翻了一个、漏了一个）**必须**报出来。

用法
----
    python3 scaffold/tools/design-locale-parity.py --root scaffold            # 只读报告
    python3 scaffold/tools/design-locale-parity.py --root scaffold --check    # 门禁
    python3 scaffold/tools/design-locale-parity.py --root scaffold --baseline # 有意接受后记账
    python3 scaffold/tools/design-locale-parity.py --root scaffold --self-test
"""
import argparse
import datetime
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

# 默认语言目录（Compose Resources 的兜底）与它的资源根。
DEFAULT_DIR = "values"
RES_REL = "composeApp/src/commonMain/composeResources"
BASELINE_REL = "tools/design-locale-parity-baseline.json"

# 中日韩：统一表意文字（含扩展 A 与兼容表意）+ 假名 + 谚文 + CJK 标点 + 全角形式。
# 全角形式要收进来，是因为「１」「，」「（）」这类全角标点在英文文案里同样是漏翻的信号。
CJK_RE = re.compile(
    r"[\u3000-\u303f\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff\uff00-\uffef]"
)

# 这些键的值允许含 CJK：它们是**语言自身的名字**（语言切换器要显示"中文"）。
# 值里出现中文是刻意的，不是漏翻。加键要带理由，不许随手扩。
#
# 2026-10-12 清空过一次：原来这里写着 `settings_language_name_zh`，但**全仓没有这个键**
# （`grep -rn "settings_language_name" scaffold/composeApp/src` 零命中，语言选择器的三项
# 实际叫 `set_theme_system/light/dark`，都不含中文）。一条指向不存在键的豁免**永不生效**，
# 但它给未来留了一个看不见的口子：谁真加了那个键，中文就会静默通过这道检查。
# 现在改成空集：要加豁免，必须同时指出**哪个文件在用这个键**，否则不许加。
CJK_ALLOWED_KEYS = set()


def _strip_ns(tag):
    """`{http://…}string` → `string`。Compose Resources 的 XML 没有命名空间，
    但 Android 侧的 `res/values/strings.xml` 有，两个都读时不能假设。"""
    return tag.split("}", 1)[-1]


def read_locale(path):
    """读一个 strings.xml → {key: value}。key 用 `tag:name` 形式，避免 string 与
    plurals 同名时互相覆盖（两者在资源系统里确实是两个不同的东西）。"""
    if not os.path.isfile(path):
        return None
    out = {}
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as exc:
        raise SystemExit(f"FAIL — {path} 不是合法 XML：{exc}")
    for el in root:
        tag = _strip_ns(el.tag)
        if tag not in ("string", "plurals", "string-array"):
            continue
        name = el.get("name")
        if not name:
            continue
        if tag == "plurals":
            # plurals 的值在子 <item quantity=…> 里；拼起来只为判 CJK/空。
            parts = [(it.text or "") for it in el if _strip_ns(it.tag) == "item"]
            value = "".join(parts)
        elif tag == "string-array":
            parts = [(it.text or "") for it in el if _strip_ns(it.tag) == "item"]
            value = "".join(parts)
        else:
            value = el.text or ""
        out[f"{tag}:{name}"] = value
    return out


def find_locales(root):
    """列出资源根下所有 `values` 与 `values-xx` 目录名。"""
    base = os.path.join(root, RES_REL)
    if not os.path.isdir(base):
        raise SystemExit(f"FAIL — 资源目录不存在：{base}")
    return sorted(d for d in os.listdir(base)
                  if d == DEFAULT_DIR or d.startswith(DEFAULT_DIR + "-"))


def measure(root):
    locales = find_locales(root)
    default = read_locale(os.path.join(root, RES_REL, DEFAULT_DIR, "strings.xml"))
    if default is None:
        raise SystemExit(f"FAIL — 默认语言资源不存在：{root}/{RES_REL}/{DEFAULT_DIR}/strings.xml")
    out = {}
    for loc in locales:
        if loc == DEFAULT_DIR:
            continue
        cur = read_locale(os.path.join(root, RES_REL, loc, "strings.xml"))
        if cur is None:
            # 目录在、文件不在：整个语言都缺，按"该语言全部键都缺"记。
            cur = {}
        missing = sorted(set(default) - set(cur))
        extra = sorted(set(cur) - set(default))
        cjk = sorted(k for k, v in cur.items()
                     if CJK_RE.search(v) and k not in CJK_ALLOWED_KEYS)
        empty = sorted(k for k, v in cur.items() if not v.strip())
        out[loc] = {"missing": missing, "extra": extra, "cjk": cjk, "empty": empty}
    return out


def load_baseline(path):
    if not os.path.isfile(path):
        return None
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def compare(current, baseline):
    """返回问题列表。棘轮只报"比基线新出现"的键 —— 修掉一个键永远不红。"""
    issues = []
    for loc, cur in sorted(current.items()):
        base = (baseline.get("locales") or {}).get(loc) or {}
        for cat, label in (("missing", "默认语言有、该语言没有"),
                           ("extra", "该语言有、默认语言没有"),
                           ("cjk", "该语言的值里含中日韩字符（疑似漏翻）"),
                           ("empty", "该语言的值是空串")):
            new = sorted(set(cur[cat]) - set(base.get(cat) or []))
            if new:
                issues.append(f"{loc} · {cat} 新增 {len(new)} 个（{label}）："
                              + "、".join(new[:12]) + ("…" if len(new) > 12 else ""))
    return issues


def render(current, baseline):
    lines = ["# 多语言资源对账", ""]
    total = 0
    for loc, cur in sorted(current.items()):
        n = sum(len(cur[c]) for c in ("missing", "extra", "cjk", "empty"))
        total += n
        lines.append(f"## `{loc}`")
        lines.append("")
        lines.append(f"- 缺失（默认语言有）：**{len(cur['missing'])}**")
        lines.append(f"- 多余（默认语言没有）：**{len(cur['extra'])}**")
        lines.append(f"- 含中日韩字符：**{len(cur['cjk'])}**")
        lines.append(f"- 空串：**{len(cur['empty'])}**")
        lines.append("")
        for cat, title in (("missing", "缺失"), ("extra", "多余"),
                           ("cjk", "含中日韩字符"), ("empty", "空串")):
            if cur[cat]:
                lines.append(f"<details><summary>{title}（{len(cur[cat])}）</summary>")
                lines.append("")
                for k in cur[cat]:
                    lines.append(f"- `{k}`")
                lines.append("")
                lines.append("</details>")
                lines.append("")
    lines.insert(2, f"合计 **{total}** 处待处理。基线："
                    + ("已记" if baseline else "**未记**"))
    lines.insert(3, "")
    return "\n".join(lines)


def cmd_self_test(root):
    """自检：喂已知缺陷必须报红，喂良性变更必须不报。"""
    fails = []

    def expect(cond, msg):
        if not cond:
            fails.append(msg)

    # 1. missing / extra 的方向性
    default = {"string:a": "甲", "string:b": "乙"}
    en = {"string:a": "A", "string:c": "C"}
    cur = {"en": {
        "missing": sorted(set(default) - set(en)),
        "extra": sorted(set(en) - set(default)),
        "cjk": [], "empty": [],
    }}
    expect(cur["en"]["missing"] == ["string:b"], "missing 应只有 string:b")
    expect(cur["en"]["extra"] == ["string:c"], "extra 应只有 string:c")

    # 2. 棘轮方向：与基线相同 → 不报；新增一个 → 报；少一个 → 不报
    base = {"locales": {"en": {"missing": ["string:b"], "extra": ["string:c"],
                               "cjk": [], "empty": []}}}
    expect(compare(cur, base) == [], "与基线相同不应报红")
    grown = {"en": {"missing": ["string:b", "string:d"], "extra": ["string:c"],
                    "cjk": [], "empty": []}}
    expect(len(compare(grown, base)) == 1, "新增一个缺失键应报 1 条")
    shrunk = {"en": {"missing": [], "extra": [], "cjk": [], "empty": []}}
    expect(compare(shrunk, base) == [], "修掉一个键不应报红")

    # 3. CJK 判定：中文要抓、英文不要抓、全角标点要抓
    expect(bool(CJK_RE.search("我的二维码")), "中文应判为 CJK")
    expect(not CJK_RE.search("My QR Code"), "英文不应判为 CJK")
    expect(bool(CJK_RE.search("OK，done")), "全角逗号应判为 CJK")
    expect(not CJK_RE.search("Tidings 2.0 (beta)"), "半角括号不应判为 CJK")

    # 4. 空串判定
    expect(not "  ".strip(), "空白串应判为空")

    # 5. 真实仓库：默认语言必须非空，且 `values-en` 必须被找到
    locales = find_locales(root)
    expect(DEFAULT_DIR in locales, "必须找到默认语言目录")
    if "values-en" not in locales:
        fails.append("本仓应当有 values-en —— 没找到说明资源根口径错了")

    if fails:
        for f in fails:
            print(f"  ✗ {f}")
        print(f"FAIL — 量具自检失败 {len(fails)} 项")
        return 1
    print(f"OK — 量具自检通过（默认语言 + {len(locales) - 1} 个语言目录："
          + "、".join(l for l in locales if l != DEFAULT_DIR) + "）")
    return 0


def main():
    ap = argparse.ArgumentParser(description="Compose Resources 多语言对账")
    ap.add_argument("--root", default=".", help="仓库根目录（默认当前目录）")
    ap.add_argument("--check", action="store_true", help="门禁：比基线新出现的键即红")
    ap.add_argument("--baseline", action="store_true", help="重记基线（有意接受后跑）")
    ap.add_argument("--reason", default="", help="重记基线时说明为什么")
    ap.add_argument("--markdown", help="把只读报告写到这个路径")
    ap.add_argument("--json", help="把只读报告写到这个 JSON 路径")
    ap.add_argument("--self-test", action="store_true", help="量具自检")
    args = ap.parse_args()

    if args.self_test:
        return cmd_self_test(args.root)

    current = measure(args.root)
    baseline_path = os.path.join(args.root, BASELINE_REL)

    total = sum(len(cur[c]) for cur in current.values()
                for c in ("missing", "extra", "cjk", "empty"))

    if args.markdown:
        os.makedirs(os.path.dirname(args.markdown) or ".", exist_ok=True)
        with open(args.markdown, "w", encoding="utf-8") as fh:
            fh.write(render(current, load_baseline(baseline_path)))
        print(f"已写出报告：{args.markdown}")

    if args.json:
        os.makedirs(os.path.dirname(args.json) or ".", exist_ok=True)
        with open(args.json, "w", encoding="utf-8") as fh:
            json.dump(current, fh, ensure_ascii=False, indent=2, sort_keys=True)
        print(f"已写出 JSON：{args.json}")

    for loc, cur in sorted(current.items()):
        print(f"{loc}: 缺失 {len(cur['missing'])} · 多余 {len(cur['extra'])}"
              f" · 含中文 {len(cur['cjk'])} · 空串 {len(cur['empty'])}")
        for cat in ("missing", "extra", "cjk", "empty"):
            for k in cur[cat][:5]:
                print(f"    [{cat}] {k}")
            if len(cur[cat]) > 5:
                print(f"    … [{cat}] 另有 {len(cur[cat]) - 5} 个")

    if args.baseline:
        # 第五个洞：棘轮工具的 --baseline 若不给理由，就是擦红灯的后门。
        if total and not args.reason.strip():
            print("FAIL — 还有待处理项时重记基线必须给 --reason（否则 --baseline 就是擦红灯）")
            return 2
        blob = {
            "generatedAt": datetime.datetime.now().isoformat(timespec="seconds"),
            "reason": args.reason.strip() or "全部对齐，无需豁免",
            "total": total,
            "locales": current,
        }
        with open(baseline_path, "w", encoding="utf-8") as fh:
            json.dump(blob, fh, ensure_ascii=False, indent=2, sort_keys=True)
        print(f"已重记基线：{baseline_path}（合计 {total}）")
        return 0

    if args.check:
        base = load_baseline(baseline_path)
        if base is None:
            print(f"FAIL — 基线不存在：{baseline_path}；先跑 --baseline 记账再判")
            return 2
        issues = compare(current, base)
        if issues:
            for i in issues:
                print(f"  ✗ {i}")
            print(f"\nFAIL — 多语言对账比基线更差（{len(issues)} 条）。"
                  "缺的键去 `values-en/strings.xml` 补翻译，不要重记基线。")
            return 1
        print(f"OK — 多语言对账无退化（基线合计 {base.get('total', '?')}，"
              f"当前 {total}；只许变少）")
        return 0

    return 0


if __name__ == "__main__":
    sys.exit(main())
