// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.protocol

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** 生成 UUIDv4。TS 侧用 `randomUUID()`，语义一致。 */
@OptIn(ExperimentalUuidApi::class)
fun newId(): String = Uuid.random().toString()

private val UUID_V4 = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
)

fun isUuidV4(value: String): Boolean = UUID_V4.matches(value)

/**
 * 屏幕协助的一次性连接 token：**32 位小写十六进制**。
 *
 * 不能拿 [`newId`] 或本地序号 id 去掉横线充当 —— 屏幕协助协议 `isScreenToken` 要求
 * `/^[0-9a-f]{32}$/`，一个非 hex 字符就会让整帧被判 `bad-payload:screen` 而**静默丢弃**
 * （不弹邀请、不回 ACK）。UUID 去横线正好是 32 位小写 hex。
 */
@OptIn(ExperimentalUuidApi::class)
fun newScreenToken(): String = Uuid.random().toString().replace("-", "")

private val HEX32 = Regex("^[a-f0-9]{32}$")

fun isScreenToken(value: String): Boolean = HEX32.matches(value)

/** SHA-256 十六进制摘要。 */
private val HEX64 = Regex("^[a-f0-9]{64}$")

fun isHex64(value: String): Boolean = HEX64.matches(value)
