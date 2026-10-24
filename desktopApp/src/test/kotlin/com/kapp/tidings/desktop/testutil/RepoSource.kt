// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop.testutil

import java.io.File

/**
 * 读仓库内源码的**唯一**入口（本模块测试专用）。
 *
 * ## 为什么需要它（而不是每个守卫各写一份）
 *
 * desktopApp 里有 5 个源码级守卫，原本各自实现了一遍"从工作目录往上找目标文件"：
 * 有的走到 `desktopApp/src/main/kotlin/...`、有的在 `src/main/kotlin/...` 与
 * `desktopApp/src/...` 之间二选一、有的写死最多回退 6 层。
 *
 * 它们各自都能在自己作者的那台机器上跑绿，但**结论取决于"谁在哪里跑测试"**：
 * Gradle 把测试 JVM 的工作目录设成模块目录（`desktopApp/`），IDE 常常设成模块或仓库根，
 * CI 可能是别的。于是同一条结构守卫在一个入口下"找不到文件"、在另一个入口下正常 ——
 * 症状是"偶发红/偶发绿"，根因是环境而不是代码。这正是一次"环境依赖测试"普查要消灭的形态。
 *
 * ## 判据
 *
 * 起点取**测试类自己的代码源位置**（`protectionDomain.codeSource`），它跟着构建产物走，
 * 任何工作目录下都一致；从那里向上找 `settings.gradle.kts` 得到仓库根。
 *
 * 找不到时**必须红**并说出找过哪些起点：源码守卫的判据是"源码里没有某个东西"，
 * 一个读不到源码却静默通过的守卫，比没有守卫更糟。
 */
object RepoSource {

    /** 仓库根。每次调用都重新解析（不缓存：极端情况下测试之间可能换工作副本）。 */
    fun root(): File {
        val codeSource = runCatching {
            File(RepoSource::class.java.protectionDomain.codeSource.location.toURI())
        }.getOrNull()
        val starts = listOfNotNull(codeSource)
        for (start in starts) {
            var dir: File? = start.absoluteFile.normalize()
            while (dir != null) {
                if (File(dir, "settings.gradle.kts").isFile) return dir
                dir = dir.parentFile
            }
        }
        throw AssertionError(
            "找不到仓库根（起点：" + starts.joinToString { it.path } +
                "）—— 源码守卫读不到目标时必须红，不能静默通过",
        )
    }

    /** 仓库内某个文件的真实路径；不存在就报错并给出期望位置。 */
    fun file(relativePath: String): File {
        val candidate = File(root(), relativePath)
        check(candidate.isFile) {
            "源码读不到：" + relativePath + "（仓库根 " + root().path + "）—— 路径过期或文件已搬走"
        }
        return candidate
    }

    /** 读仓库内某个源码文件的全文（仓库相对路径）。 */
    fun read(relativePath: String): String = file(relativePath).readText()
}
