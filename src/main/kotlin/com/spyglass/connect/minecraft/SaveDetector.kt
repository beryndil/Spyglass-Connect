package com.spyglass.connect.minecraft

import com.spyglass.connect.Log
import com.spyglass.connect.config.ConfigStore
import com.spyglass.connect.model.WorldInfo
import com.spyglass.connect.pterodactyl.PterodactylClient
import com.spyglass.connect.pterodactyl.RemoteWorldCache
import com.spyglass.connect.pterodactyl.RemoteWorldDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import net.querz.nbt.tag.CompoundTag
import net.querz.nbt.tag.ListTag
import java.io.File

/**
 * Auto-detect Minecraft Java Edition save directories per OS.
 * Supports default launcher, Prism Launcher, and custom directories.
 */
object SaveDetector {

    private const val TAG = "Saves"
    private val GAME_MODE_NAMES = mapOf(
        0 to "survival",
        1 to "creative",
        2 to "adventure",
        3 to "spectator",
    )

    private val DIFFICULTY_NAMES = mapOf(
        0 to "peaceful",
        1 to "easy",
        2 to "normal",
        3 to "hard",
    )

    /** Detect the default .minecraft/saves directory for the current OS. */
    fun defaultSavesDir(): File? {
        val os = System.getProperty("os.name", "").lowercase()
        val home = System.getProperty("user.home", "")
        val dir = when {
            os.contains("win") -> File(System.getenv("APPDATA") ?: "$home/AppData/Roaming", ".minecraft/saves")
            os.contains("mac") -> File(home, "Library/Application Support/minecraft/saves")
            else -> File(home, ".minecraft/saves")
        }
        return if (dir.isDirectory) dir else null
    }

    /**
     * Detect Prism Launcher instance saves directories.
     * Prism stores instances under a configurable root; each instance has .minecraft/saves/.
     */
    fun prismLauncherDirs(): List<File> {
        val os = System.getProperty("os.name", "").lowercase()
        val home = System.getProperty("user.home", "")

        val prismRoots = mutableListOf<File>()
        when {
            os.contains("win") -> {
                prismRoots.add(File(System.getenv("APPDATA") ?: "$home/AppData/Roaming", "PrismLauncher/instances"))
                prismRoots.add(File(System.getenv("LOCALAPPDATA") ?: "$home/AppData/Local", "PrismLauncher/instances"))
            }
            os.contains("mac") -> {
                prismRoots.add(File(home, "Library/Application Support/PrismLauncher/instances"))
            }
            else -> {
                prismRoots.add(File(home, ".local/share/PrismLauncher/instances"))
                // Flatpak path
                prismRoots.add(File(home, ".var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances"))
            }
        }

        val savesDirs = mutableListOf<File>()
        for (root in prismRoots) {
            if (!root.isDirectory) continue
            root.listFiles()?.forEach { instance ->
                if (!instance.isDirectory) return@forEach
                // Prism instances have .minecraft/saves/ or minecraft/saves/
                val dotMc = File(instance, ".minecraft/saves")
                val mc = File(instance, "minecraft/saves")
                if (dotMc.isDirectory) savesDirs.add(dotMc)
                else if (mc.isDirectory) savesDirs.add(mc)
            }
        }
        return savesDirs
    }

    /**
     * Get all save directories to scan, combining default, Prism Launcher, and custom paths.
     */
    fun allSavesDirs(): List<File> {
        val config = ConfigStore.load()
        val dirs = mutableListOf<File>()

        // Default .minecraft
        defaultSavesDir()?.let { dirs.add(it) }

        // Prism Launcher auto-detection
        if (config.autoDetectPrismLauncher) {
            dirs.addAll(prismLauncherDirs())
        }

        // Custom directories (user-configured, e.g. Pterodactyl paths)
        for (path in config.customSaveDirs) {
            val dir = File(path)
            if (dir.isDirectory) {
                // Check if this is a saves directory (contains world folders with level.dat)
                // or a single world directory (has level.dat directly)
                if (File(dir, "level.dat").exists()) {
                    // This is a world directory — use its parent as saves dir
                    dir.parentFile?.let { dirs.add(it) }
                } else {
                    dirs.add(dir)
                }
            }
        }

        return dirs.distinctBy { it.canonicalPath }
    }

    /** Find all valid Minecraft worlds across all configured save directories. */
    fun detectWorlds(savesDir: File? = null): List<WorldInfo> {
        val dirs = if (savesDir != null) listOf(savesDir) else allSavesDirs()
        Log.d(TAG, "Scanning ${dirs.size} save dirs: ${dirs.joinToString { it.absolutePath }}")

        val seen = mutableSetOf<String>() // Deduplicate by canonical path
        val worldDirs = mutableListOf<Pair<File, File>>() // worldDir to savesDir

        for (dir in dirs) {
            if (!dir.isDirectory) continue
            dir.listFiles()
                ?.filter { it.isDirectory && File(it, "level.dat").exists() }
                ?.forEach { worldDir ->
                    val canonical = worldDir.canonicalPath
                    if (seen.add(canonical)) {
                        worldDirs.add(worldDir to dir)
                    }
                }
        }

        // Parse all worlds in parallel
        val allWorlds = runBlocking(Dispatchers.IO) {
            worldDirs.map { (worldDir, parentDir) ->
                async {
                    parseWorldInfo(worldDir)?.copy(
                        sourcePath = worldDir.absolutePath,
                        sourceLabel = labelForDir(parentDir),
                    )
                }
            }.awaitAll().filterNotNull()
        }

        Log.i(TAG, "Found ${allWorlds.size} worlds")
        return allWorlds.sortedByDescending { it.lastPlayed }
    }

    /** Generate a human-readable label for a saves directory. */
    private fun labelForDir(savesDir: File): String {
        val path = savesDir.canonicalPath
        val home = System.getProperty("user.home", "")

        // Check if it's a Prism Launcher instance
        if (path.contains("PrismLauncher/instances/")) {
            val instanceName = path.substringAfter("instances/").substringBefore("/")
            return "Prism: $instanceName"
        }

        // Default .minecraft
        if (path.endsWith(".minecraft/saves") || path.endsWith("minecraft/saves")) {
            return "Default"
        }

        // Custom path — show abbreviated
        return if (path.startsWith(home)) {
            "~${path.removePrefix(home)}"
        } else {
            path
        }
    }

    /** Parse level.dat to extract world metadata. */
    fun parseWorldInfo(worldDir: File): WorldInfo? {
        val levelDat = File(worldDir, "level.dat")
        val root = NbtHelper.readCompressed(levelDat) ?: return null
        val data = NbtHelper.compound(root, "Data") ?: return null

        val modLoader = detectModLoader(data, worldDir)

        return WorldInfo(
            folderName = worldDir.name,
            displayName = NbtHelper.string(data, "LevelName", worldDir.name),
            gameMode = GAME_MODE_NAMES[NbtHelper.int(data, "GameType")] ?: "survival",
            difficulty = DIFFICULTY_NAMES[NbtHelper.int(data, "Difficulty")] ?: "normal",
            lastPlayed = NbtHelper.long(data, "LastPlayed"),
            seed = extractSeed(data),
            dataVersion = NbtHelper.int(data, "DataVersion"),
            isModded = modLoader != null,
            modLoader = modLoader,
        )
    }

    /** Detect mod loader from level.dat NBT and world directory contents. */
    private fun detectModLoader(data: CompoundTag, worldDir: File): String? {
        // Check ServerBrands list (most reliable — Fabric writes "fabric", NeoForge writes "neoforge")
        @Suppress("UNCHECKED_CAST")
        val brands = data.get("ServerBrands") as? ListTag<*>
        if (brands != null) {
            val brandStrings = (0 until brands.size()).map { brands.get(it).valueToString().trim('"').lowercase() }
            when {
                brandStrings.any { it == "neoforge" } -> return "NeoForge"
                brandStrings.any { it == "forge" } -> return "Forge"
                brandStrings.any { it == "fabric" } -> return "Fabric"
                brandStrings.any { it == "quilt" } -> return "Quilt"
            }
        }

        // Check WasModded flag + FML/fabric compound tags as fallback
        if (NbtHelper.compound(data, "fml") != null || NbtHelper.compound(data, "FML") != null) {
            return "Forge"
        }
        if (data.keySet().any { it.startsWith("fabric") }) {
            return "Fabric"
        }

        // WasModded flag without identifiable loader
        if (NbtHelper.boolean(data, "WasModded")) {
            // Check mods folder for clues
            val modsDir = findModsDir(worldDir)
            if (modsDir != null && modsDir.listFiles()?.any { it.extension == "jar" } == true) {
                val jars = modsDir.listFiles()?.map { it.name.lowercase() } ?: emptyList()
                return when {
                    jars.any { it.contains("neoforge") } -> "NeoForge"
                    jars.any { it.contains("fabric") } -> "Fabric"
                    jars.any { it.contains("forge") } -> "Forge"
                    else -> "Modded"
                }
            }
            return "Modded"
        }

        return null
    }

    /** Find the mods directory relative to a world's game root. */
    private fun findModsDir(worldDir: File): File? {
        // Singleplayer: world is in .minecraft/saves/<world>, mods at .minecraft/mods
        val savesParent = worldDir.parentFile?.parentFile // .minecraft/
        if (savesParent != null) {
            val mods = File(savesParent, "mods")
            if (mods.isDirectory) return mods
        }
        // Server: world is in server/<world>, mods at server/mods
        val serverRoot = worldDir.parentFile
        if (serverRoot != null) {
            val mods = File(serverRoot, "mods")
            if (mods.isDirectory) return mods
        }
        return null
    }

    /**
     * Detect worlds on a configured Pterodactyl server.
     * Returns empty list if Pterodactyl is not configured or disabled.
     */
    suspend fun detectRemoteWorlds(cache: RemoteWorldCache = RemoteWorldCache()): List<WorldInfo> {
        val config = ConfigStore.load()
        val ptero = config.pterodactyl
        if (!ptero.enabled || ptero.panelUrl.isBlank() || ptero.apiKey.isBlank() || ptero.selectedServerId.isBlank()) {
            return emptyList()
        }

        return try {
            val client = PterodactylClient(ptero.panelUrl, ptero.apiKey)
            try {
                RemoteWorldDetector.detectWorlds(client, ptero.selectedServerId, ptero.selectedServerName, cache)
            } finally {
                client.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Remote world detection failed", e)
            emptyList()
        }
    }

    /** Extract seed — location varies between Minecraft versions. */
    private fun extractSeed(data: CompoundTag): Long {
        // 1.16+: Data.WorldGenSettings.seed
        NbtHelper.compound(data, "WorldGenSettings")?.let { wgs ->
            val seed = NbtHelper.long(wgs, "seed")
            if (seed != 0L) return seed
        }
        // Pre-1.16: Data.RandomSeed
        return NbtHelper.long(data, "RandomSeed")
    }
}
