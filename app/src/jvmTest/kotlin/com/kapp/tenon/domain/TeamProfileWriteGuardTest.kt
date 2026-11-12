// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.testsupport.GuardSourceScan
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 团队档案与团队名**不许有第二个写入口**的源码级守卫。
 *
 * ## 守的是什么
 *
 * 团队名一旦能就地写在席位上（而不是从档案投影出来），「改名一次生效」立刻失效：
 * 症状是「A 群改了、B 群还是旧名」，而编译不报错、测试也可能全绿（各自都自洽）。
 * 同理，`settings.teamProfiles` 被第二处 `copy` 写过，档案的 rev / 审计口径就不再可信。
 *
 * ## 为什么只能在源码层守
 *
 * 「团队名只有一个来源、档案只有一个写入口」是跨文件的纪律，类型系统表达不了：
 * `teamName = ` 这个赋值在投影、解码、命令三处都合法，第四处就是旁路。
 * 所以这里按**文件白名单**钉住，新增一处必须同时改这条守卫 —— 让「加了个写入口」
 * 变成一次显式决定，而不是顺手写的 `copy`。
 */
class TeamProfileWriteGuardTest {

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

    /** 档案落盘的唯一出口。 */
    @Test
    fun teamProfilesArePersistedInOnePlaceOnly() {
        assertEquals(
            setOf("app/src/commonMain/kotlin/com/kapp/tenon/domain/TeamProfileWritePath.kt"),
            filesContaining("copy(teamProfiles ="),
            "团队档案只许在 `TeamProfileWritePath` 里落盘。" +
                "第二处 `copy(teamProfiles = …)` = 绕过 rev/重建/审计的旁路，" +
                "症状是「档案改了、群里还显示旧名」。",
        )
    }

    /**
     * 团队名 / 档案引用的写入点白名单。
     *
     * - `GroupMeta.kt`：模型定义 + `declarationFor` 的**保留**（广播时不许丢字段）
     * - `CodecGroup.kt`：手写解码（缺一处就是「发送端有、接收端 null」）
     * - `GroupDeclarationWritePath.kt`：`AttachTeam` / `DetachTeam` 的唯一席位写入
     * - `TeamProfileWritePath.kt`：档案 → 席位命令的适配（只把档案里的值传下去，不改写）
     * - `AgentTeamProjection.kt`：投影透传（只读地把席位字段搬进 UI 模型）
     */
    @Test
    fun teamNamesAndTeamIdsAreWrittenInTheDeclaredPlacesOnly() {
        val allowed = setOf(
            "core/src/commonMain/kotlin/com/kapp/tenon/core/protocol/GroupMeta.kt",
            "core/src/commonMain/kotlin/com/kapp/tenon/core/protocol/CodecGroup.kt",
            "app/src/commonMain/kotlin/com/kapp/tenon/domain/GroupDeclarationWritePath.kt",
            "app/src/commonMain/kotlin/com/kapp/tenon/domain/TeamProfileWritePath.kt",
            "app/src/commonMain/kotlin/com/kapp/tenon/domain/AgentTeamProjection.kt",
        )
        // 针里带**尾随空格**：`it.teamId == key` 这种比较也会命中不带空格的 `teamId =`，
        // 守卫会把只读比较误判成写入点（本轮真踩过）。
        assertEquals(allowed, filesContaining("teamName = "), "团队名的写入点越界了")
        assertEquals(allowed, filesContaining("teamId = "), "团队档案引用的写入点越界了")
    }
}
