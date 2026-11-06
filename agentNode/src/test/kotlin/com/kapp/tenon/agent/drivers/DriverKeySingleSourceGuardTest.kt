// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 驱动键归一化**只许有一份实现**（注册/发现/在位口径同源）。
 *
 * ## 这条守的是什么
 *
 * 同一个 agent 键在两条路上被解读：
 *
 * - 群内派单 → ControlPlane.resolveCliDriver：拿键去匹配已注册驱动的 driverId；
 * - 岗位档案 → AgentProfiles.normalizeKey：把任意写法归一成档案键。
 *
 * 两处曾经各写一份规则（小写、去 -cli、antigravity → agy）。分叉的症状是
 * 「同一个 agent 派单跑得到、查档案查不到」—— 两条路都编译通过、都不会报错，
 * 只有用户看得见。抽契约时把规则收进 DriverKeys，这条守卫保证没人再抄第二份。
 *
 * 判据先在活代码上做（剥注释）：KDoc 里解释旧实现提到 -cli 是正当的，
 * 不剥就会把解释当成违规（AGENTS §4 的「守卫被自己的注释绊倒」）。
 */
class DriverKeySingleSourceGuardTest {

    /**
     * 控制面源码的**整包**目录：ARP-6 把 `ControlPlane` 按资源拆成了 10 个文件，
     * `resolveCliDriver` 现在住在 `ControlPlanePluginGateway.kt`。
     * 判据问的是「这个模块有没有」，不是「那个文件的第几行有没有」。
     */
    private val controlPlanePackage = "agentNode/src/main/kotlin/com/kapp/tenon/agent"
    private val profiles = "agentNode/src/main/kotlin/com/kapp/tenon/agent/drivers/AgentProfiles.kt"
    private val driverKeys = "agent-contract/src/main/kotlin/com/kapp/tenon/agent/drivers/DriverKeys.kt"

    private fun live(path: String): String = GuardSourceScan.stripComments(GuardSourceScan.read(path))

    @Test
    fun bothEntryPointsDelegateToDriverKeys() {
        assertTrue(
            GuardSourceScan.livePackageOf(controlPlanePackage).contains("DriverKeys.driverIdAliases("),
            "resolveCliDriver 必须问 DriverKeys 要别名集 —— 自己再展开一遍就会与档案表分叉",
        )
        val profileCode = live(profiles)
        assertTrue(
            profileCode.contains("DriverKeys.canonicalKey("),
            "normalizeKey 必须走 DriverKeys.canonicalKey（antigravity 归一只有一份定义）",
        )
        assertTrue(
            profileCode.contains("DriverKeys.stripCliSuffix("),
            "normalizeKey 的 -cli 去后缀必须走 DriverKeys.stripCliSuffix",
        )
    }

    @Test
    fun registrationAndResolutionShareOneKeySource() {
        // 注册侧（cliDriverRegistrations / 各驱动 driverId / 凭证分派 / opencode 会话头）
        // 必须从 DriverKeys 取驱动 id，而不是手写 "codex-cli"：手写与解析规则只差一个字符
        // 时，resolveCliDriver 永远匹配不上，而两端都能编译。
        listOf(
            "agentNode/src/main/kotlin/com/kapp/tenon/agent/drivers/CliSubprocessDriver.kt",
            "agentNode/src/main/kotlin/com/kapp/tenon/agent/drivers/CliProviderEnv.kt",
            "agentNode/src/main/kotlin/com/kapp/tenon/agent/drivers/OpenCodeConfig.kt",
        ).forEach { path ->
            assertTrue(
                live(path).contains("DriverKeys.cliDriverId("),
                path.substringAfterLast('/') + " 仍在手写驱动 id —— 注册与解析必须同源（DriverKeys.cliDriverId）",
            )
        }
    }

    @Test
    fun noSecondAliasTableInProductionSources() {
        // 生产源码里除 DriverKeys 外，不许再出现 -cli 形态的**字符串字面量**：
        // 那就是别名拼接 / 手写注册 id 的形态（key + "-cli"、removeSuffix("-cli")、"codex-cli"）。
        val cliLiteral = Regex("\"[a-z0-9-]*-cli\"")
        val offenders = mutableListOf<String>()
        var scanned = 0
        listOf("agentNode/src/main", "agent-contract/src/main")
            .flatMap { GuardSourceScan.kotlinFiles(it) }
            .filter { it != driverKeys }
            .forEach { path ->
                scanned++
                if (cliLiteral.containsMatchIn(live(path))) offenders += path
            }
        assertTrue(scanned > 20, "只扫到 " + scanned + " 个生产文件，扫描根不对（守卫会静默空转）")
        assertTrue(
            offenders.isEmpty(),
            "除 DriverKeys 外还有人自己拼驱动 id：" + offenders +
                " —— 别名规则只许有一份，否则派单 / 档案 / 注册三处会分叉",
        )
    }

    @Test
    fun theFourDriverFamiliesImplementTheSharedContract() {
        listOf(
            "CliSubprocessDriver",
            "SandboxedCliDriver",
            "NativeRelayDriver",
            "ExternalPluginDriver",
        ).forEach { name ->
            val path = "agentNode/src/main/kotlin/com/kapp/tenon/agent/drivers/" + name + ".kt"
            assertTrue(
                live(path).contains(": AgentDriver"),
                name + " 没有实现 :agent-contract 的 AgentDriver —— 四类驱动必须共用同一份会话制契约",
            )
        }
    }

    @Test
    fun driverKeysActuallyCarriesTheRules() {
        // 反向：守卫如果对着一个空壳对象检查，上面几条会永远绿。这里点出关键片段。
        val keys = live(driverKeys)
        listOf("antigravity", "-cli", "stripCliSuffix", "driverIdAliases").forEach { fragment ->
            assertTrue(keys.contains(fragment), "DriverKeys 里缺关键规则片段：" + fragment)
        }
        assertTrue(
            keys.contains("const val CLI_SUFFIX"),
            "CLI 后缀必须是具名常量（散落的字面量迟早有一处写错）",
        )
        assertEquals(true, keys.contains("matchesDriverId"), "DriverKeys 少了成员判定入口")
    }
}
