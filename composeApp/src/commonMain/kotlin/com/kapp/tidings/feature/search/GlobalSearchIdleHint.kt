// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.global_search_hint
import com.kapp.tidings.ui.theme.LocalAppSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * 「还没输入关键词」时的提示 —— 手机与桌面**共用这一份**（内容属 §2.6 的"空态"）。
 *
 * 为什么要有它：过去两个壳在 Idle 上各走各的 —— 手机是 `Idle -> Unit`（整页空白，
 * 用户不知道这里能搜什么），桌面直接把空查询当"没找到"，画成「没有找到「」相关的内容」。
 * 同一个"还没开始搜"在两端给出两种解释，其中一种是错的。
 *
 * 贴顶部锚定（不垂直居中）：搜索框就在上面，提示要跟着框走，像原型 `.spph` 那样。
 * 文案复用早就写好、却一直没人消费的 `global_search_hint`（本次把它接上）。
 */
@Composable
internal fun GlobalSearchIdleHint(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = LocalAppSpacing.current.lg14,
                end = LocalAppSpacing.current.lg14,
                top = LocalAppSpacing.current.xl,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.global_search_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
