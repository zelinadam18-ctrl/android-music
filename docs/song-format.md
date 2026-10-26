# Song file format

Songs are data files, so they can be added or corrected without changing code. They live in
`composeApp/src/commonMain/composeResources/files/songs/`:

- `index.json` lists the song ids in the order they appear in the song menu.
- `<id>.json` holds one song. The file name must match the song's `id`.

The parser is `SongFormat` in `core`; the app loads the files with `SongCatalog` in `composeApp`.

## index.json

```json
{
  "songs": ["twinkle", "mary-had-a-little-lamb", "ode-to-joy"]
}
```

## A song file

```json
{
  "id": "twinkle",
  "title": "Twinkle, Twinkle, Little Star",
  "composer": null,
  "tempoBpm": 90,
  "timeSignature": "4/4",
  "key": "C major",
  "pickupBeats": 0,
  "source": "https://example.org/public-domain-score",
  "license": "Public domain",
  "sourceVerified": false,
  "arrangerNotes": "Right-hand melody only.",
  "phrases": [
    "C4/1 C4/1 G4/1 G4/1 A4/1 A4/1 G4/2",
    "F4/1 F4/1 E4/1 E4/1 D4/1 D4/1 C4/2"
  ]
}
```

| Field | Required | Meaning |
| --- | --- | --- |
| `id` | yes | Short, unique, lower-case id; must match the file name. |
| `title` | yes | Shown in the song menu. |
| `composer` | no | Composer or artist; leave out or `null` for traditional songs. |
| `tempoBpm` | yes | Quarter-note beats per minute for the reference audio. |
| `timeSignature` | yes | `"4/4"`, `"3/4"`, … (used for bar lines). |
| `key` | yes | E.g. `"C major"`. |
| `pickupBeats` | no | Length of a pickup (notes before the first full bar), in beats. Default 0. |
| `source` | yes | Where the notes came from: a URL to a public-domain score, or `"traditional"`. |
| `license` | yes | E.g. `"Public domain"`. |
| `sourceVerified` | yes | Set to `true` only after a person has compared every note with the source. |
| `arrangerNotes` | no | Choices made while arranging, such as a simplified rhythm. |
| `phrases` | yes | The learning phrases, in order (see below). |

Unknown fields are rejected, so a misspelled field name is caught rather than silently ignored.

## Phrases and notes

Each string in `phrases` is one learning phrase. Phrase boundaries come from this list, never from
a fixed note count, so put each break where the music naturally breaks (usually 6–8 notes; see the
spec, section 4).

A phrase is a space-separated list of events. Each event is a pitch, a slash, and a duration:

- **Pitch**: an upper-case letter `A`–`G`, an optional `#` (sharp) or `b` (flat), and an octave
  number. Middle C is `C4`; `A4` is 440 Hz. Use `R` for a rest.
- **Duration**: in quarter-note beats.

| Duration | Value |
| --- | --- |
| Whole note | `4` |
| Dotted half | `3` |
| Half note | `2` |
| Dotted quarter | `1.5` |
| Quarter note | `1` |
| Dotted eighth | `0.75` |
| Eighth note | `0.5` |
| Sixteenth note | `0.25` |

Examples: `C4/1` is a quarter-note middle C, `F#4/0.5` an eighth-note F sharp, `E4/1.5` a dotted
quarter E, and `R/1` a quarter rest.

## Errors

If a file can't be read, the error names the file and, where possible, the phrase and event, for
example:

```
twinkle.json: phrase 2: event 2 "X4/1": "X4" is not a note; expected something like C4, F#3 or Bb3
```
