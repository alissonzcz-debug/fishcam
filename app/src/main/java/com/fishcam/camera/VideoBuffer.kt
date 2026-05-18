package com.fishcam.camera

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.LinkedList

/**
 * Circular buffer of short video chunk files.
 *
 * Strategy:
 *  - CameraX records continuously into small chunk files (e.g. 3 s each).
 *  - We keep only the last N chunks needed to cover bufferSeconds.
 *  - On trigger, we concatenate the buffered chunks + keep recording until STOP.
 */
class VideoBuffer(
    private val cacheDir: File,
    private val bufferSeconds: Int = 15,
    private val chunkSeconds: Int = 3
) {
    companion object {
        private const val TAG = "VideoBuffer"
    }

    // How many chunks to keep: ceiling(bufferSeconds / chunkSeconds) + 1 safety
    private val maxChunks: Int = (bufferSeconds / chunkSeconds) + 2

    // Queue of completed chunk files (oldest first)
    private val chunks: LinkedList<File> = LinkedList()

    // Temp dir for chunks
    val chunkDir: File = File(cacheDir, "chunks").also { it.mkdirs() }

    @Synchronized
    fun addChunk(file: File) {
        chunks.addLast(file)
        Log.d(TAG, "Chunk added: ${file.name}, total=${chunks.size}")
        // Evict oldest chunks beyond buffer
        while (chunks.size > maxChunks) {
            val old = chunks.removeFirst()
            old.delete()
            Log.d(TAG, "Evicted old chunk: ${old.name}")
        }
    }

    /**
     * Returns a copy of current buffered chunks (oldest → newest).
     * These files should NOT be deleted — they may still be in the ring.
     */
    @Synchronized
    fun getBufferedChunks(): List<File> = chunks.toList()

    /**
     * Concatenates all buffered chunks into a single output file.
     * Returns the merged file, or null on failure.
     */
    fun mergeChunksInto(outputFile: File, extraChunks: List<File> = emptyList()): Boolean {
        val all = getBufferedChunks() + extraChunks
        if (all.isEmpty()) return false

        return try {
            FileOutputStream(outputFile).use { out ->
                all.forEach { chunk ->
                    if (chunk.exists() && chunk.length() > 0) {
                        FileInputStream(chunk).use { inp -> inp.copyTo(out) }
                    }
                }
            }
            Log.d(TAG, "Merged ${all.size} chunks → ${outputFile.name} (${outputFile.length()} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Merge failed", e)
            false
        }
    }

    /** Generate a unique chunk file path */
    fun nextChunkFile(): File =
        File(chunkDir, "chunk_${System.currentTimeMillis()}.mp4")

    @Synchronized
    fun clear() {
        chunks.forEach { it.delete() }
        chunks.clear()
    }

    fun chunkDurationSec() = chunkSeconds
}
