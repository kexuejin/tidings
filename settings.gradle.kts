// SPDX-License-Identifier: GPL-3.0-only

// 仓库顺序：默认官方源在前（境外贡献者不必先走国内镜像），阿里云留作兜底。
//
// 注意 Gradle 的回退规则：只有仓库明确返回 404 时才会试下一个仓库；连不上
// （TLS 握手中断、DNS 污染等）会直接失败，不会自动落到阿里云。dl.google.com
// 在部分网络下不可达，所以给一个显式开关：加 `-PrelayMirror=aliyun`，或设环境变量
// `RELAY_MAVEN_MIRROR=aliyun`，就把阿里云提到最前面。
pluginManagement {
    val mirrorFirst =
        (providers.gradleProperty("relayMirror").orNull ?: System.getenv("RELAY_MAVEN_MIRROR")) == "aliyun"
    repositories {
        if (mirrorFirst) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        }
        mavenCentral()
        google()
        gradlePluginPortal()
        if (!mirrorFirst) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        }
    }
}

val mirrorFirst =
    (providers.gradleProperty("relayMirror").orNull ?: System.getenv("RELAY_MAVEN_MIRROR")) == "aliyun"

/**
 * `libs.versions.toml` 里钉的 luciola 版本。
 *
 * 直接读文件而不是走 `VersionCatalogsExtension`：这里只在**设置阶段**用一次，
 * 用不上版本目录的解析语义；`the<VersionCatalogsExtension>()` 在某些初始化顺序下
 * （IDE 同步、只跑 `:help`）拿不到，那时连这条提示都发不出来。
 */
val pinnedLuciolaVersion: String? = file("gradle/libs.versions.toml")
    .takeIf { it.isFile }
    ?.readLines()
    ?.firstOrNull { it.trimStart().startsWith("luciola") && it.trimStart().contains("=") && !it.trimStart().startsWith("luciola-") }
    ?.substringAfter('=', "")
    ?.substringAfter('"', "")
    ?.substringBefore('"')
    ?.trim()

val luciolaLocalOptIn = System.getenv("LUCIOLA_LOCAL") == "1"

// 钉到 `-local` 版本却没开 mavenLocal 时，Gradle 的报错只有
// `Could not resolve io.github.kexuejin:luciola-jvm:<ver>`，看着像坐标写错了。
// 在设置阶段就说清楚怎么办 —— 这是**唯一**一个可以提前发现的「环境没配好」，
// 不是可以靠改代码绕过去的错误（绕过去的下场：原生库与 bindings 不同版 ⇒ 装完启动即崩）。
if (pinnedLuciolaVersion != null && pinnedLuciolaVersion.contains("-local") && !luciolaLocalOptIn) {
    throw GradleException(
        "luciola 钉的是本地版本 $pinnedLuciolaVersion（未发布到 Maven Central），本次构建没开 mavenLocal。\n" +
            "  修法（二选一）：\n" +
            "    1) 在 luciola 仓库跑 `scripts/publish-maven-local.sh`，然后给命令加 `LUCIOLA_LOCAL=1`" +
            "（例如 `LUCIOLA_LOCAL=1 ./gradlew :core:jvmTest`）；\n" +
            "    2) 把 gradle/libs.versions.toml 的 luciola 换成已发布版本（Maven Central 上的正式版）并" +
            "同步桌面原生库（`:desktopApp:verifyLuciolaNative` 会校验）。\n" +
            "  口径说明见 AGENTS.md §1 / README「质量门禁与验证」。",
    )
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // luciola 本地验证：luciola 仓库 scripts/publish-maven-local.sh 之后，
        // 设 LUCIOLA_LOCAL=1 让本仓优先解析 ~/.m2 里的 luciola-android/jvm
        //（含未发布的 Rust 修复）。默认关闭 —— 不污染正常构建。
        if (luciolaLocalOptIn) {
            mavenLocal()
        }
        if (mirrorFirst) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
        mavenCentral()
        google()
        if (!mirrorFirst) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
    }
}

rootProject.name = "tenon"

include(":composeApp")
include(":androidApp")
// Tenon core：协议 + 传输 + 会话，KMP 库，不含 Compose。
// composeApp（手机 UI）与 agentNode（headless JVM）共用这一份实现。
include(":core")
// app：领域层（控制器/状态/存储/数据库/平台契约）。**零 Compose 依赖** ——
// 见 app/build.gradle.kts 的说明：UI 换一套方案时这一层原样复用。
include(":app")
// cli：一个**没有 UI** 的 Tenon 客户端。它不是产品功能，是阶段 2 的验收物 ——
// 它只链接 :app（+ :agentNode），所以只要能编译运行，就证明领域层里没有 Compose。
include(":cli")
// testsupport：源码级守卫测试共用的读盘工具。**只被 testImplementation 引用**，
// 因此永远不会出现在 APK / Tenon.app 里（它曾经在 :app 的 main 源集，实测被打进 app-jvm.jar）。
include(":testsupport")
// agentNode：agent 侧节点 + localhost HTTP 控制面（JVM only，**库**；由 desktopApp 同进程承载）。
include(":agentNode")
// agent-contract：驱动层**纯接口模块**（会话制契约 + 事件七型 + 驱动键归一）。
// 从 :agentNode 的 drivers 包里原样搬出：类型全限定名不变，所以这次搬移对使用方零改动。
// 依赖方向是单向的（:agent-contract ← :agentNode ← :desktopApp）：本模块不许反向依赖
// 任何上游模块，也不许引入 Compose / Ktor / 进程与文件系统 —— 编排层与「没有 UI 的验收物」
// 因此可以只依赖契约，不必把整个控制面拖进依赖图。
include(":agent-contract")
// orchestration：Agent 编排层（工单/责任链/自愈/台账/卡片补丁）。从 :desktopApp 下沉 ——
// 壳层只留装配，编排面不再是桌面端独占。依赖 :agentNode + :app + :core，零 UI。
include(":orchestration")
// desktopApp：桌面壳（Compose Desktop），与 composeApp 共用同一份 UI 与控制器。
include(":desktopApp")

