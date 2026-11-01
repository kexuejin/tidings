// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import com.kapp.tidings.domain.model.PeerUi

/**
 * 桌面 JVM 测试的共享预览夹具。
 *
 * `PreviewState.kt` 的假数据已整体搬进 androidApp 截图测试源集（历史遗留清理，
 * 见 design/legacy-cleanup-audit-2026-11-01.md B10）—— 桌面测试够不着了。
 * 这里按需补一个**测试源集自持**的最小版本，形状与截图夹具同源（同 nodeId/nick），
 * 只保证 `PeerUi` 形状完整；聚焦/空格等行为测试不读它的业务字段。
 */
internal fun zhouPeer(): PeerUi = PeerUi(
    nodeId = "peer-zhou",
    nick = "老周",
    company = "捎话",
    dept = "后端",
    team = "Tidings",
    avatar = 2,
    host = "studio.local",
    ip = "192.168.1.34",
    udpPort = 49215,
    platform = "Ubuntu 24.04",
    tcpPort = 49216,
    online = false,
    lastSeenAt = 1_700_000_000_000L,
)
