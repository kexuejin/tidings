#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""串行化的单帧渲染器：把指定的 @PreviewTest 渲染成 reference PNG，供并排比对。

## 为什么需要它

`./gradlew :androidApp:updateDebugScreenshotTest --tests <类>.<帧>*` 能只渲染一帧
（实测 ~16s，且**逐字节幂等**：同一帧重录后 sha256 不变，所以我敢把它当"拍照"用）。

但同一个 checkout 的 build 目录**不能被两个执行单元同时写**。多人/多 agent 并行适配时，
各自随手敲 gradle 会撞锁（或把彼此的在途产物固化成"正常"）。这个脚本用 fcntl.flock
把渲染串行化：谁拿到锁谁跑，其余排队等，不会并发写 build 目录。

## 用法

    python3 tools/render-preview.py --list                 # 已知 @PreviewTest → reference 路径
    python3 tools/render-preview.py --preview CabinetPeerPhonePreview
    python3 tools/render-preview.py --preview A --preview B
    python3 tools/render-preview.py --filter 'app.relay.app.screenshot.ScreenshotPreviewsKt.Cabinet*'
    python3 tools/render-preview.py --preview X --sha     # 渲染前后打 sha，证明幂等

`--preview` 接受**不带 @PreviewTest 前缀的真实函数名**（脚本自己去源码里找它属于哪个类）。

## 判据

- 渲染后必须能**在磁盘上找到**对应的 reference PNG，否则报红（"渲染成功但没出图"是
  与"夹具欠武装"同族的静默失败）。
- 一次渲染只许改自己点名的那些帧：脚本渲染前后各打一次全量 sha 清单，
  报告**被改写 / 被删除 / 新增**各几张，与点名数不符就报红。

## 渲染前置自检（--no-precheck 可关）

本仓最高频的"编译红"是同一形状：**用了 `Res.string.X` / `Res.drawable.X` 却没写那一行
import**（`Res.*` 在 Kotlin 里是扩展属性，每个名字一行 import）。截图源集是全量编译，
所以任一人漏一行 import，**五个人的渲染全被挡住**，而症状是 `Unresolved reference` ——
极易被误诊成"key 没落地"（其实 key 在 XML 与生成物里都在）。

自检在**拿到渲染锁之前**跑，不碰 gradle，约 0.2s，逐条报 `文件:行 名字 类型`，
并区分两种成因：**缺 import**（key 已声明）还是**缺声明**（key 根本不存在）。
"""

from __future__ import annotations

import argparse
import fcntl
import glob
import hashlib
import os
import re
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_DIRS = [
    "androidApp/src/screenshotTest/kotlin",
    "androidApp/src/screenshotTestDebug/kotlin",
]
REF_ROOT = "androidApp/src/screenshotTestDebug/reference"
LOCK_PATH = os.path.join(REPO, "build", ".render-preview.lock")
JAVA_HOME = os.environ.get("RELAY_JAVA_HOME", "/opt/homebrew/opt/openjdk@21")
GRADLE_TIMEOUT = int(os.environ.get("RELAY_RENDER_TIMEOUT", "1800"))


class RenderError(RuntimeError):
    pass


def sha(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def snapshot_refs() -> dict:
    out = {}
    for p in glob.glob(os.path.join(REPO, REF_ROOT, "**", "*.png"), recursive=True):
        out[os.path.relpath(p, REPO)] = sha(p)
    return out


PREVIEW_RE = re.compile(
    r"@PreviewTest\b(?:(?!@PreviewTest).)*?@Composable\s*\n\s*(?:private\s+|internal\s+|public\s+)?fun\s+(\w+)\s*\(",
    re.S,
)


def preview_index() -> dict:
    """@PreviewTest 函数名 → 它所在的类全名（file 名 + Kt）。"""
    idx = {}
    for d in SRC_DIRS:
        for path in glob.glob(os.path.join(REPO, d, "**", "*.kt"), recursive=True):
            src = open(path, encoding="utf-8").read()
            pkg = re.search(r"^package\s+([\w.]+)", src, re.M)
            pkg = pkg.group(1) if pkg else ""
            cls = os.path.basename(path)[:-3] + "Kt"
            for name in PREVIEW_RE.findall(src):
                idx.setdefault(name, (f"{pkg}.{cls}" if pkg else cls, os.path.relpath(path, REPO)))
    return idx


def refs_for_class(cls: str) -> list:
    d = os.path.join(REPO, REF_ROOT, *cls.split("."))
    if not os.path.isdir(d):
        # 类名可能不带包路径前缀
        hits = glob.glob(os.path.join(REPO, REF_ROOT, "**", cls, "*.png"), recursive=True)
        return sorted(hits)
    return sorted(glob.glob(os.path.join(d, "*.png")))


# ---- 渲染前置自检：Res.* 扩展属性的 import 与声明 ----

RES_USAGE_RE = re.compile(r"\bRes\.(string|drawable|plurals|array|font)\.([A-Za-z_]\w*)")
RES_IMPORT_RE = re.compile(r"^import\s+app\.relay\.generated\.resources\.([A-Za-z_]\w*)", re.M)
RES_WILDCARD_RE = re.compile(r"^import\s+app\.relay\.generated\.resources\.\*", re.M)
BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.S)
LINE_COMMENT_RE = re.compile(r"//[^\n]*")
RES_SRC_GLOBS = [
    "composeApp/src/*/kotlin",
    "core/src/*/kotlin",
]
RES_ROOT = "composeApp/src/commonMain/composeResources"


def strip_comments(src: str) -> str:
    src = BLOCK_COMMENT_RE.sub(lambda m: "\n" * m.group(0).count("\n"), src)
    return LINE_COMMENT_RE.sub("", src)


def declared_resources() -> set:
    """composeResources 里真实声明过的资源名（values*/*.xml 的 name= + 各资源目录的文件名）。"""
    names = set()
    for xml in glob.glob(os.path.join(REPO, RES_ROOT, "values*", "*.xml")):
        text = open(xml, encoding="utf-8").read()
        for m in re.finditer(r"<(string|plurals|string-array|integer-array)\s+name=\"([^\"]+)\"", text):
            names.add(m.group(2))
    for d in sorted(glob.glob(os.path.join(REPO, RES_ROOT, "*"))):
        if not os.path.isdir(d):
            continue
        base = os.path.basename(d)
        if base == "values" or base.startswith("values-"):
            continue
        for f in glob.glob(os.path.join(d, "*")):
            names.add(os.path.splitext(os.path.basename(f))[0])
    return names


def res_precheck() -> list:
    """返回 [(相对路径, 行号, 名字, 类型, 缺import, 缺声明)]，按文件+行排序。"""
    declared = declared_resources()
    rows = []
    for pattern in RES_SRC_GLOBS:
        for path in sorted(glob.glob(os.path.join(REPO, pattern, "**", "*.kt"), recursive=True)):
            src = strip_comments(open(path, encoding="utf-8").read())
            used = {}
            for i, line in enumerate(src.splitlines(), 1):
                for m in RES_USAGE_RE.finditer(line):
                    used.setdefault(m.group(2), (m.group(1), i))
            if not used:
                continue
            imported = set(RES_IMPORT_RE.findall(src))
            wildcard = bool(RES_WILDCARD_RE.search(src))
            rel = os.path.relpath(path, REPO)
            for name, (kind, line) in sorted(used.items()):
                miss_import = (not wildcard) and name not in imported
                miss_decl = name not in declared
                if miss_import or miss_decl:
                    rows.append((rel, line, name, kind, miss_import, miss_decl))
    return sorted(rows)


def report_precheck() -> int:
    rows = res_precheck()
    if not rows:
        print("[precheck] OK — Res.* 的 import 与声明都齐", flush=True)
        return 0
    print(f"[precheck] FAIL — {len(rows)} 处会让 :composeApp:compileAndroidMain 变红：", file=sys.stderr)
    for rel, line, name, kind, miss_import, miss_decl in rows:
        why = []
        if miss_import:
            why.append(f"缺 import（在文件顶部加一行 `import app.relay.generated.resources.{name}`）")
        if miss_decl:
            why.append(f"缺声明（composeResources 里没有 {kind} `{name}`，要新建而不是补 import）")
        print(f"  {rel}:{line}  Res.{kind}.{name}  →  " + "；".join(why), file=sys.stderr)
    print("  先修完再渲染：截图源集是全量编译，任一人漏一行 import 会挡住所有并行渲染的人。",
          file=sys.stderr)
    return 4


# ---- 参考图重复检测：帧名、量具、配对表都看不出「两张图其实是同一张」 ----

def dup_report() -> int:
    """按 sha256 分组全部 reference PNG，只报重复组。

    为什么单开一把：W4 的 `DeskConversationsPaneEmptyPreview` 把 `sessions` 显式
    传成了非空夹具（该状态本来就空），于是它与 `DeskConversationsPaneNoSelectionPreview`
    **逐字节相同** —— 帧名不同、量具全绿、配对表各占一行，**只有比哈希能发现**。
    「空态帧其实不空」这类错，没有任何名字/契约能表达。
    """
    groups = {}
    for p in glob.glob(os.path.join(REPO, REF_ROOT, "**", "*.png"), recursive=True):
        groups.setdefault(sha(p), []).append(os.path.relpath(p, REPO))
    dups = {h: v for h, v in groups.items() if len(v) > 1}
    total = sum(len(v) for v in groups.values())
    if not dups:
        print(f"OK — {total} 张参考图两两互异（无重复）")
        return 0
    print(f"FAIL — {len(dups)} 组重复参考图（帧名不同、图是同一张 ⇒ 重复帧在冒充两张证据）：")
    for h, files in sorted(dups.items(), key=lambda kv: kv[1][0]):
        print(f"  {h[:12]}  共 {len(files)} 张：")
        for f in sorted(files):
            print(f"    {f}")
    return 1


def run_gradle(filters: list) -> int:
    cmd = ["./gradlew", ":androidApp:updateDebugScreenshotTest", "--console=plain"]
    for f in filters:
        cmd += ["--tests", f]
    env = dict(os.environ)
    env["JAVA_HOME"] = JAVA_HOME
    print(f"[render] gradle {' '.join(filters) if filters else '(全部帧)'}", flush=True)
    try:
        p = subprocess.run(cmd, cwd=REPO, env=env, timeout=GRADLE_TIMEOUT)
    except subprocess.TimeoutExpired:
        raise RenderError(f"gradle 超过 {GRADLE_TIMEOUT}s 未返回")
    return p.returncode


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="串行化单帧渲染器")
    ap.add_argument("--preview", action="append", default=[], help="@PreviewTest 函数名，可重复")
    ap.add_argument("--filter", action="append", default=[], help="直接给 gradle --tests 过滤器")
    ap.add_argument("--list", action="store_true", help="列出 @PreviewTest 与 reference 路径")
    ap.add_argument("--sha", action="store_true", help="渲染前后打全量 sha 清单，核对只动了点名的帧")
    ap.add_argument("--grep", help="--list 时按子串过滤")
    ap.add_argument("--no-precheck", action="store_true", help="跳过渲染前置的 Res.* import 自检")
    ap.add_argument("--precheck", action="store_true", help="只跑 Res.* import 自检，不渲染")
    ap.add_argument("--dup", action="store_true", help="扫全部 reference PNG，只报 md5/sha 重复组")
    args = ap.parse_args(argv)

    idx = preview_index()

    if args.dup:
        return dup_report()

    if args.precheck:
        return report_precheck()

    if args.list:
        rows = sorted(idx.items())
        for name, (cls, path) in rows:
            if args.grep and args.grep not in name:
                continue
            refs = refs_for_class(cls)
            hit = [os.path.relpath(r, REPO) for r in refs if name in os.path.basename(r)]
            print(f"{name}\t{cls}\t{hit[0] if hit else '(无 reference)'}")
        print(f"\n共 {len(rows)} 个 @PreviewTest", file=sys.stderr)
        return 0

    if not args.preview and not args.filter:
        ap.error("至少要给 --preview 或 --filter 或 --list")

    filters = list(args.filter)
    want_refs = []
    for name in args.preview:
        if name not in idx:
            print(f"FAIL — 源码里找不到 @PreviewTest 函数 {name}（--list 看全部）", file=sys.stderr)
            return 2
        cls, src = idx[name]
        filters.append(f"{cls}.{name}*")
        want_refs += [r for r in refs_for_class(cls) if name in os.path.basename(r)]

    if not args.no_precheck:
        rc = report_precheck()
        if rc != 0:
            return rc

    os.makedirs(os.path.dirname(LOCK_PATH), exist_ok=True)
    with open(LOCK_PATH, "w") as lock:
        print("[render] 等渲染锁…", flush=True)
        fcntl.flock(lock, fcntl.LOCK_EX)
        try:
            before = snapshot_refs() if args.sha else {}
            rc = run_gradle(filters)
            if rc != 0:
                print(f"FAIL — gradle 退出码 {rc}", file=sys.stderr)
                return rc
            if args.sha:
                after = snapshot_refs()
                changed = sorted(k for k in after if k in before and after[k] != before[k])
                added = sorted(k for k in after if k not in before)
                removed = sorted(k for k in before if k not in after)
                print(f"[sha] 被改写 {len(changed)} · 新增 {len(added)} · 删除 {len(removed)}")
                for label, group in (("改写", changed), ("新增", added), ("删除", removed)):
                    for k in group[:20]:
                        print(f"  {label}: {k}")
                    if len(group) > 20:
                        print(f"  … 其余 {len(group) - 20} 条省略")
        finally:
            fcntl.flock(lock, fcntl.LOCK_UN)

    produced = []
    for name in args.preview:
        cls, _ = idx[name]
        produced += [r for r in refs_for_class(cls) if name in os.path.basename(r)]
    for r in produced:
        print(f"OK  {os.path.relpath(r, REPO)}")
    missing = [n for n in args.preview
               if not any(n in os.path.basename(r) for r in refs_for_class(idx[n][0]))]
    if missing:
        print(f"FAIL — 渲染完却找不到 reference 的帧：{missing}", file=sys.stderr)
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main())
