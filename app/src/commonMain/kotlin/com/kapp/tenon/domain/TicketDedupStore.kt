// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

/**
 * 支线任务指令内存去重缓存 (TicketDedupStore)。
 *
 * 作为端侧运行期针对网络重试与离线队列快速重放的内存幂等看门狗，
 * 与持久层 MessageEntity.client_token 索引及去重逻辑构成双层幂等防线。
 * 单进程内相同 clientToken 到达时直接返回已有 questId，消除重复沙箱开单。
 *
 * 构造公开给 :orchestration（QuestRunner 的开单闸门）：:app ← :orchestration 是
 * 文档化的依赖方向；此前 internal 是因为还没有生产调用方。
 */
class TicketDedupStore() {
    private val tokenToQuest = LockedMap<String, String>()

    /**
     * 尝试记录 clientToken 与 questId 的绑定关系。
     * 若已存在则返回已有 questId；若不存在则调用 creator 产生并存入。
     */
    fun getOrPutQuestId(clientToken: String, creator: () -> String): String {
        return tokenToQuest.locked { map ->
            map.getOrPut(clientToken) { creator() }
        }
    }

    /**
     * 原子认领（开单入口的幂等闸门）：token 未登记时存入 [creator] 的结果并返回
     * **null** —— 调用方赢了这一单，负责把任务真正跑起来；已登记返回已有 questId
     * （重放 / 并发重入），调用方必须归并到那一单、绝不重复开单。
     *
     * 与 [getOrPutQuestId] 的差别在于**归属可判**：getOrPut 两侧拿到同一个 id 却
     * 分不出「是谁开的单」，认领语义要求输家放弃执行 —— 那正是防重复开单的本体。
     */
    fun claim(clientToken: String, creator: () -> String): String? = tokenToQuest.locked { map ->
        val existing = map[clientToken]
        if (existing != null) {
            existing
        } else {
            map[clientToken] = creator()
            null
        }
    }

    fun findQuestId(clientToken: String): String? {
        return tokenToQuest[clientToken]
    }

    fun has(clientToken: String): Boolean {
        return tokenToQuest[clientToken] != null
    }

    fun clear() {
        tokenToQuest.locked { it.clear() }
    }
}
