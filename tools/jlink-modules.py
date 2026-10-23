#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""打包镜像必须含代码用到的 JDK 模块（jlink module gate）。

## 为什么需要它

jpackage 用 jlink 产出的运行时镜像**只含一组默认模块**，代码里用到而没在
`nativeDistributions.modules(...)` 里声明的 JDK 包，在镜像里**根本不存在**。

这类缺失的恶劣之处在于它**只在打包产物里出现、而且失败是静默的**：

* `./gradlew :desktopApp:run` 走宿主 JDK 的全量模块，本地怎么跑都是绿的；
* 镜像里缺模块时抛的是 `NoClassDefFoundError`，而桌面端**刻意**在
  `DesktopExtension.syncOnce()` 里写了 `catch (t: Throwable)`（控制面是加装能力，
  绑不上不该拖垮整个 UI）——于是异常被吞掉：界面完全正常、UDP 17878 / TCP 17879
  照常绑定，只有 8788 永远不监听。已实测过一次：装机版 `捎话 Tidings.app` 里
  `jdk.httpserver` 缺失，手机永远看不到这台机器，而所有日志都是干净的。

所以判据不能靠"跑起来试试"，必须直接对**打包产物**做静态核对。

## 判据

`required` = 用 jdeps 扫**本仓库自己产出并打进镜像的那些 jar**（agentNode /
composeApp / core / desktopApp）得到的 JDK 模块集合；
`present` = `jimage list` 读镜像的 `lib/modules` 得到的模块集合。
要求 `required ⊆ present`，缺哪个就报哪个。

只扫自己产出的 jar，是刻意的：第三方 jar 里有大量**走不到的可选 JDK 引用**
（实测 ktor-utils → java.management、kotlin-stdlib → jdk.unsupported、
jbr-api → java.instrument，这三者在镜像里都不存在而应用工作正常）。把它们纳入
判据就必须挂一张"永久豁免名单"，而豁免名单会长大、会长成没人再读的噪声；
只覆盖自己代码的判据没有豁免项，红了就是真的少一个模块。

**这是一条单向不变式，不是棘轮**：镜像里有而代码没用的模块（例如 java.sql
来自 sqlite 驱动）不算问题，不报。
"""
import argparse
import os
import re
import subprocess
import sys

# 镜像布局：jpackage 在 macOS 上产出 *.app 包（多一层 Contents/），
# 在 Linux / Windows 上产出普通目录。两种都要认，否则换平台跑这条门禁会
# 报"不是 jlink 产出的镜像"，把布局差异说成代码缺陷。
LAYOUTS = [
    # macOS: <image>/Contents/runtime/Contents/Home/lib/modules + <image>/Contents/app
    (os.path.join("Contents", "runtime", "Contents", "Home", "lib", "modules"),
     os.path.join("Contents", "app")),
    # Linux / Windows: <image>/runtime/lib/modules + <image>/app
    (os.path.join("runtime", "lib", "modules"), "app"),
]


def resolve_layout(image_dir):
    """返回 (lib_modules 路径, app 目录路径)。

    布局不匹配时返回 (None, 试过的相对路径列表)，由调用方报"这不是
    jpackage 镜像"，并指出到底在找什么。
    """
    tried = []
    for modules_rel, jars_rel in LAYOUTS:
        modules_file = os.path.join(image_dir, modules_rel)
        if os.path.isfile(modules_file):
            return modules_file, os.path.join(image_dir, jars_rel)
        tried.append(modules_rel)
    return None, tried


def discover_image(root):
    """从 createDistributable 的输出根目录里找出那个应用镜像。

    返回 (镜像路径, 错误行列表)。**刻意不把产品名（"捎话 Tidings"）写进判据**：
    产品改名是正常操作，门禁不该跟着改名，也不该因此静默失效。
    0 个 ⇒ 报"先打包"；多于 1 个 ⇒ 报出来让人选，不许猜。
    """
    if not os.path.isdir(root):
        return None, ["找不到打包输出目录：%s\n     先跑 ./gradlew :desktopApp:createDistributable" % root]
    found = []
    for entry in sorted(os.listdir(root)):
        candidate = os.path.join(root, entry)
        if not os.path.isdir(candidate):
            continue
        modules_file, _ = resolve_layout(candidate)
        if modules_file is not None:
            found.append(candidate)
    if not found:
        return None, ["%s 下没有 jpackage 产出的镜像（找过 %d 个条目）" % (root, len(os.listdir(root)))]
    if len(found) > 1:
        return None, ["%s 下有多个镜像，无法确定该检查哪个：%s" % (root, "、".join(found))]
    return found[0], []


def module_list_from_jimage(text):
    """从 jimage list 的输出里取出模块名集合。

    行形如 `Module: java.base`；其余行（资源条目）忽略。
    """
    names = set()
    for line in text.splitlines():
        m = re.match(r"^\s*Module:\s*(\S+)\s*$", line)
        if m:
            names.add(m.group(1))
    return names


def parse_module_deps(text):
    """jdeps --print-module-deps 的输出是逗号分隔的一行。"""
    joined = " ".join(text.split())
    if not joined:
        return set()
    return {part.strip() for part in joined.split(",") if part.strip()}


def split_jdeps_output(text):
    """jdeps 把模块依赖与警告分在不同行。

    返回 (模块集合, 是否出现 jdeps 自身错误)。标准用法下 `-q` 只剩一行，
    但多 jar 时末尾可能跟 warning；出现 `Error`/`错误` 时必须判红，
    否则一次失败的 jdeps 会被当成"没有依赖"从而假绿。
    """
    modules = set()
    failed = False
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        if re.search(r"\bError\b|错误", stripped):
            failed = True
            continue
        if "->" in stripped or stripped.startswith("warning"):
            continue
        modules |= parse_module_deps(stripped)
    return modules, failed


def pick_own_jars(jar_names, prefixes):
    picked = [n for n in jar_names
              if any(n.startswith(p + "-") or n == p + ".jar" for p in prefixes)]
    return sorted(picked)


def run(cmd):
    proc = subprocess.run(cmd, capture_output=True, text=True)
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


def check(image_dir, jdk_bin, prefixes, verbose=True):
    """返回 (ok, 行列表)。所有失败路径都带可执行的原因说明。"""
    lines = []

    if not os.path.isdir(image_dir):
        return False, ["找不到应用镜像：%s\n     先跑 ./gradlew :desktopApp:createDistributable" % image_dir]
    modules_file, jars_dir = resolve_layout(image_dir)
    if modules_file is None:
        return False, [
            "在 %s 里找不到 jlink 镜像的 lib/modules（试过：%s）" % (image_dir, "、".join(jars_dir)),
            "     这不是 jpackage 产出的镜像？先跑 ./gradlew :desktopApp:createDistributable",
        ]
    if not os.path.isdir(jars_dir):
        return False, ["镜像里没有 app 目录：%s" % jars_dir]

    jimage = os.path.join(jdk_bin, "jimage")
    jdeps = os.path.join(jdk_bin, "jdeps")
    for tool in (jimage, jdeps):
        if not os.path.isfile(tool):
            return False, ["找不到 JDK 工具 %s\n     用 JAVA_HOME=<jdk21> 跑这条门禁" % tool]

    rc, out = run([jimage, "list", modules_file])
    if rc != 0:
        return False, ["jimage list 失败（%d）：%s" % (rc, out.strip())]
    present = module_list_from_jimage(out)
    if not present:
        return False, ["jimage list 没解析出任何模块 —— 输出格式变了，判据会变成空转，必须修"]

    jar_names = [n for n in os.listdir(jars_dir) if n.endswith(".jar")]
    own = pick_own_jars(jar_names, prefixes)
    if not own:
        return False, [
            "镜像的 app 目录里没找到本仓库自己的 jar（前缀 %s）" % ", ".join(prefixes),
            "    匹配到 0 个就判红：不然判据会静默变成空集，门禁永远绿。",
            "    app 目录现有 jar：%s" % (", ".join(sorted(jar_names)[:6]) + " …"),
        ]

    rc, out = run([jdeps, "--print-module-deps", "--ignore-missing-deps", "-q"] +
                  [os.path.join(jars_dir, n) for n in own])
    required, failed = split_jdeps_output(out)
    if failed or rc != 0:
        return False, ["jdeps 没跑成功（%d）：%s" % (rc, out.strip()[:400])]
    if not required:
        return False, ["jdeps 一个模块都没解析出来 —— 判据会变成空转，必须修"]

    missing = sorted(required - present)
    if verbose:
        lines.append("镜像模块 %d 个：%s" % (len(present), ", ".join(sorted(present))))
        lines.append("自有 jar %d 个：" % len(own))
        for n in own:
            lines.append("    " + n)
        lines.append("代码依赖模块 %d 个：%s" % (len(required), ", ".join(sorted(required))))
    if missing:
        lines.append("")
        lines.append("缺 %d 个模块：%s" % (len(missing), ", ".join(missing)))
        lines.append("")
        lines.append("修法：在 desktopApp/build.gradle.kts 的 nativeDistributions 里")
        lines.append("      modules(%s)" % ", ".join('"%s"' % m for m in
                                                 sorted(set(missing) | {"java.sql", "jdk.httpserver"})))
        lines.append("为什么必须显式声明：jpackage 的默认模块集不含它们，而缺了只在")
        lines.append("打包产物里抛 NoClassDefFoundError，且会被桌面端刻意的容错吞掉。")
        return False, lines
    lines.append("OK：代码用到的 JDK 模块在镜像里都有。")
    return True, lines


def self_test():
    """变异验证：把已知缺陷放回去，必须判红。"""
    cases = []

    def case(name, ok_want, fn):
        cases.append((name, ok_want, fn))

    # 1. 基础解析
    def _parse():
        got = module_list_from_jimage("Module: java.base\nModule: jdk.httpserver\nfoo/bar.txt\n")
        assert got == {"java.base", "jdk.httpserver"}, got
        assert parse_module_deps("java.base,jdk.httpserver\n") == {"java.base", "jdk.httpserver"}
        assert parse_module_deps("") == set()
        return True
    case("jimage/jdeps 输出解析", True, _parse)

    # 2. 缺模块必须被报出来（这就是真实缺陷的形态）
    def _missing():
        present = module_list_from_jimage("Module: java.base\nModule: java.desktop\n")
        required = parse_module_deps("java.base,java.desktop,jdk.httpserver")
        return sorted(required - present) == ["jdk.httpserver"]
    case("缺 jdk.httpserver 能被算出", True, _missing)

    # 3. 多出来的模块不算问题（java.sql 来自 sqlite 驱动）
    def _extra():
        present = module_list_from_jimage("Module: java.base\nModule: java.sql\n")
        required = parse_module_deps("java.base")
        return sorted(required - present) == []
    case("镜像多一个模块不算失败", True, _extra)

    # 4. jdeps 报错不能被当成"没有依赖"
    def _jdeps_error():
        _, failed = split_jdeps_output("Error: 找不到模块\n")
        return failed is True
    case("jdeps 出错判红", True, _jdeps_error)

    # 5. jar 前缀匹配：中文/空格包名不能误配
    def _pick():
        names = ["agentNode-abc.jar", "composeApp-jvm-abc.jar", "core-jvm-abc.jar",
                 "desktopApp-abc.jar", "ktor-utils-jvm-1.jar", "skiko-awt-0.9.jar"]
        got = pick_own_jars(names, ["agentNode", "composeApp", "core", "desktopApp"])
        assert "ktor-utils-jvm-1.jar" not in got, got
        assert "skiko-awt-0.9.jar" not in got, got
        assert len(got) == 4, got
        return True
    case("只挑自己产出的 jar", True, _pick)

    # 6. 空集必须判红（判据空转 = 门禁永远绿）
    def _empty():
        return pick_own_jars(["ktor-utils-jvm-1.jar"], ["agentNode", "composeApp"]) == []
    case("匹配 0 个自有 jar 是失败条件", True, _empty)

    # 7. 两种镜像布局都要认（macOS .app 与 Linux/Windows 平铺目录）
    def _layout(create_mac, create_flat, want):
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            if create_mac:
                mac_lib = os.path.join(tmp, "Contents", "runtime", "Contents", "Home", "lib")
                os.makedirs(mac_lib)
                open(os.path.join(mac_lib, "modules"), "w").close()
                os.makedirs(os.path.join(tmp, "Contents", "app"))
            if create_flat:
                os.makedirs(os.path.join(tmp, "runtime", "lib"))
                open(os.path.join(tmp, "runtime", "lib", "modules"), "w").close()
                os.makedirs(os.path.join(tmp, "app"))
            found_modules, found_jars = resolve_layout(tmp)
            if want == "none":
                return found_modules is None
            # 判的是解析出来的 app 目录是否落在预期的布局里（found_jars 是绝对路径）。
            return found_jars is not None and os.path.normpath(found_jars) == os.path.normpath(
                os.path.join(tmp, want))
    case("认 macOS .app 布局", True, lambda: _layout(True, False, os.path.join("Contents", "app")))
    case("认 Linux/Windows 平铺布局", True, lambda: _layout(False, True, "app"))
    case("两种都没有时判红", True, lambda: _layout(False, False, "none"))

    # 8. 输出根目录里自动找出镜像：认得出、多镜像不许猜、空目录要报红
    def _discover(many, empty, want):
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            if not empty:
                for name in (["A.app"] if not many else ["A.app", "B.app"]):
                    lib = os.path.join(tmp, name, "Contents", "runtime", "Contents", "Home", "lib")
                    os.makedirs(lib)
                    open(os.path.join(lib, "modules"), "w").close()
                    os.makedirs(os.path.join(tmp, name, "Contents", "app"))
            found, _ = discover_image(tmp)
            if want == "one":
                return found is not None and os.path.basename(found) == "A.app"
            return found is None
    case("自动发现单个镜像", True, lambda: _discover(False, False, "one"))
    case("多镜像时报错不猜", True, lambda: _discover(True, False, "none"))
    case("空输出目录报错", True, lambda: _discover(False, True, "none"))

    failed = 0
    for name, want, fn in cases:
        try:
            got = fn()
        except Exception as exc:  # noqa: BLE001
            got = "异常：%r" % (exc,)
        ok = got is want
        if not ok:
            failed += 1
        print("  [%s] %s -> %r" % ("OK" if ok else "FAIL", name, got))
    print("[jlink-modules] 自检：%d/%d 通过" % (len(cases) - failed, len(cases)))
    return failed == 0


def main(argv):
    ap = argparse.ArgumentParser(description="打包镜像必须含代码用到的 JDK 模块")
    ap.add_argument("--image", help="应用镜像目录（macOS 的 *.app）")
    ap.add_argument("--image-root",
                    help="createDistributable 的输出根目录，自动找出其中的镜像")
    ap.add_argument("--jdk-bin", help="含 jimage/jdeps 的 bin 目录，默认 $JAVA_HOME/bin")
    ap.add_argument("--own-prefix", action="append", default=[],
                    help="本仓库自己的 jar 前缀，可重复")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args(argv)

    if args.self_test:
        return 0 if self_test() else 1

    if not args.check:
        ap.error("要么 --check，要么 --self-test")
    if bool(args.image) == bool(args.image_root):
        ap.error("--check 需要 --image 或 --image-root（二选一）")

    jdk_bin = args.jdk_bin or os.path.join(os.environ.get("JAVA_HOME", ""), "bin")
    image = args.image
    if args.image_root:
        image, errors = discover_image(args.image_root)
        if image is None:
            print("\n".join(errors))
            return 1
    # `app` 是本仓自己的领域层模块（它已经被绑进桌面镜像）。**漏掉它不会报错** ——
    # jdeps 只是少扫一个 jar，于是"领域层用到的 JDK 模块"从不参与校验，
    # 而那正是最容易新增 JDK 依赖的一层（序列化、加密、文件系统）。
    ok, lines = check(image, jdk_bin,
                      args.own_prefix or ["agentNode", "app", "composeApp", "core", "desktopApp"])
    print("\n".join(lines))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
