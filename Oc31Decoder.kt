package com.example.korgplayer

/**
 * OC31 block decompressor for KORG arranger resources.
 * The command layout follows the publicly documented OC31 algorithm in KORG-Tools.
 * This class only decompresses a block; it does not claim to decode every STY/PCG/PCM field.
 */
object Oc31Decoder {
    data class Block(val markerOffset: Int, val compressedSize: Int, val uncompressedSize: Int, val data: ByteArray)

    fun decompress(compressed: ByteArray, expectedSize: Int): ByteArray {
        require(expectedSize in 0..64_000_000) { "Unreasonable OC31 output size" }
        val out = ByteArray(expectedSize)
        var ip = 0
        var op = 0

        fun readU8(): Int {
            require(ip < compressed.size) { "OC31 input ended unexpectedly at $ip" }
            return compressed[ip++].toInt() and 0xff
        }
        fun readBytes(count: Int) {
            require(count >= 0 && ip + count <= compressed.size) { "OC31 literal exceeds input" }
            require(op + count <= out.size) { "OC31 output exceeds declared size" }
            System.arraycopy(compressed, ip, out, op, count)
            ip += count
            op += count
        }
        fun backref(distance: Int, length: Int, extra: Int) {
            require(distance >= 0 && distance < op) { "Invalid OC31 back-reference ($distance at $op)" }
            require(length >= 0 && op + length + extra <= out.size) { "OC31 back-reference exceeds output" }
            repeat(length) {
                out[op] = out[op - distance - 1]
                op++
            }
            readBytes(extra)
        }

        while (ip < compressed.size && op < expectedSize) {
            val flag = readU8()
            if (flag >= 0x10) {
                if (flag >= 0x40) {
                    val b = readU8()
                    val tmp = (flag shl 8) or b
                    backref((tmp shr 2) and 0x7ff, (tmp shr 13) + 1, b and 3)
                } else if (flag >= 0x20) {
                    val tmp = flag and 0x1f
                    when {
                        tmp == 0 -> {
                            val length = readU8() + 0x22
                            val b2 = readU8(); val b3 = readU8()
                            backref(((b2 shl 8) or b3) shr 2, length, b3 and 3)
                        }
                        tmp == 1 -> {
                            val b2 = readU8(); val b3 = readU8()
                            val length = (b2 shl 8) or b3
                            val b4 = readU8(); val b5 = readU8()
                            backref(((b4 shl 8) or b5) shr 2, length, b5 and 3)
                        }
                        else -> {
                            val length = tmp + 2
                            val b2 = readU8(); val b3 = readU8()
                            backref(((b2 shl 8) or b3) shr 2, length, b3 and 3)
                        }
                    }
                } else {
                    val tmp = flag and 7
                    val add = if ((flag and 8) != 0) 0x4000 else 0
                    when {
                        tmp == 0 -> {
                            val length = readU8() + 0x0a
                            val b2 = readU8(); val b3 = readU8()
                            backref((((b2 shl 8) or b3) shr 2) + 0x4000 + add, length, b3 and 3)
                        }
                        tmp == 1 -> {
                            val b2 = readU8(); val b3 = readU8()
                            val length = (b2 shl 8) or b3
                            val b4 = readU8(); val b5 = readU8()
                            backref((((b4 shl 8) or b5) shr 2) + 0x4000 + add, length, b5 and 3)
                        }
                        else -> {
                            val length = tmp + 2
                            val b2 = readU8(); val b3 = readU8()
                            backref((((b2 shl 8) or b3) shr 2) + 0x4000 + add, length, b3 and 3)
                        }
                    }
                }
            } else if (flag >= 2) {
                readBytes(flag + 2)
            } else if (flag == 0) {
                readBytes(readU8() + 0x12)
            } else {
                val length = (readU8() shl 8) or readU8()
                if (length == 0 && op == expectedSize) break
                readBytes(length)
            }
        }
        require(op == expectedSize) { "OC31 output length mismatch: $op != $expectedSize" }
        return out
    }

    /** Scan a legacy KORG resource for framed OC31 chunks and return successfully decoded blocks. */
    fun scanBlocks(file: ByteArray, maxBlocks: Int = 10000): List<Block> {
        val result = ArrayList<Block>()
        var marker = 4
        while (marker + 8 <= file.size && result.size < maxBlocks) {
            if (file[marker] == 'O'.code.toByte() && file[marker + 1] == 'C'.code.toByte() &&
                file[marker + 2] == '3'.code.toByte() && file[marker + 3] == '1'.code.toByte()) {
                val sizeOffset = marker - 4
                val compressedSize = ((file[sizeOffset].toInt() and 0xff) shl 24) or
                    ((file[sizeOffset + 1].toInt() and 0xff) shl 16) or
                    ((file[sizeOffset + 2].toInt() and 0xff) shl 8) or (file[sizeOffset + 3].toInt() and 0xff)
                val uncompressedSize = (file[marker + 4].toInt() and 0xff) or
                    ((file[marker + 5].toInt() and 0xff) shl 8) or
                    ((file[marker + 6].toInt() and 0xff) shl 16) or
                    ((file[marker + 7].toInt() and 0xff) shl 24)
                // On legacy STY banks, compressedSize includes the 8-byte OC31 header and a small trailer.
                val payloadStart = marker + 8
                val payloadLength = compressedSize - 12
                if (compressedSize in 12..(file.size - sizeOffset) && uncompressedSize in 1..64_000_000 &&
                    payloadLength > 0 && payloadStart + payloadLength <= file.size) {
                    try {
                        val payload = file.copyOfRange(payloadStart, payloadStart + payloadLength)
                        val decoded = decompress(payload, uncompressedSize)
                        result.add(Block(marker, compressedSize, uncompressedSize, decoded))
                        marker = payloadStart + payloadLength
                        continue
                    } catch (_: Exception) {
                        // Not every literal "OC31" sequence is a valid framed block. Keep scanning.
                    }
                }
            }
            marker++
        }
        return result
    }
}
