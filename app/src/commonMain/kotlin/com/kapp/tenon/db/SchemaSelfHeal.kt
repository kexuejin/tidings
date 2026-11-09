// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.db

import app.cash.sqldelight.db.SqlDriver

/**
 * 开库后**无条件**跑一遍的幂等补建（D4）。
 *
 * ## 为什么版本号不能当"表都在"的证据
 *
 * 真机现场（宿主，202 MB 老库）：`PRAGMA user_version = 21`，而 `BoardCardRev` **不存在**，
 * 每张卡每帧刷一行 `rev ledger save FAILED … no such table: BoardCardRev`。
 * 原因不是 `.sq` 漏建，也不是 20.sqm 写错（手机库同版本号**有**这张表）；是这台机器的
 * 库血统把 19/20 两段整体跳过了 —— 桌面那条迁移入口用
 * `max(user_version, 列形状下限)` 决定起点，老库的列形状把它抬到了 21，
 * 于是"还没执行过的 20"被判成"早就执行过"。
 *
 * 迁移血统是**一次性**的历史，修它等于给所有老库重排一遍版本号，代价远大于收益。
 * 所以这里换一条更窄、也更可靠的路：**不依赖版本号，直接问"这张表在不在"**。
 * `CREATE TABLE IF NOT EXISTS` 对健康的库是空操作，对残缺的库是自愈。
 *
 * ## 边界（写在这里，免得下一个人把它当成"万能迁移器"）
 *
 * 只补**新建表**这一类。**不改列、不删列、不搬数据** —— 那三件事必须走 `.sqm`，
 * 因为"A 库改了列、B 库没改"这种分歧，靠 `IF NOT EXISTS` 是表达不了的。
 * 将来若要在这里加表，先问一句：这张表在任何老库上都不存在吗？
 * 若答案是"可能以旧形状存在"，它就不属于这里。
 */
internal object SchemaSelfHeal {

    /**
     * `20.sqm` 的同一条 DDL（`SchemaSelfHealTest` 逐字比对两边，改一边不改另一边会红）。
     *
     * 不直接读 `.sqm` 文件：运行期没有那个文件（迁移脚本只参与代码生成），
     * 也不该为了补建去依赖构建产物的落点。
     */
    internal const val BOARD_CARD_REV =
        "CREATE TABLE IF NOT EXISTS BoardCardRev (" +
            "card_id TEXT NOT NULL PRIMARY KEY, " +
            "last_rev INTEGER NOT NULL, " +
            "updated_at INTEGER NOT NULL)"

    /** 顺序无所谓（每条自包含），列在这里只是为了让 [run] 有得遍历。 */
    internal val statements: List<String> = listOf(BOARD_CARD_REV)

    /**
     * `21.sqm` 的调度台账表（触发式编排提案 §9 阶段二）。
     *
     * 它同样落进这张补建清单，理由和 `BoardCardRev` 一模一样：桌面入口的
     * `card_payload` 哨兵把迁移起点直接抬到 `schemaVersion`，`21.sqm` 会被判成
     * 「早就跑过」而整段跳过。症状不是启动报错，是**每条 webhook 都落不了库**
     * （`no such table: TriggerEvent` → ACK 路径转成 `503 persist-failed`，
     * 发送方永远重试）。这两张表在任何老库上都不存在，正属于这里该管的一类。
     */
    internal const val SCHEDULER_TASK_STATE =
        "CREATE TABLE IF NOT EXISTS SchedulerTaskState (" +
            "task_id TEXT NOT NULL PRIMARY KEY, " +
            "last_run_at INTEGER, " +
            "next_run_at INTEGER NOT NULL, " +
            "jitter_offset_sec INTEGER NOT NULL, " +
            "missed_runs INTEGER NOT NULL DEFAULT 0, " +
            "updated_at INTEGER NOT NULL)"

    /** `21.sqm` 的触发事件队列（复合主键 `(source_id, event_id)`，见 21.sqm 注释）。 */
    internal const val TRIGGER_EVENT =
        "CREATE TABLE IF NOT EXISTS TriggerEvent (" +
            "event_id TEXT NOT NULL, " +
            "source_id TEXT NOT NULL, " +
            "type TEXT NOT NULL, " +
            "subject TEXT NOT NULL, " +
            "summary TEXT NOT NULL, " +
            "detail TEXT NOT NULL, " +
            "link TEXT, " +
            "state TEXT NOT NULL, " +
            "error TEXT, " +
            "created_at INTEGER NOT NULL, " +
            "updated_at INTEGER NOT NULL, " +
            "PRIMARY KEY (source_id, event_id))"

    /** `21.sqm` 的待消费索引。补了表不补索引，两种库的**索引形状**就分家了。 */
    internal const val TRIGGER_EVENT_STATE_INDEX =
        "CREATE INDEX IF NOT EXISTS idx_trigger_event_state ON TriggerEvent(state, created_at)"

    /** 顺序有意义：索引必须排在它依赖的表之后。 */
    internal val triggerStatements: List<String> = listOf(
        SCHEDULER_TASK_STATE,
        TRIGGER_EVENT,
        TRIGGER_EVENT_STATE_INDEX,
    )

    /**
     * 逐条补建。
     *
     * **失败只留痕不抛**：这一步是"修历史遗留"，不是"开库前置条件"。
     * 因为一句补建失败就让整个 App 起不来，比少一张 rev 台账严重得多
     * （台账缺了只是 rev 在内存里单调、重启靠卡自带 rev 续）。
     */
    fun run(driver: SqlDriver) {
        for (sql in statements + triggerStatements) {
            runCatching { driver.execute(identifier = null, sql = sql, parameters = 0).value }
                .onFailure { t ->
                    // 这一层没有 RelayLog（编排层才有），而它恰好是最需要被看见的一类失败：
                    // 静默的后果是"每帧一行 FAILED"刷满日志却没人知道该修什么。
                    println("[db] self-heal FAILED: " + (t.message ?: t::class.simpleName) + " | " + sql)
                }
        }
    }
}
