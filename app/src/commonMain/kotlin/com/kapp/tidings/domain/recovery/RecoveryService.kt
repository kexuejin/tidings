// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain.recovery

import com.kapp.tidings.core.crypto.secureRandomBytes
import com.kapp.tidings.core.persist.RecoveryBundle
import com.kapp.tidings.core.persist.RecoveryIdentityPayload
import com.kapp.tidings.core.transport.PeerPinStore
import com.kapp.tidings.platform.AppPlatform
import com.kapp.tidings.domain.RelaySettings
import com.kapp.tidings.domain.SettingsStore

/**
 * 身份恢复（L1 + L0 收养）的领域服务：身份包的**组装、落钥匙、写信任根**三件事。
 *
 * 刻意不碰 settings 与节点生命周期 —— 那两件事的顺序纪律（"恢复先于 ensure()"、
 * "导入后重启节点生效"）归 [com.kapp.tidings.domain.RelayController]（方案 §9.2），
 * 这里只提供它要的原语。
 *
 * ## 载荷怎么来、怎么去
 *
 *  · 组装（导出 / L0 备份）：读本机钥匙材料（平台 actual）+ 对端 pin（信任根，
 *    上限 [RecoveryBundle.MAX_PEER_PINS] 条，Q4）→ [RecoveryIdentityPayload]；
 *  · 应用（导入 / 收养）：写钥匙材料 + **整体替换** pin 表。
 *    替换而不是合并的理由：恢复进来的身份是"另一台设备一生的关系"，pin 表是它
 *    的一部分；本机旧 pin 属于旧身份，留一半扔一半只会造出「对谁都要重新 TOFU」
 *    的中间态。
 */
class RecoveryService(
    private val linkIdentityDir: String,
    private val pins: PeerPinStore,
) {

    /** 组装当前身份的载荷；身份还没生成（首启前）返回 null。 */
    fun currentPayload(nodeId: String): RecoveryIdentityPayload? {
        if (nodeId.isBlank()) return null
        val material = readIdentityKeyMaterial(linkIdentityDir) ?: return null
        val pinList = pins.load()
            .map { (peerId, wire) -> RecoveryIdentityPayload.PeerPin(peerId, wire) }
            .take(RecoveryBundle.MAX_PEER_PINS)
        return RecoveryIdentityPayload(
            nodeId = nodeId,
            ecPrivateScalar = material.ecPrivateScalar,
            spki = material.spki,
            peerPins = pinList,
        )
    }

    /**
     * 把恢复出的身份落到本机：钥匙文件 + pin 表（整体替换，见类 KDoc）。
     * **不碰 settings、不碰运行中的节点** —— 调用方负责顺序与重启。
     */
    fun applyPayload(payload: RecoveryIdentityPayload) {
        writeIdentityKeyMaterial(linkIdentityDir, payload.ecPrivateScalar, payload.spki)
        pins.save(payload.peerPins.associate { it.nodeId to it.pinWire })
        // 来源全部记成 pairing：这是配对时学来的信任根，随身份包一起回来的。
        pins.saveSources(payload.peerPins.associate { it.nodeId to PIN_SOURCE_PAIRING })
    }

    /**
     * L0 收养（仅在 settings 不存在时调用，Q10）：落钥匙与 pins，再种一份
     * 带恢复 nodeId 的 settings —— 之后调用方的正常加载路径接手。
     * 显示名用宿主默认名：身份跟着人走，**设备名**跟着设备走。
     */
    fun adoptForFreshInstall(payload: RecoveryIdentityPayload, store: SettingsStore, platform: AppPlatform) {
        applyPayload(payload)
        store.save(RelaySettings.firstRun(platform.deviceDefaultNick).copy(nodeId = payload.nodeId))
    }

    companion object {
        const val PIN_SOURCE_PAIRING = "pairing"

        private const val STAMP_RADIX = 36

        /** 备份文件的默认名：`relay-backup-<stamp>.rlrb`。stamp 用 base36 时间戳，不引入日期格式化依赖。 */
        fun backupFileName(nowEpochMs: Long, random: ByteArray = secureRandomBytes(2)): String =
            BACKUP_FILE_PREFIX + nowEpochMs.toString(STAMP_RADIX) + "-" +
                random.joinToString("") { (it.toInt() and 0xff).toString(STAMP_RADIX).padStart(2, '0') } +
                BACKUP_FILE_EXTENSION

        const val BACKUP_FILE_PREFIX = "relay-backup-"
        const val BACKUP_FILE_EXTENSION = ".rlrb"
    }
}
