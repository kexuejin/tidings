#!/usr/bin/env python3
"""夹具的 RELIABLE_TYPES 必须与 Kotlin MsgTypes.RELIABLE 逐项相等。

漏掉一种的后果不是说报错，是对方按 ackRetrySchedule 重传到队列剪枝 ——
看起来只是「慢」（见 scripts/e2e/relay_e2e.py 里那条注释）。此前夹具只有 14 项、
Kotlin 有 26 项，缺 12 项，正是这类静默漂移。
"""

from __future__ import annotations

import ast
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = ROOT / "core/src/commonMain/kotlin/com/kapp/tenon/core/protocol/MessageTypes.kt"
SPECS = ROOT / "core/src/commonMain/kotlin/com/kapp/tenon/core/protocol/MessageSpecs.kt"
FIXTURE = ROOT / "scripts/e2e/relay_e2e.py"


def kotlin_reliable() -> set[str]:
    text = KOTLIN.read_text(encoding="utf-8")
    constants = dict(re.findall(r'const val (\w+) = "([^"]+)"', text))
    # 新格式：MsgTypes.RELIABLE 由 MessageSpecs.all 派生，reliable 位住在表里。
    if SPECS.exists():
        entries = re.findall(
            r"MessageSpec\(type = MsgTypes\.(\w+),.*?reliable = (true|false)",
            SPECS.read_text(encoding="utf-8"),
        )
        if entries:
            values = {constants[name] for name, flag in entries if flag == "true" and name in constants}
            if values:
                return values
    # 旧格式回退：RELIABLE 还是手写的 setOf 块。
    match = re.search(r"val RELIABLE: Set<String> = setOf\((.*?)\n    \)", text, re.S)
    if match is None:
        raise SystemExit("解析失败：找不到 MsgTypes.RELIABLE，也找不到 MessageSpecs 表")
    body = re.sub(r"//[^\n]*", "", match.group(1))
    names = re.findall(r"\b([A-Z][A-Z0-9_]*)\b", body)
    values = {constants[name] for name in names if name in constants}
    if not values:
        raise SystemExit("解析失败：RELIABLE 块里没解析出任何常量")
    return values


def fixture_reliable() -> set[str]:
    tree = ast.parse(FIXTURE.read_text(encoding="utf-8"))
    constants: dict[str, str] = {}
    target: ast.Call | None = None
    for node in tree.body:
        if not isinstance(node, ast.Assign):
            continue
        if isinstance(node.value, ast.Constant) and isinstance(node.value.value, str):
            for name in node.targets:
                if isinstance(name, ast.Name):
                    constants[name.id] = node.value.value
        elif isinstance(node.value, (ast.Tuple, ast.List)) and isinstance(node.targets[0], (ast.Tuple, ast.List)):
            lhs = [e.id for e in node.targets[0].elts if isinstance(e, ast.Name)]
            rhs = [e.value for e in node.value.elts if isinstance(e, ast.Constant) and isinstance(e.value, str)]
            if len(lhs) == len(rhs):
                constants.update(zip(lhs, rhs))
        for name in node.targets:
            if isinstance(name, ast.Name) and name.id == "RELIABLE_TYPES" and isinstance(node.value, ast.Call):
                target = node.value
    if target is None or not target.args or not isinstance(target.args[0], (ast.List, ast.Tuple, ast.Set)):
        raise SystemExit("解析失败：找不到 RELIABLE_TYPES = frozenset([...])")
    values: set[str] = set()
    for element in target.args[0].elts:
        if isinstance(element, ast.Constant) and isinstance(element.value, str):
            values.add(element.value)
        elif isinstance(element, ast.Name) and element.id in constants:
            values.add(constants[element.id])
        else:
            raise SystemExit(f"解析失败：RELIABLE_TYPES 里有认不出的元素 {ast.dump(element)}")
    return values


def main() -> int:
    expected = kotlin_reliable()
    actual = fixture_reliable()
    missing = sorted(expected - actual)
    extra = sorted(actual - expected)
    if missing or extra:
        print(f"Kotlin RELIABLE = {len(expected)} 项，夹具 = {len(actual)} 项")
        if missing:
            print("夹具缺少: " + ", ".join(missing))
        if extra:
            print("夹具多出: " + ", ".join(extra))
        return 1
    print(f"OK: 夹具与 Kotlin RELIABLE 逐项相等（{len(expected)} 项）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
