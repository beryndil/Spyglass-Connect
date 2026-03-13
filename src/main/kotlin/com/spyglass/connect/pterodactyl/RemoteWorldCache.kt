package com.spyglass.connect.pterodactyl

import com.spyglass.connect.Log
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

        Log.i(TAG, "Materialized world at $cacheDir")
        return cacheDir
    }

    /**
     * Download region files on demand (for chest scanning and map rendering).
     * These are large, so only download when explicitly requested.
     */
    suspend fun ensureRegionFiles(
        client: PterodactylClient,
        serverId: String,
        worldPath: String,
        dimension: String = "overworld",
    ): File {
        val cacheDir = worldCacheDir(serverId, worldPath)
        val remotePath = if (worldPath == "/") "" else worldPath

        val (remoteRegionDir, localRegionDir) = when (dimension) {
            "nether" -> "$remotePath/DIM-1/region" to File(cacheDir, "DIM-1/region")
            "end" -> "$remotePath/DIM1/region" to File(cacheDir, "DIM1/region")
            else -> "$remotePath/region" to File(cacheDir, "region")
        }

        downloadDirectory(client, serverId, remoteRegionDir, localRegionDir, "*.mca")

        // Also download entities directory if present (for pet scanning)
        val (remoteEntitiesDir, localEntitiesDir) = when (dimension) {
            "nether" -> "$remotePath/DIM-1/entities" to File(cacheDir, "DIM-1/entities")
            "end" -> "$remotePath/DIM1/entities" to File(cacheDir, "DIM1/entities")
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

                try {
                    val bytes = client.downloadFile(serverId, remotePath)
                    localDir.mkdirs()
                    localFile.writeBytes(bytes)
                    fileTimestamps[cacheKey] = file.modifiedAt

                    if (lastKnown != null) anyChanged = true
                    Log.d(TAG, "Downloaded ${file.name} (${bytes.size} bytes)")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to download ${file.name}: ${e.message}")
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
