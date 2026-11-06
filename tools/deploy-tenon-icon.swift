// tools/deploy-tenon-icon.swift — 「榫卯 Tenon」全平台图标生成与部署脚本
//
// 目标方案：V2 - 45° 粽角斜榫 T (The 45° Mitred Tenon T)
//
// 生成并部署清单：
// 1. Android:
//    - mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png (方圆角)
//    - mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher_round.png (圆形)
//    - drawable/ic_launcher_foreground.png (自适应前景 512px，透明底，安全区内)
//    - 同步更新 design/icon/mipmap-* 目录
// 2. iOS:
//    - iosApp/Assets.xcassets/AppIcon.appiconset/icon-1024.png (1024x1024 满幅实色方图)
// 3. macOS / Desktop:
//    - desktopApp/src/main/resources/icon.png (1024x1024 透明底圆角方标)
//    - design/icon/icon-1024.png (带 #FFFEF8 底板的 1024 图，供 make-macos-icns.sh 处理)
// 4. Vector SVG:
//    - design/icon/relay-icon.svg (标准母版)
//    - design/icon/tenon-icon.svg

import Foundation
import CoreGraphics
import AppKit

let repoRoot = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : FileManager.default.currentDirectoryPath

// 调色盘
let flatGreen = (r: CGFloat(0.129), g: CGFloat(0.337), b: CGFloat(0.243)) // #21563E 浓郁松柏深绿
let flatIvory = (r: CGFloat(0.980), g: CGFloat(0.980), b: CGFloat(0.973)) // #FAFAF8 纯平象牙白
let creamBg   = (r: CGFloat(1.000), g: CGFloat(0.996), b: CGFloat(0.973)) // #FFFEF8 米白（用于 macOS 裁切前底色）

func color(_ c: (r: CGFloat, g: CGFloat, b: CGFloat), alpha: CGFloat = 1.0) -> CGColor {
    CGColor(srgbRed: c.r, green: c.g, blue: c.b, alpha: alpha)
}

func roundedRect(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat, _ r: CGFloat) -> CGPath {
    CGPath(roundedRect: CGRect(x: x, y: y, width: w, height: h), cornerWidth: r, cornerHeight: r, transform: nil)
}

// MARK: - V2 核心几何路径（1000×1000 画布，中心在 500, 500）
func getTenonV2Parts() -> [(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))] {
    var parts: [(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))] = []
    let gap: CGFloat = 16
    
    // 1. 横梁（象牙白）：底边中央切出 45 度倒角斜插口
    let beam = CGMutablePath()
    let bx: CGFloat = 180, by: CGFloat = 220, bw: CGFloat = 640, bh: CGFloat = 160, br: CGFloat = 32
    beam.move(to: CGPoint(x: bx + br, y: by))
    beam.addLine(to: CGPoint(x: bx + bw - br, y: by))
    beam.addArc(tangent1End: CGPoint(x: bx + bw, y: by), tangent2End: CGPoint(x: bx + bw, y: by + bh), radius: br)
    beam.addLine(to: CGPoint(x: bx + bw - br, y: by + bh))
    beam.addArc(tangent1End: CGPoint(x: bx + bw, y: by + bh), tangent2End: CGPoint(x: bx + bw - br, y: by + bh), radius: br)
    
    // 底边中央：45 度斜切锁槽
    beam.addLine(to: CGPoint(x: 500 + 100, y: by + bh))
    beam.addLine(to: CGPoint(x: 500 + 40, y: by + bh - 60))
    beam.addLine(to: CGPoint(x: 500 - 40, y: by + bh - 60))
    beam.addLine(to: CGPoint(x: 500 - 100, y: by + bh))
    beam.addLine(to: CGPoint(x: bx + br, y: by + bh))
    beam.addArc(tangent1End: CGPoint(x: bx, y: by + bh), tangent2End: CGPoint(x: bx, y: by + bh - br), radius: br)
    beam.addLine(to: CGPoint(x: bx, y: by + br))
    beam.addArc(tangent1End: CGPoint(x: bx, y: by), tangent2End: CGPoint(x: bx + br, y: by), radius: br)
    beam.closeSubpath()
    parts.append((beam, flatIvory))
    
    // 2. 立柱（象牙白）：顶部 45° 凸榫
    let post = CGMutablePath()
    let px: CGFloat = 410, py: CGFloat = by + bh + gap, pw: CGFloat = 180, pBottom: CGFloat = 780, pr: CGFloat = 32
    
    post.move(to: CGPoint(x: 500 - 40 + gap*0.7, y: by + bh - 60 + gap))
    post.addLine(to: CGPoint(x: 500 + 40 - gap*0.7, y: by + bh - 60 + gap))
    post.addLine(to: CGPoint(x: 500 + 100 - gap, y: py))
    post.addLine(to: CGPoint(x: px + pw, y: py))
    post.addLine(to: CGPoint(x: px + pw, y: pBottom - pr))
    post.addArc(tangent1End: CGPoint(x: px + pw, y: pBottom), tangent2End: CGPoint(x: px + pw - pr, y: pBottom), radius: pr)
    post.addLine(to: CGPoint(x: px + pr, y: pBottom))
    post.addArc(tangent1End: CGPoint(x: px, y: pBottom), tangent2End: CGPoint(x: px, y: pBottom - pr), radius: pr)
    post.addLine(to: CGPoint(x: px, y: py))
    post.addLine(to: CGPoint(x: 500 - 100 + gap, y: py))
    post.closeSubpath()
    parts.append((post, flatIvory))
    
    // 3. 装饰性锁销（与底色相同的深绿）
    let spline = roundedRect(500 - 45, by + 45, 90, 22, 11)
    parts.append((spline, flatGreen))
    
    return parts
}

// MARK: - 渲染引擎
func renderIcon(size: CGFloat,
                glyphScale: CGFloat,
                radius: CGFloat,
                outerBg: (r: CGFloat, g: CGFloat, b: CGFloat)?,
                squircleBg: (r: CGFloat, g: CGFloat, b: CGFloat)?) -> CGImage? {
    let px = Int(size.rounded())
    guard let ctx = CGContext(data: nil, width: px, height: px, bitsPerComponent: 8, bytesPerRow: 0,
                              space: CGColorSpaceCreateDeviceRGB(),
                              bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
    ctx.setAllowsAntialiasing(true)
    ctx.interpolationQuality = .high
    
    let box = CGRect(x: 0, y: 0, width: size, height: size)
    
    // 1. 画最外层底色（如果有，例如米白）
    if let outer = outerBg {
        ctx.setFillColor(color(outer))
        ctx.fill(box)
    }
    
    // 2. 画圆角/圆形底板
    ctx.saveGState()
    if let sBg = squircleBg {
        if radius > 0 {
            let clip = CGPath(roundedRect: box, cornerWidth: radius, cornerHeight: radius, transform: nil)
            ctx.addPath(clip)
            ctx.clip()
        }
        ctx.setFillColor(color(sBg))
        ctx.fill(box)
    }
    
    // 3. 画中心图案（1000 坐标系居中缩放）
    let s = size / 1000.0 * glyphScale
    let offX = size / 2.0 - 500.0 * s
    let offY = size / 2.0 - 500.0 * s
    ctx.translateBy(x: offX, y: offY)
    ctx.scaleBy(x: s, y: -s)
    ctx.translateBy(x: 0, y: -1000)
    
    let parts = getTenonV2Parts()
    for (p, c) in parts {
        ctx.setFillColor(color(c))
        ctx.addPath(p)
        ctx.fillPath()
    }
    ctx.restoreGState()
    
    return ctx.makeImage()
}

func writePNG(_ image: CGImage?, to path: String) {
    guard let image = image else { return }
    let rep = NSBitmapImageRep(cgImage: image)
    rep.size = NSSize(width: image.width, height: image.height)
    if let data = rep.representation(using: .png, properties: [:]) {
        let url = URL(fileURLWithPath: path)
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: url)
        print("  -> \(path) (\(image.width)x\(image.height))")
    }
}

print("=== 开始生成并部署「榫卯 Tenon」多平台图标 ===")

// 1. iOS App Store 母图：1024x1024，纯直角满幅实底（由 iOS 系统自动加圆角遮罩）
let ios1024 = renderIcon(size: 1024, glyphScale: 0.82, radius: 0, outerBg: nil, squircleBg: flatGreen)
writePNG(ios1024, to: "\(repoRoot)/iosApp/Assets.xcassets/AppIcon.appiconset/icon-1024.png")

// 2. macOS 产线源图：1024x1024，带米白外底（供 make-macos-icns.sh 进行 floodfill 抠底 + 投影）
let macosSrc1024 = renderIcon(size: 1024, glyphScale: 0.82, radius: 1024 * 0.2237, outerBg: creamBg, squircleBg: flatGreen)
writePNG(macosSrc1024, to: "\(repoRoot)/design/icon/icon-1024.png")

// 3. Desktop 桌面运行时图标：1024x1024，透明外底圆角方块
let desktop1024 = renderIcon(size: 1024, glyphScale: 0.82, radius: 1024 * 0.2237, outerBg: nil, squircleBg: flatGreen)
writePNG(desktop1024, to: "\(repoRoot)/desktopApp/src/main/resources/icon.png")

// 4. Android 自适应图标前景图（ic_launcher_foreground.png）：512x512，透明背景，缩放至 60% 安全区
let androidFg = renderIcon(size: 512, glyphScale: 0.58, radius: 0, outerBg: nil, squircleBg: nil)
writePNG(androidFg, to: "\(repoRoot)/androidApp/src/main/res/drawable/ic_launcher_foreground.png")

// 5. Android 传统 Mipmap（方圆角 + 圆形）生成与分发
let densities: [(String, CGFloat)] = [
    ("mdpi", 48),
    ("hdpi", 72),
    ("xhdpi", 96),
    ("xxhdpi", 144),
    ("xxxhdpi", 192)
]

for (name, px) in densities {
    // 方形（稍带圆角）
    let squareImg = renderIcon(size: px, glyphScale: 0.78, radius: px * 0.20, outerBg: nil, squircleBg: flatGreen)
    // 圆形
    let roundImg = renderIcon(size: px, glyphScale: 0.70, radius: px / 2.0, outerBg: nil, squircleBg: flatGreen)
    
    // 部署到 androidApp
    writePNG(squareImg, to: "\(repoRoot)/androidApp/src/main/res/mipmap-\(name)/ic_launcher.png")
    writePNG(roundImg, to: "\(repoRoot)/androidApp/src/main/res/mipmap-\(name)/ic_launcher_round.png")
    
    // 部署到 design/icon
    writePNG(squareImg, to: "\(repoRoot)/design/icon/mipmap-\(name)/ic_launcher.png")
    writePNG(roundImg, to: "\(repoRoot)/design/icon/mipmap-\(name)/ic_launcher_round.png")
}

// 6. 导出标准 SVG 矢量母版
func pathData(_ p: CGPath) -> String {
    var d = ""
    p.applyWithBlock { e in
        let el = e.pointee
        func n(_ v: CGFloat) -> String { String(format: "%.2f", Double(v)) }
        switch el.type {
        case .moveToPoint: d += "M" + n(el.points[0].x) + " " + n(el.points[0].y)
        case .addLineToPoint: d += "L" + n(el.points[0].x) + " " + n(el.points[0].y)
        case .addQuadCurveToPoint:
            d += "Q" + n(el.points[0].x) + " " + n(el.points[0].y) + " " + n(el.points[1].x) + " " + n(el.points[1].y)
        case .addCurveToPoint:
            d += "C" + n(el.points[0].x) + " " + n(el.points[0].y) + " " + n(el.points[1].x) + " " + n(el.points[1].y)
                + " " + n(el.points[2].x) + " " + n(el.points[2].y)
        case .closeSubpath: d += "Z"
        @unknown default: break
        }
    }
    return d
}

let parts = getTenonV2Parts()
var svgContent = """
<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 1000 1000">
  <rect width="1000" height="1000" rx="224" fill="#21563E"/>

"""

for (p, c) in parts {
    let hex = (c.r == flatGreen.r && c.g == flatGreen.g) ? "#21563E" : "#FAFAF8"
    svgContent += "  <path fill=\"\(hex)\" d=\"\(pathData(p))\"/>\n"
}
svgContent += "</svg>\n"

try? svgContent.write(toFile: "\(repoRoot)/design/icon/relay-icon.svg", atomically: true, encoding: .utf8)
try? svgContent.write(toFile: "\(repoRoot)/design/icon/tenon-icon.svg", atomically: true, encoding: .utf8)
print("  -> design/icon/relay-icon.svg & tenon-icon.svg")

print("=== 平台图标资产生成完毕 ===")
