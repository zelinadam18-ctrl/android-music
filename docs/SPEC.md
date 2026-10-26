# Project: Beginner Piano Learning App — Phase Two

This is the product specification for a beginner piano trainer for Android and iOS **phones** (not tablets or desktop). The technology choices are recorded in [`decisions/0001-tech-stack.md`](decisions/0001-tech-stack.md), and the work is broken into GitHub issues tracked by the [roadmap issue](https://github.com/rudi-cilibrasi/musicprompt/issues/71).

## Song Sources and Verification

Implement the complete app without requiring me to manually enter note sequences. Find and verify suitable musical sources for each song, and create beginner-friendly arrangements using sources that may legally be used. Do not invent or guess note sequences. Record each song's source (for example, a public-domain score on IMSLP or the Mutopia Project, or "traditional melody") and its license in the song's data. If you cannot reach these sources, transcribe the well-known public-domain melody, mark its source as unverified, and tell me which songs need checking. If you cannot obtain a song's arrangement legally and accurately, tell me which song is blocked and continue building the rest of the app without pretending that the placeholder is the real song. Keep the song catalog structured so I can add or replace arrangements later. After implementation, verify that the generated audio, musical notation, phrases, and scoring all use the same note data.

## Overview

Approach this project as an expert senior mobile engineer and system architect. Build a professional, user-friendly phone application for Android and iOS using Kotlin Multiplatform and Compose Multiplatform. The app targets phones only; tablets and desktop are out of scope.

This project builds on the concept of my previous piano-learning application. However, **this specification will be implemented in a new, empty Kotlin Multiplatform project**. You must implement the application from scratch rather than assuming any existing code, screens, classes, or functionality are present.

The purpose of the application is to teach beginners how to play piano songs through structured, interactive lessons, similar to how Duolingo teaches languages.

The app should retain the original application's core format, professional design, audio analysis, musical notation, graphs, feedback, scoring, and saved-test functionality. The main change is that the application will now teach 6 actual songs, divided into short phrases that follow the melody, with a progression and achievement system.

## 1. Core Requirements and Preservation of Existing Functionality

Preserve and implement all of the following functionality from the original application:

- Randomized note sequences, kept as an optional Free Practice mode (the original random six-note exercise) that does not affect song progress.
- Letter notation and octave notation, such as C4, E4, and G4.
- Accurate standard musical notation on a musical staff.
- Reference audio synthesis and WAV file generation.
- FFT/STFT-based spectrogram analysis.
- Sound amplitude over time.
- Frequency-over-time graphs.
- User-controlled recording duration.
- User audio recording saved as a WAV file.
- Accurate pitch and note detection.
- Comparison of the reference performance with the user's performance.
- Comparison of note accuracy, note order, timing, and tempo.
- Mathematical mastery scoring.
- Personalized feedback based on the actual performance.
- Saved tests and a Test History screen.
- Persistent local storage of results and progress.
- A clean, maintainable architecture that can support future expansion.

Do not remove or simplify any of these features to accommodate the new song system.

The application should maintain the same general lesson and results format as the original application, with the new song-selection menu, progression system, and star achievements added to it.

## 2. Song Selection Menu

Create a professional song-selection screen that displays all 6 beginner songs.

The user must be able to select a song from this menu and view their progress on each song.

The 6 songs are:

1. Twinkle, Twinkle, Little Star
2. Mary Had a Little Lamb
3. Ode to Joy — Ludwig van Beethoven
4. Happy Birthday
5. Jingle Bells
6. Prelude in C Major — J. S. Bach (opening four bars, right hand only)

Use beginner-friendly arrangements of these songs. The arrangements should prioritize simple melodies, manageable rhythms, and appropriate difficulty for someone learning piano for the first time.

Arrange every song in C major so that it uses only white keys, and keep it within a comfortable beginner range of about G3 to G5.

The Prelude in C Major has no melody; it is built from a repeating broken-chord figure. Use only its opening four bars, which form a complete cadence, and teach only the right-hand figure. Teach it without the sustain pedal so that each note can be detected separately.

The application should initially focus on a beginner-friendly right-hand melody. Do not require complicated left-hand accompaniment unless it is explicitly introduced as part of an optional future lesson.

Each song card should display:
- Song title.
- Composer or artist, where applicable.
- Progress through the song.
- Number of phrases completed.
- The student's best overall song score, when available.
- A star achievement indicator when the song has been mastered.
- A clear indication of whether the song is in progress or completed.

The menu should be attractive, easy to navigate, and consistent with the professional design of the original application.

## 3. Accurate Song Arrangements and Note Data

Each song must contain an actual recognizable melody rather than six randomly generated notes.

Implement each song as structured musical data, including:
- Note pitch.
- Octave.
- Note duration.
- Relative timing.
- Rest duration where applicable.
- Phrase boundaries.
- Total number of notes.
- Tempo or reference timing.
- Musical notation information.

Represent notes using a consistent internal data model, such as MIDI note numbers and durations measured in beats or milliseconds. Convert these values into letter notation, octave notation, frequencies, and standard musical notation as needed. Use scientific pitch notation, in which middle C is C4 (MIDI note 60) and A4 is 440 Hz.

The song arrangements must be musically coherent, recognizable, and suitable for beginners.

Use a reliable music-data representation so that the same underlying note sequence drives the displayed sheet music, generated reference audio, timing graphs, note detection comparison, and scoring system.

**Do not generate the reference audio, musical staff, and scoring targets independently.** They must all use the same authoritative song data so that they cannot contradict one another.

All songs in the current catalog are in the public domain. For copyrighted songs added later, use only arrangements and musical material that you are permitted to include. Do not assume that a song is free to reproduce merely because a beginner arrangement is being used. Keep song data modular so that authorized arrangements can be added or replaced later. Use public-domain material where appropriate and provide clearly identified substitutes or placeholders for songs whose complete arrangements cannot legally be included.

## 4. Phrase System

Divide each song into sequential learning phrases that follow the natural phrases of the melody, such as one line of lyrics or one half of a four-bar phrase. Most phrases should contain 6–8 notes. For example, each line of Twinkle, Twinkle, Little Star is a 7-note phrase. If a natural phrase is much longer, split it at a bar line.

Store the phrase boundaries in each song's data rather than computing them from a fixed note count, so that each song is split where its music naturally breaks. Never split a note or musical event across two phrases.

The app should display the current phrase using:
- Letter and octave notation, such as C4, D4, and E4.
- Standard musical notation on a musical staff.
- Reference audio playback.
- The relevant audio-analysis graphs.
- A clear indication of the current phrase number and total phrase count.

For example, the application might display:

Song: Twinkle, Twinkle, Little Star

Phrase 1 of 6

C4 — C4 — G4 — G4 — A4 — A4 — G4

This is only an illustrative example. The actual notes, note durations, and phrase boundaries must be determined by the selected song's musical arrangement.

The application should automatically determine the number of phrases from the phrase boundaries in the song's data.

Phrases may have different lengths, and the interface should clearly show how many notes the current phrase contains. Do not invent extra notes or pad a phrase to reach a particular length.

A phrase's reference recording should include the correct note sequence, durations, rests, and timing for that phrase.

## 5. Phrase Unlocking and Progression Rules

This is one of the most important parts of the application. Implement the following rules precisely.

**A student must earn a score of at least 50/100 on the current phrase to unlock the next phrase.**

For example:

- Score of 0–49: The next phrase remains locked.
- Score of 50–100: The next phrase becomes available.

If the student scores below 50, allow them to retry the same phrase. Provide feedback explaining what needs improvement.

If the student scores 50 or higher, allow them to proceed to the next phrase.

The user must not be able to bypass this requirement by:
- Selecting a later phrase from the interface.
- Restarting the application.
- Closing and reopening the application.
- Navigating backward and then forward.
- Altering the current screen or session state.

Enforce progression rules in the application's underlying progression logic, not just by hiding or disabling buttons.

Persist unlocked phrases locally so that the user can resume their progress after closing the app.

### Retakes and Best Scores

Students should be allowed to replay previously attempted phrases.

Store each attempt separately in the test history.

Maintain the best score for each phrase as well as the most recent score.

Once a phrase has been unlocked, it should remain unlocked. A later lower score must not relock it.

A student should be able to revisit completed phrases for practice without losing progress on the song.

## 6. Song Completion and Star Achievement

The student must complete every phrase of a song before that song can be marked as completed. A phrase counts as completed once its best score is at least 50.

To earn a star, the student must achieve an **overall song score of at least 90/100**.

A phrase score of 90 or higher does not automatically award a song star.

For example:
- Phrase 1: 100
- Phrase 2: 95
- Phrase 3: 80
- Remaining phrases: completed

The student earns a star only if the final overall song score is at least 90/100.

Calculate the overall song score as the average of each phrase's best score, weighted by the number of notes in that phrase:

Overall song score = Σ (best phrase score × notes in phrase) ÷ Σ (notes in phrase)

Weighting by note count keeps longer phrases from counting the same as shorter ones. Because each phrase score already measures note accuracy, note order, timing accuracy, and rhythm and tempo consistency, the overall score accounts for all four across the entire song. Because it uses best phrase scores, it never decreases.

The result is on a 0–100 scale. Round it as described in Section 11.

The scoring system should be transparent and consistent. A student should not receive a star if the calculated overall score is below 90.

The star should be saved permanently once earned. Replaying a song with a lower score must never remove an existing star.

A student who finishes a song with an overall score below 90 should still be able to see their completion and results. They may replay phrases and improve their performance. Recalculate their overall song score whenever a phrase's best score improves, and award the star if they later meet the requirement.

## 7. Song-Level Progress Tracking

For each song, persist:
- Whether the song has been started.
- The current phrase.
- The highest unlocked phrase.
- The number of completed phrases.
- The most recent score for each phrase.
- The best score for each phrase.
- The number of attempts for each phrase and for the song as a whole.
- The overall song score.
- Whether the song has been fully completed.
- Whether the song has earned a star.
- The date the song was started.
- The date of the most recent attempt.
- The date the star was earned, if applicable.

The song-selection screen should reflect this saved information immediately.

For example, a song card could show:

Twinkle, Twinkle, Little Star

Progress: 3 of 6 phrases completed

Best overall score: 87/100

Status: In progress

Star: Not yet earned

These values are illustrative examples, not hardcoded values.

When a student earns a star, display a clear visual achievement indicator beside the song title.

The star should represent mastery of the entire song, not mastery of an individual phrase.

## 8. Reference Audio and Audio Synthesis

Generate the reference audio from the selected song's actual note data.

The app should:
- Synthesize the notes in the correct order.
- Use the specified pitches, octaves, durations, and timing.
- Produce a piano-like sound.
- Save the generated reference recording as a WAV file.
- Allow the student to play and replay the reference.

Generate PCM audio in shared code and play it with each platform's audio API (for example, AudioTrack or MediaPlayer on Android and AVAudioEngine or AVAudioPlayer on iOS), with a suitable sample rate and audio format.

If using synthesized piano tones rather than sampled piano sounds, make them sound as musical and natural as reasonably possible.

The audio playback, note sequence, and displayed musical notation must remain synchronized.

## 9. Audio Analysis and Graphs

Preserve the original application's audio-analysis functionality.

Display the following graphs for the reference recording and the user's recording:

1. **Spectrogram:** Use FFT/STFT analysis to show frequency intensity over time, with appropriate frequency and time axes.
2. **Amplitude over time:** Display how the audio signal's amplitude changes throughout the recording.
3. **Frequency over time:** Provide a beginner-friendly visualization of detected pitch changes.
4. **Reference versus user comparison:** Display the expected and detected notes against their respective timestamps so that the student can identify timing and pitch differences.

Use a suitable windowing function and overlapping STFT frames for the spectrogram.

Use appropriate fundamental-frequency detection techniques for identifying piano notes. Do not assume that the strongest spectral peak is always the fundamental frequency, because piano notes contain harmonics.

The graphs should remain readable on phone screens and should support the current phrase without becoming cluttered.

## 10. User Recording and Note Detection

After reviewing the reference phrase, the student should be able to record themselves playing it.

The recording duration should be controlled by the user rather than forced to match the reference duration.

The app should:
- Request microphone permission when needed.
- Clearly indicate when recording begins and ends.
- Save the recording as a WAV file.
- Analyze the recording.
- Identify the notes played, including their octaves.
- Display the detected notes using letter and octave notation.
- Display the detected notes on a standard musical staff.
- Display the same categories of audio graphs as the reference.
- Compare the detected performance against the expected phrase.

The pitch-detection system should account for harmonics, background noise, silence, and imperfect recordings.

Detect where each note begins (for example, from sudden rises in energy or spectral flux), not only where the pitch changes, so that repeated notes such as C4 — C4 at the start of Twinkle, Twinkle, Little Star are counted as two notes.

When confidence in a detected note is too low, indicate that the note could not be identified reliably instead of pretending the result is certain.

The user must be able to review their performance before deciding to retry or continue.

## 11. Mathematical Mastery Score

Continue using a mathematical scoring system rather than assigning scores arbitrarily.

Calculate each phrase score from measurable performance characteristics, such as:
- Correct notes.
- Incorrect or missed notes.
- Extra notes.
- Note order.
- Timing deviations.
- Rhythm and tempo consistency.

Use a consistent matching algorithm to align detected notes with the expected sequence, including when the user plays more slowly or quickly than the reference.

For example, a phrase should not receive a perfect score simply because all of its expected notes appear somewhere in the recording if they were played in the wrong order.

Define the scoring formula explicitly in the code and document how each component contributes to the final score.

Use sensible tolerances for timing and pitch detection. Do not penalize a user excessively for tiny timing differences that are normal for a beginner.

Make sure the score is deterministic: the same expected notes and detected performance should produce the same score.

Calculate the score as a decimal, then round it once to a whole number (round half up). Use that whole number everywhere: on screen, in storage, and for the 50-point unlock and 90-point star thresholds. For example, 49.5 becomes 50 and unlocks the next phrase; 49.4 becomes 49 and does not.

The phrase unlocking and song-star requirements must use the actual calculated scores.

## 12. Personalized Feedback

After every phrase attempt, provide useful feedback based on the detected performance.

Examples include:
- "You played 6 out of 7 notes correctly."
- "Your timing was slower than the reference."
- "You played C4 correctly but missed E4."
- "Your note order was correct."
- "Try keeping a more consistent rhythm."
- "You scored 48/100. You need at least 50 to unlock the next phrase."
- "You scored 76/100. The next phrase is now unlocked."

The feedback should reflect the actual analysis results.

After a phrase attempt, clearly show:
- Phrase score.
- Note accuracy.
- Timing accuracy.
- Note-order accuracy.
- What the student did well.
- What the student should improve.
- Whether the next phrase is unlocked.

When the student completes the entire song, show:
- Overall song score.
- Total phrases completed.
- Performance summary.
- Personalized recommendations.
- Whether the student earned a star.

If the student scores below 90 on the entire song, explain that the song is complete but the mastery star has not yet been earned.

Do not award a star based solely on a high individual phrase score.

## 13. Test History and Saved Recordings

Preserve the original saved-test functionality and expand it to support multiple songs and phrases.

Create a Test History section where the student can review previous attempts.

Each saved attempt should include, where applicable:
- Song title.
- Phrase number.
- Date and time.
- Expected notes.
- Detected notes.
- Reference recording.
- User recording.
- Phrase score.
- Note accuracy.
- Timing accuracy.
- Note-order accuracy.
- Feedback.
- Comparison and graph data, or enough persisted information to reproduce the results.

The student should be able to open an old test and view its results without changing current progression or accidentally recording a new attempt.

Store structured data in a local Room database (Room for Kotlin Multiplatform, shared by Android and iOS) and store WAV recordings as files, saving their paths or URIs in the database.

Ensure that saved recordings remain accessible after the application is closed and reopened.

Implement appropriate handling for missing or inaccessible recording files.

Keep historical attempts separate from current progress. Reviewing an old result must never unlock a phrase or overwrite a newer attempt.

## 14. Architecture and Local Persistence

Because this is the foundation of a larger learning application, implement a clean and maintainable architecture.

Use Kotlin Multiplatform with an MVVM architecture. Share as much code as practical between Android and iOS, and keep platform-specific code (audio recording and playback, audio session, file locations, permissions) behind small interfaces.

Separate responsibilities into appropriate components, such as:
- User interface.
- Song catalog.
- Song and phrase data models.
- Musical notation rendering.
- Audio synthesis.
- Audio recording.
- Audio playback.
- Audio analysis.
- Pitch and note detection.
- Performance comparison.
- Phrase scoring.
- Song-level scoring.
- Progression and unlocking.
- Achievement and star management.
- Test history.
- Local persistence.
- Platform-specific audio, file, and permission code for Android and iOS.

Use Room for structured persistent data and a suitable file-management approach for WAV recordings.

Use a repository layer where appropriate to separate the UI from database and file operations.

The app must restore the student's progress after restarting. Do not rely exclusively on in-memory variables.

The progression rules must be enforced consistently throughout the application.

Avoid placing the entire application in a single screen or a single large class.

Use reusable components and keep the song catalog data-driven so that additional songs, lessons, instruments, and difficulty levels can be introduced later.

## 15. Professional User Interface

Maintain a professional, polished, beginner-friendly design.

The primary navigation should make it easy to access:
- Song selection.
- The current lesson.
- Phrase results.
- Test History.
- Song progress and achievements.

The song-selection screen should clearly communicate which songs are available, which are in progress, and which have earned stars.

The lesson screen should clearly communicate:
1. Which song the student selected.
2. Which phrase they are practicing.
3. Which notes they need to play.
4. What the notes look like on a musical staff.
5. How to listen to the reference.
6. How to record their performance.
7. How their performance compares with the reference.
8. Whether they can proceed to the next phrase.

Do not allow locked phrases to appear playable.

You may display locked phrases to show the student what is coming next, but make it clear that they are locked until the required score is achieved.

Provide clear retry and continue actions. The Continue action must only be available when the student has met the minimum score requirement for that phrase.

Use accessible text, readable graphs, appropriate loading states, and clear error messages.

## 16. Implementation Requirements

Since the project starts empty:

- Inspect the existing project structure before implementation.
- Set up the required Kotlin files, dependencies, resources, navigation, database, and architecture.
- Implement the complete application rather than only creating UI mockups.
- Ensure that buttons and navigation actually work.
- Ensure that song data, generated audio, musical notation, note detection, and scoring use consistent information.
- Implement persistent progression and achievement rules.
- Test important edge cases, including restarting the app, retaking a phrase, scoring below 50, earning exactly 50, scoring below 90 overall, and earning exactly 90 overall.
- Test the progression and star rules with unit tests that supply scores directly, such as 49, 50, 89, and 90. Supplying scores in tests is expected; the ban on hardcoded scores applies to the app itself.
- Ensure that the app builds for both Android and iOS, and fix build errors introduced by the implementation.
- Avoid replacing real functionality with hardcoded demo scores, fake note detection, or placeholder buttons.
- Do not claim that a feature is implemented if it is only a visual placeholder.

Prioritize correctness and reliability over unnecessary complexity.

If a feature requires a technical trade-off, choose the most reliable practical approach, explain the trade-off briefly, and preserve the core requirements.

## 17. Final Acceptance Criteria

The implementation is not complete until the following conditions are met on both an Android phone and an iPhone:

1. The app launches and displays all 6 songs.
2. A student can select a song and practice its first phrase.
3. The app displays the correct notes in letter, octave, and musical-staff notation.
4. The reference audio matches the selected song and phrase.
5. The app records and analyzes the student's performance.
6. The required graphs and comparison are displayed.
7. Each attempt produces a calculated score and performance-specific feedback.
8. A score below 50 does not unlock the next phrase.
9. A score of exactly 50 does unlock the next phrase.
10. The student cannot bypass locked phrases through navigation or an application restart.
11. Completing every phrase marks the song as completed.
12. A song star is awarded only when the overall song score is at least 90.
13. A score below 90 does not earn a star.
14. An earned star remains saved after the app restarts.
15. Progress and phrase unlocks persist after the app restarts.
16. Previous attempts and WAV recordings remain accessible through Test History.
17. Reviewing old tests does not alter current progress.
18. All 6 songs use coherent, beginner-appropriate arrangements and consistent musical data.
19. The project builds successfully for Android and iOS, and the implemented screens and controls perform their intended actions.

**Final priority:** Preserve the original app's complete learning, audio-analysis, graphing, feedback, scoring, and saved-test experience while adding the 6-song catalog, phrase-by-phrase progression, persistent progress tracking, and song-level star achievements. The application must be fully functional and ready for further development, not merely a visual prototype.

# Appendix: Original Phase One Specification

The following is the specification of the original application. Use it as the reference for the features, format, and behavior that Phase Two must preserve. In Phase Two, song-based lessons replace the random six-note sequence as the main lesson flow; the random exercise remains only as the optional Free Practice mode. The original application was Android-only, so its platform-specific wording (Android Studio, AudioTrack, Activity) describes that app; where it conflicts with the cross-platform choices above, the main specification takes precedence.

Approach this app as an **expert senior Android Studio engineer and system architect**. Make this app user-friendly, with a professional look and feel. This app will be written in **Android Studio using Kotlin**.

The purpose of this app is to teach users how to play basic notes on a piano. This is **Phase One** of a much larger project that will eventually teach users how to master an instrument through structured lessons, similar to how Duolingo teaches languages.

For now, this app should focus on teaching the user to recognize and play **6 basic piano notes**.

### 1. Random Note Generation

The app should randomly generate **6 basic piano notes**.

The generated notes should be displayed to the user using:
- **Letter notation** (for example, A, B, C)
- **Octave notation** (for example, A4, C4, E4)
- **Standard musical notation on a musical staff**, using actual music-note symbols.

The musical staff should accurately represent the pitch and octave of each generated note. For example, if the app generates C4, the user should be able to see C4 represented correctly on the staff as well as seeing "C4" written alongside or underneath it.

The notes should be generated randomly while making sure they are valid piano notes.

### 2. Reference Audio

After generating the 6 notes, the app should synthesize an audio recording of those notes in the correct order.

The app should:
- Generate the audio programmatically.
- Convert the generated notes into a realistic piano-like sound as accurately as reasonably possible.
- Save the generated recording as a **WAV file**.
- Play the recording for the user.

For audio synthesis, use an appropriate Android audio approach such as **PCM audio generation with AudioTrack**, rather than simply playing pre-recorded audio if possible.

### 3. Reference Audio Analysis

The app should analyze the generated reference recording.

It should display an accurate **spectrogram using an FFT/STFT-based approach**.

The spectrogram should show:
- Frequency (Hz)
- Time (seconds)
- Frequency intensity/magnitude

Use an appropriate **windowing function and overlapping STFT windows** so that the spectrogram accurately represents how the frequencies change over time.

The app should also display **sound/amplitude over time**.

In addition, create a more user-friendly frequency-over-time visualization that makes it easier for a beginner to understand which notes are being played and when.

### 4. User Recording

Next, it should be the user's turn.

The app should ask the user to record themselves playing the generated notes.

The recording length should be **the user's choice** rather than forcing the recording to have the same duration as the reference.

Once the app receives the recording, it should:

- Save the user's recording as a **WAV file**.
- Analyze the recording.
- Detect the notes the user played.
- Display the detected notes on the screen.
- Display the detected notes using **letter + octave notation**.
- Display the detected notes on a **musical staff using standard musical notation**.
- Display the same types of graphs used for the reference recording.

It is extremely important that the detected notes are as **accurate as possible**. The app should use proper pitch-detection/frequency-analysis techniques rather than simply guessing based on amplitude.

For example, the app could use **FFT/STFT analysis combined with fundamental-frequency detection** to determine the notes being played. It should account for the fact that a piano note contains multiple harmonics, so the strongest frequency is not always the fundamental frequency.

### 5. Reference vs. User Comparison

The app should include another graph comparing the reference recording with the user's recording.

The comparison should show **which notes were played and when they were played**.

For example, if the reference expects:

`C4 → E4 → G4`

the app should be able to compare the timing of the user's detected notes against those expected notes.

This should allow the user to see whether they:
- Played the correct notes.
- Played notes too early or too late.
- Played the notes in the correct order.
- Played at approximately the correct speed.

The comparison should be based on the **actual timing of the detected notes**, not just whether the correct notes appeared somewhere in the recording.

### 6. Mastery Score

Finally, the app should calculate a **Mastery Score**.

The score should be calculated mathematically rather than being randomly assigned.

The score should consider multiple factors, such as:
- **Note accuracy**
- **Note order**
- **Timing accuracy**
- **Speed/tempo**
- Potentially other measurable performance factors that would be useful for determining mastery.

Use a weighted scoring system so that the different categories contribute appropriately to the final score.

For example, the architecture could allow something similar to:

`Mastery Score = Note Accuracy × Weight + Timing Accuracy × Weight + Order Accuracy × Weight + Speed Accuracy × Weight`

The exact weights can be chosen based on what makes the most sense for a beginner piano-learning application.

### 7. Feedback

After calculating the score, the app should provide the user with useful feedback about what they should improve.

For example:

- "You played 5 out of 6 notes correctly."
- "Your timing was slightly slower than the reference."
- "You played C4 correctly but missed E4."
- "Your note order was correct."
- "Work on playing the sequence more consistently."

The feedback should be based on the actual analysis results and should **not give generic feedback that is unrelated to the user's performance**.

### 8. Saved Tests / Test History

The app should save each completed test so the user can view their previous performances.

Create a **Test History** or **Past Tests** section in the app.

Each saved test should contain information such as:
- Date and time of the test.
- The 6 notes that were generated.
- The generated reference recording.
- The user's recording.
- The user's detected notes.
- The Mastery Score.
- Note accuracy.
- Timing accuracy.
- Speed/tempo accuracy.
- Note-order accuracy.
- The feedback provided after the test.
- The visual comparison/analysis data when practical.

The user should be able to select a previous test from the Test History screen and open a **Test Results** page showing the results from that specific attempt.

The history should be stored locally on the device so that the tests remain available after closing and reopening the application.

Use an appropriate local persistence solution such as **Room Database** for structured test data. Store audio recordings as files and save their file paths/URIs in the database rather than storing large WAV files directly inside the database.

The architecture should be designed so that this history system can later be expanded into a larger **progress-tracking system**, allowing future versions of the app to show improvement over time.

### 9. Architecture

Because this is Phase One of a much larger application, structure the project so it can be expanded later.

Use a clean and maintainable Android architecture, such as **MVVM**, with clear separation between:
- UI
- Audio recording
- Audio playback
- Audio synthesis
- Audio analysis
- Pitch/note detection
- Musical notation rendering
- Performance comparison
- Scoring
- Feedback generation
- Test history/storage

Avoid putting the entire application into one Activity or one large class.

Use reusable components/classes where appropriate so that additional instruments, lessons, songs, difficulty levels, and user progress can be added later.

The UI should be professional, simple, and beginner-friendly. The user should always understand:
1. What note sequence they need to play.
2. What they are currently doing.
3. How their recording compares to the reference.
4. What they need to improve.
5. How their current performance compares with previous tests.

For Phase One, focus on making the **core note-generation, musical-notation, audio-analysis, note-detection, comparison, scoring, and test-history systems accurate and reliable** rather than adding unnecessary features.
