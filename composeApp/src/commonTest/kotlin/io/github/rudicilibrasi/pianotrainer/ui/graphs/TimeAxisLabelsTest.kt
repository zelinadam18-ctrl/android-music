package io.github.rudicilibrasi.pianotrainer.ui.graphs

import kotlin.test.Test
import kotlin.test.assertEquals

class TimeAxisLabelsTest {

    @Test
    fun labelsWithRoomAreCentredOnTheirTicks() {
        assertEquals(listOf(0f, 90f, 190f), TimeAxis.labelLefts(listOf(0f, 100f, 200f), listOf(20f, 20f, 20f), 0f, 300f, 4f))
    }

    @Test
    fun aLabelThatWouldTouchThePreviousOneIsSkipped() {
        // "0 s" is pushed right to stay inside the graph, so "1 s" would run into it.
        assertEquals(listOf(0f, null, 70f), TimeAxis.labelLefts(listOf(0f, 40f, 90f), listOf(40f, 40f, 40f), 0f, 300f, 4f))
    }

    @Test
    fun theLastLabelStaysInsideTheGraph() {
        assertEquals(listOf(0f, 260f), TimeAxis.labelLefts(listOf(0f, 300f), listOf(20f, 40f), 0f, 300f, 4f))
    }
}
