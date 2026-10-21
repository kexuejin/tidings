// SPDX-License-Identifier: GPL-3.0-only

import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // 桌面端只复用 composeApp 的 JVM 变体：控制器、状态、所有 @Composable 页面。
    implementation(project(":composeApp"))
    // 必须显式声明 :core —— composeApp 用的是 implementation(project(":core"))，
    // implementation 不进消费者的**编译** classpath，于是 desktopApp 里每个
    // `import app.relay.core.…` 都报 Unresolved reference 'core'。
    // 症状看着像"缺依赖"，根因是上游把协议层当成了实现细节。
    implementation(project(":core"))
    // 控制面（ControlPlane）来自 agentNode：桌面版同进程复用它，不复制一份 HTTP 路由。
    // 注意只复用控制面，不复用它的 Main —— 节点的运行时由 RelayController 拥有。
    implementation(project(":agentNode"))
    implementation(compose.desktop.currentOs)
    // `@Preview` 注解本身。桌面壳原先一个 Preview 都没有，提取出独立组件后无法在 IDE 里
    // 单独看一个 pane；`composeApp` 把这个依赖声明在自己的 androidMain 里，
    // `implementation` 不进消费者的编译 classpath，所以桌面端要自己再声明一次。
    implementation(libs.compose.ui.tooling.preview)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutines.core)
    // 桌面端的 Main 调度器。
    //
    // JVM 上 `Dispatchers.Main` **不会**自己解析成"AWT 事件线程"：没有这个依赖时拿到的是
    // `kotlinx.coroutines.internal.MissingMainCoroutineDispatcher`，一被调度就抛
    // `IllegalStateException: Module with the Main dispatcher is missing`。
    //
    // 这不是理论风险 —— 用户报的「截图报错」就是这一条：`DesktopScreenCaptureService`
    // 用 `scope.launch(Dispatchers.Main)` 起覆盖窗，于是点截图必然炸在协程里
    // （stderr 里一条未捕获异常，界面上什么也不显示，看起来就像按钮坏了）。
    //
    // 版本不在这里写死：`libs.versions.kotlinx.coroutines` 是 core 用的同一个版本引用，
    // swing 与 core 必须同版本，写死两个号迟早漂移（这正是上面那句异常的原文要求：
    // "…and ensure it has the same version as 'kotlinx-coroutines-core'"）。
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:${libs.versions.kotlinx.coroutines.get()}")
    // `Res` / `stringResource` 本身。与上面 `:core`、`@Preview` 同一个根因：`composeApp`
    // 用的是 `implementation(compose.components.resources)`，implementation 不进消费者的
    // **编译** classpath。主源集以前不需要它（桌面文案都是 Kotlin 字面量），现在
    // 「输入法自检」页的文案必须走 strings.xml —— CJK 棘轮门禁不允许新增字面量，
    // 而棘轮只降不升，加基线是禁止的。于是主源集也要能看见 `Res`。
    implementation(compose.components.resources)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    // `StringResource` 本身。`DesktopTrayMenuTest` 断言托盘模型持的是哪条资源
    // （`TrayText(Res.string.desktop_tray_…, args)`），于是要直接写出这个类型；
    // `composeApp` 用的是 `implementation(compose.components.resources)`，
    // implementation 不进消费者的编译 classpath，所以这里要自己再声明一次
    // （与上面 `:core`、`@Preview` 两处同一个根因）。
    testImplementation(compose.components.resources)
}

/** 与 `DesktopDeepLink.MESSAGE_SUFFIX` 同源；改动要同时改两侧。 */
val MESSAGE_EXTENSION = "relaymsg"

/** 与 `DesktopDeepLink.SCHEME` 对应的私有 MIME 类型。 */
val RELAY_MIME = "application/x-relay-message"

/**
 * macOS 的 URL scheme 声明。
 *
 * `CFBundleTypeRole=Viewer`：捎话只是这个链接的消费者，不是编辑器。
 */
val RELAY_URL_TYPES_XML = """
    <key>CFBundleURLTypes</key>
    <array>
        <dict>
            <key>CFBundleURLName</key>
            <string>app.relay.desktop</string>
            <key>CFBundleTypeRole</key>
            <string>Viewer</string>
            <key>CFBundleURLSchemes</key>
            <array>
                <string>relay</string>
            </array>
        </dict>
    </array>
""".trimIndent()

compose.desktop {
    application {
        mainClass = "app.relay.desktop.MainKt"

        // 堆上限。**不设这一项时 JVM 用的是自己的默认规则**：最大堆 = 物理内存 / 4。
        // 这台机器 24 GB ⇒ 默认上限 6 GB，于是 GC 几乎不感到压力、堆一路长到 1 GB+
        // 才回收。实测（jpackage 产物，启动 15 分钟、无显式上限）：
        //
        //   footprint 917 MB → 999 MB → 915 MB → 1127 MB → 1173 MB
        //
        // 构成里 380 MB 是图形侧（Skia 的 Owned physical footprint 240 MB +
        // IOSurface 128 MB + IOAccelerator 12 MB），那部分是 Compose 渲染的固有成本，
        // **改代码省不掉**，也不随堆上限变化；剩下的 VM_ALLOCATE 486 MB +
        // MALLOC_SMALL 252 MB 才是这几条参数能压的部分。
        //
        // 所以别指望压到两三百 MB：Compose Desktop 常驻 600~700 MB 是正常水位。
        //
        // 768m 是「够用且逼 GC 干活」的起点：512m 对 Compose + Skia 偏紧（会频繁 GC
        // 卡顿），1g 又贴回默认值附近、起不到约束作用。这个数字必须靠真机读数复核，
        // 不能当成定论：装上去连续 footprint，稳定在 600~700 MB 且界面不卡才算成立。
        //
        // `+UseSerialGC`：桌面端是 GUI 应用，堆压在几百 MB 时 SerialGC 的停顿与
        // 并行 GC 差别不大，但它没有额外的 GC 线程栈与元数据开销 —— 那部分在
        // footprint 里是实打实的（并发 GC 每个线程都要预留栈与本地缓冲）。
        //
        // `-Xss512k`：线程栈。默认 1 MB，进程里的 Java 线程实测 88 个（AWT-EventQueue /
        // relay-network-poll / DefaultDispatcher-worker-N / ControlPlane 的 pool-1-thread-N
        // / 各编译器线程）。
        //
        // **注意别按「线程数 × 栈上限」估收益**：栈是按需换入的，不是每线程实占常驻。
        // 实测 footprint 里的 stack 项只有 6528 KB（88 个线程的合计 ≈ 6.4 MB），
        // 所以这一条的真实收益是 5~10 MB 量级。早期曾按 91 × 1 MB 估算成 45 MB，
        // 那是错的；真正的大头是 -Xmx768m 与 UseSerialGC。
        //
        // 安全性有实测依据：用产物同款参数跑探针，512k 栈仍可递归 3607 层；
        // Compose 的 UI 递归深度远达不到，真溢出会是一次明确的 StackOverflowError。
        //
        // `-XX:MaxMetaspaceSize`：元数据区无上限时，长时间运行的 GUI 应用会持续加载
        // 类（协程、序列化、Ktor 反射）把它们留在元空间里。192m 对桌面端够用，
        // 而且超限会是可诊断的 OutOfMemoryError: Metaspace，而不是慢慢吃满整个内存。
        jvmArgs += listOf(
            "-Xmx768m",
            "-XX:+UseSerialGC",
            "-Xss512k",
            "-XX:MaxMetaspaceSize=192m",
        )

        nativeDistributions {
            // 先产出可分发目录/DMG；签名与公证交给 DSL，不手工跑 xcrun。
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Tidings"
            packageVersion = "1.0.0"
            description = "Local-first peer-to-peer messaging and agent collaboration"
            vendor = "Tidings"

            // sqldelight 的 JDBC 驱动（`sqlite-driver` → `java.sql.DriverManager`）不在
            // jlink 的默认模块集里。不显式声明时，jpackage 生成的运行时镜像只含
            // java.base / java.datatransfer / java.xml / java.prefs / java.desktop /
            // java.logging / jdk.crypto.ec —— 打出来的 .app **一启动就崩**：
            //   NoClassDefFoundError: java/sql/DriverManager
            //     at app.cash.sqldelight.driver.jdbc.sqlite.ThreadedConnectionManager.getConnection
            //     at app.relay.app.MessageStore_jvmKt.createMessageStore(MessageStore.jvm.kt:47)
            //     at app.relay.desktop.MainKt.main(Main.kt:64)
            // 这条只在**打包产物**里出现，`./gradlew :desktopApp:run` 走宿主 JDK 全量模块，
            // 因此本地怎么跑都是绿的 —— 必须用 createDistributable 后的 .app 验收。
            //
            // jdk.httpserver：`ControlPlane` 用 `com.sun.net.httpserver.HttpServer` 起
            // 回环控制面（桌面端同进程承载 agent 节点后，这条链路才是 8788 的唯一来源）。
            // 它同样不在默认模块集里，而**失败是静默的**：`DesktopExtension.syncOnce()`
            // 里那句 `catch (t: Throwable)` 是刻意写的（控制面是加装能力，绑不上不能
            // 拖垮 UI），于是 NoClassDefFoundError 被吞掉，桌面端界面完全正常、
            // UDP 17878 / TCP 17879 照常绑定，只有 8788 永远不出现 —— 2026-10-15 实测
            // 装好新版 .app 后正是这个现象（手机看不到这台机器、插件设置页显示未连接）。
            // 与 java.sql 那条同源：只在打包产物里出现，`:/desktopApp:run` 永远发现不了。
            modules("java.sql", "jdk.httpserver")

            macOS {
                bundleID = "app.relay.desktop"
                dockName = "捎话 Tidings"
                packageBuildVersion = "1"
                signing { sign.set(false) }
                notarization { appleID.set("") }
                // 应用图标。**这一项不写时 Compose 会静默使用它自带的
                // `default-icon-mac.icns`**：编译期、打包期、运行期都没有任何提示，
                // 只有把 .app 拆开比 sha256 才看得出来（实测
                // `build/compose/default-resources/1.11.1/default-icon-mac.icns`
                // 与装到 /Applications 的那份逐字节相同，都是 22ab18eb…）。
                // 所以这里除了设值，还 require 一次文件存在 —— 把"静默换回默认图标"
                // 变成一次响亮的失败。
                //
                // 图标由 `design/icon/make-macos-icns.sh` 生成：源
                // `design/icon/icon-1024.png`（满幅方形，圆角外是米白底）→ 抠底 +
                // 收缩 + 上 Apple Big Sur 的 824/1024 栅格 + 投影 → `Tidings.icns`。
                // 要改口径就改那条脚本，不要手工替换 Tidings.icns。
                //
                // 用 rootProject 定位：图标在仓库根的 `design/icon/`。
                val relayIcns = rootProject.file("design/icon/Tidings.icns")
                require(relayIcns.isFile) {
                    "找不到 macOS 应用图标：${relayIcns.absolutePath}\n" +
                        "在仓库根跑 `bash design/icon/make-macos-icns.sh` 重新生成。"
                }
                iconFile.set(relayIcns)
                // CFBundleURLTypes 是 `relay://` 能被 macOS 交给本应用的前提；jpackage
                // 不认识 URL scheme，只能靠这段原始 XML 补进 Info.plist。
                // 后缀必须与 DesktopDeepLink.SCHEME 一致（有单测钉住那一侧）。
                infoPlist { extraKeysRawXml = RELAY_URL_TYPES_XML }
                // 后缀不带点、不带 *. —— 这是 jpackage 的 --file-associations 语法，
                // 写成 "*.relaymsg" 会注册出一个字面量后缀 "*.relaymsg"。
                // 必须与 DesktopDeepLink.MESSAGE_SUFFIX 一致。
                //
                // 一律写命名实参：这个 DSL 的参数顺序是 (mimeType, extension, description)，
                // 与直觉相反。曾按 (extension, description, mimeType) 写，打包出来的
                // Info.plist 里 CFBundleTypeExtensions 变成了中文名、CFBundleTypeName
                // 变成了 MIME 串 —— 编译期毫无提示，只有拆开 .app 才看得见。
                fileAssociation(
                    mimeType = RELAY_MIME,
                    extension = MESSAGE_EXTENSION,
                    description = "Tidings Message",
                )
            }
            windows {
                menuGroup = "Tidings"
                upgradeUuid = "5B72605E-B68C-4B9F-835A-3453D83D4822"
                fileAssociation(
                    mimeType = RELAY_MIME,
                    extension = MESSAGE_EXTENSION,
                    description = "Tidings Message",
                )
            }
            linux {
                packageName = "relay-desktop"
                fileAssociation(
                    mimeType = RELAY_MIME,
                    extension = MESSAGE_EXTENSION,
                    description = "Tidings Message",
                )
            }
        }
    }
}

// 不切 JUnit Platform：libs.kotlin.test 默认走 JUnit4，和 :composeApp / :agentNode 保持一致。
// 写成 useJUnitPlatform() 而 classpath 上没有 kotlin-test-junit5，Gradle 会报「找不到引擎」。
// 临时：打印运行时 classpath，便于脱离 Gradle 直接 java -cp 启动取证据
val printRuntimeClasspath by tasks.registering {
    val cp = configurations.named("runtimeClasspath")
    doLast {
        println("RUNTIME_CP=" + cp.get().files.joinToString(":") { it.absolutePath })
    }
}
