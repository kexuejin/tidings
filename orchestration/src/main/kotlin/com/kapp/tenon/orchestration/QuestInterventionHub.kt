// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.core.protocol.QuestStatus
import com.kapp.tenon.db.RelayDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * 支线任务协程挂起与人类插话唤醒中枢 (QuestInterventionHub)。
 *
 * 核心功能与健壮性保障：
 * 1. 遇到门禁受阻或歧义时挂起协程，等待人类输入；
 * 2. 并发重入时原子置换并显式取消旧协程，避免孤儿挂起；
 * 3. finally 块使用 CAS 比较移除 (remove(key, value))，避免误删新协程 deferred；
 * 4. 挂起状态持久化至 SQLite (QuestSuspendedState)，所有 DB 读写均切入 Dispatchers.IO，消除 Android 主线程阻塞风险；
 * 5. 协程被取消时 (CancellationException) 安全捕获并落库 CANCELLED 状态，派发回调后重抛；
 * 6. 超时后转为 PAUSED 态并释放协程，通知 UI 显示已暂停 —— **投递与超时的竞态必须收口**：
 *    [submitHumanInput] 的「摘除 + complete」是同一个原子动作，超时路径先 CAS 摘除再落
 *    PAUSED，两个方向都不存在「输入已收下却谎报成功/被丢弃」（§3 静默丢失）；
 * 7. [resumePausedQuest] 恢复 PAUSED / INTERRUPTED 态：翻面回 WAITING_INPUT 并通知前端。
 *    **契约**：恢复只翻状态与登记行，不重建 worker 协程 —— 支线 runner 重新拉起后经
 *    [awaitHumanInput] 消费该行（含机主恢复时补充的指令，见 [additionalInput] 并入 prompt）。
 *    没有重拉 runner 之前，行会停在 WAITING_INPUT，开机扫描按 INTERRUPTED 收口；
 * 8. 桌面端开机重启时扫描数据库未决任务，统一翻面为 INTERRUPTED 态并通知前端恢复。
 */
class QuestInterventionHub(
    private val database: RelayDatabase,
    private val onStatusUpdate: suspend (questId: String, status: QuestStatus) -> Unit = { _, _ -> },
) {
    private val pendingInputs = ConcurrentHashMap<String, CompletableDeferred<String>>()

    suspend fun awaitHumanInput(
        questId: String,
        convId: String,
        prompt: String,
        timeoutMs: Long = 300_000L,
        nowMillis: Long = System.currentTimeMillis(),
        onStarted: () -> Unit = {},
    ): String? {
        val deferred = CompletableDeferred<String>()
        val old = pendingInputs.put(questId, deferred)
        old?.cancel(CancellationException("Replaced by newer intervention step for quest: $questId"))

        withContext(Dispatchers.IO) {
            database.relayDatabaseQueries.insertOrReplaceQuestSuspendedState(
                quest_id = questId,
                conv_id = convId,
                prompt = prompt,
                status = "WAITING_INPUT",
                rev = 1L,
                suspended_at = nowMillis,
            )
        }

        onStarted()

        return try {
            val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
            if (result != null) {
                withContext(Dispatchers.IO) {
                    database.relayDatabaseQueries.deleteQuestSuspendedStateById(questId)
                }
                result
            } else {
                // 超时边界三态，全部不许谎报（§3）：
                // ① complete 恰在超时后到达（deferred 已完成）→ 取它的值按成功收尾；
                // ② 没人投递且 CAS 摘除成功 → 才写 PAUSED 并通知；
                // ③ 摘除失败（新重入已置换）→ 行归新协程，旧协程什么都不写。
                if (deferred.isCompleted) {
                    val late = runCatching { deferred.await() }.getOrNull()
                    if (late != null) {
                        if (pendingInputs[questId] == null) {
                            withContext(Dispatchers.IO) {
                                database.relayDatabaseQueries.deleteQuestSuspendedStateById(questId)
                            }
                        }
                        return late
                    }
                }
                if (pendingInputs.remove(questId, deferred) && pendingInputs[questId] == null) {
                    withContext(Dispatchers.IO) {
                        database.relayDatabaseQueries.updateQuestSuspendedStateStatus("PAUSED", questId)
                    }
                    onStatusUpdate(questId, QuestStatus.PAUSED)
                }
                null
            }
        } catch (e: CancellationException) {
            // 仅在当前 deferred 仍是该任务的最新注册句柄时才标记 CANCELLED；若已被新重入置换，不破坏新任务的 WAITING_INPUT
            if (pendingInputs[questId] === deferred) {
                withContext(NonCancellable + Dispatchers.IO) {
                    database.relayDatabaseQueries.updateQuestSuspendedStateStatus("CANCELLED", questId)
                    onStatusUpdate(questId, QuestStatus.CANCELLED)
                }
            }
            throw e
        } finally {
            // CAS 移除：严禁无条件 remove(questId)，杜绝新注册 deferred 被旧协程误删
            pendingInputs.remove(questId, deferred)
        }
    }

    /**
     * UI 表现层调用：机主回复时唤醒协程。
     *
     * 「摘除 + complete」必须是**一个**原子动作：先 complete 后摘除的话，超时路径
     * 看到的是「未摘除的未完成 deferred」之外还有一个已 deliver 的值 —— 两个方向
     * 各写一次终态。CAS 摘除成功才 complete：输给超时路径就如实返回 false，
     * 绝不让 UI 以为投递成功而结果被丢弃。
     */
    fun submitHumanInput(questId: String, input: String): Boolean {
        val deferred = pendingInputs[questId] ?: return false
        if (!pendingInputs.remove(questId, deferred)) return false
        return deferred.complete(input)
    }

    /**
     * 恢复处于 PAUSED 或 INTERRUPTED 态的支线任务：翻面回 WAITING_INPUT 并通知前端。
     *
     * @param additionalInput 机主恢复时补充的指令；**不落盘就等于蒸发**（§3）——
     *   并进挂起行 prompt，支线 runner 重新 [awaitHumanInput] 时读行即拿到完整上下文。
     */
    suspend fun resumePausedQuest(questId: String, additionalInput: String? = null): Boolean = withContext(Dispatchers.IO) {
        val item = database.relayDatabaseQueries.selectQuestSuspendedStateById(questId).executeAsOneOrNull()
        if (item != null && (item.status == "PAUSED" || item.status == "INTERRUPTED")) {
            val merged = additionalInput?.takeIf { it.isNotBlank() }?.let {
                item.prompt + "\n[owner resume] " + it.trim()
            } ?: item.prompt
            database.relayDatabaseQueries.updateQuestSuspendedStatePrompt(merged, questId)
            database.relayDatabaseQueries.updateQuestSuspendedStateStatus("WAITING_INPUT", questId)
            onStatusUpdate(questId, QuestStatus.WAITING_INPUT)
            true
        } else {
            false
        }
    }

    /** 任务主动取消时撤销挂起。 */
    suspend fun cancelWait(questId: String) = withContext(Dispatchers.IO) {
        pendingInputs.remove(questId)?.cancel()
        database.relayDatabaseQueries.deleteQuestSuspendedStateById(questId)
    }

    /** 桌面端开机重启时调用：扫描数据库未决挂起记录，统一翻面为 INTERRUPTED 态。 */
    suspend fun recoverInterruptedQuestsOnStartup() = withContext(Dispatchers.IO) {
        val suspendedList = database.relayDatabaseQueries.selectAllQuestSuspendedStates().executeAsList()
        for (item in suspendedList) {
            if (item.status == "WAITING_INPUT") {
                database.relayDatabaseQueries.updateQuestSuspendedStateStatus("INTERRUPTED", item.quest_id)
                onStatusUpdate(item.quest_id, QuestStatus.INTERRUPTED)
            }
        }
    }

    suspend fun cleanupTerminalState(questId: String) = withContext(Dispatchers.IO) {
        database.relayDatabaseQueries.deleteQuestSuspendedStateById(questId)
    }

    fun isPending(questId: String): Boolean = pendingInputs.containsKey(questId)
}
