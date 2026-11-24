package io.github.rudicilibrasi.pianotrainer.ui.graphs

import io.github.rudicilibrasi.pianotrainer.core.analysis.AnalysisResult
import io.github.rudicilibrasi.pianotrainer.core.analysis.GraphData
import io.github.rudicilibrasi.pianotrainer.core.music.Phrase
import io.github.rudicilibrasi.pianotrainer.core.music.SongFormat
import io.github.rudicilibrasi.pianotrainer.core.scoring.PhraseScorer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphChoicesTest {

    private val result = AnalysisResult(1, 44100, 1.0, emptyList(), emptyList(), GraphData(0.01, 40.0, Array(100) { FloatArray(10) }, FloatArray(100), List(100) { null }))
    private val phrase = Phrase(0, SongFormat.parseEvents("C4/1 D4/1"), 0.0)
    private val comparison = ComparisonInput(phrase, 120.0, emptyList(), PhraseScorer.score(phrase, 120.0, emptyList()))

    @Test
    fun theLessonScreenShowsTheReferenceGraphs() {
        val choices = GraphChoices.of(reference = result, yours = null, comparison = null)
        assertEquals(listOf(GraphKind.Spectrogram, GraphKind.Amplitude, GraphKind.Pitch), choices.kinds)
        assertEquals(listOf(GraphSource.Reference), choices.sources)
        assertFalse(choices.showsSourceToggle(GraphKind.Pitch), "nothing to toggle between")
    }

    @Test
    fun theResultsScreenShowsAllFour() {
        val choices = GraphChoices.of(reference = result, yours = result, comparison = comparison)
        assertEquals(GraphKind.entries.toList(), choices.kinds)
        assertEquals(listOf(GraphSource.Reference, GraphSource.You), choices.sources)
        assertTrue(choices.showsSourceToggle(GraphKind.Spectrogram))
        assertTrue(choices.showsSourceToggle(GraphKind.Amplitude))
        assertTrue(choices.showsSourceToggle(GraphKind.Pitch))
        assertFalse(choices.showsSourceToggle(GraphKind.Compare), "Compare shows both at once")
    }

    @Test
    fun withOnlyTheComparisonOnlyCompareIsOffered() {
        val choices = GraphChoices.of(reference = null, yours = null, comparison = comparison)
        assertEquals(listOf(GraphKind.Compare), choices.kinds)
    }

    @Test
    fun theDefaultSourceExistsWhileTheRecordingsAreStillLoading() {
        // The results screen opens with the comparison before either recording has loaded; picking
        // a source from an empty list used to crash it.
        val choices = GraphChoices.of(reference = null, yours = null, comparison = comparison)
        assertEquals(emptyList(), choices.sources)
        assertEquals(GraphSource.You, choices.defaultSource)
        assertEquals(GraphSource.You, GraphChoices.of(result, result, comparison).defaultSource)
        assertEquals(GraphSource.Reference, GraphChoices.of(result, null, null).defaultSource)
    }

    @Test
    fun withNothingThereAreNoGraphs() {
        assertEquals(emptyList(), GraphChoices.of(null, null, null).kinds)
    }
}
