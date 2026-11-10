// SPDX-License-Identifier: GPL-3.0-only

// Tenon 编排层（orchestration）。
//
// ## 它是什么
//
// Agent 的**编排面**：工单编排（派单/跟进/中止/退单）、责任链执行体（一链一 worktree、
// 棒间交接靠提交）、自愈循环、台账治理（会话免审 / 踩坑 / 盲区 / 健康度 / 高危租约）、
// 卡片补丁（翻面 / 产物投递 / 合并落盘）。它原来寄生在 :desktopApp 里 —— 于是「编排只在
// 桌面端存在」，手机与 :cli 拿不到。
//
// ## 依赖方向（单向）
//
//     :core ← :app ← :orchestration
//     :core ← :agent-contract ← :agentNode ← :orchestration
//     :orchestration ← :desktopApp / :cli（测试）
//
// 它需要 :app 的 RelayController（卡的广播与产物投递是领域能力）与 :agentNode 的控制面
// （CLI 驱动执行走 executeCliTask）。壳层只剩装配：把 controller / scope / 端口注入进来。
//
// **零 UI**：本模块不应用 Compose 插件、不声明 Compose 依赖，也不碰 AWT / Swing。
// 这条边界由 :domainComposeFreeGate 的扫描根与本模块自己的编译类路径共同守着。
plugins {
    alias(libs.plugins.kotlinJvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // ControlPlane / StageChain / ReviewerAudit / 驱动契约（经 :agentNode 的 api 传递）。
    api(project(":agentNode"))
    // RelayController（广播链卡、投群产物）与 RelayUiState —— 编排要读领域状态。
    api(project(":app"))
    // 协议类型（ConvId / Limits / Codec 等）直接出现在编排代码里。
    api(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test)
    // 源码级守卫读仓库里的 .kt（与 :app / :agentNode 同一套工具）。
    testImplementation(project(":testsupport"))
}
