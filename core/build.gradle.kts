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
        namespace = "app.relay.core"
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
