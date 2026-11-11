// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.ui.components

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 守卫 UX 规范 V1 §2.4「宿主休眠 / 离线感知」的两条落点：`@` 候选徽标与发送侧温暖提示。
 *
 * ## 措辞上的一处如实收窄
 *
 * 规范写的是「电脑休眠中」，但本端**只有** `SenderIdentity.online == false` 这一个事实：
 * 休眠、断网、关机、进程被杀在节点表里长得一模一样。把它断言成「休眠」是编造，
 * 把「唤醒后自动断点续传」写成承诺也超出了协议能保证的范围（群消息为离线成员入队，
 * 宿主回来后是否继续跑由宿主决定）。所以资源文案只说「休眠/离线」与「消息已排队，
 * 恢复连接后继续处理」。已存在的会话内横幅不受本笔约束。
 *
 * ## 判据为什么是源码级
 *
 * 本仓没有 Compose UI 测试基建，而这两条都在 Composable / 局部函数内部：候选行画不画
 * 徽标、发送时置不置提示态，单测够不到。与 `WorkspaceGroupCreateDialogGuardTest`
 * 同一取舍：先剥注释再逐片段匹配，并且**每一段都必须能被摘除判红**（变异自检），
 * 否则「某个词在文件里出现过」这种弱判据会假绿。
 *
 * 反向验证：本守卫先于实现落地，实现前判红；实现后转绿。守卫自身不能引用尚不存在的
 * 新函数，否则实现前是编译错而不是测试红 —— 所以这里只用文本判据，不 `import` 新符号。
 */
class OfflineHostAwarenessGuardTest {

    private val composerPath = "composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/components/MessageComposer.kt"
    private val chatPath = "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/chat/ChatScreen.kt"
    private val zhPath = "composeApp/src/commonMain/composeResources/values/strings.xml"
    private val enPath = "composeApp/src/commonMain/composeResources/values-en/strings.xml"

    private fun live(path: String): String =
        GuardSourceScan.stripComments(GuardSourceScan.read(path))

    private val xmlComment = Regex("""<!--[\s\S]*?-->""")
    private val declaredEntry = Regex("""<string\s+name=""")
    private val entry = Regex("""<string\s+name="([^"]+)"\s*>([\s\S]*?)</string>""")

    /** 剥注释后逐条取值；解析数与声明数对不上就返回空表（宁可报红，不许恒真）。 */
    private fun values(xml: String): Map<String, String> {
        val stripped = xmlComment.replace(xml, "")
        val declared = declaredEntry.findAll(stripped).count()
        val parsed = entry.findAll(stripped).associate { it.groupValues[1] to it.groupValues[2].trim() }
        return if (declared == parsed.size) parsed else emptyMap()
    }

    private val keys = listOf("chat_mention_host_offline", "chat_offline_host_send_hint")

    /** 实现必须同时满足的四组事实；任何一组摘掉都得判红。 */
    private fun wired(composer: String, chat: String, zh: String, en: String): Boolean {
        val zhValues = values(zh)
        val enValues = values(en)
        if (zhValues.isEmpty() || enValues.isEmpty()) return false
        if (keys.any { zhValues[it].isNullOrBlank() || enValues[it].isNullOrBlank() }) return false
        return listOf(
            // ① 候选模型带离线位，默认 false（漏传的调用点不会凭空标红）。
            "val isHostOffline: Boolean = false,",
            // ② `@` 下拉里真的按这个位画了徽标，不是只在模型里留了个字段。
            "if (target.isHostOffline) {",
            "Res.string.chat_mention_host_offline",
            // ③ 匹配助手必须存在（调用方在聊天屏，那里另有判据）。
            "internal fun offlineMentionTokenIn(",
        ).all { composer.contains(it) } && listOf(
            // ④ 离线位只由「agent 宿主明确报了离线」推出来，null（不知道）不标红。
            "isHostOffline = identity.isAgent && identity.online == false",
            "val offlineMentionByToken",
            "var offlineHostName by remember(peerId)",
            // ⑤ 发送即评估；下一次编辑草稿就撤掉提示，不是永久挂着。
            "offlineHostName = offlineMentionTokenIn(body, offlineMentionByToken.keys)",
            "offlineHostName = null",
            // ⑥ 提示真的画在输入区上方，用资源文案而不是硬编码中文。
            "Res.string.chat_offline_host_send_hint",
        ).all { chat.contains(it) }
    }

    @Test
    fun offlineAwarenessIsWiredEndToEnd() {
        assertTrue(
            wired(live(composerPath), live(chatPath), GuardSourceScan.read(zhPath), GuardSourceScan.read(enPath)),
            "§2.4 的候选徽标 / 离线位派生 / 发送侧提示没有全链路接线",
        )
    }

    @Test
    fun bothLocalesCarryTheSameKeys() {
        val zhValues = values(GuardSourceScan.read(zhPath))
        val enValues = values(GuardSourceScan.read(enPath))
        assertTrue(zhValues.isNotEmpty() && enValues.isNotEmpty(), "资源解析完整性")
        keys.forEach { key ->
            assertTrue(zhValues.containsKey(key), "中文资源缺 $key")
            assertTrue(enValues.containsKey(key), "英文资源缺 $key")
        }
        // 新文案不许把刚收口掉的「工作区」再带回来。
        keys.forEach { key ->
            assertFalse(zhValues[key].orEmpty().contains("工作区"), "$key 的中文值带回了工程词汇")
            assertFalse(enValues[key].orEmpty().contains("Workspace", ignoreCase = true), "$key 的英文值带回了工程词汇")
        }
        assertEquals(zhValues.size, enValues.size, "双语逐键对账")
    }

    @Test
    fun everyCriticalFragmentIsRemovalSensitive() {
        val composer = live(composerPath)
        val chat = live(chatPath)
        val zh = GuardSourceScan.read(zhPath)
        val en = GuardSourceScan.read(enPath)
        assertTrue(wired(composer, chat, zh, en), "变异自检的起点必须是绿")

        val composerFragments = listOf(
            "val isHostOffline: Boolean = false,",
            "if (target.isHostOffline) {",
            "Res.string.chat_mention_host_offline",
            "internal fun offlineMentionTokenIn(",
        )
        val chatFragments = listOf(
            "isHostOffline = identity.isAgent && identity.online == false",
            "val offlineMentionByToken",
            "var offlineHostName by remember(peerId)",
            "offlineHostName = offlineMentionTokenIn(body, offlineMentionByToken.keys)",
            "offlineHostName = null",
            "Res.string.chat_offline_host_send_hint",
        )
        composerFragments.forEach { why ->
            assertFalse(
                wired(composer.replace(why, ""), chat, zh, en),
                "摘掉「$why」后判据仍为真 ⇒ 这段是摆设",
            )
        }
        chatFragments.forEach { why ->
            assertFalse(
                wired(composer, chat.replace(why, ""), zh, en),
                "摘掉「$why」后判据仍为真 ⇒ 这段是摆设",
            )
        }
        keys.forEach { key ->
            listOf(zhPath to zh, enPath to en).forEach { (path, xml) ->
                val mutated = xml.replace(Regex("""<string name="$key">[\s\S]*?</string>"""), "")
                assertFalse(wired(composer, chat, if (path == zhPath) mutated else zh, if (path == enPath) mutated else en), "$key 在 $path 里被摘掉后判据仍为真")
            }
        }
        // 反向对照：换掉一段无关文本不该把判据弄红（防止「任何改动都判红」的假绿）。
        assertTrue(
            wired(composer.replace("chat_mention_all", "chat_mention_all_placeholder"), chat, zh, en),
            "无关文本替换不该影响判据",
        )
    }

    @Test
    fun guardIsNotFooledByCommentsOrEmptySources() {
        val zh = GuardSourceScan.read(zhPath)
        val en = GuardSourceScan.read(enPath)
        assertFalse(wired("", "", zh, en), "空源码必须判红，不能恒真")
        assertFalse(
            wired(
                "// val isHostOffline: Boolean = false,\n/* Res.string.chat_mention_host_offline */",
                "/* Res.string.chat_offline_host_send_hint */",
                zh,
                en,
            ),
            "只出现在注释里的片段不能算接线",
        )
    }

    /* ===== 匹配助手的行为判据 =====
     *
     * 这几条只能在实现落地之后写：实现前引用尚不存在的符号是编译错，不是测试红，
     * 那会毁掉「先红后绿」的读数。源码级守卫负责反向验证，这里负责行为边界。
     */

    @Test
    fun offlineTokenMatchesWholeWordsOnly() {
        val offline = setOf("@DSH", "@coder")
        assertEquals("@DSH", offlineMentionTokenIn("@DSH 请跑一下门禁", offline))
        assertEquals("@coder", offlineMentionTokenIn("麻烦 @coder, 看看这个补丁", offline))
        // `@coder2` 是一个不同的席位，不能让 `@coder` 的子串命中它。
        assertEquals(null, offlineMentionTokenIn("@coder2 请跑一下门禁", offline))
        assertEquals(null, offlineMentionTokenIn("普通一句话", offline))
    }

    @Test
    fun offlineTokenNeedsBothANonEmptyBodyAndOfflineCandidates() {
        assertEquals(null, offlineMentionTokenIn("", setOf("@DSH")))
        assertEquals(null, offlineMentionTokenIn("   ", setOf("@DSH")))
        // 在线态未知 / 全体在线时不该凭空弹提示。
        assertEquals(null, offlineMentionTokenIn("@DSH 开始", emptySet()))
    }
}
