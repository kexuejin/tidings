// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.testing

/**
 * 测试专用的临时目录工具。
 *
 * 为什么是 expect/actual 而不是直接写 commonMain：文件传输的往返测试需要**真实可写的目录**，
 * 而"临时目录在哪"这件事在 JVM 与 Darwin 上完全不同（`java.io.tmpdir` vs `NSTemporaryDirectory`）。
 * 把它放在 commonTest 而不是 commonMain，是因为生产代码不需要"建临时目录"这个能力 ——
 * 手机端永远写到 data/cache 目录，接口面不该为测试多长一个方法。
 */
expect fun createTempDir(prefix: String): String

/** 递归删除。测试收尾用，失败不抛（临时目录残留不该让测试红）。 */
expect fun removeRecursively(path: String)
