#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""施工痕迹扫描：注释里只对"当时在做什么"有意义的那几类形态。

## 为什么

`docs/relay-comment-audit.md` §4 立的注释政策里有六条是"禁止出现某种形态"：
ISO 日期、真机型号、内网地址、本机绝对路径、决议号、时间戳痕迹。
它们全都很短、很像正常技术内容 —— "2026-10-10 在 Pixel 6 上实测三次" 读起来
完全像一句技术说明，人工 review 不会拦它，半年后也没人知道该不该更新那个日期。

只有变成会报红的门禁，政策才真的生效。

## 每条形态的判据与理由

| 形态 | 判据 | 为什么算痕迹 |
|---|---|---|
| ISO 日期 | `20XX-XX-XX` | 让下一个人以为这条结论有时效 |
| 真机型号 | Pixel/iPhone/iPad/小米/Redmi + 数字 | 同上，且与缺陷史绑定的才该进 `docs/adr/` |
| 内网地址 | `192.168.x.x` | 是作者家网络；网段判据本身（`CidrUtils`）是例外 |
| 本机路径 | `/Users/<名>`、`/Volumes/<名>` | 是作者本机路径，对第三方无意义 |
| 决议号 | `决议 #NNN` | 指向一个仓库里不存在的台账 = 死引用 |
| 内部文件 | PLAN.md / ARCHITECTURE.md / docs/plans/ | `.gitignore` 明确排除的内部工作笔记 |
| 时间戳痕迹 | `HH:MM:SS`、`第 N 次`、`N ms 即崩溃` | 只对当时那一次有意义 |

## 例外（必须保留，否则门禁会毁掉真数据）

- **测试源集不在扫描根里**：测试里写 `192.168.2.82:17878` 是被测对象本身。
- **网段判据代码**：`CidrUtils` 里 `192.168.1.55/24` 是它实现的那个功能的示例。
- **文档字符串里的示例**：`relay://192.168.1.5:17878` 这类配对码格式说明。
- **明确是反例的句子**：含「例如」「比如」「反例」「假」等词的行不报。
- 上一行里明写了"这行不是注释"等情况不在本工具职责内（本工具只看注释）。

## 棘轮

与 `cjk-literal-gate` / `stale-comment-ref.py` 同构：基线记每个文件允许的条数，
只有新增才失败。
"""

from __future__ import annotations

import argparse
import glob
import json
import os
import re
import subprocess
import sys

# ---------------------------------------------------------------------------
# 七条形态
# ---------------------------------------------------------------------------

RULES = [
    # (规则 id, 说明, 注释里匹配的正则)
    ("iso-date", "ISO 日期",
     re.compile(r"\b20[0-9]{2}-[01][0-9]-[0-3][0-9]\b")),
    ("device-model", "真机型号",
     re.compile(r"\b(?:Pixel\s?[0-9]+|iPhone\s?[0-9A-Za-z]+|iPad\s?[0-9A-Za-z]+|"
                r"小米\s?[0-9]*|Redmi\s?[0-9A-Za-z]*)\b")),
    ("lan-ip", "内网地址",
     re.compile(r"\b192\.168\.\d{1,3}\.\d{1,3}\b")),
    ("local-path", "本机绝对路径",
     re.compile(r"/(?:Users|Volumes)/[A-Za-z]")),
    ("decision-no", "决议号",
     re.compile(r"决议\s*#\s*\d+")),
    ("internal-file", "内部工作笔记",
     re.compile(r"(?:^|[^\w/])(?:PLAN\.md|ARCHITECTURE\.md|docs/plans/)")),
    ("timestamp-trace", "时间戳痕迹",
     re.compile(r"\b(?:[01]?\d|2[0-3]):[0-5]\d:[0-5]\d\b")),
]

# 整行豁免：这一行明确在讲"不是这样"或"举例"，不是施工痕迹。
EXEMPT_LINE = re.compile(
    r"例如|比如|反例|假值|示例|假数据|占位|不要这样|不能这样|会错|错误示范|坑的是|"
    r"relay://|cidr|CIDR|网段|/24|测试数据|fixture|夹具|protocol|Protocol"
    # 「今天的记录写「14:22」、明天同一条写成「2026-10-12 14:22」」是在**举例两种格式**，
    # 那个完整日期正是例子本身，删掉就没法说明两种格式的区别了。
    r"|今天.{0,20}明天"
)

# 这些文件里的形态是**该文件实现的功能本身**，不是注释痕迹。
FILE_EXEMPT = {
    # TransferServer 里那几处内网地址是"裸地址 vs SocketAddress.toString()"的对照例，
    # 换成别的网段反而看不出说的是 IPv4 映射地址。
    os.path.normpath("core/src/commonMain/kotlin/com/kapp/tidings/core/transport/TransferServer.kt"),
    # NodeRuntime 那处是在举 `{"to":"192.168.1.5"}` 这个 JSON 的形状。
    os.path.normpath("core/src/commonMain/kotlin/com/kapp/tidings/core/session/NodeRuntime.kt"),
    # 桌面端搜索历史的参考串逐字是原型那行，录参考图要用同一串。
    os.path.normpath("composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopHistorySearch.kt"),
    # 「192.168.1.77:17878 这种串没人记得住是哪台」—— 用的是 RFC1918 里的示例段。
    os.path.normpath("app/src/commonMain/kotlin/com/kapp/tidings/domain/RelayController.kt"),
    # 契约样例里的假地址（KDoc 里的 sample 实现），不是生产取值。
    os.path.normpath("app/src/commonMain/kotlin/com/kapp/tidings/platform/PlatformContracts.kt"),
    # 「m09b-addnode-fill 框里已经写着 192.168.1.31」—— 逐字引用原型那一屏。
    os.path.normpath("composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/nodes/NodeSheets.kt"),
    # `/Users/a&b/…` 是在举"路径里有 & 会破 plist"，必须保留原样才看得出问题。
    os.path.normpath("desktopApp/src/main/kotlin/com/kapp/tidings/desktop/DesktopLoginItem.kt"),
    # CIDR 规范化实现，示例网段就是它的输入格式
    os.path.normpath("core/src/commonMain/kotlin/com/kapp/tidings/core/protocol/CidrUtils.kt"),
    # 二维码载荷格式说明
    os.path.normpath("core/src/commonMain/kotlin/com/kapp/tidings/core/qr/QrEncoder.kt"),
    # 截图夹具的假数据（虽然不在根里，防御性保留）
    os.path.normpath("composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/PreviewState.kt"),
    # agentNode 控制面的 curl 示例
    os.path.normpath("agentNode/src/main/kotlin/com/kapp/tidings/agent/ControlPlane.kt"),
    # 桌面端深链格式与私网判据
    os.path.normpath("desktopApp/src/main/kotlin/com/kapp/tidings/desktop/DesktopDeepLink.kt"),
    os.path.normpath("desktopApp/src/main/kotlin/com/kapp/tidings/desktop/DesktopPlatform.kt"),
}


def comment_line_mask(lines):
    """与 stale-comment-ref.py 同构：标出每行哪些字符属于注释。"""
    mask = []
    in_block = False
    for line in lines:
        row = [False] * len(line)
        i, n = 0, len(line)
        if in_block:
            end = line.find("*/", 2)
            if end == -1:
                mask.append([True] * n)
                continue
            for k in range(0, end + 2):
                row[k] = True
            i = end + 2
            in_block = False
        while i < n:
            ch = line[i]
            if ch == '"':
                if line.startswith('"""', i):
                    end = line.find('"""', i + 3)
                    i = n if end == -1 else end + 3
                else:
                    i += 1
                    while i < n:
                        if line[i] == "\\":
                            i += 2
                            continue
                        if line[i] == '"':
                            i += 1
                            break
                        if line[i] == "'":
                            i += 1
                            continue
                        i += 1
                continue
            if ch == "'":
                i += 1
                while i < n:
                    if line[i] == "\\":
                        i += 2
                        continue
                    i += 1
                    if line[i - 1] != "\\":
                        break
                continue
            if line.startswith("//", i):
                for k in range(i, n):
                    row[k] = True
                break
            if line.startswith("/*", i):
                end = line.find("*/", i + 2)
                if end == -1:
                    for k in range(i, n):
                        row[k] = True
                    in_block = True
                    break
                for k in range(i, end + 2):
                    row[k] = True
                i = end + 2
                continue
            i += 1
        mask.append(row)
    return mask


def scan_file(path, rel):
    if os.path.normpath(rel) in FILE_EXEMPT:
        return []
    try:
        with open(path, encoding="utf-8") as fh:
            lines = fh.read().split("\n")
    except (OSError, UnicodeDecodeError):
        return []
    mask = comment_line_mask(lines)
    hits = []
    for ln, line in enumerate(lines, start=1):
        if EXEMPT_LINE.search(line):
            continue
        row = mask[ln - 1] if ln - 1 < len(mask) else []
        for rid, label, rx in RULES:
            for m in rx.finditer(line):
                if not all(row[m.start():m.end()]):
                    continue          # 不在注释里
                hits.append({
                    "file": rel, "line": ln, "rule": rid, "label": label,
                    "snippet": m.group(0),
                    "text": line.strip()[:140],
                })
    return hits


def scan(base, roots):
    hits = []
    for root in roots:
        top = os.path.join(base, root)
        if not os.path.isdir(top):
            continue
        for dirpath, dirnames, filenames in os.walk(top):
            dirnames[:] = [d for d in dirnames if d != "build"]
            for fn in sorted(filenames):
                # 源码根里不该躺着文档/工作笔记：曾经有一份 LUCIOLA_WIRING.md 带着日期、
                # 行号引用（NodeRuntime.kt:291）和"下一个会话执行"睡在 transport/ 下，
                # 而所有注释门禁都只收 *.kt —— 它谁也没挡住。
                if fn.endswith(".md") and not os.path.normpath(
                    os.path.relpath(os.path.join(dirpath, fn), base),
                ) in FILE_EXEMPT:
                    rel = os.path.relpath(os.path.join(dirpath, fn), base)
                    hits.append({
                        "file": rel, "line": 1, "rule": "stray-doc",
                        "label": "源码根里的文档",
                        "snippet": fn, "text": "工作笔记/文档请放 docs/ 或 design/",
                    })
                    continue
                if not fn.endswith(".kt"):
                    continue
                full = os.path.join(dirpath, fn)
                rel = os.path.relpath(full, base)
                hits.extend(scan_file(full, rel))
    return hits


def load_baseline(path):
    base = {}
    if not os.path.exists(path):
        return base
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            k, _, v = line.rpartition("\t")
            if k:
                base[k] = int(v)
    return base


def write_baseline(path, current, note):
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("# 施工痕迹（日期/型号/内网地址/本机路径/决议号/内部文件/时间戳）棘轮基线\n")
        fh.write("# 格式: <相对路径>\\t<允许的条数>\n")
        fh.write("# 生成时机: %s\n" % note)
        fh.write("# 生成命令: python3 tools/construction-trace.py --write\n")
        for k in sorted(current):
            fh.write("%s\t%d\n" % (k, current[k]))


def git_note(base):
    try:
        return subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=base,
                              capture_output=True, text=True, check=True).stdout.strip()
    except Exception:
        return "unknown"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--baseline", default="tools/construction-trace-baseline.txt")
    ap.add_argument("--root-dir", action="append", default=[],
                    help="扫描根（相对 --root），可重复")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--write", action="store_true")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--rule", help="只看某条规则")
    args = ap.parse_args()

    base = os.path.abspath(args.root)
    roots = args.root_dir or [
        # **core 的四个源码集都要扫**。曾经只有 commonMain —— 而 jvmSharedMain 正是
        # AGENTS.md §0 指定的"新传输实现写这里"的接缝，一次重构把大量生产代码搬进去之后，
        # 门禁就静默停止覆盖它了。scan() 对不存在的根是跳过，所以缺口不会自己报错。
        "core/src/commonMain", "core/src/jvmMain", "core/src/jvmSharedMain",
        "core/src/iosMain",
        # 领域层现在住在 :app。漏掉这几行不会报错 —— scan() 对不存在的根是跳过，
        # 于是"整层没被扫过"会显示成"无新增"。
        "app/src/commonMain", "app/src/androidMain",
        "app/src/iosMain", "app/src/jvmMain", "app/src/jvmSharedMain",
        "composeApp/src/commonMain", "composeApp/src/androidMain",
        "composeApp/src/iosMain", "composeApp/src/jvmMain",
        "agentNode/src/main", "desktopApp/src/main", "androidApp/src/main",
        "cli/src/main",
    ]
    hits = scan(base, roots)
    if args.rule:
        hits = [h for h in hits if h["rule"] == args.rule]

    if args.json:
        print(json.dumps(hits, ensure_ascii=False, indent=2))
        return 0

    current = {}
    for h in hits:
        current[h["file"]] = current.get(h["file"], 0) + 1

    by_rule = {}
    for h in hits:
        by_rule[h["label"]] = by_rule.get(h["label"], 0) + 1

    if args.write:
        write_baseline(os.path.join(base, args.baseline), current, "HEAD=%s" % git_note(base))
        print("[construction-trace] 已写入基线：%d 个文件 / %d 条" % (len(current), len(hits)))
        for k in sorted(by_rule):
            print("  %-14s %d" % (k, by_rule[k]))
        return 0

    baseline = load_baseline(os.path.join(base, args.baseline))
    seen = {}
    failures = []
    for h in hits:
        seen[h["file"]] = seen.get(h["file"], 0) + 1
        if seen[h["file"]] > baseline.get(h["file"], 0):
            failures.append(h)

    print("[construction-trace] 扫描 %d 个根；命中 %d 条 %s"
          % (len(roots), len(hits), by_rule if by_rule else ""))
    if failures:
        print("[construction-trace] FAIL — %d 条新增" % len(failures))
        for h in failures:
            print("  · %s:%d  [%s] `%s`" % (h["file"], h["line"], h["label"], h["snippet"]))
            print("      %s" % h["text"])
            print("      改法：留结论，去掉日期与型号；真机缺陷史去 docs/adr/ 建一条 ADR。")
        return 1
    print("[construction-trace] OK —— 无新增（基线 %d 条 / %d 个文件）"
          % (sum(baseline.values()), len(baseline)))
    return 0


if __name__ == "__main__":
    sys.exit(main())