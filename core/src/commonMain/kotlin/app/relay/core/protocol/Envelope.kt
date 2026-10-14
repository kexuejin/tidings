// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.protocol

import app.relay.core.nowMillis
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 线协议信封。字段顺序 = TS 侧 `JSON.stringify` 的属性插入顺序
 * （`makeEnvelope` 依次写 v/type/id/from/ts/payload）。
 *
 * **所有属性都不能有默认值**：`encodeDefaults = false` 会把等于默认值的属性省略掉，
 * 给 `v` 一个默认值就会让编码结果丢掉 `"v":1`，字节对拍立刻失败。
 */
@Serializable
data class Envelope(
    val v: Int,
    val type: String,
    val id: String,
    val from: String,
    val ts: Long,
    val payload: JsonElement,
)

/**
 * 对应 TS 的 `makeEnvelope`。`id`/`ts` 可注入，便于测试构造确定性帧。
 */
fun makeEnvelope(
    type: String,
    from: String,
    payload: JsonElement,
    id: String = newId(),
    ts: Long = nowMillis(),
): Envelope = Envelope(
    v = Protocol.VERSION,
    type = type,
    id = id,
    from = from,
    ts = ts,
    payload = payload,
)
