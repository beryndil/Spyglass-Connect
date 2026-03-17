package com.spyglass.connect.minecraft

import net.querz.nbt.io.NBTInputStream
import net.querz.nbt.tag.CompoundTag
import net.querz.nbt.tag.ListTag
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * Read Minecraft Anvil (.mca) region files.
 *
 * Port of minecolony-manager/parser/storage_parser.py parse_region_file().
 * Each region file contains 32x32 chunks. The first 4KB is an offset table:
 * - 1024 entries of 4 bytes each: 3-byte offset (in 4KB sectors) + 1-byte sector count
 * - Actual chunk data at offset*4096: 4-byte length + 1-byte compression type + compressed NBT
 */
object AnvilReader {

    private const val SECTOR_SIZE = 4096
    private const val HEADER_ENTRIES = 1024
    private const val COMPRESSION_ZLIB = 2
    private const val COMPRESSION_GZIP = 1
    private const val COMPRESSION_NONE = 3

    /**
     * Read all chunks from a region file, extracting block entities.
     * Returns a list of (chunkNBT, chunkX, chunkZ) for further processing.
     */
    fun readRegionChunks(regionFile: File): List<CompoundTag> {
        if (!regionFile.exists() || regionFile.length() < SECTOR_SIZE) return emptyList()

        val chunks = mutableListOf<CompoundTag>()

        RandomAccessFile(regionFile, "r").use { raf ->
            // Read 4KB offset table
            val header = ByteArray(SECTOR_SIZE)
            raf.readFully(header)

            for (i in 0 until HEADER_ENTRIES) {
                val baseIdx = i * 4
                // 3-byte big-endian offset + 1-byte sector count
                val offset = ((header[baseIdx].toInt() and 0xFF) shl 16) or
                    ((header[baseIdx + 1].toInt() and 0xFF) shl 8) or
                    (header[baseIdx + 2].toInt() and 0xFF)
                val sectors = header[baseIdx + 3].toInt() and 0xFF

                if (offset == 0 || sectors == 0) continue

                try {
                    val byteOffset = offset.toLong() * SECTOR_SIZE
                    if (byteOffset >= raf.length()) continue

                    raf.seek(byteOffset)

                    // 4-byte big-endian length
                    val length = raf.readInt()
                    if (length <= 1 || length > sectors * SECTOR_SIZE) continue

                    // 1-byte compression type
                    val compressionType = raf.readByte().toInt() and 0xFF

                    // Read compressed data
                    val compressed = ByteArray(length - 1)
                    raf.readFully(compressed)

                    val nbt = decompressChunk(compressed, compressionType) ?: continue
                    chunks.add(nbt)
                } catch (_: Exception) {
                    // Skip corrupt chunks
                }
            }
        }

        return chunks
    }

    /** Decompress chunk data based on compression type. */
    private fun decompressChunk(data: ByteArray, compressionType: Int): CompoundTag? {
        val inputStream = when (compressionType) {
            COMPRESSION_ZLIB -> InflaterInputStream(ByteArrayInputStream(data))
            COMPRESSION_GZIP -> GZIPInputStream(ByteArrayInputStream(data))
            COMPRESSION_NONE -> ByteArrayInputStream(data)
            else -> return null
        }

        return try {
            NBTInputStream(BufferedInputStream(inputStream)).use { nbtIn ->
                val named = nbtIn.readTag(64) // max depth
                named.tag as? CompoundTag
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extract block entities from a chunk NBT.
     * Handles both modern (1.18+: "block_entities") and legacy ("TileEntities") keys.
     */
    @Suppress("UNCHECKED_CAST")
    fun extractBlockEntities(chunkNbt: CompoundTag): ListTag<CompoundTag>? {
        // 1.18+ format
        (chunkNbt.get("block_entities") as? ListTag<CompoundTag>)?.let { return it }
        // Legacy format
        (chunkNbt.get("TileEntities") as? ListTag<CompoundTag>)?.let { return it }
        // Nested under Level (pre-1.18)
        val level = NbtHelper.compound(chunkNbt, "Level")
        (level?.get("TileEntities") as? ListTag<CompoundTag>)?.let { return it }
        return null
    }

    /**
     * Quick check: does this region file contain any chunk with InhabitedTime >= threshold?
     * Only reads the offset table + InhabitedTime from each chunk header — does NOT parse
     * block entities, making this much faster than a full readRegionChunks().
     */
    fun hasVisitedChunks(regionFile: File, minInhabitedTicks: Long): Boolean {
        if (!regionFile.exists() || regionFile.length() < SECTOR_SIZE) return false

        RandomAccessFile(regionFile, "r").use { raf ->
            val header = ByteArray(SECTOR_SIZE)
            raf.readFully(header)

            for (i in 0 until HEADER_ENTRIES) {
                val baseIdx = i * 4
                val offset = ((header[baseIdx].toInt() and 0xFF) shl 16) or
                    ((header[baseIdx + 1].toInt() and 0xFF) shl 8) or
                    (header[baseIdx + 2].toInt() and 0xFF)
                val sectors = header[baseIdx + 3].toInt() and 0xFF
                if (offset == 0 || sectors == 0) continue

                try {
                    val byteOffset = offset.toLong() * SECTOR_SIZE
                    if (byteOffset >= raf.length()) continue
                    raf.seek(byteOffset)
                    val length = raf.readInt()
                    if (length <= 1 || length > sectors * SECTOR_SIZE) continue
                    val compressionType = raf.readByte().toInt() and 0xFF
                    val compressed = ByteArray(length - 1)
                    raf.readFully(compressed)
                    val nbt = decompressChunk(compressed, compressionType) ?: continue

                    val inhabited = NbtHelper.long(nbt, "InhabitedTime", -1L).let { v ->
                        if (v >= 0) v
                        else NbtHelper.long(NbtHelper.compound(nbt, "Level"), "InhabitedTime", 0L)
                    }
                    if (inhabited >= minInhabitedTicks) return true
                } catch (_: Exception) {
                    // Skip corrupt chunks
                }
            }
        }
        return false
    }

    /**
     * Get all region files for a dimension within a world directory.
     */
    fun regionFiles(worldDir: File, dimension: String = "overworld"): List<File> {
        val regionDir = when (dimension) {
            "overworld" -> File(worldDir, "region")
            "the_nether" -> File(worldDir, "DIM-1/region")
            "the_end" -> File(worldDir, "DIM1/region")
            else -> File(worldDir, "region")
        }
        if (!regionDir.isDirectory) return emptyList()
        return regionDir.listFiles { f -> f.extension == "mca" }?.toList() ?: emptyList()
    }
}
