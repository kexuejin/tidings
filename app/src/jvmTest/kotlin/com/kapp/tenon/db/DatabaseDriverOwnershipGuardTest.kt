// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.db

import com.kapp.tenon.testsupport.GuardSourceScan
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 数据库驱动的**所有权**守卫：原始驱动只许在 [DatabaseDriverFactory] 里开，串行化包装只许在那里套。
 *
 * ## 为什么需要它（对应第三步持有点 ③ driverCacheLock / ④ writeLockFor）
 *
 * 这两把锁保护的是两条不变量：
 *  - **每库每进程只开一条驱动**（迁移只跑一遍、不给同一个 `relay.db` 开第二条连接）；
 *  - **本进程对库的读与事务串行化**（同进程两条连接在升级写锁时会让 SQLite 跳过 busy handler
 *    直接 `SQLITE_BUSY`，见 [SerializedAccessDriver] 的 KDoc 实测）。
 *
 * 两条都由「所有驱动都从 [createDatabaseDriver] 出来」保证。而 `openPlatformDatabase` /
 * `SerializedAccessDriver` 都是 internal —— 在同一个模块里，任何文件都能绕开工厂直接开一条
 * 裸驱动，编译器不会拦。绕开的后果不是报错，是**迁移跑两遍 + 并发写偶发 BUSY**；真机上它
 * 表现为 journal 冻结数分钟（该工厂 KDoc 记录的事故），而不是一个指向这里的报错。
 *
 * 所以按源码文本钉住所有权（只读活代码，剥注释）。
 *
 * 反向验证：在任一分片里写一句 `openPlatformDatabase(host.platform)`（可编译），本用例变红。
 */
class DatabaseDriverOwnershipGuardTest {

    private val mainRoots = listOf(
        "app/src/commonMain/kotlin",
        "app/src/jvmSharedMain/kotlin",
        "app/src/jvmMain/kotlin",
        "app/src/androidMain/kotlin",
        "app/src/iosMain/kotlin",
    )

    /** 允许出现「原始驱动创建」的文件：工厂本体 + 三个平台 actual（它们只**定义**）。 */
    private val rawOpenAllowed = setOf(
        "app/src/commonMain/kotlin/com/kapp/tenon/db/DatabaseDriverFactory.kt",
        "app/src/jvmMain/kotlin/com/kapp/tenon/db/DatabaseDriverFactory.jvm.kt",
        "app/src/androidMain/kotlin/com/kapp/tenon/db/DatabaseDriverFactory.android.kt",
        "app/src/iosMain/kotlin/com/kapp/tenon/db/DatabaseDriverFactory.ios.kt",
    )

    /** 允许构造串行化包装的文件：只有工厂与包装自身的定义文件。 */
    private val wrapperAllowed = setOf(
        "app/src/commonMain/kotlin/com/kapp/tenon/db/DatabaseDriverFactory.kt",
        "app/src/commonMain/kotlin/com/kapp/tenon/db/SerializedAccessDriver.kt",
    )

    /** 允许调用 `createDatabaseDriver` 的文件：工厂定义 + 三个平台 store 工厂。 */
    private val factoryCallAllowed = setOf(
        "app/src/commonMain/kotlin/com/kapp/tenon/db/DatabaseDriverFactory.kt",
        "app/src/jvmMain/kotlin/com/kapp/tenon/domain/MessageStore.jvm.kt",
        "app/src/androidMain/kotlin/com/kapp/tenon/domain/MessageStore.android.kt",
        "app/src/iosMain/kotlin/com/kapp/tenon/domain/MessageStore.ios.kt",
    )

    @Test
    fun rawDriverIsOpenedOnlyByTheFactory() = assertNoUseOutside(
        token = "openPlatformDatabase(",
        allowed = rawOpenAllowed,
        why = "原始驱动只许由 DatabaseDriverFactory 开：绕开工厂等于给同一个库开第二条连接，迁移会跑两遍、并发写偶发 SQLITE_BUSY",
    )

    @Test
    fun serializedWrapperIsBuiltOnlyByTheFactory() = assertNoUseOutside(
        token = "SerializedAccessDriver(",
        allowed = wrapperAllowed,
        why = "串行化包装只许由 DatabaseDriverFactory 套：手搓一条裸驱动会把「本进程读/事务串行」这条不变量整个绕过去",
    )

    @Test
    fun driverFactoryIsCalledOnlyByStoreFactories() = assertNoUseOutside(
        token = "createDatabaseDriver(",
        allowed = factoryCallAllowed,
        why = "createDatabaseDriver 只许被三个平台的 store 工厂（message/agent store）调用：别处再建一条驱动会破坏「每库每进程一条」",
    )

    private fun assertNoUseOutside(token: String, allowed: Set<String>, why: String) {
        val root = GuardSourceScan.scaffoldRoot()
        val offenders = mutableListOf<String>()
        for (rootPath in mainRoots) {
            val dir = File(root, rootPath)
            assertTrue(dir.isDirectory, "扫描根不存在：$rootPath（缺输入 = 没验到，不能当通过）")
            dir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    val relative = file.relativeTo(root).path.replace("\\", "/")
                    if (relative in allowed) return@forEach
                    val source = GuardSourceScan.stripComments(file.readText())
                    if (source.contains(token)) offenders += relative
                }
        }
        assertTrue(offenders.isEmpty(), why + "。命中：" + offenders.joinToString())
    }
}
