// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain.orchestrator

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 瞬态异常分类器测试（参考 DeepSeek dsh-superagent 的 `transient(error)`）。
 *
 * 验证：
 * 1. 常见 HTTP 状态码（408, 429, 500, 502, 503, 504）准确归类为 transient；
 * 2. 常见网络底层异常（ECONNRESET, ETIMEDOUT, socket hang up, rate limit）准确识别；
 * 3. 业务确定性异常（如编译语法错误、断言失败、文件未找到）绝不误判为 transient。
 */
class TransientErrorClassifierTest {

    @Test
    fun detectsHttpTransientStatusCodes() {
        assertTrue(TransientErrorClassifier.isTransient("HTTP 429 Too Many Requests"))
        assertTrue(TransientErrorClassifier.isTransient("Request failed with status code 503"))
        assertTrue(TransientErrorClassifier.isTransient("Error 500 Internal Server Error"))
        assertTrue(TransientErrorClassifier.isTransient("Gateway Timeout 504"))
        assertTrue(TransientErrorClassifier.isTransient("Request timeout 408"))
    }

    @Test
    fun detectsNetworkAndQuotaPhrases() {
        assertTrue(TransientErrorClassifier.isTransient("read ECONNRESET from socket"))
        assertTrue(TransientErrorClassifier.isTransient("connect ETIMEDOUT 192.168.1.100:8848"))
        assertTrue(TransientErrorClassifier.isTransient("Rate limit reached for model claude-3-7-sonnet"))
        assertTrue(TransientErrorClassifier.isTransient("Model overloaded, please retry later"))
        assertTrue(TransientErrorClassifier.isTransient("Socket hang up unexpectedly"))
        assertTrue(TransientErrorClassifier.isTransient("Anthropic error: quota exceeded for current billing cycle"))
    }

    @Test
    fun detectsFromThrowable() {
        val ex = RuntimeException("connect failed: ETIMEDOUT")
        assertTrue(TransientErrorClassifier.isTransient(ex))
    }

    @Test
    fun deterministicErrorsAreNotTransient() {
        assertFalse(TransientErrorClassifier.isTransient("SyntaxError: Unexpected token in JSON at position 42"))
        assertFalse(TransientErrorClassifier.isTransient("AssertionError: expected true but was false"))
        assertFalse(TransientErrorClassifier.isTransient("NoSuchFileException: file not found: /tmp/test.kt"))
        assertFalse(TransientErrorClassifier.isTransient("Compilation error: Unresolved reference 'foo'"))
        assertFalse(TransientErrorClassifier.isTransient(null as String?))
        assertFalse(TransientErrorClassifier.isTransient(""))
    }
}
