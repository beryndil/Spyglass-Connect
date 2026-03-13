package com.spyglass.connect.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persist app configuration to ~/.spyglass-connect/config.json.
 * Stores custom save directories, server port, and launcher auto-detection toggle.
 */
object ConfigStore {

    @Serializable
    data class PterodactylConfig(
        val panelUrl: String = "",
        val apiKey: String = "",
        val selectedServerId: String = "",
        val selectedServerName: String = "",
        val enabled: Boolean = false,
    )

    @Serializable
    data class AppConfig(
        val customSaveDirs: MutableList<String> = mutableListOf(),
        val serverPort: Int = 29170,
        val autoDetectPrismLauncher: Boolean = true,
        val minimizeToTray: Boolean = false,
        val closeToTray: Boolean = false,
        val pterodactyl: PterodactylConfig = PterodactylConfig(),
    )

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val configDir = File(System.getProperty("user.home"), ".spyglass-connect")
    private val configFile = File(configDir, "config.json")

    private var cached: AppConfig? = null

    fun load(): AppConfig {
        cached?.let { return it }
        if (!configFile.exists()) return AppConfig().also { cached = it }
        return try {
            json.decodeFromString(AppConfig.serializer(), configFile.readText()).also { cached = it }
        } catch (_: Exception) {
            AppConfig().also { cached = it }
        }
    }

    fun save(config: AppConfig) {
        cached = config
        configDir.mkdirs()
        configFile.writeText(json.encodeToString(config))
    }

    fun addCustomDir(path: String) {
        val config = load()
        val normalized = path.trimEnd('/', '\\')
        if (normalized !in config.customSaveDirs) {
            config.customSaveDirs.add(normalized)
            save(config)
        }
    }

    fun removeCustomDir(path: String) {
        val config = load()
        config.customSaveDirs.remove(path)
        save(config)
    }

    fun setAutoDetectPrism(enabled: Boolean) {
        val config = load()
        save(config.copy(autoDetectPrismLauncher = enabled))
    }

    fun setMinimizeToTray(enabled: Boolean) {
        val config = load()
        save(config.copy(minimizeToTray = enabled))
    }

    fun setCloseToTray(enabled: Boolean) {
        val config = load()
        save(config.copy(closeToTray = enabled))
    }

    fun setPterodactylConfig(ptero: PterodactylConfig) {
        val config = load()
        save(config.copy(pterodactyl = ptero))
    }

    fun clearPterodactylConfig() {
        setPterodactylConfig(PterodactylConfig())
    }
}
