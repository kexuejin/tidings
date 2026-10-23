// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

/**
 * 通知点击时，从系统交给应用壳的那几个额外字段。
 *
 * 这个对象是**两侧的契约**：发通知的一侧在 [AndroidPushNotificationHandler]（composeApp），
 * 收通知的一侧在 `MainActivity`（androidApp）。androidApp 依赖 composeApp，反过来不行，
 * 所以键名只能放在这里 —— 两边各写一遍字面量的话，改一处就会静默失配：通知照发，
 * 点开却永远落在默认页上，而且没有任何报错。
 */
object AndroidNotificationIntent {
    /**
     * 点通知要定位到的对端 id（`String`）。
     *
     * 缺这个键（或值为空）时，壳只把应用打开，不做定位 —— 例如普通消息通知来自一个
     * 已经被删掉的会话。
     */
    const val EXTRA_FOCUS_PEER: String = "com.kapp.tidings.extra.FOCUS_PEER"
}
