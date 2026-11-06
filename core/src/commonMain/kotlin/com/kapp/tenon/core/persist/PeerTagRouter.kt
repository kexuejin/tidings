// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.persist

import com.kapp.tenon.core.protocol.ChannelTags

/**
 * `targetTag` 路由的**确定性选择**（M7-T4，决策包 §3.7 第 3 条）：
 * 多节点同标签时选谁 —— **在线优先 + nodeId 字典序**，写死并在测试钉住。
 *
 * 纯函数：候选从调用方给（标签 store 的 `nodeIdsWithTag` + registry 的在线态），
 * 这里只裁 —— 判据可单测、可反向验证，不碰 store 与网络。
 */
object PeerTagRouter {

    /**
     * 从挂着 [tag] 的候选里选出承接节点。
     *
     * @param candidatesWithTag 标签 store 给的「挂着这个标签的 nodeId」全集；
     * @param isOnline registry 在线态查询（发起方本机的可达性缓存）。
     * @return 选中的 nodeId；没有候选 / 全部形状不合法 ⇒ null（调用方以
     *   `no-tag-match` 如实回执，不静默改投别家）。
     */
    fun resolve(
        tag: String,
        candidatesWithTag: List<String>,
        isOnline: (nodeId: String) -> Boolean,
    ): String? {
        if (!ChannelTags.isWellFormed(tag)) return null
        // nodeId 不是标签（形状谓词只管 tag），候选全集参与排序：在线优先，再按字典序钉确定性。
        return candidatesWithTag
            .sortedWith(
                compareByDescending<String> { isOnline(it) }.thenBy { it },
            )
            .firstOrNull()
    }
}
