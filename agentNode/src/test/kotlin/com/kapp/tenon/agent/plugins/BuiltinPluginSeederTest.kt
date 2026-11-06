// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.plugins

import com.kapp.tenon.agent.drivers.DriverKeys
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuiltinPluginSeederTest {

    private fun tempDir(): File {
        val dir = File.createTempFile("seeder-test-", "").let { f ->
            f.delete()
            f.mkdirs()
            f
        }
        return dir
    }

    @Test
    fun seedPopulatesBuiltinPlugins() {
        val dir = tempDir()
        try {
            BuiltinPluginSeeder.seed(dir)

            for (builtin in BuiltinPluginSeeder.BUILTIN_PLUGINS) {
                val pluginDir = File(dir, builtin.pluginId)
                assertTrue(pluginDir.isDirectory, "Directory should exist for ${builtin.pluginId}")
                val manifestFile = File(pluginDir, "manifest.json")
                assertTrue(manifestFile.isFile, "manifest.json should exist for ${builtin.pluginId}")

                val parsed = PluginManifestJson.parse(manifestFile.readText())
                assertEquals(builtin.pluginId, parsed.pluginId)
                assertEquals(builtin.name, parsed.name)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun seedIsIdempotentAndDoesNotOverwriteCustomModifications() {
        val dir = tempDir()
        try {
            BuiltinPluginSeeder.seed(dir)

            // Modify one manifest
            val targetId = DriverKeys.cliDriverId("claude")
            val claudeDir = File(dir, targetId)
            val manifestFile = File(claudeDir, "manifest.json")
            manifestFile.writeText("""{"pluginId":"$targetId","name":"Custom Modified Claude","leader":{"agentId":"claude","name":"Claude","role":"architect"}}""")

            // Seed again
            BuiltinPluginSeeder.seed(dir)

            // Should retain custom modification
            val parsed = PluginManifestJson.parse(manifestFile.readText())
            assertEquals("Custom Modified Claude", parsed.name)
        } finally {
            dir.deleteRecursively()
        }
    }
}
