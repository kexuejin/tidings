// SPDX-License-Identifier: GPL-3.0-only

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    // RelaySettings 是 @Serializable —— 没有这个插件，注解能编译通过但
    // 不会生成 serializer()，报错会落在"存设置"那几行上，离病根很远。
    alias(libs.plugins.kotlinSerialization)
}

compose.resources {
    // Pin the generated Res class instead of relying on the group/module-name default, so
    // the import in App.kt is the same no matter what the product names its modules.
    packageOfResClass = "com.kapp.tidings.generated.resources"
    publicResClass = true
    // The module also builds for jvm and iOS, where the auto rule can skip generation and
    // leave stringResource() unresolvable. A scaffold must compile on the first try.
    generateResClass = always
}

kotlin {
    jvm()

    androidLibrary {
        // **库模块不许占用应用身份的 namespace。**
        //
        // 这里原来是 `com.kapp.tidings` —— 与 `:androidApp` 的 applicationId 完全相同，
        // 于是应用壳只能退到 `com.kapp.tidings.app`，那又撞上 `:app` 领域层的包名，
        // 最终让 `am start` 变成 `com.kapp.tidings/.app.MainActivity`。
        //
        // 用 `com.kapp.tidings.platform`：本模块 androidMain 的 18 个文件全在这个包下，
        // namespace 与实际代码归属一致。Compose 的资源类另由
        // `packageOfResClass = "com.kapp.tidings.generated.resources"` 固定，不受这里影响。
        namespace = "com.kapp.tidings.platform"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        buildToolsVersion = libs.versions.android.buildToolsVersion.get()
        minSdk = libs.versions.android.minSdk.get().toInt()
        androidResources.enable = true
        withJava()
        // 打开「宿主单测」编译：Android 平台实现（androidMain）里的 internal 只能由
        // androidHostTest 看到 —— jvmTest 是另一条编译，覆盖不到它。
        withHostTestBuilder {}.configure {}
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "RelayShared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            // The UI is driven by the real protocol/transport runtime. Without this the
            // screens can only render literals, which is exactly what the scaffold did.
            // 领域层（控制器/状态/存储/数据库/平台契约）。它零 Compose，换 UI 方案时原样复用。
            api(project(":app"))
            implementation(project(":core"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.network)

            // 这四个用 api 而不是 implementation：本模块**对外发布的是 @Composable
            // 函数**，任何消费者（`androidApp` 的截图门禁就是第一个）写一个调用点就
            // 需要 `Modifier`/`@Composable`/`Row`/`MaterialTheme` 这些类型在编译面上。
            // 用 implementation 时，症状是消费者报 `Unresolved reference 'Row'`，
            // 看起来像"缺依赖"，实际是发布面声明错了。
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            api(compose.ui)
            // 系统返回键：聊天详情要能吃掉返回键回到会话列表，而不是直接退出应用。
            // 用 implementation 而不是 api —— BackHandler 只在 App.kt 内部调用，
            // 不出现在本模块对外的 @Composable 签名里。
            implementation(libs.compose.ui.backhandler)
            // 官方自适应布局：节点页按窗口尺寸在「单列 / 列表+详情」之间切换，
            // 以及宽窗口的折行/三栏容器。自研一套阈值判断会让平板与折叠屏的行为
            // 永远停在"我们以为应该这样"，官方方案把这些规则收敛在库里。
            implementation(libs.compose.material3.adaptive)
            implementation(libs.compose.material3.adaptive.layout)
            implementation(libs.compose.material3.adaptive.navigation)
            // User-visible strings live in composeResources/values*/strings.xml and nowhere
            // else. The androidLibrary block above already sets androidResources.enable = true;
            // without it every gate stays green and the app crashes on launch.
            implementation(compose.components.resources)
            // 桌面壳迁进 commonMain 后要用 outlined 图标；desktopApp 早就依赖同一坐标里的
            // compose.materialIconsExtended，这里只是让同一套图标在 commonMain 也可用，
            // 没有引入新的坐标库。
            implementation(compose.materialIconsExtended)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            // 聊天正文的 Markdown 渲染（见 feature/chat/MarkdownMessage.kt）。
            // 只加 base + m3：m3 那个才有按 Material3 主题取色/取字阶的入口。
            // **不加 -code**（围栏代码的语法高亮）：它拖进来的 dev.snipme:highlights
            // 没有 Android 产物（要靠 AGP 的 jvm→androidJvm 兼容规则回退），而且整个
            // jar 都是 Java 21 字节码 —— 这两条都得先在真机上验过再说，而正文渲染不需要它。
            implementation(libs.markdown.renderer)
            implementation(libs.markdown.renderer.m3)
        }
        androidMain.dependencies {
            implementation(libs.compose.ui.tooling.preview)
            // CameraX + ML Kit for QR scanning (P4)
            implementation(libs.camerax.core)
            implementation(libs.camerax.camera2)
            implementation(libs.camerax.lifecycle)
            implementation(libs.camerax.view)
            implementation(libs.mlkit.barcode)
            implementation(libs.sqldelight.android.driver)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.native.driver)
        }

        // 源集名必须是 androidHostTest（:36 的 withHostTestBuilder 打开的那个）。
        // 写成 androidUnitTest 会是一个**悬空源集**：Gradle 只报一句 warning，
        // 那 5 条宿主单测（AndroidDefaultNickTest）**从来不跑** —— 提交前审查 B2。
        val androidHostTest by getting {
            dependencies {
                // Android 平台实现住在 androidMain（AndroidPlatform 的默认名判定就是它），
                // 而 jvmTest 跑的是 jvmMain/jvmShared 那条编译，看不到 androidMain 的 internal
                // —— 所以这条测试要落在 Android 的**宿主单测**源集上，跑 AGP 的 JVM 单测任务。
                //
                // 用 kotlin-test-junit 而不是 libs.kotlin.test：KMP 工件在纯 Android 单测编译里
                // 解析不到 jvm 变体时，前者能落到 JUnit4 实现（workspace-ia 的经验）。
                implementation(kotlin("test-junit"))
            }
        }
        // 源码级守卫共用的读盘工具（独立模块，只被测试引用）。
        val jvmTest by getting {
            dependencies {
                implementation(project(":testsupport"))
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            // 落盘走的是真 IO 调度器（ioDispatcher），断言"写盘有没有发生、发生了几次"
            // 需要可控的等待：runTest + withTimeout，而不是 Thread.sleep 猜时间。
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// SQLDelight 的定义（`.sq` / `.sqm`）与生成代码已搬进 :app —— 表结构服务的是
// `SqlDelightMessageStore` 的查询，两者不该隔着一条模块边界。本模块只**消费**
// `:app` 生成的 `RelayDatabase`（经 `api(project(":app"))` 传递可见）。
//
// 依赖坐标本身保留：`SqlDelightMessageStore` 持有 driver 与 queries 类型，
// 而 iOS/JVM/Android 的 driver 工厂在 :app 里 —— 这里只是不需要再生成代码。
