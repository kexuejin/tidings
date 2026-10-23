// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.session

import com.kapp.tidings.core.persist.FileMessageStore
import com.kapp.tidings.core.protocol.Profile
import com.kapp.tidings.core.protocol.Timings
import com.kapp.tidings.core.transport.DIRECTION_IN
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * 周期维护必须**真的**裁剪信封日志。
 *
 * 为什么单独钉一条「接线」测试：`FileMessageStore.trim` 早就实现了、也早有单测，但
 * **生产路径一次都没调用过** —— 于是真机桌面端一天就长到 12.9 万行 / 72 MB，而同一时刻
 * 消息表只有 1235 条。功能写了不等于功能生效；这条测试钉的就是那根线。
 */
class NodeRuntimeMessageLogTrimTest {

    @Test
    fun periodicPruneTrimsTheEnvelopeLog() = runBlocking {
        val dir = Files.createTempDirectory("relay-log-trim").toFile()
        val store = FileMessageStore(File(dir, "messages.log").absolutePath)
        repeat(10) { store.append("peer-a", DIRECTION_IN, "{}", 1_000L + it) }
        assertEquals(10, store.since("peer-a", 0, 100).size, "先确认真的有 10 条")

        val runtime = NodeRuntime(
            profile = Profile.mobile(
                nodeId = "trim-test-node",
                nick = "trim-test",
                host = "localhost",
                tcpPort = 0,
                ver = "test",
            ),
            udpPort = 0,
            tcpPort = 0,
            messageStore = store,
            // TTL 放大到不会命中，只让条数上限生效 —— 这条测的是「接线」，不是 TTL。
            timings = Timings(messageLogMaxRows = 4, messageLogTtl = 7L * 24 * 3_600_000),
        )

        runtime.prune(now = 2_000L)

        assertEquals(
            4,
            store.since("peer-a", 0, 100).size,
            "周期维护必须把信封日志裁到上限（接线断了就还是 10）",
        )
    }
}
