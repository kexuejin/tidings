// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.relay.app.RelaySettings
import app.relay.app.RelayUiState
import app.relay.generated.resources.Res
import app.relay.generated.resources.r2_set_nick_hint
import app.relay.generated.resources.r2_set_profile_avatar_label
import app.relay.generated.resources.r2_set_profile_node_chip
import app.relay.generated.resources.r2_set_profile_rev_chip
import app.relay.generated.resources.r2_set_profile_org
import app.relay.generated.resources.r2_set_profile_page_hint
import app.relay.generated.resources.r2_set_profile_team_hint
import app.relay.generated.resources.set_save_profile
import app.relay.generated.resources.set_company
import app.relay.generated.resources.set_dept
import app.relay.generated.resources.set_invalid_nick
import app.relay.generated.resources.set_nick
import app.relay.generated.resources.set_profile_title
import app.relay.generated.resources.set_team
import app.relay.ui.ContentWidths
import app.relay.ui.SectionCard
import app.relay.ui.centerCapped
import app.relay.ui.components.AvatarPicker
import app.relay.ui.components.LargeTitleBar
import app.relay.ui.components.PeerAvatar
import app.relay.ui.components.TitleBarDivider
import org.jetbrains.compose.resources.stringResource
import app.relay.ui.theme.LocalAppSpacing
import app.relay.ui.theme.relayCardShadow

/**
 * 资料编辑页的表单卡：**没有卡头**。
 *
 * 原型 `screenSetProfile`（`prototype/relay.html:3693-3694`）与桌面版 `screenDSettings`
 * （`prototype/relay.html:5257`）都是 `<div class="card" …><div class="padc">` 直接进第一行
 * 字段，没有 `<h3>`。用 [app.relay.ui.SectionCard] 会多画一行「我的资料」卡头（手机与桌面
 * 两个规范帧都多），所以这里只用它**同样的容器样式**：surface 底、[relayCardShadow]
 * 自绘的 `--shadow` 等效投影、`MaterialTheme.shapes.medium` 圆角、`lg` 内边距。
 */
@Composable
private fun ProfileFormCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .relayCardShadow(shape = MaterialTheme.shapes.medium),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        // 原型 `.card{box-shadow:var(--shadow)}`：海拔归零、投影自绘。
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(horizontal = LocalAppSpacing.current.lg, vertical = LocalAppSpacing.current.lg)) {
            content()
        }
    }
}

/** 二级子页：资料编辑。 */
@Composable
fun SettingsProfileSubpage(
    state: RelayUiState,
    onSaveProfile: (String, String, String, String, Int) -> Unit,
    onBack: (() -> Unit)? = null,
    /** 头像分段控件第三档「上传图片」的出口；null = 没接线，该档置灰。 */
    onOpenAvatarPage: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val settings = state.settings
    var nick by remember(settings.nick) { mutableStateOf(settings.nick) }
    var company by remember(settings.company) { mutableStateOf(settings.company) }
    var dept by remember(settings.dept) { mutableStateOf(settings.dept) }
    var team by remember(settings.team) { mutableStateOf(settings.team) }
    var avatar by remember(settings.avatar) { mutableStateOf(settings.avatar) }

    val nickInvalid = nick.isBlank()

    Column(modifier = modifier.fillMaxSize()) {
        // 页头分形态：手机端是 `topbar('我的资料','昵称、头像、公司、部门、团队',…)`
        // （原型 prototype/relay.html:3683 = 大标题 + 副标题 + 返回键）；桌面设置窗的右栏
        // 没有页头，只有一行小节标题 `.grphead`
        // （原型 prototype/relay.html:5248 `<div class="grphead" style="padding-top:0">我的资料</div>`；
        //  桌面窗变体 prototype/relay.html:136 `.win .grphead{padding:12px 3px 5px;font-size:11px;letter-spacing:.06em}`，
        //  行内 padding-top:0 覆盖后实际是 `0 3px 5px`）。
        // 判别式用 onBack == null：两条生产路径里只有桌面宿主传 null
        // （desktopApp/.../DesktopHosting.kt:531 传 null；本文件手机子页传 { subpage = null }）。
        if (onBack == null) {
            Text(
                text = stringResource(Res.string.set_profile_title),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.06.em,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 3.dp, end = 3.dp, bottom = 5.dp),
            )
        } else {
            LargeTitleBar(
                title = stringResource(Res.string.set_profile_title),
                subtitle = "昵称、头像、公司、部门、团队",
                onBack = onBack,
                contentMaxWidth = ContentWidths.Settings,
            )
            TitleBarDivider()
        }

        Column(
            modifier = Modifier
                .weight(1f)
                // 宽屏收列（原型 `.pad.set{max-width:640px;margin:0 auto}`，:1045）。
                // 放在 verticalScroll **之前**：被夹过的这一列就是滚动容器本身，
                // 于是滚动条、overscroll 与内容列边界三者重合，不会出现"能滚的比能看的宽"。
                .centerCapped(ContentWidths.Settings)
                .verticalScroll(rememberScrollState())
                .padding(
                    start = LocalAppSpacing.current.lg,
                    top = LocalAppSpacing.current.lg14,
                    end = LocalAppSpacing.current.lg,
                    bottom = LocalAppSpacing.current.lg,
                ),
            verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                // 原型 `.idcard{…border-radius:var(--r-card)…}`（`:1197`），而 `--r-card` 手机是
                // 16px（`:58` :root）、桌面设置窗覆盖成 12px（`:584`）⇒ 判别式沿用页头那条
                // `onBack == null`（只有桌面宿主传 null）。
                shape = RoundedCornerShape(if (onBack == null) 12.dp else 16.dp),
                modifier = Modifier.fillMaxWidth().padding(top = LocalAppSpacing.current.sm),
            ) {
                Row(
                    modifier = Modifier.padding(LocalAppSpacing.current.lg),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.lg14),
                ) {
                    PeerAvatar(name = nick, size = 54.dp, avatar = avatar)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.xs)) {
                        Text(
                            text = nick.ifBlank { "我的昵称" },
                            // 原型 `.pn{font-size:16px;font-weight:700}`（:1220）。
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "${company.ifBlank { "公司" }} · ${dept.ifBlank { "部门" }} · ${team.ifBlank { "团队" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // 原型 `.pchips`（:1224）里是两枚 `.tag`：`资料 v3` 是 `.tag.ok`（绿底）、
                        // `节点 …` 是 `.tag.plain`（灰底）。`.tag{border-radius:var(--r-chip)}` 且
                        // `--r-chip:999px`（:56）⇒ 全圆；代码原来用 RoundedCornerShape(4.dp)，
                        // 差集里的 `radius 只代码{4}` 就是这两处。
                        Row(horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm6)) {
                            TagPill(
                                text = stringResource(Res.string.r2_set_profile_rev_chip, settings.profileRev),
                                tone = TagTone.Ok,
                            )
                            TagPill(text = stringResource(Res.string.r2_set_profile_node_chip, state.selfNodeId.take(8)))
                        }
                    }
                }
            }

            ProfileFormCard {
                // 五个字段名右侧都挂着自己的协议字段名（原型 `.field label .fk`，`:322`：
                // nick / avatar / company / dept / team），昵称下面常驻一条
                // 「最多 32 个字符，不能为空。」（原型 `:3689` 的 `.hint`，不是只在非法时出现）。
                LabeledFieldWithKey(
                    label = stringResource(Res.string.set_nick),
                    fieldKey = "nick",
                    value = nick,
                    onValueChange = { nick = it },
                    supporting = stringResource(
                        if (nickInvalid) Res.string.set_invalid_nick else Res.string.r2_set_nick_hint,
                    ),
                    maxLength = RelaySettings.LIMIT_NICK,
                )

                FieldLabelRow(
                    label = stringResource(Res.string.r2_set_profile_avatar_label),
                    fieldKey = "avatar",
                    modifier = Modifier.padding(top = LocalAppSpacing.current.md10, bottom = LocalAppSpacing.current.xs),
                )

                AvatarPicker(
                    avatar = avatar,
                    nick = nick,
                    onAvatarChange = { avatar = it },
                    onOpenAvatarPage = onOpenAvatarPage,
                )

                // 原型 `.psep`（`:1205-1207`）：组织 = 11px/600 小节标题 + 右侧撑满的 hairline，
                // 上下 margin 16 / 12。
                OrgSeparator(stringResource(Res.string.r2_set_profile_org))

                // 原型 `.frow{display:grid;grid-template-columns:1fr 1fr;gap:0 12px}`（`:1210`）。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md),
                ) {
                    LabeledFieldWithKey(
                        label = stringResource(Res.string.set_company),
                        fieldKey = "company",
                        value = company,
                        onValueChange = { company = it },
                        modifier = Modifier.weight(1f),
                        maxLength = RelaySettings.LIMIT_COMPANY,
                    )
                    LabeledFieldWithKey(
                        label = stringResource(Res.string.set_dept),
                        fieldKey = "dept",
                        value = dept,
                        onValueChange = { dept = it },
                        modifier = Modifier.weight(1f),
                        maxLength = RelaySettings.LIMIT_DEPT,
                    )
                }
                LabeledFieldWithKey(
                    label = stringResource(Res.string.set_team),
                    fieldKey = "team",
                    value = team,
                    onValueChange = { team = it },
                    modifier = Modifier.fillMaxWidth(),
                    supporting = stringResource(Res.string.r2_set_profile_team_hint),
                    maxLength = RelaySettings.LIMIT_TEAM,
                )

                Button(
                    onClick = { onSaveProfile(nick, company, dept, team, avatar) },
                    enabled = !nickInvalid,
                    modifier = Modifier.fillMaxWidth().padding(top = LocalAppSpacing.current.sm),
                ) {
                    Text(stringResource(Res.string.set_save_profile))
                }
            }

            // 卡外页级 hint（原型 `padding:0 4px;margin-top:12px`）。
            Text(
                text = stringResource(Res.string.r2_set_profile_page_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = LocalAppSpacing.current.xs),
            )

        }
    }
}
