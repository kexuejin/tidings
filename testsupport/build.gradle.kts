// SPDX-License-Identifier: GPL-3.0-only

// 源码级守卫测试共用的读盘工具（`GuardSourceScan`）。
//
// ## 为什么它必须是一个独立模块，而不是某个模块的 main 源集
//
// 它本来是 `app/src/jvmMain/` 里的一个类。那样有两个问题：
//
// 1. **它会被打进发货产物**。实测 `app-jvm.jar` 里有
//    `app/relay/testsupport/GuardSourceScan.class`（12KB），而那个 jar 就在
//    `Tidings.app/Contents/app/` 里 —— 测试代码进了用户机器。
// 2. 它只能用 `testImplementation` 引用，而 KMP **没有 Gradle 的 `testFixtures`**：
//    `:app` 的 `jvmTest` 对 `:composeApp` 不可见，反之亦然。
//
// 独立模块同时解决两件事：谁需要谁 `testImplementation(project(":testsupport"))`，
// 而它永远不进任何 `main` 源集，所以绝不可能被打包。
//
// 依赖面刻意只有 JDK：这里若混进 Compose 或 :app，等于把「测试工具」变成
// 新的耦合来源。
plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    // 它的使用者是测试，但工具本身不依赖任何测试框架 —— 保持最小。
}
