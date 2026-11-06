#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""证明「屏幕/截图里那张二维码」是真码，而不是看起来像码的方块。

用法：
    python3 tools/verify-rendered-qr.py <png> <payload>

它做三件事：
 1. 在 PNG 里找出二维码的位置（靠"很长的竖直深色条"定位 —— 定位图案的竖条有 7 个模块高，
    正文文字的笔画远达不到这个长度）；
 2. 按模块中心采样，还原出屏幕上的矩阵；
 3. 用 **libqrencode**（`qrencode`）对同一载荷独立生成一张，逐模块比对。

为什么值得单独做一遍：`QrEncoderTest` 已经对着 libqrencode 钉住了编码器，但**编码器对了
不等于画出来是对的** —— 少画静区、模块错位半格、深浅反色，都能让一张"正确的码"变成扫不
出来的图，而这类失败在单测里看不出来（Canvas 不是纯函数）。这个脚本补的就是从矩阵到
像素这一段。

依赖：ImageMagick（`magick`）与 libqrencode（`qrencode`），都是本机开发工具，
**不进产品依赖**。没有真机时这是"码能不能扫"最接近的一次验证；有真机时当然还是拿手机扫。
"""

import subprocess
import sys

MIN_RUN = 60          # 定位图案竖条的最小像素高度，用来把二维码和文字笔画区分开
MIN_CELL = 6          # 单个模块的像素下限（太小说明找错东西了）
MAX_CELL = 60
SIZES = (21, 25, 29, 33, 37, 41, 45, 49, 53, 57)  # 版本 1–10 的模块数


def read_pgm(path):
    data = open(path, "rb").read()
    parts, i = [], 0
    while len(parts) < 4:
        while data[i:i + 1].isspace():
            i += 1
        if data[i:i + 1] == b"#":
            while data[i:i + 1] != b"\n":
                i += 1
            continue
        j = i
        while not data[j:j + 1].isspace():
            j += 1
        parts.append(data[i:j])
        i = j
    i += 1
    w, h = int(parts[1]), int(parts[2])
    return w, h, data[i:i + w * h]


def longest_vertical_run(dark, x, height):
    best = run = 0
    for y in range(height):
        run = run + 1 if dark(x, y) else 0
        best = max(best, run)
    return best


def longest_horizontal_run(dark, y, x0, x1):
    best = run = 0
    for x in range(x0, x1 + 1):
        run = run + 1 if dark(x, y) else 0
        best = max(best, run)
    return best


def locate(width, height, px, size):
    """按已知的模块数返回 (left, top, cell)；找不到就返回 None。

    模块数不靠猜：调用方先用 libqrencode 对同一载荷生成参考矩阵，那里就有准确的版本。
    """
    def dark(x, y):
        return px[y * width + x] < 128

    # 定位图案的竖条有 7 个模块高（约 100px），正文文字的笔画远达不到这个长度，
    # 所以"很长的竖直深色条"能把二维码的左右边界圈出来（左侧与右侧各一个定位图案）。
    columns = [x for x in range(width) if longest_vertical_run(dark, x, height) >= MIN_RUN]
    if not columns:
        return None
    left, right = min(columns), max(columns)

    # 上下边界同理：定位图案的横条让最上面 7 行与最下面 7 行出现长横条。
    rows = [y for y in range(height) if longest_horizontal_run(dark, y, left, right) >= MIN_RUN]
    if not rows:
        return None
    top, bottom = min(rows), max(rows)

    w, h = right - left + 1, bottom - top + 1
    if abs(w - h) > 3:
        return None
    cell = w / size
    if not MIN_CELL <= cell <= MAX_CELL:
        return None
    return left, top, cell


def sample(width, px, left, top, size, cell):
    def dark(x, y):
        return px[y * width + x] < 128

    matrix = []
    for row in range(size):
        line = []
        for col in range(size):
            cx = int(left + (col + 0.5) * cell)
            cy = int(top + (row + 0.5) * cell)
            # 3×3 邻域取多数：抗锯齿边缘上单点采样会翻。
            votes = sum(1 for dy in (-2, 0, 2) for dx in (-2, 0, 2) if dark(cx + dx, cy + dy))
            line.append(1 if votes >= 5 else 0)
        matrix.append(line)
    return matrix


def reference(payload):
    out = subprocess.run(
        ["qrencode", "-t", "ASCII", "-m", "0", "-l", "M", "-8", "-o", "-", payload],
        capture_output=True, text=True, check=True,
    ).stdout
    rows = [r for r in out.split("\n") if r]
    return [[1 if r[i * 2:i * 2 + 2] == "##" else 0 for i in range(len(r) // 2)] for r in rows]


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    png, payload = sys.argv[1], sys.argv[2]
    pgm = subprocess.run(
        ["magick", png, "-colorspace", "gray", "-depth", "8", "pgm:-"],
        capture_output=True, check=True,
    ).stdout
    open("/tmp/verify-rendered-qr.pgm", "wb").write(pgm)
    width, height, px = read_pgm("/tmp/verify-rendered-qr.pgm")

    ref = reference(payload)
    size = len(ref)
    if size not in SIZES:
        print(f"libqrencode 给出 {size} 个模块，超出本工具认识的范围")
        return 1

    found = locate(width, height, px, size)
    if not found:
        print(f"没在 {png} 里找到 {size} 个模块的二维码（不是没画，就是画得不像码）")
        return 1
    left, top, cell = found
    print(f"找到二维码：左上 ({left},{top})，{size} 个模块，每格 {cell:.2f}px")

    shot = sample(width, px, left, top, size, cell)
    wrong = [(x, y) for y in range(len(ref)) for x in range(len(ref)) if shot[y][x] != ref[y][x]]
    if wrong:
        print(f"{len(wrong)} 个模块与 libqrencode 不同，前几个：{wrong[:10]}")
        return 1
    print(f"逐模块一致（{len(ref) * len(ref)} 个模块）：这张码扫得出「{payload}」")
    return 0


if __name__ == "__main__":
    sys.exit(main())
