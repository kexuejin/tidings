// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain.recovery

import com.kapp.tidings.core.persist.RecoveryIdentityPayload
import com.kapp.tidings.domain.MessageStore
import com.kapp.tidings.domain.RelaySettings
import com.kapp.tidings.domain.SettingsStore
import com.kapp.tidings.domain.model.MessageUi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * L1 **全量快照**（bundle kind = `full`）的载荷编解码：身份 + 设置 + 会话历史。
 *
 * 载荷是 AEAD 内的一段 JSON（自描述、跨版本可容忍）：
 * ```
 * { "v": 1,
 *   "identity": { nodeId, key(b64 标量), spki(b64), pins: [{nodeId, pin}] },
 *   "settings": <RelaySettings 的 JSON 对象>,
 *   "threads":  { <convId>: [MessageUi…] } }
 * ```
 *
 * ## 各节用哪份 Json、为什么
 *
 * - `settings` 存成 **JsonObject（不透明）**：编码用 `SettingsStore.json`、解码也用它 ——
 *   与 `settings.json` 完全同一套容忍度（`ignoreUnknownKeys`）。新版本写的备份里出现
 *   旧构建不认识的字段时整包不作废；反过来旧备份里的缺省字段按默认值补。
 * - `threads` 用 `MessageStore.json` 的口径（`ignoreUnknownKeys`，理由同上，见其 KDoc）。
 * - 外层 DTO 自己一份 `Json { ignoreUnknownKeys }`。
 *
 * ## 为什么整包进内存而不是流式
 *
 * 应用自己的内存模型就是全量线程常驻（`core.threads`，真机 6656 条 / 约 15 MB），
 * 导出从这份常驻直接序列化，峰值只是翻倍瞬时；「流式」要在应用改成窗口化加载之后
 * 才有意义 —— 到时候改这里，bundle 格式不用动（载荷本来就是分节 JSON）。
 * 首版排除 `downloads/` 的裁决（Q7）不变。
 */
internal object RecoveryDataCodec {

    /** 载荷格式版本（与容器 FORMAT_VERSION 独立：容器管加密布局，这里管 JSON 布局）。 */
    const val SNAPSHOT_VERSION: Int = 1

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class PeerPinDto(val nodeId: String, val pin: String)

    @Serializable
    private data class IdentityDto(
        val nodeId: String,
        /** P-256 私钥标量，base64（定长 32B 解码后）。 */
        val key: String,
        /** SPKI DER，base64。 */
        val spki: String,
        val pins: List<PeerPinDto> = emptyList(),
    )

    @Serializable
    private data class FullSnapshotDto(
        val v: Int = SNAPSHOT_VERSION,
        val identity: IdentityDto,
        val settings: JsonObject,
        val threads: Map<String, List<MessageUi>> = emptyMap(),
    )

    /** 解码结果：三个维度各自成体，导入路径按序落盘。 */
    internal class FullSnapshot(
        val identity: RecoveryIdentityPayload,
        val settings: RelaySettings,
        val threads: Map<String, List<MessageUi>>,
    ) {
        val messageCount: Long get() = threads.values.sumOf { it.size }.toLong()
    }

    fun encode(
        identity: RecoveryIdentityPayload,
        settings: RelaySettings,
        threads: Map<String, List<MessageUi>>,
    ): ByteArray {
        val dto = FullSnapshotDto(
            v = SNAPSHOT_VERSION,
            identity = IdentityDto(
                nodeId = identity.nodeId,
                key = kotlin.io.encoding.Base64.encode(identity.ecPrivateScalar),
                spki = kotlin.io.encoding.Base64.encode(identity.spki),
                pins = identity.peerPins.map { PeerPinDto(it.nodeId, it.pinWire) },
            ),
            settings = SettingsStore.json
                .encodeToString(RelaySettings.serializer(), settings)
                .let { json.parseToJsonElement(it).jsonObject },
            threads = threads,
        )
        return json.encodeToString(FullSnapshotDto.serializer(), dto).encodeToByteArray()
    }

    /** 形状不对抛 [IllegalArgumentException]（调用方翻译成 BAD_FORMAT）。 */
    fun decode(bytes: ByteArray): FullSnapshot {
        val dto = json.decodeFromString(FullSnapshotDto.serializer(), bytes.decodeToString())
        require(dto.v == SNAPSHOT_VERSION) { "snapshot version ${dto.v}" }
        val scalar = kotlin.io.encoding.Base64.decode(dto.identity.key)
        val spki = kotlin.io.encoding.Base64.decode(dto.identity.spki)
        require(scalar.size in 1..32) { "identity key length" }
        require(spki.isNotEmpty()) { "identity spki empty" }
        val settings = SettingsStore.json.decodeFromJsonElement(RelaySettings.serializer(), dto.settings)
        return FullSnapshot(
            identity = RecoveryIdentityPayload(
                nodeId = dto.identity.nodeId,
                ecPrivateScalar = scalar,
                spki = spki,
                peerPins = dto.identity.pins.map { RecoveryIdentityPayload.PeerPin(it.nodeId, it.pin) },
            ),
            settings = settings,
            threads = dto.threads,
        )
    }

    /** threads 节的序列化器（`MessagePersist` 快照复用同一形状时的参照）。 */
    val threadsSerializer = MapSerializer(String.serializer(), ListSerializer(MessageUi.serializer()))
}
