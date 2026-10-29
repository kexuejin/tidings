// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent.plugins

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * 插件注册的 token 门闸（方案 §2.3）：`/v1/plugin/register` **强制**校验，
 * 没有它，本机任意进程都能自我入驻并领到任务与工作区授权。
 *
 * ## token 的两个来源（按优先级）
 *
 * 1. **环境变量 `TIDINGS_PLUGIN_TOKEN`**（托管模式）：宿主拉起插件子进程时注入，
 *    宿主自己读同一个变量比对 —— 两边天然一致。
 * 2. **数据目录下的受控文件 `plugins/plugin-token`**（守护进程模式）：宿主保证它存在
 *    （[ensureTokenFile]，缺失时生成 64 位十六进制并收紧到 0600），独立启动的插件进程
 *    读它完成注册；插件也可以用自己的配置携带 token —— 那个值同样要等于这个文件里的。
 *
 * ## fail-closed
 *
 * 两个来源都拿不到 token 时 [expectedToken] 为 null，[matches] 对一切请求返回 false、
 * 控制面回 503 —— **端点整体关闸**，绝不退化成「没配就放行」。桌面端正常路径总是
 * 配好的（[ensureTokenFile] 在启动时被调用）；关闸是给「手工起控制面又没配 token」
 * 这类状态的答案，不是日常路径。
 *
 * 本机威胁模型的完整叙述在 SECURITY.md：回环 + 同用户进程本就是同一个信任域，
 * 这个门拦的是「无 token 的进程不能**自我入驻**」，不是「同用户的有意攻击者」。
 */
class PluginTokenGate private constructor(
    private val envToken: String?,
    private val tokenFile: File?,
) {

    /** 期望的 token；null = 未配置（fail-closed，见类 KDoc）。env 优先，每次现读文件。 */
    fun expectedToken(): String? {
        envToken?.let { return it }
        val file = tokenFile ?: return null
        if (!file.isFile) return null
        return runCatching { file.readText().trim().takeIf { it.isNotEmpty() } }.getOrNull()
    }

    /** 端点是否有可用的期望 token。 */
    fun isConfigured(): Boolean = !expectedToken().isNullOrBlank()

    /** 常时比较：不泄漏「匹配了多长前缀」。缺 token 的请求直接不匹配。 */
    fun matches(presented: String?): Boolean {
        val expected = expectedToken() ?: return false
        if (presented.isNullOrEmpty()) return false
        return MessageDigest.isEqual(
            expected.encodeToByteArray(),
            presented.encodeToByteArray(),
        )
    }

    companion object {
        const val ENV_VAR: String = "TIDINGS_PLUGIN_TOKEN"
        const val TOKEN_FILE_NAME: String = "plugin-token"

        /** 测试与显式装配用：直接给一个 token（null/空 = 未配置）。 */
        fun fromExplicit(token: String?): PluginTokenGate =
            PluginTokenGate(envToken = token?.takeIf { it.isNotBlank() }, tokenFile = null)

        /** 测试与显式装配用：从指定文件读（文件不存在 = 未配置）。 */
        fun fromFile(file: File): PluginTokenGate = PluginTokenGate(envToken = null, tokenFile = file)

        /** 生产默认：环境变量优先，其次默认数据目录下的受控文件。 */
        fun fromEnvironment(
            env: String? = System.getenv(ENV_VAR),
            tokenFile: File = defaultTokenFile(),
        ): PluginTokenGate = PluginTokenGate(envToken = env?.takeIf { it.isNotBlank() }, tokenFile = tokenFile)

        /** 宿主数据目录下的受控文件默认路径（与 agent-grants.json 同一个数据目录）。 */
        fun defaultTokenFile(): File = File(File(HostDirs.default(), "plugins"), TOKEN_FILE_NAME)

        /**
         * 确保 `<dataDir>/plugins/plugin-token` 存在（0600）并返回它。
         *
         * 桌面托管模式在启动时调用：同一个 token 既注入子进程（env），也留在文件里
         * 给守护进程读。已存在则**不重写**（重写会让所有已配好的插件一夜之间全部
         * 注册失败）。权限收紧是尽力而为：非 POSIX 文件系统上静默跳过（回环 + 同用户
         * 的既有信任域不因此改变）。
         */
        fun ensureTokenFile(dataDir: File): File {
            val dir = File(dataDir, "plugins").apply { mkdirs() }
            val file = File(dir, TOKEN_FILE_NAME)
            if (!file.isFile) {
                val bytes = ByteArray(32)
                SecureRandom().nextBytes(bytes)
                val hex = bytes.joinToString("") { "%02x".format(it) }
                file.writeText(hex)
            }
            runCatching {
                Files.setPosixFilePermissions(
                    file.toPath(),
                    PosixFilePermissions.fromString("rw-------"),
                )
            }
            return file
        }
    }
}

/**
 * 本机宿主数据目录（桌面端 `~/Library/Application Support/捎话 Tidings/`、Linux 走
 * XDG）。抽出来是因为它原来只有 ControlPlane.defaultGrantFile 一个消费者，插件
 * token 文件需要**同一个目录** —— 数据目录的判据只能有一份，抄第二份迟早漂。
 */
internal object HostDirs {
    fun default(): File {
        val home = System.getProperty("user.home") ?: "."
        val os = System.getProperty("os.name").orEmpty()
        val base = if (os.contains("Mac")) {
            "$home/Library/Application Support"
        } else {
            System.getenv("XDG_DATA_HOME") ?: "$home/.local/share"
        }
        return File(base, "捎话 Tidings")
    }
}
