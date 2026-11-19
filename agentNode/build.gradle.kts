// SPDX-License-Identifier: GPL-3.0-only

// Tenon agentNode：本机常驻的 agent 侧节点 + localhost HTTP 控制面。
//
// JVM only。设计要点：agent 完全不碰 IM 协议 ——
// Codex / Claude 等 agent 那一侧只 POST 到 127.0.0.1:8788，由承载方用 :core 的
// 协议实现去和局域网内的对端设备或桌面节点对话。这样 agent 集成面就是一个 JSON HTTP 接口，
// 换宿主（agent 插件、脚本、别的 agent）都不用改协议层。
//
// 这是一个**库**，不是一个可运行进程：本模块只产出 ControlPlane 等类，
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
    // 驱动契约住在独立的纯接口模块（:agent-contract）：AgentDriver / DriverEvent /
    // SessionHandle / TaskProgressSnapshot / DriverCapability 与驱动键归一都从那里来。
    // 用 api 而不是 implementation —— 这些类型出现在 DriverRegistry 的公开签名上
    // （register(AgentDriver) / listDrivers(): List<AgentDriver>），实现层必须能看见契约。
    api(project(":agent-contract"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test)
    // 源码级守卫读仓库里的 .kt（驱动键单一来源、交接/卡槽两条不变量）。
    // 与 :app / :desktopApp 同一套工具；它只被测试引用，永不进产物。
    testImplementation(project(":testsupport"))
}

/**
 * 驱动自检：把「这家 CLI 会怎么被拉起」一次摊开（找到? / 路径 / 版本 / 登录态 /
 * 模型数 / sandbox HOME 覆盖 / 生产装配的 argv 预览）。
 *
 * 为什么是个 Gradle 任务而不是产品功能：驱动缺陷几乎都是静默的（退出码 0、事件流正常），
 * 只有真机派单才暴露。有了它，改一行 argv 模板就能当场看见结果，
 * 不必每次都走「装信任 → 配对 → 真派单」。
 *
 * 任务住在驱动库（而不是编排层或桌面壳）：它问的每一件事都属于这一层，
 * 库被别的产品拿走时自检能力跟着走。用法见 `DriverDoctorMain` 的 KDoc。
 */
tasks.register<JavaExec>("driverDoctor") {
    group = "verification"
    description = "Runs the local driver self-check (discovery / auth / models / real argv)."
    mainClass.set("com.kapp.tenon.agent.drivers.DriverDoctorMainKt")
    classpath = sourceSets["main"].runtimeClasspath
}

/**
 * 有一类守卫读的是**仓库根上的文档**（`GateListSyncGuardTest` 比对 AGENTS.md / README /
 * CI 三处清单），Gradle 看不见这层依赖：只改文档时 `:agentNode:test` 会被判 UP-TO-DATE
 * 直接跳过，本地于是永远绿的 —— 而 CI 是全新检出、必然重跑，两边结论相反。
 *
 * 把这三份文件声明成任务输入，本地的「改文档没同步清单」才会当场红。
 */
tasks.named<org.gradle.api.tasks.testing.Test>("test") {
    inputs.files(
        rootProject.file("AGENTS.md"),
        rootProject.file("README.md"),
        rootProject.file(".github/workflows/ci.yml"),
    ).withPropertyName("repositoryGateListDocs")
}
