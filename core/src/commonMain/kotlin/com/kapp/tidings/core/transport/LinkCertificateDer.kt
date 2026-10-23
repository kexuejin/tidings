// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

/**
 * 自签 X.509 证书的**最小 DER 生成器**（纯 Kotlin、零平台依赖）。
 *
 * 为什么要有它（task-26）：iOS 上没有任何公开 API 能"现造一张证书"，只能自己写 DER；
 * 而 JVM 侧 task-25 已经有一份（在 jvmShared 里用 java.security 签名）。把**ASN.1 那一半**
 * 抽到 commonMain，两端共用同一份字节布局 —— 否则 JVM 与 iOS 会各写一套，迟早对不上，
 * 而对不上的直接后果是 **pin 不同**（pin = SPKI，证书里的公钥必须是同一把）。
 *
 * 平台差异全部由调用方注入，这个对象自己不碰任何平台 API：
 *  · [spki]：公钥的 SubjectPublicKeyInfo（DER）；
 *  · [serial]：证书序列号（16 字节随机，由平台提供随机源）；
 *  · [sanIps]：SAN 里的 iPAddress（4/16 字节，由平台解析地址）；
 *  · [sign]：对 TBS 签名（JVM = Signature("SHA256withECDSA")，iOS = SecKeyCreateSignature）。
 */
internal object LinkCertificateDer {

    /** ecdsa-with-SHA256（secp256r1 的标准签名算法，OID 1.2.840.10045.4.3.2）。 */
    private val ECDSA_WITH_SHA256 = hex("06082a8648ce3d040302")

    /** serverAuth（1.3.6.1.5.5.7.3.1）—— 长连接是"我们去连对端"，按服务端用途签发。 */
    private val EXT_KEY_USAGE_SERVER_AUTH = hex("06082b06010505070301")

    /** 自签证书的 CN。**不放 nodeId**：身份靠 pin（SPKI），不靠证书里的名字（task-13 裁决）。 */
    private const val COMMON_NAME = "Tidings"

    /**
     * 把地址字符串解析成 SAN 用的字节（IPv4 = 4 字节，IPv6 = 16 字节）；不是 IP 就返回 null。
     *
     * 为什么两端必须共用它：SAN 里的 iPAddress 是**证书字节的一部分**，而 pin = sha256(SPKI)。
     * 同一台机器在 JVM 与 iOS 上必须解析出**逐字节相同**的地址字节，否则两端对同一份证书
     * 算出不同的指纹 —— 这类漂移在真机上表现为"配过对的设备突然连不上"，极难查。
     *
     * 纯 Kotlin 实现（不用 InetAddress / inet_pton）：IPv4 点分十进制 + IPv6（含 `::` 压缩、
     * 大小写、尾部 `%scope`）。主机名返回 null，由调用方决定要不要走平台解析。
     */
    fun ipBytes(host: String): ByteArray? {
        val clean = host.trim().substringBefore('%').removeSurrounding("[", "]")
        if (clean.isEmpty()) return null
        if ('.' in clean && ':' !in clean) return ipv4Bytes(clean)
        if (':' in clean) return ipv6Bytes(clean)
        return null
    }

    private fun ipv4Bytes(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for (index in 0 until 4) {
            val text = parts[index]
            if (text.isEmpty() || text.length > 3 || !text.all { it in '0'..'9' }) return null
            if (text.length > 1 && text[0] == '0') return null
            val value = text.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            out[index] = value.toByte()
        }
        return out
    }

    private fun ipv6Bytes(host: String): ByteArray? {
        if (host.count { it == ':' } > 8) return null
        val doubleColon = host.indexOf("::")
        if (doubleColon >= 0 && host.indexOf("::", doubleColon + 1) >= 0) return null
        val head = if (doubleColon >= 0) host.substring(0, doubleColon) else host
        val tail = if (doubleColon >= 0) host.substring(doubleColon + 2) else ""
        val headGroups = if (head.isEmpty()) emptyList() else head.split(':')
        val tailGroups = if (tail.isEmpty()) emptyList() else tail.split(':')
        fun parse(groups: List<String>): List<Int>? {
            val out = ArrayList<Int>(groups.size)
            for (group in groups) {
                if (group.isEmpty() || group.length > 4) return null
                val value = group.toIntOrNull(16) ?: return null
                out += value
            }
            return out
        }
        val headValues = parse(headGroups) ?: return null
        val tailValues = parse(tailGroups) ?: return null
        val missing = 8 - headValues.size - tailValues.size
        if (doubleColon < 0) {
            if (missing != 0) return null
        } else if (missing < 1) {
            return null
        }
        val groups = headValues + List(maxOf(missing, 0)) { 0 } + tailValues
        if (groups.size != 8) return null
        val out = ByteArray(16)
        for (index in 0 until 8) {
            out[index * 2] = ((groups[index] shr 8) and 0xFF).toByte()
            out[index * 2 + 1] = (groups[index] and 0xFF).toByte()
        }
        return out
    }
    /**
     * EC P-256 的 SubjectPublicKeyInfo（SPKI），由 **X9.63 未压缩点**（`0x04 || X || Y`）拼出。
     *
     * 为什么需要它（task-26 ① 的隐藏地雷）：iOS 的 `SecKeyCopyExternalRepresentation` 只给
     * 这个**原始点**，而 JVM 的 `PublicKey.encoded` 直接就是 SPKI。pin = sha256(SPKI) ——
     * 两端必须拼出**逐字节相同**的 SPKI，否则同一把密钥在两端算出两个不同的 pin，
     * 后果是"配过对的设备突然连不上"，而且在真机上极难查。
     *
     * 结构固定（RFC 5480）：SEQUENCE { SEQUENCE { ecPublicKey, prime256v1 }, BIT STRING(0x00 || point) }。
     */
    fun ecP256SpkiFromRawPoint(rawPoint: ByteArray): ByteArray {
        require(rawPoint.size == 65 && rawPoint[0] == 0x04.toByte()) {
            "只接受未压缩的 P-256 点（65 字节、以 0x04 开头），实际 ${rawPoint.size} 字节"
        }
        return seq(
            seq(
                // OID 1.2.840.10045.2.1 —— ecPublicKey
                hex("06072a8648ce3d0201"),
                // OID 1.2.840.10045.3.1.7 —— prime256v1 (secp256r1)
                hex("06082a8648ce3d030107"),
            ),
            // BIT STRING：第一个字节是"未使用位数"，恒为 0
            der(0x03, concat(byteArrayOf(0), rawPoint)),
        )
    }

    fun selfSigned(
        spki: ByteArray,
        serial: ByteArray,
        sanIps: List<ByteArray>,
        notBeforeMillis: Long,
        notAfterMillis: Long,
        sign: (ByteArray) -> ByteArray,
    ): ByteArray {
        require(serial.isNotEmpty()) { "序列号不能为空" }
        val algorithm = seq(ECDSA_WITH_SHA256)
        val name = seq(der(0x31, seq(hex("0603550403"), der(0x0c, COMMON_NAME.encodeToByteArray()))))

        fun extension(oid: String, value: ByteArray, critical: Boolean): ByteArray = seq(
            hex(oid),
            *(if (critical) arrayOf(hex("0101ff")) else emptyArray()),
            der(0x04, value),
        )

        val san = seq(*sanIps.map { der(0x87, it) }.toTypedArray())
        val extensions = der(
            0xa3,
            seq(
                // basicConstraints: CA = FALSE（critical，与 TS/浏览器对叶证书的期望一致）
                extension("0603551d13", seq(), critical = true),
                // keyUsage: digitalSignature（critical）
                extension("0603551d0f", hex("03020780"), critical = true),
                // extKeyUsage: serverAuth
                extension("0603551d25", seq(EXT_KEY_USAGE_SERVER_AUTH), critical = false),
                // subjectAltName: 本机全部地址 —— 对端按地址连过来时证书必须匹配
                extension("0603551d11", san, critical = false),
            ),
        )

        // 序列号最高位必须为 0，否则会被当成负数（DER INTEGER 的符号位）。
        val positiveSerial = serial.copyOf().also { it[0] = (it[0].toInt() and 0x7F).toByte() }
        val serialDer = der(0x02, positiveSerial)

        val tbs = seq(
            // version v3（显式 [0] EXPLICIT INTEGER 2）
            der(0xa0, hex("020102")),
            serialDer,
            algorithm,
            name,
            seq(asn1Time(notBeforeMillis), asn1Time(notAfterMillis)),
            name,
            spki,
            extensions,
        )
        val signature = sign(tbs)
        return seq(tbs, algorithm, der(0x03, concat(byteArrayOf(0), signature)))
    }

    /**
     * epoch 毫秒 → ASN.1 时间。
     *
     * 2050 年之前用 UTCTime（YYMMDDHHMMSSZ，两位年），之后用 GeneralizedTime（四位年）——
     * 这是 X.509 的硬性规定，写错的话部分实现会直接拒绝解析。
     *
     * 自己算年月日而不是用平台日期库：commonMain 没有 `java.time`，而 `String.format`
     * 也是 JVM 专有。用 Hinnant 的 civil_from_days 纯整数算法，两端结果逐字节一致。
     */
    private fun asn1Time(epochMillis: Long): ByteArray {
        val days = floorDiv(epochMillis, 86_400_000L)
        val millisOfDay = epochMillis - days * 86_400_000L
        val secondsOfDay = millisOfDay / 1_000L
        val (year, month, day) = civilFromDays(days)
        val hour = secondsOfDay / 3_600L
        val minute = (secondsOfDay / 60L) % 60L
        val second = secondsOfDay % 60L
        val digits = pad(year, 4) + pad(month, 2) + pad(day, 2) +
            pad(hour, 2) + pad(minute, 2) + pad(second, 2)
        return if (year < 2050) {
            der(0x17, (digits.substring(2) + "Z").encodeToByteArray())
        } else {
            der(0x18, (digits + "Z").encodeToByteArray())
        }
    }

    private fun pad(value: Long, width: Int): String = value.toString().padStart(width, '0')

    /** 与 `Math.floorDiv` 同义（commonMain 没有它）。 */
    private fun floorDiv(a: Long, b: Long): Long {
        val q = a / b
        return if (a % b != 0L && (a xor b) < 0) q - 1 else q
    }

    /** Hinnant 的 civil_from_days：1970-01-01 起的天数 → (年, 月, 日)。 */
    private fun civilFromDays(days: Long): Triple<Long, Long, Long> {
        val z = days + 719_468L
        val era = (if (z >= 0) z else z - 146_096L) / 146_097L
        val doe = z - era * 146_097L
        val yoe = (doe - doe / 1_460L + doe / 36_524L - doe / 146_096L) / 365L
        val y = yoe + era * 400L
        val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
        val mp = (5L * doy + 2L) / 153L
        val d = doy - (153L * mp + 2L) / 5L + 1L
        val m = mp + (if (mp < 10L) 3L else -9L)
        return Triple(y + (if (m <= 2L) 1L else 0L), m, d)
    }

    private fun der(tag: Int, value: ByteArray): ByteArray {
        val out = ArrayList<Byte>(value.size + 6)
        out.add(tag.toByte())
        val length = value.size
        if (length < 128) {
            out.add(length.toByte())
        } else {
            var n = length
            val stack = ArrayList<Byte>()
            while (n > 0) {
                stack.add((n and 0xFF).toByte())
                n = n ushr 8
            }
            out.add((0x80 or stack.size).toByte())
            for (i in stack.indices.reversed()) out.add(stack[i])
        }
        for (b in value) out.add(b)
        return out.toByteArray()
    }

    private fun seq(vararg parts: ByteArray): ByteArray = der(0x30, concat(*parts))

    private fun concat(vararg parts: ByteArray): ByteArray {
        val size = parts.sumOf { it.size }
        val out = ByteArray(size)
        var offset = 0
        for (part in parts) {
            part.copyInto(out, offset)
            offset += part.size
        }
        return out
    }

    private fun hex(text: String): ByteArray =
        ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
