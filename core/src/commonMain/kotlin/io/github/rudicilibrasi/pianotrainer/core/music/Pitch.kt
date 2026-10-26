package io.github.rudicilibrasi.pianotrainer.core.music

import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A piano key, identified by its MIDI note number.
 *
 * Uses scientific pitch notation: middle C is C4 (MIDI 60) and A4 (MIDI 69) is 440 Hz.
 * Only the 88 keys of a standard piano, A0 (21) to C8 (108), are valid.
 */
data class Pitch(val midi: Int) : Comparable<Pitch> {

    init {
        require(midi in LOWEST_MIDI..HIGHEST_MIDI) {
            "MIDI note $midi is outside the piano range $LOWEST_MIDI..$HIGHEST_MIDI (A0 to C8)"
        }
    }

    /** Note name without the octave, using sharps for black keys: "C", "C#", "D", … */
    val name: String get() = NAMES[midi % 12]

    /** Octave number; a new octave starts at each C, so B3 is followed by C4. */
    val octave: Int get() = midi / 12 - 1

    /** Name and octave, e.g. "C4" or "F#3". */
    val label: String get() = "$name$octave"

    /** Equal-temperament frequency: `440 · 2^((midi − 69) / 12)`. */
    val frequencyHz: Double get() = A4_HZ * 2.0.pow((midi - A4_MIDI) / 12.0)

    override fun compareTo(other: Pitch): Int = midi.compareTo(other.midi)

    override fun toString(): String = label

    companion object {
        const val A4_MIDI = 69
        const val A4_HZ = 440.0
        const val LOWEST_MIDI = 21
        const val HIGHEST_MIDI = 108

        private val NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        private val LETTER_SEMITONES = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)
        private val PATTERN = Regex("""([A-G])([#b]?)(-?\d+)""")

        /**
         * Parses a note such as "C4", "F#3" or "Bb3". The letter must be upper case; the
         * accidental is `#` (sharp) or `b` (flat).
         *
         * @throws IllegalArgumentException if the text isn't a note or is outside the piano range.
         */
        fun parse(text: String): Pitch {
            val match = PATTERN.matchEntire(text)
                ?: throw IllegalArgumentException("\"$text\" is not a note; expected something like C4, F#3 or Bb3")
            val (letter, accidental, octaveText) = match.destructured
            val accidentalOffset = when (accidental) {
                "#" -> 1
                "b" -> -1
                else -> 0
            }
            val midi = (octaveText.toInt() + 1) * 12 + LETTER_SEMITONES.getValue(letter[0]) + accidentalOffset
            require(midi in LOWEST_MIDI..HIGHEST_MIDI) {
                "\"$text\" is outside the piano range A0 to C8"
            }
            return Pitch(midi)
        }

        /**
         * Finds the piano key closest to [frequencyHz] and how far the frequency is from it in cents
         * (hundredths of a semitone, between −50 and +50).
         *
         * @return the nearest key, or null if that key would be outside the piano range.
         * @throws IllegalArgumentException if [frequencyHz] is not a positive, finite number.
         */
        fun nearest(frequencyHz: Double): PitchEstimate? {
            require(frequencyHz.isFinite() && frequencyHz > 0.0) {
                "Frequency must be a positive number, but was $frequencyHz"
            }
            val exactMidi = A4_MIDI + 12.0 * log2(frequencyHz / A4_HZ)
            val nearestMidi = exactMidi.roundToInt()
            if (nearestMidi !in LOWEST_MIDI..HIGHEST_MIDI) return null
            return PitchEstimate(Pitch(nearestMidi), cents = (exactMidi - nearestMidi) * 100.0)
        }
    }
}

/** A detected frequency expressed as the nearest piano key plus its deviation in cents. */
data class PitchEstimate(val pitch: Pitch, val cents: Double)
