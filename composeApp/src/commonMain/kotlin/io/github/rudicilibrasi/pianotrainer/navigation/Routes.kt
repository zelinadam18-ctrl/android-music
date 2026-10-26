package io.github.rudicilibrasi.pianotrainer.navigation

import kotlinx.serialization.Serializable

// Type-safe navigation routes. Arguments are part of the route, so a screen can be restored
// (after process death, for example) from its route alone.

@Serializable
data object SongsRoute

@Serializable
data class SongDetailRoute(val songId: String)

/** [phraseIndex] is 0-based; show phrase numbers to people as `phraseIndex + 1`. */
@Serializable
data class LessonRoute(val songId: String, val phraseIndex: Int)

@Serializable
data class PhraseResultsRoute(val attemptId: Long)

@Serializable
data class SongCompleteRoute(val songId: String)

@Serializable
data object HistoryRoute

@Serializable
data class HistoryDetailRoute(val attemptId: Long)

@Serializable
data object FreePracticeRoute
