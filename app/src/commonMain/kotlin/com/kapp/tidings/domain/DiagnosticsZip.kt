// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

/**
 * 最小 ZIP 写入器（STORED，不压缩）。
 *
 * ## 为什么自己写而不是用 `java.util.zip`
 *
 * 诊断包要在 **commonMain** 组装：手机壳（Android）、桌面（JVM）、iOS 共用同一份导出逻辑，
 * 而 `java.util.zip` 只存在于 JVM/Android 源集。为了一个 zip 把整条逻辑下沉成两份平台实现，
 * 正是 AGENTS.md §2.3「jvmSharedMain 是接缝，不要复制成两份」要避免的形态 —— 更别说 iOS
 * 还会变成第三份。STORED（不压缩）的 zip 格式只有三段固定结构，几十行足够，而且诊断包
 * 的 JSON 本来就压不动多少。
 *
 * ## 不压缩的代价值得
 *
 * - 没有 deflate 依赖、没有平台差异，产出可被系统解压器/SDK 的 ZipInputStream 直接读；
 * - 写包时不会因为压缩器分配大缓冲把手机内存顶上去（`messages.log` 是真机上十几 MB 的文件）；
 * - 宽度固定：本地头 + 中央目录 + EOCD，全部小端。
 *
 * 文件名统一按 UTF-8 编码并置通用标志位 bit 11（0x0800）—— 不置的话 Windows 解压器会按
 * CP437 解释非 ASCII 名。当前条目名都是 ASCII，但把规则写死比"现在恰好没中文"可靠。
 */
internal fun buildDiagnosticsZip(entries: List<DiagnosticsEntry>, generatedAtMillis: Long): ByteArray {
    val (dosTime, dosDate) = dosStamp(generatedAtMillis)
    val writer = ByteWriter(initialCapacity = 16 * 1024)
    data class CentralRecord(
        val name: ByteArray,
        val crc: Long,
        val size: Int,
        val offset: Long,
    )

    val central = ArrayList<CentralRecord>(entries.size)
    for (entry in entries) {
        val name = entry.path.encodeToByteArray()
        val crc = crc32(entry.bytes)
        val offset = writer.size.toLong()
        writer.u32(LOCAL_HEADER_SIGNATURE)
        writer.u16(VERSION_STORED)
        writer.u16(FLAG_UTF8)
        writer.u16(METHOD_STORED)
        writer.u16(dosTime)
        writer.u16(dosDate)
        writer.u32(crc)
        writer.u32(entry.bytes.size.toLong())
        writer.u32(entry.bytes.size.toLong())
        writer.u16(name.size)
        writer.u16(0) // extra length
        writer.bytes(name)
        writer.bytes(entry.bytes)
        central += CentralRecord(name, crc, entry.bytes.size, offset)
    }

    val centralOffset = writer.size.toLong()
    for (record in central) {
        writer.u32(CENTRAL_HEADER_SIGNATURE)
        writer.u16(VERSION_MADE_BY)
        writer.u16(VERSION_STORED)
        writer.u16(FLAG_UTF8)
        writer.u16(METHOD_STORED)
        writer.u16(dosTime)
        writer.u16(dosDate)
        writer.u32(record.crc)
        writer.u32(record.size.toLong())
        writer.u32(record.size.toLong())
        writer.u16(record.name.size)
        writer.u16(0) // extra length
        writer.u16(0) // comment length
        writer.u16(0) // disk number start
        writer.u16(0) // internal attributes
        writer.u32(0) // external attributes
        writer.u32(record.offset)
        writer.bytes(record.name)
    }
    val centralSize = writer.size - centralOffset

    writer.u32(EOCD_SIGNATURE)
    writer.u16(0) // disk number
    writer.u16(0) // disk with central directory
    writer.u16(central.size)
    writer.u16(central.size)
    writer.u32(centralSize)
    writer.u32(centralOffset)
    writer.u16(0) // comment length
    return writer.toByteArray()
}

/** MS-DOS 日期/时间（ZIP 头用）。年份低于 1980 时按 1980 夹住 —— 规范的起算年。 */
internal fun dosStamp(millis: Long): Pair<Int, Int> {
    val dayMillis = 86_400_000L
    var days = millis / dayMillis
    var rem = millis % dayMillis
    if (rem < 0) {
        rem += dayMillis
        days -= 1
    }
    val (year, month, day) = civilFromDays(days)
    val clampedYear = year.coerceIn(1980, 2107)
    val time = ((rem / 3_600_000L).toInt() shl 11) or
        (((rem / 60_000L) % 60L).toInt() shl 5) or
        ((rem / 1_000L) % 60L).toInt().shr(1)
    val date = ((clampedYear - 1980) shl 9) or (month shl 5) or day
    return time to date
}

/**
 * 标准 CRC-32（IEEE 802.3，多项式 0xEDB88320 反射形式）。
 *
 * 逐位算而不是查表：诊断包每次只算几 MB，省一张 1KB 表；同样的算法在三个平台上是同一份代码。
 */
internal fun crc32(bytes: ByteArray): Long {
    var crc = 0xFFFFFFFFL
    for (byte in bytes) {
        crc = crc xor (byte.toLong() and 0xFF)
        repeat(8) {
            crc = if ((crc and 1L) != 0L) (crc ushr 1) xor 0xEDB88320L else crc ushr 1
        }
    }
    return (crc xor 0xFFFFFFFFL) and 0xFFFFFFFFL
}

/** 可增长的字节缓冲（commonMain 没有 ByteArrayOutputStream）。 */
private class ByteWriter(initialCapacity: Int) {
    private var buffer = ByteArray(initialCapacity)
    var size: Int = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra <= buffer.size) return
        var next = buffer.size * 2
        while (next < size + extra) next *= 2
        buffer = buffer.copyOf(next)
    }

    fun u16(value: Int) {
        ensure(2)
        buffer[size++] = (value and 0xFF).toByte()
        buffer[size++] = ((value ushr 8) and 0xFF).toByte()
    }

    fun u32(value: Long) {
        ensure(4)
        buffer[size++] = (value and 0xFF).toByte()
        buffer[size++] = ((value ushr 8) and 0xFF).toByte()
        buffer[size++] = ((value ushr 16) and 0xFF).toByte()
        buffer[size++] = ((value ushr 24) and 0xFF).toByte()
    }

    fun bytes(value: ByteArray) {
        ensure(value.size)
        value.copyInto(buffer, size)
        size += value.size
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)
}

// 小端写出的固定签名。注释里写十进制便于对照 ZIP 规范。
private const val LOCAL_HEADER_SIGNATURE = 0x04034b50L
private const val CENTRAL_HEADER_SIGNATURE = 0x02014b50L
private const val EOCD_SIGNATURE = 0x06054b50L
private const val VERSION_STORED = 10
private const val VERSION_MADE_BY = 20
private const val FLAG_UTF8 = 0x0800
private const val METHOD_STORED = 0
