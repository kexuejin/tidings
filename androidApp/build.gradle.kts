// SPDX-License-Identifier: GPL-3.0-only

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    // Preview screenshot testing. Applied in the Android application module
    // because the plugin is Android-only, even though the screens it renders
    // live in the shared module.
    alias(libs.plugins.screenshot)
}

android {
    // Enables the screenshotTest source set. The references it compares against
    // are committed, and a human looks at them before they are re-recorded.
    experimentalProperties["android.experimental.enableScreenshotTest"] = true

    // **namespace 必须与 applicationId 一致**（都是 `com.kapp.tidings`）。
    //
    // 原来写的是 `com.kapp.tidings.app`，那是错的，而且错得有连锁反应：
    //
    // 1. 应用身份是 `com.kapp.tidings`（applicationId），但 R 类/Manifest 归属写成 `com.kapp.tidings.app`
    //    —— 它是 applicationId 的**子串**，于是 `am start` 只能写成
    //    `com.kapp.tidings/.app.MainActivity`，既反直觉又不必要；
    // 2. `com.kapp.tidings.app` 同时又是 `:app`（领域层）124 个文件的 Kotlin 包名，
    //    两个毫无关系的东西撞成了同一个字符串；
    // 3. 追根是 `:composeApp` 先占了 `com.kapp.tidings` 这个 namespace（见它的 build 文件）。
    //
    // 那一处也一并改了：库模块占走应用身份的 namespace 才是最初的错。
    namespace = "com.kapp.tidings"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.android.buildToolsVersion.get()

    defaultConfig {
        applicationId = "com.kapp.tidings"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.compileSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation(project(":composeApp"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)

    // Screens go here: androidApp/src/screenshotTest/...
    screenshotTestImplementation(libs.screenshot.validation.api)
    screenshotTestImplementation(libs.compose.ui.tooling)
    screenshotTestImplementation(project(":composeApp"))
    // 预览数据要造真实时间戳（相对时间恒定 ⇒ 参考图恒定），`nowMillis()` 在 :core。
    // composeApp 把它当 implementation，不对外暴露，所以这里单独声明。
    screenshotTestImplementation(project(":core"))

    // 纯 JVM 单测（不需要设备/Robolectric）：目前钉的是「测试注入口只在可调试构建里注册」
    // 这类**纯判定**。宿主 APK 里能单测的东西不多，但这类安全闸门值得一条。
    //
    // 用 kotlin-test-junit 而不是目录里的 libs.kotlin.test：后者是 KMP 工件
    // （org.jetbrains.kotlin:kotlin-test），在纯 Android 模块里解析不到 jvm 变体，
    // 症状是 kotlin.test.Test 在单测源集里 "Unresolved reference"（实测）。
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:" + libs.versions.kotlin.get())
}

// Make the screenshot gate an automatic step of every release build.
//
// Without this the gate only runs when a human remembers to type it, and a
// release lane that builds the AAB does not run it. A green publish can then
// ship a UI change the committed references disagree with and nobody finds out.
// A gate nothing runs is a document, not a gate.
//
// A Gradle task dependency is the right seam rather than a hook in a release
// script: it fires for *every* way a release artifact can be produced (a
// release lane, a local `./gradlew bundleRelease`, fastlane), so the path
// cannot be bypassed by changing the trigger. The gate lives in the build, not
// in the caller.
//
// Release artifacts only - `assembleDebug` stays fast, so iterating on UI is
// not slowed down. A developer changing UI still runs the gate explicitly:
//   ./gradlew :androidApp:validateDebugScreenshotTest
// and re-records deliberately with updateDebugScreenshotTest after looking at
// the diff.
//
// Screenshot validation is opt-in for release builds via -PvalidateScreenshot=true
if (project.hasProperty("validateScreenshot")) {
    tasks.matching { it.name == "bundleRelease" || it.name == "assembleRelease" }.configureEach {
        dependsOn(":androidApp:validateDebugScreenshotTest")
    }
}

// Do NOT try to fix the render locale from here. It was tried and it does not work.
//
// The screenshot plugin (0.0.1-alpha16) forces the *render* JVM to en-US, hardcoded
// and not configurable: `PreviewScreenshotTestEngineInputKt.configureCommonJvmArgs`
// emits `-Duser.language=en -Duser.country=US` with `ldc` constants, and no extension
// class exists to override it.
//
// Appending our own `Test.jvmArgumentProviders` entry *does* win on the command line -
// a fork was captured with `-Duser.country=CN -Duser.language=zh` and no `en`/`US` -
// and it still changed nothing on screen, because Compose resources do not read
// `java.util.Locale`. A probe preview printed `jvm=zh_CN_#Hans` next to `compose=en`.
// The resource loader resolves through `androidx.compose.ui.text.intl.Locale.current`
// (`DefaultComposeEnvironment.rememberEnvironment`), which Layoutlib feeds from the
// preview's own configuration, not from a system property.
//
// So the locale is pinned per preview instead, with `locale = "zh-rCN"` on every
// `@Preview` in `src/screenshotTest/kotlin/`. That is the only lever that reaches
// `compose=`.
//
// History, for whoever finds the gate red again: `values-en/strings.xml` landed in
// 7fdfec2; the 98 committed references were recorded earlier at a8c8301, in Chinese.
// Between those commits every render resolved to English, so the gate went red on
// 98/98 screens - including screens no layout change could reach. That was a
// pre-existing repo defect, not a UI regression.

