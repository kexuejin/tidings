// icongen-tenon-v3.swift — 「榫卯 Tenon」第三轮全新视觉方向探索（纯 2D 扁平矢量）
//
// 突破前两轮单纯字标 T 开槽的局限，探索更具品牌象征性与文化辨识度的 4 种全新平面几何方向：
//
// V1: 【鲁班锁六角印 (The Luban Knot Hexagon)】— 3 根几何构件交织互锁成六边形，象征三方同在场（人、机、网）
// V2: 【45° 粽角斜榫 T (The 45° Mitred Tenon T)】— 45度角斜切滑入自锁，打破呆板十字，具动势与力学美感
// V3: 【双臂互扣环 (The Dual-Arm Interlocking Clasp)】— 两个圆角 U 型构件互相反向穿插咬死，P2P 平权双向锁定
// V4: 【斗拱叠构 (The Dougong Cantilever Crest)】— 提炼斗拱榫卯的飞檐层叠几何，极具东方结构力学威仪

import Foundation
import CoreGraphics
import AppKit

let outDir = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "design/icon/preview"
try? FileManager.default.createDirectory(atPath: outDir, withIntermediateDirectories: true)

// 纯平调色盘
let flatGreen = (r: CGFloat(0.129), g: CGFloat(0.337), b: CGFloat(0.243)) // #21563E 浓郁松柏深绿底板
let flatIvory = (r: CGFloat(0.980), g: CGFloat(0.980), b: CGFloat(0.973)) // #FAFAF8 纯平象牙白
let flatOchre = (r: CGFloat(0.855), g: CGFloat(0.686), b: CGFloat(0.490)) // #DAAF7D 暖润木石金
let flatSlate = (r: CGFloat(0.820), g: CGFloat(0.855), b: CGFloat(0.840)) // #D1DAD6 纯平钛灰青

func color(_ c: (r: CGFloat, g: CGFloat, b: CGFloat)) -> CGColor {
    CGColor(srgbRed: c.r, green: c.g, blue: c.b, alpha: 1.0)
}

func roundedRect(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat, _ r: CGFloat) -> CGPath {
    CGPath(roundedRect: CGRect(x: x, y: y, width: w, height: h), cornerWidth: r, cornerHeight: r, transform: nil)
}

// MARK: - V1: 【鲁班锁六角印 (The Luban Knot Hexagon)】
// 3 个折角几何构件在平面上以 120° 旋转互锁，每一块都压住下一块、同时被上一块穿过，构成完美的自锁六边形
func variantV1() -> [(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))] {
    var parts: [(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))] = []
    let cx: CGFloat = 500, cy: CGFloat = 500
    let gap: CGFloat = 16
    
    // 单个折角构件的轮廓（以中心为参考系）
    // 构造一个由长臂与折角组成的互锁块
    func makePiece() -> CGPath {
        let p = CGMutablePath()
        // 长臂向右上方伸出，中段折向中心并向左下穿出
        p.move(to: CGPoint(x: -60, y: -260))
        p.addLine(to: CGPoint(x: 180, y: -260))
        p.addArc(tangent1End: CGPoint(x: 230, y: -260), tangent2End: CGPoint(x: 255, y: -215), radius: 28)
        p.addLine(to: CGPoint(x: 255, y: -215))
        p.addLine(to: CGPoint(x: 170, y: -70))
        p.addLine(to: CGPoint(x: 50, y: -70))
        p.addLine(to: CGPoint(x: 50, y: 70 - gap))
        p.addLine(to: CGPoint(x: -60, y: 70 - gap))
        p.addLine(to: CGPoint(x: -60, y: -160))
        p.addLine(to: CGPoint(x: 80, y: -160))
        p.addLine(to: CGPoint(x: 125, y: -210))
        p.addLine(to: CGPoint(x: -60, y: -210))
        p.closeSubpath()
        return p
    }
    
    // 简化但极度规整的 3 块互锁等边六边形几何条
    // 采用等距几何切片：3 根厚条相互叠压
    let w: CGFloat = 100
    let armL: CGFloat = 340
    let colors = [flatIvory, flatOchre, flatSlate]
    
    for i in 0..<3 {
        let angle = CGFloat(i) * 120.0 * .pi / 180.0
        let p = CGMutablePath()
        
        // 每个条带由两段折角构成
        // 本地坐标系下画一个 L 形条
        let p0 = CGPoint(x: -armL/2, y: -170)
        let p1 = CGPoint(x: armL/2 - 30, y: -170)
        let p2 = CGPoint(x: armL/2 - 30, y: 40)
        let p3 = CGPoint(x: armL/2 - 30 - w, y: 40)
        let p4 = CGPoint(x: armL/2 - 30 - w, y: -170 + w)
        let p5 = CGPoint(x: -armL/2, y: -170 + w)
        
        var t = CGAffineTransform(translationX: cx, y: cy).rotated(by: angle)
        
        let path = CGMutablePath()
        path.move(to: p0.applying(t))
        path.addLine(to: p1.applying(t))
        path.addLine(to: p2.applying(t))
        path.addLine(to: p3.applying(t))
        path.addLine(to: p4.applying(t))
        path.addLine(to: p5.applying(t))
        path.closeSubpath()
        
        parts.append((path, colors[i]))
    }
    
    // 中心锁销菱形
    let centerPin = CGMutablePath()
    let cr: CGFloat = 46
    centerPin.move(to: CGPoint(x: cx, y: cy - cr))
    centerPin.addLine(to: CGPoint(x: cx + cr * 0.866, y: cy + cr * 0.5))
    centerPin.addLine(to: CGPoint(x: cx - cr * 0.866, y: cy + cr * 0.5))
    centerPin.closeSubpath()
    parts.append((centerPin, flatIvory))
    
    return parts
}

// MARK: - V2: 【45° 粽角斜榫 T (The 45° Mitred Tenon T)】
// 横梁与立柱的交汇处，不是生硬的 90° 平接，而是以 45° 斜角滑入卡紧，展现出动势与现代力学张力
func variantV2() -> [(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))] {
    var parts: [(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))] = []
    let gap: CGFloat = 16
    
    // 横梁（象牙白）：两端微翘，底边切出 45 度倒角斜插口
    let beam = CGMutablePath()
    let bx: CGFloat = 180, by: CGFloat = 220, bw: CGFloat = 640, bh: CGFloat = 160, br: CGFloat = 32
    beam.move(to: CGPoint(x: bx + br, y: by))
    beam.addLine(to: CGPoint(x: bx + bw - br, y: by))
    beam.addArc(tangent1End: CGPoint(x: bx + bw, y: by), tangent2End: CGPoint(x: bx + bw, y: by + bh), radius: br)
    beam.addLine(to: CGPoint(x: bx + bw - br, y: by + bh))
    beam.addArc(tangent1End: CGPoint(x: bx + bw, y: by + bh), tangent2End: CGPoint(x: bx + bw - br, y: by + bh), radius: br)
    
    // 底边中央：45 度锯齿斜切锁槽 (Chevron Interlock)
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
    
    // 立柱（纯白 + 顶部 45° 吻合斜榫）：
    let post = CGMutablePath()
    let px: CGFloat = 410, py: CGFloat = by + bh + gap, pw: CGFloat = 180, pBottom: CGFloat = 780, pr: CGFloat = 32
    
    // 顶部斜切凸榫精准插入横梁槽中
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
    
    // 中间装饰性斜契销 (Tenon Spline)
    let spline = roundedRect(500 - 45, by + 45, 90, 22, 11)
    parts.append((spline, flatGreen))
    
    return parts
}

// MARK: - V3: 【双臂互扣环 (The Dual-Arm Interlocking Clasp)】
// 两个反向互扣的圆角 U 型几何臂，像两只紧扣的臂膀，完美诠释 P2P 双向认证与平权协同
func variantV3() -> [(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))] {
    var parts: [(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))] = []
    let gap: CGFloat = 16
    let strokeW: CGFloat = 86
    
    // 上臂 (象牙白，U型开口向下偏右)
    let u1 = CGMutablePath()
    // 外圈
    u1.addRoundedRect(in: CGRect(x: 270, y: 220, width: 340, height: 380), cornerWidth: 100, cornerHeight: 100)
    // 镂空内圈
    let inner1 = CGMutablePath()
    inner1.addRoundedRect(in: CGRect(x: 270 + strokeW, y: 220 + strokeW, width: 340 - strokeW*2, height: 380 - strokeW*2), cornerWidth: 50, cornerHeight: 50)
    u1.addPath(inner1)
    
    // 下臂 (暖木金，U型开口向上偏左，两臂在空间中互相勾住)
    let u2 = CGMutablePath()
    u2.addRoundedRect(in: CGRect(x: 390, y: 400, width: 340, height: 380), cornerWidth: 100, cornerHeight: 100)
    let inner2 = CGMutablePath()
    inner2.addRoundedRect(in: CGRect(x: 390 + strokeW, y: 400 + strokeW, width: 340 - strokeW*2, height: 380 - strokeW*2), cornerWidth: 50, cornerHeight: 50)
    u2.addPath(inner2)
    
    // 切割互锁：利用剪裁让上臂的一条边压在下臂上，另一条边穿在下臂下
    parts.append((u2, flatOchre))
    parts.append((u1, flatIvory))
    
    // 在交叠处开出 16px 负形切口，显示出「穿插扣合」的硬核自锁感
    let maskBar = roundedRect(390 - gap, 360, strokeW + gap*2, 130, 24)
    parts.append((maskBar, flatGreen))
    
    // 补齐被切除后的顶层跨梁
    let topBridge = roundedRect(270, 420, strokeW, 140, 24)
    parts.append((topBridge, flatIvory))
    
    return parts
}

// MARK: - V4: 【斗拱叠构 (The Dougong Cantilever Crest)】
// 提炼中国古代斗拱层叠出挑、经纬自锁的几何美学，化为现代顶尖技术极客徽标
func variantV4() -> [(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))] {
    var parts: [(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))] = []
    
    // 顶梁 (大拱横飞，宽 680，高 120，y: 200)
    let topBeam = roundedRect(500 - 340, 200, 680, 110, 26)
    parts.append((topBeam, flatIvory))
    
    // 承托方斗 (中心锁块，宽 220，高 100，y: 330)
    let douBlock = roundedRect(500 - 110, 330, 220, 95, 22)
    parts.append((douBlock, flatOchre))
    
    // 次级出挑横拱 (宽 480，高 100，y: 445)
    let subBeam = roundedRect(500 - 240, 445, 480, 95, 22)
    parts.append((subBeam, flatIvory))
    
    // 底座立柱与榫头贯穿 (宽 180，从 y: 560 到底部 y: 780)
    let basePost = roundedRect(500 - 90, 560, 180, 220, 30)
    parts.append((basePost, flatIvory))
    
    // 中央贯穿自锁线（一条垂直贯穿中轴的微缝，表达一体自锁）
    let centerLine = roundedRect(500 - 8, 220, 16, 540, 8)
    parts.append((centerLine, flatGreen))
    
    return parts
}

let v3Variants: [([(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))], String, String)] = [
    (variantV1(), "V1-LubanKnot", "鲁班锁六角印 (The Luban Knot)"),
    (variantV2(), "V2-MitredT", "45°粽角斜榫 T (The 45° Mitred Tenon T)"),
    (variantV3(), "V3-DualClasp", "双臂互扣环 (The Dual-Arm Clasp)"),
    (variantV4(), "V4-DougongCrest", "斗拱叠构 (The Dougong Cantilever)")
]

// MARK: - 渲染引擎
func drawFlat(_ parts: [(CGPath, (r: CGFloat, g: CGFloat, b: CGFloat))],
              into ctx: CGContext, box: CGRect, radius: CGFloat) {
    ctx.saveGState()
    let clip = CGPath(roundedRect: box, cornerWidth: radius, cornerHeight: radius, transform: nil)
    ctx.addPath(clip)
    ctx.clip()
    
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

for item in v3Variants {
    let img = makeFlatImage(item.0, size: 512, radius: 512 * 0.2237)
    writePNG(img, "\(outDir)/\(item.1)-512.png")
}

let cell: CGFloat = 220, gap: CGFloat = 20, margin: CGFloat = 24
let small: CGFloat = 48
let cols = CGFloat(v3Variants.count)
let totalW = margin * 2 + cell * cols + gap * (cols - 1)
let totalH = margin * 2 + cell + 30 + small + 40

if let sheet = CGContext(data: nil, width: Int(totalW), height: Int(totalH), bitsPerComponent: 8, bytesPerRow: 0,
                         space: CGColorSpaceCreateDeviceRGB(),
                         bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) {
    sheet.setFillColor(color((r: 0.94, g: 0.94, b: 0.93)))
    sheet.fill(CGRect(x: 0, y: 0, width: totalW, height: totalH))
    
    for (i, item) in v3Variants.enumerated() {
        let x = margin + CGFloat(i) * (cell + gap)
        if let img = makeFlatImage(item.0, size: cell, radius: cell * 0.2237) {
            sheet.draw(img, in: CGRect(x: x, y: totalH - margin - cell, width: cell, height: cell))
        }
        if let smImg = makeFlatImage(item.0, size: small, radius: small * 0.2237) {
            sheet.draw(smImg, in: CGRect(x: x + (cell - small) / 2, y: margin + 20, width: small, height: small))
        }
    }
    writePNG(sheet.makeImage(), "\(outDir)/tenon-v3-candidates-sheet.png")
    print("Successfully generated: \(outDir)/tenon-v3-candidates-sheet.png")
}
