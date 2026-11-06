// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import com.kapp.tenon.agent.plugins.PluginInstallResult
import com.kapp.tenon.agent.plugins.PluginInstaller
import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * Control plane routes for managing plugin lifecycles and runtime settings.
 */

internal fun ControlPlane.pluginToggle(exchange: HttpExchange) {
    val obj = readJsonObject(exchange) ?: return
    val pluginId = obj["pluginId"]?.jsonPrimitiveOrNull()?.contentOrNull?.trim().orEmpty()
    if (pluginId.isEmpty()) {
        respond(exchange, 400, errorJson("bad-request", "pluginId is required"))
        return
    }
    val enabled = obj["enabled"]?.let { (it as? JsonPrimitive)?.booleanOrNull }
    if (enabled == null) {
        respond(exchange, 400, errorJson("bad-request", "enabled (boolean) is required"))
        return
    }

    pluginStateManager.setPluginEnabled(pluginId, enabled)
    val host = pluginHostRef?.invoke()
    if (!enabled) {
        if (host != null) {
            host.stopPlugin(pluginId)
        } else {
            plugins.markOffline(pluginId, "disabled by user")
        }
    } else {
        host?.spawnPlugin(pluginId)
    }

    respond(
        exchange,
        200,
        buildJsonObject {
            put("ok", true)
            put("pluginId", pluginId)
            put("enabled", enabled)
        },
    )
}

internal fun ControlPlane.pluginStates(exchange: HttpExchange) {
    val installed = PluginInstaller.listInstalled(pluginsDir)
    val registered = plugins.list().associateBy { it.pluginId }

    respond(
        exchange,
        200,
        buildJsonObject {
            put("ok", true)
            putJsonArray("plugins") {
                installed.forEach { manifest ->
                    val isEnabled = pluginStateManager.isPluginEnabled(manifest.pluginId)
                    val reg = registered[manifest.pluginId]
                    val status = reg?.status ?: if (isEnabled) "installed" else "disabled"
                    val settings = pluginStateManager.getPluginSettings(manifest.pluginId)

                    addJsonObject {
                        put("pluginId", manifest.pluginId)
                        put("name", manifest.name)
                        put("version", manifest.version)
                        put("type", manifest.type)
                        put("enabled", isEnabled)
                        put("status", status)
                        reg?.baseUrl?.let { put("baseUrl", it) }
                        reg?.httpPort?.let { put("httpPort", it) }
                        putJsonObject("settings") {
                            settings.forEach { (k, v) -> put(k, v) }
                        }
                    }
                }
            }
        },
    )
}

internal fun ControlPlane.pluginInstall(exchange: HttpExchange) {
    val obj = readJsonObject(exchange) ?: return
    val path = (obj["path"] ?: obj["archivePath"])?.jsonPrimitiveOrNull()?.contentOrNull?.trim().orEmpty()
    if (path.isEmpty()) {
        respond(exchange, 400, errorJson("bad-request", "path to zip archive is required"))
        return
    }
    val zipFile = File(path)
    if (!zipFile.isFile) {
        respond(exchange, 400, errorJson("file-not-found", "plugin archive does not exist: $path"))
        return
    }

    when (val result = PluginInstaller.installFromZip(zipFile, pluginsDir)) {
        is PluginInstallResult.Success -> {
            val manifest = result.manifest
            pluginStateManager.setPluginEnabled(manifest.pluginId, true)
            pluginHostRef?.invoke()?.spawnPlugin(manifest.pluginId)
            respond(
                exchange,
                200,
                buildJsonObject {
                    put("ok", true)
                    put("pluginId", manifest.pluginId)
                    put("name", manifest.name)
                    put("version", manifest.version)
                    put("installed", true)
                },
            )
        }
        is PluginInstallResult.Failure -> {
            respond(exchange, 400, errorJson(result.error, result.message))
        }
    }
}

internal fun ControlPlane.pluginUninstall(exchange: HttpExchange) {
    val obj = readJsonObject(exchange) ?: return
    val pluginId = obj["pluginId"]?.jsonPrimitiveOrNull()?.contentOrNull?.trim().orEmpty()
    if (pluginId.isEmpty()) {
        respond(exchange, 400, errorJson("bad-request", "pluginId is required"))
        return
    }

    pluginHostRef?.invoke()?.stopPlugin(pluginId)
    plugins.unregister(pluginId)
    pluginStateManager.clearPluginSettings(pluginId)
    pluginStateManager.setPluginEnabled(pluginId, true)
    val removed = PluginInstaller.uninstallPlugin(pluginId, pluginsDir)

    respond(
        exchange,
        200,
        buildJsonObject {
            put("ok", true)
            put("pluginId", pluginId)
            put("removed", removed)
        },
    )
}

internal fun ControlPlane.pluginSaveSettings(exchange: HttpExchange) {
    val obj = readJsonObject(exchange) ?: return
    val pluginId = obj["pluginId"]?.jsonPrimitiveOrNull()?.contentOrNull?.trim().orEmpty()
    if (pluginId.isEmpty()) {
        respond(exchange, 400, errorJson("bad-request", "pluginId is required"))
        return
    }
    val settingsObj = obj["settings"] as? JsonObject
    if (settingsObj == null) {
        respond(exchange, 400, errorJson("bad-request", "settings must be a JSON object"))
        return
    }

    val map = settingsObj.mapValues { (_, v) ->
        (v as? JsonPrimitive)?.contentOrNull ?: v.toString()
    }
    pluginStateManager.updatePluginSettings(pluginId, map)

    respond(
        exchange,
        200,
        buildJsonObject {
            put("ok", true)
            put("pluginId", pluginId)
            putJsonObject("settings") {
                map.forEach { (k, v) -> put(k, v) }
            }
        },
    )
}
