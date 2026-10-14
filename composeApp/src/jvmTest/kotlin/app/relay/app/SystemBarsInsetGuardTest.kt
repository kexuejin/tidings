// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 守卫「系统栏 / 安全区 / 键盘 inset 只被消费一次」这条不变量。
 *
 * 这条不变量的真机现象是**空白条**，而且它的表现形式很容易骗过人：
 *
 * - 状态栏/导航栏留白，通常来自 inset 被消费了两次（宿主一次 + Compose 一次），
 *   两次的量叠加就是一条整数倍的空带。旧版 `MainActivity` 没开 `enableEdgeToEdge()`、
 *   根层也没有 `Surface`，于是系统先画一层自己的窗口背景，Compose 再画一层页面背景，
 *   两层颜色对不上就成了一条与内容无关的"空白"。
 * - 键盘弹起时的输入栏上方留白，来自 `safeDrawing`（含 IME）**再加**一次
 *   `imePadding()`：一次被根层消费，一次被输入栏自己消费，两者之间的差就是那条空白。
 *
 * 这两件事都不是「布局写错了」，而是**同一份 inset 被消费了两次**，靠肉眼看截图
 * 只能看出"有条白条"，看不出是谁多消费了一次。所以这里做的是**源码级守卫**：
 * 把消费点钉死成"根层一处"，并禁止屏内再出现第二个消费点。
 *
 * 为什么必须放在 `jvmTest` 而不是 `commonTest`：断言的是源码文本，只有 JVM 侧能读文件。
 * 为什么不能用 `@Preview` 截图代替：AGP screenshot 插件在 JVM 里渲染
 * `@Preview`，`WindowInsets` 恒为 0（`WindowInsetsHolder` 是 internal，无法伪造），
 * 预览**看不见**系统栏外观。真机证据仍然要留，但只落在真机上，不进仓库。
 *
 * 允许的唯一消费点见 [rootInsetOwner]；一旦改动根层归属，这条测试会先红。
 */
class SystemBarsInsetGuardTest {

    /** 唯一被允许消费 `safeDrawing` 的文件：共享 Compose 根。 */
    private val rootInsetOwner = "composeApp/src/commonMain/kotlin/app/relay/App.kt"

    @Test
    fun `只有根层可以消费 inset`() {
        val consumers = mutableListOf<String>()
        for (source in commonSources()) {
            if (code(source).contains("WindowInsets.safeDrawing")) consumers += relative(source)
        }
        assertEquals(
            listOf("composeApp/src/commonMain/kotlin/app/relay/App.kt"),
            consumers.map { it }.sorted(),
            "safeDrawing 的消费点必须唯一：只有共享 Compose 根。多一个消费点就多一条白带。",
        )
    }

    @Test
    fun `屏内不得使用 imePadding 或 safeDrawingPadding`() {
        val offenders = mutableListOf<String>()
        for (source in commonSources()) {
            if (source.path.replace('\\', '/').endsWith(rootInsetOwner)) continue
            val body = code(source)
            if (body.contains(".imePadding()") || body.contains(".safeDrawingPadding()")) {
                offenders += relative(source)
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "这些文件在根层已消费 safeDrawing（含 IME）之后又自己加了一次 inset，" +
                "键盘弹起时会多出一条空白：${offenders.joinToString()}",
        )
    }

    @Test
    fun `CompactScaffold 把自己的 inset 全部置零`() {
        val scaffold = File(scaffoldRoot(), "composeApp/src/commonMain/kotlin/app/relay/ui/components/CompactScaffold.kt")
        val body = code(scaffold)
        assertTrue(
            body.contains("contentWindowInsets = WindowInsets(0)"),
            "Scaffold 必须置零 contentWindowInsets，否则它会在根层之上再消费一遍顶部/水平安全区。",
        )
        // 期望值从 2 改成 1：这个外壳曾带一条 NavigationRail 宽屏分支，但那条分支不可达
        // ——生产调用点在 `if (twoPane) DesktopShell else` 的 else 里，宽屏永远走不到这里。
        // 已按自适应布局审计的处置①删除，
        // 文件也从 AdaptiveScaffold 改名成 CompactScaffold，现在只剩底部 NavigationBar 一处。
        assertEquals(
            1,
            Regex("windowInsets = WindowInsets\\(0\\)").findAll(body).count(),
            "底部 NavigationBar 需要一次 windowInsets = WindowInsets(0)，缺了底栏就会留白。",
        )
        // 竖导形态（CompactNavigationRail）自己不消费 inset，所以计数仍是 1；
        // 若哪天有人给它套了 Scaffold 而忘了置零，这里会先红。
        assertTrue(
            body.contains("landscape: Boolean = false"),
            "CompactScaffold 必须保留 landscape 参数（缺省 false），横屏形态靠它选择。",
        )
        // 真正要守的不变量：这个单栏外壳**不得**按宽屏两档分支。历史上那条
        // NavigationRail 分支收的是 MediumTwoPane / ExpandedListDetail，永远不可达。
        // 横屏竖导是另一回事（宽度 800–900dp，给不了两栏），但它同样不该在这里
        // 判断「走哪个壳」—— 壳的分发归 App.kt 的 resolveAppLayoutMode。
        for (forbidden in listOf("MediumTwoPane", "ExpandedListDetail", "twoPane")) {
            assertTrue(
                !body.contains(forbidden),
                "单栏外壳里不该出现 `$forbidden`：走哪个壳由 App.kt 的 resolveAppLayoutMode 决定，" +
                    "在这里再判一次就是当年那条不可达分支的翻版。",
            )
        }
    }

    @Test
    fun `系统栏图标明暗由 CareSystemBars 负责 且每个 target 都有 actual`() {
        val expect = File(composeAppRoot(), "src/commonMain/kotlin/app/relay/platform/CareSystemBars.kt")
        assertTrue(code(expect).contains("expect fun CareSystemBars(darkTheme: Boolean)"), "缺少 expect 声明")

        // 编译目标集合：commonMain 之外现存 androidMain / iosMain / jvmMain。
        val targets = File(composeAppRoot(), "src")
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.endsWith("Main") && it.name != "commonMain" }
            .map { it.name }
            .sorted()
        assertEquals(listOf("androidMain", "iosMain", "jvmMain"), targets, "源码集集合变了，actual 清单要跟着复核")

        for (target in targets) {
            // actual 文件按平台短名命名：androidMain -> CareSystemBars.android.kt
            val suffix = target.removeSuffix("Main")
            val actual = File(composeAppRoot(), "src/$target/kotlin/app/relay/platform/CareSystemBars.$suffix.kt")
            assertTrue(actual.isFile, "缺少 actual：${relative(actual)}")
            assertTrue(
                code(actual).contains("actual fun CareSystemBars(darkTheme: Boolean)"),
                "${relative(actual)} 必须提供同名 actual",
            )
        }
    }

    @Test
    fun `Android 宿主开启 edge to edge 且 iOS 宿主铺满窗口`() {
        val activity = File(scaffoldRoot(), "androidApp/src/main/kotlin/MainActivity.kt")
        // 断言「调用了」而不是「无参调用」：默认样式会在浅色导航栏上叠一层白色 scrim，
        // 所以这里必须带着显式透明的 statusBarStyle / navigationBarStyle。
        assertTrue(
            Regex("""enableEdgeToEdge\s*\(""").containsMatchIn(code(activity)),
            "MainActivity 必须调用 enableEdgeToEdge(...)",
        )
        assertTrue(
            Regex("""navigationBarStyle\s*=\s*SystemBarStyle\.auto\(\s*Color\.TRANSPARENT,\s*Color\.TRANSPARENT""")
                .containsMatchIn(code(activity)),
            "enableEdgeToEdge 必须显式把 navigationBarStyle 设为透明，" +
                "默认样式在浅色导航栏上会叠约 90% 白的 scrim，底部留一条白带",
        )

        // CMP 的 UIKitWindowInsetsManager 报的是**窗口级** safeAreaInsets，它并不知道
        // SwiftUI 已经替它缩进过多少。所以宿主若只让开键盘那一份
        // （`.ignoresSafeArea(.keyboard)`），同一份安全区会被消费两次：
        // SwiftUI 先缩一次 frame，Compose 根层再 `windowInsetsPadding(safeDrawing)` 一次。
        //
        // 这条断言在 2026-10-04 被真机量测推翻过一次，别再改回去：
        // iPhone 17 Pro 模拟器（@3x）「消息」标题顶边 436px = 62（SwiftUI 缩进）
        // + 62（Compose 根层再 pad）+ 21pt（页面自留白）；改成 `.ignoresSafeArea()` 后
        // 变成 250px = 62 + 21pt，与 Pixel 6 的 190px = 48.8（挖孔 inset）+ 23.6dp 同构。
        // 证据：design/runtime-evidence/layout-ios-safe-area-double-count.txt
        val ios = File(scaffoldRoot(), "iosApp/iOSApp.swift")
        assertTrue(
            Regex("""\.ignoresSafeArea\s*\(\s*\)""").containsMatchIn(code(ios)),
            "iOS 宿主必须写成 ComposeAppView().ignoresSafeArea()（不带参数 = .all，含 .container 与 .keyboard）。" +
                "宿主只让开 .keyboard 时，SwiftUI 缩进一次、Compose 根层再 pad 一次，顶部会多出一条白带。",
        )
    }

    @Test
    fun `Android 主 Activity 必须 adjustResize`() {
        val manifest = File(scaffoldRoot(), "androidApp/src/main/AndroidManifest.xml")
        assertTrue(
            code(manifest).contains("""windowSoftInputMode="adjustResize""""),
            "${relative(manifest)} 必须给 MainActivity 声明 windowSoftInputMode=\"adjustResize\"。\n" +
                "默认是 adjust=pan：系统把输入栏平移到键盘上沿，而 Compose 根层又按 safeDrawing " +
                "消费了同一个 IME inset，两者叠加会在底部导航栏与键盘之间留下约 195dp 空白，" +
                "并把带状态栏 padding 的顶部 header 推出屏幕。",
        )
    }

    // ---- helpers -------------------------------------------------------------------------

    private fun commonSources(): List<File> =
        File(composeAppRoot(), "src/commonMain/kotlin")
            .walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "swift") }
            .toList()

    /** 去掉注释，避免 KDoc 里提到的 `imePadding()` 被当成真的调用点。 */
    private fun code(file: File): String {
        assertTrue(file.isFile, "找不到源码：${file.absolutePath}")
        return file.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""//[^\n]*"""), " ")
    }

    private fun relative(file: File): String =
        file.absolutePath.removePrefix(scaffoldRoot().absolutePath + "/")

    private fun composeAppRoot(): File = File(scaffoldRoot(), "composeApp")

    /** 从 Gradle 的测试工作目录向上找 `settings.gradle.kts`，避免写死相对层级。 */
    private fun scaffoldRoot(): File {
        var dir: File? = File("").absoluteFile.normalize()
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile && File(dir, "composeApp").isDirectory) return dir
            dir = dir.parentFile
        }
        throw AssertionError("找不到 scaffold 根目录（工作目录 ${File("").absolutePath}）")
    }
}