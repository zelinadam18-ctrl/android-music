package io.github.rudicilibrasi.pianotrainer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.rudicilibrasi.pianotrainer.core.analysis.AnalysisResult
import io.github.rudicilibrasi.pianotrainer.core.analysis.AudioAnalyzer
import io.github.rudicilibrasi.pianotrainer.core.music.Phrase
import io.github.rudicilibrasi.pianotrainer.data.AppStorage
import io.github.rudicilibrasi.pianotrainer.data.ReferenceAudioCache
import io.github.rudicilibrasi.pianotrainer.data.StoredFile
import io.github.rudicilibrasi.pianotrainer.database.Attempt
import io.github.rudicilibrasi.pianotrainer.ui.graphs.ComparisonInput
import io.github.rudicilibrasi.pianotrainer.ui.graphs.GraphSwitcher

/** Shown wherever playback fails, with what to try next (#65). */
const val PLAYBACK_FAILED = "The audio couldn't be played. Check the volume and that no other app is using the speaker, then try again."

/** A saved recording, ready to play and draw, or why it can't be. */
sealed interface SavedRecording {
    class Available(val wav: ByteArray, val analysis: AnalysisResult) : SavedRecording
    data class Unavailable(val message: String) : SavedRecording
}

/** The reference and the student's recording for one saved attempt (#62). */
class AttemptAudio(val reference: SavedRecording, val yours: SavedRecording) {
    companion object {
        const val UNAVAILABLE = "Recording unavailable"

        /**
         * Loads both. The student's recording comes only from its saved file: if that's missing or
         * corrupt, it's unavailable (it can't be recreated). The reference is regenerated from the
         * song data whenever the phrase still has the attempt's notes, so a deleted cache file
         * doesn't matter; otherwise the saved reference file is used.
         */
        suspend fun load(
            attempt: Attempt,
            phrase: Phrase?,
            tempoBpm: Double?,
            storage: AppStorage?,
            references: ReferenceAudioCache?,
            analyzer: AudioAnalyzer,
        ): AttemptAudio {
            val yours = load(attempt.userWavPath, storage, analyzer)
            val reference = if (phrase != null && tempoBpm != null && references != null) {
                analyse(runCatching { references.reference(attempt.songId, phrase, tempoBpm).wav }.getOrNull(), analyzer)
            } else {
                load(attempt.referenceWavPath, storage, analyzer)
            }
            return AttemptAudio(reference, yours)
        }

        private suspend fun load(path: String?, storage: AppStorage?, analyzer: AudioAnalyzer): SavedRecording {
            if (path == null || storage == null) return SavedRecording.Unavailable(UNAVAILABLE)
            return when (val file = runCatching { storage.read(path) }.getOrElse { StoredFile.Unreadable(it.message ?: "") }) {
                is StoredFile.Found -> analyse(file.bytes, analyzer)
                StoredFile.Missing, is StoredFile.Unreadable -> SavedRecording.Unavailable(UNAVAILABLE)
            }
        }

        /** A WAV that won't decode (a corrupt file) is unavailable too, never a crash. */
        private suspend fun analyse(wav: ByteArray?, analyzer: AudioAnalyzer): SavedRecording {
            wav ?: return SavedRecording.Unavailable(UNAVAILABLE)
            return try {
                SavedRecording.Available(wav, analyzer.analyze(wav))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                SavedRecording.Unavailable(UNAVAILABLE)
            }
        }
    }
}

/**
 * Playback of the reference and the student's recording, and the graphs (#59). Whatever is
 * unavailable says "Recording unavailable" in place of its player and graphs (#62).
 */
@Composable
fun AttemptAudioSection(audio: AttemptAudio?, comparison: ComparisonInput?, onPlay: (ByteArray) -> Unit, playbackFailed: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (audio == null) {
            Text("Loading the recordings…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PlayButton("Play reference", audio.reference, onPlay, Modifier.weight(1f))
                PlayButton("Play my recording", audio.yours, onPlay, Modifier.weight(1f))
            }
            (audio.yours as? SavedRecording.Unavailable)?.let {
                Text(it.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (playbackFailed) {
                Text(PLAYBACK_FAILED, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }
        GraphSwitcher(
            reference = (audio?.reference as? SavedRecording.Available)?.analysis,
            yours = (audio?.yours as? SavedRecording.Available)?.analysis,
            comparison = comparison,
        )
    }
}

@Composable
private fun PlayButton(label: String, recording: SavedRecording, onPlay: (ByteArray) -> Unit, modifier: Modifier) {
    val available = recording as? SavedRecording.Available
    OutlinedButton(onClick = { available?.let { onPlay(it.wav) } }, enabled = available != null, modifier = modifier) {
        Text(if (available != null) label else AttemptAudio.UNAVAILABLE)
    }
}
