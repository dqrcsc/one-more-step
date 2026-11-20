package com.oms.core.proxy

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque

/**
 * Pure Kotlin implementation of official BLAKE3 (hash & derive_key).
 * Used for Shadowsocks-2022 (SIP022 / SIP023 EIH) session subkey and identity header derivation,
 * with zero native .so binary dependencies.
 */
object Blake3Pure {
    private val IV = intArrayOf(
        0x6A09E667,
        0xBB67AE85.toInt(),
        0x3C6EF372,
        0xA54FF53A.toInt(),
        0x510E527F,
        0x9B05688C.toInt(),
        0x1F83D9AB,
        0x5BE0CD19
    )

    private val MSG_PERMUTATION = intArrayOf(
        2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8
    )

    private const val CHUNK_START = 1 shl 0
    private const val CHUNK_END = 1 shl 1
    private const val PARENT = 1 shl 2
    private const val ROOT = 1 shl 3
    private const val DERIVE_KEY_CONTEXT = 1 shl 5
    private const val DERIVE_KEY_MATERIAL = 1 shl 6

    private inline fun rotr32(x: Int, n: Int): Int = (x ushr n) or (x shl (32 - n))

    private fun g(state: IntArray, a: Int, b: Int, c: Int, d: Int, mx: Int, my: Int) {
        state[a] = state[a] + state[b] + mx
        state[d] = rotr32(state[d] xor state[a], 16)
        state[c] = state[c] + state[d]
        state[b] = rotr32(state[b] xor state[c], 12)
        state[a] = state[a] + state[b] + my
        state[d] = rotr32(state[d] xor state[a], 8)
        state[c] = state[c] + state[d]
        state[b] = rotr32(state[b] xor state[c], 7)
    }

    private fun round(state: IntArray, m: IntArray) {
        g(state, 0, 4, 8, 12, m[0], m[1])
        g(state, 1, 5, 9, 13, m[2], m[3])
        g(state, 2, 6, 10, 14, m[4], m[5])
        g(state, 3, 7, 11, 15, m[6], m[7])
        g(state, 0, 5, 10, 15, m[8], m[9])
        g(state, 1, 6, 11, 12, m[10], m[11])
        g(state, 2, 7, 8, 13, m[12], m[13])
        g(state, 3, 4, 9, 14, m[14], m[15])
    }

    private fun compress(
        cv: IntArray,
        block: ByteArray,
        blockLen: Int,
        counter: Long,
        flags: Int
    ): IntArray {
        val padded = if (block.size == 64) block else block.copyOf(64)
        val buf = ByteBuffer.wrap(padded).order(ByteOrder.LITTLE_ENDIAN)
        var m = IntArray(16) { buf.int }
        val state = intArrayOf(
            cv[0], cv[1], cv[2], cv[3],
            cv[4], cv[5], cv[6], cv[7],
            IV[0], IV[1], IV[2], IV[3],
            (counter and 0xFFFFFFFFL).toInt(),
            ((counter ushr 32) and 0xFFFFFFFFL).toInt(),
            blockLen,
            flags
        )
        for (r in 0 until 7) {
            round(state, m)
            if (r < 6) {
                val permuted = IntArray(16)
                for (i in 0 until 16) {
                    permuted[i] = m[MSG_PERMUTATION[i]]
                }
                m = permuted
            }
        }
        for (i in 0 until 8) {
            state[i] = state[i] xor state[i + 8]
            state[i + 8] = state[i + 8] xor cv[i]
        }
        return state
    }

    private data class ChunkNode(
        val cv: IntArray,
        val block: ByteArray,
        val blockLen: Int,
        val counter: Long,
        val flags: Int
    )

    private fun chunkFinalNode(
        chunkBytes: ByteArray,
        chunkCounter: Long,
        keyWords: IntArray,
        flags: Int
    ): ChunkNode {
        var cv = keyWords.copyOf()
        if (chunkBytes.isEmpty()) {
            return ChunkNode(cv, ByteArray(0), 0, chunkCounter, flags or CHUNK_START or CHUNK_END)
        }
        val blockCount = (chunkBytes.size + 63) / 64
        for (idx in 0 until blockCount) {
            val start = idx * 64
            val end = minOf(start + 64, chunkBytes.size)
            val blk = chunkBytes.copyOfRange(start, end)
            var blkFlags = flags
            if (idx == 0) blkFlags = blkFlags or CHUNK_START
            if (idx == blockCount - 1) {
                blkFlags = blkFlags or CHUNK_END
                return ChunkNode(cv, blk, blk.size, chunkCounter, blkFlags)
            }
            cv = compress(cv, blk, blk.size, chunkCounter, blkFlags).copyOf(8)
        }
        error("Unreachable")
    }

    private fun packParentBlock(leftCv: IntArray, rightCv: IntArray): ByteArray {
        val buf = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 8) buf.putInt(leftCv[i])
        for (i in 0 until 8) buf.putInt(rightCv[i])
        return buf.array()
    }

    private fun blake3Core(data: ByteArray, keyWords: IntArray, flags: Int, outLen: Int): ByteArray {
        require(outLen > 0) { "outLen must be positive" }
        val chunkCount = if (data.isEmpty()) 1 else (data.size + 1023) / 1024
        val cvStack = ArrayDeque<IntArray>()

        for (chunkIdx in 0 until chunkCount - 1) {
            val start = chunkIdx * 1024
            val end = minOf(start + 1024, data.size)
            val node = chunkFinalNode(data.copyOfRange(start, end), chunkIdx.toLong(), keyWords, flags)
            var newCv = compress(node.cv, node.block, node.blockLen, node.counter, node.flags).copyOf(8)
            var totalChunks = chunkIdx + 1
            while ((totalChunks and 1) == 0) {
                val left = cvStack.removeLast()
                val parentBlk = packParentBlock(left, newCv)
                newCv = compress(keyWords, parentBlk, 64, 0L, flags or PARENT).copyOf(8)
                totalChunks = totalChunks ushr 1
            }
            cvStack.addLast(newCv)
        }

        val lastIdx = chunkCount - 1
        val lastChunk = if (data.isEmpty()) {
            ByteArray(0)
        } else {
            data.copyOfRange(lastIdx * 1024, data.size)
        }
        val lastNode = chunkFinalNode(lastChunk, lastIdx.toLong(), keyWords, flags)
        var outCv = lastNode.cv
        var outBlk = lastNode.block
        var outBlkLen = lastNode.blockLen
        var outCtr = lastNode.counter
        var outFlags = lastNode.flags

        if (cvStack.isNotEmpty()) {
            var rightCv = compress(outCv, outBlk, outBlkLen, outCtr, outFlags).copyOf(8)
            while (cvStack.size > 1) {
                val leftCv = cvStack.removeLast()
                val parentBlk = packParentBlock(leftCv, rightCv)
                rightCv = compress(keyWords, parentBlk, 64, 0L, flags or PARENT).copyOf(8)
            }
            val leftCv = cvStack.removeLast()
            outCv = keyWords
            outBlk = packParentBlock(leftCv, rightCv)
            outBlkLen = 64
            outCtr = 0L
            outFlags = flags or PARENT
        }

        val out = ByteArray(outLen)
        var written = 0
        var rootCtr = 0L
        while (written < outLen) {
            val words = compress(outCv, outBlk, outBlkLen, rootCtr, outFlags or ROOT)
            val blockBytes = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN).apply {
                for (w in words) putInt(w)
            }.array()
            val toCopy = minOf(64, outLen - written)
            System.arraycopy(blockBytes, 0, out, written, toCopy)
            written += toCopy
            rootCtr++
        }
        return out
    }

    fun hash(data: ByteArray, outLen: Int = 32): ByteArray {
        return blake3Core(data, IV, 0, outLen)
    }

    fun deriveKey(context: String, keyMaterial: ByteArray, outLen: Int = 32): ByteArray {
        val ctxBytes = blake3Core(context.toByteArray(Charsets.UTF_8), IV, DERIVE_KEY_CONTEXT, 32)
        val buf = ByteBuffer.wrap(ctxBytes).order(ByteOrder.LITTLE_ENDIAN)
        val ctxWords = IntArray(8) { buf.int }
        return blake3Core(keyMaterial, ctxWords, DERIVE_KEY_MATERIAL, outLen)
    }
}
