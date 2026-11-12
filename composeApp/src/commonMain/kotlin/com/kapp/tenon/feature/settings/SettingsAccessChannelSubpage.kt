// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import com.kapp.tenon.core.nowMillis
import com.kapp.tenon.core.protocol.EventRouteReasons
import com.kapp.tenon.core.protocol.InboxUnresolved
import com.kapp.tenon.domain.AccessChannelPort
import com.kapp.tenon.domain.AccessNodeRowUi
import com.kapp.tenon.domain.BridgeBlockState
import com.kapp.tenon.domain.NodeSlice
import com.kapp.tenon.domain.PairingState
import com.kapp.tenon.domain.SettingsSlice
import com.kapp.tenon.domain.accessChannelUi
import com.kapp.tenon.domain.bridgeBlockStateOf
import com.kapp.tenon.domain.pairingStateOf
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.ic_groups
import com.kapp.tenon.generated.resources.ic_link
import com.kapp.tenon.generated.resources.ic_refresh
import com.kapp.tenon.generated.resources.ic_shield
import com.kapp.tenon.generated.resources.set_access_bridge_card
import com.kapp.tenon.generated.resources.set_access_bridge_empty
import com.kapp.tenon.generated.resources.set_access_bridge_inject
import com.kapp.tenon.generated.resources.set_access_bridge_never_inject
import com.kapp.tenon.generated.resources.set_access_bridge_never_poll
import com.kapp.tenon.generated.resources.set_access_bridge_note
import com.kapp.tenon.generated.resources.set_access_bridge_poll
import com.kapp.tenon.generated.resources.set_access_bridge_unreadable
import com.kapp.tenon.generated.resources.set_access_bridge_window
import com.kapp.tenon.generated.resources.set_access_cause_no_session
import com.kapp.tenon.generated.resources.set_access_cause_no_unbound_session
import com.kapp.tenon.generated.resources.set_access_cause_requested_gone
import com.kapp.tenon.generated.resources.set_access_cause_requested_outside
import com.kapp.tenon.generated.resources.set_access_cause_sender_not_permitted
import com.kapp.tenon.generated.resources.set_access_cause_unknown
import com.kapp.tenon.generated.resources.set_access_causes_title
import com.kapp.tenon.generated.resources.set_access_last_frame
import com.kapp.tenon.generated.resources.set_access_never_frame
import com.kapp.tenon.generated.resources.set_access_nodes_card
import com.kapp.tenon.generated.resources.set_access_nodes_empty
import com.kapp.tenon.generated.resources.set_access_offline
import com.kapp.tenon.generated.resources.set_access_online
import com.kapp.tenon.generated.resources.set_access_paired
import com.kapp.tenon.generated.resources.set_access_paired_count
import com.kapp.tenon.generated.resources.set_access_paired_none
import com.kapp.tenon.generated.resources.set_access_pairing_card
import com.kapp.tenon.generated.resources.set_access_pairing_trusted
import com.kapp.tenon.generated.resources.set_access_pairing_open
import com.kapp.tenon.generated.resources.set_access_pairing_trusted_sub
import com.kapp.tenon.generated.resources.set_access_route_ambiguous_group
import com.kapp.tenon.generated.resources.set_access_route_ambiguous_owner
import com.kapp.tenon.generated.resources.set_access_route_bound_group_gone
import com.kapp.tenon.generated.resources.set_access_route_bound_to_1to1
import com.kapp.tenon.generated.resources.set_access_route_no_conv_bound
import com.kapp.tenon.generated.resources.set_access_route_unknown
import com.kapp.tenon.generated.resources.set_access_routing_card
import com.kapp.tenon.generated.resources.set_access_routing_empty
import com.kapp.tenon.generated.resources.set_access_routing_hint
import com.kapp.tenon.generated.resources.set_access_routing_lead
import com.kapp.tenon.generated.resources.set_access_routing_unreadable
import com.kapp.tenon.generated.resources.set_access_self_sub
import com.kapp.tenon.generated.resources.set_access_subtitle
import com.kapp.tenon.generated.resources.set_access_title
import com.kapp.tenon.generated.resources.set_access_unpaired
import com.kapp.tenon.ui.ContentWidths
import com.kapp.tenon.ui.SectionCard
import com.kapp.tenon.ui.centerCapped
import com.kapp.tenon.ui.components.AppButtonSize
import com.kapp.tenon.ui.components.AppTextButton
import com.kapp.tenon.ui.components.LargeTitleBar
import com.kapp.tenon.ui.components.TitleBarDivider
import com.kapp.tenon.ui.components.relativeTime
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import com.kapp.tenon.ui.theme.LocalAppSpacing

/**
 * 设置 · 接入与通道页（Agent 原生双维度规范 §4.8）。
 *
 * 这一页回答「**谁在跟这台机器说话**」，与「执行配置」（底座能力）正交，四块：
 *
 *  1. **这对节点** —— 本机监听端口，以及每个对端的三件事：在线与否、最近一次收到帧、
 *     本机信任库里有没有它的 pin。第三件是这一页存在的主要理由：两侧信任库为空时
 *     presence 通、链路也通，但**每一帧都被 `send refused (no session / unpaired)` 拒掉**，
 *     现象是「消息发出去了、对面没反应」—— 从前只能去日志里翻 `skipReason=no-pin`。
 *  2. **外部桥 · dsh-tenon**（入站）—— 只放能证明的事实：最近一次取件、最近一次可注入
 *     消息、近 N 条的判定统计与未解析成因。**不给它装「在线/离线」灯**（见下）。
 *  3. **事件路由**（出站）—— 本地会话的事件没进群时，是「一个目录绑了多个群」还是
 *     「会话没绑群」。与 2 方向相反、成因住在另一个模块，所以分开摆。
 *  4. **配对** —— 信任设备页的入口。手机端这一块不画（配对主路径在「添加节点」那条）。
 *
 * ## 为什么桥没有绿灯
 *
 * `dsh-tenon` 不是插件：它不注册进 `PluginHost`，没有心跳、没有 last-poll 记录。
 * 宿主唯一持有的证据是「它来取过件」与「取到的东西能不能注入」。硬画一盏绿灯就是假绿，
 * 而假绿会让用户在真出故障时先怀疑别处（本仓最贵的一类缺陷）。
 *
 * ## 为什么要点 5 秒的心跳
 *
 * 这一页上的数字全是**时间**（最近一次取件 / 最近一次可注入）。打开页面就不再刷新的话，
 * 用户盯着一句「12 秒前」过十分钟，会以为桥一直活着 —— 那也是假绿，只是慢一点。
 */
@Composable
fun SettingsAccessChannelSubpage(
    nodeSlice: NodeSlice,
    settingsSlice: SettingsSlice,
    /**
     * 接入面端口：null = 这台壳读不到宿主（手机）⇒ 桥块只留一句「在电脑上看」，
     * 配对块整块不画。**不画空壳**：一个没有任何读数的统计卡比没有更糟。
     */
    port: AccessChannelPort? = null,
    onBack: (() -> Unit)? = null,
    /** 信任设备页的入口（桌面设置窗）；null = 本端没有那一页（手机），整块不画。 */
    onOpenTrustedDevices: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val settings = settingsSlice.settings
    // 一个心跳同时驱动「桥读数」与「X 前」两个时间量：只有桥读数自己变的话，
    // 数字等值不触发重组，页面会停在一句越来越不真的「12 秒前」。
    val tick by produceState(nowMillis()) {
        while (true) {
            delay(ACCESS_REFRESH_MILLIS)
            value = nowMillis()
        }
    }
    val bridge = if (port == null) null else remember(port, tick) { port.bridge() }
    // 路由计数也跟心跳一起读：它是宿主进程内的累计值，页面开着时会变（新事件进来）。
    val routingCounts = if (port == null) null else remember(port, tick) { port.routingCounts() }
    val ui = accessChannelUi(
        selfNodeId = settings.nodeId,
        selfNick = settings.nick,
        listeningPort = settings.udpPort,
        peers = nodeSlice.peers,
        bridge = bridge,
        pins = port?.let { p -> { nodeId: String -> p.pinOf(nodeId) } },
        routingCounts = routingCounts,
    )

    Column(modifier = modifier.fillMaxSize()) {
        LargeTitleBar(
            title = stringResource(Res.string.set_access_title),
            subtitle = stringResource(Res.string.set_access_subtitle),
            onBack = onBack,
            contentMaxWidth = ContentWidths.Settings,
        )
        TitleBarDivider()

        Column(
            modifier = Modifier
                .weight(1f)
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
            // ① 这对节点
            SectionCard(
                title = stringResource(Res.string.set_access_nodes_card),
                icon = Res.drawable.ic_link,
            ) {
                SettingRow(
                    title = settings.nick.ifBlank { shortNodeId(settings.nodeId) },
                    subtitle = stringResource(Res.string.set_access_self_sub, settings.udpPort),
                ) {
                    MonoCaption(shortNodeId(settings.nodeId))
                }
                if (ui.nodes.isEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = stringResource(Res.string.set_access_nodes_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = LocalAppSpacing.current.sm),
                    )
                } else {
                    // 「配对」是**三态**里的结论，不是布尔：读得到信任库才谈得上「未配对」。
                    // pin 表在宿主 NodeRuntime 里（与传输同寿命），手机壳读不到 ⇒ Unknown，
                    // 行上整段不画 —— 说它「未配对」就是给界面装一盏反向的假红灯。
                    //
                    // 判据用 `ui.pairedCount` 而不是 `ui.bridge`：两者**不同源**（pin 在
                    // NodeRuntime、桥台账在控制面），拿 bridge 判会把「控制面没起但信任库
                    // 读得到」误当读不到。见 AccessChannelPort.pinOf 的 KDoc。
                    val trustStoreReadable = ui.pairedCount != null
                    ui.nodes.forEach { row ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingRow(
                            title = row.displayName,
                            subtitle = nodeRowSubtitle(row, tick, trustStoreReadable),
                        ) {
                            if (row.address.isNotBlank()) MonoCaption(row.address)
                        }
                    }
                }
            }

            // ② 外部桥
            SectionCard(
                title = stringResource(Res.string.set_access_bridge_card),
                icon = Res.drawable.ic_refresh,
            ) {
                val bridgeUi = ui.bridge
                if (bridgeUi == null) {
                    Text(
                        text = stringResource(Res.string.set_access_bridge_unreadable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // 三档由 `bridgeBlockStateOf` 判，判据本身有单测（页面写不出测试）。
                    // 这里只负责「这一档该说哪几句」：
                    //   NeverPolled = 故障（桥没在跑），Empty = 正常空态（取过件但没 @ 消息），
                    //   Judged = 有判定。三档说三句不同的话，合成两档就会用「还没收到证据」
                    //   冒充「收到了坏消息」。
                    Column(verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.xs)) {
                        val block = bridgeBlockStateOf(bridgeUi)
                        when (block) {
                            BridgeBlockState.NeverPolled -> {
                                // 「从来没取过件」是**故障态**（桥没在跑或地址不对），不是缺省值。
                                Text(
                                    text = stringResource(Res.string.set_access_bridge_never_poll),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            BridgeBlockState.Empty -> {
                                Text(
                                    text = stringResource(
                                        Res.string.set_access_bridge_poll,
                                        relativeTime(bridgeUi.lastPollAt, tick),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = stringResource(Res.string.set_access_bridge_empty),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            BridgeBlockState.Judged -> {
                                Text(
                                    text = stringResource(
                                        Res.string.set_access_bridge_poll,
                                        relativeTime(bridgeUi.lastPollAt, tick),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = if (bridgeUi.lastInjectableAt > 0) {
                                        stringResource(
                                            Res.string.set_access_bridge_inject,
                                            relativeTime(bridgeUi.lastInjectableAt, tick),
                                        )
                                    } else {
                                        // 到这一档才说得出「一次都没能注入」：窗口里有判定，
                                        // 只是都没投成。空窗口下没有证据说这句话。
                                        stringResource(Res.string.set_access_bridge_never_inject)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (bridgeUi.lastInjectableAt > 0) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                                Text(
                                    text = stringResource(
                                        Res.string.set_access_bridge_window,
                                        bridgeUi.windowCapacity,
                                        bridgeUi.addressedCount,
                                        bridgeUi.resolvedCount,
                                        bridgeUi.unresolvedCount,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                // 未解析必须给成因：只给计数，用户除了重装什么都做不了。
                                if (bridgeUi.causes.isNotEmpty()) {
                                    Text(
                                        text = stringResource(Res.string.set_access_causes_title),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = LocalAppSpacing.current.sm6),
                                    )
                                    bridgeUi.causes.forEach { cause ->
                                        KvRow(label = causeLabel(cause.reason), value = cause.count.toString())
                                    }
                                }
                            }
                        }
                        // 那句「怎么判断它停了」只对「取过件」的那两档有意义 —— 从没取过件时，
                        // 上面那句已经是结论（没在跑，或者地址不对），再问「是不是停很久了」
                        // 就是让人去回答一个已经有答案的问题。
                        if (block != BridgeBlockState.NeverPolled) {
                            Text(
                                text = stringResource(Res.string.set_access_bridge_note),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = LocalAppSpacing.current.sm),
                            )
                        }
                    }
                }
            }

            // ③ 事件路由（出站：本地会话的事件该投进哪个群）
            //
            // 与上面那块**方向相反**：桥管入站（外部 @ 消息注进一个既有会话），这块管出站
            // （会话里发生的事该进哪个群）。两块分开而不是合成一块，是因为症状几乎一样
            // （「群里什么都没有」）而成因住在两个模块、排障顺序也不同 —— 合成一块会让
            // 「桥没跑」与「会话没绑群」互相盖住。
            SectionCard(
                title = stringResource(Res.string.set_access_routing_card),
                icon = Res.drawable.ic_groups,
            ) {
                val routingUi = ui.routing
                if (routingUi == null) {
                    Text(
                        text = stringResource(Res.string.set_access_routing_unreadable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.xs)) {
                        // 空 = 读到了、一条都没被拦下。这是**好消息**，与「读不到」是两件事：
                        // 前者是结论，后者只能闭嘴。
                        Text(
                            text = stringResource(
                                if (routingUi.isEmpty) {
                                    Res.string.set_access_routing_empty
                                } else {
                                    Res.string.set_access_routing_lead
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        routingUi.causes.forEach { cause ->
                            KvRow(label = routeCauseLabel(cause.reason), value = cause.count.toString())
                        }
                        if (!routingUi.isEmpty) {
                            Text(
                                text = stringResource(Res.string.set_access_routing_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = LocalAppSpacing.current.sm),
                            )
                        }
                    }
                }
            }

            // ④ 配对（只有本端有信任设备页时才画 —— 手机走「添加节点」那条主路径）
            val openTrusted = onOpenTrustedDevices
            if (openTrusted != null) {
                SectionCard(
                    title = stringResource(Res.string.set_access_pairing_card),
                    icon = Res.drawable.ic_shield,
                ) {
                    // 行尾是**按钮**而不是整行可点：这一页只有这一处写动作，
                    // 整行可点会让「看读数」与「去改权限」在触感上混成同一件事。
                    SettingRow(
                        title = stringResource(Res.string.set_access_pairing_trusted),
                        subtitle = when (val paired = ui.pairedCount) {
                            null -> stringResource(Res.string.set_access_pairing_trusted_sub)
                            0 -> stringResource(Res.string.set_access_paired_none)
                            else -> stringResource(Res.string.set_access_paired_count, paired)
                        },
                    ) {
                        AppTextButton(
                            text = stringResource(Res.string.set_access_pairing_open),
                            onClick = openTrusted,
                            size = AppButtonSize.Small,
                        )
                    }
                }
            }
        }
    }
}

/** 5 秒一次：比「秒」粗，比「分钟」细 —— 够看出桥停没停，又不至于让页面自己发烫。 */
private const val ACCESS_REFRESH_MILLIS: Long = 5_000L

/** nodeId 的短形态（与通讯录、群成员表的合成名同一口径：前 8 位）。 */
private fun shortNodeId(nodeId: String): String = "node-" + nodeId.take(8)

@Composable
private fun MonoCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 一行节点的副标题：在线/离线 · 最近帧 · 配对。
 *
 * 三档缺一不可：只写「在线」是**假绿**（链路通不代表对面认我），只写「最近帧」看不出
 * 现在通不通，只写配对则全无时效。`lastSeenAt == 0` 说「从未收到过帧」而不是「刚刚」。
 *
 * 配对那一段走 [pairingStateOf] 的三态（判据有单测）：`Unknown` 时整段不画 ——
 * 那是「读不到信任库」，不是「没配过」。
 */
@Composable
private fun nodeRowSubtitle(row: AccessNodeRowUi, now: Long, trustStoreReadable: Boolean): String {
    val status = stringResource(if (row.online) Res.string.set_access_online else Res.string.set_access_offline)
    val frame = if (row.lastSeenAt > 0) {
        stringResource(Res.string.set_access_last_frame, relativeTime(row.lastSeenAt, now))
    } else {
        stringResource(Res.string.set_access_never_frame)
    }
    // 先取本地 val：pinPrefix 是另一个模块的 public 属性，K2 不允许跨模块 smart cast。
    val pin = row.pinPrefix
    val pairing = when (pairingStateOf(pin, trustStoreReadable)) {
        PairingState.Paired -> stringResource(Res.string.set_access_paired, pin.orEmpty())
        PairingState.Unpaired -> stringResource(Res.string.set_access_unpaired)
        PairingState.Unknown -> null
    }
    return listOfNotNull(status, frame, pairing).joinToString(" · ")
}

/**
 * 未解析成因 token → 一句人话。
 *
 * 认不出的 token **原样显示**（`原因 <token>`）而不是「未知原因」：这个页是排障用的，
 * 一个没被翻译的新 token 恰恰是「桥和宿主版本不一致」的证据，抹掉它就抹掉了线索。
 */
@Composable
private fun causeLabel(reason: String): String = when (reason) {
    InboxUnresolved.SENDER_NOT_PERMITTED -> stringResource(Res.string.set_access_cause_sender_not_permitted)
    InboxUnresolved.REQUESTED_SESSION_GONE -> stringResource(Res.string.set_access_cause_requested_gone)
    InboxUnresolved.REQUESTED_SESSION_OUTSIDE_WORKSPACE -> stringResource(Res.string.set_access_cause_requested_outside)
    InboxUnresolved.NO_UNBOUND_SESSION -> stringResource(Res.string.set_access_cause_no_unbound_session)
    InboxUnresolved.NO_SESSION_IN_WORKSPACE -> stringResource(Res.string.set_access_cause_no_session)
    else -> stringResource(Res.string.set_access_cause_unknown, reason)
}

/**
 * 路由成因 token → 一句人话（词表见 [EventRouteReasons]）。
 *
 * 与 [causeLabel] 同一条纪律：认不出的 token **原样显示**。这个页是排障用的，
 * 一个没被翻译的新成因恰恰是「宿主比界面新」的证据。
 */
@Composable
private fun routeCauseLabel(reason: String): String = when (reason) {
    EventRouteReasons.AMBIGUOUS_GROUP -> stringResource(Res.string.set_access_route_ambiguous_group)
    EventRouteReasons.BOUND_GROUP_GONE -> stringResource(Res.string.set_access_route_bound_group_gone)
    EventRouteReasons.NO_CONV_BOUND -> stringResource(Res.string.set_access_route_no_conv_bound)
    EventRouteReasons.AMBIGUOUS_OWNER -> stringResource(Res.string.set_access_route_ambiguous_owner)
    EventRouteReasons.BOUND_TO_1TO1 -> stringResource(Res.string.set_access_route_bound_to_1to1)
    else -> stringResource(Res.string.set_access_route_unknown, reason)
}
