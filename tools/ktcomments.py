#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Kotlin 注释剥离的唯一实现 —— 设计量具家族共用（design_contract 等 import 本模块）。

## 为什么收敛成一份（2026-11-07，第四十九个洞的同族修正）

此前四个量具（design_contract / design-screen-diff / render-preview /
nullable-callback-wiring）各自用「先剥块注释、再剥行注释」的两遍正则实现。
块注释正则不认识字符串与行注释：`DesktopComposer.kt` 的行注释里有
`arrayOf("*/*")`，其中 `/*` 被当成块注释开点，把到下一个 `*/` 的约 280 行
真实代码整段吞成空白 —— 3 处间距字面量对棘轮不可见，增删不推动棘轮。
症状是「同一个文件，独立扫描 4 处、量具只数到 1 处」，且没有任何报错。

## 契约

- **单遍状态机**：在任意位置只可能是 字符串/字符字面量 → 行注释 → 块注释 → 普通字符。
- **行号不变**：块注释按其占的行数换成换行符；行注释从 `//` 起丢弃、换行保留。
- **字符串原样保留**：`"https://x"`、`'*'`、`arrayOf("*/*")` 都不会被当成注释起点。
- 字符串状态**每行复位**：Kotlin 普通字符串不跨行，漏配引号最多污染本行，
  不许把后半文件当成字符串吞掉。`\"\"\"` 三引号不在量具扫描根出现（只出现在
  测试夹具，而测试被 SCAN_EXCLUDE 排除）；即使出现也只是其内容按代码看待，不会崩。
"""

from __future__ import annotations


def strip_comments(src: str) -> str:
    """去掉 Kotlin 注释，保住行号与字符串内容。全仓量具只此一份实现。"""
    out: list[str] = []
    i = 0
    n = len(src)
    in_str = False
    quote = ""
    while i < n:
        c = src[i]
        if in_str:
            out.append(c)
            if c == "\\" and i + 1 < n:
                out.append(src[i + 1])
                i += 2
                continue
            if c == quote or c == "\n":
                in_str = False
            i += 1
            continue
        if c in "\"'":
            in_str = True
            quote = c
            out.append(c)
            i += 1
        elif c == "/" and src[i + 1:i + 2] == "/":
            j = src.find("\n", i)
            if j == -1:
                break
            i = j  # 换行本身留在原文里，下一轮原样输出
        elif c == "/" and src[i + 1:i + 2] == "*":
            j = src.find("*/", i + 2)
            end = n if j == -1 else j + 2
            out.append("\n" * src.count("\n", i, end))
            i = end
        else:
            out.append(c)
            i += 1
    return "".join(out)


_SELF_TEST = [
    # (输入, 期望输出)。重点：字符串里的 `/*`、`//` 不开注释；行注释里的 `/*` 不吞码。
    ('val a = arrayOf("*/*") // 注释里的 /* 开点\nval b = 1\nval c = 2\n',
     'val a = arrayOf("*/*") \nval b = 1\nval c = 2\n'),
    ('val url = "https://example.com" // 协议不腰斩\nval x = 8.dp\n',
     'val url = "https://example.com" \nval x = 8.dp\n'),
    ('/* 块注释第一行\n   第二行 300.dp */\nval k = 12.dp\n',
     '\n\nval k = 12.dp\n'),
    ('val ch = \'/\' // 字符字面量里的斜杠\nval y = 4\n',
     "val ch = '/' \nval y = 4\n"),
    ('val esc = "a\\"b // 不是注释"\nval z = 2\n',
     'val esc = "a\\"b // 不是注释"\nval z = 2\n'),
    ('val tail = 1\n// 文件末尾行注释没有换行',
     'val tail = 1\n'),
    ('/* 未闭合块注释吞到文件尾\nval dead = 1\n',
     '\n\n'),
]


def self_test() -> None:
    for idx, (src, want) in enumerate(_SELF_TEST, 1):
        got = strip_comments(src)
        assert got == want, f"用例 {idx} 不符:\n  输入: {src!r}\n  期望: {want!r}\n  实得: {got!r}"


if __name__ == "__main__":
    self_test()
    print("ktcomments self-test: 全部通过")
