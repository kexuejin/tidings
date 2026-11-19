// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.drivers.ModelCatalogEntry
import com.kapp.tenon.agent.drivers.ModelCatalogModel
import com.kapp.tenon.agent.drivers.ModelCatalogState
import com.kapp.tenon.domain.DriverModelCatalogStateUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 目录映射的纯函数测试：驱动侧结论 → 端口侧 UI 形状。
 *
 * 反向验证：把 [`KNOWN 但空` 降级成 UNKNOWN] 那条删掉，[knownButEmptyIsDowngraded] 必红 ——
 * 它守的是「空集不许冒充结论」（空 KNOWN 会让界面画出一行没有选项的目录）。
 */
class DriverModelCatalogMappingTest {

    private fun entry(
        key: String,
        state: ModelCatalogState,
        models: List<ModelCatalogModel> = emptyList(),
        note: String = "",
    ) = ModelCatalogEntry(key, state, models, note)

    @Test
    fun knownEntriesCarryIdAndLabel() {
        val ui = DriverModelCatalogMapping.toUi(
            mapOf(
                "agy" to entry(
                    "agy", ModelCatalogState.KNOWN,
                    listOf(ModelCatalogModel("m-1", "Model One"), ModelCatalogModel("m-2", "")),
                ),
            ),
        )
        val agy = ui.getValue("agy")
        assertEquals(DriverModelCatalogStateUi.KNOWN, agy.state)
        assertEquals(listOf("m-1", "m-2"), agy.models.map { it.id })
        // 没有可读名时回落成 id —— 不许画空标签。
        assertEquals("m-2", agy.models.last().label)
    }

    @Test
    fun knownButEmptyIsDowngraded() {
        val ui = DriverModelCatalogMapping.toUi(
            mapOf("agy" to entry("agy", ModelCatalogState.KNOWN, emptyList())),
        ).getValue("agy")
        assertEquals(
            DriverModelCatalogStateUi.UNKNOWN, ui.state,
            "空 KNOWN 会让界面画出没有选项的目录 ⇒ 必须降级成 UNKNOWN",
        )
        assertTrue(ui.models.isEmpty())
    }

    @Test
    fun unknownEntryKeepsNoteForDiagnostics() {
        val ui = DriverModelCatalogMapping.toUi(
            mapOf("dsh" to entry("dsh", ModelCatalogState.UNKNOWN, note = "no verified model-list probe")),
        ).getValue("dsh")
        assertEquals(DriverModelCatalogStateUi.UNKNOWN, ui.state)
        assertTrue(ui.note.isNotBlank())
    }

    @Test
    fun knownModelIdsSkipsUnknownAndEmpty() {
        val ui = DriverModelCatalogMapping.toUi(
            mapOf(
                "agy" to entry("agy", ModelCatalogState.KNOWN, listOf(ModelCatalogModel("m-1", "One"))),
                "dsh" to entry("dsh", ModelCatalogState.UNKNOWN),
            ),
        )
        val known = DriverModelCatalogMapping.knownModelIds(ui)
        assertEquals(setOf("m-1"), known["agy"])
        // 缺键 = 不知道 ⇒ 保存期放行；空集 = 知道但没有 ⇒ 会拒掉一切，两者不能混。
        assertNull(known["dsh"])
    }
}
