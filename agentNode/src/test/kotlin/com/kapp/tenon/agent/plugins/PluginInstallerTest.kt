// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.plugins

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PluginInstallerTest {

    private fun tempDir(): File {
        val dir = File.createTempFile("installer-test-", "").let { f ->
            f.delete()
            f.mkdirs()
            f
        }
        return dir
    }

    private fun createZip(zipFile: File, entries: Map<String, String>) {
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            for ((path, content) in entries) {
                zos.putNextEntry(ZipEntry(path))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
    }

    @Test
    fun installValidZipWithRootManifest() {
        val workDir = tempDir()
        val pluginsDir = File(workDir, "plugins")
        val zipFile = File(workDir, "my-tool.zip")
        try {
            val manifestJson = """
                {
                    "pluginId": "my-tool",
                    "name": "My Custom Tool",
                    "version": "1.0.0",
                    "type": "single",
                    "entrypoint": "python3 main.py",
                    "leader": {
                        "agentId": "my-tool",
                        "name": "My Tool",
                        "role": "worker"
                    }
                }
            """.trimIndent()

            createZip(zipFile, mapOf(
                "manifest.json" to manifestJson,
                "main.py" to "print('hello')",
            ))

            val result = PluginInstaller.installFromZip(zipFile, pluginsDir)
            val success = assertIs<PluginInstallResult.Success>(
                result,
                (result as? PluginInstallResult.Failure)?.let { "${it.error}: ${it.message}" },
            )
            assertEquals("my-tool", success.pluginId)

            val targetDir = File(pluginsDir, "my-tool")
            assertTrue(targetDir.isDirectory)
            assertTrue(File(targetDir, "manifest.json").isFile)
            assertTrue(File(targetDir, "main.py").isFile)
            assertEquals("print('hello')", File(targetDir, "main.py").readText())
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun installValidZipWithSubfolderManifest() {
        val workDir = tempDir()
        val pluginsDir = File(workDir, "plugins")
        val zipFile = File(workDir, "archive.zip")
        try {
            val manifestJson = """
                {
                    "pluginId": "nested-tool",
                    "name": "Nested Tool",
                    "version": "2.0.0",
                    "leader": {
                        "agentId": "nested-tool",
                        "name": "Nested Tool",
                        "role": "worker"
                    }
                }
            """.trimIndent()

            createZip(zipFile, mapOf(
                "nested-tool/manifest.json" to manifestJson,
                "nested-tool/run.sh" to "echo 42",
            ))

            val result = PluginInstaller.installFromZip(zipFile, pluginsDir)
            val success = assertIs<PluginInstallResult.Success>(
                result,
                (result as? PluginInstallResult.Failure)?.let { "${it.error}: ${it.message}" },
            )
            assertEquals("nested-tool", success.pluginId)

            val targetDir = File(pluginsDir, "nested-tool")
            assertTrue(targetDir.isDirectory)
            assertTrue(File(targetDir, "manifest.json").isFile)
            assertTrue(File(targetDir, "run.sh").isFile)
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun zipSlipAttackIsBlocked() {
        val workDir = tempDir()
        val pluginsDir = File(workDir, "plugins")
        val zipFile = File(workDir, "malicious.zip")
        try {
            createZip(zipFile, mapOf(
                "manifest.json" to """{"pluginId":"attack","name":"Attack"}""",
                "../../evil.sh" to "rm -rf /",
            ))

            val result = PluginInstaller.installFromZip(zipFile, pluginsDir)
            val failure = assertIs<PluginInstallResult.Failure>(result)
            assertEquals("security-violation", failure.error)
            assertTrue(failure.message.contains("Zip Slip"))

            // Verify nothing was written to plugins directory
            assertFalse(File(pluginsDir, "attack").exists())
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun invalidPluginIdInManifestIsRejected() {
        val workDir = tempDir()
        val pluginsDir = File(workDir, "plugins")
        val zipFile = File(workDir, "invalid-id.zip")
        try {
            createZip(zipFile, mapOf(
                "manifest.json" to """{"pluginId":"Invalid_ID!","name":"Bad ID"}""",
            ))

            val result = PluginInstaller.installFromZip(zipFile, pluginsDir)
            val failure = assertIs<PluginInstallResult.Failure>(result)
            assertEquals("bad-plugin-id", failure.error)
            assertFalse(File(pluginsDir, "Invalid_ID!").exists())
        } finally {
            workDir.deleteRecursively()
        }
    }

    /** 严格解析（C4）：zip 里声明未知键的清单按 bad-manifest 拒装，不静默入库。 */
    @Test
    fun manifestWithUnknownKeyFailsInstall() {
        val workDir = tempDir()
        val pluginsDir = File(workDir, "plugins")
        val zipFile = File(workDir, "unknown-key.zip")
        try {
            createZip(zipFile, mapOf(
                "manifest.json" to
                    """{"pluginId":"strict-plugin","name":"Strict","entrypoint":"python3 main.py","triggers":[]}""",
            ))

            val result = PluginInstaller.installFromZip(zipFile, pluginsDir)
            val failure = assertIs<PluginInstallResult.Failure>(result)
            assertEquals("bad-manifest", failure.error)
            assertTrue(failure.message.contains("triggers"), "失败信息应点名未知键：" + failure.message)
            assertFalse(File(pluginsDir, "strict-plugin").exists(), "拒装的插件不许落盘")
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun uninstallRemovesDirectory() {
        val workDir = tempDir()
        val pluginsDir = File(workDir, "plugins")
        try {
            val installed = File(pluginsDir, "to-remove")
            installed.mkdirs()
            File(installed, "manifest.json").writeText("{}")

            assertTrue(installed.isDirectory)
            val removed = PluginInstaller.uninstallPlugin("to-remove", pluginsDir)
            assertTrue(removed)
            assertFalse(installed.exists())

            // Second uninstall returns false
            assertFalse(PluginInstaller.uninstallPlugin("to-remove", pluginsDir))
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun listInstalledReturnsValidManifests() {
        val workDir = tempDir()
        val pluginsDir = File(workDir, "plugins")
        try {
            assertTrue(PluginInstaller.listInstalled(pluginsDir).isEmpty())

            val p1 = File(pluginsDir, "p1")
            p1.mkdirs()
            File(p1, "manifest.json").writeText("""
                {
                    "pluginId": "p1",
                    "name": "Plugin One",
                    "version": "1.0.0",
                    "type": "single",
                    "leader": {"agentId": "p1", "name": "P1", "role": "worker"}
                }
            """.trimIndent())

            val p2 = File(pluginsDir, "p2")
            p2.mkdirs()
            File(p2, "manifest.json").writeText("invalid json")

            val list = PluginInstaller.listInstalled(pluginsDir)
            assertEquals(1, list.size)
            assertEquals("p1", list[0].pluginId)
            assertEquals("Plugin One", list[0].name)
        } finally {
            workDir.deleteRecursively()
        }
    }
}
