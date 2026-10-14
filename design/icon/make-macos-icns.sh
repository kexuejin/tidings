#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
#
# 生成 macOS 应用图标 `Tidings.icns`（供 `desktopApp/build.gradle.kts` 的
# `nativeDistributions.macOS { iconFile.set(...) }` 引用）。
#
# 为什么需要这个脚本，而不是直接把 `icon-1024.png` 丢给 iconutil：
# `icon-1024.png` 是给 Android / iOS 用的**满幅方形**图 —— 圆角之外那圈是米白
# `#FFFEF8`（不透明），因为那两个平台会自己按启动器/系统的遮罩去裁。macOS **不裁**：
# 把那张图原样塞进 .icns，Dock 里就是一个带米白角的方块。所以要三步：
#
#   1. 抠底 —— 从四个角 flood fill 把米白底变成透明，再收缩 2px 去掉 flood fill
#      留在圆角边缘的一圈浅色描边（flood fill 按"与种子色的差"判断，永远停在
#      抗锯齿的过渡像素上，所以那圈描边不靠调 fuzz 能去掉，只能靠收缩）。
#   2. 上栅格 —— 缩到 824/1024 居中。这是 Apple Big Sur 起的图标栅格（四周留
#      100px 给投影），也是 Compose 自带的 `default-icon-mac.icns` 用的比例；
#      不留边的话本应用图标在 Dock 里会比邻座明显大一圈。再叠一层标准投影。
#   3. 出 icns —— sips 生成 iconset 需要的 10 个尺寸，iconutil 打包。
#
# 中间产物 `Tidings.iconset/` 是可重生成的，已在 `.gitignore` 里忽略；仓库里只留
# `relay-macos-1024.png`（主图，便于比对与重生成）与 `Tidings.icns`（交付物）。
#
# 依赖：macOS 自带 `sips` / `iconutil`，以及 ImageMagick 7（`magick`）。
# 用法：`bash design/icon/make-macos-icns.sh`（在仓库根跑即可，脚本自己 cd）。

set -euo pipefail
cd "$(dirname "$0")"

SRC=icon-1024.png              # 满幅方形源图（圆角外为米白底）
MASTER=relay-macos-1024.png    # 抠底 + 上栅格 + 投影后的主图
ICONSET=Tidings.iconset          # 中间产物，.gitignore 已忽略
ICNS=Tidings.icns                # 交付物

CREAM='#FFFEF8'                # icon-1024.png 圆角外的底色
GRID_ART=824                   # Apple Big Sur 图标栅格：1024 画布内 824 的艺术区
ERODE=2                        # 抠底后收缩的像素数（吃掉 flood fill 残留的浅色描边）
SHADOW='30x16+0+12'            # 标准投影：30% 不透明度、sigma 16、下移 12px

for bin in magick sips iconutil; do
  command -v "$bin" >/dev/null || { echo "缺少依赖：$bin" >&2; exit 1; }
done

W=$(magick identify -format '%w' "$SRC")
H=$(magick identify -format '%h' "$SRC")
[ "$W" = "$H" ] || { echo "源图必须是正方形，实际 ${W}x${H}" >&2; exit 1; }

echo "[1/3] 抠底：${W}x${H} 源图 → 四角米白转透明 + 收缩 ${ERODE}px"
magick "$SRC" -alpha set -channel RGBA -fuzz 12% -fill none \
  -draw "alpha 0,0 floodfill" \
  -draw "alpha $((W - 1)),0 floodfill" \
  -draw "alpha 0,$((H - 1)) floodfill" \
  -draw "alpha $((W - 1)),$((H - 1)) floodfill" \
  -channel A -morphology Erode "Disk:${ERODE}" +channel \
  -channel A -blur 0x0.6 +channel \
  /tmp/relay-icon-cutout.png

echo "[2/3] 上栅格：缩到 ${GRID_ART}/${W} 居中 + 投影 → ${MASTER}"
magick /tmp/relay-icon-cutout.png \
  -resize "${GRID_ART}x${GRID_ART}" -background none -gravity center -extent "${W}x${H}" \
  \( +clone -background '#000000' -shadow "$SHADOW" \) +swap \
  -background none -layers merge +repage \
  -background none -gravity center -extent "${W}x${H}" \
  "$MASTER"
rm -f /tmp/relay-icon-cutout.png

echo "[3/3] 出 icns：10 个尺寸 → ${ICNS}"
rm -rf "$ICONSET"
mkdir -p "$ICONSET"
# iconset 要求的 10 个文件名 → 像素尺寸。成对写，避免手滑错位。
while read -r name px; do
  sips -z "$px" "$px" "$MASTER" --out "$ICONSET/$name" >/dev/null
done <<'SIZES'
icon_16x16.png 16
icon_16x16@2x.png 32
icon_32x32.png 32
icon_32x32@2x.png 64
icon_128x128.png 128
icon_128x128@2x.png 256
icon_256x256.png 256
icon_256x256@2x.png 512
icon_512x512.png 512
icon_512x512@2x.png 1024
SIZES

iconutil -c icns "$ICONSET" -o "$ICNS"

echo "完成：$(shasum -a 256 "$ICNS")"
echo "主图：$(shasum -a 256 "$MASTER")"
