// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 全局搜索「只看某一段」的判据单测。
 *
 * 真身：全局搜索原来只能三块一起搜 —— 想找「上周某人发的那个文件」只能靠关键词碰运气。
 * 会话内检索早就有类型筛选，这是把同一份能力补齐到全局。
 *
 * 判据抽成 [GlobalSearchScope.includes] 的理由：三段必须共用**同一条**规则。
 * 各写各的 `scope == All || scope == X` 就是三处可以各自漂的真相来源 ——
 * 漏改一处，用户点「只看文件」却仍看到联系人，而且没有任何报错。
 */
class GlobalSearchScopeTest {

    /** 「全部」包含每一段 —— 它是默认档，行为必须与改动前完全一致。 */
    @Test
    fun allIncludesEverySection() {
        assertTrue(GlobalSearchScope.All.includes(GlobalSearchScope.Contacts))
        assertTrue(GlobalSearchScope.All.includes(GlobalSearchScope.Chats))
        assertTrue(GlobalSearchScope.All.includes(GlobalSearchScope.Files))
    }

    /** 选了某一档 = 只要那一段，别的都不要。 */
    @Test
    fun aSpecificScopeExcludesEveryOtherSection() {
        assertTrue(GlobalSearchScope.Files.includes(GlobalSearchScope.Files))
        assertFalse(GlobalSearchScope.Files.includes(GlobalSearchScope.Contacts))
        assertFalse(GlobalSearchScope.Files.includes(GlobalSearchScope.Chats))
    }

    /** 联系人档同理 —— 三个档都要各自钉住，别只测一个。 */
    @Test
    fun contactsScopeOnlyIncludesContacts() {
        assertTrue(GlobalSearchScope.Contacts.includes(GlobalSearchScope.Contacts))
        assertFalse(GlobalSearchScope.Contacts.includes(GlobalSearchScope.Files))
    }
}
