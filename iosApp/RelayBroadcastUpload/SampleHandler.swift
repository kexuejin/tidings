// SPDX-License-Identifier: GPL-3.0-only

import Foundation
import ReplayKit
import AVFoundation
import CoreVideo

/// ReplayKit 广播上传扩展：全屏镜像的唯一合法来源。
///
/// 分工（与 `composeApp/src/iosMain/.../IosScreenSharingService.kt` 的类注释逐字对齐）：
/// - 本进程拿到 `RPBroadcastSampleHandler` 推来的全屏 CMSampleBuffer；
/// - 编成 JPEG：长边 ≤1920、单帧 ≤512KB（协议 Limits 的发送侧上限）；
/// - **双槽交替 + 先 .tmp 再 rename** 原子写进 App Group 容器
///   `<group>/screen-share/frame-<0|1>.jpg`；
/// - 主 App 轮询帧槽文件的 mtime 发现新帧（不用 darwin notify，理由写在主 App 侧注释里）。
///
/// 帧率不在这里做节流：观看端 `screen-next` 的节奏才是节拍，这里"来一帧写一帧"，
/// 主 App 不读自然就慢下来；扩展自己再叠一层限流只会让两边对不上。
///
/// 真机专属：模拟器不支持广播扩展（Apple 的限制，不是实现问题）。
class SampleHandler: RPBroadcastSampleHandler {

    private let frameDirName = "screen-share"
    private let maxEdge = 1920          // Limits.SCREEN_MAX_EDGE
    private let maxBytes = 512 * 1024   // Limits.SCREEN_MAX_FRAME_BYTES
    private var slot = 0
    private var writerQueue: DispatchQueue?

    override func broadcastStarted(withSetupInfo setupInfo: [String: NSObject]?) {
        writerQueue = DispatchQueue(label: "com.kapp.tidings.broadcast-writer", qos: .userInitiated)
        ensureFrameDir()
    }

    override func broadcastPaused() {
        // 暂停 = 暂时没帧。主 App 把"没有新文件"当静止处理（重发上一帧），不必特殊动作。
    }

    override func broadcastResumed() {}

    override func broadcastFinished() {
        // 已知取舍：没有即时的 ended 信号通道，主 App 靠帧静默 + 观看端超时结束会话。
        // 清理留给下一次 broadcastStarted（帧文件留着无妨，会话起点过滤 mtime）。
        writerQueue = nil
    }

    override func processSampleBuffer(_ sampleBuffer: CMSampleBuffer, with sampleBufferType: RPSampleBufferType) {
        guard sampleBufferType == RPSampleBufferType.video,
              let queue = writerQueue,
              let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer)
        else { return }
        // 只取当前最新帧：排队积压会让画面越来越旧，宁可丢拍不可延迟。
        //
        // 这里**不要**手写 CVPixelBufferRetain/Release：Swift 里 Core Foundation 对象是自动
        // 内存管理的（那两个函数在 Swift 里已被标为 unavailable），而闭包**捕获** pixelBuffer
        // 本身就会把它 retain 到闭包结束 —— 手写反而编译不过。
        queue.async { [weak self] in
            guard let self = self else { return }
            guard let jpeg = self.encode(pixelBuffer) else { return }
            self.writeAtomically(jpeg)
        }
    }

    // MARK: - JPEG

    private func encode(_ buffer: CVPixelBuffer) -> Data? {
        let w = CVPixelBufferGetWidth(buffer)
        let h = CVPixelBufferGetHeight(buffer)
        guard w > 0, h > 0 else { return nil }
        let scale = min(1.0, Double(maxEdge) / Double(max(w, h)))
        let target = CIImage(cvPixelBuffer: buffer)
            .transformed(by: CGAffineTransform(scaleX: CGFloat(scale), y: CGFloat(scale)))
        // CIContextOption 里没有 cacheInterpretation（那是老 API 的写法），
        // 与它同义的是 cacheIntermediates = false。
        let ctx = CIContext(options: [.cacheIntermediates: false])
        guard var data = ctx.jpegRepresentation(of: target,
                                                colorSpace: CGColorSpaceCreateDeviceRGB(),
                                                options: [kCGImageDestinationLossyCompressionQuality as CIImageRepresentationOption: 0.7])
        else { return nil }

        // 超 512KB 先降质量再降尺寸（与 Android 侧同一策略，观看端对超限帧是整帧拒收）。
        var quality = 0.6
        while data.count > maxBytes && quality >= 0.3 {
            guard let smaller = ctx.jpegRepresentation(of: target,
                                                       colorSpace: CGColorSpaceCreateDeviceRGB(),
                                                       options: [kCGImageDestinationLossyCompressionQuality as CIImageRepresentationOption: quality])
            else { break }
            data = smaller
            quality -= 0.15
        }
        var side = scale
        var rounds = 0
        while data.count > maxBytes && rounds < 4 {
            rounds += 1
            side *= 0.8
            let shrunk = CIImage(cvPixelBuffer: buffer)
                .transformed(by: CGAffineTransform(scaleX: CGFloat(side), y: CGFloat(side)))
            guard let smaller = ctx.jpegRepresentation(of: shrunk,
                                                      colorSpace: CGColorSpaceCreateDeviceRGB(),
                                                      options: [kCGImageDestinationLossyCompressionQuality as CIImageRepresentationOption: quality])
            else { break }
            data = smaller
        }
        return data.count <= maxBytes ? data : nil
    }

    // MARK: - 共享容器写入

    private func frameDirURL() -> URL? {
        FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: "group.com.kapp.tidings")?
            .appendingPathComponent(frameDirName, isDirectory: true)
    }

    private func ensureFrameDir() {
        guard let dir = frameDirURL() else { return }
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    private func writeAtomically(_ data: Data) {
        guard let dir = frameDirURL() else { return }
        slot = 1 - slot
        let final = dir.appendingPathComponent("frame-\(slot).jpg")
        let tmp = dir.appendingPathComponent("frame-\(slot).tmp.jpg")
        // 先写 tmp 再 rename：同卷 rename 是原子的，读侧永远读到完整的一帧。
        do {
            try data.write(to: tmp, options: .atomic)
            try FileManager.default.replaceItem(at: final, withItemAt: tmp,
                                                backupItemName: nil,
                                                options: [],
                                                resultingItemURL: nil)
        } catch {
            try? FileManager.default.removeItem(at: tmp)
        }
    }
}
