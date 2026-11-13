// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kapp.tenon.core.protocol.RosterWriteTier
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.roster_write_ask
import com.kapp.tenon.generated.resources.roster_write_auto
import com.kapp.tenon.generated.resources.roster_write_current_ask
import com.kapp.tenon.generated.resources.roster_write_current_auto
import com.kapp.tenon.generated.resources.roster_write_desc
import com.kapp.tenon.generated.resources.roster_write_denied
import com.kapp.tenon.generated.resources.roster_write_error
import com.kapp.tenon.generated.resources.roster_write_note
import com.kapp.tenon.generated.resources.roster_write_title
import com.kapp.tenon.generated.resources.roster_write_unavailable
import com.kapp.tenon.ui.components.AppButton
import com.kapp.tenon.ui.components.AppButtonSize
import com.kapp.tenon.ui.components.AppOutlinedButton
import com.kapp.tenon.ui.theme.LocalAppSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * 写面档位在**界面这一侧**的窄接口（与 [ToolApprovalsSource] 同一条缝）。
 *
 * ## 为什么这一块有意不做跨端（真机走查的同族缺口，裁决：不补）
 *
 * 免批规则跨端了（atr1），写编制档位**不跨**，两条理由：
 * 1. **主权语义不同**：免批规则是「对端按了始终允许产生的事实清单」，看见与收回
 *    是配对的；档位是机主**策略**（每次问我 / 直接执行），机主席位在桌面 ——
 *    与「设置 → 授信设备」同席。
 * 2. **caps 预算 16/16 满额**：再开一族帧必须再让一项出广播档（att1 已为 atr1
 *    让过位），为一块桌面策略开关再削一项能力不值。
 * 手机答编制写审批卡（`request` 档的卡走 agent-approval 帧）的能力不受此限。
 * 下次真机走查若再发现这条，指向本注即可，不必重新展开。
 *
 * 档位文件与判定逻辑住在 `:agentNode`（JVM-only，跟着跑 agent 的那台机器走），
 * 而这一块 UI 住 commonMain（android 截图门要够得着它）。所以只暴露三件事：
 * 读当前档、读读盘错误、写档。
 *
 * **没有「能不能写」这种能力位**：控制面没挂上时整个 source 是 null。
 */
interface RosterWriteTierSource {

    /** 当前档位（宿主侧已把未知值折成 [RosterWriteTier.REQUEST]，这里不再判一次）。 */
    fun tier(): String

    /** 读盘失败的如实记录；null = 正常。 */
    fun error(): String?

    /** 写档并落盘；false = 没写成（界面必须说出来，不能假装切了）。 */
    fun setTier(next: String): Boolean
}

/**
 * 档位的读与改（界面这一侧的纯逻辑）。
 *
 * 单测只需要它：当前档读得对不对、切换失败时**不许**把界面改成新档、读盘错误要能透出来 ——
 * 这三件事都能在不渲染 Compose 的前提下钉住。
 */
class RosterWriteAdmin(private val source: RosterWriteTierSource) {

    fun current(): String = source.tier().takeIf { it in RosterWriteTier.VALUES } ?: RosterWriteTier.REQUEST

    fun error(): String? = source.error()

    /**
     * 切到 [next]，返回**切换后**真正生效的档位。
     *
     * 写盘失败时返回旧档而不是新档：界面若先乐观切过去，用户会看到「直接执行」已选中
     * 而宿主仍每次都问 —— 这种不一致比一次明确的失败更难查。
     */
    fun apply(next: String): String {
        if (next !in RosterWriteTier.VALUES) return current()
        return if (source.setTier(next)) next else current()
    }
}

/**
 * 「agent 改编制的授权」区块：**默认每次问我**，这里可以改成直接执行。
 *
 * ## 它补的是哪一段
 *
 * 工具面（`/v1/agent/tools`）落地时档位只能改文件（`roster-write.json`）——
 * 而档位的存在意义就是「机主自己决定要不要每次点同意」。只能改文件等于**没有开关**：
 * 绝大多数用户不会去翻 `~/.tenon/`，于是这套能力永远停在「每次都弹卡」，
 * 或者更糟：有人手改了文件、忘了改回来，界面上又看不到。
 *
 * ## 为什么不做成「一个开关」
 *
 * 两档说的是两件事：`request` 是「我每次都要看」，`auto` 是「这批事我信了」。
 * 一个开关只能表达「开/关」，用户看不出关掉之后是「不问了」还是「不许写了」——
 * 后者是这个系统里最不能猜的语义。
 *
 * ## 谁不能改它
 *
 * agent 自己不能：档位只有本文件 + 人的界面能写，控制面**没有**写档的路由
 * （拿到 token 的本地进程包括 agent，给它一条写路由等于让它给自己放行）。
 */
@Composable
fun RosterWriteSection(
    source: RosterWriteTierSource?,
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    // 写完/写失败都要立刻反映：档位是我们这一页改的，不该等外部回调。
    var epoch by remember { mutableIntStateOf(0) }
    var lastApply by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val admin = remember(source, epoch) { source?.let { RosterWriteAdmin(it) } }
    val current = admin?.current() ?: RosterWriteTier.REQUEST
    val error = admin?.error()

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(sp.lg),
            verticalArrangement = Arrangement.spacedBy(sp.md),
        ) {
            Text(
                text = stringResource(Res.string.roster_write_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(Res.string.roster_write_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when {
                // 读不到 ≠ 默认档。控制面没起时画成「每次问我」会让人以为开关是好的。
                source == null -> Text(
                    text = stringResource(Res.string.roster_write_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> {
                    // 点当前档 = 重写一次同样的值（幂等），不做成「点了没反应」的死按钮：
                    // 这一页上唯一的反馈就是这颗按钮，它哑掉时用户没法判断自己点到了没有。
                    val choose: (String) -> Unit = { next ->
                        lastApply = next to (admin?.apply(next) == next)
                        epoch++
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(sp.md)) {
                        // 选中的那颗实心、另一颗描边：两档互斥，不该看起来同样重。
                        if (current == RosterWriteTier.REQUEST) {
                            AppButton(
                                text = stringResource(Res.string.roster_write_ask),
                                size = AppButtonSize.Small,
                                onClick = { choose(RosterWriteTier.REQUEST) },
                            )
                            AppOutlinedButton(
                                text = stringResource(Res.string.roster_write_auto),
                                size = AppButtonSize.Small,
                                onClick = { choose(RosterWriteTier.AUTO) },
                            )
                        } else {
                            AppOutlinedButton(
                                text = stringResource(Res.string.roster_write_ask),
                                size = AppButtonSize.Small,
                                onClick = { choose(RosterWriteTier.REQUEST) },
                            )
                            AppButton(
                                text = stringResource(Res.string.roster_write_auto),
                                size = AppButtonSize.Small,
                                onClick = { choose(RosterWriteTier.AUTO) },
                            )
                        }
                    }

                    Text(
                        text = stringResource(
                            if (current == RosterWriteTier.AUTO) {
                                Res.string.roster_write_current_auto
                            } else {
                                Res.string.roster_write_current_ask
                            },
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // 写盘失败必须报：静默失败会让人以为「下次就不问了」，而下次照样弹卡。
                    lastApply?.let { (tier, ok) ->
                        if (!ok) {
                            Text(
                                text = stringResource(Res.string.roster_write_denied, tier),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            // 档位文件坏了：界面上要看见，否则「我明明配了直接执行」永远是个悬案。
            error?.let { detail ->
                Text(
                    text = stringResource(Res.string.roster_write_error, detail),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = stringResource(Res.string.roster_write_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
