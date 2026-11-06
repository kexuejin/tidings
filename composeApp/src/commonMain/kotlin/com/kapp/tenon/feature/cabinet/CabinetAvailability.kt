// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.cabinet

/**
 * 「打开共享目录」这颗按钮画不画的**唯一判据**：能力缺失 = 不画。
 *
 * 移动端 `CabinetScreen` 与桌面端 `CabinetPane` 共用 `MineCabinetView`，两端只把
 * 宿主给不给得出的动作透传进来；能不能画由这一个纯函数说了算，任何一端都不再
 * 自己写一份裸比较。抽成有名字的函数，是为了让源码守卫能钉住「判据只有一处」，
 * 也便于单测——两端过去靠 KDoc 里一句「同一个判据」人工同步，是会漂移的那种约定。
 */
internal fun shouldShowShareRoot(onOpenShareRoot: (() -> Unit)?): Boolean =
    onOpenShareRoot != null
