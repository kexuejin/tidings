// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.testsupport.GuardSourceScan
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `VirtualAgentMember` 声明构造与席位判据的**源码级守卫**。
 *
 * ## 为什么只能在源码层守
 *
 * 两条纪律的类型系统都表达不了：
 *
 * 1. **构造点收口**：广播前重建本机声明时若绕开 `declarationFor` 手写构造，
 *    团队字段（entityType / teamMembers）会被抹掉 —— 本机落库有、发出去的帧没有，
 *    group 帧不拒收、不报错（AGENTS §3 的静默丢失）。编译器不拦「少传几个参数」。
 * 2. **代表席位单一判据**：行显示名、默认席位、对话框默认选中必须引用同一个
 *    `representativeSeat`，否则多席位时三处各落在不同条声明上。
 *
 * 守卫剥掉注释再匹配（本仓踩过「守卫被自己的注释绊倒」）。
 */
class VirtualAgentDeclarationGuardTest {

    /** 生产源码根：按模块 × 源集枚举，不靠工作目录猜测（AGENTS §2.7-3 的教训）。 */
    private fun productionSourceRoots(): List<File> {
        val root = GuardSourceScan.scaffoldRoot()
        val modules = listOf(
            "core", "app", "composeApp", "agentNode", "orchestration",
            "cli", "desktopApp", "androidApp", "iosApp", "testsupport",
        )
        val sourceSets = listOf(
            "commonMain", "jvmSharedMain", "jvmMain", "androidMain",
            "iosMain", "appleMain", "nativeMain", "main",
        )
        return modules.mapNotNull { module ->
            sourceSets.mapNotNull { set ->
                File(root, "$module/src/$set/kotlin").takeIf { it.isDirectory }
            }
        }.flatten()
    }

    /** 剥注释后包含 `needle` 的文件（相对仓库根的路径），跨全部生产源码根。 */
    private fun filesContaining(needle: String): Set<String> {
        val root = GuardSourceScan.scaffoldRoot()
        return productionSourceRoots()
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" } }
            .filter { GuardSourceScan.stripComments(it.readText()).contains(needle) }
            .map { it.absolutePath.removePrefix(root.absolutePath + "/") }
            .toSet()
    }

    @Test
    fun virtualAgentMemberIsOnlyConstructedInsideDeclarationForAndTheDecoder() {
        // data class 声明本身 + declarationFor 的构造（都在 GroupMeta.kt）与解码侧兜底。
        val found = filesContaining("VirtualAgentMember(")
        assertEquals(
            setOf(
                "core/src/commonMain/kotlin/com/kapp/tenon/core/protocol/GroupMeta.kt",
                "core/src/commonMain/kotlin/com/kapp/tenon/core/protocol/CodecGroup.kt",
            ),
            found,
            "VirtualAgentMember 构造点必须收口：重建/新增声明走 declarationFor（它从 previous 原样" +
                "带回团队字段），解码侧是唯一的例外。出现新的构造点 = 又一条会把团队字段抹掉的广播路径。",
        )
    }

    @Test
    fun declarationForIsDefinedInCoreAndUsedByTheGroupWritePath() {
        val core = GuardSourceScan.read("core/src/commonMain/kotlin/com/kapp/tenon/core/protocol/GroupMeta.kt")
        assertTrue(
            GuardSourceScan.stripComments(core).contains("fun declarationFor("),
            "declarationFor 必须定义在 core（四端共享；app 不该自己再写一份）",
        )
        // 两条声明构造路径（D6 之后：建群随群的席位 / 入驻新增；广播重建只携带、
        // 不再构造 —— 自动生成已删）。都必须走 declarationFor ——
        // 少一处就是少一处团队字段回填。
        val groups = GuardSourceScan.read("app/src/commonMain/kotlin/com/kapp/tenon/domain/RelayControllerGroupsComponent.kt")
        assertEquals(
            1,
            Regex("declarationFor\\(").findAll(GuardSourceScan.stripComments(groups)).count(),
            "建群随群的席位构造必须走 declarationFor（团队字段回填的唯一入口）",
        )
        // 入驻/编辑的席位构造已随写路径收口搬进 `GroupDeclarationWritePath.kt`：
        // 那里恰好一处（`planJoinSeat`）。搬走后 ProjectDirectory 里**不该再有**构造点 ——
        // 出现第二处就是又一条绕过命令的写入路径。
        val writePath = GuardSourceScan.read(
            "app/src/commonMain/kotlin/com/kapp/tenon/domain/GroupDeclarationWritePath.kt",
        )
        assertEquals(
            1,
            Regex("declarationFor\\(").findAll(GuardSourceScan.stripComments(writePath)).count(),
            "入驻新增的席位构造必须走 declarationFor，且只许在写路径里有一处",
        )
        val projectDirectory = GuardSourceScan.read(
            "app/src/commonMain/kotlin/com/kapp/tenon/domain/RelayControllerProjectDirectoryComponent.kt",
        )
        assertEquals(
            0,
            Regex("declarationFor\\(").findAll(GuardSourceScan.stripComments(projectDirectory)).count(),
            "入驻已收口到 GroupDeclarationWritePath —— 这里再出现构造点就是旁路",
        )
    }

    /** 显示与写入共用 representativeSeat：行显示名（app）与默认席位（core）同源。 */
    @Test
    fun theAgentRowDisplayUsesTheRepresentativeSeatCriterion() {
        val identity = GuardSourceScan.read("app/src/commonMain/kotlin/com/kapp/tenon/domain/SenderIdentity.kt")
        assertTrue(
            GuardSourceScan.stripComments(identity).contains("representativeSeat("),
            "resolveAgentOfHost 的行显示名/默认席位必须走 representativeSeat —— " +
                "与撤回按钮、团队编制对话框同一条判据（多席位时三处不得各取各的）",
        )
        val lookup = GuardSourceScan.read("core/src/commonMain/kotlin/com/kapp/tenon/core/protocol/DeclaredAgentLookup.kt")
        val live = GuardSourceScan.stripComments(lookup)
        assertTrue(
            live.contains("fun representativeSeat(") && live.contains("fun seatsOfAgent("),
            "代表席位与席位枚举都定义在 core 的 DeclaredAgentLookup.kt（单一判据的唯一住址）",
        )
        // UI 侧的两条（对话框默认席位、两端同挂）读的是 composeApp 源码，
        // 按「跨层守卫住在上层」的规矩住在 :composeApp 的 TeamRosterWiringGuardTest。
    }

    /** entityType 的取值域单一定义：比较/赋值不许对字面量写。 */
    @Test
    fun entityTypeIsNeverComparedOrAssignedAgainstALiteral() {
        val offenders = productionSourceRoots()
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" } }
            .filter { it.name != "EntityTypes.kt" }
            .map { it to GuardSourceScan.stripComments(it.readText()) }
            .filter { (_, text) ->
                // `entityType == "team"` / `entityType != "single"` / `entityType = "single"` / `entityType ?: "single"`。
                Regex("entityType\\s*[=!]=\\s*\"").containsMatchIn(text) ||
                    Regex("entityType\\s*\\?:\\s*\"").containsMatchIn(text) ||
                    Regex("entityType\\s*=\\s*\"[a-z]+\"").containsMatchIn(text)
            }
            .map { (file, _) -> file.absolutePath }
        assertEquals(
            emptySet(),
            offenders.toSet(),
            "entityType 的判定与赋值必须引用 EntityTypes，不许对字面量写 —— " +
                "取值域多写一份清单 = AGENTS §3 第 7 种缺陷的协议版。",
        )
    }
}
