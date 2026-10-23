// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.session

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 源码级守卫：**换网回调里必须真的去重试消息积压**。
 *
 * 为什么需要它：[Messenger.retryQueuedAfterNetworkChange] 的行为有单元测试
 * （`NetworkChangeQueueRetryTest`），但那条测试只能证明「调了它就会重试」，
 * 证明不了「换网时有人调它」。接线漏了这一行，机制再对也等于不存在 —— 而症状
 * （换网后队列只涨不降、界面一直显示对方离线）从单测里完全看不见。
 *
 * 与 `OptimisticWiringGuardTest` 同一套做法：剥掉注释再匹配，避免被解释这件事的
 * 注释绊倒（本仓踩过一次：源码级守卫被自己的注释喂绿）。
 */
class NetworkChangeQueueRetryWiringTest {

    @Test
    fun theNetworkChangeListenerRetriesTheMessageQueue() {
        val source = stripComments(findNodeRuntime().readText())
        val body = bracedBlockAfter(source, "NetworkWatch.onChanged {")
        assertTrue(
            body.contains("retryQueuedAfterNetworkChange()"),
            "换网回调里没有重试消息积压：队列对「离线 + 无候选」的对端会永久静音（问题 5）。回调体：${body.take(400)}",
        )
    }

    private fun findNodeRuntime(): File {
        val relative = "src/commonMain/kotlin/com/kapp/tidings/core/session/NodeRuntime.kt"
        val starts = listOfNotNull(
            System.getProperty("user.dir")?.let(::File),
            runCatching {
                File(NetworkChangeQueueRetryWiringTest::class.java.protectionDomain.codeSource.location.toURI())
            }.getOrNull(),
        )
        for (start in starts) {
            var dir: File? = start.absoluteFile
            while (dir != null) {
                val candidate = File(dir, relative)
                if (candidate.isFile) return candidate
                dir = dir.parentFile
            }
        }
        error("找不到 $relative（起点：${starts.joinToString { it.path }}）")
    }

    /** 去掉 `//` 行注释与 `/* … */` 块注释（本文件没有含注释符号的字符串字面量）。 */
    private fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            val next = if (i + 1 < source.length) source[i + 1] else 0.toChar()
            if (c == '/' && next == '/') {
                while (i < source.length && source[i] != '\n') i++
            } else if (c == '/' && next == '*') {
                i += 2
                while (i + 1 < source.length && !(source[i] == '*' && source[i + 1] == '/')) i++
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** 从 [marker] 之后第一个左花括号起按括号配平切出整块（跨多行）。 */
    private fun bracedBlockAfter(source: String, marker: String): String {
        val start = source.indexOf(marker)
        assertTrue(start >= 0, "源码里找不到：$marker")
        val open = source.indexOf('{', start)
        assertTrue(open >= 0, "找不到 $marker 的左花括号")
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
            i++
        }
        error("括号不配平：$marker")
    }
}
