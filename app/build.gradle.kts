// SPDX-License-Identifier: GPL-3.0-only

// Tidings 领域层：控制器、状态、存储、数据库、平台契约。
//
// ## 这个模块存在的唯一目的
//
// 把「一套 UI 方案」与「应用事实」分开。`:app` **不应用 Compose 插件、不声明任何
// Compose 依赖** —— 所以这里任何一次 `import androidx.compose.*` 都是**编译期**
// Unresolved reference，而不是一条要靠人守的约定。
//
// 判据不是"看起来分层了"，而是：去掉 `:composeApp` 之后这个模块仍然能编译、
// 它的测试仍然能跑（它们测控制器、存储、协议编排，不测像素）。
//
// ## 为什么它自己带 SQLDelight
//
// 库定义（`.sq` / `.sqm`）与"消息怎么落盘"是同一个事实：表结构服务于
// `SqlDelightMessageStore` 的查询。把它们分到两个模块，等于让 schema 与消费它的
// 查询隔着一条模块边界 —— 加一条查询要改两个 build 文件。
//
// ## 依赖方向
//
//     :core  <-  :app  <-  :composeApp  <-  :desktopApp / :androidApp / :iosApp
//
// `:app` 依赖 `:core`（协议、传输、会话）；它**不知道**有没有 UI。
import org.jetbrains.kotlin.gradle.plugin.KotlinHierarchyTemplate

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvm()

    androidLibrary {
        // **不能是 com.kapp.tidings.app**：那是 :androidApp（应用壳）的 namespace，而 Android 的
        // Manifest 合并器要求每个模块的 namespace 全局唯一 —— 撞了会在
        // :androidApp:processDebugMainManifest 报
        // "Namespace 'com.kapp.tidings.app' is used in multiple modules and/or libraries"。
        //
        // 这个值不影响任何 Kotlin 包名（源码仍在 com.kapp.tidings.*），只用于 R 类与 Manifest
        // 归属。用 .domain 后缀表明它是**库**而不是应用壳。
        namespace = "com.kapp.tidings.domain"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        buildToolsVersion = libs.versions.android.buildToolsVersion.get()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withJava()
        // 宿主单测：androidMain 里的 internal 只有 hostTest 那条编译看得到。
        withHostTestBuilder {}.configure {}
    }

    // JVM 与 Android 的公共实现放在同一层（`src/jvmSharedMain/`）。
    //
    // :app 里有两对 actual 是**逐字重复**的（SettingsStore、StateLock）：
    // 拆模块时它们从 :composeApp 一起搬过来，各自的 jvm/android 版本一字不差。
    // 抄出来的两份没有任何机制保证一起改，而漏改的两边都能编译。
    //
    // 写法照抄 :core —— 包括那条踩坑记录：**不要用 `create("jvmShared")` + `dependsOn`**。
    // KGP 靠名字里的平台前缀推断源集归属，手工造新源集会让默认层级模板失效
    // （iosMain/appleMain/nativeMain 从图上消失，iOS 报一屏 "has no actual declaration"）。
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    applyHierarchyTemplate(KotlinHierarchyTemplate.Templates.default) {
        common {
            group("jvmShared") {
                withJvm()
                // `withAndroidTarget()` 只认 KMP 的 `androidTarget()`；这里用的是 AGP 的
                // `androidLibrary()`（编译目标名 `android`），所以按名字点名。
                withCompilations { it.target.name == "android" }
            }
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            // iOS 壳只 import 这一个 framework；UI 层的 RelayShared 会再导出它。
            baseName = "RelayDomain"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.network)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            // 注意这里**没有** compose.* —— 见文件头的说明。加一行就等于把
            // 「领域层不依赖 UI」这条不变量交给编译器去发现（而它会发现不了：
            // 依赖加上了，import 就合法了）。
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.native.driver)
        }
        val androidHostTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
        // 源码级守卫共用的读盘工具（独立模块，只被测试引用 —— 它曾经住在 :app 的
        // main 源集，实测被打进了 app-jvm.jar，也就是会随 Tidings.app 发到用户机器上）。
        val jvmTest by getting {
            dependencies {
                implementation(project(":testsupport"))
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            // 落盘/时序类断言用 runTest + withTimeout，不用 Thread.sleep 猜时间。
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

sqldelight {
    databases {
        create("RelayDatabase") {
            packageName.set("com.kapp.tidings.db")
            dialect("app.cash.sqldelight:sqlite-3-38-dialect:2.0.2")
        }
    }
}
