package com.spyglass.connect.minecraft

import com.spyglass.connect.model.ActiveEffect
import com.spyglass.connect.model.EnchantmentData
import com.spyglass.connect.model.ItemStack
import com.spyglass.connect.model.LocationData
import com.spyglass.connect.model.PlayerData
import com.spyglass.connect.model.PlayerSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.querz.nbt.tag.CompoundTag
import net.querz.nbt.tag.IntArrayTag
import net.querz.nbt.tag.ListTag
import net.querz.nbt.tag.ShortTag
import net.querz.nbt.tag.StringTag
import java.io.File

/**
 * Parse player inventory, health, position, etc. from Minecraft save data.
 *
 * Singleplayer: inventory in level.dat → Data → Player
 * Multiplayer: inventory in playerdata/<uuid>.dat
 */
object PlayerParser {

    private val DIMENSION_MAP = mapOf(
        "minecraft:overworld" to "overworld",
        "minecraft:the_nether" to "the_nether",
        "minecraft:the_end" to "the_end",
    )

    private val jsonParser = Json { ignoreUnknownKeys = true }

    /** List all players in a world (owner + anyone in playerdata/). Includes basic stats. */
    fun listPlayers(worldDir: File): List<PlayerSummary> {
        val players = mutableListOf<PlayerSummary>()
        val levelDat = File(worldDir, "level.dat")
        val root = NbtHelper.readCompressed(levelDat) ?: return emptyList()
        val data = NbtHelper.compound(root, "Data") ?: return emptyList()

        // Owner from level.dat → Data → Player
        val ownerCompound = NbtHelper.compound(data, "Player")
        val ownerUuid = ownerCompound?.let { extractUuid(it) }

        // All players from playerdata/
        val playerDataDir = File(worldDir, "playerdata")
        if (playerDataDir.isDirectory) {
            playerDataDir.listFiles { f -> f.extension == "dat" }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { datFile ->
                    val uuid = datFile.nameWithoutExtension.takeIf { it.contains("-") } ?: return@forEach
                    val name = resolvePlayerName(uuid, worldDir)
                    val playerTag = NbtHelper.readCompressed(datFile)
                    val pos = playerTag?.let { extractPosition(it) } ?: Triple(0.0, 0.0, 0.0)
                    val dim = playerTag?.let { extractDimension(it) } ?: "overworld"
                    players.add(PlayerSummary(
                        uuid = uuid,
                        name = name,
                        lastPlayed = datFile.lastModified(),
                        isOwner = uuid.equals(ownerUuid, ignoreCase = true),
                        health = playerTag?.let { NbtHelper.float(it, "Health", 20f) } ?: 20f,
                        foodLevel = playerTag?.let { NbtHelper.int(it, "foodLevel", 20) } ?: 20,
                        xpLevel = playerTag?.let { NbtHelper.int(it, "XpLevel") } ?: 0,
                        dimension = dim,
                        posX = pos.first,
                        posY = pos.second,
                        posZ = pos.third,
                    ))
                }
        }

        // If owner wasn't in playerdata (pure singleplayer), add from level.dat
        if (ownerUuid != null && players.none { it.uuid.equals(ownerUuid, ignoreCase = true) }) {
            val name = resolvePlayerName(ownerUuid, worldDir)
            val pos = ownerCompound?.let { extractPosition(it) } ?: Triple(0.0, 0.0, 0.0)
            val dim = ownerCompound?.let { extractDimension(it) } ?: "overworld"
            players.add(0, PlayerSummary(
                uuid = ownerUuid,
                name = name,
                lastPlayed = levelDat.lastModified(),
                isOwner = true,
                health = ownerCompound?.let { NbtHelper.float(it, "Health", 20f) } ?: 20f,
                foodLevel = ownerCompound?.let { NbtHelper.int(it, "foodLevel", 20) } ?: 20,
                xpLevel = ownerCompound?.let { NbtHelper.int(it, "XpLevel") } ?: 0,
                dimension = dim,
                posX = pos.first,
                posY = pos.second,
                posZ = pos.third,
            ))
        }

        return players
    }

    /** Parse a specific player by UUID. Falls back to owner if UUID is null. */
    fun parseByUuid(worldDir: File, playerUuid: String?): PlayerData? {
        val levelDat = File(worldDir, "level.dat")
        val root = NbtHelper.readCompressed(levelDat) ?: return null
        val data = NbtHelper.compound(root, "Data") ?: return null
        val worldName = NbtHelper.string(data, "LevelName", worldDir.name)
        val worldSpawn = extractWorldSpawn(data)

        // If no UUID specified, use the default parse (owner first)
        if (playerUuid == null) return parse(worldDir)

        // Check if the owner matches the requested UUID
        NbtHelper.compound(data, "Player")?.let { player ->
            val ownerUuid = extractUuid(player)
            if (ownerUuid.equals(playerUuid, ignoreCase = true)) {
                val playerName = resolvePlayerName(playerUuid, worldDir)
                return parsePlayerCompound(player, worldName, ownerUuid, playerName, worldSpawn)
            }
        }

        // Look in playerdata/
        val datFile = File(worldDir, "playerdata/$playerUuid.dat")
        if (datFile.exists()) {
            val playerRoot = NbtHelper.readCompressed(datFile) ?: return null
            val playerName = resolvePlayerName(playerUuid, worldDir)
            return parsePlayerCompound(playerRoot, worldName, playerUuid, playerName, worldSpawn)
        }

        return null
    }

    /** Parse player data from a world directory. Tries singleplayer first, then playerdata. */
    fun parse(worldDir: File): PlayerData? {
        // Try singleplayer (level.dat → Data → Player)
        val levelDat = File(worldDir, "level.dat")
        val root = NbtHelper.readCompressed(levelDat) ?: return null
        val data = NbtHelper.compound(root, "Data") ?: return null
        val worldName = NbtHelper.string(data, "LevelName", worldDir.name)
        val worldSpawn = extractWorldSpawn(data)

        // Singleplayer player compound
        NbtHelper.compound(data, "Player")?.let { player ->
            val uuid = extractUuid(player)
            val playerName = uuid?.let { resolvePlayerName(it, worldDir) }
            return parsePlayerCompound(player, worldName, uuid, playerName, worldSpawn)
        }

        // Fall back to playerdata directory (multiplayer or dedicated server)
        val playerDataDir = File(worldDir, "playerdata")
        if (playerDataDir.isDirectory) {
            val datFiles = playerDataDir.listFiles { f -> f.extension == "dat" }
                ?.sortedByDescending { it.lastModified() }
            datFiles?.firstOrNull()?.let { datFile ->
                val playerRoot = NbtHelper.readCompressed(datFile) ?: return null
                val uuid = datFile.nameWithoutExtension.takeIf { it.contains("-") }
                val playerName = uuid?.let { resolvePlayerName(it, worldDir) }
                return parsePlayerCompound(playerRoot, worldName, uuid, playerName, worldSpawn)
            }
        }

        return null
    }

    /** Parse a player compound tag into PlayerData. */
    private fun parsePlayerCompound(
        player: CompoundTag,
        worldName: String,
        playerUuid: String? = null,
        playerName: String? = null,
        worldSpawn: LocationData? = null,
    ): PlayerData {
        val pos = extractPosition(player)
        val dimension = extractDimension(player)

        return PlayerData(
            worldName = worldName,
            health = NbtHelper.float(player, "Health", 20f),
            foodLevel = NbtHelper.int(player, "foodLevel", 20),
            xpLevel = NbtHelper.int(player, "XpLevel"),
            xpProgress = NbtHelper.float(player, "XpP"),
            posX = pos.first,
            posY = pos.second,
            posZ = pos.third,
            dimension = dimension,
            inventory = parseInventory(player),
            armor = parseArmor(player),
            offhand = parseOffhand(player),
            enderChest = parseEnderChest(player),
            playerUuid = playerUuid,
            playerName = playerName,
            selectedSlot = NbtHelper.int(player, "SelectedItemSlot"),
            lastDeathLocation = extractLastDeathLocation(player),
            spawnLocation = extractSpawnLocation(player),
            spawnForced = NbtHelper.boolean(player, "SpawnForced"),
            activeEffects = extractActiveEffects(player),
            worldSpawn = worldSpawn,
        )
    }

    /** Extract world spawn from level.dat Data root. Handles both old (SpawnX/Y/Z) and 1.21+ (spawn compound) formats. */
    private fun extractWorldSpawn(data: CompoundTag): LocationData? {
        // 1.21+ format: spawn compound with pos IntArray and dimension string
        NbtHelper.compound(data, "spawn")?.let { spawn ->
            val posArray = (spawn.get("pos") as? IntArrayTag)?.value
            if (posArray != null && posArray.size >= 3) {
                val dim = NbtHelper.string(spawn, "dimension", "minecraft:overworld")
                val dimension = DIMENSION_MAP[dim] ?: dim.removePrefix("minecraft:")
                return LocationData(x = posArray[0], y = posArray[1], z = posArray[2], dimension = dimension)
            }
        }
        // Legacy format: flat SpawnX/Y/Z ints
        if (!data.containsKey("SpawnX")) return null
        return LocationData(
            x = NbtHelper.int(data, "SpawnX"),
            y = NbtHelper.int(data, "SpawnY"),
            z = NbtHelper.int(data, "SpawnZ"),
            dimension = "overworld",
        )
    }

    /** Extract last death location from LastDeathLocation compound. */
    private fun extractLastDeathLocation(player: CompoundTag): LocationData? {
        val deathLoc = NbtHelper.compound(player, "LastDeathLocation") ?: return null
        val posArray = (deathLoc.get("pos") as? IntArrayTag)?.value ?: return null
        if (posArray.size < 3) return null
        val dim = NbtHelper.string(deathLoc, "dimension", "")
        val dimension = DIMENSION_MAP[dim] ?: dim.removePrefix("minecraft:")
        return LocationData(x = posArray[0], y = posArray[1], z = posArray[2], dimension = dimension)
    }

    /** Extract spawn location (bed/respawn anchor). Handles both old (SpawnX/Y/Z) and 1.21+ (respawn compound) formats. */
    private fun extractSpawnLocation(player: CompoundTag): LocationData? {
        // 1.21+ format: respawn compound with pos IntArray and dimension string
        NbtHelper.compound(player, "respawn")?.let { respawn ->
            val posArray = (respawn.get("pos") as? IntArrayTag)?.value
            if (posArray != null && posArray.size >= 3) {
                val dim = NbtHelper.string(respawn, "dimension", "minecraft:overworld")
                val dimension = DIMENSION_MAP[dim] ?: dim.removePrefix("minecraft:")
                return LocationData(x = posArray[0], y = posArray[1], z = posArray[2], dimension = dimension)
            }
        }
        // Legacy format: flat SpawnX/Y/Z ints
        if (!player.containsKey("SpawnX")) return null
        val x = NbtHelper.int(player, "SpawnX")
        val y = NbtHelper.int(player, "SpawnY")
        val z = NbtHelper.int(player, "SpawnZ")
        val dim = NbtHelper.string(player, "SpawnDimension", "minecraft:overworld")
        val dimension = DIMENSION_MAP[dim] ?: dim.removePrefix("minecraft:")
        return LocationData(x = x, y = y, z = z, dimension = dimension)
    }

    /**
     * Extract active status effects.
     * 1.20.5+: "active_effects" list with string "id"
     * Legacy: "ActiveEffects" list with numeric "Id"
     */
    private fun extractActiveEffects(player: CompoundTag): List<ActiveEffect> {
        @Suppress("UNCHECKED_CAST")
        val effectsList = (player.get("active_effects") as? ListTag<CompoundTag>)
            ?: (player.get("ActiveEffects") as? ListTag<CompoundTag>)
            ?: return emptyList()

        return (0 until effectsList.size()).mapNotNull { i ->
            val effect = effectsList[i]
            val id = resolveEffectId(effect) ?: return@mapNotNull null
            ActiveEffect(
                id = id,
                amplifier = NbtHelper.int(effect, "amplifier", NbtHelper.int(effect, "Amplifier")),
                duration = NbtHelper.int(effect, "duration", NbtHelper.int(effect, "Duration")),
                ambient = NbtHelper.boolean(effect, "ambient", NbtHelper.boolean(effect, "Ambient")),
                showParticles = NbtHelper.boolean(effect, "show_particles",
                    NbtHelper.boolean(effect, "ShowParticles", true)),
                showIcon = NbtHelper.boolean(effect, "show_icon",
                    NbtHelper.boolean(effect, "ShowIcon", true)),
            )
        }
    }

    /** Resolve effect ID from either string "id" (1.20.5+) or numeric "Id" (legacy). */
    private fun resolveEffectId(effect: CompoundTag): String? {
        val stringId = NbtHelper.string(effect, "id")
        if (stringId.isNotBlank()) return stringId.removePrefix("minecraft:")
        val numId = NbtHelper.int(effect, "Id", -1)
        return LEGACY_EFFECT_IDS[numId]
    }

    private val LEGACY_EFFECT_IDS = mapOf(
        1 to "speed", 2 to "slowness", 3 to "haste", 4 to "mining_fatigue",
        5 to "strength", 6 to "instant_health", 7 to "instant_damage",
        8 to "jump_boost", 9 to "nausea", 10 to "regeneration",
        11 to "resistance", 12 to "fire_resistance", 13 to "water_breathing",
        14 to "invisibility", 15 to "blindness", 16 to "night_vision",
        17 to "hunger", 18 to "weakness", 19 to "poison", 20 to "wither",
        21 to "health_boost", 22 to "absorption", 23 to "saturation",
        24 to "glowing", 25 to "levitation", 26 to "luck", 27 to "unluck",
        28 to "slow_falling", 29 to "conduit_power", 30 to "dolphins_grace",
        31 to "bad_omen", 32 to "hero_of_the_village", 33 to "darkness",
    )

    /** Extract UUID from a player compound (modern int-array or legacy long-pair format). */
    private fun extractUuid(player: CompoundTag): String? {
        // Modern format (1.16+): UUID stored as int array of 4 ints
        (player.get("UUID") as? IntArrayTag)?.let { tag ->
            val ints = tag.value
            if (ints.size == 4) {
                val most = (ints[0].toLong() shl 32) or (ints[1].toLong() and 0xFFFFFFFFL)
                val least = (ints[2].toLong() shl 32) or (ints[3].toLong() and 0xFFFFFFFFL)
                return java.util.UUID(most, least).toString()
            }
        }
        // Legacy format (pre-1.16): UUIDMost + UUIDLeast as longs
        val most = NbtHelper.long(player, "UUIDMost", 0L)
        val least = NbtHelper.long(player, "UUIDLeast", 0L)
        if (most != 0L || least != 0L) {
            return java.util.UUID(most, least).toString()
        }
        return null
    }

    /** Extract XYZ position from the Pos list tag. */
    private fun extractPosition(player: CompoundTag): Triple<Double, Double, Double> {
        @Suppress("UNCHECKED_CAST")
        val posList = player.get("Pos") as? ListTag<*> ?: return Triple(0.0, 0.0, 0.0)
        if (posList.size() < 3) return Triple(0.0, 0.0, 0.0)
        return Triple(
            posList[0].valueToString().toDoubleOrNull() ?: 0.0,
            posList[1].valueToString().toDoubleOrNull() ?: 0.0,
            posList[2].valueToString().toDoubleOrNull() ?: 0.0,
        )
    }

    /** Extract dimension string. Handles both old int and new string formats. */
    private fun extractDimension(player: CompoundTag): String {
        val dim = NbtHelper.string(player, "Dimension", "")
        if (dim.isNotEmpty()) return DIMENSION_MAP[dim] ?: dim.removePrefix("minecraft:")
        // Old format (pre-1.16): integer dimension
        return when (NbtHelper.int(player, "Dimension", 0)) {
            -1 -> "the_nether"
            1 -> "the_end"
            else -> "overworld"
        }
    }

    /** Parse main inventory (slots 0-35). */
    private fun parseInventory(player: CompoundTag): List<ItemStack> {
        return parseItemList(player, "Inventory") { slot -> slot in 0..35 }
    }

    /**
     * Parse armor slots.
     * Pre-1.21: slots 100-103 in Inventory list.
     * 1.21+: equipment compound with feet/legs/chest/head keys.
     */
    private fun parseArmor(player: CompoundTag): List<ItemStack> {
        // Try 1.21+ equipment compound first
        val equipment = NbtHelper.compound(player, "equipment")
        if (equipment != null) {
            val slotMap = mapOf("feet" to 100, "legs" to 101, "chest" to 102, "head" to 103)
            val items = mutableListOf<ItemStack>()
            for ((key, slot) in slotMap) {
                val itemTag = NbtHelper.compound(equipment, key) ?: continue
                parseItem(itemTag)?.let { items.add(it.copy(slot = slot)) }
            }
            if (items.isNotEmpty()) return items
        }
        // Fallback: pre-1.21 format (slots 100-103 in Inventory)
        return parseItemList(player, "Inventory") { slot -> slot in 100..103 }
    }

    /**
     * Parse offhand item.
     * Pre-1.21: slot -106 in Inventory list.
     * 1.21+: equipment compound with "offhand" key.
     */
    private fun parseOffhand(player: CompoundTag): ItemStack? {
        // Try 1.21+ equipment compound first
        val equipment = NbtHelper.compound(player, "equipment")
        if (equipment != null) {
            val itemTag = NbtHelper.compound(equipment, "offhand")
            if (itemTag != null) return parseItem(itemTag)?.copy(slot = -106)
        }
        // Fallback: pre-1.21 format
        return parseItemList(player, "Inventory") { slot -> slot == -106 }.firstOrNull()
    }

    /** Parse ender chest contents. */
    private fun parseEnderChest(player: CompoundTag): List<ItemStack> {
        return parseItemList(player, "EnderItems") { true }
    }

    /** Generic item list parser with slot filter. */
    private fun parseItemList(
        parent: CompoundTag,
        listKey: String,
        slotFilter: (Int) -> Boolean,
    ): List<ItemStack> {
        @Suppress("UNCHECKED_CAST")
        val items = parent.get(listKey) as? ListTag<CompoundTag> ?: return emptyList()
        return items.mapNotNull { item -> parseItem(item) }
            .filter { slotFilter(it.slot) }
    }

    /** Parse a single item compound into ItemStack. */
    private fun parseItem(item: CompoundTag): ItemStack? {
        val id = NbtHelper.string(item, "id").removePrefix("minecraft:")
        if (id.isBlank()) return null
        // 1.21+ uses lowercase "count" (IntTag); older uses "Count" (ByteTag)
        val count = maxOf(NbtHelper.int(item, "Count", 0), NbtHelper.int(item, "count", 0))
            .coerceAtLeast(1)
        val slot = NbtHelper.int(item, "Slot", -1)
        val enchantments = parseEnchantments(item)
        val damage = extractDamage(item)
        val customName = extractCustomName(item)
        return ItemStack(
            id = id,
            count = count,
            slot = slot,
            enchantments = enchantments,
            damage = damage,
            customName = customName,
        )
    }

    /**
     * Extract enchantments from an item compound.
     * Handles two NBT formats:
     * - 1.13–1.20.4: tag.Enchantments — ListTag of CompoundTags with "id" (String) + "lvl" (Short)
     * - 1.20.5+: components."minecraft:enchantments".levels — CompoundTag where keys are IDs, values are ints
     */
    private fun parseEnchantments(item: CompoundTag): List<EnchantmentData> {
        val result = mutableListOf<EnchantmentData>()

        // 1.13–1.20.4 format: tag.Enchantments
        val tag = NbtHelper.compound(item, "tag")
        if (tag != null) {
            @Suppress("UNCHECKED_CAST")
            val enchList = tag.get("Enchantments") as? ListTag<CompoundTag>
            enchList?.forEach { ench ->
                val enchId = NbtHelper.string(ench, "id").removePrefix("minecraft:")
                val level = NbtHelper.int(ench, "lvl", 1)
                if (enchId.isNotBlank()) {
                    result.add(EnchantmentData(enchId, level))
                }
            }
            if (result.isNotEmpty()) return result

            // Also check StoredEnchantments (for enchanted books)
            @Suppress("UNCHECKED_CAST")
            val storedList = tag.get("StoredEnchantments") as? ListTag<CompoundTag>
            storedList?.forEach { ench ->
                val enchId = NbtHelper.string(ench, "id").removePrefix("minecraft:")
                val level = NbtHelper.int(ench, "lvl", 1)
                if (enchId.isNotBlank()) {
                    result.add(EnchantmentData(enchId, level))
                }
            }
            if (result.isNotEmpty()) return result
        }

        // 1.20.5+ format: components."minecraft:enchantments".levels
        val components = NbtHelper.compound(item, "components")
        if (components != null) {
            val enchComp = NbtHelper.compound(components, "minecraft:enchantments")
                ?: NbtHelper.compound(components, "minecraft:stored_enchantments")
            val levels = NbtHelper.compound(enchComp, "levels")
            levels?.let { lvls ->
                for (key in lvls.keySet()) {
                    val enchId = key.removePrefix("minecraft:")
                    val level = NbtHelper.int(lvls, key, 1)
                    result.add(EnchantmentData(enchId, level))
                }
            }
        }

        return result
    }

    /** Extract damage value from an item (durability used). */
    private fun extractDamage(item: CompoundTag): Int {
        // 1.13–1.20.4: tag.Damage
        val tag = NbtHelper.compound(item, "tag")
        if (tag != null) {
            val dmg = NbtHelper.int(tag, "Damage")
            if (dmg > 0) return dmg
        }
        // 1.20.5+: components."minecraft:damage"
        val components = NbtHelper.compound(item, "components")
        if (components != null) {
            return NbtHelper.int(components, "minecraft:damage")
        }
        return 0
    }

    /** Extract custom name from an item. */
    private fun extractCustomName(item: CompoundTag): String? {
        // 1.13–1.20.4: tag.display.Name — JSON text component string
        val display = NbtHelper.compound(item, "tag", "display")
        if (display != null) {
            val nameJson = NbtHelper.string(display, "Name")
            if (nameJson.isNotBlank()) return stripJsonText(nameJson)
        }
        // 1.20.5+: components."minecraft:custom_name"
        val components = NbtHelper.compound(item, "components")
        if (components != null) {
            val name = NbtHelper.string(components, "minecraft:custom_name")
            if (name.isNotBlank()) return stripJsonText(name)
        }
        return null
    }

    /** Strip JSON text component wrapper: {"text":"My Sword"} → "My Sword" */
    private fun stripJsonText(raw: String): String {
        return try {
            val obj = jsonParser.parseToJsonElement(raw)
            if (obj is JsonObject) {
                obj["text"]?.jsonPrimitive?.content ?: raw
            } else {
                raw.trim('"')
            }
        } catch (_: Exception) {
            raw.trim('"')
        }
    }

    /** Resolve player name from usercache.json in game root directory. */
    fun resolvePlayerName(uuid: String, worldDir: File): String? {
        // usercache.json is in the game root (parent of saves/) or server root (parent of world)
        val candidates = listOfNotNull(
            worldDir.parentFile?.parentFile, // .minecraft/saves/<world> → .minecraft/
            worldDir.parentFile,             // server/<world> → server/
        )
        for (dir in candidates) {
            val cacheFile = File(dir, "usercache.json")
            if (!cacheFile.exists()) continue
            try {
                val text = cacheFile.readText()
                val array = jsonParser.parseToJsonElement(text).jsonArray
                for (entry in array) {
                    val obj = entry.jsonObject
                    val entryUuid = obj["uuid"]?.jsonPrimitive?.content ?: continue
                    if (entryUuid.equals(uuid, ignoreCase = true)) {
                        return obj["name"]?.jsonPrimitive?.content
                    }
                }
            } catch (_: Exception) {
                // Ignore parse errors
            }
        }
        return null
    }
}
