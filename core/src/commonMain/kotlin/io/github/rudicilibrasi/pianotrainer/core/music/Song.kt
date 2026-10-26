package io.github.rudicilibrasi.pianotrainer.core.music

/**
 * One note or rest. Durations are in beats, where a beat is a quarter note
 * (so a half note is 2.0 and a dotted quarter is 1.5).
 */
data class NoteEvent(val pitch: Pitch?, val beats: Double) {

    init {
        require(beats.isFinite() && beats > 0.0) { "Duration must be a positive number of beats, but was $beats" }
    }

    val isRest: Boolean get() = pitch == null

    override fun toString(): String = "${pitch?.label ?: "R"}/$beats"

    companion object {
        fun note(label: String, beats: Double): NoteEvent = NoteEvent(Pitch.parse(label), beats)

        fun rest(beats: Double): NoteEvent = NoteEvent(null, beats)
    }
}

data class TimeSignature(val beatsPerBar: Int, val beatUnit: Int) {

    init {
        require(beatsPerBar > 0) { "Beats per bar must be positive, but was $beatsPerBar" }
        require(beatUnit in setOf(1, 2, 4, 8, 16)) { "Beat unit must be 1, 2, 4, 8 or 16, but was $beatUnit" }
    }

    /** Length of one bar in quarter-note beats (3/4 → 3.0, 6/8 → 3.0, 2/2 → 4.0). */
    val quarterBeatsPerBar: Double get() = beatsPerBar * 4.0 / beatUnit

    override fun toString(): String = "$beatsPerBar/$beatUnit"
}

/** A note or rest with its position in time, measured from the start of its phrase. */
data class TimedEvent(
    val event: NoteEvent,
    val startBeat: Double,
    val startMs: Double,
    val durationMs: Double,
) {
    val endMs: Double get() = startMs + durationMs
}

/**
 * One learning phrase: a short section of a song that the student practises and is scored on.
 *
 * @property index 0-based position in the song; show [number] to people.
 * @property startBeat beat at which this phrase starts, counted from the start of the song.
 */
class Phrase(val index: Int, val events: List<NoteEvent>, val startBeat: Double) {

    init {
        require(events.any { !it.isRest }) { "Phrase ${index + 1} has no notes" }
    }

    /** 1-based phrase number for display ("Phrase 3 of 6"). */
    val number: Int get() = index + 1

    /** The notes to play, without rests. */
    val notes: List<NoteEvent> get() = events.filter { !it.isRest }

    val noteCount: Int get() = events.count { !it.isRest }

    /** Total length including rests. */
    val durationBeats: Double get() = events.sumOf { it.beats }

    fun durationMs(tempoBpm: Double): Double = beatsToMs(durationBeats, tempoBpm)

    /** Every note and rest with its start and length, measured from the start of this phrase. */
    fun timedEvents(tempoBpm: Double): List<TimedEvent> {
        var beat = 0.0
        return events.map { event ->
            TimedEvent(event, beat, beatsToMs(beat, tempoBpm), beatsToMs(event.beats, tempoBpm)).also { beat += event.beats }
        }
    }
}

/**
 * A song as structured data. This is the single source of truth that the reference audio, the
 * staff, note detection targets and scoring all read from.
 *
 * Phrase boundaries come from the song data (one list of events per phrase), never from a fixed
 * note count.
 *
 * @property tempoBpm quarter-note beats per minute.
 * @property pickupBeats length of any pickup (anacrusis) before the first full bar, in beats.
 * @property source where the notes were taken from (a URL or "traditional").
 * @property sourceVerified true once a person has checked every note against [source].
 */
class Song(
    val id: String,
    val title: String,
    val composer: String?,
    val tempoBpm: Double,
    val timeSignature: TimeSignature,
    val key: String,
    val source: String,
    val license: String,
    val sourceVerified: Boolean,
    phrases: List<List<NoteEvent>>,
    val pickupBeats: Double = 0.0,
) {
    val phrases: List<Phrase>

    init {
        require(id.isNotBlank()) { "Song id must not be blank" }
        require(title.isNotBlank()) { "Song \"$id\" has no title" }
        require(tempoBpm.isFinite() && tempoBpm > 0.0) { "Song \"$id\" tempo must be positive, but was $tempoBpm" }
        require(pickupBeats >= 0.0 && pickupBeats < timeSignature.quarterBeatsPerBar) {
            "Song \"$id\" pickup must be shorter than one bar, but was $pickupBeats beats"
        }
        require(phrases.isNotEmpty()) { "Song \"$id\" has no phrases" }
        var startBeat = 0.0
        this.phrases = phrases.mapIndexed { index, events ->
            Phrase(index, events, startBeat).also { startBeat += it.durationBeats }
        }
    }

    /** Every note and rest in the song, in order. */
    val events: List<NoteEvent> get() = phrases.flatMap { it.events }

    val noteCount: Int get() = phrases.sumOf { it.noteCount }

    val durationBeats: Double get() = phrases.sumOf { it.durationBeats }

    val msPerBeat: Double get() = beatsToMs(1.0, tempoBpm)

    fun phraseDurationMs(phrase: Phrase): Double = phrase.durationMs(tempoBpm)

    fun timedEvents(phrase: Phrase): List<TimedEvent> = phrase.timedEvents(tempoBpm)
}

private fun beatsToMs(beats: Double, tempoBpm: Double): Double = beats * 60_000.0 / tempoBpm
