#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""打包前校验：luciola native 库与 Kotlin bindings 的 UniFFI 契约必须逐项一致。

为什么要这道门禁（2026-11-05 真机事故）
--------------------------------------
`luciola-jvm 0.9.0` 的 `IntegrityCheckingUniffiLib` 在类初始化时逐项调用
`uniffi_luciola_uniffi_checksum_<name>()`，把 native 库返回的 short 与 bindings 里
编译进去的常量比对；不一致就抛 `UniFFI API checksum mismatch`，**桌面端启动即崩**。
当时 `desktopApp/resources/common/native/` 里躺着一份从 luciola 0.10.0 源码编出来的
dylib（3,800,736 B），而 Gradle 依赖是 0.9.0：71 项里有 10 项对不上。
`copyLuciolaNative` 原先只检查「文件存在」，于是一个必崩的安装包被正常产出。

二进制不入库（见 `desktopApp/resources/common/native/README.md`），所以光把本机那份
换掉不能防止复发。这道工具在**打包前**把「存在」升级成「契约一致」：

  * 用 `javap -c -p` 从 bindings jar 读出 71 个期望 checksum 和 contract version；
  * 用 `ctypes` 加载待打包的 native 库，逐项调用同名导出函数比 short；
  * 缺函数 / 值不同 / contract version 不同 ⇒ 非零退出，Gradle 打包直接失败。

用法::

    python3 tools/verify-luciola-native.py \
        --jar /path/to/luciola-jvm-0.9.0.jar \
        --dylib /path/to/libluciola_uniffi.dylib

`desktopApp` 的 `verifyLuciolaNative` 任务会在 `createDistributable` 前自动调用它，
jar 从 `runtimeClasspath` 解析，不需要写死任何个人路径。
"""
from __future__ import annotations

import argparse
import ctypes
import hashlib
import os
import re
import shutil
import subprocess
import sys

INTEGRITY_CLASS = "uniffi.luciola_uniffi.IntegrityCheckingUniffiLib"
# javap 里 `uniffiCheckApiChecksums` 的形状：
#   invokestatic #32 // Method uniffi_luciola_uniffi_checksum_func_x:()S
#   sipush       -15986
CHECKSUM_CALL = re.compile(r"Method\s+(uniffi_luciola_uniffi_checksum_\w+):\(\)S")
SIPUSH = re.compile(r"\bsipush\s+(-?\d+)")
BIPUSH = re.compile(r"\bbipush\s+(-?\d+)")
CONTRACT_FN = "ffi_luciola_uniffi_uniffi_contract_version"


def find_javap(explicit: str | None) -> str:
    if explicit:
        return explicit
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = os.path.join(java_home, "bin", "javap")
        if os.path.isfile(candidate):
            return candidate
    found = shutil.which("javap")
    if not found:
        raise SystemExit(
            "找不到 javap：请设 JAVA_HOME 或 --javap <jdk/bin/javap>。"
            "没有它就无法从 bindings jar 读出期望 checksum，不许跳过校验。"
        )
    return found


def expected_from_jar(javap: str, jar: str) -> tuple[int | None, list[tuple[str, int]]]:
    if not os.path.isfile(jar):
        raise SystemExit(f"bindings jar 不存在：{jar}")
    proc = subprocess.run(
        [javap, "-c", "-p", "-classpath", jar, INTEGRITY_CLASS],
        capture_output=True, text=True,
    )
    if proc.returncode != 0:
        raise SystemExit(f"javap 读不出 {INTEGRITY_CLASS}：{proc.stderr.strip()}")
    lines = proc.stdout.splitlines()
    expected: list[tuple[str, int]] = []
    seen: set[str] = set()
    contract: int | None = None
    for i, line in enumerate(lines):
        m = CHECKSUM_CALL.search(line)
        if m:
            name = m.group(1)
            # sipush 紧跟在 invokestatic 之后（通常就是下一行），给一点余量。
            for nxt in lines[i + 1:i + 4]:
                v = SIPUSH.search(nxt)
                if v:
                    if name not in seen:
                        seen.add(name)
                        expected.append((name, int(v.group(1))))
                    break
        b = BIPUSH.search(line)
        if b and contract is None:
            # `uniffiCheckContractVersion` 里 bipush <version> 后紧接着读 native contract version
            if any(CONTRACT_FN in x for x in lines[i + 1:i + 5]):
                contract = int(b.group(1))
    if not expected:
        raise SystemExit(
            "bindings jar 里一个 checksum 常量都没读到 —— 正则失效或 bindings 换了生成方式，"
            "宁可报错也不许静默放行（空集合会让这道校验永远绿）。"
        )
    return contract, expected


def to_i16(v: int) -> int:
    """bindings 里的 sipush 是 signed short，native 返回的是 unsigned short。"""
    return ctypes.c_int16(v & 0xFFFF).value


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--jar", required=True, help="luciola-jvm-*.jar（bindings，期望值来源）")
    ap.add_argument("--dylib", required=True, help="待打包的 libluciola_uniffi.{dylib,so,dll}")
    ap.add_argument("--javap", default=None, help="javap 路径；默认 JAVA_HOME/bin/javap 或 PATH")
    args = ap.parse_args()

    javap = find_javap(args.javap)
    contract, expected = expected_from_jar(javap, args.jar)

    if not os.path.isfile(args.dylib):
        raise SystemExit(f"native 库不存在：{args.dylib}")
    digest = hashlib.sha256(open(args.dylib, "rb").read()).hexdigest()
    size = os.path.getsize(args.dylib)
    print(f"dylib    = {args.dylib}")
    print(f"sha256   = {digest}")
    print(f"size     = {size}")
    print(f"jar      = {args.jar}")
    print(f"bindings = {len(expected)} 项 checksum, contract version {contract}")

    try:
        lib = ctypes.CDLL(args.dylib)
    except OSError as exc:
        print(f"FAIL 无法加载 native 库：{exc}")
        return 1

    bad: list[tuple[str, object, object]] = []
    cv_fn = getattr(lib, CONTRACT_FN, None)
    if cv_fn is None:
        bad.append((CONTRACT_FN, contract, "MISSING"))
        native_contract = None
    else:
        cv_fn.restype = ctypes.c_uint32
        native_contract = int(cv_fn())
        if contract is not None and native_contract != contract:
            bad.append((CONTRACT_FN, contract, native_contract))

    for name, want in expected:
        fn = getattr(lib, name, None)
        if fn is None:
            bad.append((name, want, "MISSING"))
            continue
        fn.restype = ctypes.c_uint16
        got = to_i16(int(fn()))
        if got != want:
            bad.append((name, want, got))

    print(f"checksums: {len(expected)} checked, {len(bad)} mismatch")
    for item in bad[:20]:
        print("   ", item)
    if len(bad) > 20:
        print(f"    ... 另有 {len(bad) - 20} 项")
    if bad:
        print(
            "VERDICT= MISMATCH\n"
            "native 库与 Kotlin bindings 不是同一个 luciola 版本，装到用户机器上会\n"
            "`UniFFI API checksum mismatch` 启动即崩。请用与 gradle/libs.versions.toml\n"
            "里 luciola 版本对应的源码重新构建，并把 TENON_LUCIOLA_NATIVE 指到它的 release 目录。"
        )
        return 1
    print(f"VERDICT= MATCH (contract version {native_contract})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
