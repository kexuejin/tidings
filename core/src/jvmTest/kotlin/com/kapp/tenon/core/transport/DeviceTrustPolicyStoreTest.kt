// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.transport

import com.kapp.tenon.core.protocol.DeviceTrustPolicies
import com.kapp.tenon.core.protocol.Profile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 设备接入信任模式的**落盘与取值纪律**。
 *
 * 为什么值得一条测试：这一档一旦"读不出来就当宽松"，等于给局域网里任何人自动信任；
 * 而"认不出的取值静默归一"又会让界面切的档与判据读的档不一致（`byDevice ↔ bydevice`
 * 那次的同款）。两个坑都在这里钉住。
 */
class DeviceTrustPolicyStoreTest {

    private fun profile(nodeId: String) = Profile.mobile(
        nodeId = nodeId,
        nick = nodeId,
        host = "127.0.0.1",
        ver = "",
    )

    private fun factory(store: SecureStateStore) =
        LuciolaTransportFactory(
            profile = profile("host-node"),
            udpPort = 0,
            seedPersist = { "5e".repeat(32) },
            stateStore = store,
        )

    @Test
    fun defaultsToVerifyWhenNothingWasEverSaved() {
        val f = factory(InMemorySecureStateStore())
        assertEquals(
            DeviceTrustPolicies.VERIFY,
            f.deviceTrustPolicy(),
            "旧库没有这个字段时必须是最严档",
        )
    }

    @Test
    fun switchingPersistsAcrossInstances() {
        val store = InMemorySecureStateStore()
        val first = factory(store)
        assertTrue(first.setDeviceTrustPolicy(DeviceTrustPolicies.AUTO))
        assertEquals(DeviceTrustPolicies.AUTO, first.deviceTrustPolicy())

        // 换一个实例读同一份状态：档位跟着走，不是"只活在内存里"。
        assertEquals(DeviceTrustPolicies.AUTO, factory(store).deviceTrustPolicy())
    }

    @Test
    fun unknownValueIsRejectedInsteadOfSilentlyNormalized() {
        val f = factory(InMemorySecureStateStore())
        assertFalse(f.setDeviceTrustPolicy("trusted"), "认不出的取值不许写进去")
        assertFalse(f.setDeviceTrustPolicy("AUTO"), "取值是协议事实，大小写不通用")
        assertEquals(DeviceTrustPolicies.VERIFY, f.deviceTrustPolicy())
    }

    @Test
    fun garbageAlreadyOnDiskFallsBackToVerify() {
        val store = InMemorySecureStateStore()
        store.save("""{"devicePolicy":"whatever"}""")
        assertEquals(
            DeviceTrustPolicies.VERIFY,
            factory(store).deviceTrustPolicy(),
            "盘上写着认不出的档位 ⇒ 最严档，不是宽松档",
        )
    }
}
