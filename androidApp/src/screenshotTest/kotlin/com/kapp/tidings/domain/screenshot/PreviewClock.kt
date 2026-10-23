// SPDX-License-Identifier: GPL-3.0-only

@file:Suppress("ConstPropertyName")

package com.kapp.tidings.domain.screenshot

/**
 * 预览用的固定基准时刻。
 *
 * 为什么必须固定：同一帧两次渲染要出**逐像素相同**的图。`lastSeenAt` 一类字段
 * 走 `relativeTime(at, now)`，若 `now` 读挂钟，今天早上绿、明天早上就红，
 * 而且是全库一起红。
 *
 * 这个常量原来在 6 个文件里各有一份。5 份取 `1_735_689_600_000`（2025-01-01T00:00Z），
 * `ScreenshotPreviewsExtra.kt` 取 `1_789_000_000_000`（2026-09-10T00:26:40Z）
 * —— 不是笔误，那份的注释写了理由：取一个整点之外的数，顺便证明参考图
 * 不依赖「整点」这种巧合，也不依赖 JVM 默认时区。
 *
 * 两个取值各有各的参考图，所以**这里保留两套名字**，不强行统一成一个 ——
 * 统一会让 100 多张参考图里的时间文案全变。
 */
internal const val PREVIEW_CLOCK_BASE = 1_735_689_600_000L

/** Extra 那族用的非整点基准。见上面「不是笔误」的说明。 */
internal const val PREVIEW_CLOCK_BASE_ODD = 1_789_000_000_000L

/** 预览里的「现在」，传给屏幕的 `now` 参数，绝不读挂钟。 */
internal const val PREVIEW_NOW = PREVIEW_CLOCK_BASE + 25 * 60_000L

/**
 * Extra 那族的「现在」，以非整点基准为原点。
 *
 * 与 [PREVIEW_NOW] 分成两个不是重复：Extra 族 100 多张参考图里的时间文案是按
 * 非整点基准渲染的，改用整点基准会让那些帧整体变红。第一版只留一个
 * `PREVIEW_NOW`，结果是 7 帧变红（agents-long-text / agents-sessions /
 * conversations-banner / conversations-group-create / conversations-long-text /
 * agents-session-no-alias / conversations-group）。
 */
internal const val PREVIEW_NOW_ODD = PREVIEW_CLOCK_BASE_ODD + 25 * 60_000L

/** 相对基准时刻偏移若干分钟；负数表示基准之前。 */
internal fun at(minutes: Long): Long = PREVIEW_CLOCK_BASE + minutes * 60_000L

/** 同上，但以非整点基准为原点（Extra 那族专用）。 */
internal fun atOdd(minutes: Long): Long = PREVIEW_CLOCK_BASE_ODD + minutes * 60_000L
