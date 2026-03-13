package com.spyglass.connect.pterodactyl

import com.spyglass.connect.Log
import kotlinx.coroutines.*

/**
 * Polls a remote Pterodactyl world for file changes on a fixed interval.
 * When changes are detected, notifies the callback with the set of changed categories.
 */
class RemoteWorldPoller(
    private val scope: CoroutineScope,
    private val onChanged: suspend (Set<String>) -> Unit,
) {

    companion object {
        private const val TAG = "PteroPoller"
        private const val POLL_INTERVAL_MS = 10_000L // 10 seconds
    }

    private var pollJob: Job? = null

    /** Start polling a remote world for changes. Cancels any previous poll. */
    fun start(
        client: PterodactylClient,
        cache: RemoteWorldCache,
        serverId: String,
        worldPath: String,
    ) {
        stop()
        Log.i(TAG, "Starting poll for ptero://$serverId$worldPath (${POLL_INTERVAL_MS}ms interval)")

        pollJob = scope.launch(Dispatchers.IO) {
            // Wait one interval before first poll (initial data was just fetched)
            delay(POLL_INTERVAL_MS)

            while (isActive) {
                try {
                    val changes = cache.refreshWorld(client, serverId, worldPath)
                    if (changes.isNotEmpty()) {
                        Log.i(TAG, "Changes detected: $changes")
                        onChanged(changes)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Poll error: ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** Stop polling. */
    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }
}
