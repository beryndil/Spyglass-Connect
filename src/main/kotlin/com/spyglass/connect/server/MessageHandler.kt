package com.spyglass.connect.server

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

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var selectedWorldDir: File? = null
    private var cachedContainers: List<ContainerInfo>? = null

    /** Handle an incoming message and return a response (or null for no response). */
    fun handle(message: SpyglassMessage): SpyglassMessage? {
        return when (message.type) {
            MessageType.SELECT_WORLD -> handleSelectWorld(message)
            MessageType.REQUEST_PLAYER -> handleRequestPlayer(message)
            MessageType.REQUEST_CHESTS -> handleRequestChests(message)
            MessageType.REQUEST_STRUCTURES -> handleRequestStructures(message)
            MessageType.REQUEST_MAP -> handleRequestMap(message)
            MessageType.SEARCH_ITEMS -> handleSearchItems(message)
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
            return errorResponse(message.requestId, "world_not_found", "World not found: ${payload.folderName}")
        }

        selectedWorldDir = worldDir
        cachedContainers = null // Invalidate cache

        // Return world list with confirmation
        return worldListMessage()
    }

    private fun handleRequestPlayer(message: SpyglassMessage): SpyglassMessage {
        val worldDir = selectedWorldDir
            ?: return errorResponse(message.requestId, "no_world", "No world selected").also {
                println("[MessageHandler] REQUEST_PLAYER failed: no world selected")
            }

        val playerData = PlayerParser.parse(worldDir)
            ?: return errorResponse(message.requestId, "no_player", "No player data found in $worldDir").also {
                println("[MessageHandler] REQUEST_PLAYER failed: PlayerParser.parse returned null for $worldDir")
            }
        println("[MessageHandler] REQUEST_PLAYER success: ${playerData.worldName}, health=${playerData.health}, inv=${playerData.inventory.size} items")

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
        val containers = cachedContainers ?: ChestScanner.scanWorld(worldDir).also {
            cachedContainers = it
            searchIndex.build(it)
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
        searchIndex.clear()
    }
}
