// icongen-mark.swift — 「捎话」图标标记（手递消息卡）三种构图，输出预览对照图与资源
//
// 概念 B：一只手把一张消息卡递出去。汉字字标已被否（小尺寸与跨市场推广都吃亏），
// 这里用纯几何形状：托举的手 + 卡片 + 运动弧线，全部用 CGPath 现画，不依赖字体。
//
// 用法：swift icongen-mark.swift <输出目录>

import Foundation
import CoreGraphics
import AppKit

let outDir = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "."
let green = (r: CGFloat(0.176), g: CGFloat(0.416), b: CGFloat(0.310))   // #2D6A4F
let ivory = (r: CGFloat(0.980), g: CGFloat(0.980), b: CGFloat(0.973))  // #FAFAF8
let tan = (r: CGFloat(0.831), g: CGFloat(0.639), b: CGFloat(0.451))     // #D4A373
let paper = (r: CGFloat(0.957), g: CGFloat(0.925), b: CGFloat(0.855))  // #F4ECD9

// MARK: - 形状

func rounded(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat, _ r: CGFloat) -> CGPath {
    CGPath(roundedRect: CGRect(x: x, y: y, width: w, height: h), cornerWidth: r, cornerHeight: r, transform: nil)
}

/// 旋转后的形状（绕自身中心）。
func spin(_ path: CGPath, _ deg: CGFloat, around cx: CGFloat, cy: CGFloat) -> CGPath {
    let out = CGMutablePath()
    out.addPath(path, transform: CGAffineTransform(translationX: cx, y: cy)
        .rotated(by: deg * .pi / 180)
        .translatedBy(x: -cx, y: -cy))
    return out
}

/// 碗形（托举的手掌）：外弧向下鼓 H、内弧向下鼓 h，两者之间是掌心厚度。
/// y 向下坐标里不用 addArc：弧的角度方向不好预测，改成显式二次曲线。
func bowl(cx: CGFloat, cy: CGFloat, rOuter: CGFloat, rInner: CGFloat, depthOuter: CGFloat, depthInner: CGFloat) -> CGPath {
    let p = CGMutablePath()
    p.move(to: CGPoint(x: cx - rOuter, y: cy))
    p.addQuadCurve(to: CGPoint(x: cx, y: cy + depthOuter),
                   control: CGPoint(x: cx - rOuter * 0.86, y: cy + depthOuter))
    p.addQuadCurve(to: CGPoint(x: cx + rOuter, y: cy),
                   control: CGPoint(x: cx + rOuter * 0.86, y: cy + depthOuter))
    p.addLine(to: CGPoint(x: cx + rInner, y: cy))
    p.addQuadCurve(to: CGPoint(x: cx, y: cy + depthInner),
                   control: CGPoint(x: cx + rInner * 0.86, y: cy + depthInner))
    p.addQuadCurve(to: CGPoint(x: cx - rInner, y: cy),
                   control: CGPoint(x: cx - rInner * 0.86, y: cy + depthInner))
    p.closeSubpath()
    return p
}

/// 圆角笔画：两点之间的胶囊（沿连线方向旋转，不是外接正方形）。
func stroke(_ x1: CGFloat, _ y1: CGFloat, _ x2: CGFloat, _ y2: CGFloat, _ w: CGFloat) -> CGPath {
    let len = sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1))
    let deg = atan2(y2 - y1, x2 - x1) * 180 / .pi
    // 顺序要点：CGAffineTransform 的链式组合是「写在前面的最后生效」，
    // 所以先平移再旋转才是「以 (x1,y1) 为起点、沿连线方向摆一根胶囊」。
    let bar = CGPath(roundedRect: CGRect(x: 0, y: -w / 2, width: len, height: w),
                     cornerWidth: w / 2, cornerHeight: w / 2, transform: nil)
    let out = CGMutablePath()
    out.addPath(bar, transform: CGAffineTransform(translationX: x1, y: y1)
        .rotated(by: deg * .pi / 180))
    return out
}

// MARK: - 三种构图（画布 1000×1000，y 向下）

/// 整组旋转：让整枚标记微微侧过来，像手正在递东西。
func tiltAll(_ parts: [(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))], _ deg: CGFloat) -> [(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))] {
    parts.map { (spin($0.0, deg, around: 500, cy: 500), $0.1) }
}

/// 卡片 + 卡面的一道「字」
func card(cx: CGFloat, cy: CGFloat, w: CGFloat, h: CGFloat, deg: CGFloat)
    -> [(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))] {
    let r = h * 0.23
    return [(spin(rounded(cx - w / 2, cy - h / 2, w, h, r), deg, around: cx, cy: cy), ivory),
            (spin(rounded(cx - w * 0.14, cy - h * 0.16, w * 0.56, h * 0.142, h * 0.071),
                  deg, around: cx, cy: cy), green)]
}

/// C1：三指托着卡片，端正
func variantC1() -> [(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))] {
    var parts = card(cx: 500, cy: 244, w: 410, h: 268, deg: -5)
    for x in [CGFloat(320), 500, 680] {
        parts.append((rounded(x - 59, 360, 118, 310, 59), ivory))
    }
    parts.append((rounded(240, 620, 520, 250, 125), ivory))
    parts.append((spin(rounded(105, 600, 200, 128, 64), -26, around: 205, cy: 664), ivory))
    return parts
}

/// C2：三指托着卡片，整枚标记侧倾（像正在递出去）
func variantC2() -> [(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))] {
    var parts = card(cx: 505, cy: 220, w: 410, h: 268, deg: -5)
    for x in [CGFloat(325), 505, 685] {
        parts.append((rounded(x - 59, 340, 118, 310, 59), ivory))
    }
    parts.append((rounded(245, 600, 520, 250, 125), ivory))
    parts.append((spin(rounded(110, 580, 200, 128, 64), -26, around: 210, cy: 644), ivory))
    return tiltAll(parts, -10)
}

/// C3：四指张开托卡片，更像真手
func variantC3() -> [(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))] {
    var parts = card(cx: 500, cy: 252, w: 400, h: 255, deg: -5)
    for x in [CGFloat(295), 440, 585, 730] {
        parts.append((rounded(x - 50, 372, 100, 300, 50), ivory))
    }
    parts.append((rounded(235, 630, 530, 240, 120), ivory))
    parts.append((spin(rounded(100, 615, 195, 125, 62), -24, around: 198, cy: 678), ivory))
    return parts
}

/// C4：三指捏着卡片右上角递出，带一道粗运动弧
func variantC4() -> [(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))] {
    var parts = card(cx: 545, cy: 250, w: 400, h: 260, deg: -11)
    for x in [CGFloat(320), 500, 680] {
        parts.append((rounded(x - 59, 400, 118, 290, 59), ivory))
    }
    parts.append((rounded(245, 655, 520, 235, 118), ivory))
    parts.append((spin(rounded(115, 630, 210, 135, 67), -28, around: 220, cy: 698), ivory))
    parts.append((stroke(742, 470, 806, 406, 50), tan))
    return parts
}

let variants: [([(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))], String)] =
    [(variantC1(), "C1"), (variantC2(), "C2"), (variantC3(), "C3"), (variantC4(), "C4")]

// MARK: - 绘制

func color(_ c: (r: CGFloat, g: CGFloat, b: CGFloat)) -> CGColor {
    CGColor(srgbRed: c.r, green: c.g, blue: c.b, alpha: 1)
}

@discardableResult
func draw(_ parts: [(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))],
          into ctx: CGContext, box: CGRect, emit: CGFloat, radius: CGFloat) {
    ctx.saveGState()
    let clip = CGPath(roundedRect: box, cornerWidth: radius, cornerHeight: radius, transform: nil)
    ctx.addPath(clip)
    ctx.clip()
    ctx.setFillColor(color(green))
    ctx.fill(box)
    // 1000 坐标系的标记居中缩放到 box
    let s = min(box.width, box.height) / 1000.0 * emit
    let off = CGPoint(x: box.midX - 500 * s, y: box.midY - 500 * s)
    ctx.translateBy(x: off.x, y: off.y)
    // 标记按 SVG 的 y 向下画；CGContext 的 y 向上，这里翻一次
    ctx.scaleBy(x: s, y: -s)
    ctx.translateBy(x: 0, y: -1000)
    for (p, c) in parts {
        ctx.setFillColor(color(c))
        ctx.addPath(p)
        ctx.fillPath()
    }
    ctx.restoreGState()
}

func makeImage(_ parts: [(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))],
               size: CGFloat, radius: CGFloat, background: (r: CGFloat, g: CGFloat, b: CGFloat)?) -> CGImage? {
    let px = Int(size.rounded())
    guard let ctx = CGContext(data: nil, width: px, height: px, bitsPerComponent: 8, bytesPerRow: 0,
                              space: CGColorSpaceCreateDeviceRGB(),
                              bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
    ctx.setAllowsAntialiasing(true)
    ctx.interpolationQuality = .high
    if let background {
        ctx.setFillColor(color(background))
        ctx.fill(CGRect(x: 0, y: 0, width: size, height: size))
    }
    draw(parts, into: ctx, box: CGRect(x: 0, y: 0, width: size, height: size), emit: 0.80, radius: radius)
    return ctx.makeImage()
}

func writePNG(_ image: CGImage?, _ file: String) {
    guard let image else { return }
    let rep = NSBitmapImageRep(cgImage: image)
    rep.size = NSSize(width: image.width, height: image.height)
    if let data = rep.representation(using: .png, properties: [:]) {
        try? data.write(to: URL(fileURLWithPath: file))
    }
}

// 对照图：大圆角徽章一行（220px）+ 真实小尺寸一行（48px，落在中性底上，模拟启动器网格）
let cell: CGFloat = 220, gap: CGFloat = 14, margin: CGFloat = 14
let small: CGFloat = 48
let cols = CGFloat(variants.count)
let width = margin * 2 + cell * cols + gap * (cols - 1)
let height = margin * 2 + cell + gap + small + gap + cell
if let sheet = CGContext(data: nil, width: Int(width), height: Int(height), bitsPerComponent: 8, bytesPerRow: 0,
                         space: CGColorSpaceCreateDeviceRGB(),
                         bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) {
    sheet.setFillColor(color((r: 0.949, g: 0.945, b: 0.929)))
    sheet.fill(CGRect(x: 0, y: 0, width: width, height: height))
    for (i, item) in variants.enumerated() {
        let x = margin + CGFloat(i) * (cell + gap)
        // 上排：大号圆角方
        if let img = makeImage(item.0, size: cell, radius: cell * 0.2237, background: nil) {
            sheet.draw(img, in: CGRect(x: x, y: height - margin - cell, width: cell, height: cell))
        }
        // 下排：48px 真尺寸
        if let img = makeImage(item.0, size: small, radius: small * 0.2237, background: nil) {
            sheet.draw(img, in: CGRect(x: x + (cell - small) / 2, y: margin, width: small, height: small))
        }
    }
    writePNG(sheet.makeImage(), "\(outDir)/mark-candidates.png")
    print("candidates -> \(outDir)/mark-candidates.png  (\(variants.map { $0.1 }.joined(separator: ", ")))")
}

// 选定构图后（pick 环境变量 B1/B2/B3）导出正式资源
if let pick = ProcessInfo.processInfo.environment["PICK"], let item = variants.first(where: { $0.1 == pick }) {
    try? FileManager.default.createDirectory(atPath: outDir, withIntermediateDirectories: true)
    let parts = item.0
    // Android 传统 mipmap：方形 + 圆形（launcher 自己裁圆角，标记留 20% 边距）
    let densities: [(String, CGFloat)] = [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]
    for (name, px) in densities {
        let dir = "\(outDir)/mipmap-\(name)"
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        writePNG(makeImage(parts, size: px, radius: 0, background: green), "\(dir)/ic_launcher.png")
        writePNG(makeImage(parts, size: px, radius: px / 2, background: green), "\(dir)/ic_launcher_round.png")
    }
    // iOS 1024：满幅方形无圆角、无 alpha
    writePNG(makeImage(parts, size: 1024, radius: 0, background: green), "\(outDir)/icon-1024.png")

    // SVG（512 三个形状 + Android 前景 108）：把每段 path 导出给 python 组装 vector drawable
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
    let hex: [(r: CGFloat, g: CGFloat, b: CGFloat)] = [ivory, green, tan, paper]
    let hexes = ["#FAFAF8", "#2D6A4F", "#D4A373", "#F4ECD9"]
    var lines: [String] = []
    for (i, part) in parts.enumerated() {
        let c = hex.firstIndex(where: { $0.r == part.1.r && $0.g == part.1.g && $0.b == part.1.b }) ?? 0
        lines.append(hexes[c] + "\t" + pathData(part.0))
    }
    let tsv = lines.joined(separator: "\n")
    try? tsv.write(toFile: "\(outDir)/mark-paths.tsv", atomically: true, encoding: .utf8)

    // 预览用 SVG
    let svgHead = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"512\" height=\"512\" viewBox=\"0 0 1000 1000\">\n"
    var svg = svgHead + "  <rect width=\"1000\" height=\"1000\" fill=\"#2D6A4F\"/>\n"
    for (i, line) in lines.enumerated() {
        let f = line.split(separator: "\t", maxSplits: 1)
        svg += "  <path fill=\"\(f[0])\" d=\"\(f[1])\"/>\n"
    }
    svg += "</svg>\n"
    try? svg.write(toFile: "\(outDir)/relay-icon.svg", atomically: true, encoding: .utf8)
    print("exported \(pick) resources")
}