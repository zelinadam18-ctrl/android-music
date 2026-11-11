package io.github.rudicilibrasi.pianotrainer.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SampleBufferTest {

    @Test
    fun joinsChunksInOrder() {
        val buffer = SampleBuffer()
        buffer.add(floatArrayOf(0.1f, 0.2f))
        buffer.add(floatArrayOf(0.3f, 0.4f, 0.5f), count = 2)
        buffer.add(FloatArray(0))
        assertEquals(4, buffer.size)
        assertContentEquals(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f), buffer.toArray())
    }

    @Test
    fun clearStartsAgain() {
        val buffer = SampleBuffer()
        buffer.add(floatArrayOf(1f))
        buffer.clear()
        assertEquals(0, buffer.size)
        assertContentEquals(FloatArray(0), buffer.toArray())
    }
}
