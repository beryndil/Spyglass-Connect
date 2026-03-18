package com.spyglass.connect.minecraft

import com.spyglass.connect.Log
import com.spyglass.connect.model.ContainerInfo
import com.spyglass.connect.model.EnchantmentData
import com.spyglass.connect.model.ItemStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import net.querz.nbt.tag.CompoundTag
import net.querz.nbt.tag.ListTag
import java.io.File

/**
 * Scan Minecraft world for containers (chests, barrels, shulker boxes, etc.)
 * and extract their inventories.
 *
 * Port of minecolony-manager/parser/storage_parser.py container scanning logic.
 */
object ChestScanner {

    private const val TAG = "ChestScanner"

    /** Block entity IDs that are player-relevant storage containers.
     *  Dispensers and droppers are excluded — they're almost always structure-generated
     *  (jungle temples, ocean monuments) rather than player storage. */
    private val CONTAINER_TYPES = setOf(
        "minecraft:chest",
        "minecraft:trapped_chest",
        "minecraft:barrel",
        "minecraft:shulker_box",
        "minecraft:hopper",
        "minecraft:brewing_stand",
        "minecraft:furnace",
        "minecraft:blast_furnace",
        "minecraft:smoker",
    )

    /**
     * Scan all containers in a world across all dimensions.
     * Fair play is always enabled: LootTable containers are skipped, and InhabitedTime
     * filtering is auto-detected (used on vanilla, disabled on Paper which zeroes it).
     */
    suspend fun scanWorld(
        worldDir: File,
        onProgress: (suspend (dimension: String, currentRegion: Int, totalRegions: Int, regionFile: String, containersFound: Int) -> Unit)? = null,
    ): List<ContainerInfo> {
        val containers = mutableListOf<ContainerInfo>()
        for (dimension in listOf("overworld", "the_nether", "the_end")) {
            val priorCount = containers.size
            val wrappedProgress: (suspend (String, Int, Int, String, Int) -> Unit)? = onProgress?.let { cb ->
                { dim, cur, tot, file, dimFound -> cb(dim, cur, tot, file, priorCount + dimFound) }
            }
            containers.addAll(scanDimension(worldDir, dimension, wrappedProgress))
        }
        return containers
    }

    /**
     * Minimum InhabitedTime (in ticks) for a chunk to be considered "visited".
     * 20 ticks = 1 second — filters out chunks the player has never actually been in.
     */
    private const val MIN_INHABITED_TICKS = 20L

    /**
     * Sample multiple region files to check if InhabitedTime is tracked.
     * Paper/Spigot servers zero this field for all chunks, making it useless.
     * Returns true if at least one chunk across sampled files has InhabitedTime > 0.
     *
     * Samples up to 5 region files spread across the list to handle Chunky-loaded worlds
     * where the first region(s) may be entirely pre-generated with InhabitedTime = 0.
     */
    private fun hasInhabitedTimeData(regionFiles: List<File>): Boolean {
        if (regionFiles.isEmpty()) return false
        // Sample up to 5 files spread evenly across the list
        val step = maxOf(1, regionFiles.size / 5)
        val samples = (regionFiles.indices step step).take(5).map { regionFiles[it] }
        return samples.any { file ->
            val chunks = AnvilReader.readRegionChunks(file)
            chunks.any { chunk ->
                val v = NbtHelper.long(chunk, "InhabitedTime", -1L).let { t ->
                    if (t >= 0) t
                    else NbtHelper.long(NbtHelper.compound(chunk, "Level"), "InhabitedTime", 0L)
                }
                v > 0
            }
        }
    }

    /** Scan all containers in a single dimension. Processes region files sequentially to avoid OOM. */
    suspend fun scanDimension(
        worldDir: File,
        dimension: String,
        onProgress: (suspend (dimension: String, currentRegion: Int, totalRegions: Int, regionFile: String, containersFound: Int) -> Unit)? = null,
    ): List<ContainerInfo> {
        val allRegionFiles = AnvilReader.regionFiles(worldDir, dimension)
        Log.d(TAG, "[$dimension] Found ${allRegionFiles.size} region files in ${worldDir.absolutePath}")
        if (allRegionFiles.isEmpty()) return emptyList()

        // Auto-detect: use InhabitedTime filtering only if the server actually tracks it
        val useInhabitedTime = hasInhabitedTimeData(allRegionFiles)
        Log.i(TAG, "[$dimension] InhabitedTime filtering: ${if (useInhabitedTime) "enabled (vanilla)" else "disabled (Paper/zeroed)"}")

        // Pre-filter: skip entire region files that have no visited chunks (parallel)
        val regionFiles = if (useInhabitedTime && allRegionFiles.size > 20) {
            Log.i(TAG, "[$dimension] Pre-filtering ${allRegionFiles.size} regions for visited chunks...")
            coroutineScope {
                allRegionFiles.map { file ->
                    async(Dispatchers.IO) { file to AnvilReader.hasVisitedChunks(file, MIN_INHABITED_TICKS) }
                }.awaitAll()
            }.filter { it.second }.map { it.first }.also {
                Log.i(TAG, "[$dimension] ${it.size} of ${allRegionFiles.size} regions have visited chunks")
            }
        } else {
            allRegionFiles
        }

        val allContainers = mutableListOf<ContainerInfo>()
        for ((index, regionFile) in regionFiles.withIndex()) {
            onProgress?.invoke(dimension, index + 1, regionFiles.size, regionFile.name, allContainers.size)
            val chunks = AnvilReader.readRegionChunks(regionFile)
            var skippedChunks = 0
            var totalBlockEntities = 0
            for (chunk in chunks) {
                if (useInhabitedTime) {
                    val inhabited = NbtHelper.long(chunk, "InhabitedTime", -1L).let { v ->
                        if (v >= 0) v
                        else NbtHelper.long(NbtHelper.compound(chunk, "Level"), "InhabitedTime", 0L)
                    }
                    if (inhabited < MIN_INHABITED_TICKS) {
                        skippedChunks++
                        continue
                    }
                }

                val blockEntities = AnvilReader.extractBlockEntities(chunk) ?: continue
                totalBlockEntities += blockEntities.size()
                for (i in 0 until blockEntities.size()) {
                    val be = blockEntities[i]
                    val container = parseContainer(be, dimension) ?: continue
                    allContainers.add(container)
                }
            }
            Log.d(TAG, "[$dimension] Region ${regionFile.name}: skipped $skippedChunks, processed ${chunks.size - skippedChunks} chunks, $totalBlockEntities block entities, ${allContainers.size} total")
        }
        return allContainers
    }

    /** Parse a block entity into ContainerInfo, or null if not a container. */
    private fun parseContainer(blockEntity: CompoundTag, dimension: String): ContainerInfo? {
        val id = NbtHelper.string(blockEntity, "id")
        if (id !in CONTAINER_TYPES) return null

        val x = NbtHelper.int(blockEntity, "x")
        val y = NbtHelper.int(blockEntity, "y")
        val z = NbtHelper.int(blockEntity, "z")

        // Skip loot table containers (not yet generated by the game)
        if (blockEntity.containsKey("LootTable")) return null

        val customName = parseCustomName(blockEntity)
        val items = parseItems(blockEntity)

        // Skip empty containers
        if (items.isEmpty()) return null

        Log.d(TAG, "  Found $id at ($x, $y, $z) with ${items.size} items")
        return ContainerInfo(
            type = id.removePrefix("minecraft:"),
            x = x, y = y, z = z,
            dimension = dimension,
            customName = customName,
            items = items,
        )
    }

    /**
     * Parse custom name from block entity.
     * Minecraft stores custom names as JSON chat components: {"text":"My Chest"}
     */
    private fun parseCustomName(blockEntity: CompoundTag): String? {
        val raw = NbtHelper.string(blockEntity, "CustomName")
        if (raw.isBlank()) return null
        // Try parsing JSON chat component
        if (raw.startsWith("{")) {
            return try {
                val textMatch = Regex(""""text"\s*:\s*"([^"]*?)"""").find(raw)
                textMatch?.groupValues?.get(1)?.ifBlank { null }
            } catch (_: Exception) {
                raw
            }
        }
        return raw
    }

    /** Parse Items list from a container block entity. */
    @Suppress("UNCHECKED_CAST")
    private fun parseItems(blockEntity: CompoundTag): List<ItemStack> {
        val itemList = blockEntity.get("Items") as? ListTag<CompoundTag> ?: return emptyList()
        return (0 until itemList.size()).mapNotNull { i ->
            val item = itemList[i]
            val id = NbtHelper.string(item, "id").removePrefix("minecraft:")
            if (id.isBlank()) return@mapNotNull null
            // 1.21+ uses lowercase "count" (IntTag); older uses "Count" (ByteTag)
            val count = maxOf(NbtHelper.int(item, "Count", 0), NbtHelper.int(item, "count", 0))
                .coerceAtLeast(1)
            val slot = NbtHelper.int(item, "Slot", -1)
            val enchantments = parseEnchantments(item)
            val damage = extractDamage(item)
            val customName = extractCustomName(item)
            ItemStack(
                id = id,
                count = count,
                slot = slot,
                enchantments = enchantments,
                damage = damage,
                customName = customName,
            )
        }
    }

    /**
     * Extract enchantments from an item compound.
     * Handles three NBT formats:
     * - 1.13–1.20.4: tag.Enchantments / tag.StoredEnchantments
     * - 1.20.5–1.21.1: components."minecraft:enchantments".levels
     * - 1.21.2+: components."minecraft:enchantments" (direct map)
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseEnchantments(item: CompoundTag): List<EnchantmentData> {
        val result = mutableListOf<EnchantmentData>()

        // 1.13–1.20.4 format: tag.Enchantments
        val tag = NbtHelper.compound(item, "tag")
        if (tag != null) {
            val enchList = tag.get("Enchantments") as? ListTag<CompoundTag>
            enchList?.forEach { ench ->
                val enchId = NbtHelper.string(ench, "id").removePrefix("minecraft:")
                val level = NbtHelper.int(ench, "lvl", 1)
                if (enchId.isNotBlank()) result.add(EnchantmentData(enchId, level))
            }
            if (result.isNotEmpty()) return result

            val storedList = tag.get("StoredEnchantments") as? ListTag<CompoundTag>
            storedList?.forEach { ench ->
                val enchId = NbtHelper.string(ench, "id").removePrefix("minecraft:")
                val level = NbtHelper.int(ench, "lvl", 1)
                if (enchId.isNotBlank()) result.add(EnchantmentData(enchId, level))
            }
            if (result.isNotEmpty()) return result
        }

        // 1.20.5+ format: components."minecraft:enchantments"
        val components = NbtHelper.compound(item, "components")
        if (components != null) {
            val enchComp = NbtHelper.compound(components, "minecraft:enchantments")
                ?: NbtHelper.compound(components, "minecraft:stored_enchantments")
            if (enchComp != null) {
                val levels = NbtHelper.compound(enchComp, "levels")
                val source = levels ?: enchComp
                for (key in source.keySet()) {
                    if (levels == null && !key.contains(":")) continue
                    val enchId = key.removePrefix("minecraft:")
                    val level = NbtHelper.int(source, key, 1)
                    if (level > 0) result.add(EnchantmentData(enchId, level))
                }
            }
        }

        return result
    }

    /** Extract damage value from an item (durability used). */
    private fun extractDamage(item: CompoundTag): Int {
        val tag = NbtHelper.compound(item, "tag")
        if (tag != null) {
            val dmg = NbtHelper.int(tag, "Damage")
            if (dmg > 0) return dmg
        }
        val components = NbtHelper.compound(item, "components")
        if (components != null) {
            return NbtHelper.int(components, "minecraft:damage")
        }
        return 0
    }

    /** Extract custom name from an item in a container. */
    private fun extractCustomName(item: CompoundTag): String? {
        // 1.13–1.20.4: tag.display.Name
        val tag = NbtHelper.compound(item, "tag")
        if (tag != null) {
            val display = NbtHelper.compound(tag, "display")
            if (display != null) {
                val nameJson = NbtHelper.string(display, "Name")
                if (nameJson.isNotBlank()) return stripJsonName(nameJson)
            }
        }
        // 1.20.5+: components."minecraft:custom_name"
        val components = NbtHelper.compound(item, "components")
        if (components != null) {
            val name = NbtHelper.string(components, "minecraft:custom_name")
            if (name.isNotBlank()) return stripJsonName(name)
        }
        return null
    }

    /** Strip JSON text component wrapper: {"text":"My Sword"} → "My Sword" */
    private fun stripJsonName(raw: String): String {
        if (raw.startsWith("{")) {
            return try {
                val textMatch = Regex(""""text"\s*:\s*"([^"]*?)"""").find(raw)
                textMatch?.groupValues?.get(1) ?: raw
            } catch (_: Exception) {
                raw
            }
        }
        return raw.trim('"')
    }
}
