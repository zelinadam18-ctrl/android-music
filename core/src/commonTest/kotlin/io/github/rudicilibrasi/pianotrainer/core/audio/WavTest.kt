package io.github.rudicilibrasi.pianotrainer.core.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WavTest {

    @Test
    fun headerFieldsDescribe16BitMonoPcm() {
        val bytes = Wav.encode(floatArrayOf(0f, 0.5f, -0.5f), sampleRate = 44100)
        assertEquals(44 + 6, bytes.size)
        assertEquals("RIFF", bytes.ascii(0))
        assertEquals(42, bytes.le32(4))
        assertEquals("WAVE", bytes.ascii(8))
        assertEquals("fmt ", bytes.ascii(12))
        assertEquals(16, bytes.le32(16))
        assertEquals(1, bytes.le16(20)) // PCM
        assertEquals(1, bytes.le16(22)) // mono
        assertEquals(44100, bytes.le32(24))
        assertEquals(88200, bytes.le32(28)) // byte rate
        assertEquals(2, bytes.le16(32)) // block align
        assertEquals(16, bytes.le16(34))
        assertEquals("data", bytes.ascii(36))
        assertEquals(6, bytes.le32(40))
    }

    @Test
    fun sixteenBitDataSurvivesDecodeAndEncodeExactly() {
        val values = listOf(0, 1, -1, 1000, -1000, 32767, -32768)
        val original = TestWav(channels = 1, sampleRate = 48000).data(values).build()
        val clip = Wav.decode(original)
        assertEquals(48000, clip.sampleRate)
        assertContentEquals(original, Wav.encode(clip))
    }

    @Test
    fun samplesSurviveEncodeAndDecodeWithin16BitPrecision() {
        val samples = FloatArray(1000) { (0.9 * sin(2 * PI * 440 * it / 44100)).toFloat() }
        val clip = Wav.decode(Wav.encode(samples, 44100))
        assertEquals(samples.size, clip.samples.size)
        for (i in samples.indices) {
            assertTrue(abs(samples[i] - clip.samples[i]) <= 1f / 32768, "sample $i")
        }
        assertEquals(1000.0 / 44100, clip.durationSeconds, absoluteTolerance = 1e-12)
    }

    @Test
    fun clipsSamplesOutsideTheValidRange() {
        val clip = Wav.decode(Wav.encode(floatArrayOf(2f, -3f, 1f, -1f), 8000))
        assertContentEquals(floatArrayOf(32767 / 32768f, -1f, 32767 / 32768f, -1f), clip.samples)
    }

    @Test
    fun mixesStereoDownToMono() {
        val bytes = TestWav(channels = 2, sampleRate = 44100).data(listOf(16384, -16384, 8192, 8192)).build()
        val clip = Wav.decode(bytes)
        assertContentEquals(floatArrayOf(0f, 0.25f), clip.samples)
    }

    @Test
    fun skipsUnknownChunksIncludingOddSizedOnes() {
        val bytes = TestWav(channels = 1, sampleRate = 44100)
            .chunk("LIST", ByteArray(5) { 7 })
            .data(listOf(100, -100))
            .build()
        assertContentEquals(floatArrayOf(100 / 32768f, -100 / 32768f), Wav.decode(bytes).samples)
    }

    @Test
    fun usesTheSamplesPresentWhenTheDataChunkClaimsToBeLonger() {
        // As left by a recording that stopped before its header was updated.
        val bytes = TestWav(channels = 1, sampleRate = 44100).data(listOf(1, 2, 3), declaredSize = -1).build()
        assertEquals(3, Wav.decode(bytes).samples.size)
    }

    @Test
    fun acceptsExtensibleFormatWithPcmSubFormat() {
        val bytes = TestWav(channels = 1, sampleRate = 44100, extensible = true).data(listOf(5)).build()
        assertContentEquals(floatArrayOf(5 / 32768f), Wav.decode(bytes).samples)
    }

    @Test
    fun rejectsFilesItCannotRead() {
        val cases = mapOf(
            "too short" to ByteArray(4),
            "not RIFF" to TestWav(1, 44100).data(listOf(0)).build().also { it[0] = 'X'.code.toByte() },
            "8-bit" to TestWav(1, 44100, bitsPerSample = 8).data(listOf(0)).build(),
            "float" to TestWav(1, 44100, audioFormat = 3).data(listOf(0)).build(),
            "3 channels" to TestWav(3, 44100).data(listOf(0, 0, 0)).build(),
            "no data chunk" to TestWav(1, 44100).build(),
            "no fmt chunk" to TestWav(1, 44100, includeFmt = false).data(listOf(0)).build(),
        )
        for ((name, bytes) in cases) {
            assertFailsWith<WavFormatException>(name) { Wav.decode(bytes) }
        }
    }

    @Test
    fun headerRejectsInvalidArguments() {
        assertFailsWith<IllegalArgumentException> { Wav.header(sampleRate = 0, dataSize = 0) }
        assertFailsWith<IllegalArgumentException> { Wav.header(sampleRate = 44100, dataSize = -2) }
    }
}

/** Builds WAV files by hand, including variants the encoder never produces. */
private class TestWav(
    val channels: Int,
    val sampleRate: Int,
    val bitsPerSample: Int = 16,
    val audioFormat: Int = 1,
    val extensible: Boolean = false,
    val includeFmt: Boolean = true,
) {
    private val chunks = mutableListOf<ByteArray>()

    fun chunk(id: String, body: ByteArray, declaredSize: Int = body.size): TestWav {
        val padded = if (body.size % 2 == 1) body + 0 else body
        chunks += id.encodeToByteArray() + le32(declaredSize) + padded
        return this
    }

    fun data(values: List<Int>, declaredSize: Int? = null): TestWav {
        val body = values.flatMap { le16(it).toList() }.toByteArray()
        return chunk("data", body, declaredSize ?: body.size)
    }

    fun build(): ByteArray {
        val blockAlign = channels * bitsPerSample / 8
        val fmt = if (!extensible) {
            le16(audioFormat) + le16(channels) + le32(sampleRate) + le32(sampleRate * blockAlign) +
                le16(blockAlign) + le16(bitsPerSample)
        } else {
            le16(0xFFFE) + le16(channels) + le32(sampleRate) + le32(sampleRate * blockAlign) +
                le16(blockAlign) + le16(bitsPerSample) + le16(22) + le16(bitsPerSample) + le32(4) +
                le16(audioFormat) + ByteArray(14)
        }
        val fmtChunk = if (includeFmt) "fmt ".encodeToByteArray() + le32(fmt.size) + fmt else ByteArray(0)
        val body = "WAVE".encodeToByteArray() + fmtChunk + chunks.fold(ByteArray(0)) { acc, c -> acc + c }
        return "RIFF".encodeToByteArray() + le32(body.size) + body
    }
}

private fun le16(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte())

private fun le32(value: Int) = ByteArray(4) { (value shr (8 * it)).toByte() }

private fun ByteArray.ascii(offset: Int) = decodeToString(offset, offset + 4)

private fun ByteArray.le16(offset: Int) = (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

private fun ByteArray.le32(offset: Int) = (0 until 4).fold(0) { acc, i -> acc or ((this[offset + i].toInt() and 0xFF) shl (8 * i)) }
