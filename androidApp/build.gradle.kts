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

    namespace = "app.relay.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.android.buildToolsVersion.get()

    defaultConfig {
        applicationId = "app.relay"
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

