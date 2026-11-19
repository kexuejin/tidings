// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「三份清单必须同步」的机器化。
 *
 * AGENTS.md §1 / README「质量门禁与验证」/ `.github/workflows/ci.yml` 写的是同一份门禁与测试
 * 清单，三处都要改。这件事此前只靠文档里的提醒，而它已经发散过两次：README 漏掉
 * `:app:jvmTest` 与 `:cli:test` 还声称与 AGENTS 同口径，CI 照 README 抄了一遍，于是
 * `:cli` 与 `:domainComposeFreeGate` 长期不在 CI 上跑。
 *
 * 发散为什么贵：本地按文档跑一遍是绿的，CI 也绿，**只是少跑了几条**——没有任何一处会报红。
 * 所以把「三处集合相等」变成一条断言。
 *
 * ## 判据
 *
 * 只比**任务集合**，不比顺序、不比换行风格：顺序无关紧要，漏掉一条才是缺陷。
 * 抽取范围是 `./gradlew` 起、反斜杠续行止的那条命令，避免把表格/正文里提到的任务名算进来
 * （`AGENTS.md` §2.1 的表格里就有一整列门禁名，但它们不带 `:` 前缀，本来也不会命中）。
 *
 * ## 抽取不到就报错，不静默跳过
 *
 * 如果哪天有人把这三段改成别的写法（比如拆成两条命令），本测试会明确说「找不到锚点」，
 * 而不是拿到空集然后判两个空集相等——那样守卫会在改坏的同一天变成一句空话。
 */
class GateListSyncGuardTest {

    /** 与 `./gradlew` 无关的前缀不在任务 token 之列：正则要求 `:` 开头，`--flag` 天然不命中。 */
    private val taskToken = Regex(":[a-z][A-Za-z0-9-]*(?::[A-Za-z0-9-]+)?")

    /** 测试任务：`模块:jvmTest|test|testAndroidHostTest`（模块名可含连字符，如 `:agent-contract`）。 */
    private val testTask = Regex(":[a-z][A-Za-z0-9-]*:(?:jvmTest|test|testAndroidHostTest)\\b")

    private fun read(path: String): String = GuardSourceScan.read(path)

    /**
     * 取「包含 [anchor] 的那条 gradle 命令」：向上找到 `./gradlew` 行，向下收集到不再以
     * 反斜杠续行为止。
     *
     * 为什么要遍历**每一处**锚点再挑一条：`ci.yml` 的注释里反复提到这些任务名（`:core:jvmTest
     * 的 8 个真引擎用例…`），从注释行向上找到的 `./gradlew` 可能是**另一条**命令（luciola 的
     * publish）。判别方法不靠位置猜测：真正的清单命令任务数最多，取 token 数最多的那条。
     */
    private fun gradleCommand(text: String, anchor: String, source: String): String {
        val lines = text.lines()
        val candidates = lines.indices.filter { lines[it].contains(anchor) }
        assertTrue(
            candidates.isNotEmpty(),
            "$source 里找不到锚点 $anchor —— 清单被改成别的写法了，请同步更新本守卫的抽取方式",
        )
        val blocks = mutableListOf<String>()
        for (anchorIdx in candidates) {
            var start = anchorIdx
            while (start >= 0 && !lines[start].contains("./gradlew")) start--
            if (start < 0) continue
            val sb = StringBuilder()
            var i = start
            while (i < lines.size) {
                sb.append(lines[i]).append('\n')
                if (!lines[i].trimEnd().endsWith("\\")) break
                i++
            }
            blocks.add(sb.toString())
        }
        assertTrue(blocks.isNotEmpty(), "$source 的 $anchor 上方没有 ./gradlew，抽取方式需要更新")
        return blocks.maxByOrNull { taskToken.findAll(it).count() }!!
    }

    private fun gatesOf(block: String): Set<String> =
        taskToken.findAll(block)
            .map { it.value }
            .filterNot { testTask.matches(it) }
            .toSet()

    private fun testsOf(block: String): Set<String> =
        testTask.findAll(block).map { it.value }.toSet()

    @Test
    fun gateTasksAreTheSameInAgentsReadmeAndCi() {
        val blocks = linkedMapOf(
            "AGENTS.md" to gradleCommand(read("AGENTS.md"), ":cjkLiteralGate", "AGENTS.md"),
            "README.md" to gradleCommand(read("README.md"), ":cjkLiteralGate", "README.md"),
            "ci.yml" to gradleCommand(read(".github/workflows/ci.yml"), ":cjkLiteralGate", "ci.yml"),
        )
        val agents = gatesOf(blocks.getValue("AGENTS.md"))
        val readme = gatesOf(blocks.getValue("README.md"))
        val ci = gatesOf(blocks.getValue("ci.yml"))

        // 空集相等是最坏的假绿：先钉住「抽到了东西」，再比集合。
        for ((name, set) in listOf("AGENTS.md" to agents, "README.md" to readme, "ci.yml" to ci)) {
            assertTrue(set.size >= 13, "$name 只抽到 ${set.size} 条门禁：$set（抽取失败或清单被删）")
        }
        assertEquals(agents, readme, "README 的门禁清单与 AGENTS.md §1 不一致：${agents - readme} / ${readme - agents}")
        assertEquals(agents, ci, "ci.yml 的门禁清单与 AGENTS.md §1 不一致：${agents - ci} / ${ci - agents}")

        // 这条测试自己的存在意义：驱动规格门禁必须在三处都跑，否则「改了 JSON 没重投影」只在本地红。
        assertTrue(":driverSpecDrift" in agents, "AGENTS.md §1 缺 :driverSpecDrift")
        assertTrue(":driverSpecSelfTest" in agents, "AGENTS.md §1 缺 :driverSpecSelfTest")

        // 「命令在清单里」不等于「它真的会失败」：`|| true` / `|| :` 让整条命令永不报红，
        // 而集合比对看不出这个差别。`--continue` 是合法的（继续跑其余门禁，整体仍非 0）。
        for ((name, block) in blocks) {
            assertTrue(
                Regex("\\|\\|\\s*(true|:)").find(block) == null,
                "$name 的门禁清单不许用 `|| true` / `|| :` 吞掉失败（集合比对看不出这个）",
            )
        }
    }

    @Test
    fun testTasksAreTheSameInAgentsReadmeAndCi() {
        val agents = testsOf(gradleCommand(read("AGENTS.md"), ":core:jvmTest", "AGENTS.md"))
        val readme = testsOf(gradleCommand(read("README.md"), ":core:jvmTest", "README.md"))
        val ci = testsOf(gradleCommand(read(".github/workflows/ci.yml"), ":core:jvmTest", "ci.yml"))

        for ((name, set) in listOf("AGENTS.md" to agents, "README.md" to readme, "ci.yml" to ci)) {
            assertTrue(set.size >= 9, "$name 只抽到 ${set.size} 条测试任务：$set")
        }
        assertEquals(agents, readme, "README 的测试清单与 AGENTS.md §1 不一致：${agents - readme} / ${readme - agents}")
        assertEquals(agents, ci, "ci.yml 的测试清单与 AGENTS.md §1 不一致：${agents - ci} / ${ci - agents}")
    }
}
