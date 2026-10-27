// SPDX-License-Identifier: GPL-3.0-only

// Tidings core：协议 + 传输 + 会话。
//
// 刻意不依赖 Compose：composeApp（手机 UI）与 agentNode（headless JVM）
// 共用这一份实现，UI 框架不能泄漏进协议层。
import org.jetbrains.kotlin.gradle.plugin.KotlinHierarchyTemplate

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm()

    androidLibrary {
        namespace = "com.kapp.tidings.core"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        buildToolsVersion = libs.versions.android.buildToolsVersion.get()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withJava()
    }

    // 与 composeApp 保持一致：Compose ≥1.11 不生成 iosX64()。
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "RelayCore"
            isStatic = true
        }
    }

    // JVM 与 Android 的公共实现放在同一层（`src/jvmShared/`）。
    //
    // 这三对 actual 原来在 `jvmMain/` 与 `androidMain/` 下各留一份，是逐行抄出来的：
    // 实测 `Platform` 逐字节相同、`HostAddress` 只差一行注释、`FileIo` 只差一个
    // 内部类名。抄出来的两份没有任何机制保证一起改，而漏改的两边都能编译 ——
    // 症状是「桌面续传正常、安卓 resume 从 0 重拉」，只有真机能暴露。
    //
    // 为什么不用 `create("jvmShared")` + `dependsOn`（那是最直白的写法，也是先试过的）：
    // KGP 认源集是靠**名字里的平台前缀**来推断归属的，一旦你手工造一个新源集，
    // 默认层级模板整个不生效了 —— `iosMain`/`appleMain`/`nativeMain` 从图上消失，
    // 于是那三个目录被忽略，iOS 编译报一屏
    // `Expected nowMillis has no actual declaration in module <commonMain> for Native`
    // （报错指向 commonMain，看起来像 expect 写错了，真因是源集图被改坏）。已实测。
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    applyHierarchyTemplate(KotlinHierarchyTemplate.Templates.default) {
        common {
            group("jvmShared") {
                withJvm()
                // `withAndroidTarget()` 只认 KMP 自己的 `androidTarget()`；这里用的是
                // AGP 的 `androidLibrary()`（编译目标名 `android`），所以按名字点名。
                withCompilations { it.target.name == "android" }
            }
        }
    }

    sourceSets {
        // luciola 发现引擎(JVM/Android;iOS 保留自研)。luciola 仓库 docs/MIGRATION-tidings.md 阶段一。
        val jvmMain by getting { dependencies { implementation(libs.luciola) } }
        // Android 必须用 AAR 变体:luciola-jvm 只有桌面 dylib,JNA 在 Android 上加载不到;
        // AAR 里带 jniLibs/*.so(见 luciola 仓 verifyNativeLib 的守卫)。
        val androidMain by getting { dependencies { implementation(libs.luciola.android) } }
        // iOS 必须用 **KMP 根模块**（luciola-multiplatform）：只有它发布了 iosArm64 /
        // iosSimulatorArm64 变体（klib + 内嵌 xcframework）。luciola-jvm 只有桌面 dylib、
        // luciola-android 只有 AAR —— 用错坐标 JVM 侧完全看不出问题，只有 iOS 编译才炸。
        val iosMain by getting { dependencies { implementation(libs.luciola.multiplatform) } }
        getByName("jvmSharedMain").dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.network)
        }

        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.network)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// luciola(JNA) 在测试里加载本地库；分支阶段指向本机构建产物。
//
// **优先 release**：debug 引擎的 AES-GCM/握手是未优化的，同一条数据面实测慢 6-8 倍
// （投屏 10fps×100KiB 的单帧 p95：release 6ms / debug 29ms；50MB 文件 3s vs 46s）。
// 拿 debug 的数去验收会得到「加密信道就是慢」的错误结论 —— 而真机装的是 release。
val luciolaNativeDir: String = runCatching {
    val base = file("/workspace/RustBuild/cargo-target")
    listOf(base.resolve("release"), base.resolve("debug"))
        .firstOrNull { it.resolve("libluciola_uniffi.dylib").exists() }
        ?.absolutePath
        ?: base.resolve("debug").absolutePath
}.getOrDefault("/workspace/RustBuild/cargo-target/debug")

tasks.withType<Test>().configureEach {
    systemProperty("jna.library.path", System.getProperty("jna.library.path") ?: luciolaNativeDir)
}

// ## 为什么验收用例要单独一个 JVM
//
// `ChannelFileTransferE2ETest` 是**真验收**：两条真实 luciola 加密信道、真实分块窗口、
// 真落盘 50MB 再校验 sha256，跑一次 ~40s 并持续做文件 IO。把它放进 `:core:jvmTest`
// 会把同一 JVM 里后续的 luciola 握手用例挤到超时 —— 实测连跑时
// `binaryChunkFrameFromTrustedPeerCarriesPeerEndpoint` 这类用例会在 10s 上超时，
// 单个类跑却是绿的（683/683）。所以判据拆成两条，都跑才算过：
//
//   ./gradlew :core:jvmTest              # 单元/协议（四套 JVM 套件的一项）
//   ./gradlew :core:jvmAcceptanceTest    # ≥50MB 信道验收（goal 第 3 步证据）
val jvmAcceptanceTest = tasks.register<Test>("jvmAcceptanceTest") {
    group = "verification"
    description = "luciola 信道 ≥50MB 验收（真实信道 + 真实落盘 + sha256 一致）"
    val base = tasks.named<Test>("jvmTest")
    dependsOn("jvmTestClasses")
    testClassesDirs = base.get().testClassesDirs
    classpath = base.get().classpath
    filter {
        includeTestsMatching("com.kapp.tidings.core.transport.ChannelFileTransferE2ETest")
    }
    // **必须与上面 tasks.withType<Test> 同一份 release 路径**。
    //
    // 这里原来硬编码成 `cargo-target/debug`，而本文件上面对 release 的注释已经写明：
    // debug 引擎的 AES-GCM/握手未优化，同一条数据面慢 6-8 倍（50MB：3s vs 46s），
    // 「拿 debug 的数去验收会得到『加密信道就是慢』的错误结论。」
    //
    // 实际后果（2026-10-27 查明）：这条验收长期跑在 debug 引擎上 ——
    //   ① 吞吐只有 0.15-0.16 MiB/s，被误判成协议/加密的问题，查了十几轮；
    //   ② 上游 Rust 的修复（取消安全 recv、写失败不丢帧、LinkStats 新字段）
    //      **一次都没被这条验收执行过**，因为它加载的是另一个 .so/.dylib；
    //   ③ 装上 0.4.6 的 6 字段 LinkStats 后，debug 那份旧引擎只有 4 字段，
    //      读 stats() 直接 BufferUnderflowException —— 这才把真相暴露出来。
    systemProperty("jna.library.path", System.getProperty("jna.library.path") ?: luciolaNativeDir)
    // 一次只跑一条：这条用例独占端口与磁盘，并行只会互相干扰。
    maxParallelForks = 1
}

// 单元套件里排除这条验收：它由 jvmAcceptanceTest 负责。
tasks.named<Test>("jvmTest") {
    exclude("**/ChannelFileTransferE2ETest*")
}
