package com.spyglass.connect.minecraft

import com.spyglass.connect.Log
import com.spyglass.connect.model.ContainerInfo
import com.spyglass.connect.model.ItemStack
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
    fun scanWorld(
        worldDir: File,
        onProgress: ((dimension: String, currentRegion: Int, totalRegions: Int, regionFile: String, containersFound: Int) -> Unit)? = null,
    ): List<ContainerInfo> {
        val containers = mutableListOf<ContainerInfo>()
        for (dimension in listOf("overworld", "the_nether", "the_end")) {
            containers.addAll(scanDimension(worldDir, dimension, onProgress))
        }
        return containers
    }

    /**
     * Minimum InhabitedTime (in ticks) for a chunk to be considered "visited".
     * 20 ticks = 1 second — filters out chunks the player has never actually been in.
     */
    private const val MIN_INHABITED_TICKS = 20L

    /**
     * Sample the first region file to check if InhabitedTime is tracked.
     * Paper/Spigot servers zero this field for all chunks, making it useless.
     * Returns true if at least one chunk has InhabitedTime > 0.
     */
    private fun hasInhabitedTimeData(regionFiles: List<File>): Boolean {
        val sample = regionFiles.firstOrNull() ?: return false
        val chunks = AnvilReader.readRegionChunks(sample)
        return chunks.any { chunk ->
            val v = NbtHelper.long(chunk, "InhabitedTime", -1L).let { t ->
                if (t >= 0) t
                else NbtHelper.long(NbtHelper.compound(chunk, "Level"), "InhabitedTime", 0L)
            }
            v > 0
        }
    }

    /** Scan all containers in a single dimension. Processes region files sequentially to avoid OOM. */
    fun scanDimension(
        worldDir: File,
        dimension: String,
        onProgress: ((dimension: String, currentRegion: Int, totalRegions: Int, regionFile: String, containersFound: Int) -> Unit)? = null,
    ): List<ContainerInfo> {
        val regionFiles = AnvilReader.regionFiles(worldDir, dimension)
        Log.d(TAG, "[$dimension] Found ${regionFiles.size} region files in ${worldDir.absolutePath}")
        if (regionFiles.isEmpty()) return emptyList()

        // Auto-detect: use InhabitedTime filtering only if the server actually tracks it
        val useInhabitedTime = hasInhabitedTimeData(regionFiles)
        Log.i(TAG, "[$dimension] InhabitedTime filtering: ${if (useInhabitedTime) "enabled (vanilla)" else "disabled (Paper/zeroed)"}")

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
            ItemStack(id = id, count = count, slot = slot)
        }
    }
}
