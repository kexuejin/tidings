// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import org.junit.Assume
import java.io.File

/**
 * 真引擎用例的前置条件：这台机器上到底有没有 luciola 原生库。
 *
 * 存在的理由（一次真实的 CI 事故）：构建脚本曾经把 `jna.library.path` 默认成作者本机的
 * 绝对路径。别人 clone 之后，那不是「构建失败」——配置阶段一声不响，跑用例时才在
 * `UniffiEngineAdapter(...)` 的类初始化里抛 `UnsatisfiedLinkError` / `ExceptionInInitializerError`。
 * 症状是红的，根因是环境，而且**报错里没有一个字告诉你「去设 TIDINGS_LUCIOLA_NATIVE」**。
 *
 * 所以这里把两件事分开，与仓库的「没验到 ≠ 验坏了」同口径：
 *  - **没配**（`tidings.luciola.nativeDir` 为空）⇒ 跳过，并把「怎么配」打印出来；
 *  - **配了却没有/用不了** ⇒ **失败**（不能因为「配错了」就变成跳过，那等于把配置错误藏起来）。
 *
 * 构建侧（`core/build.gradle.kts`）负责解析目录并写入 `tidings.luciola.nativeDir`。
 */
object LuciolaNativeGuard {

    /** Gradle 解析到的目录；未解析到时为空串。 */
    private val configuredDir: String? =
        System.getProperty("tidings.luciola.nativeDir")?.takeIf { it.isNotBlank() }

    private val libNames = listOf(
        "libluciola_uniffi.dylib", "libluciola_uniffi.so", "luciola_uniffi.dll",
    )

    /** 目录里真的有库文件（只判存在，不试加载：试加载会把「版本对不上」变成跳过）。 */
    fun available(): Boolean {
        val dir = configuredDir ?: return false
        val d = File(dir)
        return d.isDirectory && libNames.any { File(d, it).isFile }
    }

    /**
     * 在每个真引擎用例的 `@Before` 里调一次。
     *
     * - 没解析到目录：`Assume` 跳过（本机/CI 没装引擎是正常状态）；
     * - 解析到了但里面没有库：`error` 失败（配错了要立刻看见）。
     */
    fun assumeAvailable() {
        if (configuredDir == null) {
            Assume.assumeTrue(
                "跳过 luciola 真引擎用例：未解析到原生库目录。" +
                    "设置 TIDINGS_LUCIOLA_NATIVE=<cargo-target/release 或 debug> 后重跑。",
                false,
            )
            return
        }
        check(available()) {
            "已配置 luciola 原生库目录，但里面没有 " + libNames.joinToString("/") +
                "：$configuredDir（显式配置了却用不了 —— 这不是跳过，是配置错误）"
        }
    }
}
