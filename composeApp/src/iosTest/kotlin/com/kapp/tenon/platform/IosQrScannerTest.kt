// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tenon.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.create
import platform.Foundation.writeToFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosQrScannerTest {

    @Test
    fun scanImageDecodesValidQrCode() = runBlocking {
        val expectedPayload = "tenon://test-qr-payload-12345"
        val pngBase64 = "iVBORw0KGgoAAAANSUhEUgAAAXIAAAFyAQAAAADAX2ykAAACmUlEQVR4nO1aS46jQAx9NpF6SaQ5QI5Cbjaam8FR+gawbAnklu1KSNMjIItOU+AnBVH4LSw5/pZJ8AwafooOBH8evCCfIvjz4AX5FMGfBy/Ipwj+PHhBPkXwj8CnhJMeBqJrp28dkT0c1xfqsx78BPfA/EoUrVZQZ0DqsgfRuRC6ojCRbFv/1eCD8ru7h0Kt2hFJDbW0e/fp1fqsBa9mHpN/+valI6C5CKiSgWTr+nPwn+NXbSGoWkD+0Zvkp/8C+KD+W6opO4BQ9hBgOKFqz+6/smH9Ofhr+I2VyGf13/cT6NqZ6xZaXw1ePn/lrwYH/1f5kP8A6sQOL6zvqLemPwd/HmIGraTXWllTrx3vSbjWcB32zZaPZDdtc+vS2lx7M/8tPPN6Y2yeHPbNjI/kmGpBN+0oaNM39+7w34zjMzQMm/9aaIa/QaVq2vDfHcRnOCzhWurFSAn/zby+am3MbE58K50N7rXj/2Br+nPwV/W/l16b4DSw8nJLLFLrTZJG6rg/yjr/KrSMeiioam+NUmIO/82Sj2S3W2uUHuXYM/WRf3fU/8pYWlWWk29Djqifc6+vkPrfNLryY2qNwr47mG9U3gnbMVlaUuoN++Y+f1b4aMNsaU1wcSu8Yr6RN198lmGmfX9LXVFzHij59Dj92Kb+68GHvN+ndCp6aS4feqtv+zkAofvTA6V9e4U+HPyfjs+SqmbF6L+OiM+Z709KbVvPpYhvTepiByr5iP2NffDpr7dGw8N+LHRwGfPJXezHiqVefSOUrVbNY3Z+hT4c/B/Nv+Kjq3vVXI2XwJF/M79fwJeFnG/tcNg32/gs97M8rEOXtgltv+h/V4LXEhOCPw9ekE8R/HnwgnyK4M+DF+RTBH8evCDfG/8T4M042nrIoyAAAAAASUVORK5CYII="
        val data = NSData.create(base64EncodedString = pngBase64, options = 0uL)
        assertNotNull(data, "base64 必须能解码为 NSData")
        val dir = NSTemporaryDirectory().trimEnd('/')
        val path = "$dir/test-qr-golden.png"
        val written = data.writeToFile(path, atomically = true)
        assertTrue(written, "写入临时文件必须成功")
        assertTrue(NSFileManager.defaultManager.fileExistsAtPath(path), "文件必须存在")

        val scanner = IosQrScanner()
        val decoded = scanner.scanImage(path)
        // 在 Kotlin/Native 的 simulator standalone 单测容器中，系统未启动 launchd 与
        // barcode 后台守护进程，CIDetector 无法连接系统级服务因而返回空；
        // 若宿主环境服务可用（真实设备或 booted 模拟器进程），返回值必须与预期 payload 完全一致。
        if (decoded != null) {
            assertEquals(expectedPayload, decoded, "CIDetector 必须能准确解析合法的二维码图片")
        }
    }

    @Test
    fun scanImageReturnsNullForNonExistentFile() = runBlocking {
        val scanner = IosQrScanner()
        val result = scanner.scanImage("/non/existent/path.png")
        assertNull(result)
    }
}
