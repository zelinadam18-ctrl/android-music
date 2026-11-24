package io.github.rudicilibrasi.pianotrainer.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import io.github.rudicilibrasi.pianotrainer.core.music.Phrase

/**
 * Which note is sounding during reference playback (#30), from the phrase's note start times
 * (the same times the reference is rendered from, #21) and the player's position.
 */
class PlaybackHighlight(phrase: Phrase, tempoBpm: Double) {
    private val notes = phrase.timedEvents(tempoBpm).filter { !it.event.isRest }

    /**
     * Index among the phrase's notes (rests not counted) of the note sounding at [positionMs], or
     * null when nothing is playing, before the first note, during a rest, or after the last note
     * ends. Pass null for [positionMs] when playback is stopped, which clears the highlight.
     */
    fun noteAt(positionMs: Long?): Int? {
        if (positionMs == null) return null
        val index = notes.indexOfLast { it.startMs <= positionMs }
        if (index < 0) return null
        return index.takeIf { positionMs < notes[index].endMs }
    }
}

/** The notes as "C4 — C4 — G4 …", with [highlightIndex] (a note index, rests not counted) emphasised. */
@Composable
fun NoteLetters(phrase: Phrase, highlightIndex: Int?, modifier: Modifier = Modifier) {
    val labels = phrase.notes.map { it.pitch!!.label }
    val highlight = MaterialTheme.colorScheme.primary
    val text = buildAnnotatedString {
        labels.forEachIndexed { i, label ->
            if (i > 0) append(" — ")
            if (i == highlightIndex) {
                withStyle(SpanStyle(color = highlight, fontWeight = FontWeight.Bold)) { append(label) }
            } else {
                append(label)
            }
        }
    }
    val description = labels.joinToString(", ") + (highlightIndex?.let { ". Now playing ${labels.getOrNull(it)}" } ?: "")
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = modifier.semantics { contentDescription = description })
}
