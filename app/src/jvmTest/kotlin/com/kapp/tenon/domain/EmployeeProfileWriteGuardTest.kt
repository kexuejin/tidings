// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.testsupport.GuardSourceScan
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 员工档案**不许有第二个写入口**的源码级守卫。
 *
 * ## 守的是什么
 *
 * 员工名与档案引用一旦能就地写在席位上（而不是从档案投影出来），「改名一次生效」立刻失效：
 * 症状是「A 群改了、B 群还是旧名」，编译不报错、两处各自也自洽。
 * 同理，`settings.employeeProfiles` 被第二处 `copy` 写过，档案的 rev 与审计口径就不再可信，
 * 而「一个 agent 一份档案」这条不变量会在最想不到的地方被破坏（两份名字抢同一个身份）。
 *
 * ## 为什么只能在源码层守
 *
 * `employeeName = ` 这个赋值在模型定义、解码、命令、投影四处都合法，第五处就是旁路。
 * 类型系统表达不了「只有一个来源」，所以按**文件白名单**钉住 —— 新增一处必须同时改这条守卫，
 * 让「加了个写入口」变成一次显式决定。
 */
class EmployeeProfileWriteGuardTest {

    private fun root(): File = GuardSourceScan.scaffoldRoot()

    private fun productionFiles(): List<File> {
        val modules = listOf(
            "core", "app", "composeApp", "agentNode", "orchestration",
            "cli", "desktopApp", "androidApp", "iosApp", "testsupport",
        )
        val sourceSets = listOf(
            "commonMain", "jvmSharedMain", "jvmMain", "androidMain",
            "iosMain", "appleMain", "nativeMain", "main",
        )
        return modules.flatMap { module ->
            sourceSets.mapNotNull { set -> File(root(), "$module/src/$set/kotlin").takeIf { it.isDirectory } }
        }.flatMap { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
    }

    private fun relative(file: File): String = file.absolutePath.removePrefix(root().absolutePath + "/")

    private fun filesContaining(needle: String): Set<String> = productionFiles()
        .filter { GuardSourceScan.stripComments(it.readText()).contains(needle) }
        .map { relative(it) }
        .toSet()

    /**
     * 按正则找文件。`[^)]` 天然跨行（字符类不受 DOTALL 影响），所以 `copy(` 折行写法也能命中。
     */
    private fun filesMatching(pattern: Regex): Set<String> = productionFiles()
        .filter { pattern.containsMatchIn(GuardSourceScan.stripComments(it.readText())) }
        .map { relative(it) }
        .toSet()

    /** 档案落盘的唯一出口。 */
    @Test
    fun employeeProfilesArePersistedInOnePlaceOnly() {
        assertEquals(
            setOf("app/src/commonMain/kotlin/com/kapp/tenon/domain/EmployeeProfileWritePath.kt"),
            filesContaining("copy(employeeProfiles ="),
            "员工档案只许在 `EmployeeProfileWritePath` 里落盘。" +
                "第二处 `copy(employeeProfiles = …)` = 绕过 rev/重建/审计的旁路，" +
                "症状是「档案改了、群里还显示旧名」。",
        )
    }

    /**
     * 席位上的员工字段**只许在席位写路径里改**。
     *
     * ## 为什么针是 `copy(employeeId = ` 而不是 `employeeId = `
     *
     * 裸针会把**命名实参**（`EmployeeEditDialog(employeeId = …)`）和只读比较都算成写入点，
     * 于是每加一个界面调用就要改守卫 —— 假红多了，守卫就会被人顺手放宽。
     * 真正要守的风险是「在别处改席位字段」，而那必然写成 `copy(employeeId = …)`。
     *
     * ## 构造点由另一条守卫兜
     *
     * `VirtualAgentMember(...)` 的构造点（模型定义 + 手写解码）由
     * `VirtualAgentDeclarationGuardTest` 按构造点白名单守着 —— 两条守卫各守一半，
     * 不重复覆盖同一件事。
     */
    @Test
    fun seatEmployeeFieldsAreOnlyWrittenByTheSeatWritePath() {
        val allowed = setOf(
            // 唯一允许改席位字段的文件：AttachEmployee / DetachEmployee。
            "app/src/commonMain/kotlin/com/kapp/tenon/domain/GroupDeclarationWritePath.kt",
        )
        assertEquals(allowed, filesContaining("copy(employeeId = "), "席位员工引用的写入点越界了")
        // 员工名可能不是 `copy(` 的第一个实参（`copy(employeeId = …, employeeName = …)`），
        // 所以用正则找「同一个 copy 调用里出现了 employeeName =」，而不是固定前缀。
        assertEquals(
            allowed,
            filesMatching(Regex("""copy\([^)]*employeeName =""")),
            "席位员工名的写入点越界了",
        )
    }

    /**
     * 档案 → 席位的适配只许在写路径里（它把档案里的值传下去，不改写）。
     *
     * 与上一条分开：那条守**席位**，这条守**命令**。命令的构造点如果散开，
     * 就会有人在界面里自己拼 `AttachEmployee` 绕过档案校验。
     */
    @Test
    fun seatCommandsAreConstructedByTheWritePathsOnly() {
        val allowed = setOf(
            "app/src/commonMain/kotlin/com/kapp/tenon/domain/EmployeeProfileWritePath.kt",
        )
        assertEquals(
            allowed,
            filesContaining("GroupDeclarationCommand.AttachEmployee("),
            "挂员工档案的命令只许由员工写路径发",
        )
        assertEquals(
            allowed,
            filesContaining("GroupDeclarationCommand.DetachEmployee("),
            "摘员工档案的命令只许由员工写路径发",
        )
    }
}
