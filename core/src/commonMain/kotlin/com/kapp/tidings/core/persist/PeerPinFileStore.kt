// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.persist

import com.kapp.tidings.core.fs.fileExists
import com.kapp.tidings.core.fs.readFileBytes
import com.kapp.tidings.core.transport.PeerPinStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * TLS pin 的落盘实现：`<dataDir>/stores/peer-pins.json`。
 *
 * 与另外几个 store 同一条纪律：读不出来/写不进去一律降级为「这份当空表」。丢的是
 * 公网长连接的兜底能力（fail-closed：没 pin 就不建公网连接），不是消息。
 */
class FilePeerPinStore(private val path: String) : PeerPinStore {

    private var current: Map<String, String> = read()
    private var sources: Map<String, String> = readSources()

    override fun load(): Map<String, String> = current

    override fun loadSources(): Map<String, String> = sources

    override fun save(pins: Map<String, String>) {
        current = pins
        persist()
    }

    override fun saveSources(sources: Map<String, String>) {
        this.sources = sources
        persist()
    }

    /** 两个 map 一起写：分开写会出现"pin 已更新、来源还是旧的"这种自相矛盾的文件。 */
    private fun persist() {
        runCatching {
            val text = json.encodeToString(
                PeerPinsSnapshot.serializer(),
                PeerPinsSnapshot(pins = current, sources = sources),
            )
            rewriteLog(null, path, StringBuilder(text))
        }
    }

    private fun read(): Map<String, String> = readSnapshot().pins

    private fun readSources(): Map<String, String> = readSnapshot().sources

    /** 读不出来一律降级成空表：丢的是公网长连接的兜底能力，不是消息（与别的 store 同纪律）。 */
    private fun readSnapshot(): PeerPinsSnapshot {
        if (!fileExists(path)) return PeerPinsSnapshot()
        val bytes = readFileBytes(path) ?: return PeerPinsSnapshot()
        return runCatching {
            json.decodeFromString(PeerPinsSnapshot.serializer(), bytes.decodeToString())
        }.getOrDefault(PeerPinsSnapshot())
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** 写进文件的那个形状（外层 version 与 PeerEndpointsSnapshot 同一条理由）。 */
@Serializable
internal data class PeerPinsSnapshot(
    val version: Int = SNAPSHOT_VERSION,
    val pins: Map<String, String> = emptyMap(),
    /**
     * nodeId → pin 来源（task-38：`pairing` / `tofu`）。
     *
     * 旧文件没有这个键 ⇒ 默认空表 ⇒ 全按 `unknown` 处理（**不会**假装它是 pairing）。
     */
    val sources: Map<String, String> = emptyMap(),
) {
    companion object {
        const val SNAPSHOT_VERSION = 1
    }
}
