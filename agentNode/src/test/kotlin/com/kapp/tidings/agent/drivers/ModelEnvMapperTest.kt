// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent.drivers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ModelEnvMapper 的判据（升级轮 T-3a，设计 §5.5）：
 *
 * 1. 三 CLI 的映射逐键正确（claude/codex/opencode）；
 * 2. **映射缺失 ⇒ 拒单**（unknown targetCli 抛异常，不静默用错模型 —— D-023 哲学）；
 * 3. **nullable reasonerModel 键缺席**（不下发空值，帧纪律同一条哲学）；
 * 4. **安全不变量**：env Map 的 key 只含模型商环境变量名，value 不进日志 ——
 *    反向验证：把映射缺失改成「回退 OpenAI 默认」⇒ unknown 用例红（静默用错模型）。
 */
class ModelEnvMapperTest {

    private val config = ModelProviderConfig(
        providerId = "opencode-go",
        apiBaseUrl = "https://api.opencode.go/v1",
        apiKey = "sk-local-secret",
        defaultModel = "deepseek-v4.1-flash",
        reasonerModel = "deepseek-v4.1-reasoner",
    )

    @Test
    fun claudeMappingIsExact() {
        val env = ModelEnvMapper.buildEnvironment(config, "claude")
        assertEquals("https://api.opencode.go/v1", env["ANTHROPIC_BASE_URL"])
        assertEquals("sk-local-secret", env["ANTHROPIC_API_KEY"])
        assertEquals("deepseek-v4.1-flash", env["ANTHROPIC_MODEL"])
    }

    @Test
    fun codexMappingIsExact() {
        val env = ModelEnvMapper.buildEnvironment(config, "codex")
        assertEquals("sk-local-secret", env["OPENAI_API_KEY"])
        assertEquals("deepseek-v4.1-flash", env["CODEX_MODEL"])
    }

    @Test
    fun opencodeMappingIsExact() {
        val env = ModelEnvMapper.buildEnvironment(config, "opencode")
        assertEquals("sk-local-secret", env["OPENCODE_API_KEY"])
        assertEquals("deepseek-v4.1-flash", env["OPENCODE_MODEL"])
    }

    @Test
    fun unknownTargetCliRefusesInsteadOfGuessing() {
        try {
            ModelEnvMapper.buildEnvironment(config, "unknown-cli")
            fail("映射缺失必须拒单 —— 静默用错模型比拒单更糟（D-023 哲学）")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                e.message!!.contains("unknown-cli"),
                "异常里要能读出是哪个 CLI 没映射：" + e.message,
            )
        }
    }

    @Test
    fun nullReasonerModelOmitsTheKeyInsteadOfSendingNull() {
        val withoutReasoner = config.copy(reasonerModel = null)
        val env = ModelEnvMapper.buildEnvironment(withoutReasoner, "claude")
        assertTrue(!env.containsKey("ANTHROPIC_REASONER_MODEL"), "nullable model 键缺席（不下发空值）")
    }

    @Test
    fun envKeysNeverLeakIntoAnyProtocolShape() {
        // 安全不变量的守卫：env Map 的**键集合**必须是纯模型商环境变量 ——
        // 若有人把 env Map 塞进报文序列化（groupId/text/payload 之类协议键），这里红。
        val env = ModelEnvMapper.buildEnvironment(config, "claude")
        val protocolKeys = setOf("groupId", "text", "payload", "channelId", "cardId")
        assertTrue(
            protocolKeys.intersect(env.keys).isEmpty(),
            "env 键混入协议键 = 凭证面进了报文形状（AGENTS §3 红线）",
        )
    }
}
