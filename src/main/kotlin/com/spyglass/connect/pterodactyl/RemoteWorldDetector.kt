package com.spyglass.connect.pterodactyl

import com.spyglass.connect.Log
import com.spyglass.connect.minecraft.SaveDetector
import com.spyglass.connect.model.WorldInfo
import java.io.File

/**
 * Discover Minecraft worlds on a Pterodactyl server by scanning for level.dat files
 * in common server directory structures.
 */
object RemoteWorldDetector {

    private const val TAG = "PteroDetect"

    // Common world directory names on Minecraft servers
    private val WORLD_CANDIDATES = listOf(
        "/world",           // Paper/Spigot/vanilla
        "/",                // Some servers have level.dat in root
    )

    /**
     * Detect worlds on a remote Pterodactyl server.
     * Downloads level.dat files to parse world metadata, then returns WorldInfo objects.
     */
    suspend fun detectWorlds(
        client: PterodactylClient,
        serverId: String,
        serverName: String,
        cache: RemoteWorldCache,
    ): List<WorldInfo> {
        val worlds = mutableListOf<WorldInfo>()

        try {
            // First, list root directory to find world folders
            val rootFiles = client.listFiles(serverId, "/")
            val rootDirs = rootFiles.filter { !it.isFile }.map { it.name }
            val rootHasLevelDat = rootFiles.any { it.isFile && it.name == "level.dat" }

            Log.d(TAG, "Root dirs: $rootDirs, has level.dat: $rootHasLevelDat")

            // Check each candidate location
            val candidates = mutableListOf<String>()

            // Standard "world" directory
            if ("world" in rootDirs) {
                candidates.add("/world")
            }

            // Check for level.dat in root (some server setups)
            if (rootHasLevelDat) {
                candidates.add("/")
            }

            // Check other common names
            for (name in rootDirs) {
                if (name in listOf("world", "plugins", "config", "logs", "cache", "libraries", "versions")) continue
                // Check if this directory has a level.dat
                try {
                    val dirFiles = client.listFiles(serverId, "/$name")
                    if (dirFiles.any { it.isFile && it.name == "level.dat" }) {
                        candidates.add("/$name")
                    }
                } catch (_: Exception) {
                    // Skip directories we can't read
                }
            }

            Log.i(TAG, "World candidates for '$serverName': $candidates")

            // Download and parse level.dat for each candidate
            for (worldPath in candidates) {
                try {
                    val levelDatPath = if (worldPath == "/") "/level.dat" else "$worldPath/level.dat"
                    val levelDatBytes = client.downloadFile(serverId, levelDatPath)

                    // Write to cache so we can use SaveDetector.parseWorldInfo()
                    val cacheDir = cache.worldCacheDir(serverId, worldPath)
                    cacheDir.mkdirs()
                    File(cacheDir, "level.dat").writeBytes(levelDatBytes)

                    val worldInfo = SaveDetector.parseWorldInfo(cacheDir)
                    if (worldInfo != null) {
                        val folderName = if (worldPath == "/") "server_root" else worldPath.trimStart('/')
                        worlds.add(
                            worldInfo.copy(
                                folderName = "ptero_${serverId}_$folderName",
                                sourcePath = "ptero://$serverId$worldPath",
                                sourceLabel = "Ptero: $serverName",
                            )
                        )
                        Log.i(TAG, "Found world: ${worldInfo.displayName} at $worldPath")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse world at $worldPath: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "World detection failed for server '$serverName'", e)
        }

        return worlds
    }
}
