// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 表面三档 —— 原型 \`--surface\` / \`--surface-2\` / \`--surface-3\`。
 *
 * 出处：\`prototype/relay.html:1083\`
 * \`--surface:#1A211F; --surface-2:#222A28; --surface-3:#2A3331\`（由暗到亮三层）。
 *
 * ## 为什么要有这一层
 *
 * 这三档在主题里**一直都在**（\`Color.kt\` 的 \`surface\` / \`surfaceContainerHigh\` /
 * \`surfaceVariant\`），但界面只用了 \`surface\` 和 \`surfaceVariant\` 两个角色名，
 * 而且**混着用**：标签、胶囊、分段控件、内嵌计数、指示灯，统统写 \`surfaceVariant\`。
 * 于是原型里"卡片 < 次级块 < 内嵌控件"的三层纵深，在代码里退化成一层半 ——
 * 这正是"平、没层次"的机制性来源。
 *
 * ## 每档管什么（按原型的使用统计分的，不是凭感觉）
 *
 * | 档 | 原型变量 | 原型里用在哪（次数） | 代码里的等价角色 |
 * |---|---|---|---|
 * | [Base] | \`--surface\` | 卡片、列表行本体 | \`surface\` |
 * | [Raised] | \`--surface-2\` | 标签、胶囊、分段控件、卡片内次级块（**47 处**） | \`surfaceVariant\` |
 * | [Inset] | \`--surface-3\` | 小内嵌控件：计数、清除按钮、指示灯、图标座（**11 处**） | \`accents.surface3\` |
 *
 * ## 用法
 *
 * \`\`\`kotlin
 * Surface(color = SurfaceTiers.Raised) { Text("同事") }   // 一个标签
 * \`\`\`
 *
 * 写 \`SurfaceTiers.Raised\` 而不是直接写 \`colorScheme.surfaceContainerHigh\`：
 * 名字说的是**这一处在界面上是哪一层**，而不是"哪个 M3 槽位恰好是这个颜色值" ——
 * 后者换个主题就会错，前者不会。
 */
object SurfaceTiers {

    /** 卡片、列表行本体：原型 \`--surface\`。 */
    val Base: Color
        @Composable get() = MaterialTheme.colorScheme.surface

    /** 标签 / 胶囊 / 分段控件 / 卡片内次级块：原型 \`--surface-2\`（用得最多的一档，47 处）。 */
    val Raised: Color
        @Composable get() = MaterialTheme.colorScheme.surfaceVariant

    /** 小内嵌控件（计数、清除按钮、指示灯、图标座）：原型 \`--surface-3\`。 */
    val Inset: Color
        @Composable get() = MaterialTheme.accents.surface3
}
