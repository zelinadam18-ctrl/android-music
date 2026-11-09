# Phrase scoring

How one attempt at a phrase becomes a whole-number score from 0 to 100 (spec §11). The code is in `core/src/commonMain/kotlin/.../core/scoring/`: `Aligner` (alignment), `Timing` (timing and tempo) and `PhraseScorer` (the formula). The weights are a starting point, to be tuned against real piano recordings (#40).

## The formula

```
raw   = 45 × note accuracy
      + 20 × order accuracy
      + 20 × timing accuracy
      + 15 × tempo accuracy

score = raw rounded once, half up, to a whole number
```

Each accuracy is a number from 0 to 1, so `raw` runs from 0 to 100.

| Component | Weight | What it measures |
|---|---|---|
| Note accuracy | 45 | Did the student play the right notes? |
| Order accuracy | 20 | Did they play them in the right order? |
| Timing accuracy | 20 | Were the notes in rhythm? |
| Tempo accuracy | 15 | Was the overall speed close to the reference? |

## Step 1: alignment

The detected notes are aligned to the phrase's notes with edit-distance dynamic programming (Needleman–Wunsch style). Each step is one of:

- **correct**: the expected note was played;
- **wrong**: a different note, or one that couldn't be identified reliably, was played in its place;
- **missed**: the expected note wasn't played;
- **extra**: a note was played that isn't in the phrase.

Wrong, missed and extra notes cost 1 each; the alignment with the lowest total cost wins. Among equally cheap alignments, the one with the most correct notes wins, and any remaining tie is broken the same way every time.

Alignment uses the **order** of the notes, not their absolute times, so a student who plays slower or faster still lines up note for note. Notes the detector marked **uncertain** can be aligned but never count as correct.

## Step 2: the components

**Note accuracy** = (correct − 0.5 × extra) ÷ expected notes, clamped to 0–1.
Wrong and missed notes aren't correct, so they lower it directly; each extra note costs half a note.

**Order accuracy** = correct ÷ the notes that would be correct if order didn't matter (0 if none of the expected pitches were played).
The second number counts each reliably detected note whose pitch is still unused among the expected notes. Playing all the right notes in the wrong order keeps it high while the order-aware correct count drops, so **wrong order can never score 100**. Reversing a five-note phrase gives an order accuracy of 1/5.

**Timing accuracy.** Over the correct notes, fit `userTime = a × expectedTime + b` by least squares. `a` is the tempo ratio (above 1 is slower) and `b` is when the student started. Each correct note's distance from that line, in beats at the student's own tempo, is its timing deviation. Removing the fit first means playing evenly slower, or starting a few seconds into the recording, costs nothing here.

| Deviation | Penalty |
|---|---|
| up to ±⅛ beat | none |
| between ⅛ and ½ beat | rises linearly |
| ±½ beat or more | full |

Timing accuracy = 1 − the average penalty. The spread (standard deviation) of the deviations is kept as a measure of rhythm consistency, for feedback.

**Tempo accuracy** compares `a` with 1:

| Tempo | Penalty |
|---|---|
| within ±15% of the reference | none |
| between 15% and 50% off | rises linearly |
| 50% or more off | full |

With fewer than two correct notes there's nothing to fit, so timing and tempo accuracy are both 0.

## Step 3: rounding once

`raw` is kept as a decimal and rounded **once**, half up, to a whole number. That whole number is what the student sees, what is stored, and what is compared with the thresholds: **50** unlocks the next phrase and **90** counts toward the song's star.

| raw | score | unlocks? |
|---|---|---|
| 49.4 | 49 | no |
| 49.5 | 50 | yes |
| 89.4 | 89 | (below 90) |
| 89.5 | 90 | (90 or more) |

## Examples

For a five-note phrase played at the reference tempo:

| Performance | Note | Order | Timing | Tempo | Score |
|---|---|---|---|---|---|
| Perfect | 1.0 | 1.0 | 1.0 | 1.0 | 100 |
| One wrong note | 0.8 | 1.0 | 1.0 | 1.0 | 91 |
| Perfect, but at half speed | 1.0 | 1.0 | 1.0 | 0.0 | 85 |
| All notes, reversed | 0.2 | 0.2 | 0.0 | 0.0 | 13 |
| Nothing played | 0.0 | 0.0 | 0.0 | 0.0 | 0 |

The score is deterministic: the same expected notes and detected notes always give the same score.
