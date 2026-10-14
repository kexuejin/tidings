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

rootProject.name = "relay"

include(":composeApp")
include(":androidApp")
// Tidings core：协议 + 传输 + 会话，KMP 库，不含 Compose。
// composeApp（手机 UI）与 agentNode（headless JVM）共用这一份实现。
include(":core")
// agentNode：agent 侧节点 + localhost HTTP 控制面（JVM only，**库**；由 desktopApp 同进程承载）。
include(":agentNode")
// desktopApp：桌面壳（Compose Desktop），与 composeApp 共用同一份 UI 与控制器。
include(":desktopApp")

