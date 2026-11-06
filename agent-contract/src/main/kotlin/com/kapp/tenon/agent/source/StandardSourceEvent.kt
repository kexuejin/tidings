// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.source

import kotlinx.serialization.Serializable

/**
 * Standardized 6-field event model for external ingress sources (§3.3).
 */
@Serializable
data class StandardSourceEvent(
    val id: String,
    val type: String,
    val subject: String,
    val summary: String,
    val detail: String,
    val link: String? = null,
) {
    companion object {
        const val MAX_SUMMARY_CHARS: Int = 200
        const val MAX_DETAIL_CHARS: Int = 4000

        fun create(
            id: String,
            type: String,
            subject: String,
            summary: String,
            detail: String,
            link: String? = null,
        ): StandardSourceEvent {
            require(id.isNotBlank()) { "id must not be blank" }
            require(type.isNotBlank()) { "type must not be blank" }
            require(subject.isNotBlank()) { "subject must not be blank" }

            return StandardSourceEvent(
                id = id.trim(),
                type = type.trim(),
                subject = subject.trim(),
                summary = summary.trim().take(MAX_SUMMARY_CHARS),
                detail = detail.trim().take(MAX_DETAIL_CHARS),
                link = link?.trim()?.ifBlank { null },
            )
        }
    }
}
