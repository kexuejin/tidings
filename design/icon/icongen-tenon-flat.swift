// icongen-tenon-flat.swift — 「榫卯 Tenon」纯扁平 2D 矢量图标生成器
//
// 彻底摒弃 3D 渲染、厚重光影与立体透视。采用现代顶级开发者工具（类似 Linear、Figma、Vercel）
// 的纯正 2D 扁平矢量与瑞士平面设计语言，依靠正负形咬合线表达「榫卯自锁」。
//
// 构图方案：
// F1: 【负形透榫 T (Negative-Seam T-Joint)】— 纯白极简 T 标，中间以一条锐利的负形咬合线雕刻出直榫凹凸
// F2: 【双色同侪互锁 (Dual-Tone Peer Interlock)】— 象牙白(人) + 暖木金(Agent) 双色平铺，凹凸严丝合缝
// F3: 【燕尾自锁 T (Dovetail Wedge T)】— 经典倒梯形燕尾受力楔，纯正交正负形
// F4: 【极简几何印 (Bauhaus Stepped Monogram)】— 包豪斯极简阶梯咬合，极高远距离辨识度

import Foundation
import CoreGraphics
import AppKit

let outDir = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "design/icon/preview"
try? FileManager.default.createDirectory(atPath: outDir, withIntermediateDirectories: true)

// 纯扁平调色盘（无渐变、无反光、无环境光遮蔽）
let flatGreen = (r: CGFloat(0.129), g: CGFloat(0.337), b: CGFloat(0.243)) // #21563E 浓郁松柏深绿底板
let flatIvory = (r: CGFloat(0.980), g: CGFloat(0.980), b: CGFloat(0.973)) // #FAFAF8 纯平象牙白
let flatOchre = (r: CGFloat(0.855), g: CGFloat(0.686), b: CGFloat(0.490)) // #DAAF7D 纯平暖木金
let flatSlate = (r: CGFloat(0.820), g: CGFloat(0.855), b: CGFloat(0.840)) // #D1DAD6 纯平钛灰青

func color(_ c: (r: CGFloat, g: CGFloat, b: CGFloat)) -> CGColor {
    CGColor(srgbRed: c.r, green: c.g, blue: c.b, alpha: 1.0)
}

func roundedRect(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat, _ r: CGFloat) -> CGPath {
    CGPath(roundedRect: CGRect(x: x, y: y, width: w, height: h), cornerWidth: r, cornerHeight: r, transform: nil)
}

// MARK: - F1：【负形透榫 T (Negative-Seam T-Joint)】
// 整个 T 标为一个纯白色块，中央被一条深绿色的 16px 凹凸阶梯缝切开，极简、高级、克制
func variantF1() -> [(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))] {
    var parts: [(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))] = []
    let gap: CGFloat = 16
    
    // 上横梁（卯）：宽 620，高 170，y: 200 -> 370，底边带有中央 140 宽度的方形凹槽 (深度 60)
    let beam = CGMutablePath()
    let bx: CGFloat = 190, by: CGFloat = 200, bw: CGFloat = 620, bh: CGFloat = 170, br: CGFloat = 34
    beam.move(to: CGPoint(x: bx + br, y: by))
    beam.addLine(to: CGPoint(x: bx + bw - br, y: by))
    beam.addArc(tangent1End: CGPoint(x: bx + bw, y: by), tangent2End: CGPoint(x: bx + bw, y: by + br), radius: br)
    beam.addLine(to: CGPoint(x: bx + bw, y: by + bh - br))
    beam.addArc(tangent1End: CGPoint(x: bx + bw, y: by + bh), tangent2End: CGPoint(x: bx + bw - br, y: by + bh), radius: br)
    
    // 横梁底边（向内开凹槽）：
    beam.addLine(to: CGPoint(x: 500 + 75, y: by + bh))
    beam.addLine(to: CGPoint(x: 500 + 75, y: by + bh - 60))
    beam.addLine(to: CGPoint(x: 500 - 75, y: by + bh - 60))
    beam.addLine(to: CGPoint(x: 500 - 75, y: by + bh))
    beam.addLine(to: CGPoint(x: bx + br, y: by + bh))
    beam.addArc(tangent1End: CGPoint(x: bx, y: by + bh), tangent2End: CGPoint(x: bx, y: by + bh - br), radius: br)
    beam.addLine(to: CGPoint(x: bx, y: by + br))
    beam.addArc(tangent1End: CGPoint(x: bx, y: by), tangent2End: CGPoint(x: bx + br, y: by), radius: br)
    beam.closeSubpath()
    parts.append((beam, flatIvory))
    
    // 下立柱（榫）：宽 180，从 y: 780 升起，顶部突出宽度 150 - gap*2 的凸榫，嵌入上述凹槽中！
    let post = CGMutablePath()
    let px: CGFloat = 500 - 90, py: CGFloat = by + bh + gap, pw: CGFloat = 180, pBottom: CGFloat = 780, pr: CGFloat = 34
    let tenonW: CGFloat = 150 - gap
    let tenonH: CGFloat = 60 - gap/2
    
    post.move(to: CGPoint(x: 500 - tenonW/2, y: by + bh - tenonH))
    post.addLine(to: CGPoint(x: 500 + tenonW/2, y: by + bh - tenonH))
    post.addLine(to: CGPoint(x: 500 + tenonW/2, y: py))
    post.addLine(to: CGPoint(x: px + pw, y: py))
    post.addLine(to: CGPoint(x: px + pw, y: pBottom - pr))
    post.addArc(tangent1End: CGPoint(x: px + pw, y: pBottom), tangent2End: CGPoint(x: px + pw - pr, y: pBottom), radius: pr)
    post.addLine(to: CGPoint(x: px + pr, y: pBottom))
    post.addArc(tangent1End: CGPoint(x: px, y: pBottom), tangent2End: CGPoint(x: px, y: pBottom - pr), radius: pr)
    post.addLine(to: CGPoint(x: px, y: py))
    post.addLine(to: CGPoint(x: 500 - tenonW/2, y: py))
    post.closeSubpath()
    parts.append((post, flatIvory))
    
    return parts
}

// MARK: - F2：【双色同侪互锁 (Dual-Tone Peer Interlock)】
// 象牙白上梁代表人类/决策端，暖木金立柱代表 Agent/执行端，双色完全平铺，在中心严密咬合
func variantF2() -> [(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))] {
    var parts: [(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))] = []
    let gap: CGFloat = 14
    
    // 上横梁（卯）：象牙白
    let beam = CGMutablePath()
    let bx: CGFloat = 190, by: CGFloat = 200, bw: CGFloat = 620, bh: CGFloat = 170, br: CGFloat = 34
    beam.move(to: CGPoint(x: bx + br, y: by))
    beam.addLine(to: CGPoint(x: bx + bw - br, y: by))
    beam.addArc(tangent1End: CGPoint(x: bx + bw, y: by), tangent2End: CGPoint(x: bx + bw, y: by + br), radius: br)
    beam.addLine(to: CGPoint(x: bx + bw, y: by + bh - br))
    beam.addArc(tangent1End: CGPoint(x: bx + bw, y: by + bh), tangent2End: CGPoint(x: bx + bw - br, y: by + bh), radius: br)
    
    // 阶梯咬合口
    beam.addLine(to: CGPoint(x: 500 + 80, y: by + bh))
    beam.addLine(to: CGPoint(x: 500 + 80, y: by + bh - 70))
    beam.addLine(to: CGPoint(x: 500 - 80, y: by + bh - 70))
    beam.addLine(to: CGPoint(x: 500 - 80, y: by + bh))
    beam.addLine(to: CGPoint(x: bx + br, y: by + bh))
    beam.addArc(tangent1End: CGPoint(x: bx, y: by + bh), tangent2End: CGPoint(x: bx, y: by + bh - br), radius: br)
    beam.addLine(to: CGPoint(x: bx, y: by + br))
    beam.addArc(tangent1End: CGPoint(x: bx, y: by), tangent2End: CGPoint(x: bx + br, y: by), radius: br)
    beam.closeSubpath()
    parts.append((beam, flatIvory))
    
    // 下立柱（榫）：暖木金/暖石色，完全平铺
    let post = CGMutablePath()
    let px: CGFloat = 500 - 90, py: CGFloat = by + bh + gap, pw: CGFloat = 180, pBottom: CGFloat = 780, pr: CGFloat = 34
    let tenonW: CGFloat = 160 - gap
    let tenonH: CGFloat = 70 - gap/2
    
    post.move(to: CGPoint(x: 500 - tenonW/2, y: by + bh - tenonH))
    post.addLine(to: CGPoint(x: 500 + tenonW/2, y: by + bh - tenonH))
    post.addLine(to: CGPoint(x: 500 + tenonW/2, y: py))
    post.addLine(to: CGPoint(x: px + pw, y: py))
    post.addLine(to: CGPoint(x: px + pw, y: pBottom - pr))
    post.addArc(tangent1End: CGPoint(x: px + pw, y: pBottom), tangent2End: CGPoint(x: px + pw - pr, y: pBottom), radius: pr)
    post.addLine(to: CGPoint(x: px + pr, y: pBottom))
    post.addArc(tangent1End: CGPoint(x: px, y: pBottom), tangent2End: CGPoint(x: px, y: pBottom - pr), radius: pr)
    post.addLine(to: CGPoint(x: px, y: py))
    post.addLine(to: CGPoint(x: 500 - tenonW/2, y: py))
    post.closeSubpath()
    parts.append((post, flatOchre))
    
    return parts
}

// MARK: - F3：【燕尾自锁 T (Dovetail Wedge T)】
// 经典防脱落燕尾榫：横梁下方开出倒梯形（上窄下宽），立柱顶部展开为梯形燕尾插入，不可拉脱
func variantF3() -> [(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))] {
    var parts: [(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))] = []
    let gap: CGFloat = 15
    
    // 上横梁
    let beam = CGMutablePath()
    let bx: CGFloat = 190, by: CGFloat = 200, bw: CGFloat = 620, bh: CGFloat = 170, br: CGFloat = 34
    beam.move(to: CGPoint(x: bx + br, y: by))
    beam.addLine(to: CGPoint(x: bx + bw - br, y: by))
    beam.addArc(tangent1End: CGPoint(x: bx + bw, y: by), tangent2End: CGPoint(x: bx + bw, y: by + br), radius: br)
    beam.addLine(to: CGPoint(x: bx + bw, y: by + bh - br))
    beam.addArc(tangent1End: CGPoint(x: bx + bw, y: by + bh), tangent2End: CGPoint(x: bx + bw - br, y: by + bh), radius: br)
    
    // 燕尾凹槽：颈部宽 100，深处扩散到宽 160
    beam.addLine(to: CGPoint(x: 500 + 50, y: by + bh))
    beam.addLine(to: CGPoint(x: 500 + 80, y: by + bh - 70))
    beam.addLine(to: CGPoint(x: 500 - 80, y: by + bh - 70))
    beam.addLine(to: CGPoint(x: 500 - 50, y: by + bh))
    beam.addLine(to: CGPoint(x: bx + br, y: by + bh))
    beam.addArc(tangent1End: CGPoint(x: bx, y: by + bh), tangent2End: CGPoint(x: bx, y: by + bh - br), radius: br)
    beam.addLine(to: CGPoint(x: bx, y: by + br))
    beam.addArc(tangent1End: CGPoint(x: bx, y: by), tangent2End: CGPoint(x: bx + br, y: by), radius: br)
    beam.closeSubpath()
    parts.append((beam, flatIvory))
    
    // 下立柱与燕尾凸头
    let post = CGMutablePath()
    let px: CGFloat = 500 - 90, py: CGFloat = by + bh + gap, pw: CGFloat = 180, pBottom: CGFloat = 780, pr: CGFloat = 34
    
    post.move(to: CGPoint(x: 500 - 72, y: by + bh - 70 + gap))
    post.addLine(to: CGPoint(x: 500 + 72, y: by + bh - 70 + gap))
    post.addLine(to: CGPoint(x: 500 + 44, y: py))
    post.addLine(to: CGPoint(x: px + pw, y: py))
    post.addLine(to: CGPoint(x: px + pw, y: pBottom - pr))
    post.addArc(tangent1End: CGPoint(x: px + pw, y: pBottom), tangent2End: CGPoint(x: px + pw - pr, y: pBottom), radius: pr)
    post.addLine(to: CGPoint(x: px + pr, y: pBottom))
    post.addArc(tangent1End: CGPoint(x: px, y: pBottom), tangent2End: CGPoint(x: px, y: pBottom - pr), radius: pr)
    post.addLine(to: CGPoint(x: px, y: py))
    post.addLine(to: CGPoint(x: 500 - 44, y: py))
    post.closeSubpath()
    parts.append((post, flatIvory))
    
    return parts
}

// MARK: - F4：【包豪斯极简阶梯徽标 (Bauhaus Stepped Monogram)】
// 两个纯平面 L 型块面构成一方形印，中间以阶梯几何无缝自锁，极简、耐看、极强现代科技感
func variantF4() -> [(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))] {
    var parts: [(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))] = []
    let gap: CGFloat = 16
    let sz: CGFloat = 560
    let ox: CGFloat = 220, oy: CGFloat = 220, r: CGFloat = 38
    
    // 左上构件 (象牙白)
    let p1 = CGMutablePath()
    p1.move(to: CGPoint(x: ox + r, y: oy))
    p1.addLine(to: CGPoint(x: ox + sz - r, y: oy))
    p1.addArc(tangent1End: CGPoint(x: ox + sz, y: oy), tangent2End: CGPoint(x: ox + sz, y: oy + r), radius: r)
    p1.addLine(to: CGPoint(x: ox + sz, y: oy + 210))
    // 阶梯凸榫
    p1.addLine(to: CGPoint(x: 500 + 65, y: oy + 210))
    p1.addLine(to: CGPoint(x: 500 + 65, y: 500 - gap/2))
    p1.addLine(to: CGPoint(x: 500 - 65, y: 500 - gap/2))
    p1.addLine(to: CGPoint(x: 500 - 65, y: oy + sz - 210))
    p1.addLine(to: CGPoint(x: ox, y: oy + sz - 210))
    p1.addLine(to: CGPoint(x: ox, y: oy + r))
    p1.addArc(tangent1End: CGPoint(x: ox, y: oy), tangent2End: CGPoint(x: ox + r, y: oy), radius: r)
    p1.closeSubpath()
    parts.append((p1, flatIvory))
    
    // 右下构件 (纯平钛灰/浅象牙)
    let p2 = CGMutablePath()
    p2.move(to: CGPoint(x: ox + sz, y: oy + 210 + gap))
    p2.addLine(to: CGPoint(x: ox + sz, y: oy + sz - r))
    p2.addArc(tangent1End: CGPoint(x: ox + sz, y: oy + sz), tangent2End: CGPoint(x: ox + sz - r, y: oy + sz), radius: r)
    p2.addLine(to: CGPoint(x: ox + r, y: oy + sz))
    p2.addArc(tangent1End: CGPoint(x: ox, y: oy + sz), tangent2End: CGPoint(x: ox, y: oy + sz - r), radius: r)
    p2.addLine(to: CGPoint(x: ox, y: oy + sz - 210 + gap))
    // 阶梯凹槽
    p2.addLine(to: CGPoint(x: 500 - 65 - gap, y: oy + sz - 210 + gap))
    p2.addLine(to: CGPoint(x: 500 - 65 - gap, y: 500 + gap/2))
    p2.addLine(to: CGPoint(x: 500 + 65 - gap, y: 500 + gap/2))
    p2.addLine(to: CGPoint(x: 500 + 65 - gap, y: oy + 210 + gap))
    p2.closeSubpath()
    parts.append((p2, flatSlate))
    
    return parts
}

let flatVariants: [([(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))], String, String)] = [
    (variantF1(), "F1-NegativeSeamT", "负形透榫 T (Negative-Seam T-Joint)"),
    (variantF2(), "F2-DualTonePeer", "双色同侪互锁 (Dual-Tone Peer Interlock)"),
    (variantF3(), "F3-DovetailWedgeT", "燕尾自锁 T (Dovetail Wedge T)"),
    (variantF4(), "F4-SteppedMonogram", "包豪斯阶梯印 (Bauhaus Stepped Monogram)")
]

// MARK: - 纯扁平渲染引擎 (Zero 3D, Zero Gradients, Zero Ambient Occlusion)
func drawFlat(_ parts: [(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))],
              into ctx: CGContext, box: CGRect, radius: CGFloat) {
    ctx.saveGState()
    let clip = CGPath(roundedRect: box, cornerWidth: radius, cornerHeight: radius, transform: nil)
    ctx.addPath(clip)
    ctx.clip()
    
    // 纯平松柏绿底色
    ctx.setFillColor(color(flatGreen))
    ctx.fill(box)
    
    let s = min(box.width, box.height) / 1000.0 * 0.84
    let offX = box.midX - 500 * s
    let offY = box.midY - 500 * s
    ctx.translateBy(x: offX, y: offY)
    ctx.scaleBy(x: s, y: -s)
    ctx.translateBy(x: 0, y: -1000)
    
    for (p, c) in parts {
        ctx.setFillColor(color(c))
        ctx.addPath(p)
        ctx.fillPath()
    }
    ctx.restoreGState()
}

func makeFlatImage(_ parts: [(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))],
                   size: CGFloat, radius: CGFloat) -> CGImage? {
    let px = Int(size.rounded())
    guard let ctx = CGContext(data: nil, width: px, height: px, bitsPerComponent: 8, bytesPerRow: 0,
                              space: CGColorSpaceCreateDeviceRGB(),
                              bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
    ctx.setAllowsAntialiasing(true)
    ctx.interpolationQuality = .high
    drawFlat(parts, into: ctx, box: CGRect(x: 0, y: 0, width: size, height: size), radius: radius)
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

// 导出各款 512px 独立高清图
for item in flatVariants {
    let img = makeFlatImage(item.0, size: 512, radius: 512 * 0.2237)
    writePNG(img, "\(outDir)/\(item.1)-512.png")
}

// 对照总览板：上排 220px 扁平大标 + 下排 48px 真机启动器小网格
let cell: CGFloat = 220, gap: CGFloat = 20, margin: CGFloat = 24
let small: CGFloat = 48
let cols = CGFloat(flatVariants.count)
let totalW = margin * 2 + cell * cols + gap * (cols - 1)
let totalH = margin * 2 + cell + 30 + small + 40

if let sheet = CGContext(data: nil, width: Int(totalW), height: Int(totalH), bitsPerComponent: 8, bytesPerRow: 0,
                         space: CGColorSpaceCreateDeviceRGB(),
                         bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) {
    sheet.setFillColor(color((r: 0.94, g: 0.94, b: 0.93)))
    sheet.fill(CGRect(x: 0, y: 0, width: totalW, height: totalH))
    
    for (i, item) in flatVariants.enumerated() {
        let x = margin + CGFloat(i) * (cell + gap)
        if let img = makeFlatImage(item.0, size: cell, radius: cell * 0.2237) {
            sheet.draw(img, in: CGRect(x: x, y: totalH - margin - cell, width: cell, height: cell))
        }
        if let smImg = makeFlatImage(item.0, size: small, radius: small * 0.2237) {
            sheet.draw(smImg, in: CGRect(x: x + (cell - small) / 2, y: margin + 20, width: small, height: small))
        }
    }
    writePNG(sheet.makeImage(), "\(outDir)/tenon-flat-candidates-sheet.png")
    print("Successfully generated flat sheet: \(outDir)/tenon-flat-candidates-sheet.png")
}
