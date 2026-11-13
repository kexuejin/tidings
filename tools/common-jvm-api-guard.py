#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""
commonMain 的 JVM 专有 API 门禁（KMP 多目标能否编译）。

## 为什么要有它（2026-11-13 实测踩到）

`app/src/commonMain` 与 `composeApp/src/commonMain` 里写了两处 JVM 专有写法：

  * `MutableMap.putIfAbsent(...)` / `MutableMap.merge(...)`（`java.util.Map` 的默认方法）
  * `@JvmName`（住在 `kotlin.jvm`，common 源集里根本不存在）

Android 与桌面都能编（它们本来就是 JVM），**只有 iOS 目标编不过**。而 iOS 目标不在
任何一处收工清单里（跑它要 Kotlin/Native 工具链，CI 上是几分钟起步），于是这两处从
11-11 一直红到 11-13，没人看见 —— 直到有人真的去编了一次 iOS。

这条门禁只读源码、几秒出结果，抓的是**症状**而不是「编译 iOS 需要的那套环境」：

> common 源集里出现 JVM 专有符号 ⇒ 这一行不可能在任何非 JVM 目标上编译通过。

## 它不做什么

不判「语义对不对」，也不替代真的编译：`expect`/`actual` 漏写、iOS 平台件缺失这类，
只有编译能抓。这条门禁的定位是**便宜的早期预警**，与 `:core:jvmTest` 之外的
「编一次 iOS」互补，不是取代它。

## 两个假绿判据（与其他源码门禁同一套）

1. 扫描根必须存在（写错路径 ⇒ 空集合 ⇒ 「没有 offender」恒真）。
2. 扫到的文件数必须超过下限。

## 允许清单

`ALLOWED` 里逐条写理由，并且**判陈旧**：清单里的行若已不存在，门禁变红，逼人删条目。

## 它取代了谁

`composeApp` 里曾有一条 `CommonMainJvmOnlyApiTest`（EXT-10b 立的），判据只有
「`java.` 限定引用」与「`@Volatile` 必须显式 import」两条。那两条本门禁原样保留
（`java.*` 正则 + 文件级 `@Volatile` 检查），但那份测试漏掉了 2026-11-11 真正让 iOS
断掉的三样：`putIfAbsent` / `merge`（`java.util.Map` 默认方法）与 `@JvmName`；
而且它要跑 `:composeApp:jvmTest` 才执行（得先把整个 UI 层编译出来）。合到这里之后
判据只有一份，且与其余源码门一样「几秒出结果、可单独调用」。
"""

from __future__ import annotations

import argparse
import os
import re
import sys

# 有 iOS 形态的模块才扫：:orchestration / :agentNode / :desktopApp / :cli 是 JVM-only，
# 它们出现 JVM API 完全合法（那也是它们唯一的形态）。
COMMON_ROOTS = (
    "core/src/commonMain/kotlin",
    "core/src/commonTest/kotlin",
    "app/src/commonMain/kotlin",
    "app/src/commonTest/kotlin",
    "composeApp/src/commonMain/kotlin",
    "composeApp/src/commonTest/kotlin",
)

# 当前约 900+ 个文件；取 600 抓的是「整个根不见了」，不是数准个数。
MIN_FILES = 600

# 逐根下限（当前 core 125 / app 117 / composeApp 170，含 commonTest 后更高）。
# 总数够而某个根整个消失（改名、拆模块）时，只看总数是抓不到的 —— 那种情况下
# 该源集里的一切都不会再被检查，而门禁照样绿。
PER_ROOT_MIN_FILES = {
    "core/src/commonMain/kotlin": 90,
    "app/src/commonMain/kotlin": 90,
    "composeApp/src/commonMain/kotlin": 120,
}

# 文件级判据（不是逐行正则）：出现 `@Volatile` 就必须有跨平台的 import。
# JVM 上 `kotlin.jvm.Volatile` 靠默认 import 生效，Kotlin/Native 上直接 Unresolved。
VOLATILE_ANNOTATION = re.compile(r"@Volatile\b")
VOLATILE_IMPORT = "import kotlin.concurrent.Volatile"

# 出现的症状 → 人话解释。正则按**代码**匹配（注释与字符串先剥掉）。
RULES: tuple[tuple[str, str, str], ...] = (
    (r"\bjava\.[a-zA-Z]", "java.*", "JVM 专有包；common 里连全限定写法也不行（平台件走 expect/actual）"),
    (r"\bputIfAbsent\s*\(", "putIfAbsent", "java.util.Map 的默认方法；common 用 `if (k !in m) m[k] = v`"),
    (r"\.\s*merge\s*\(", ".merge", "java.util.Map 的默认方法；common 用 `m[k] = (m[k] ?: 0) + n`"),
    (r"\bcomputeIfAbsent\s*\(", "computeIfAbsent", "java.util.Map 的默认方法；common 用 `getOrPut`"),
    (r"\bcomputeIfPresent\s*\(", "computeIfPresent", "java.util.Map 的默认方法；common 显式判空改写"),
    (r"@JvmName\b", "@JvmName", "kotlin.jvm 注解；common 用不同 Kotlin 函数名区分即可"),
    (r"@JvmStatic\b", "@JvmStatic", "kotlin.jvm 注解；common 改用伴生对象 / 顶层函数"),
    (r"@JvmOverloads\b", "@JvmOverloads", "kotlin.jvm 注解；common 用默认参数"),
    (r"@JvmField\b", "@JvmField", "kotlin.jvm 注解；common 用普通属性"),
    (r"@Synchronized\b", "@Synchronized", "kotlin.jvm 注解；并发要靠 expect/actual 或 Mutex"),
    (r"\bsynchronized\s*\(", "synchronized", "JVM 专有关键字函数；common 用 Mutex / 单线程假定"),
    (r"\bSystem\s*\.\s*(currentTimeMillis|nanoTime|getenv|getProperty|out|err)\b", "System.*",
     "java.lang.System；common 用 `nowMillis()` / `Clock` / 平台 expect"),
    (r"\bString\s*\.\s*format\s*\(", "String.format", "JVM 专有静态方法；common 用字符串模板或平台 expect"),
    (r"::class\s*\.\s*java\b", "::class.java", "JVM 反射；common 用 `::class.simpleName` 或平台 expect"),
    (r"\bjavaClass\b", "javaClass", "JVM 反射属性；common 用 `this::class`"),
    (r"\bThread\s*\(", "Thread(", "java.lang.Thread；common 用协程"),
)

# import 行的粗判：common 源集里出现这些包名一律是错的（平台件走 expect/actual）。
FORBIDDEN_IMPORTS = ("java.", "javax.", "kotlin.jvm.", "sun.", "jdk.")

# `kotlin.jvm` 里**唯一**在各目标上都成立的例外：`@JvmInline value class` 是 common 的
# 正式写法（Kotlin 官方文档就这么 import），多平台都支持。
IMPORT_EXCEPTIONS = ("import kotlin.jvm.JvmInline",)

# 逐条写理由的例外。键 = "相对路径::规则签名"，值是理由。
# 整文件放行的代价：该文件里再出现**真的** JVM 专有 API 也看不见 —— 所以每条都要写清
# 「为什么这个文件里的同名符号不是 java.util.Map」。
ALLOWED: dict[str, str] = {
    "core/src/commonMain/kotlin/com/kapp/tenon/core/transport/SharedMap.kt::computeIfAbsent":
        "自建线程安全 Map 包装，`computeIfAbsent` 是它自己声明的方法（不是 java.util.Map 的）",
    "core/src/commonMain/kotlin/com/kapp/tenon/core/transport/SecureChannelTransport.kt::computeIfAbsent":
        "调用的是自建 `SharedMap.computeIfAbsent`（字段 `outbound`），非 JVM 默认方法",
    "app/src/commonMain/kotlin/com/kapp/tenon/domain/LockedCollections.kt::putIfAbsent":
        "自建 `LockedMap.putIfAbsent` 的声明处，语义自己实现（含 Mutex）",
    "app/src/commonMain/kotlin/com/kapp/tenon/domain/AgentCardActionLedger.kt::putIfAbsent":
        "调用的是自建 `LockedMap.putIfAbsent`（字段 `decided`），非 JVM 默认方法",
}


def strip_comments_and_strings(text: str) -> str:
    """去掉 // 行注释、/* */ 块注释与字符串字面量。

    不做这件事会把 KDoc 里「为什么不要 write putIfAbsent」这类说明也算成违规
    （本仓的注释密度决定了这不是理论问题）。
    """
    out: list[str] = []
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if ch == "/" and nxt == "/":
            while i < n and text[i] != "\n":
                i += 1
            continue
        if ch == "/" and nxt == "*":
            i += 2
            while i + 1 < n and not (text[i] == "*" and text[i + 1] == "/"):
                i += 1
            i += 2
            continue
        if text.startswith('"""', i):
            i += 3
            while i < n and not text.startswith('"""', i):
                i += 1
            i += 3
            continue
        if ch == '"':
            i += 1
            while i < n and text[i] != '"':
                if text[i] == "\\":
                    i += 1
                i += 1
            i += 1
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def scan(base: str) -> tuple[list[tuple[str, str, str]], int, list[str], list[str]]:
    offenders: list[tuple[str, str, str]] = []
    scanned = 0
    missing: list[str] = []
    thin: list[str] = []
    for root in COMMON_ROOTS:
        top = os.path.join(base, root)
        if not os.path.isdir(top):
            missing.append(root)
            continue
        per_root = 0
        for dirpath, dirnames, filenames in os.walk(top):
            dirnames[:] = [d for d in dirnames if d != "build"]
            for fn in filenames:
                if not fn.endswith(".kt"):
                    continue
                per_root += 1
                full = os.path.join(dirpath, fn)
                rel = os.path.relpath(full, base).replace(os.sep, "/")
                scanned += 1
                with open(full, encoding="utf-8") as fh:
                    raw = fh.read()
                code = strip_comments_and_strings(raw)
                # 报错里显示**原文行**（剥掉字符串后只剩 `m.putIfAbsent(, )`，读不出上下文）。
                # 两边行数一致才按行号取；块注释跨行会让行数错位，那时退回剥过的版本。
                raw_lines = raw.splitlines()
                code_lines = code.splitlines()
                aligned = len(raw_lines) == len(code_lines)
                allowed_sigs = {k.rsplit("::", 1)[1] for k in ALLOWED if k.rsplit("::", 1)[0] == rel}
                for idx, line in enumerate(code_lines):
                    stripped = line.strip()
                    shown = raw_lines[idx].strip()[:200] if aligned else stripped[:160]
                    if stripped.startswith("import "):
                        if any(stripped == ok for ok in IMPORT_EXCEPTIONS):
                            continue
                        for pkg in FORBIDDEN_IMPORTS:
                            if (" " + pkg) in stripped:
                                offenders.append((rel, "import " + pkg, shown))
                    for pattern, sig, _reason in RULES:
                        if sig in allowed_sigs:
                            continue
                        if re.search(pattern, line):
                            offenders.append((rel, sig, shown))
                # 文件级：@Volatile 存在就必须有跨平台 import（JVM 默认 import 会掩盖它）。
                if VOLATILE_ANNOTATION.search(code) and VOLATILE_IMPORT not in raw:
                    offenders.append((rel, "@Volatile 缺 import", VOLATILE_IMPORT + " 缺失"))
        floor = PER_ROOT_MIN_FILES.get(root)
        if floor is not None and per_root < floor:
            thin.append("%s（%d < %d）" % (root, per_root, floor))
    return offenders, scanned, missing, thin


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=None, help="仓库根（默认按脚本位置推断）")
    args = ap.parse_args()
    base = os.path.abspath(args.root) if args.root else os.path.abspath(
        os.path.join(os.path.dirname(__file__), "..")
    )

    failed = False
    offenders, scanned, missing, thin = scan(base)

    if missing:
        print("[common-jvm-api] FAIL — 这些扫描根不存在：" + ", ".join(missing))
        print("  读不到目录的守卫永远是通过的（空集合最干净）。模块搬家了？")
        failed = True
    if thin:
        print("[common-jvm-api] FAIL — 这些扫描根文件数明显变少（整根消失的第二种写法）：")
        for t in thin:
            print("  · " + t)
        failed = True
    if scanned < MIN_FILES:
        print("[common-jvm-api] FAIL — 只扫到 %d 个文件（下限 %d，根存在但匹配不到也算假绿）"
              % (scanned, MIN_FILES))
        failed = True
    if offenders:
        print("[common-jvm-api] FAIL — commonMain/commonTest 里出现 JVM 专有符号"
              "（iOS / Native 目标编不过，Android 与桌面照常绿 ⇒ 静默）：")
        seen = set()
        for rel, sig, snippet in offenders:
            key = (rel, sig)
            if key in seen:
                continue
            seen.add(key)
            reason = next((r for p, s, r in RULES if s == sig), "平台专有包名")
            print("  · %s  [%s]  %s" % (rel, sig, reason))
            print("    " + snippet)
        print("  改法：换成 common 写法（见每条后面的提示）；确实只能在 JVM 上做的，"
              "放进 jvmMain/jvmSharedMain 并让 common 侧走 expect/actual。")
        failed = True

    stale = []
    for key, _reason in ALLOWED.items():
        rel = key.rsplit("::", 1)[0]
        if not os.path.isfile(os.path.join(base, rel)):
            stale.append(key + "（文件已不存在）")
    if stale:
        print("[common-jvm-api] FAIL — 允许清单陈旧：")
        for s in stale:
            print("  · " + s)
        failed = True

    if failed:
        return 1
    print("[common-jvm-api] OK — 扫描 %d 个 common 文件，0 处 JVM 专有 API（允许清单 %d 条）"
          % (scanned, len(ALLOWED)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
