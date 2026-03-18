package com.spyglass.connect.pterodactyl

import com.spyglass.connect.Log
import kotlinx.coroutines.delay
import java.io.File

/**
 * Downloads and caches remote Minecraft world files locally so that all existing
 * parsers (PlayerParser, ChestScanner, MapRenderer, etc.) work unchanged via File I/O.
 *
 * Cache dir: ~/.spyglass-connect/cache/ptero/{serverId}/{worldPath}/
 */
class RemoteWorldCache {

    companion object {
        private const val TAG = "PteroCache"
        private val cacheRoot = File(System.getProperty("user.home"), ".spyglass-connect/cache/ptero")
    }

    // Track modifiedAt per file to avoid re-downloading unchanged files
    private val fileTimestamps = mutableMapOf<String, String>()

    // Track which worlds use Paper/Spigot's split-dimension layout
    private val paperLayoutWorlds = mutableSetOf<String>()

    /** Mark a world as using Paper's split-dimension layout (world_nether, world_the_end). */
    fun setPaperLayout(serverId: String, worldPath: String, isPaper: Boolean) {
        val key = "$serverId:$worldPath"
        if (isPaper) paperLayoutWorlds.add(key) else paperLayoutWorlds.remove(key)
    }

    private fun isPaperLayout(serverId: String, worldPath: String): Boolean {
        return "$serverId:$worldPath" in paperLayoutWorlds
    }

    /** Get the local cache directory for a remote world. */
    fun worldCacheDir(serverId: String, worldPath: String): File {
        val sanitized = worldPath.trimStart('/').replace("/", "_").ifEmpty { "root" }
        return File(cacheRoot, "$serverId/$sanitized")
    }

    /**
     * Download essential world files (level.dat, playerdata, stats, advancements)
     * so player data features work.
     */
    suspend fun materializeWorld(
        client: PterodactylClient,
        serverId: String,
        worldPath: String,
    ): File {
        val cacheDir = worldCacheDir(serverId, worldPath)
        cacheDir.mkdirs()

        val remotePath = if (worldPath == "/") "" else worldPath

        // Download level.dat
        downloadFileIfChanged(client, serverId, "$remotePath/level.dat", File(cacheDir, "level.dat"))

        // Download playerdata/*.dat
        downloadDirectory(client, serverId, "$remotePath/playerdata", File(cacheDir, "playerdata"), "*.dat")

        // Download stats/*.json
        downloadDirectory(client, serverId, "$remotePath/stats", File(cacheDir, "stats"), "*.json")

        // Download advancements/*.json
        downloadDirectory(client, serverId, "$remotePath/advancements", File(cacheDir, "advancements"), "*.json")

        // Download usercache.json from server root (for player name resolution)
        // Place it in the parent dir so PlayerParser.resolvePlayerName() finds it via worldDir.parentFile
        val serverCacheDir = cacheDir.parentFile
        if (serverCacheDir != null) {
            downloadFileIfChanged(client, serverId, "/usercache.json", File(serverCacheDir, "usercache.json"))
        }

        Log.i(TAG, "Materialized world at $cacheDir")
        return cacheDir
    }

    /**
     * Download region files on demand (for chest scanning and map rendering).
     * These are large, so only download when explicitly requested.
     *
     * Handles Paper/Spigot's split-dimension layout where nether/end region files
     * live in sibling directories (e.g. /world_nether/DIM-1/region/) rather than
     * nested under the main world dir (e.g. /world/DIM-1/region/).
     * Files are cached in the vanilla layout so all parsers work unchanged.
     */
    suspend fun ensureRegionFiles(
        client: PterodactylClient,
        serverId: String,
        worldPath: String,
        dimension: String = "overworld",
    ): File {
        val cacheDir = worldCacheDir(serverId, worldPath)
        val remotePath = if (worldPath == "/") "" else worldPath
        val paper = isPaperLayout(serverId, worldPath)

        // For Paper layout, nether/end region files are in sibling directories:
        //   /world_nether/DIM-1/region/  →  cached as world/DIM-1/region/
        //   /world_the_end/DIM1/region/  →  cached as world/DIM1/region/
        val (remoteRegionDir, localRegionDir) = when (dimension) {
            "nether" -> {
                val remoteDir = if (paper) {
                    "${remotePath}_nether/DIM-1/region"
                } else {
                    "$remotePath/DIM-1/region"
                }
                remoteDir to File(cacheDir, "DIM-1/region")
            }
            "end" -> {
                val remoteDir = if (paper) {
                    "${remotePath}_the_end/DIM1/region"
                } else {
                    "$remotePath/DIM1/region"
                }
                remoteDir to File(cacheDir, "DIM1/region")
            }
            else -> "$remotePath/region" to File(cacheDir, "region")
        }

        downloadDirectory(client, serverId, remoteRegionDir, localRegionDir, "*.mca")

        // Also download entities directory if present (for pet scanning)
        val (remoteEntitiesDir, localEntitiesDir) = when (dimension) {
            "nether" -> {
                val remoteDir = if (paper) {
                    "${remotePath}_nether/DIM-1/entities"
                } else {
                    "$remotePath/DIM-1/entities"
                }
                remoteDir to File(cacheDir, "DIM-1/entities")
            }
            "end" -> {
                val remoteDir = if (paper) {
                    "${remotePath}_the_end/DIM1/entities"
                } else {
                    "$remotePath/DIM1/entities"
                }
                remoteDir to File(cacheDir, "DIM1/entities")
            }
            else -> "$remotePath/entities" to File(cacheDir, "entities")
        }
        try {
            downloadDirectory(client, serverId, remoteEntitiesDir, localEntitiesDir, "*.mca")
        } catch (_: Exception) {
            // entities dir may not exist on older servers
        }

        return cacheDir
    }

    /**
     * Download only the specific region files needed for a map area.
     * Much faster than ensureRegionFiles() which downloads ALL region files.
     */
    suspend fun ensureRegionFilesForArea(
        client: PterodactylClient,
        serverId: String,
        worldPath: String,
        centerX: Int,
        centerZ: Int,
        radiusChunks: Int,
        dimension: String = "overworld",
    ): File {
        val cacheDir = worldCacheDir(serverId, worldPath)
        val remotePath = if (worldPath == "/") "" else worldPath
        val paper = isPaperLayout(serverId, worldPath)

        val remoteRegionDir = when (dimension) {
            "nether" -> if (paper) "${remotePath}_nether/DIM-1/region" else "$remotePath/DIM-1/region"
            "end" -> if (paper) "${remotePath}_the_end/DIM1/region" else "$remotePath/DIM1/region"
            else -> "$remotePath/region"
        }
        val localRegionDir = when (dimension) {
            "nether" -> File(cacheDir, "DIM-1/region")
            "end" -> File(cacheDir, "DIM1/region")
            else -> File(cacheDir, "region")
        }

        // Calculate which region files contain the requested chunks
        val centerChunkX = centerX shr 4
        val centerChunkZ = centerZ shr 4
        val regionFiles = mutableSetOf<String>()
        for (cx in (centerChunkX - radiusChunks)..(centerChunkX + radiusChunks)) {
            for (cz in (centerChunkZ - radiusChunks)..(centerChunkZ + radiusChunks)) {
                regionFiles.add("r.${cx shr 5}.${cz shr 5}.mca")
            }
        }

        Log.i(TAG, "Map area needs ${regionFiles.size} region file(s) for $dimension")

        // Download only the needed region files — skip any already on disk
        var downloaded = 0
        var cached = 0
        for (fileName in regionFiles) {
            val remoteFull = "$remoteRegionDir/$fileName"
            val localFile = File(localRegionDir, fileName)

            // Use disk cache — only download if the file doesn't exist locally
            if (localFile.exists()) {
                cached++
                continue
            }

            for (attempt in 1..3) {
                try {
                    val bytes = client.downloadFile(serverId, remoteFull)
                    localRegionDir.mkdirs()
                    localFile.writeBytes(bytes)
                    downloaded++
                    Log.d(TAG, "Downloaded $fileName (${bytes.size} bytes)")
                    break
                } catch (e: Exception) {
                    if (attempt < 3) {
                        delay(500L * attempt)
                    } else {
                        Log.w(TAG, "Failed to download $fileName: ${e.message}")
                    }
                }
            }
        }
        Log.i(TAG, "Region files: $cached cached, $downloaded downloaded, ${regionFiles.size} needed")

        return cacheDir
    }

    /**
     * Refresh a world's essential files, checking for changes via modifiedAt timestamps.
     * Returns the set of change categories detected.
     */
    suspend fun refreshWorld(
        client: PterodactylClient,
        serverId: String,
        worldPath: String,
    ): Set<String> {
        val changes = mutableSetOf<String>()
        val cacheDir = worldCacheDir(serverId, worldPath)
        val remotePath = if (worldPath == "/") "" else worldPath

        // Check level.dat
        if (downloadFileIfChanged(client, serverId, "$remotePath/level.dat", File(cacheDir, "level.dat"))) {
            changes.add("level")
        }

        // Check playerdata
        if (downloadDirectory(client, serverId, "$remotePath/playerdata", File(cacheDir, "playerdata"), "*.dat")) {
            changes.add("player")
        }

        // Check stats
        if (downloadDirectory(client, serverId, "$remotePath/stats", File(cacheDir, "stats"), "*.json")) {
            changes.add("stats")
        }

        return changes
    }

    /**
     * Download a single file if it has changed (based on modifiedAt from the API).
     * Returns true if the file was re-downloaded.
     */
    private suspend fun downloadFileIfChanged(
        client: PterodactylClient,
        serverId: String,
        remotePath: String,
        localFile: File,
    ): Boolean {
        val cacheKey = "$serverId:$remotePath"

        try {
            // We need to list the parent directory to get the modifiedAt timestamp
            val parentDir = remotePath.substringBeforeLast("/").ifEmpty { "/" }
            val fileName = remotePath.substringAfterLast("/")

            val files = client.listFiles(serverId, parentDir)
            val fileInfo = files.firstOrNull { it.name == fileName && it.isFile }
                ?: return false // File doesn't exist remotely

            val lastKnown = fileTimestamps[cacheKey]
            if (lastKnown == fileInfo.modifiedAt && localFile.exists()) {
                return false // No change
            }

            // Download
            val bytes = client.downloadFile(serverId, remotePath)
            localFile.parentFile?.mkdirs()
            localFile.writeBytes(bytes)
            fileTimestamps[cacheKey] = fileInfo.modifiedAt

            Log.d(TAG, "Downloaded $remotePath (${bytes.size} bytes)")
            return lastKnown != null // Only report as "changed" if we had a previous version
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download $remotePath: ${e.message}")
            return false
        }
    }

    /**
     * Download all matching files in a remote directory.
     * Returns true if any files were re-downloaded.
     */
    private suspend fun downloadDirectory(
        client: PterodactylClient,
        serverId: String,
        remoteDir: String,
        localDir: File,
        pattern: String,
    ): Boolean {
        var anyChanged = false

        try {
            val files = client.listFiles(serverId, remoteDir)
            val extension = pattern.removePrefix("*")

            for (file in files) {
                if (!file.isFile) continue
                if (!file.name.endsWith(extension)) continue

                val remotePath = "$remoteDir/${file.name}"
                val localFile = File(localDir, file.name)

                val cacheKey = "$serverId:$remotePath"
                val lastKnown = fileTimestamps[cacheKey]

                if (lastKnown == file.modifiedAt && localFile.exists()) continue

                // For large region files (.mca), use disk cache — only re-download
                // if modifiedAt is newer than what we last downloaded
                if (localFile.exists() && file.name.endsWith(".mca")) {
                    if (lastKnown == null) {
                        // First run after restart — trust the disk cache
                        fileTimestamps[cacheKey] = file.modifiedAt
                        continue
                    }
                    // modifiedAt changed → re-download below
                }

                var downloaded = false
                for (attempt in 1..3) {
                    try {
                        val bytes = client.downloadFile(serverId, remotePath)
                        localDir.mkdirs()
                        localFile.writeBytes(bytes)
                        fileTimestamps[cacheKey] = file.modifiedAt

                        if (lastKnown != null) anyChanged = true
                        Log.d(TAG, "Downloaded ${file.name} (${bytes.size} bytes)")
                        downloaded = true
                        break
                    } catch (e: Exception) {
                        if (attempt < 3) {
                            Log.d(TAG, "Retry ${attempt}/3 for ${file.name}: ${e.message}")
                            delay(500L * attempt)
                        } else {
                            Log.w(TAG, "Failed to download ${file.name} after 3 attempts: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Directory not found or inaccessible: $remoteDir")
        }

        return anyChanged
    }

    /** Clear all cached files for a server. */
    fun clearCache(serverId: String) {
        val dir = File(cacheRoot, serverId)
        if (dir.exists()) {
            dir.deleteRecursively()
            Log.i(TAG, "Cleared cache for server $serverId")
        }
        fileTimestamps.keys.removeAll { it.startsWith("$serverId:") }
    }

    /** Clear the entire cache. */
    fun clearAll() {
        if (cacheRoot.exists()) {
            cacheRoot.deleteRecursively()
            Log.i(TAG, "Cleared all Pterodactyl caches")
        }
        fileTimestamps.clear()
    }
}
