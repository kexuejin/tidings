// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent

import com.kapp.tidings.core.protocol.Profile
import com.kapp.tidings.core.protocol.newId
import com.kapp.tidings.core.session.NodeRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `/healthz` 的 CORS 契约。
 *
 * ## 为什么这条要有测试
 *
 * 桌面端设置页里那张「捎话」卡片跑在**浏览器**里，它判断「本机桌面端在不在」的办法
 * 就是读 `127.0.0.1:<port>/healthz`。那是跨源请求（端口不同），浏览器不给读响应，
 * 除非控制面回一个允许的 Origin —— 没有这几个头，卡片只能显示「未检测到捎话桌面端」，
 * 而桌面端明明在跑。
 *
 * ## 边界也一并钉住
 *
 * 放开 CORS 是**安全取舍**，所以「不该给的」必须比「该给的」更严格地被测到：
 * 只给 `/healthz`（`/v1/` 一律不给）、只给回环 Origin（`127.0.0.1.evil.com` 必须判否）。
 * 少了后半截，这条测试只会证明「开了」，不会证明「没开过头」。
 */
class ControlPlaneCorsTest {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private lateinit var scope: CoroutineScope
    private lateinit var agent: NodeRuntime
    private lateinit var plane: ControlPlane

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        agent = NodeRuntime(
            profile = Profile.mobile(
                nodeId = newId(),
                nick = "Tidings Agent",
                host = "relay-agent",
                ver = "relay-0.1.0",
            ),
            udpPort = 0,
            bindHost = "127.0.0.1",
        )
        runBlocking { agent.start(scope) }
        // 故意带上 token：预检请求按规范不带 Authorization，若把 CORS 放在鉴权之后，
        // 这条用例会拿到 401 —— 那正是浏览器侧表现为「跨域被拒」的成因。
        plane = ControlPlane(
            agent, httpPort = 0, token = "secret-token",
            sessions = AgentSessionRegistry(),
            workspaces = AgentWorkspaceRegistry(),
            grants = AgentGrantStore(java.io.File.createTempFile("relay-cors-", ".json").apply { delete() }),
        )
        plane.start()
    }

    @AfterTest
    fun tearDown() {
        plane.stop()
        // NodeRuntime.stop() 是挂起的：要等 TCP 监听器真正关掉，端口才算释放。
        runBlocking { agent.stop() }
        scope.cancel()
    }

    /**
     * `auth = true` 与真实调用一致（控制面配了 token 就得带）；
     * 预检那条按规范**不带** Authorization，专门用来证明 CORS 答在鉴权之前。
     */
    private fun send(
        path: String,
        origin: String?,
        method: String = "GET",
        auth: Boolean = true,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:${plane.boundPort}$path"))
            .timeout(Duration.ofSeconds(5))
            .method(method, HttpRequest.BodyPublishers.noBody())
        if (origin != null) builder.header("Origin", origin)
        if (auth) builder.header("Authorization", "Bearer secret-token")
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun healthzAllowsALoopbackPageToReadIt() {
        val origin = "http://127.0.0.1:50042"
        val response = send("/healthz", origin)
        assertEquals(200, response.statusCode())
        assertEquals(
            origin,
            response.headers().firstValue("Access-Control-Allow-Origin").orElse(null),
            "回环来源必须被原样回显 —— 卡片靠这个才读得到状态",
        )
        assertTrue(
            response.headers().firstValue("Vary").orElse("").contains("Origin"),
            "回显 Origin 必须配 Vary: Origin，否则中间缓存会把 A 站点的允许头复用到 B 站点",
        )
    }

    @Test
    fun localhostAndIpv6LoopbackAreAlsoAccepted() {
        for (origin in listOf("http://localhost:3080", "http://[::1]:8080")) {
            assertEquals(
                origin,
                send("/healthz", origin).headers().firstValue("Access-Control-Allow-Origin").orElse(null),
                "回环名有三个：127.0.0.1 / localhost / [::1]，漏一个就是某类宿主上卡片又瞎了",
            )
        }
    }

    @Test
    fun anAppRegisteredCustomSchemeIsAccepted() {
        // DSH 桌面端不用 http 装界面：它 loadURL("dsh-app://app/")，于是页面 fetch 本机
        // 控制面时带的是这个来源。网页伪造不了自定义协议来源 —— 那要在系统里注册过 scheme。
        assertEquals(
            "dsh-app://app",
            send("/healthz", "dsh-app://app").headers()
                .firstValue("Access-Control-Allow-Origin").orElse(null),
        )
    }

    @Test
    fun anOpaqueNullOriginIsAccepted() {
        // 桌面端 webview 用 file:// 载入页面时，Origin 是字面量 `null`（不透明来源）。
        // 桌面端宿主没有面板通道的前提下，这是卡片唯一能读到状态的路径。
        assertEquals(
            "null",
            send("/healthz", "null").headers().firstValue("Access-Control-Allow-Origin").orElse(null),
        )
    }

    @Test
    fun aLookalikeHostIsRejected() {
        // 按 host 段精确比对，不是 contains —— 否则任意域名都能拿到允许头。
        for (origin in listOf("http://127.0.0.1.evil.com", "https://evil.com")) {
            assertNull(
                send("/healthz", origin).headers().firstValue("Access-Control-Allow-Origin").orElse(null),
                "$origin 不是回环来源，不许给 CORS 头",
            )
        }
    }

    @Test
    fun otherRoutesGetNoCorsEvenFromALoopbackPage() {
        // 这是这条测试真正守住的东西：CORS 只放开 healthz 这一个只读接口。
        // `/v1/` 下有消息、授权台账、发送、投屏 —— 它们**不许**被任意本机网页读到。
        assertNull(
            send("/v1/peers", "http://127.0.0.1:50042").headers()
                .firstValue("Access-Control-Allow-Origin").orElse(null),
            "/v1/peers 泄露邻居台账，不该加 CORS",
        )
        assertNull(
            send("/v1/inbox?since=0", "http://127.0.0.1:50042").headers()
                .firstValue("Access-Control-Allow-Origin").orElse(null),
            "/v1/inbox 泄露消息内容，不该加 CORS",
        )
    }

    @Test
    fun preflightIsAnsweredBeforeAuth() {
        // 预检不带 Authorization，必须仍然拿到 204 + 允许头，否则浏览器直接判跨域失败。
        val response = send("/healthz", "http://127.0.0.1:50042", method = "OPTIONS", auth = false)
        assertEquals(204, response.statusCode(), "预检必须在鉴权之前答，否则带 token 的实例会把预检打成 401")
        assertEquals(
            "http://127.0.0.1:50042",
            response.headers().firstValue("Access-Control-Allow-Origin").orElse(null),
        )
    }
}