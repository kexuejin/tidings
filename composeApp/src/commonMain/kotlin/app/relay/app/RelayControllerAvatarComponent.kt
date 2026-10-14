// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.core.crypto.sha256Hex
import app.relay.core.fs.readFileBytes
import app.relay.core.ioDispatcher
import app.relay.core.protocol.AvatarOps
import app.relay.core.protocol.Envelope
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 头像：收到对方的头像请求时怎么回、什么时候去拉，以及「我的头像」页的选图与移除。
 *
 * 切出来的理由：注意 [handlePickAvatar] 与 [handleRemovePickedAvatar] **只动本机**
 * ——不宣告、不上传。它们是本地偏好而不是协议动作，这件事在协议层和 UI 层都看不出来，
 * 只在控制器里有意义；单独成文件才不会被当成协议代码对待。
 *
 * @param platform 选文件与发通知都走 [AppPlatform]，这是本族唯一的平台依赖 ——
 *   桌面端没有选文件能力时入口直接隐藏，而不是点了报空。
 */
internal class RelayControllerAvatarComponent(
    private val host: RelayController,
) {
fun handleOnAvatarEnvelope(envelope: Envelope) {
    val payload = envelope.payload as? JsonObject ?: return
    val op = payload["op"]?.jsonPrimitive?.content ?: return
    val hash = payload["hash"]?.jsonPrimitive?.content ?: return

    when (op) {
        AvatarOps.DATA -> {
            val dataStr = payload["data"]?.jsonPrimitive?.content ?: return
            val bytes = runCatching { Base64.Default.decode(dataStr) }.getOrNull() ?: return
            val computedHash = sha256Hex(bytes)
            if (computedHash.equals(hash, ignoreCase = true)) {
                host._state.update { current ->
                    current.copy(avatarCache = current.avatarCache + (hash to bytes))
                }
            }
        }
        AvatarOps.GET -> {
            val cached = host._state.value.avatarCache[hash]
            val rt = host.runtime
            if (rt != null) {
                host.scope.launch {
                    if (cached != null) {
                        val base64 = Base64.Default.encode(cached)
                        rt.sendAvatarData(envelope.from, hash, base64)
                    } else {
                        rt.sendAvatarMiss(envelope.from, hash)
                    }
                }
            }
        }
        AvatarOps.MISS -> {
            // 对端未命中头像
        }
    }
}


fun handleFetchAvatarIfNeeded(peerId: String, hash: String) {
    if (hash.isBlank()) return
    if (host._state.value.avatarCache.containsKey(hash)) return
    val rt = host.runtime ?: return
    host.scope.launch {
        rt.sendAvatarGet(peerId, hash)
    }
}

/**
 * 「上传头像」页的「选择图片 / 换一张 / 重选」：弹系统文件选择框，读进内存。
 *
 * 这一支**只做本机的事**：选中 → 读字节 → 算 sha256 → 存进 [RelayUiState.pickedAvatarBytes]
 * 与 [RelayUiState.pickedAvatarHash]。它**不宣告、不上网** —— `cap av1` 至今没有任何生产
 * 调用点传 `customAvatar = true`（`RelaySettings.caps(...)`），所以对端看到的仍是数值头像。
 * 这正是 [SetAvatarPage] 自己那张「协议状态」卡对用户明说的事（原型 `m-set-avatar` 的
 * 三条出路里，本稿按 ② 画：这一档点了跳到说明页），页面上没有一句谎。
 *
 * 失败一律 [BannerMessage.PickFailed] / [BannerMessage.UnknownError]，与
 * `handlePickAndSendFiles` / `importQrImage` 同形：用户点出来的一次交互，失败该回到
 * 「没选」那条路，不抛异常。取消（用户关掉选择框）不是错误，静默返回。
 */


/**
 * 「上传头像」页的「选择图片 / 换一张 / 重选」：弹系统文件选择框，读进内存。
 *
 * 这一支**只做本机的事**：选中 → 读字节 → 算 sha256 → 存进 [RelayUiState.pickedAvatarBytes]
 * 与 [RelayUiState.pickedAvatarHash]。它**不宣告、不上网** —— `cap av1` 至今没有任何生产
 * 调用点传 `customAvatar = true`（`RelaySettings.caps(...)`），所以对端看到的仍是数值头像。
 * 这正是 [SetAvatarPage] 自己那张「协议状态」卡对用户明说的事（原型 `m-set-avatar` 的
 * 三条出路里，本稿按 ② 画：这一档点了跳到说明页），页面上没有一句谎。
 *
 * 失败一律 [BannerMessage.PickFailed] / [BannerMessage.UnknownError]，与
 * `handlePickAndSendFiles` / `importQrImage` 同形：用户点出来的一次交互，失败该回到
 * 「没选」那条路，不抛异常。取消（用户关掉选择框）不是错误，静默返回。
 */
fun handlePickAvatar() {
    host.scope.launch {
        val picked = try {
            host.platform.filePicker.pick()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            host.warn(
                failure.messageOrNull()?.let(BannerMessage::PickFailed) ?: BannerMessage.UnknownError,
            )
            return@launch
        } ?: return@launch

        val bytes = withContext(ioDispatcher) {
            readFileBytes(picked.path, maxBytes = MAX_AVATAR_BYTES)
        }
        if (bytes == null || bytes.isEmpty()) {
            host.warn(BannerMessage.PickFailed(picked.name))
            return@launch
        }
        val hash = sha256Hex(bytes)
        host._state.update { it.copy(pickedAvatarBytes = bytes, pickedAvatarHash = hash) }
    }
}

/** 移除刚选中的那张图（还原到「还没选图」态）。只清本机，不碰任何已宣告的东西。 */


/** 移除刚选中的那张图（还原到「还没选图」态）。只清本机，不碰任何已宣告的东西。 */
fun handleRemovePickedAvatar() {
    host._state.update { it.copy(pickedAvatarBytes = null, pickedAvatarHash = null) }
}

/**
 * 上传头像的单图上限：**32 KiB**。
 *
 * 取自原型 `m-set-avatar` 第 3 步逐字「webp 非动图、≤ 32 KiB」与那屏 annot 的
 * 「源图那边另有两个上限：单边 ≤ 8192、≤ 20 MiB」（`prototype/relay.html` 的
 * `avUploader()` 段）。这里守的是**落盘后的那张**，也就是 32 KiB 这一条 ——
 * 8192/20MiB 是"源图"的闸，属于还没实现的编码那一步（Compose 端不做解码与裁切，
 * 见 [SetAvatarPage] 的 KDoc），这一轮不假装做了。
 */
}


/**
 * 上传头像的单图上限：**32 KiB**。
 *
 * 取自原型 `m-set-avatar` 第 3 步逐字「webp 非动图、≤ 32 KiB」与那屏 annot 的
 * 「源图那边另有两个上限：单边 ≤ 8192、≤ 20 MiB」（`prototype/relay.html` 的
 * `avUploader()` 段）。这里守的是**落盘后的那张**，也就是 32 KiB 这一条 ——
 * 8192/20MiB 是"源图"的闸，属于还没实现的编码那一步（Compose 端不做解码与裁切，
 * 见 [SetAvatarPage] 的 KDoc），这一轮不假装做了。
 */
internal const val MAX_AVATAR_BYTES: Long = 32L * 1024
