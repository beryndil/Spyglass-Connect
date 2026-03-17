package com.spyglass.connect.server

import com.spyglass.connect.Log
import com.spyglass.connect.config.ConfigStore
import com.spyglass.connect.minecraft.*
import com.spyglass.connect.model.*
import com.spyglass.connect.pterodactyl.PterodactylClient
import com.spyglass.connect.pterodactyl.RemoteWorldCache
import com.spyglass.connect.pterodactyl.RemoteWorldPoller
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.encodeToJsonElement
import java.io.File

/**
 * Dispatch incoming messages to the appropriate Minecraft data parser
 * and construct response messages.
 */
class MessageHandler(
    private val worldsProvider: () -> List<WorldInfo>,
    private val searchIndex: ItemSearchIndex,
    private val scope: CoroutineScope? = null,
    private val onWorldChanged: (suspend (Set<String>) -> Unit)? = null,
) {

    companion object {
        private const val TAG = "Handler"
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var selectedWorldDir: File? = null
    private var cachedContainers: List<ContainerInfo>? = null
    private var cachedPets: List<PetData>? = null

    /** Cached player UUID from the last player data request (used for stats/advancements). */
    private var cachedPlayerUuid: String? = null

    /** Track whether the current world is remote (ptero://) for polling vs file-watching. */
    var isRemoteWorld: Boolean = false
        private set
    private var remoteServerId: String? = null
    private var remoteWorldPath: String? = null
    private val remoteWorldCache = RemoteWorldCache()
    private var remotePoller: RemoteWorldPoller? = null
    private var pterodactylClient: PterodactylClient? = null

    /** Handle an incoming message and return a response (or null for no response). */
    suspend fun handle(
        message: SpyglassMessage,
        sendIntermediate: (suspend (SpyglassMessage) -> Unit)? = null,
    ): SpyglassMessage? {
        Log.d(TAG, "Handling ${message.type}")
        return when (message.type) {
            MessageType.SELECT_WORLD -> handleSelectWorld(message)
            MessageType.REQUEST_PLAYER_LIST -> handleRequestPlayerList(message)
            MessageType.REQUEST_PLAYER -> handleRequestPlayer(message)
            MessageType.REQUEST_CHESTS -> handleRequestChests(message, sendIntermediate)
            MessageType.REQUEST_STRUCTURES -> handleRequestStructures(message)
            MessageType.REQUEST_MAP -> handleRequestMap(message)
            MessageType.SEARCH_ITEMS -> handleSearchItems(message)
            MessageType.REQUEST_STATS -> handleRequestStats(message)
            MessageType.REQUEST_ADVANCEMENTS -> handleRequestAdvancements(message)
            MessageType.REQUEST_PETS -> handleRequestPets(message)
            MessageType.DEVICE_LOG -> { handleDeviceLog(message); null }
            else -> errorResponse(message.requestId, ErrorCode.UNKNOWN_TYPE, "Unknown message type: ${message.type}")
        }
    }

    /** Get the current world list as a message (includes all worlds — phone UI handles modded). */
    fun worldListMessage(): SpyglassMessage {
        val payload = WorldListPayload(worlds = worldsProvider())
        return SpyglassMessage(
            type = MessageType.WORLD_LIST,
            payload = json.encodeToJsonElement(payload),
        )
    }

    private suspend fun handleSelectWorld(message: SpyglassMessage): SpyglassMessage {
        val payload = json.decodeFromJsonElement(SelectWorldPayload.serializer(), message.payload)

        // Find the world across all configured save directories
        val world = worldsProvider().firstOrNull { it.folderName == payload.folderName }

        // Stop any existing remote poller
        remotePoller?.stop()
        remotePoller = null
        pterodactylClient?.close()
        pterodactylClient = null
        isRemoteWorld = false
        remoteServerId = null
        remoteWorldPath = null

        // Check if this is a remote (Pterodactyl) world
        if (world?.sourcePath?.startsWith("ptero://") == true) {
            return handleSelectRemoteWorld(message, world)
        }

        val worldDir = if (world?.sourcePath?.isNotEmpty() == true) {
            File(world.sourcePath)
        } else {
            // Fallback: search all save dirs
            SaveDetector.allSavesDirs()
                .map { File(it, payload.folderName) }
                .firstOrNull { it.isDirectory && File(it, "level.dat").exists() }
        }

        if (worldDir == null || !worldDir.isDirectory) {
            Log.w(TAG, "SELECT_WORLD failed: '${payload.folderName}' not found")
            return errorResponse(message.requestId, ErrorCode.WORLD_NOT_FOUND, "World not found: ${payload.folderName}")
        }

        selectedWorldDir = worldDir
        cachedContainers = null // Invalidate cache
        cachedPlayerUuid = null
        cachedPets = null
        Log.i(TAG, "Selected world: ${worldDir.absolutePath}")

        // Return world list with confirmation
        return worldListMessage()
    }

    /** Handle selecting a remote Pterodactyl world. */
    private suspend fun handleSelectRemoteWorld(message: SpyglassMessage, world: WorldInfo): SpyglassMessage {
        val uri = world.sourcePath // ptero://{serverId}/{worldPath}
        val withoutScheme = uri.removePrefix("ptero://")
        val serverId = withoutScheme.substringBefore("/")
        val worldPath = "/" + withoutScheme.substringAfter("/", "")

        val config = ConfigStore.load().pterodactyl
        if (!config.enabled || config.apiKey.isBlank()) {
            return errorResponse(message.requestId, ErrorCode.WORLD_NOT_FOUND, "Pterodactyl not configured")
        }

        try {
            val client = PterodactylClient(config.panelUrl, config.apiKey)
            pterodactylClient = client

            // Materialize the world (download essential files to local cache)
            Log.i(TAG, "Materializing remote world ptero://$serverId$worldPath...")
            val cacheDir = remoteWorldCache.materializeWorld(client, serverId, worldPath)

            selectedWorldDir = cacheDir
            isRemoteWorld = true
            remoteServerId = serverId
            remoteWorldPath = worldPath
            cachedContainers = null
            cachedPlayerUuid = null
            cachedPets = null

            // Start polling for changes
            if (scope != null && onWorldChanged != null) {
                val poller = RemoteWorldPoller(scope) { changes ->
                    invalidateCache()
                    onWorldChanged.invoke(changes)
                }
                poller.start(client, remoteWorldCache, serverId, worldPath)
                remotePoller = poller
            }

            Log.i(TAG, "Selected remote world: $cacheDir (from ptero://$serverId$worldPath)")
            return worldListMessage()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to materialize remote world", e)
            return errorResponse(message.requestId, ErrorCode.WORLD_NOT_FOUND, "Failed to load remote world: ${e.message}")
        }
    }

    private fun handleRequestPlayerList(message: SpyglassMessage): SpyglassMessage {
        val worldDir = selectedWorldDir
            ?: return errorResponse(message.requestId, ErrorCode.NO_WORLD, "No world selected").also {
                Log.w(TAG, "REQUEST_PLAYER_LIST failed: no world selected")
            }

        val players = PlayerParser.listPlayers(worldDir)
        Log.i(TAG, "REQUEST_PLAYER_LIST: ${players.size} players in ${worldDir.name}")

        val payload = PlayerListPayload(worldName = worldDir.name, players = players)
        return SpyglassMessage(
            type = MessageType.PLAYER_LIST,
            requestId = message.requestId,
            payload = json.encodeToJsonElement(payload),
        )
    }

    private fun handleRequestPlayer(message: SpyglassMessage): SpyglassMessage {
        val worldDir = selectedWorldDir
            ?: return errorResponse(message.requestId, ErrorCode.NO_WORLD, "No world selected").also {
                Log.w(TAG, "REQUEST_PLAYER failed: no world selected")
            }

        // Check if a specific player UUID was requested
        val requestedUuid = try {
            val req = json.decodeFromJsonElement(RequestPlayerPayload.serializer(), message.payload)
            req.playerUuid
        } catch (_: Exception) {
            null // Backwards-compatible: no payload means default player
        }

        val playerData = PlayerParser.parseByUuid(worldDir, requestedUuid)
            ?: return errorResponse(message.requestId, ErrorCode.NO_PLAYER, "No player data found").also {
                Log.w(TAG, "REQUEST_PLAYER failed: no player found for uuid=$requestedUuid in $worldDir")
            }
        cachedPlayerUuid = playerData.playerUuid
        Log.i(TAG, "REQUEST_PLAYER success: ${playerData.worldName}, player=${playerData.playerName}, health=${playerData.health}, inv=${playerData.inventory.size} items")

        return SpyglassMessage(
            type = MessageType.PLAYER_DATA,
            requestId = message.requestId,
            payload = json.encodeToJsonElement(playerData),
        )
    }

    private suspend fun handleRequestChests(
        message: SpyglassMessage,
        sendIntermediate: (suspend (SpyglassMessage) -> Unit)? = null,
    ): SpyglassMessage {
        val worldDir = selectedWorldDir
            ?: return errorResponse(message.requestId, ErrorCode.NO_WORLD, "No world selected")

        // For remote worlds, ensure region files are downloaded first
        // Always invalidate container cache — region files may have changed on the server
        if (isRemoteWorld && remoteServerId != null && remoteWorldPath != null) {
            val client = pterodactylClient ?: return errorResponse(message.requestId, ErrorCode.NO_WORLD, "Pterodactyl client not available")
            Log.i(TAG, "Downloading region files for chest scan...")
            remoteWorldCache.ensureRegionFiles(client, remoteServerId!!, remoteWorldPath!!, "overworld")
            cachedContainers = null
        }

        // Use cached containers or scan fresh
        val containers = cachedContainers ?: run {
            Log.i(TAG, "Scanning chests in ${worldDir.name}...")
            ChestScanner.scanWorld(worldDir) { dimension, current, total, regionFile, containersFound ->
                sendIntermediate?.invoke(SpyglassMessage(
                    type = MessageType.SCAN_PROGRESS,
                    payload = json.encodeToJsonElement(ScanProgressPayload(
                        dimension = dimension,
                        currentRegion = current,
                        totalRegions = total,
                        regionFile = regionFile,
                        containersFound = containersFound,
                    )),
                ))
            }.also {
                Log.i(TAG, "Found ${it.size} containers")
                cachedContainers = it
                searchIndex.build(it)
            }
        }

        Log.i(TAG, "REQUEST_CHESTS: ${containers.size} containers, ${containers.sumOf { it.items.size }} item stacks")
        val payload = ChestContentsPayload(
            worldName = worldDir.name,
            containers = containers,
            totalContainers = containers.size,
            totalItemStacks = containers.sumOf { it.items.size },
        )

        return SpyglassMessage(
            type = MessageType.CHEST_CONTENTS,
            requestId = message.requestId,
            payload = json.encodeToJsonElement(payload),
        )
    }

    private suspend fun handleRequestStructures(message: SpyglassMessage): SpyglassMessage {
        val worldDir = selectedWorldDir
            ?: return errorResponse(message.requestId, ErrorCode.NO_WORLD, "No world selected")

        // For remote worlds, ensure region files are downloaded
        if (isRemoteWorld && remoteServerId != null && remoteWorldPath != null) {
            val client = pterodactylClient ?: return errorResponse(message.requestId, ErrorCode.NO_WORLD, "Pterodactyl client not available")
            Log.i(TAG, "Downloading region files for structure scan...")
            remoteWorldCache.ensureRegionFiles(client, remoteServerId!!, remoteWorldPath!!, "overworld")
        }

        val structures = StructureScanner.scanWorld(worldDir)
        val payload = StructureLocationsPayload(
            worldName = worldDir.name,
            structures = structures,
        )

        return SpyglassMessage(
            type = MessageType.STRUCTURE_LOCATIONS,
            requestId = message.requestId,
            payload = json.encodeToJsonElement(payload),
        )
    }

    private suspend fun handleRequestMap(message: SpyglassMessage): SpyglassMessage {
        val worldDir = selectedWorldDir
            ?: return errorResponse(message.requestId, ErrorCode.NO_WORLD, "No world selected")

        val req = json.decodeFromJsonElement(RequestMapPayload.serializer(), message.payload)

        // For remote worlds, ensure region files are downloaded for the requested dimension
        if (isRemoteWorld && remoteServerId != null && remoteWorldPath != null) {
            val client = pterodactylClient ?: return errorResponse(message.requestId, ErrorCode.NO_WORLD, "Pterodactyl client not available")
            val dimension = req.dimension
            Log.i(TAG, "Downloading region files for map render ($dimension)...")
            remoteWorldCache.ensureRegionFiles(client, remoteServerId!!, remoteWorldPath!!, dimension)
        }

        val tiles = MapRenderer.renderTiles(worldDir, req.centerX, req.centerZ, req.radiusChunks, req.dimension)

        val playerData = PlayerParser.parse(worldDir)
        val payload = MapRenderPayload(
            worldName = worldDir.name,
            tiles = tiles,
            playerX = playerData?.posX ?: 0.0,
            playerZ = playerData?.posZ ?: 0.0,
        )

        return SpyglassMessage(
            type = MessageType.MAP_RENDER,
            requestId = message.requestId,
            payload = json.encodeToJsonElement(payload),
        )
    }

    private suspend fun handleSearchItems(message: SpyglassMessage): SpyglassMessage {
        val worldDir = selectedWorldDir
            ?: return errorResponse(message.requestId, ErrorCode.NO_WORLD, "No world selected")

        // Build index if not already done
        if (searchIndex.uniqueItemCount == 0) {
            val containers = cachedContainers ?: ChestScanner.scanWorld(worldDir).also {
                cachedContainers = it
            }
            searchIndex.build(containers)
        }

        val req = json.decodeFromJsonElement(SearchItemsPayload.serializer(), message.payload)
        val results = searchIndex.search(req.query, req.maxResults)

        val payload = SearchResultsPayload(query = req.query, results = results)

        return SpyglassMessage(
            type = MessageType.SEARCH_RESULTS,
            requestId = message.requestId,
            payload = json.encodeToJsonElement(payload),
        )
    }

    private fun handleRequestStats(message: SpyglassMessage): SpyglassMessage {
        val worldDir = selectedWorldDir
            ?: return errorResponse(message.requestId, ErrorCode.NO_WORLD, "No world selected")

        val uuid = cachedPlayerUuid ?: resolvePlayerUuid(worldDir)
            ?: return errorResponse(message.requestId, ErrorCode.NO_PLAYER, "No player UUID found")

        val categories = StatsParser.parse(worldDir, uuid)
        val payload = PlayerStatsPayload(worldName = worldDir.name, categories = categories)

        return SpyglassMessage(
            type = MessageType.PLAYER_STATS,
            requestId = message.requestId,
            payload = json.encodeToJsonElement(payload),
        )
    }

    private fun handleRequestAdvancements(message: SpyglassMessage): SpyglassMessage {
        val worldDir = selectedWorldDir
            ?: return errorResponse(message.requestId, ErrorCode.NO_WORLD, "No world selected")

        val uuid = cachedPlayerUuid ?: resolvePlayerUuid(worldDir)
            ?: return errorResponse(message.requestId, ErrorCode.NO_PLAYER, "No player UUID found")

        val advancements = AdvancementParser.parse(worldDir, uuid)
        val payload = PlayerAdvancementsPayload(worldName = worldDir.name, advancements = advancements)

        return SpyglassMessage(
            type = MessageType.PLAYER_ADVANCEMENTS,
            requestId = message.requestId,
            payload = json.encodeToJsonElement(payload),
        )
    }

    private suspend fun handleRequestPets(message: SpyglassMessage): SpyglassMessage {
        val worldDir = selectedWorldDir
            ?: return errorResponse(message.requestId, ErrorCode.NO_WORLD, "No world selected")

        // For remote worlds, ensure entity files are downloaded first
        if (isRemoteWorld && remoteServerId != null && remoteWorldPath != null) {
            val client = pterodactylClient ?: return errorResponse(message.requestId, ErrorCode.NO_WORLD, "Pterodactyl client not available")
            Log.i(TAG, "Downloading entity files for pet scan...")
            remoteWorldCache.ensureRegionFiles(client, remoteServerId!!, remoteWorldPath!!, "overworld")
        }

        val pets = cachedPets ?: run {
            Log.i(TAG, "Scanning entities for pets in ${worldDir.name}...")
            val scanned = EntityScanner.scanWorld(worldDir)
            cachedPets = scanned
            scanned
        }

        val payload = PetsListPayload(worldName = worldDir.name, pets = pets)

        return SpyglassMessage(
            type = MessageType.PETS_LIST,
            requestId = message.requestId,
            payload = json.encodeToJsonElement(payload),
        )
    }

    /** Resolve player UUID from world dir (fallback when not cached from player data request). */
    private fun resolvePlayerUuid(worldDir: File): String? {
        // Try playerdata directory first (multiplayer)
        val playerDataDir = File(worldDir, "playerdata")
        if (playerDataDir.isDirectory) {
            val datFile = playerDataDir.listFiles { f -> f.extension == "dat" }
                ?.maxByOrNull { it.lastModified() }
            datFile?.nameWithoutExtension?.takeIf { it.contains("-") }?.let { return it }
        }
        // Fall back to PlayerParser to extract UUID from singleplayer level.dat
        return PlayerParser.parse(worldDir)?.playerUuid
    }

    private fun errorResponse(requestId: String, code: String, message: String): SpyglassMessage {
        return SpyglassMessage(
            type = MessageType.ERROR,
            requestId = requestId,
            payload = json.encodeToJsonElement(ErrorPayload(code, message)),
        )
    }

    private fun handleDeviceLog(message: SpyglassMessage) {
        try {
            val payload = json.decodeFromJsonElement(DeviceLogPayload.serializer(), message.payload)
            val logDir = File(System.getProperty("user.home"), ".spyglass-connect/device-logs")
            logDir.mkdirs()

            val logFile = File(logDir, "device.log")

            // Rotate if over 2 MB
            if (logFile.exists() && logFile.length() > 2 * 1024 * 1024) {
                val old = File(logDir, "device.log.old")
                old.delete()
                logFile.renameTo(old)
            }

            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
            logFile.appendText(buildString {
                for (entry in payload.entries) {
                    val ts = dateFormat.format(java.util.Date(entry.timestamp))
                    append("[$ts] ${entry.level}/${entry.tag}: ${entry.message}\n")
                    if (entry.throwable != null) {
                        // Indent throwable cause chain for readability
                        for (line in entry.throwable.lines()) {
                            if (line.isNotBlank()) append("  $line\n")
                        }
                    }
                }
            })

            val errorCount = payload.entries.count { it.level == "E" || it.level == "WTF" }
            val warnCount = payload.entries.count { it.level == "W" }
            if (errorCount > 0 || warnCount > 0) {
                Log.w(TAG, "Received device logs: $errorCount errors, $warnCount warnings — saved to ${logFile.absolutePath}")
            } else {
                Log.d(TAG, "Received ${payload.entries.size} device log entries")
            }

            // Update the observable log count for UI notification
            _deviceLogCount.value += payload.entries.size
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle device log", e)
        }
    }

    /** Observable count of received device log entries (for UI notification). */
    private val _deviceLogCount = kotlinx.coroutines.flow.MutableStateFlow(0)
    val deviceLogCount: kotlinx.coroutines.flow.StateFlow<Int> = _deviceLogCount

    /** Invalidate cached data (called when file watcher detects changes). */
    fun invalidateCache() {
        cachedContainers = null
        cachedPlayerUuid = null
        cachedPets = null
        searchIndex.clear()
    }
}
