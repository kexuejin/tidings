// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.testing

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

actual fun createTempDir(prefix: String): String =
    Files.createTempDirectory(prefix).toAbsolutePath().toString()

/**
 * 先深后浅删除，否则目录非空会删不掉。
 *
 * 整体包在 `runCatching` 里：清理失败只是临时目录残留，不该把一次本来成功的传输测试染红。
 */
actual fun removeRecursively(path: String) {
    runCatching {
        val root: Path = Path.of(path)
        if (!Files.exists(root)) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
