// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * K3 ① 的进程级会合点。
 *
 * 这里钉三件事：通知会到每个订阅者、取消之后不再到、以及**一个订阅者抛异常不许拖垮别人**
 * （最后一条是真机上最容易变成"换网之后再也收不到通知"的那种坏法）。
 * [NetworkWatch] 是进程级单例，所以每个用例自己收尾（try/finally 里取消订阅、复位 idle）。
 */
class NetworkWatchTest {

    @Test
    fun notifyReachesEverySubscriber() {
        val seen = mutableListOf<String>()
        val a = NetworkWatch.onChanged { seen += "a" }
        val b = NetworkWatch.onChanged { seen += "b" }
        try {
            NetworkWatch.notifyChanged()
            assertEquals(listOf("a", "b"), seen)
        } finally {
            a(); b()
        }
    }

    @Test
    fun cancelStopsDelivery() {
        var hits = 0
        val cancel = NetworkWatch.onChanged { hits += 1 }
        NetworkWatch.notifyChanged()
        cancel()
        NetworkWatch.notifyChanged()
        assertEquals(1, hits, "取消之后不该再收到通知")
    }

    @Test
    fun oneThrowingSubscriberDoesNotBlockOthers() {
        val seen = mutableListOf<String>()
        val bad = NetworkWatch.onChanged { error("subscriber blew up") }
        val good = NetworkWatch.onChanged { seen += "good" }
        try {
            // 不抛出去 = 平台那条网络回调不会被我们打崩。
            NetworkWatch.notifyChanged()
            assertEquals(listOf("good"), seen)
        } finally {
            bad(); good()
        }
    }

    @Test
    fun idleProviderDefaultsToFalseAndCanBeInstalled() {
        NetworkWatch.installIdleProvider(null)
        assertFalse(NetworkWatch.isIdleNow(), "没人装 = 不降频，行为与 K3 之前一字不差")
        try {
            NetworkWatch.installIdleProvider { true }
            assertTrue(NetworkWatch.isIdleNow())
            NetworkWatch.installIdleProvider { false }
            assertFalse(NetworkWatch.isIdleNow())
        } finally {
            NetworkWatch.installIdleProvider(null)
        }
    }
}
