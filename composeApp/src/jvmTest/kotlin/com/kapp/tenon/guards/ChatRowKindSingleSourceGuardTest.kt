// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.guards

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 「一条消息画成什么」只有一份判据（轮 15 / 消息行注册表 WP1）。
 *
 * 改造前类型散在字段里：`notice != null` 是系统行、`screen != null` 是历史卡、
 * `renderCard != null` 是声明式卡……而**同一类型**在撤回、引用预览、回复条、
 * 连续发件编组里又各写了一遍裸字段判据。两把尺子分叉时不会编译失败，只会
 * 静默少一个功能（本仓的会话分段线就是这么「桌面有、手机没有」的）。
 *
 * 本守卫只读源码：不看函数名、不看注释，认的是每个消费点真的调用了唯一判别式，
 * 且没有留下裸字段判据。运行期真值表由 `ChatRowKindContractTest` 钉住。
 *
 * 反向验证：把任一消费点改回裸字段判据，[noBareTypeProbeInConsumers] 变红；
 * 删掉注册表文件，[registryExposesOneDiscriminatorAndAnExplicitOrder] 变红。
 */
class ChatRowKindSingleSourceGuardTest {

    private val registry = "app/src/commonMain/kotlin/com/kapp/tenon/domain/model/ChatRowKinds.kt"

    private val consumers = listOf(
        "app/src/commonMain/kotlin/com/kapp/tenon/domain/ChatInteractions.kt",
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/chat/ChatBubbles.kt",
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/chat/ChatMessageList.kt",
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/components/MessageComposer.kt",
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/desktop/DesktopComposer.kt",
    )

    private fun live(path: String): String = GuardSourceScan.stripComments(GuardSourceScan.read(path))

    @Test
    fun everyConsumerAsksTheRegistry() {
        for (path in consumers) {
            assertTrue(
                live(path).contains("ChatRowKinds."),
                path + " 还在自己判消息类型：必须向唯一判别式 ChatRowKinds 问",
            )
        }
    }

    @Test
    fun noBareTypeProbeInConsumers() {
        val forbidden = listOf(
            "message.notice != null",
            "quoted.screen != null",
            "replyingTo.file?.name",
            "reply.file?.name",
            "a.notice == null && b.notice == null",
            "message.pk == null &&",
        )
        for (path in consumers) {
            val source = live(path)
            for (token in forbidden) {
                assertTrue(
                    !source.contains(token),
                    path + " 出现裸类型判据 \"" + token + "\"：请改问 ChatRowKinds",
                )
            }
        }
    }

    @Test
    fun registryExposesOneDiscriminatorAndAnExplicitOrder() {
        val source = live(registry)
        assertTrue(source.contains("fun of(message: MessageUi): ChatRowKind"), "唯一判别式不见了")
        assertTrue(source.contains("val ordered"), "显式优先级不见了")
        assertTrue(source.contains("fun capabilities(kind: ChatRowKind)"), "能力表不见了")
    }

    @Test
    fun registryLivesInTheComposeFreeDomain() {
        val source = live(registry)
        assertTrue(!source.contains("androidx.compose"), "领域层不许依赖 Compose")
        assertTrue(source.contains("package com.kapp.tenon.domain.model"), "注册表放错了包")
    }
}
