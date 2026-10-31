// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.crypto

/**
 * M7-T3 切片 2b（D-015 ②）：产物**校验状态**的判定 —— 端到端那一道门
 * （agent declared 的 sha256 vs 已落盘文件的实测摘要）。
 *
 * ## 与传输校验的分工（D-15 探明的事实）
 *
 * `TransferClient` 的 `PullStage.Verify` 已在 receive 后实测摘要、不符删 .part 报
 * hash-mismatch —— 「传输没坏」这道门**已经有了**。这一道回答的是另一件事：
 * **agent declared 的就是这份吗**（agent 侧 bug / 清单过期 / 文件被换过）。
 * 两道门独立，这里不建第二套自动重拉编排（D-015 裁决：纯展示）。
 *
 * ## 三态，两态不许混判
 *
 *  - [Status.UNDECLARED]：帧没带 sha256（旧产物 / agf1 之前的对端）—— **没报 ≠ 空串
 *    ≠ 缺文件**，界面上它是一句「未声明」，不是「已校验」也不是「校验失败」；
 *  - [Status.FILE_MISSING]：declared 在、但文件读不到（未下载 / 被清）—— 校验无从谈起，
 *    如实说缺文件，**不判成功也不判失败**；
 *  - [Status.OK] / [Status.MISMATCH]：declared 与实测都拿到了才轮到比对 ——
 *    失败是可见状态（留证据给用户/诊断），**不删盘、不自动重拉**。
 *
 * 纯函数：declared/actual 两个 hex + 文件是否存在；「路径 → 实测 hex」的读取留在调用方
 * （平台侧才碰文件系统），这里只判 —— 判据可单测、可反向验证，不碰盘。
 */
object ArtifactVerification {

    enum class Status {
        /** 产物帧没带 sha256：没有凭据，谈不上校验。 */
        UNDECLARED,

        /** 有凭据但文件不在（未下载 / 被清理）：校验无从谈起。 */
        FILE_MISSING,

        /** declared ⟺ 实测一致。 */
        OK,

        /** declared ≠ 实测：agent 侧 declared 错或文件被换 —— 可见，不删盘。 */
        MISMATCH,
    }

    /**
     * 判一次校验状态。
     *
     * @param declared 产物帧带来的 sha256（null = 帧没带）；
     * @param actualHex 已落盘文件的实测摘要（null = 文件读不到 / 未下载）。
     *   declared 非 null 而 [actualHex] 为 null ⇒ [Status.FILE_MISSING]（两态不混判的红线）。
     */
    fun verify(declared: String?, actualHex: String?): Status {
        if (declared.isNullOrBlank()) return Status.UNDECLARED
        // declared 不是合法形状时同样按「没有凭据」处理 —— 一条坏凭据不能拿来判失败，
        // 否则对端一个写坏的键就能把所有产物刷成「校验失败」（可见性被滥用成噪声）。
        if (!isSha256Hex(declared)) return Status.UNDECLARED
        if (actualHex == null) return Status.FILE_MISSING
        return if (declared == actualHex) Status.OK else Status.MISMATCH
    }
}
