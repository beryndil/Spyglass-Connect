package com.spyglass.connect.watcher

import com.spyglass.connect.Log
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.*

/**
 * Watch a Minecraft world folder for changes.
 * Uses Java NIO WatchService (inotify) for instant detection, plus a polling
 * fallback every 3 seconds for cases where inotify misses events (e.g. Flatpak
 * sandboxed games that write files atomically via rename).
 *
 * Monitors: level.dat, playerdata/, region/, DIM-1/region/, DIM1/region/
 */
class WorldWatcher(
    private val scope: CoroutineScope,
    private val onChanged: suspend (Set<String>) -> Unit,
) {

    private var watchJob: Job? = null
    private var pollJob: Job? = null
    private var watchService: WatchService? = null

    companion object {
        private const val TAG = "Watcher"
        private const val POLL_INTERVAL_MS = 3000L
    }

    /** Start watching a world directory. Cancels any previous watch. */
    fun watch(worldDir: File) {
        stop()
        Log.i(TAG, "Watching ${worldDir.absolutePath}")

        val debouncer = ChangeDebouncer(scope, 500L, onChanged)

        // inotify-based watcher (instant but may miss atomic renames)
        watchJob = scope.launch(Dispatchers.IO) {
            val ws = FileSystems.getDefault().newWatchService()
            watchService = ws

            val dirsToWatch = buildList {
                add(worldDir.toPath() to "level")
                val playerData = File(worldDir, "playerdata")
                if (playerData.isDirectory) add(playerData.toPath() to "player")
                for ((subdir, category) in listOf(
                    "region" to "region_overworld",
                    "DIM-1/region" to "region_nether",
                    "DIM1/region" to "region_end",
                )) {
                    val dir = File(worldDir, subdir)
                    if (dir.isDirectory) add(dir.toPath() to category)
                }
            }

            val keyToCategory = mutableMapOf<WatchKey, String>()

            for ((path, category) in dirsToWatch) {
                try {
                    val key = path.register(
                        ws,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_CREATE,
                    )
                    keyToCategory[key] = category
                    Log.d(TAG, "Registered watch: $path ($category)")
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot watch $path: ${e.message}")
                }
            }

            try {
                while (isActive) {
                    val key = ws.poll(1, java.util.concurrent.TimeUnit.SECONDS) ?: continue
                    val category = keyToCategory[key] ?: "unknown"

                    for (event in key.pollEvents()) {
                        val kind = event.kind()
                        if (kind == StandardWatchEventKinds.OVERFLOW) continue
                        debouncer.onChange(category)
                    }

                    if (!key.reset()) {
                        keyToCategory.remove(key)
                        if (keyToCategory.isEmpty()) break
                    }
                }
            } catch (_: ClosedWatchServiceException) {
                Log.d(TAG, "Watch service closed")
            }
        }

        // Polling fallback — catches changes inotify misses
        pollJob = scope.launch(Dispatchers.IO) {
            val trackedFiles = buildList {
                add(File(worldDir, "level.dat") to "level")
                val playerDataDir = File(worldDir, "playerdata")
                if (playerDataDir.isDirectory) {
                    playerDataDir.listFiles()?.filter { it.name.endsWith(".dat") }?.forEach {
                        add(it to "player")
                    }
                }
            }

            // Snapshot initial timestamps
            val lastModified = trackedFiles.associate { (f, _) ->
                f.absolutePath to f.lastModified()
            }.toMutableMap()

            Log.d(TAG, "Poll fallback tracking ${lastModified.size} files")

            while (isActive) {
                delay(POLL_INTERVAL_MS)

                // Also pick up new playerdata files
                val currentFiles = buildList {
                    addAll(trackedFiles)
                    val playerDataDir = File(worldDir, "playerdata")
                    if (playerDataDir.isDirectory) {
                        playerDataDir.listFiles()?.filter { it.name.endsWith(".dat") }?.forEach { f ->
                            if (trackedFiles.none { it.first.absolutePath == f.absolutePath }) {
                                add(f to "player")
                            }
                        }
                    }
                }

                for ((file, category) in currentFiles) {
                    val path = file.absolutePath
                    val mod = file.lastModified()
                    val prev = lastModified[path]
                    if (prev == null || mod > prev) {
                        lastModified[path] = mod
                        if (prev != null) { // Skip initial snapshot
                            Log.d(TAG, "Poll detected change: ${file.name} ($category)")
                            debouncer.onChange(category)
                        }
                    }
                }
            }
        }
    }

    /** Stop watching. */
    fun stop() {
        watchJob?.cancel()
        watchJob = null
        pollJob?.cancel()
        pollJob = null
        try { watchService?.close() } catch (_: Exception) {}
        watchService = null
    }
}
