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
        mavenLocal()
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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (mirrorFirst) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
        mavenLocal()
        mavenCentral()
        google()
        if (!mirrorFirst) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
    }
}

rootProject.name = "tidings"

include(":composeApp")
include(":androidApp")
// Tidings core：协议 + 传输 + 会话，KMP 库，不含 Compose。
// composeApp（手机 UI）与 agentNode（headless JVM）共用这一份实现。
include(":core")
// app：领域层（控制器/状态/存储/数据库/平台契约）。**零 Compose 依赖** ——
// 见 app/build.gradle.kts 的说明：UI 换一套方案时这一层原样复用。
include(":app")
// cli：一个**没有 UI** 的 Tidings 客户端。它不是产品功能，是阶段 2 的验收物 ——
// 它只链接 :app（+ :agentNode），所以只要能编译运行，就证明领域层里没有 Compose。
include(":cli")
// testsupport：源码级守卫测试共用的读盘工具。**只被 testImplementation 引用**，
// 因此永远不会出现在 APK / Tidings.app 里（它曾经在 :app 的 main 源集，实测被打进 app-jvm.jar）。
include(":testsupport")
// agentNode：agent 侧节点 + localhost HTTP 控制面（JVM only，**库**；由 desktopApp 同进程承载）。
include(":agentNode")
// desktopApp：桌面壳（Compose Desktop），与 composeApp 共用同一份 UI 与控制器。
include(":desktopApp")

