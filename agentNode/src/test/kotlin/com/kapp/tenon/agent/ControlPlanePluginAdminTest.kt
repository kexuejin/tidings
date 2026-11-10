// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import com.kapp.tenon.agent.plugins.PluginStateManager
import com.kapp.tenon.core.protocol.Profile
import com.kapp.tenon.core.protocol.newId
import com.kapp.tenon.core.session.NodeRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.kapp.tenon.agent.udpTestTransport

class ControlPlanePluginAdminTest {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var scope: CoroutineScope
    private lateinit var agent: NodeRuntime
    private lateinit var plane: ControlPlane
    private lateinit var tempDir: File
    private lateinit var pluginsDir: File
    private lateinit var stateManager: PluginStateManager

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        tempDir = File.createTempFile("plugin-admin-test-", "").apply {
            delete()
            mkdirs()
        }
        pluginsDir = File(tempDir, "plugins").apply { mkdirs() }
        stateManager = PluginStateManager(tempDir)

        agent = NodeRuntime(
            messengerTransportFactory = udpTestTransport(),
            profile = Profile.mobile(
                nodeId = newId(),
                nick = "Admin Test Agent",
                host = "relay-admin",
                ver = "0.1.0",
            ),
            udpPort = 0,
            bindHost = "127.0.0.1",
        )

        runBlocking {
            agent.start(scope)
        }

        plane = ControlPlane(
            agent,
            httpPort = 0,
            token = "",
            sessions = AgentSessionRegistry(),
            pluginsDir = pluginsDir,
            pluginStateManager = stateManager,
        )
        plane.start()
    }

    @AfterTest
    fun tearDown() {
        plane.stop()
        runBlocking { agent.stop() }
        scope.cancel()
        tempDir.deleteRecursively()
    }

    private fun baseUrl(): String = "http://127.0.0.1:${plane.boundPort}"

    private fun postJson(path: String, body: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun get(path: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .GET()
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun createZip(zipFile: File, entries: Map<String, String>) {
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            for ((entryPath, content) in entries) {
                zos.putNextEntry(ZipEntry(entryPath))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
    }

    @Test
    fun pluginStatesReturnsInstalledPluginsAndStatus() {
        val p1 = File(pluginsDir, "demo-plugin").apply { mkdirs() }
        File(p1, "manifest.json").writeText(
            """
            {
                "pluginId": "demo-plugin",
                "name": "Demo Plugin",
                "version": "1.0.0",
                "type": "single",
                "leader": {
                    "agentId": "demo",
                    "name": "Demo Agent",
                    "role": "worker"
                }
            }
            """.trimIndent(),
        )

        val res = get("/v1/plugin/states")
        assertEquals(200, res.statusCode())
        val body = json.parseToJsonElement(res.body()).jsonObject
        assertTrue(body["ok"]!!.jsonPrimitive.boolean)
        val plugins = body["plugins"]!!.jsonArray
        assertEquals(1, plugins.size)
        val first = plugins[0].jsonObject
        assertEquals("demo-plugin", first["pluginId"]!!.jsonPrimitive.content)
        assertEquals("Demo Plugin", first["name"]!!.jsonPrimitive.content)
        assertTrue(first["enabled"]!!.jsonPrimitive.boolean)
        assertEquals("installed", first["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun pluginToggleEnablesAndDisablesPlugin() {
        val p1 = File(pluginsDir, "toggle-plugin").apply { mkdirs() }
        File(p1, "manifest.json").writeText(
            """
            {
                "pluginId": "toggle-plugin",
                "name": "Toggle Plugin",
                "version": "1.0.0",
                "type": "single",
                "leader": {"agentId": "toggle", "name": "Toggle", "role": "worker"}
            }
            """.trimIndent(),
        )

        // Disable
        val disableRes = postJson("/v1/plugin/toggle", """{"pluginId": "toggle-plugin", "enabled": false}""")
        assertEquals(200, disableRes.statusCode())
        assertFalse(stateManager.isPluginEnabled("toggle-plugin"))

        val statesAfterDisable = get("/v1/plugin/states")
        val pAfterDisable = json.parseToJsonElement(statesAfterDisable.body()).jsonObject["plugins"]!!.jsonArray[0].jsonObject
        assertFalse(pAfterDisable["enabled"]!!.jsonPrimitive.boolean)
        assertEquals("disabled", pAfterDisable["status"]!!.jsonPrimitive.content)

        // Re-enable
        val enableRes = postJson("/v1/plugin/toggle", """{"pluginId": "toggle-plugin", "enabled": true}""")
        assertEquals(200, enableRes.statusCode())
        assertTrue(stateManager.isPluginEnabled("toggle-plugin"))
    }

    @Test
    fun pluginSettingsSavesAndReturnsCustomSettings() {
        val p1 = File(pluginsDir, "settings-plugin").apply { mkdirs() }
        File(p1, "manifest.json").writeText(
            """
            {
                "pluginId": "settings-plugin",
                "name": "Settings Plugin",
                "version": "1.0.0",
                "type": "single",
                "leader": {"agentId": "settings", "name": "Settings", "role": "worker"}
            }
            """.trimIndent(),
        )

        val saveRes = postJson(
            "/v1/plugin/settings",
            """
            {
                "pluginId": "settings-plugin",
                "settings": {
                    "apiBase": "https://api.example.com",
                    "concurrency": "4"
                }
            }
            """.trimIndent(),
        )
        assertEquals(200, saveRes.statusCode())

        val settings = stateManager.getPluginSettings("settings-plugin")
        assertEquals("https://api.example.com", settings["apiBase"])
        assertEquals("4", settings["concurrency"])

        val statesRes = get("/v1/plugin/states")
        val pluginObj = json.parseToJsonElement(statesRes.body()).jsonObject["plugins"]!!.jsonArray[0].jsonObject
        val returnedSettings = pluginObj["settings"]!!.jsonObject
        assertEquals("https://api.example.com", returnedSettings["apiBase"]!!.jsonPrimitive.content)
        assertEquals("4", returnedSettings["concurrency"]!!.jsonPrimitive.content)
    }

    @Test
    fun pluginInstallFromZipExtractsAndRegisters() {
        val zipFile = File(tempDir, "installable.zip")
        createZip(
            zipFile,
            mapOf(
                "manifest.json" to """
                    {
                        "pluginId": "installed-tool",
                        "name": "Installed Tool",
                        "version": "2.1.0",
                        "type": "single",
                        "leader": {"agentId": "itool", "name": "ITool", "role": "worker"}
                    }
                """.trimIndent(),
            ),
        )

        val installRes = postJson("/v1/plugin/install", """{"path": "${zipFile.absolutePath}"}""")
        assertEquals(200, installRes.statusCode())
        val body = json.parseToJsonElement(installRes.body()).jsonObject
        assertTrue(body["ok"]!!.jsonPrimitive.boolean)
        assertEquals("installed-tool", body["pluginId"]!!.jsonPrimitive.content)

        val targetDir = File(pluginsDir, "installed-tool")
        assertTrue(targetDir.isDirectory)
        assertTrue(File(targetDir, "manifest.json").isFile)
        assertTrue(stateManager.isPluginEnabled("installed-tool"))
    }

    @Test
    fun pluginUninstallRemovesFromDiskAndCleansState() {
        val targetDir = File(pluginsDir, "to-remove").apply { mkdirs() }
        File(targetDir, "manifest.json").writeText(
            """
            {
                "pluginId": "to-remove",
                "name": "To Remove",
                "version": "1.0.0",
                "type": "single",
                "leader": {"agentId": "rm", "name": "RM", "role": "worker"}
            }
            """.trimIndent(),
        )
        stateManager.updatePluginSettings("to-remove", mapOf("key" to "value"))

        val uninstallRes = postJson("/v1/plugin/uninstall", """{"pluginId": "to-remove"}""")
        assertEquals(200, uninstallRes.statusCode())
        val body = json.parseToJsonElement(uninstallRes.body()).jsonObject
        assertTrue(body["ok"]!!.jsonPrimitive.boolean)
        assertTrue(body["removed"]!!.jsonPrimitive.boolean)

        assertFalse(targetDir.exists())
        assertTrue(stateManager.getPluginSettings("to-remove").isEmpty())
    }
}
