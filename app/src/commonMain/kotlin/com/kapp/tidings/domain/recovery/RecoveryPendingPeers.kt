// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain.recovery

import com.kapp.tidings.core.fs.deleteFile
import com.kapp.tidings.core.fs.makeDirectories
import com.kapp.tidings.core.fs.openRandomAccess
import com.kapp.tidings.core.fs.pathJoin
import com.kapp.tidings.core.fs.readFileBytes
import com.kapp.tidings.core.fs.use
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * L2 待拉取对端集合的落盘（`stores/recovery-pending.json`）。
 *
 * 为什么必须持久化：集合只在内存里的话，「收养完成 → 进程在 drain 前被杀」之后，
 * 下次启动 settings 已存在、不再走收养、集合为空 —— 这次恢复的历史拉取**静默丢失**，
 * 没有任何痕迹。落盘后下次启动照常发起（ACK 不到就一直留到对端升级或上线为止）。
 *
 * 尽力而为：写失败不抛（备份路径同款纪律）—— 丢这份清单的后果是「下次再试」，
 * 不是数据丢失。
 */
internal object RecoveryPendingPeers {

    @Serializable
    private data class Pending(val peers: List<String>)

    private val json = Json { ignoreUnknownKeys = true }

    fun load(dataDir: String): Set<String> {
        val bytes = readFileBytes(pathJoin(dataDir, "stores", FILE_NAME)) ?: return emptySet()
        return runCatching {
            json.decodeFromString(Pending.serializer(), bytes.decodeToString()).peers.toSet()
        }.getOrDefault(emptySet())
    }

    fun save(dataDir: String, peers: Set<String>) {
        if (peers.isEmpty()) {
            // 清空 = 删文件：空文件与「从没进入过恢复」不可区分，也不需要区分。
            deleteFile(pathJoin(dataDir, "stores", FILE_NAME))
            return
        }
        runCatching {
            val dir = pathJoin(dataDir, "stores")
            makeDirectories(dir)
            val target = pathJoin(dir, FILE_NAME)
            deleteFile(target)
            val bytes = json.encodeToString(Pending.serializer(), Pending(peers.toList())).encodeToByteArray()
            openRandomAccess(target, forWrite = true).use { it.append(bytes, bytes.size) }
        }
    }

    private const val FILE_NAME = "recovery-pending.json"
}
