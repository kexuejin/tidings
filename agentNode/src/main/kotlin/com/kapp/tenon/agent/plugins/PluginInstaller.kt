// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.plugins

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * 插件安装结果。
 */
sealed interface PluginInstallResult {
    data class Success(val pluginId: String, val pluginDir: File, val manifest: PluginManifest) : PluginInstallResult
    data class Failure(val error: String, val message: String) : PluginInstallResult
}

/**
 * 插件安装与卸载器（P2-Step4 插件管理）：
 * 负责从 ZIP 包或目录中安全导入插件，实施 Zip Slip 防路径穿透校验，并放置到 plugins 目录。
 *
 * ## 安全红线
 * 1. 严格防御 Zip Slip 漏洞：解压文件规范化绝对路径必须受限于临时解压根目录内部；
 * 2. 严格校验 manifest.json：必须存在且 pluginId 必须符合字符集白名单；
 * 3. 目标目录强制以 pluginId 命名，避免目录名与清单身份不符导致的对账问题。
 */
object PluginInstaller {

    private const val MANIFEST_FILE_NAME = "manifest.json"

    fun installFromZip(zipFile: File, pluginsDir: File): PluginInstallResult {
        if (!zipFile.isFile) {
            return PluginInstallResult.Failure("file-not-found", "Zip file does not exist: ${zipFile.absolutePath}")
        }
        val tempExtractDir = File.createTempFile("tenon-plugin-extract-", "").let { f ->
            f.delete()
            f.mkdirs()
            f
        }

        try {
            // 1. 安全解压并拦截 Zip Slip
            safeExtractZip(zipFile, tempExtractDir)

            // 2. 定位并解析 manifest.json
            val sourcePluginDir = findPluginSourceDir(tempExtractDir)
                ?: return PluginInstallResult.Failure("missing-manifest", "No $MANIFEST_FILE_NAME found in plugin archive")

            val manifestFile = File(sourcePluginDir, MANIFEST_FILE_NAME)
            val manifest = try {
                PluginManifestJson.parse(manifestFile.readText())
            } catch (t: Throwable) {
                return PluginInstallResult.Failure("bad-manifest", "Failed to parse $MANIFEST_FILE_NAME: ${t.message}")
            }

            // 3. 清单与字符集校验
            val validationError = PluginRegistration.validate(manifest)
            if (validationError != null) {
                return PluginInstallResult.Failure(validationError.error, validationError.message)
            }

            // 4. 移动至目标插件目录 plugins/<pluginId>/
            if (!pluginsDir.exists()) {
                pluginsDir.mkdirs()
            }
            val targetDir = File(pluginsDir, manifest.pluginId)
            if (targetDir.exists()) {
                targetDir.deleteRecursively()
            }

            val moved = copyRecursively(sourcePluginDir, targetDir)
            if (!moved) {
                return PluginInstallResult.Failure("install-failed", "Failed to copy plugin into ${targetDir.absolutePath}")
            }

            return PluginInstallResult.Success(manifest.pluginId, targetDir, manifest)
        } catch (s: SecurityException) {
            return PluginInstallResult.Failure("security-violation", s.message ?: "Security violation")
        } catch (e: Exception) {
            return PluginInstallResult.Failure("io-error", e.message ?: "Extraction failed")
        } finally {
            tempExtractDir.deleteRecursively()
        }
    }

    fun uninstallPlugin(pluginId: String, pluginsDir: File): Boolean {
        if (!PluginRegistration.PLUGIN_ID_PATTERN.matches(pluginId)) {
            return false
        }
        val targetDir = File(pluginsDir, pluginId)
        if (!targetDir.isDirectory) {
            return false
        }
        return targetDir.deleteRecursively()
    }

    fun listInstalled(pluginsDir: File): List<PluginManifest> {
        if (!pluginsDir.isDirectory) return emptyList()
        return pluginsDir.listFiles { f -> f.isDirectory }
            ?.mapNotNull { dir ->
                val manifestFile = File(dir, MANIFEST_FILE_NAME)
                if (manifestFile.isFile) {
                    runCatching { PluginManifestJson.parse(manifestFile.readText()) }.getOrNull()
                } else null
            }
            ?.sortedBy { it.pluginId }
            ?: emptyList()
    }

    private fun safeExtractZip(zipFile: File, destinationDir: File) {
        val destCanonicalPath = destinationDir.canonicalPath
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val outFile = File(destinationDir, entry.name)
                val outCanonicalPath = outFile.canonicalPath

                // 防 Zip Slip 核心守卫
                if (!outCanonicalPath.startsWith(destCanonicalPath + File.separator) && outCanonicalPath != destCanonicalPath) {
                    throw SecurityException("Zip Slip path traversal blocked: ${entry.name}")
                }

                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun findPluginSourceDir(rootDir: File): File? {
        val directManifest = File(rootDir, MANIFEST_FILE_NAME)
        if (directManifest.isFile) return rootDir

        val subDirs = rootDir.listFiles { f -> f.isDirectory } ?: return null
        for (sub in subDirs) {
            val nestedManifest = File(sub, MANIFEST_FILE_NAME)
            if (nestedManifest.isFile) return sub
        }
        return null
    }

    private fun copyRecursively(src: File, dst: File): Boolean {
        return try {
            src.copyRecursively(dst, overwrite = true)
        } catch (_: IOException) {
            false
        }
    }
}
