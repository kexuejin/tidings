// SPDX-License-Identifier: GPL-3.0-only

plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    // agentNode 是纯 JVM 模块。必须在根工程声明一次：Kotlin 插件已经在
    // classpath 上（kotlinMultiplatform 带进来的），模块里再写 version 会被
    // Gradle 拒绝（"already on the classpath with an unknown version"）。
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.sqldelight) apply false
}

// P2-21：硬编码 CJK 文案的门禁。实现（词法器 + 基线棘轮）在
// tools/cjk-literal-gate.gradle.kts，根脚本只挂一行，免得把构建脚本淹掉。
//   ./gradlew :cjkLiteralGate            扫描生产源码并与基线比对
//   ./gradlew :cjkLiteralGateBaseline    有意更新基线（棘轮调整）
//   ./gradlew :cjkLiteralGateSelfTest    自检词法器
apply(from = "tools/cjk-literal-gate.gradle.kts")

// 设计契约门禁：spec 与代码逐键漂移、token 消费率棘轮、状态槽覆盖。
// 实现（投影器 + 两个棘轮）在 tools/design-contract-gate.gradle.kts 与同目录的 Python 工具。
//   ./gradlew :designSpecDrift         spec↔code 逐键一致 + 书写段引用完整
//   ./gradlew :designSpecProject       改了代码后重新投影 design-spec.json
//   ./gradlew :designTokenUsageGate    token 消费率棘轮（只降不升）
//   ./gradlew :designStateSlotGate     状态槽覆盖棘轮
//   ./gradlew :designStateSlotReport   打印状态槽覆盖报告
//   ./gradlew :designContractSelfTest  三个工具的量具自检
apply(from = "tools/design-contract-gate.gradle.kts")


// 注释里的 `X.kt:NN` 行号引用会被重构悄悄冲掉且毫无编译错误；
// 这条门禁把它换成符号名。注册 :staleCommentRefGate / :staleCommentRefGateBaseline。
apply(from = "tools/stale-comment-ref-gate.gradle.kts")

// 施工痕迹门禁：注释里的日期 / 真机型号 / 内网地址 / 本机路径 / 决议号 /
// 内部工作笔记指针。这些形态读起来都像正常技术说明，只有变成会报红的门禁，
// `docs/relay-comment-audit.md` §4 那六条政策才真的生效。
apply(from = "tools/construction-trace-gate.gradle.kts")
// 领域层零 Compose 依赖：换掉 UI 方案时 :app 原样复用。
// 它是根任务而非单元测试 —— 单元测试要先编译 :app，那时编译器会先于守卫失败，
// 「守卫红了」是错觉。见 tools/domain-compose-free-gate.gradle.kts 的说明。
apply(from = "tools/domain-compose-free-gate.gradle.kts")

// 打包镜像的 JDK 模块门禁：jpackage 的默认模块集不含代码用到的每个 JDK 包，
// 缺了只在**打包产物**里抛 NoClassDefFoundError，而且会被桌面端刻意的容错吞掉
// （已实测：装机版缺 jdk.httpserver ⇒ 界面全正常、8788 永不监听）。
//   ./gradlew :jlinkModulesGate          先打包再核对镜像
//   ./gradlew :jlinkModulesGateSelfTest  量具自检
apply(from = "tools/jlink-modules-gate.gradle.kts")
