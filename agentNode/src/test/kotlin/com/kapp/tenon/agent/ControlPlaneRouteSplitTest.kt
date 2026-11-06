// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import com.kapp.tenon.testsupport.GuardSourceScan
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 控制面拆文件之后的三条结构不变量（ARP-6）。
 *
 * ## 为什么要一条守卫，而不是拆完就完
 *
 * `ControlPlane` 原来是 3891 行的单文件，**21 条路由 + 令牌门 + 分发器 + 状态**住在一起。
 * 拆开之后最容易发生的退化不是"又合并回去"，而是**慢慢长回去**：
 * 有人给某条路由加一个 `private val cache = …`，再给另一个文件加一块可变状态，
 * 一年后又是一份新的上帝文件 —— 而且没人记得当初为什么拆。
 *
 * 三条不变量各挡一种退化：
 *
 * 1. **每个控制面文件 ≤ 800 行**：超了就说明这个资源该再切一刀（而不是"就这一条塞进去"）。
 * 2. **令牌门与分发器留在装配文件**：`handle()` 的 `when` 是 21 条路由的唯一清单，
 *    `authorized()` 是唯一的鉴权口。它们被挪进某个资源文件后，"新增一条路由要改哪里"
 *    就取决于那条路由属于哪个资源 —— 而鉴权顺序（插件注册两条在鉴权之前）就会散开。
 * 3. **路由文件不声明属性**：状态只在装配文件。路由文件里出现顶层 `val`/`var`
 *    就是给自己开了一块私有内存 —— 它不会在 `stop()` 里被清、也不会在 `init` 里被装，
 *    症状是"重启之后这里不生效"，且只在真机上出现。
 *
 * 三条都按"这个模块有没有"来判，不钉行号、不钉写法顺序 —— 换实现方式不该让守卫红。
 */
class ControlPlaneRouteSplitTest {

    private val planePackage = "agentNode/src/main/kotlin/com/kapp/tenon/agent"

    /** 控制面的源码文件（拆出来的都是 `ControlPlane*.kt`，同一包、同一顶层前缀）。 */
    private fun planeFiles(): List<File> =
        GuardSourceScan.resolveDir(planePackage)
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith("ControlPlane") && it.name.endsWith(".kt") }
            .sortedBy { it.name }

    private fun mainFile(): File = planeFiles().first { it.name == "ControlPlane.kt" }

    private fun routeFiles(): List<File> = planeFiles().filter { it.name != "ControlPlane.kt" }

    @Test
    fun theControlPlaneIsActuallySplitByResource() {
        val files = planeFiles()
        assertTrue(
            files.size >= 5,
            "控制面只剩 " + files.size + " 个文件（" + files.map { it.name } + "）—— " +
                "按资源拆的分包被合回了？单文件 3891 行的可读性代价是真的（ARP-6）。",
        )
    }

    @Test
    fun everyControlPlaneFileStaysUnderTheLineBudget() {
        val tooBig = planeFiles()
            .map { it.name to it.readLines().size }
            .filter { (_, lines) -> lines > 800 }
        assertTrue(
            tooBig.isEmpty(),
            "这些控制面文件超过 800 行：" + tooBig + " —— 按资源再切一刀，而不是继续往里塞。" +
                "拆分前的单文件是 3891 行，那就是这条线想避免的形态。",
        )
    }

    @Test
    fun theTokenGateAndTheDispatcherStayInTheAssemblyFile() {
        val live = GuardSourceScan.stripComments(mainFile().readText())
        // 分发器：21 条路由的唯一清单。
        assertTrue(
            live.contains("fun handle(exchange: HttpExchange)"),
            "分发器 `handle` 不在 ControlPlane.kt 里 —— 路由清单散到各资源文件后，" +
                "「新增一条路由要改哪里」就取决于它属于哪个资源，而鉴权顺序（插件注册两条" +
                "刻意排在 authorized 之前）也会跟着散开。",
        )
        // 令牌门：唯一的鉴权口。
        assertTrue(
            live.contains("fun authorized(exchange: HttpExchange): Boolean"),
            "令牌门 `authorized` 不在 ControlPlane.kt 里 —— 鉴权只许有一个入口，" +
                "否则「哪些路由免鉴权」就没有单一事实来源。",
        )
        // 令牌本身也住在装配文件：它被放宽到 internal 是为了路由文件能读，
        // 但**声明**仍应只有一个（在构造参数里）。
        assertTrue(
            live.contains("token: String,"),
            "令牌不再是构造参数 —— 鉴权口与令牌来源必须在一起，否则测试里替身与生产走两套。",
        )
    }

    @Test
    fun routeFilesDeclareNoState() {
        val declaring = Regex("""^(?:private |internal |public )?(?:const )?(?:val|var) """)
        val offenders = routeFiles().flatMap { f ->
            f.readLines().withIndex()
                .filter { (_, line) -> declaring.containsMatchIn(line) }
                .map { (i, line) -> f.name + ":" + (i + 1) + "  " + line.trim().take(80) }
        }
        assertTrue(
            offenders.isEmpty(),
            "路由文件里出现了属性声明：" + offenders + " —— 状态只在 ControlPlane.kt" +
                "（构造参数 + 装配处的字段）。路由文件里自己存一块，既不在 `init` 里被装、" +
                "也不在 `stop()` 里被清，症状是重启后这里不生效，且只在真机上出现。",
        )
    }
}
