#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""
领域层零 Compose 依赖门禁。

## 为什么这是一个「根项目 Gradle 任务」而不是一条单元测试

它本来是 `:composeApp` 里的一条 jvmTest。那样有个致命缺陷：**跑它要先编译 `:app`**。
于是当有人往领域层插一行 `import androidx.compose.*` 时，`:app:compileKotlinJvm`
先失败——守卫根本轮不到执行。看起来「守卫红了」，实际上红的是编译器；
哪天某个模块临时引进了 Compose 依赖让编译通过，守卫仍然是绿的（它没有机会说话）。

门禁只读源码，不需要任何编译产物。做成根任务的第二个理由与其他源码门禁一致：
它可以被 `./gradlew :domainComposeFreeGate` 单独调用，几秒出结果。

## 两个判据（缺一都是在骗自己）

1. **每个扫描根都必须存在**。写错路径 / 领域层又搬家时，`walk` 会遍历空集合，
   「没有 offender」于是恒真 —— 这是本仓源码门禁最常见的假绿形态。
2. **扫到的文件数必须超过下限**。同一条假绿的第二种写法：根存在但匹配不到文件。

## 允许清单

四个壳层契约文件**必须**依赖 Compose（它们换掉 UI 方案就不存在了），
列进 `INDIVIDUAL_ALLOWED` 并逐条写理由。清单本身也要判陈旧：
清单里的文件若已不再依赖 Compose，门禁变红 —— 否则下一个人会以为那里还有依赖。
"""

from __future__ import annotations

import argparse
import os
import sys

# 领域层源码根（相对仓库根）。**搬模块时这些必须跟着改**，改漏会被判红。
DOMAIN_ROOTS = (
    "app/src/commonMain/kotlin/com/kapp/tidings/domain",
    "app/src/commonMain/kotlin/com/kapp/tidings/db",
    "app/src/commonMain/kotlin/com/kapp/tidings/platform",
    "app/src/commonMain/kotlin/com/kapp/tidings/navigation",
)

# 扫到的文件数下限。当前 58（app/45 + model/4 + db/3 + platform/5 + navigation/1）。
# 取 50 而不是 58：它抓的是「整个根不见了」，不是数准个数。
MIN_FILES = 50

# 必须依赖 Compose 的壳层契约文件，逐条写理由。
INDIVIDUAL_ALLOWED = {
    "composeApp/src/commonMain/kotlin/com/kapp/tidings/platform/CareSystemBars.kt":
        "@Composable expect fun：刷系统栏图标的明暗，本身就是组合函数",
    "composeApp/src/commonMain/kotlin/com/kapp/tidings/platform/ImageDecoding.kt":
        "返回 ImageBitmap —— 它服务的唯一用途就是画出来",
    "composeApp/src/commonMain/kotlin/com/kapp/tidings/platform/QrPreviewProvider.kt":
        "相机取景框是 @Composable，这是它唯一的形态",
    "composeApp/src/commonMain/kotlin/com/kapp/tidings/navigation/AppNavigation.kt":
        "AppNavItem 带 StringResource / DrawableResource（Compose Resources 类型）",
}

COMPOSE_MARKERS = ("androidx.compose", "org.jetbrains.compose")


def compose_imports(text: str) -> list[str]:
    """只看 import 行。注释里提到 Compose 合法（KDoc 常在解释「为什么不引它」）。"""
    out = []
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped.startswith("import "):
            continue
        if any(m in stripped for m in COMPOSE_MARKERS):
            out.append(stripped)
    return out


def scan(base: str) -> tuple[list[str], int, list[str]]:
    offenders: list[str] = []
    scanned = 0
    missing: list[str] = []
    for root in DOMAIN_ROOTS:
        top = os.path.join(base, root)
        if not os.path.isdir(top):
            missing.append(root)
            continue
        for dirpath, dirnames, filenames in os.walk(top):
            dirnames[:] = [d for d in dirnames if d != "build"]
            for fn in filenames:
                if not fn.endswith(".kt"):
                    continue
                full = os.path.join(dirpath, fn)
                rel = os.path.relpath(full, base).replace(os.sep, "/")
                scanned += 1
                with open(full, encoding="utf-8") as fh:
                    hits = compose_imports(fh.read())
                if hits:
                    offenders.append(rel + " -> " + "; ".join(hits))
    return offenders, scanned, missing


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=None, help="仓库根（默认按脚本位置推断）")
    args = ap.parse_args()
    base = os.path.abspath(args.root) if args.root else os.path.abspath(
        os.path.join(os.path.dirname(__file__), "..")
    )

    failed = False
    offenders, scanned, missing = scan(base)

    if missing:
        print("[domain-compose-free] FAIL — 这些扫描根不存在：" + ", ".join(missing))
        print("  守卫读不到目录会静默通过（空集合永远是干净的）。领域层又搬家了？" )
        failed = True
    if scanned < MIN_FILES:
        print("[domain-compose-free] FAIL — 只扫到 %d 个文件（下限 %d）" % (scanned, MIN_FILES))
        failed = True
    if offenders:
        print("[domain-compose-free] FAIL — 领域层出现 Compose import：")
        for o in offenders:
            print("  · " + o)
        print("  改法：做成可注入的桥（见 RelayUiTexts 的形状），或列进 INDIVIDUAL_ALLOWED 并写理由。" )
        failed = True

    # 允许清单不许陈旧：里面的文件若已不再依赖 Compose，清单该缩小了。
    stale = []
    for rel, _reason in INDIVIDUAL_ALLOWED.items():
        full = os.path.join(base, rel)
        if not os.path.isfile(full):
            stale.append(rel + "（文件已不存在）")
            continue
        with open(full, encoding="utf-8") as fh:
            if not compose_imports(fh.read()):
                stale.append(rel + "（已不再依赖 Compose）")
    if stale:
        print("[domain-compose-free] FAIL — 允许清单陈旧：")
        for s in stale:
            print("  · " + s)
        print("  陈旧的允许清单会让下一个人以为那里还有依赖。删掉该条目。" )
        failed = True

    if failed:
        return 1
    print("[domain-compose-free] OK — 扫描 %d 个领域文件，0 处 Compose import（允许清单 %d 条均有效）"
          % (scanned, len(INDIVIDUAL_ALLOWED)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
