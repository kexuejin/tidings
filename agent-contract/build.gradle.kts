// SPDX-License-Identifier: GPL-3.0-only

// Tidings agent 运行时契约（agent-contract）。
//
// ## 它是什么
//
// 驱动层的**纯接口模块**：一次 agent 会话的生命周期（openSession / executeTask /
// handleAction / queryProgress）、驱动向上层报告的事件七型（其中 Artifact 是进程内事件、
// 不进协议帧 —— 本机路径绝不进线上载荷）、能力清单与进度快照。
// 这里只有声明与不可变数据，**没有任何实现、没有任何 IO**。
//
// ## 为什么从 :agentNode 抽出来
//
// 契约原先住在 :agentNode 的 drivers 包里，与「谁来实现它」绑在一起。抽出来的效果是：
// 编排层（:orchestration）与验收物（:cli 的假驱动测试）可以只依赖契约，不必把整个
// 控制面（Ktor 控制面、子进程发现、插件宿主）拖进依赖图。
//
// 依赖方向是单向的：:agent-contract ← :agentNode ← :orchestration / :desktopApp。
// 本模块**不许**反向依赖任何上游模块，也不许引入 Compose / Ktor / 进程与文件系统。
//
// ## 包名为什么仍是 com.kapp.tenon.agent.drivers
//
// 抽模块是「零行为变更」的搬移：类型全限定名不变，于是 20 个使用点的 import 一行不用动，
// 反悔成本也低。拆包名属于另一次重构，不与这次混在一起。
plugins {
    alias(libs.plugins.kotlinJvm)
    // 契约里三个数据类带 @Serializable（DriverCapability / SessionHandle /
    // TaskProgressSnapshot）：序列化形状是接口的一部分，不是实现细节。
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // 契约的公开类型上带 kotlinx.serialization 注解与生成序列化器，消费者要能直接
    // encode/decode 它们 ⇒ 用 api 而不是 implementation。
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test)
    // 源码级守卫读的是仓库里的 .kt（契约 KDoc 里的两条编排不变量、接口成员清单）。
    testImplementation(project(":testsupport"))
}
