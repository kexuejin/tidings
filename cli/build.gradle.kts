// SPDX-License-Identifier: GPL-3.0-only

// `:cli` —— 一个**没有 UI** 的 Tidings 客户端。
//
// ## 它存在的理由（不是"顺带做个命令行工具"）
//
// 阶段 2 的验收判据是「UI 是不是真的只是观察者」。这句话不能被"分模块了"证明，
// 只能被**一个真的没有 UI 的程序**证明：如果它去掉 `:composeApp` 之后还能起控制器、
// 发消息、拿回执、读收件箱，那这个边界成立；只要有一条做不到，就说明领域层里
// 还有 UI 耦合没解干净。
//
// 所以它不是产品功能（用户没有理由用命令行发消息），而是**可执行的验收物**。
// 它进版本库是为了让这条判据以后每次都能重跑，而不是一次性截图。
plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // **只依赖 :app**（它会传递 :core）。刻意不依赖 :composeApp ——
    // 那会把一份 Compose 栈拖进这个"没有 UI"的程序里，验收就假了。
    implementation(project(":app"))
    // :core 是 api 暴露的类型（Timings / NodeRuntime / ScreenPhase）真正住的地方。
    // 不写这一行，上面那行只是 implementation —— 它的 api 不会传递过来。
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    // 阶段 1 的验收测试要在「没有桌面壳」的 classpath 上建一条链跑通 —— 编排层必须是
    // **测试依赖**：主源集仍只链接 :app（Main.kt 的 KDoc 把这条写成了验收条件本身）。
    testImplementation(project(":orchestration"))
    testImplementation(libs.kotlin.test)
}

application {
    mainClass.set("com.kapp.tenon.cli.MainKt")
}
