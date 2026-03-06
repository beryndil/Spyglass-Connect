package com.spyglass.connect.server

import com.spyglass.connect.Log
import com.spyglass.connect.minecraft.*
import com.spyglass.connect.model.*
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
) {

    companion object {
        private const val TAG = "Handler"
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var selectedWorldDir: File? = null
    private var cachedContainers: List<ContainerInfo>? = null

    /** Cached player UUID from the last player data request (used for stats/advancements). */
    private var cachedPlayerUuid: String? = null

    /** Handle an incoming message and return a response (or null for no response). */
    fun handle(message: SpyglassMessage): SpyglassMessage? {
        Log.d(TAG, "Handling ${message.type}")
        return when (message.type) {
            MessageType.SELECT_WORLD -> handleSelectWorld(message)
            MessageType.REQUEST_PLAYER_LIST -> handleRequestPlayerList(message)
            MessageType.REQUEST_PLAYER -> handleRequestPlayer(message)
            MessageType.REQUEST_CHESTS -> handleRequestChests(message)
            MessageType.REQUEST_STRUCTURES -> handleRequestStructures(message)
            MessageType.REQUEST_MAP -> handleRequestMap(message)
            MessageType.SEARCH_ITEMS -> handleSearchItems(message)
            MessageType.REQUEST_STATS -> handleRequestStats(message)
            MessageType.REQUEST_ADVANCEMENTS -> handleRequestAdvancements(message)
            else -> errorResponse(message.requestId, "unknown_type", "Unknown message type: ${message.type}")
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

    private fun handleSelectWorld(message: SpyglassMessage): SpyglassMessage {
        val payload = json.decodeFromJsonElement(SelectWorldPayload.serializer(), message.payload)

        // Find the world across all configured save directories
        val world = worldsProvider().firstOrNull { it.folderName == payload.folderName }

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
            return errorResponse(message.requestId, "world_not_found", "World not found: ${payload.folderName}")
        }

        selectedWorldDir = worldDir
        cachedContainers = null // Invalidate cache
        cachedPlayerUuid = null
        Log.i(TAG, "Selected world: ${worldDir.absolutePath}")

        // Return world list with confirmation
        return worldListMessage()
    }

    private fun handleRequestPlayerList(message: SpyglassMessage): SpyglassMessage {
        val worldDir = selectedWorldDir
            ?: return errorResponse(message.requestId, "no_world", "No world selected").also {
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
            ?: return errorResponse(message.requestId, "no_world", "No world selected").also {
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
            ?: return errorResponse(message.requestId, "no_player", "No player data found").also {
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

    private fun handleRequestChests(message: SpyglassMessage): SpyglassMessage {
        val worldDir = selectedWorldDir
            ?: return errorResponse(message.requestId, "no_world", "No world selected")

        // Use cached containers or scan fresh
        val containers = cachedContainers ?: run {
            Log.i(TAG, "Scanning chests in ${worldDir.name}...")
            ChestScanner.scanWorld(worldDir).also {
                Log.i(TAG, "Found ${it.size} containers")
                cachedContainers = it
                searchIndex.build(it)
            }
        }

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

    private fun handleRequestStructures(message: SpyglassMessage): SpyglassMessage {
        val worldDir = selectedWorldDir
            ?: return errorResponse(message.requestId, "no_world", "No world selected")

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

    private fun handleRequestMap(message: SpyglassMessage): SpyglassMessage {
        val worldDir = selectedWorldDir
            ?: return errorResponse(message.requestId, "no_world", "No world selected")

        val req = json.decodeFromJsonElement(RequestMapPayload.serializer(), message.payload)
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

    private fun handleSearchItems(message: SpyglassMessage): SpyglassMessage {
        val worldDir = selectedWorldDir
            ?: return errorResponse(message.requestId, "no_world", "No world selected")

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
            ?: return errorResponse(message.requestId, "no_world", "No world selected")

        val uuid = cachedPlayerUuid ?: resolvePlayerUuid(worldDir)
            ?: return errorResponse(message.requestId, "no_player", "No player UUID found")

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
            ?: return errorResponse(message.requestId, "no_world", "No world selected")

        val uuid = cachedPlayerUuid ?: resolvePlayerUuid(worldDir)
            ?: return errorResponse(message.requestId, "no_player", "No player UUID found")

        val advancements = AdvancementParser.parse(worldDir, uuid)
        val payload = PlayerAdvancementsPayload(worldName = worldDir.name, advancements = advancements)

        return SpyglassMessage(
            type = MessageType.PLAYER_ADVANCEMENTS,
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

    /** Invalidate cached data (called when file watcher detects changes). */
    fun invalidateCache() {
        cachedContainers = null
        cachedPlayerUuid = null
        searchIndex.clear()
    }
}
