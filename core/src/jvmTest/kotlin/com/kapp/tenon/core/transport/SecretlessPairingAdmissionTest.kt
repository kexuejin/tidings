// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.transport

import com.kapp.tenon.core.protocol.DeviceTrustPolicies
import com.kapp.tenon.core.protocol.Profile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「无密钥入站配对」这条路的**宿主侧决策**（不建网络，只问准入对象）。
 *
 * 为什么值得单独钉：这条路的默认值必须是「不存在」（`acceptsSecretlessRequests() == false`），
 * 而一旦打开，四个入口（自动信任 / 问人 / 白名单 / 批准后重试）各自的效果必须与界面上的档位
 * 一一对应。判据错一格的表现是**静默的**：局域网里陌生人直接进信任库，或者用户批了却永远连不上。
 */
class SecretlessPairingAdmissionTest {

    private val peerKey = ByteArray(32) { (it + 1).toByte() }
    private val peerNode = "peer-node-1"

    private fun newFactory(store: SecureStateStore) = LuciolaTransportFactory(
        profile = Profile.mobile(nodeId = "host-node", nick = "host", host = "127.0.0.1", ver = ""),
        udpPort = 0,
        seedPersist = { "5e".repeat(32) },
        stateStore = store,
    )

    /** 先在一份状态里落好档位，再用同一个 store 起一个**新**实例（档位要能从盘上读回来）。 */
    private fun factory(policy: String? = null): LuciolaTransportFactory {
        val store = InMemorySecureStateStore()
        if (policy != null) {
            assertTrue(newFactory(store).setDeviceTrustPolicy(policy))
        }
        return newFactory(store)
    }

    @Test
    fun defaultPolicyAsksTheHumanAndNeverTrustsSilently() {
        val admission = factory().pairingAdmission()
        assertTrue(admission.acceptsSecretlessRequests(), "verify 档要能收到申请，否则永远问不了人")
        assertEquals(
            SecretlessPairingVerdict.AskHuman,
            admission.decideSecretless(peerNode, peerKey, "192.168.2.22:27878"),
        )
    }

    @Test
    fun whitelistRefusesAndDoesNotEvenOpenTheWindow() {
        val f = factory(DeviceTrustPolicies.WHITELIST)
        val admission = f.pairingAdmission()
        assertFalse(admission.acceptsSecretlessRequests(), "白名单档连申请都不收")
        assertTrue(
            admission.decideSecretless(peerNode, peerKey, "192.168.2.22:27878") is SecretlessPairingVerdict.Refuse,
        )
    }

    @Test
    fun autoTrustsOnlyPrivateAddresses() {
        val admission = factory(DeviceTrustPolicies.AUTO).pairingAdmission()
        assertEquals(
            SecretlessPairingVerdict.Trust,
            admission.decideSecretless(peerNode, peerKey, "192.168.2.22:27878"),
        )
        assertEquals(
            SecretlessPairingVerdict.AskHuman,
            admission.decideSecretless(peerNode, peerKey, "8.8.8.8:27878"),
            "跨网段不能自动信任",
        )
    }

    @Test
    fun approvalMakesTheNextRetrySucceed() {
        val f = factory()
        val admission = f.pairingAdmission()

        assertFalse(f.approveSecretlessPairing(peerNode), "没申请过的东西不能批")
        admission.onPairingRequested(peerNode, peerKey, "Pixel 6", "192.168.2.22:27878")
        assertEquals(listOf(peerNode), f.pendingPairingRequests().keys.toList())

        assertTrue(f.approveSecretlessPairing(peerNode))
        assertTrue(f.pendingPairingRequests().isEmpty(), "决定已做出，不该还挂在待处理里")
        assertEquals(
            SecretlessPairingVerdict.Trust,
            admission.decideSecretless(peerNode, peerKey, "8.8.8.8:27878"),
            "批过之后，对端下一次重试（哪怕跨网段）必须放行 —— 否则人点了也白点",
        )
    }

    @Test
    fun rejectionDropsTheRequestWithoutTrusting() {
        val f = factory()
        val admission = f.pairingAdmission()
        admission.onPairingRequested(peerNode, peerKey, "Pixel 6", "192.168.2.22:27878")
        assertTrue(f.rejectSecretlessPairing(peerNode))
        assertTrue(f.pendingPairingRequests().isEmpty())
        assertEquals(
            SecretlessPairingVerdict.AskHuman,
            admission.decideSecretless(peerNode, peerKey, "192.168.2.22:27878"),
            "拒了之后仍然是「问人」，不是「信了」也不是「永久拉黑」",
        )
    }

    @Test
    fun alreadyTrustedPeerSkipsTheWholeFlow() {
        val f = factory(DeviceTrustPolicies.WHITELIST)
        val fp = f.fingerprint
        // 用本机公钥当"已信任对端"的替身：判据只看指纹是否在账上。
        f.trustDevice(nodeId = peerNode, name = "Pixel 6", publicKey = f.publicKey, full = true)
        assertTrue(f.isTrustedFingerprint(fp))
        assertEquals(
            SecretlessPairingVerdict.Trust,
            f.pairingAdmission().decideSecretless(peerNode, f.publicKey, "192.168.2.22:27878"),
            "已信任的设备重连不该再走审批（连白名单档也是放行）",
        )
    }
}
