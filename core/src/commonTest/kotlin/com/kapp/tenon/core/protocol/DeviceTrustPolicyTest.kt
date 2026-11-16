// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

import com.kapp.tenon.core.transport.SecretlessPairingVerdict
import com.kapp.tenon.core.transport.decideSecretlessPairing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 设备接入三档的**判据表**。
 *
 * 这张表存在的理由：判据一旦只活在传输层，界面切了档、后端读成别的值这种静默就没人挡。
 * 这里把「三档 × 四种前置状态」逐格钉死，改任何一格都要在这里留下痕迹。
 */
class DeviceTrustPolicyTest {

    // ---------------------------------------------------------------- 归一

    @Test
    fun unknownOrMissingPolicyFallsBackToVerify() {
        assertEquals(DeviceTrustPolicies.VERIFY, DeviceTrustPolicies.normalize(null))
        assertEquals(DeviceTrustPolicies.VERIFY, DeviceTrustPolicies.normalize(""))
        assertEquals(DeviceTrustPolicies.VERIFY, DeviceTrustPolicies.normalize("   "))
        assertEquals(DeviceTrustPolicies.VERIFY, DeviceTrustPolicies.normalize("trusted"))
        // 大小写不同**不算**同一个值：协议取值就是这三个小写串。
        assertEquals(DeviceTrustPolicies.VERIFY, DeviceTrustPolicies.normalize("AUTO"))
        assertEquals(DeviceTrustPolicies.VERIFY, DeviceTrustPolicies.normalize("Auto"))
    }

    @Test
    fun knownPoliciesRoundTrip() {
        for (value in DeviceTrustPolicies.VALUES) {
            assertEquals(value, DeviceTrustPolicies.normalize(value))
            assertEquals(value, DeviceTrustPolicies.normalize("  $value  "))
        }
        assertEquals(
            listOf("verify", "auto", "whitelist"),
            DeviceTrustPolicies.VALUES,
            "三档的取值是协议事实，不许在别处再写一份",
        )
    }

    // ---------------------------------------------------------------- 判据

    @Test
    fun alreadyTrustedPeerNeverAsksAgain() {
        for (policy in DeviceTrustPolicies.VALUES + listOf(null, "garbage")) {
            assertEquals(
                SecretlessPairingVerdict.Trust,
                decideSecretlessPairing(policy, sameLan = false, alreadyTrusted = true, preApproved = false),
                "重连不该再走一遍审批（policy=$policy）",
            )
        }
    }

    @Test
    fun approvalSticksEvenWhenPolicySaysAskAgain() {
        // 手机会在几秒内重试；人点得慢一点时，这次批准必须对**后续重试**同样生效。
        assertEquals(
            SecretlessPairingVerdict.Trust,
            decideSecretlessPairing("verify", sameLan = false, alreadyTrusted = false, preApproved = true),
        )
    }

    @Test
    fun whitelistRefusesUnknownPeersWithoutAsking() {
        val verdict = decideSecretlessPairing("whitelist", sameLan = true, alreadyTrusted = false, preApproved = false)
        assertTrue(verdict is SecretlessPairingVerdict.Refuse, "白名单下不该弹审批：$verdict")
        assertEquals("policy-whitelist", (verdict as SecretlessPairingVerdict.Refuse).reason)
    }

    @Test
    fun autoTrustsOnlyOnTheSameLan() {
        assertEquals(
            SecretlessPairingVerdict.Trust,
            decideSecretlessPairing("auto", sameLan = true, alreadyTrusted = false, preApproved = false),
        )
        assertEquals(
            SecretlessPairingVerdict.AskHuman,
            decideSecretlessPairing("auto", sameLan = false, alreadyTrusted = false, preApproved = false),
            "跨网段不能自动信任：那正是「谁先连进来谁可信」的真实风险",
        )
    }

    @Test
    fun verifyAsksEvenOnTheSameLan() {
        assertEquals(
            SecretlessPairingVerdict.AskHuman,
            decideSecretlessPairing("verify", sameLan = true, alreadyTrusted = false, preApproved = false),
        )
        // 认不出的取值与「没配」等价，都必须落到最严档。
        assertEquals(
            SecretlessPairingVerdict.AskHuman,
            decideSecretlessPairing(null, sameLan = true, alreadyTrusted = false, preApproved = false),
        )
    }
}
