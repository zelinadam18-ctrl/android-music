package io.github.rudicilibrasi.pianotrainer.core.analysis

import io.github.rudicilibrasi.pianotrainer.core.audio.AudioClip
import io.github.rudicilibrasi.pianotrainer.core.audio.Wav
import io.github.rudicilibrasi.pianotrainer.core.dsp.Spectrogram
import io.github.rudicilibrasi.pianotrainer.core.dsp.Stft
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Graph data, already downsampled for display: at most [AudioAnalyzer.MAX_COLUMNS] time columns,
 * so drawing stays smooth on phones. Column `c` covers `c · columnSeconds` to `(c + 1) · columnSeconds`.
 *
 * @property spectrogramDb `[column][row]` level in dB (0 dB = full-scale sine, floor −120 dB).
 *   Row `r` covers `r · rowHz` to `(r + 1) · rowHz`, up to [AudioAnalyzer.MAX_GRAPH_HZ].
 * @property amplitudeDb RMS level per column in dBFS (floor −120 dB).
 * @property pitchHz median fundamental per column, or null where nothing was voiced.
 */
class GraphData(
    val columnSeconds: Double,
    val rowHz: Double,
    val spectrogramDb: Array<FloatArray>,
    val amplitudeDb: FloatArray,
    val pitchHz: List<Double?>,
) {
    val columnCount: Int get() = amplitudeDb.size
    val rowCount: Int get() = spectrogramDb.firstOrNull()?.size ?: 0
}

/**
 * Everything the app shows and stores about one recording.
 *
 * @property analysisVersion which version of the algorithms produced this, so saved history
 *   records it (see [AudioAnalyzer.ANALYSIS_VERSION]).
 */
class AnalysisResult(
    val analysisVersion: Int,
    val sampleRate: Int,
    val durationSeconds: Double,
    val notes: List<DetectedNote>,
    val onsets: List<Onset>,
    val graphs: GraphData,
)

/**
 * The single entry point that analyses any recording, reference or student, the same way
 * (spec §9, §10): WAV → STFT, amplitude envelope, pitch track and detected notes.
 *
 * [analyze] runs on a background dispatcher, checks for cancellation between stages, and reports
 * progress from 0 to 1 for the loading state.
 */
class AudioAnalyzer(private val dispatcher: CoroutineDispatcher = Dispatchers.Default) {

    suspend fun analyze(wav: ByteArray, onProgress: (Float) -> Unit = {}): AnalysisResult =
        withContext(dispatcher) {
            val clip = Wav.decode(wav)
            coroutineContext.ensureActive()
            analyzeClip(clip, onProgress)
        }

    suspend fun analyze(clip: AudioClip, onProgress: (Float) -> Unit = {}): AnalysisResult =
        withContext(dispatcher) { analyzeClip(clip, onProgress) }

    private suspend fun analyzeClip(clip: AudioClip, onProgress: (Float) -> Unit): AnalysisResult {
        val samples = clip.samples
        val sampleRate = clip.sampleRate
        onProgress(0f)

        val notes = NoteDetector(sampleRate).analyze(samples)
        coroutineContext.ensureActive()
        onProgress(0.6f)

        val spectrogram = Stft.compute(samples, sampleRate)
        coroutineContext.ensureActive()
        onProgress(0.85f)

        val graphs = graphs(samples, sampleRate, spectrogram, notes.pitchFrames)
        coroutineContext.ensureActive()
        onProgress(1f)

        return AnalysisResult(
            analysisVersion = ANALYSIS_VERSION,
            sampleRate = sampleRate,
            durationSeconds = samples.size.toDouble() / sampleRate,
            notes = notes.notes,
            onsets = notes.onsets,
            graphs = graphs,
        )
    }

    private fun graphs(
        samples: FloatArray,
        sampleRate: Int,
        spectrogram: Spectrogram,
        pitchFrames: List<PitchFrame>,
    ): GraphData {
        val duration = samples.size.toDouble() / sampleRate
        val columns = max(1, min(MAX_COLUMNS, spectrogram.frameCount))
        val columnSeconds = if (duration > 0) duration / columns else 0.0
        fun columnOf(timeSeconds: Double): Int =
            if (columnSeconds <= 0) 0 else (timeSeconds / columnSeconds).toInt().coerceIn(0, columns - 1)

        // Spectrogram: keep bins up to MAX_GRAPH_HZ, max-pooled into at most MAX_ROWS rows and
        // MAX_COLUMNS columns, so short onsets and quiet partials stay visible.
        val usableBins = min(spectrogram.binCount, (MAX_GRAPH_HZ / spectrogram.binWidthHz).toInt() + 1)
        val binsPerRow = max(1, (usableBins + MAX_ROWS - 1) / MAX_ROWS)
        val rows = (usableBins + binsPerRow - 1) / binsPerRow
        val image = Array(columns) { FloatArray(rows) { Stft.FLOOR_DB } }
        for (frame in 0 until spectrogram.frameCount) {
            val column = image[columnOf(spectrogram.frameTimeSeconds(frame))]
            val magnitudes = spectrogram.magnitudesDb[frame]
            for (bin in 0 until usableBins) {
                val row = bin / binsPerRow
                if (magnitudes[bin] > column[row]) column[row] = magnitudes[bin]
            }
        }

        // Amplitude envelope: RMS of the samples in each column.
        val amplitude = FloatArray(columns) { c ->
            val from = (c * columnSeconds * sampleRate).toInt().coerceIn(0, samples.size)
            val to = if (c == columns - 1) samples.size else ((c + 1) * columnSeconds * sampleRate).toInt().coerceIn(from, samples.size)
            if (to <= from) {
                Stft.FLOOR_DB
            } else {
                var sum = 0.0
                for (i in from until to) sum += samples[i].toDouble() * samples[i]
                val rms = sqrt(sum / (to - from))
                if (rms <= 0.0) Stft.FLOOR_DB else max(Stft.FLOOR_DB.toDouble(), 20 * log10(rms)).toFloat()
            }
        }

        // Pitch track: median of the voiced frames in each column.
        val perColumn = List(columns) { ArrayList<Double>() }
        for (frame in pitchFrames) frame.frequencyHz?.let { perColumn[columnOf(frame.timeSeconds)] += it }
        val pitch = perColumn.map { values -> values.sorted().takeIf { it.isNotEmpty() }?.let { it[it.size / 2] } }

        return GraphData(columnSeconds, binsPerRow * spectrogram.binWidthHz, image, amplitude, pitch)
    }

    companion object {
        /**
         * Bump whenever detection or analysis changes in a way that could change results, so saved
         * history records which algorithm produced it.
         */
        const val ANALYSIS_VERSION = 2

        /** Most time columns in any graph. */
        const val MAX_COLUMNS = 400

        /** Most frequency rows in the spectrogram graph. */
        const val MAX_ROWS = 128

        /** Highest frequency shown in the spectrogram: covers the partials that matter for piano. */
        const val MAX_GRAPH_HZ = 5000.0
    }
}
