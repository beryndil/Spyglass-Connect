package com.spyglass.connect.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * Wire-format message envelope for all Spyglass Connect communication.
 * Both desktop → phone and phone → desktop use this same wrapper.
 */
@Serializable
data class SpyglassMessage(
    val type: String,
    val requestId: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val payload: JsonElement = JsonNull,
)

// ── Message types ───────────────────────────────────────────────────────────

object MessageType {
    // Desktop → Phone
    const val WORLD_LIST = "world_list"
    const val PLAYER_LIST = "player_list"
    const val PLAYER_DATA = "player_data"
    const val CHEST_CONTENTS = "chest_contents"
    const val STRUCTURE_LOCATIONS = "structure_locations"
    const val MAP_RENDER = "map_render"
    const val SEARCH_RESULTS = "search_results"
    const val WORLD_CHANGED = "world_changed"
    const val ERROR = "error"

    const val PLAYER_STATS = "player_stats"
    const val PLAYER_ADVANCEMENTS = "player_advancements"
    const val PETS_LIST = "pets_list"

    // Phone → Desktop
    const val SELECT_WORLD = "select_world"
    const val REQUEST_PLAYER_LIST = "request_player_list"
    const val REQUEST_PLAYER = "request_player"
    const val REQUEST_CHESTS = "request_chests"
    const val REQUEST_STRUCTURES = "request_structures"
    const val REQUEST_MAP = "request_map"
    const val SEARCH_ITEMS = "search_items"
    const val REQUEST_STATS = "request_stats"
    const val REQUEST_ADVANCEMENTS = "request_advancements"
    const val REQUEST_PETS = "request_pets"
    const val DEVICE_LOG = "device_log"

    // Pairing
    const val PAIR_REQUEST = "pair_request"
    const val PAIR_ACCEPT = "pair_accept"
}

// ── Payload data classes (Desktop → Phone) ──────────────────────────────────

@Serializable
data class WorldInfo(
    val folderName: String,
    val displayName: String,
    val gameMode: String = "survival",
    val difficulty: String = "normal",
    val lastPlayed: Long = 0,
    val seed: Long = 0,
    val dataVersion: Int = 0,
    val sourcePath: String = "",
    val sourceLabel: String = "Default",
    val isModded: Boolean = false,
    val modLoader: String? = null,
)

@Serializable
data class WorldListPayload(
    val worlds: List<WorldInfo>,
)

@Serializable
data class PlayerSummary(
    val uuid: String,
    val name: String?,
    val lastPlayed: Long = 0,
    val isOwner: Boolean = false,
    val health: Float = 20f,
    val foodLevel: Int = 20,
    val xpLevel: Int = 0,
    val dimension: String = "overworld",
    val posX: Double = 0.0,
    val posY: Double = 0.0,
    val posZ: Double = 0.0,
)

@Serializable
data class PlayerListPayload(
    val worldName: String,
    val players: List<PlayerSummary>,
)

@Serializable
data class EnchantmentData(val id: String, val level: Int)

@Serializable
data class ItemStack(
    val id: String,
    val count: Int = 1,
    val slot: Int = -1,
    val enchantments: List<EnchantmentData> = emptyList(),
    val damage: Int = 0,
    val customName: String? = null,
)

@Serializable
data class LocationData(
    val x: Int,
    val y: Int,
    val z: Int,
    val dimension: String = "overworld",
)

@Serializable
data class ActiveEffect(
    val id: String,
    val amplifier: Int = 0,
    val duration: Int = 0,
    val ambient: Boolean = false,
    val showParticles: Boolean = true,
    val showIcon: Boolean = true,
)

@Serializable
data class PlayerData(
    val worldName: String,
    val health: Float = 20f,
    val foodLevel: Int = 20,
    val xpLevel: Int = 0,
    val xpProgress: Float = 0f,
    val posX: Double = 0.0,
    val posY: Double = 0.0,
    val posZ: Double = 0.0,
    val dimension: String = "overworld",
    val inventory: List<ItemStack> = emptyList(),
    val armor: List<ItemStack> = emptyList(),
    val offhand: ItemStack? = null,
    val enderChest: List<ItemStack> = emptyList(),
    val playerUuid: String? = null,
    val playerName: String? = null,
    val selectedSlot: Int = 0,
    val lastDeathLocation: LocationData? = null,
    val spawnLocation: LocationData? = null,
    val spawnForced: Boolean = false,
    val activeEffects: List<ActiveEffect> = emptyList(),
    val worldSpawn: LocationData? = null,
)

@Serializable
data class ContainerInfo(
    val type: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val dimension: String = "overworld",
    val customName: String? = null,
    val items: List<ItemStack> = emptyList(),
)

@Serializable
data class ChestContentsPayload(
    val worldName: String,
    val containers: List<ContainerInfo>,
    val totalContainers: Int = 0,
    val totalItemStacks: Int = 0,
)

@Serializable
data class StructureLocation(
    val type: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val dimension: String = "overworld",
)

@Serializable
data class StructureLocationsPayload(
    val worldName: String,
    val structures: List<StructureLocation>,
)

@Serializable
data class MapTile(
    val chunkX: Int,
    val chunkZ: Int,
    val dimension: String = "overworld",
    val imageBase64: String,
)

@Serializable
data class MapRenderPayload(
    val worldName: String,
    val tiles: List<MapTile>,
    val playerX: Double = 0.0,
    val playerZ: Double = 0.0,
)

@Serializable
data class SearchHit(
    val itemId: String,
    val totalCount: Int,
    val locations: List<ContainerInfo>,
)

@Serializable
data class SearchResultsPayload(
    val query: String,
    val results: List<SearchHit>,
)

@Serializable
data class WorldChangedPayload(
    val worldName: String,
    val changedCategories: List<String> = emptyList(),
)

@Serializable
data class ErrorPayload(
    val code: String,
    val message: String,
)

// ── Stats + Advancements payloads ───────────────────────────────────────────

@Serializable
data class StatEntry(val key: String, val value: Long)

@Serializable
data class StatCategory(val category: String, val entries: List<StatEntry>)

@Serializable
data class PlayerStatsPayload(val worldName: String, val categories: List<StatCategory>)

@Serializable
data class AdvancementStatus(
    val id: String,
    val done: Boolean,
    val criteria: Map<String, String> = emptyMap(),
)

@Serializable
data class PlayerAdvancementsPayload(
    val worldName: String,
    val advancements: List<AdvancementStatus>,
)

// ── Pets payloads ────────────────────────────────────────────────────────────

@Serializable
data class PetData(
    val entityType: String,
    val customName: String? = null,
    val health: Float = 0f,
    val maxHealth: Float = 0f,
    val posX: Double = 0.0,
    val posY: Double = 0.0,
    val posZ: Double = 0.0,
    val dimension: String = "overworld",
    val ownerUuid: String? = null,
    val ownerName: String? = null,
    val collarColor: Int = -1,
    val catVariant: String? = null,
    val horseSpeed: Double = 0.0,
    val horseJump: Double = 0.0,
)

@Serializable
data class PetsListPayload(
    val worldName: String,
    val pets: List<PetData>,
)

// ── Payload data classes (Phone → Desktop) ──────────────────────────────────

@Serializable
data class SelectWorldPayload(
    val folderName: String,
)

@Serializable
data class RequestPlayerPayload(
    val playerUuid: String? = null,
)

@Serializable
data class RequestMapPayload(
    val centerX: Int = 0,
    val centerZ: Int = 0,
    val radiusChunks: Int = 8,
    val dimension: String = "overworld",
)

@Serializable
data class SearchItemsPayload(
    val query: String,
    val maxResults: Int = 50,
)

// ── Device Log payloads ─────────────────────────────────────────────────────

@Serializable
data class DeviceLogEntry(
    val timestamp: Long,
    val level: String,
    val tag: String,
    val message: String,
    val throwable: String? = null,
)

@Serializable
data class DeviceLogPayload(
    val entries: List<DeviceLogEntry>,
)

// ── Pairing ─────────────────────────────────────────────────────────────────

@Serializable
data class QrPairingData(
    val app: String = "spyglass-connect",
    val version: Int = 1,
    val ip: String,
    val port: Int,
    val pubkey: String,
    val nonce: String,
)

@Serializable
data class PairRequestPayload(
    val deviceName: String,
    val pubkey: String,
)

@Serializable
data class PairAcceptPayload(
    val deviceName: String,
    val accepted: Boolean = true,
    val pubkey: String? = null,
)
