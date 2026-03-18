package com.spyglass.connect.minecraft

import com.spyglass.connect.model.MapTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import net.querz.nbt.tag.CompoundTag
import net.querz.nbt.tag.LongArrayTag
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Render overhead map tiles from Minecraft world data.
 *
 * Uses chunk heightmaps (WORLD_SURFACE) to determine the top block at each column,
 * then maps block IDs to colors via BlockColorPalette.
 * This avoids scanning all 256+ Y levels — just one block per column.
 */
object MapRenderer {

    private const val CHUNK_SIZE = 16

    /**
     * Render map tiles around a center position.
     * Each tile is one chunk (16x16 blocks), rendered as a 16x16 PNG.
     */
    fun renderTiles(
        worldDir: File,
        centerX: Int,
        centerZ: Int,
        radiusChunks: Int,
        dimension: String,
    ): List<MapTile> {
        val centerChunkX = centerX shr 4
        val centerChunkZ = centerZ shr 4
        val tiles = mutableListOf<MapTile>()

        // Group chunks by region file
        val regionChunks = mutableMapOf<String, MutableList<Pair<Int, Int>>>()
        for (cx in (centerChunkX - radiusChunks)..(centerChunkX + radiusChunks)) {
            for (cz in (centerChunkZ - radiusChunks)..(centerChunkZ + radiusChunks)) {
                val rx = cx shr 5 // region = chunk / 32
                val rz = cz shr 5
                val regionKey = "r.$rx.$rz.mca"
                regionChunks.getOrPut(regionKey) { mutableListOf() }.add(cx to cz)
            }
        }

        val regionDir = when (dimension) {
            "overworld" -> File(worldDir, "region")
            "the_nether" -> File(worldDir, "DIM-1/region")
            "the_end" -> File(worldDir, "DIM1/region")
            else -> File(worldDir, "region")
        }

        val regionWork = regionChunks.mapNotNull { (regionFileName, chunkCoords) ->
            val regionFile = File(regionDir, regionFileName)
            if (regionFile.exists()) regionFile to chunkCoords.toSet() else null
        }

        if (regionWork.isEmpty()) return tiles

        return runBlocking(Dispatchers.IO) {
            regionWork.map { (regionFile, chunkCoords) ->
                async {
                    val result = mutableListOf<MapTile>()
                    val chunks = AnvilReader.readRegionChunks(regionFile)
                    for (chunkNbt in chunks) {
                        val chunkX = extractChunkX(chunkNbt) ?: continue
                        val chunkZ = extractChunkZ(chunkNbt) ?: continue
                        if ((chunkX to chunkZ) !in chunkCoords) continue

                        val image = renderChunkTile(chunkNbt)
                        if (image != null) {
                            result.add(MapTile(
                                chunkX = chunkX,
                                chunkZ = chunkZ,
                                dimension = dimension,
                                imageBase64 = imageToBase64(image),
                            ))
                        }
                    }
                    result
                }
            }.awaitAll().flatten()
        }
    }

    /** Render a single chunk as a 16x16 tile image using heightmap + block colors. */
    private fun renderChunkTile(chunkNbt: CompoundTag): BufferedImage? {
        val image = BufferedImage(CHUNK_SIZE, CHUNK_SIZE, BufferedImage.TYPE_INT_RGB)

        // Try heightmap-based rendering first
        val heightmap = extractHeightmap(chunkNbt)
        val sections = extractSections(chunkNbt)

        if (sections.isEmpty()) return null

        // 1.18+ stores heightmap values relative to the bottom of the world.
        // yPos is the minimum section Y index (e.g. -4 for overworld = Y -64).
        val minBuildHeight = NbtHelper.int(chunkNbt, "yPos", 0) * CHUNK_SIZE

        for (x in 0 until CHUNK_SIZE) {
            for (z in 0 until CHUNK_SIZE) {
                val y = if (heightmap != null) {
                    minBuildHeight + heightmap[z * CHUNK_SIZE + x] - 1
                } else {
                    estimateHeight(sections)
                }

                val blockId = getBlockAt(sections, x, y.coerceAtLeast(0), z)
                val color = BlockColorPalette.getColor(blockId ?: "stone")

                // Simple height-based shading
                val shade = (y.coerceIn(0, 255).toFloat() / 255f * 0.3f + 0.7f).coerceIn(0.7f, 1.0f)
                val r = ((color shr 16 and 0xFF) * shade).toInt().coerceIn(0, 255)
                val g = ((color shr 8 and 0xFF) * shade).toInt().coerceIn(0, 255)
                val b = ((color and 0xFF) * shade).toInt().coerceIn(0, 255)

                image.setRGB(x, z, (r shl 16) or (g shl 8) or b)
            }
        }

        return image
    }

    /** Extract WORLD_SURFACE heightmap from chunk NBT. */
    private fun extractHeightmap(chunkNbt: CompoundTag): IntArray? {
        val heightmaps = NbtHelper.compound(chunkNbt, "Heightmaps")
            ?: NbtHelper.compound(chunkNbt, "Level", "Heightmaps")
            ?: return null

        val longArray = heightmaps.get("WORLD_SURFACE") as? LongArrayTag ?: return null
        return unpackHeightmap(longArray.value, CHUNK_SIZE * CHUNK_SIZE)
    }

    /**
     * Unpack a packed long array heightmap.
     * Minecraft packs heightmap values into longs with a fixed bit width.
     */
    private fun unpackHeightmap(packed: LongArray, count: Int): IntArray {
        if (packed.isEmpty()) return IntArray(count)

        val result = IntArray(count)
        val bitsPerEntry = maxOf(1, (packed.size * 64) / count)
        if (bitsPerEntry > 32) return IntArray(count)

        val entriesPerLong = 64 / bitsPerEntry
        val mask = (1L shl bitsPerEntry) - 1L

        for (i in 0 until count) {
            val longIndex = i / entriesPerLong
            val bitOffset = (i % entriesPerLong) * bitsPerEntry
            if (longIndex >= packed.size) break
            result[i] = ((packed[longIndex] ushr bitOffset) and mask).toInt()
        }

        return result
    }

    /** Extract chunk sections (Y-indexed sub-chunks). */
    @Suppress("UNCHECKED_CAST")
    private fun extractSections(chunkNbt: CompoundTag): List<CompoundTag> {
        val sections = (chunkNbt.get("sections") as? net.querz.nbt.tag.ListTag<CompoundTag>)
            ?: (NbtHelper.compound(chunkNbt, "Level")?.get("Sections") as? net.querz.nbt.tag.ListTag<CompoundTag>)
            ?: return emptyList()

        return (0 until sections.size()).map { idx -> sections[idx] }
    }

    /** Get the block ID at a specific position within chunk sections. */
    @Suppress("UNCHECKED_CAST")
    private fun getBlockAt(sections: List<CompoundTag>, x: Int, y: Int, z: Int): String? {
        val sectionY = y shr 4
        val section = sections.firstOrNull { NbtHelper.int(it, "Y") == sectionY }
            ?: return null

        val blockStates = NbtHelper.compound(section, "block_states") ?: return null
        val palette = blockStates.get("palette") as? net.querz.nbt.tag.ListTag<CompoundTag>
            ?: return null

        if (palette.size() == 0) return null
        if (palette.size() == 1) {
            return NbtHelper.string(palette[0], "Name")
        }

        val data = blockStates.get("data") as? LongArrayTag ?: return null
        val bitsPerEntry = maxOf(4, ceilLog2(palette.size()))
        val localY = y and 0xF
        val index = (localY * CHUNK_SIZE + z) * CHUNK_SIZE + x

        val paletteIndex = unpackPaletteIndex(data.value, index, bitsPerEntry)
        if (paletteIndex < 0 || paletteIndex >= palette.size()) return null

        return NbtHelper.string(palette[paletteIndex], "Name")
    }

    /** Unpack a single palette index from packed long array. */
    private fun unpackPaletteIndex(packed: LongArray, index: Int, bitsPerEntry: Int): Int {
        val entriesPerLong = 64 / bitsPerEntry
        val longIndex = index / entriesPerLong
        val bitOffset = (index % entriesPerLong) * bitsPerEntry

        if (longIndex >= packed.size) return 0

        val mask = (1L shl bitsPerEntry) - 1L
        return ((packed[longIndex] ushr bitOffset) and mask).toInt()
    }

    private fun estimateHeight(sections: List<CompoundTag>): Int {
        return sections.maxOfOrNull { NbtHelper.int(it, "Y") * 16 + 15 } ?: 64
    }

    private fun ceilLog2(n: Int): Int {
        var bits = 0
        var value = n - 1
        while (value > 0) {
            bits++
            value = value shr 1
        }
        return bits
    }

    private fun extractChunkX(chunkNbt: CompoundTag): Int? {
        val x = NbtHelper.int(chunkNbt, "xPos", Int.MIN_VALUE)
        if (x != Int.MIN_VALUE) return x
        val level = NbtHelper.compound(chunkNbt, "Level") ?: return null
        val lx = NbtHelper.int(level, "xPos", Int.MIN_VALUE)
        return if (lx != Int.MIN_VALUE) lx else null
    }

    private fun extractChunkZ(chunkNbt: CompoundTag): Int? {
        val z = NbtHelper.int(chunkNbt, "zPos", Int.MIN_VALUE)
        if (z != Int.MIN_VALUE) return z
        val level = NbtHelper.compound(chunkNbt, "Level") ?: return null
        val lz = NbtHelper.int(level, "zPos", Int.MIN_VALUE)
        return if (lz != Int.MIN_VALUE) lz else null
    }

    /** Encode a BufferedImage to Base64 PNG. */
    private fun imageToBase64(image: BufferedImage): String {
        val baos = ByteArrayOutputStream()
        ImageIO.write(image, "PNG", baos)
        return Base64.getEncoder().encodeToString(baos.toByteArray())
    }
}
