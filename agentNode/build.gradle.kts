// SPDX-License-Identifier: GPL-3.0-only

// Tidings agentNode：本机常驻的 agent 侧节点 + localhost HTTP 控制面。
//
// JVM only。设计要点：agent 完全不碰 IM 协议 ——
// Codex / Claude 等 agent 那一侧只 POST 到 127.0.0.1:8788，由承载方用 :core 的
// 协议实现去和局域网内的对端设备或桌面节点对话。这样 agent 集成面就是一个 JSON HTTP 接口，
// 换宿主（agent 插件、脚本、别的 agent）都不用改协议层。
//
// 这是一个**库**，不是一个可运行进程：本模块只产出 ControlPlane / WsGateway 等类，
// 由 desktopApp 同进程复用（见 desktopApp/build.gradle.kts 的 implementation(project(":agentNode"))）。
// 独立的 CLI 入口（原 Main.kt + application 插件）已于 2026-10-14 弃用 —— 那个进程没有看护者，
// 开机无人拉起、挂了无人重启、退出留孤儿进程；现在由桌面端托盘承载（固定监听 127.0.0.1:8788、
// 广播 ag1，并带开机自启与关闭到托盘）。
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test)
}
