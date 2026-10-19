import SwiftUI
import RelayShared
import Foundation

@main
struct RelayApp: App {
    /// TLS 自检入口（task-26 ①）：**只按启动参数触发**，不动正常 UI、不加可见按钮。
    ///
    ///   xcrun simctl launch <device> <bundle-id> --relay-tls-selftest
    ///
    /// 跑一次完整双向握手（Swift → composeApp 的一行包装 → core 的 IosTlsLinkTransport：
    /// 起监听 + 客户端、正确 pin 必须成功、错误 pin 必须被拒），把报告打到 stdout 后退出；
    /// 最后一行是 RESULT=PASS|FAIL。这一层**只能在 App 进程**里跑：Kotlin/Native 的测试
    /// 二进制不是 app、拿不到 keychain（SecItemAdd → -25291），拿不到 sec_identity_t。
    init() {
        if ProcessInfo.processInfo.arguments.contains("--relay-tls-selftest") {
            let report = RelayTlsSelfTestKt.relayTlsSelfTest()
            print("[relay-tls-selftest]\n" + report)
            fflush(stdout)
            exit(0)
        }
    }

    var body: some Scene {
        WindowGroup {
            // 让窗口铺到系统栏后面，安全区**只由 Compose 根层消费一次**。
            //
            // 这条 `.ignoresSafeArea()` 是 2026-10-04 在 iPhone 17 Pro 模拟器上量出来的，
            // 不是照抄模板：SwiftUI 的 `WindowGroup` 默认会把子视图的 frame 缩进安全区
            // （顶部 62pt），而 CMP 的 `UIKitWindowInsetsManager` **同时**又把这 62pt 报给
            // Compose 的 `WindowInsets`。根层 `RelayApp` 再 `windowInsetsPadding(safeDrawing)`
            // 一次，顶部就变成 62 + 62 ≈ 124pt 的空白 —— 实测「消息」标题顶边在 436px
            // （@3x，145pt），而同样的 Compose 代码在 Pixel 6 上是 190px（72dp，顶部 inset
            // 48.8dp 来自挖孔 128px）。去掉 SwiftUI 这一层的缩进后，两者对齐。
            //
            // 参数用 `.all`（含 `.container` 与 `.keyboard`）：容器安全区交给 Compose 根层；
            // 键盘也一样 —— CMP 把 IME 高度报进 `safeDrawing`，UIKit 再缩一次视图就成了
            // 两次消费。Android 端的对应写法是 `enableEdgeToEdge()`（只负责铺满窗口、
            // 不写底色），由 `SystemBarsInsetGuardTest` 一起守住。
            ComposeAppView()
                .ignoresSafeArea()
        }
    }
}

struct ComposeAppView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
    }
}
