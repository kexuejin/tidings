// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent.plugins

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * 已注册插件的台账与状态机（M2 宿主网关）。
 *
 * 条目由 `POST /v1/plugin/register` 写入（ControlPlane 的插件注册处理器），
 * 状态由两条路径翻转：
 *
 * 1. **主动探测**：[startMonitoring] 起一个低频循环，对每个条目 `GET <baseUrl>/healthz`
 *    （插件侧 HTTP 契约的一部分，真插件与本仓夹具都实现它）。探不到 ⇒ 翻 offline；
 *    之前离线的又探到了 ⇒ 翋回 online（插件重启的自然路径，全生命周期热插拔）。
 * 2. **调用失败**：宿主驱动（ExternalPluginDriver）在 IPC 调用失败时经
 *    [markOffline] 即时翻状态 —— 不等下一个探测周期（M2-T3 接线）。
 *
 * 探测间隔是构造参数：生产 15s；测试给小值配 `Await` 轮询，不赌固定时序。
 */
class PluginRegistry(
    /** 探测周期（毫秒）。 */
    val probeIntervalMs: Long = 15_000L,
    /** 单次探测的连接/读取超时。 */
    private val probeTimeoutMs: Int = 2_000,
    private val probeScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    class Entry(
        val manifest: PluginManifest,
        /** 插件自己的回环 IPC 地址（`http://127.0.0.1:<httpPort>`）。 */
        val baseUrl: String,
        val httpPort: Int,
        /** leader + members 的完整 senderId（注册时算好，发送侧不变量的证据）。 */
        val agentSenderIds: List<String>,
        val registeredAt: Long,
    ) {
        val pluginId: String get() = manifest.pluginId

        @Volatile
        var status: String = STATUS_ONLINE
            private set

        @Volatile
        var lastError: String? = null
            private set

        fun flip(status: String, lastError: String?) {
            this.status = status
            this.lastError = lastError
        }
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    @Volatile
    private var monitorJob: Job? = null

    /** 注册（同 pluginId 重注册 = **替换**：升级/重启的自然路径，不长出第二个条目）。 */
    fun register(manifest: PluginManifest, baseUrl: String, httpPort: Int, agentSenderIds: List<String>): Entry {
        val entry = Entry(manifest, baseUrl, httpPort, agentSenderIds, System.currentTimeMillis())
        entries[manifest.pluginId] = entry
        return entry
    }

    /** 注销；返回是否真的有这个条目。 */
    fun unregister(pluginId: String): Boolean = entries.remove(pluginId) != null

    fun get(pluginId: String): Entry? = entries[pluginId]

    /** 端口冲突检查用：同一回环端口只能归一个插件（两个驱动不能抢同一条 IPC）。 */
    fun findByPort(httpPort: Int): Entry? = entries.values.firstOrNull { it.httpPort == httpPort }

    fun list(): List<Entry> = entries.values.sortedBy { it.pluginId }.toList()

    fun markOffline(pluginId: String, reason: String) {
        entries[pluginId]?.flip(STATUS_OFFLINE, reason)
    }

    fun markOnline(pluginId: String) {
        entries[pluginId]?.flip(STATUS_ONLINE, null)
    }

    // ---------------------------------------------------------------- 探测循环

    /** 启动低频健康探测（幂等）。控制面 start() 时调用。 */
    fun startMonitoring() {
        synchronized(this) {
            if (monitorJob != null) return
            monitorJob = probeScope.launch { monitorLoop() }
        }
    }

    /** 停止探测（控制面 stop() 时调用；注销全部条目不在这一步 —— 状态留着给人看）。 */
    fun stopMonitoring() {
        synchronized(this) {
            monitorJob?.cancel()
            monitorJob = null
        }
    }

    private suspend fun monitorLoop() {
        while (currentCoroutineContext().isActive) {
            delay(probeIntervalMs)
            for (entry in list()) {
                val healthy = runCatching { probeHealth(entry.baseUrl) }.getOrDefault(false)
                if (!healthy && entry.status == STATUS_ONLINE) {
                    entry.flip(STATUS_OFFLINE, "health probe failed: " + entry.baseUrl + "/healthz unreachable")
                } else if (healthy && entry.status != STATUS_ONLINE) {
                    entry.flip(STATUS_ONLINE, null)
                }
            }
        }
    }

    /**
     * `GET <baseUrl>/healthz` —— 插件侧契约。200 = 活着。
     * 任何 IO 异常都算不健康（进程死了、端口没了、挂起），不区分死法。
     */
    fun probeHealth(baseUrl: String): Boolean {
        val conn = URI(baseUrl + "/healthz").toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = probeTimeoutMs
        conn.readTimeout = probeTimeoutMs
        try {
            return conn.responseCode == 200
        } finally {
            conn.disconnect()
        }
    }

    /** 测试收尾用（构造了默认 scope 的调用方不留活动线程）。 */
    fun dispose() {
        stopMonitoring()
        probeScope.cancel()
    }

    companion object {
        const val STATUS_ONLINE: String = "online"
        const val STATUS_OFFLINE: String = "offline"
    }
}
