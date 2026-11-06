// icongen-tenon.swift — 「榫卯 Tenon」应用图标候选生成器
//
// 构图方案：
// T1: 【T型透榫】(Architectural T-Joint) — 上梁与立柱严丝合缝咬合，构成高辨识度 T 标
// T2: 【双块互锁】(Interlocking Dual Blocks) — 阶梯型凹凸自锁，阴阳两块滑入锁定
// T3: 【燕尾自锁】(The Dovetail Joint) — 经典梯形防脱燕尾榫，象征绝对抗拉力与安全
// T4: 【十字合榫】(Cross Halving Joint) — 经纬纵横穿插，象征 P2P 局域网节点互联
//
// 用法：swift icongen-tenon.swift <输出目录>

import Foundation
import CoreGraphics
import AppKit

let outDir = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "design/icon/preview"
try? FileManager.default.createDirectory(atPath: outDir, withIntermediateDirectories: true)

// 调色盘
let bgGreen = (r: CGFloat(0.133), g: CGFloat(0.345), b: CGFloat(0.247)) // #22583F 沉稳松柏深绿
let ivory   = (r: CGFloat(0.980), g: CGFloat(0.980), b: CGFloat(0.973)) // #FAFAF8 纯净象牙白
let ochre   = (r: CGFloat(0.855), g: CGFloat(0.686), b: CGFloat(0.490)) // #DAAF7D 暖润木石金/赭石
let slate   = (r: CGFloat(0.820), g: CGFloat(0.840), b: CGFloat(0.835)) // #D1D6D5 钛灰轻对比
let gapColor = bgGreen // 互锁微缝颜色（同底色）

func color(_ c: (r: CGFloat, g: CGFloat, b: CGFloat), alpha: CGFloat = 1.0) -> CGColor {
    CGColor(srgbRed: c.r, green: c.g, blue: c.b, alpha: alpha)
}

func roundedRect(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat, _ r: CGFloat) -> CGPath {
    CGPath(roundedRect: CGRect(x: x, y: y, width: w, height: h), cornerWidth: r, cornerHeight: r, transform: nil)
}

// MARK: - 候选 T1：【T型透榫】(Architectural T-Joint)
// 上横梁为卯，中央开槽；下立柱为榫，顶部探入横梁心，形成极其刚劲纯粹的「T」字，中缝留有 14px 结构微缝
func variantT1() -> [(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))] {
    var parts: [(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))] = []
    
    // 上横梁（左半部与右半部，或一体梁留出咬合槽）
    // 梁尺寸：宽 600，高 160，y: 220 -> 380
    // 立柱尺寸：宽 180，高 440，y: 350 -> 790
    // 榫头 (Tenon Tongue)：宽 120，深入梁内到 y: 260
    
    // 绘制横梁主体（分成左右双翼，中间留出透榫孔槽）
    let beamY: CGFloat = 220
    let beamH: CGFloat = 160
    let beamR: CGFloat = 36
    
    // 左翼横梁 (x: 180 -> 420)
    let leftBeam = CGMutablePath()
    leftBeam.move(to: CGPoint(x: 180 + beamR, y: beamY))
    leftBeam.addLine(to: CGPoint(x: 420, y: beamY))
    leftBeam.addLine(to: CGPoint(x: 420, y: beamY + beamH))
    leftBeam.addLine(to: CGPoint(x: 180 + beamR, y: beamY + beamH))
    leftBeam.addArc(tangent1End: CGPoint(x: 180, y: beamY + beamH), tangent2End: CGPoint(x: 180, y: beamY), radius: beamR)
    leftBeam.addArc(tangent1End: CGPoint(x: 180, y: beamY), tangent2End: CGPoint(x: 420, y: beamY), radius: beamR)
    leftBeam.closeSubpath()
    parts.append((leftBeam, ivory))
    
    // 右翼横梁 (x: 580 -> 820)
    let rightBeam = CGMutablePath()
    rightBeam.move(to: CGPoint(x: 580, y: beamY))
    rightBeam.addLine(to: CGPoint(x: 820 - beamR, y: beamY))
    rightBeam.addArc(tangent1End: CGPoint(x: 820, y: beamY), tangent2End: CGPoint(x: 820, y: beamY + beamH), radius: beamR)
    rightBeam.addArc(tangent1End: CGPoint(x: 820, y: beamY + beamH), tangent2End: CGPoint(x: 580, y: beamY + beamH), radius: beamR)
    rightBeam.addLine(to: CGPoint(x: 580, y: beamY + beamH))
    rightBeam.closeSubpath()
    parts.append((rightBeam, ivory))
    
    // 中央贯穿的直榫立柱 (Tenon Post)
    // 柱身：宽 180，cx: 500 -> x: 410 -> 590
    // 从底部 y: 780 升起，并在梁中段 (y: 220 -> 380) 以 120 宽度直插贯穿到梁顶！
    let post = CGMutablePath()
    let postX: CGFloat = 410
    let postW: CGFloat = 180
    let postBottom: CGFloat = 780
    let postR: CGFloat = 36
    
    // 柱底倒圆角
    post.move(to: CGPoint(x: postX, y: beamY + beamH + 18))
    post.addLine(to: CGPoint(x: postX, y: postBottom - postR))
    post.addArc(tangent1End: CGPoint(x: postX, y: postBottom), tangent2End: CGPoint(x: postX + postW, y: postBottom), radius: postR)
    post.addArc(tangent1End: CGPoint(x: postX + postW, y: postBottom), tangent2End: CGPoint(x: postX + postW, y: beamY + beamH), radius: postR)
    post.addLine(to: CGPoint(x: postX + postW, y: beamY + beamH + 18))
    
    // 柱肩与透榫头 (Tenon Tongue in center)
    post.addLine(to: CGPoint(x: 500 + 60, y: beamY + beamH + 18))
    post.addLine(to: CGPoint(x: 500 + 60, y: beamY + 14))
    post.addLine(to: CGPoint(x: 500 - 60, y: beamY + 14))
    post.addLine(to: CGPoint(x: 500 - 60, y: beamY + beamH + 18))
    post.closeSubpath()
    parts.append((post, ochre))
    
    // 顶部盖板锁定键 (Tenon Key Pin / 销钉)
    let pin = roundedRect(500 - 80, beamY - 14, 160, 28, 12)
    parts.append((pin, ivory))
    
    return parts
}

// MARK: - 候选 T2：【双块阶梯互锁】(Interlocking Dual Blocks)
// 两个现代极简 L 型几何块，在对角以阶梯齿形严密咬合，中留 16px 缝隙，形成自锁方印
func variantT2() -> [(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))] {
    var parts: [(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))] = []
    
    let boxSize: CGFloat = 560
    let originX: CGFloat = 220
    let originY: CGFloat = 220
    let r: CGFloat = 44
    let gap: CGFloat = 16
    
    // 上左半块 (Block A - 象牙白) 带凸榫
    let p1 = CGMutablePath()
    p1.move(to: CGPoint(x: originX + r, y: originY))
    p1.addLine(to: CGPoint(x: originX + boxSize - r, y: originY))
    p1.addArc(tangent1End: CGPoint(x: originX + boxSize, y: originY), tangent2End: CGPoint(x: originX + boxSize, y: originY + 200), radius: r)
    p1.addLine(to: CGPoint(x: originX + boxSize, y: originY + 220))
    // 咬合阶梯走线
    p1.addLine(to: CGPoint(x: 500 + 70, y: originY + 220))
    p1.addLine(to: CGPoint(x: 500 + 70, y: 500 - gap/2))
    p1.addLine(to: CGPoint(x: 500 - 70, y: 500 - gap/2))
    p1.addLine(to: CGPoint(x: 500 - 70, y: originY + boxSize - 200))
    p1.addLine(to: CGPoint(x: originX, y: originY + boxSize - 200))
    p1.addLine(to: CGPoint(x: originX, y: originY + r))
    p1.addArc(tangent1End: CGPoint(x: originX, y: originY), tangent2End: CGPoint(x: originX + r, y: originY), radius: r)
    p1.closeSubpath()
    parts.append((p1, ivory))
    
    // 下右半块 (Block B - 暖木金/钛灰) 凹槽对应
    let p2 = CGMutablePath()
    let shiftY = gap
    p2.move(to: CGPoint(x: originX + boxSize, y: originY + 220 + shiftY))
    p2.addLine(to: CGPoint(x: originX + boxSize, y: originY + boxSize - r))
    p2.addArc(tangent1End: CGPoint(x: originX + boxSize, y: originY + boxSize), tangent2End: CGPoint(x: originX + boxSize - r, y: originY + boxSize), radius: r)
    p2.addLine(to: CGPoint(x: originX + r, y: originY + boxSize))
    p2.addArc(tangent1End: CGPoint(x: originX, y: originY + boxSize), tangent2End: CGPoint(x: originX, y: originY + boxSize - 200), radius: r)
    p2.addLine(to: CGPoint(x: originX, y: originY + boxSize - 200 + shiftY))
    // 咬合对接
    p2.addLine(to: CGPoint(x: 500 - 70 - gap, y: originY + boxSize - 200 + shiftY))
    p2.addLine(to: CGPoint(x: 500 - 70 - gap, y: 500 + gap/2))
    p2.addLine(to: CGPoint(x: 500 + 70 - gap, y: 500 + gap/2))
    p2.addLine(to: CGPoint(x: 500 + 70 - gap, y: originY + 220 + shiftY))
    p2.closeSubpath()
    parts.append((p2, ochre))
    
    return parts
}

// MARK: - 候选 T3：【经典燕尾榫】(The Dovetail Joint)
// 梯形膨大榫头深深嵌在基座凹槽中，抗拉自锁，极具结构力学辨识度
func variantT3() -> [(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))] {
    var parts: [(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))] = []
    
    // 整体居中方块：宽 540，高 540 (230 -> 770)
    let left: CGFloat = 230
    let right: CGFloat = 770
    let top: CGFloat = 230
    let bottom: CGFloat = 770
    let r: CGFloat = 40
    let gap: CGFloat = 14
    
    // 上部组件 (卯槽基座，象牙白)
    let socket = CGMutablePath()
    socket.move(to: CGPoint(x: left + r, y: top))
    socket.addLine(to: CGPoint(x: right - r, y: top))
    socket.addArc(tangent1End: CGPoint(x: right, y: top), tangent2End: CGPoint(x: right, y: top + r), radius: r)
    socket.addLine(to: CGPoint(x: right, y: 500 - gap/2))
    // 燕尾切槽：从右向中间收窄，再向底张开
    socket.addLine(to: CGPoint(x: 500 + 120, y: 500 - gap/2))
    socket.addLine(to: CGPoint(x: 500 + 70, y: 360))
    socket.addLine(to: CGPoint(x: 500 - 70, y: 360))
    socket.addLine(to: CGPoint(x: 500 - 120, y: 500 - gap/2))
    socket.addLine(to: CGPoint(x: left, y: 500 - gap/2))
    socket.addLine(to: CGPoint(x: left, y: top + r))
    socket.addArc(tangent1End: CGPoint(x: left, y: top), tangent2End: CGPoint(x: left + r, y: top), radius: r)
    socket.closeSubpath()
    parts.append((socket, ivory))
    
    // 下部组件 (燕尾榫柱，暖金色)
    let dovetail = CGMutablePath()
    dovetail.move(to: CGPoint(x: left, y: 500 + gap/2))
    dovetail.addLine(to: CGPoint(x: 500 - 120, y: 500 + gap/2))
    // 燕尾榫凸头向上伸入
    dovetail.addLine(to: CGPoint(x: 500 - 70 + 4, y: 360 + gap))
    dovetail.addLine(to: CGPoint(x: 500 + 70 - 4, y: 360 + gap))
    dovetail.addLine(to: CGPoint(x: 500 + 120, y: 500 + gap/2))
    dovetail.addLine(to: CGPoint(x: right, y: 500 + gap/2))
    dovetail.addLine(to: CGPoint(x: right, y: bottom - r))
    dovetail.addArc(tangent1End: CGPoint(x: right, y: bottom), tangent2End: CGPoint(x: right - r, y: bottom), radius: r)
    dovetail.addLine(to: CGPoint(x: left + r, y: bottom))
    dovetail.addArc(tangent1End: CGPoint(x: left, y: bottom), tangent2End: CGPoint(x: left, y: bottom - r), radius: r)
    dovetail.closeSubpath()
    parts.append((dovetail, ochre))
    
    return parts
}

// MARK: - 候选 T4：【极简单色 T 榫印】(The Monolithic Tenon T)
// 全象牙白纯单色（极佳适配 App Store / macOS 托盘与极简深色桌面），依靠深邃阴刻凹槽表达自锁
func variantT4() -> [(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))] {
    var parts: [(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))] = []
    
    // 横梁 (Beam)：宽 580，高 170，居中在 (500, 290)
    let beam = roundedRect(500 - 290, 210, 580, 170, 38)
    parts.append((beam, ivory))
    
    // 立柱 (Post)：宽 170，高 450，居中在 (500, 565)
    let post = roundedRect(500 - 85, 345, 170, 440, 38)
    parts.append((post, ivory))
    
    // 咬合处的阴刻自锁槽孔（深绿底色反镂空）
    let mortiseHole = roundedRect(500 - 100, 360, 200, 22, 11)
    parts.append((mortiseHole, bgGreen))
    
    // 中心锁销 (Tenon Pin)
    let pin = roundedRect(500 - 35, 270, 70, 70, 18)
    parts.append((pin, bgGreen))
    
    return parts
}

let variants: [([(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))], String, String)] = [
    (variantT1(), "T1-ArchT", "T型透榫 (Architectural T-Joint)"),
    (variantT2(), "T2-DualBlock", "双块互锁 (Interlocking Blocks)"),
    (variantT3(), "T3-Dovetail", "经典燕尾 (The Dovetail Joint)"),
    (variantT4(), "T4-MonoT", "极简单色印 (Monolithic Tenon T)")
]

// MARK: - 渲染引擎
func draw(_ parts: [(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))],
          into ctx: CGContext, box: CGRect, radius: CGFloat) {
    ctx.saveGState()
    let clip = CGPath(roundedRect: box, cornerWidth: radius, cornerHeight: radius, transform: nil)
    ctx.addPath(clip)
    ctx.clip()
    
    // 背景深松柏绿
    ctx.setFillColor(color(bgGreen))
    ctx.fill(box)
    
    // 缩放至画布
    let s = min(box.width, box.height) / 1000.0 * 0.82
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

func makeImage(_ parts: [(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))],
               size: CGFloat, radius: CGFloat) -> CGImage? {
    let px = Int(size.rounded())
    guard let ctx = CGContext(data: nil, width: px, height: px, bitsPerComponent: 8, bytesPerRow: 0,
                              space: CGColorSpaceCreateDeviceRGB(),
                              bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
    ctx.setAllowsAntialiasing(true)
    ctx.interpolationQuality = .high
    draw(parts, into: ctx, box: CGRect(x: 0, y: 0, width: size, height: size), radius: radius)
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

// 导出 4 款独立高清大图 (512x512)
for item in variants {
    let img = makeImage(item.0, size: 512, radius: 512 * 0.2237)
    writePNG(img, "\(outDir)/\(item.1)-512.png")
}

// 对照总览图：上方 220px 大图标 + 下方 48px 真实手机/桌面小尺寸图标
let cell: CGFloat = 220, gap: CGFloat = 20, margin: CGFloat = 24
let small: CGFloat = 48
let cols = CGFloat(variants.count)
let totalW = margin * 2 + cell * cols + gap * (cols - 1)
let totalH = margin * 2 + cell + 30 + small + 40

if let sheet = CGContext(data: nil, width: Int(totalW), height: Int(totalH), bitsPerComponent: 8, bytesPerRow: 0,
                         space: CGColorSpaceCreateDeviceRGB(),
                         bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) {
    // 浅灰背景对照板
    sheet.setFillColor(color((r: 0.94, g: 0.94, b: 0.93)))
    sheet.fill(CGRect(x: 0, y: 0, width: totalW, height: totalH))
    
    for (i, item) in variants.enumerated() {
        let x = margin + CGFloat(i) * (cell + gap)
        // 大图
        if let img = makeImage(item.0, size: cell, radius: cell * 0.2237) {
            sheet.draw(img, in: CGRect(x: x, y: totalH - margin - cell, width: cell, height: cell))
        }
        // 小尺寸真机网格对照
        if let smImg = makeImage(item.0, size: small, radius: small * 0.2237) {
            sheet.draw(smImg, in: CGRect(x: x + (cell - small) / 2, y: margin + 20, width: small, height: small))
        }
    }
    writePNG(sheet.makeImage(), "\(outDir)/tenon-candidates-sheet.png")
    print("Successfully generated: \(outDir)/tenon-candidates-sheet.png")
}
