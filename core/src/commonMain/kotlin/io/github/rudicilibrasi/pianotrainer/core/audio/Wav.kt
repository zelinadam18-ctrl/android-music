package io.github.rudicilibrasi.pianotrainer.core.audio

import kotlin.math.roundToInt

/** Mono audio: samples between −1 and 1 at [sampleRate] samples per second. */
class AudioClip(val samples: FloatArray, val sampleRate: Int) {
    val durationSeconds: Double get() = samples.size.toDouble() / sampleRate
}

/** Thrown when bytes are not a WAV file this app can read. */
class WavFormatException(message: String) : IllegalArgumentException(message)

/**
 * Reads and writes WAV files as byte arrays (file access is handled elsewhere).
 *
 * Writes 16-bit PCM mono. Reads 16-bit PCM mono or stereo; stereo is mixed down to mono.
 * Samples are scaled by 32768 in both directions, so 16-bit data survives a decode/encode
 * round trip exactly.
 */
object Wav {
    const val HEADER_SIZE = 44

    private const val BITS_PER_SAMPLE = 16
    private const val BYTES_PER_SAMPLE = 2
    private const val FORMAT_PCM = 1
    private const val FORMAT_EXTENSIBLE = 0xFFFE
    private const val SCALE = 32768f

    /** Encodes mono [samples] as a 16-bit PCM WAV file. Samples outside −1…1 are clipped. */
    fun encode(samples: FloatArray, sampleRate: Int): ByteArray {
        val bytes = header(sampleRate, dataSize = samples.size * BYTES_PER_SAMPLE).copyOf(HEADER_SIZE + samples.size * BYTES_PER_SAMPLE)
        var offset = HEADER_SIZE
        for (sample in samples) {
            val value = (sample * SCALE).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            bytes.putInt16(offset, value)
            offset += BYTES_PER_SAMPLE
        }
        return bytes
    }

    fun encode(clip: AudioClip): ByteArray = encode(clip.samples, clip.sampleRate)

    /**
     * The 44-byte header for a 16-bit PCM mono WAV whose sample data is [dataSize] bytes long.
     * Recorders can write this with a placeholder size, stream the samples, then rewrite it.
     */
    fun header(sampleRate: Int, dataSize: Int): ByteArray {
        require(sampleRate > 0) { "Sample rate must be positive, but was $sampleRate" }
        require(dataSize >= 0) { "Data size must not be negative, but was $dataSize" }
        val bytes = ByteArray(HEADER_SIZE)
        bytes.putAscii(0, "RIFF")
        bytes.putInt32(4, HEADER_SIZE - 8 + dataSize)
        bytes.putAscii(8, "WAVE")
        bytes.putAscii(12, "fmt ")
        bytes.putInt32(16, 16)
        bytes.putInt16(20, FORMAT_PCM)
        bytes.putInt16(22, 1)
        bytes.putInt32(24, sampleRate)
        bytes.putInt32(28, sampleRate * BYTES_PER_SAMPLE)
        bytes.putInt16(32, BYTES_PER_SAMPLE)
        bytes.putInt16(34, BITS_PER_SAMPLE)
        bytes.putAscii(36, "data")
        bytes.putInt32(40, dataSize)
        return bytes
    }

    /**
     * Decodes a 16-bit PCM WAV file (mono, or stereo mixed down to mono).
     *
     * Unknown chunks are skipped. If the data chunk claims to be longer than the file (for example
     * because a recording was interrupted before its header was updated), the samples that are
     * present are used.
     *
     * @throws WavFormatException if the bytes are not a WAV file in a supported format.
     */
    fun decode(bytes: ByteArray): AudioClip {
        if (bytes.size < 12 || bytes.ascii(0, 4) != "RIFF" || bytes.ascii(8, 4) != "WAVE") {
            throw WavFormatException("Not a WAV file (missing RIFF/WAVE header)")
        }
        var format: Format? = null
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val id = bytes.ascii(offset, 4)
            val declaredSize = bytes.uint32(offset + 4)
            val bodyStart = offset + 8
            when (id) {
                "fmt " -> format = readFormat(bytes, bodyStart, declaredSize)
                "data" -> {
                    val fmt = format ?: throw WavFormatException("WAV data chunk comes before its fmt chunk")
                    val available = minOf(declaredSize, (bytes.size - bodyStart).toLong()).toInt()
                    return AudioClip(readSamples(bytes, bodyStart, available, fmt.channels), fmt.sampleRate)
                }
            }
            // Chunks are padded to an even number of bytes.
            val next = bodyStart + declaredSize + (declaredSize and 1L)
            if (next > bytes.size) break
            offset = next.toInt()
        }
        throw WavFormatException("WAV file has no data chunk")
    }

    private class Format(val channels: Int, val sampleRate: Int)

    private fun readFormat(bytes: ByteArray, start: Int, size: Long): Format {
        if (size < 16 || start + 16 > bytes.size) throw WavFormatException("WAV fmt chunk is too short")
        var audioFormat = bytes.uint16(start)
        if (audioFormat == FORMAT_EXTENSIBLE) {
            // The real format is the first two bytes of the sub-format GUID.
            if (size < 40 || start + 26 > bytes.size) throw WavFormatException("WAV extensible fmt chunk is too short")
            audioFormat = bytes.uint16(start + 24)
        }
        val channels = bytes.uint16(start + 2)
        val sampleRate = bytes.uint32(start + 4).toInt()
        val bitsPerSample = bytes.uint16(start + 14)
        if (audioFormat != FORMAT_PCM) throw WavFormatException("Unsupported WAV encoding $audioFormat; only PCM is supported")
        if (bitsPerSample != BITS_PER_SAMPLE) throw WavFormatException("Unsupported WAV sample size $bitsPerSample bits; only 16-bit is supported")
        if (channels != 1 && channels != 2) throw WavFormatException("Unsupported WAV channel count $channels; only mono and stereo are supported")
        if (sampleRate <= 0) throw WavFormatException("Invalid WAV sample rate $sampleRate")
        return Format(channels, sampleRate)
    }

    private fun readSamples(bytes: ByteArray, start: Int, length: Int, channels: Int): FloatArray {
        val frameSize = BYTES_PER_SAMPLE * channels
        val frames = length / frameSize
        return FloatArray(frames) { frame ->
            val offset = start + frame * frameSize
            if (channels == 1) {
                bytes.int16(offset) / SCALE
            } else {
                (bytes.int16(offset) + bytes.int16(offset + BYTES_PER_SAMPLE)) / (2 * SCALE)
            }
        }
    }
}

private fun ByteArray.putAscii(offset: Int, text: String) {
    text.forEachIndexed { i, c -> this[offset + i] = c.code.toByte() }
}

private fun ByteArray.putInt16(offset: Int, value: Int) {
    this[offset] = value.toByte()
    this[offset + 1] = (value shr 8).toByte()
}

private fun ByteArray.putInt32(offset: Int, value: Int) {
    for (i in 0 until 4) this[offset + i] = (value shr (8 * i)).toByte()
}

private fun ByteArray.ascii(offset: Int, length: Int): String =
    (offset until offset + length).map { Char(this[it].toInt() and 0xFF) }.joinToString("")

private fun ByteArray.uint16(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

private fun ByteArray.int16(offset: Int): Int = uint16(offset).toShort().toInt()

private fun ByteArray.uint32(offset: Int): Long =
    (0 until 4).fold(0L) { acc, i -> acc or ((this[offset + i].toLong() and 0xFF) shl (8 * i)) }
