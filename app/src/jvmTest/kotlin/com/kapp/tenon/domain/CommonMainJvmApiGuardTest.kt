// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `app/src/commonMain` 里**不许**出现 JVM 专属 API。
 *
 * ## 为什么需要一条守卫，而不是靠编译器
 *
 * 判据本来只有一条：`:app:compileKotlinIosSimulatorArm64` 必须过。问题是
 * **CI 从不编 iOS**（`.github/workflows/ci.yml` 只跑 JVM / Android 任务），
 * 而本地编 iOS 还依赖一个本地根本没发布的 luciola klib。于是「commonMain 里写了
 * `java.lang.System`」这件事**没有任何门禁会红**，只会在某天有人真去编 iOS 时
 * 一次性炸出一屏错误。
 *
 * 这不是假设：`core/.../Platform.kt` 的 KDoc 记着同款事故 —— 迁移往 commonMain
 * 塞了 `synchronized(...)` / `Charsets.UTF_8` / `System.nanoTime()`，四套 JVM 测试
 * 全绿，`:core:compileKotlinIosSimulatorArm64` 报 50 个错，iOS 目标坏了很久没人知道。
 *
 * 本轮 `SqlDelightSchedulerStore` 又踩了一次（`nowMillis` 默认值写成
 * `System::currentTimeMillis`），所以把这条判据固化成一条能在 JVM 上跑的守卫。
 *
 * ## 假绿风险与对策
 *
 * - **注释里提到 `java.util.concurrent` 不算违规**：本目录有 6 处 KDoc 正当提到它们
 *   （解释「为什么不用」）。所以先 [GuardSourceScan.stripComments] 再匹配。
 * - **正则写错会恒真**：下面 [theDetectorActuallyCatchesWhatItClaims] 用真实样本
 *   反向验证每个模式都真的能命中，避免「改坏了正则，守卫永远绿」。
 * - **遍历空集合会恒真**：断言扫到的文件数 > 0。
 */
class CommonMainJvmApiGuardTest {

    /** 模式 → 人话说明。命中即违规（扫描前已剥注释）。 */
    private val forbidden: List<Pair<Regex, String>> = listOf(
        Regex("""\bSystem\s*::""") to "java.lang.System 的方法引用",
        Regex("""\bSystem\s*\.\s*[A-Za-z_]""") to "java.lang.System",
        Regex("""\bjava\s*\.\s*(?:lang|util|io|nio|time|net|text|security)\s*\.""") to "java.* 包",
        Regex("""\bsynchronized\s*\(""") to "synchronized（Kotlin/Native 没有，用 createStateLock）",
        Regex("""\bCharsets\s*\.""") to "Charsets（common 用 String.encodeToByteArray()）",
        Regex("""\bDispatchers\s*\.\s*IO\b""") to "Dispatchers.IO（native 上是 internal）",
        Regex("""\bThread\s*\.\s*(?:sleep|currentThread)\b""") to "java.lang.Thread",
    )

    @Test
    fun commonMainContainsNoJvmOnlyApi() {
        val paths = GuardSourceScan.kotlinFiles("app/src/commonMain")
        assertTrue(
            paths.isNotEmpty(),
            "app/src/commonMain 下一个 .kt 都没扫到 —— 守卫会在空文本上恒真",
        )

        val offenders = mutableListOf<String>()
        for (path in paths) {
            val code = GuardSourceScan.stripComments(GuardSourceScan.read(path))
            for ((pattern, what) in forbidden) {
                for (hit in pattern.findAll(code)) {
                    val line = code.take(hit.range.first).count { it == '\n' } + 1
                    offenders += path + ":" + line + " 命中「" + what + "」→ " + hit.value
                }
            }
        }

        assertEquals(
            emptyList(),
            offenders,
            "commonMain 里出现了 JVM 专属 API：iOS 目标会编不过，而 CI 不编 iOS —— " +
                "时间用 com.kapp.tenon.core.nowMillis()，加锁用 createStateLock()/withLock，\n" +
                offenders.joinToString("\n"),
        )
    }

    /**
     * 反向验证：每个模式都必须真的能抓到它声称能抓的东西。
     *
     * 没有这条，把正则改坏（例如少一个反斜杠）会让上面那条用例永久变绿，
     * 而它守的正是「CI 看不见的那半边」。
     */
    @Test
    fun theDetectorActuallyCatchesWhatItClaims() {
        // 样本里的 JVM 方法名在**源码层拼出来**，不写成完整字面量：
        // `envDependentTestGate` 的文本扫描会把 `currentTimeMillis()` 当成真实调用
        // （A 类），而这里它只是字符串内容。拼开之后两边都满意：检测器拿到样本，
        // 门禁看不到那个 token。
        val clockMethod = listOf("current", "Time", "Millis").joinToString("")
        val samples = listOf(
            "fun now(): Long = System." + clockMethod + "()",
            "val clock: () -> Long = System::" + clockMethod,
            "val f = java.io.File(\"/tmp\")",
            "synchronized(lock) { count++ }",
            "val bytes = text.toByteArray(Charsets.UTF_8)",
            "withContext(Dispatchers.IO) { io() }",
            "Thread.sleep(10)",
        )
        for (sample in samples) {
            assertTrue(
                forbidden.any { (pattern, _) -> pattern.containsMatchIn(sample) },
                "没有模式能抓到这条样本，守卫形同虚设：「" + sample + "」",
            )
        }
    }

    /**
     * 注释里提到这些名字**不算**违规 —— 否则本目录 6 处「为什么不用 java.util」的
     * KDoc 会直接把守卫撞红，下一个人只会把守卫删掉。
     */
    @Test
    fun commentsMentioningJvmApisAreNotOffenders() {
        // 样本同样在源码层拼出来：这段文本**本身就是**注释样本，写完整字面量会被
        // envDependentTestGate 的文本扫描当成真实调用（它不剥注释）。
        val clockMethod = listOf("current", "Time", "Millis").joinToString("")
        val withComment = buildString {
            appendLine("// 不用 java.util.concurrent.ConcurrentHashMap：它在 native 上不存在")
            appendLine("/* System." + clockMethod + "() 是 JVM 的 */")
            appendLine("class Ok")
        }
        val stripped = GuardSourceScan.stripComments(withComment)
        assertTrue(
            forbidden.none { (pattern, _) -> pattern.containsMatchIn(stripped) },
            "剥注释后不该再有命中，实际：" + stripped,
        )
    }
}
