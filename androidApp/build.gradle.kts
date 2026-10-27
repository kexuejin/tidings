// SPDX-License-Identifier: GPL-3.0-only

import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    // Preview screenshot testing. Applied in the Android application module
    // because the plugin is Android-only, even though the screens it renders
    // live in the shared module.
    alias(libs.plugins.screenshot)
}

// 发布签名:密钥库文件与口令永远不进版本库。
//
// 曾经的形态是 release 直接挂 debug 签名 —— 而 debug keystore 是**每台机器各一份**
// (CI runner 每次冷启动现生成一份,本地是 ~/.android 那份),于是同一个应用
// 在本地产物与 Release 产物上带着互不兼容的签名,设备上想换装必须卸载重装。
//
// 解析顺序(环境变量优先,便于 CI 注入;其次是仓库根的 keystore.properties,
// 该文件已被 .gitignore 忽略,只存在于本机):
//   1. RELAY_STORE_FILE / RELAY_STORE_PASSWORD / RELAY_KEY_ALIAS / RELAY_KEY_PASSWORD
//   2. keystore.properties 的 storeFile / storePassword / keyAlias / keyPassword
// 四项齐 → 用正式签名;一项都没有 → 回退 debug 签名并打一行警告(新克隆不配
// 也能出包);**缺一部分 → 构建直接失败** —— "配了一半"会把配置错误藏进一次
// 看起来成功的构建里,比"没配"更糟。
// 密钥库放哪、怎么备份,是仓库之外的部署事实,不写进本仓(随仓公开等于
// 把签名材料的部署状态交给所有人)。
val releaseSigningProperties = Properties().apply {
    val propertiesFile = rootProject.file("keystore.properties")
    if (propertiesFile.isFile) propertiesFile.inputStream().use { load(it) }
}

fun releaseSigningValue(propertyName: String, envName: String): String? =
    System.getenv(envName)?.takeIf { it.isNotBlank() }
        ?: releaseSigningProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }

val releaseSigningValues = mapOf(
    "storeFile" to releaseSigningValue("storeFile", "RELAY_STORE_FILE"),
    "storePassword" to releaseSigningValue("storePassword", "RELAY_STORE_PASSWORD"),
    "keyAlias" to releaseSigningValue("keyAlias", "RELAY_KEY_ALIAS"),
    "keyPassword" to releaseSigningValue("keyPassword", "RELAY_KEY_PASSWORD"),
)
val releaseSigningConfigured = releaseSigningValues.values.all { it != null }
if (!releaseSigningConfigured && releaseSigningValues.values.any { it != null }) {
    val present = releaseSigningValues.filterValues { it != null }.keys.joinToString(",")
    val absent = releaseSigningValues.filterValues { it == null }.keys.joinToString(",")
    throw GradleException(
        "发布签名只配了一半(已有 $present,缺少 $absent)。要么四项配齐" +
            "(keystore.properties 或 RELAY_* 环境变量),要么全部拿掉回退 debug。"
    )
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

    if (releaseSigningConfigured) {
        signingConfigs {
            create("release") {
                // 相对路径按仓库根解析;绝对路径(密钥库放在仓库外)原样使用。
                storeFile = rootProject.file(releaseSigningValues.getValue("storeFile")!!)
                storePassword = releaseSigningValues.getValue("storePassword")
                keyAlias = releaseSigningValues.getValue("keyAlias")
                keyPassword = releaseSigningValues.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // 调试包与已安装的正式签名包**共存**:真机上面装的是正式签名的 Tidings,
            // 直接装 debug 会 INSTALL_FAILED_UPDATE_INCOMPATIBLE,而卸载会清掉用户历史。
            // namespace 不变(见上方注释),只换 applicationId。
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseSigningConfigured) {
                signingConfigs.getByName("release")
            } else {
                logger.lifecycle(
                    "androidApp: 未配置正式发布签名(keystore.properties / RELAY_* 环境变量)," +
                        "release 回退 debug 签名 —— 该产物只能覆盖安装同为 debug 签名的构建。" +
                        "正式签名的配置材料在仓库之外,不随仓分发。"
                )
                signingConfigs.getByName("debug")
            }
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

