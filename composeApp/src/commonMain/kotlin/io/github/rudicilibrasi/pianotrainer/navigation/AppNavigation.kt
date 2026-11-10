package io.github.rudicilibrasi.pianotrainer.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.rudicilibrasi.pianotrainer.AppContainer
import io.github.rudicilibrasi.pianotrainer.core.AppInfo
import io.github.rudicilibrasi.pianotrainer.ui.screens.FreePracticeScreen
import io.github.rudicilibrasi.pianotrainer.ui.screens.HistoryDetailScreen
import io.github.rudicilibrasi.pianotrainer.ui.screens.HistoryScreen
import io.github.rudicilibrasi.pianotrainer.ui.screens.LessonScreen
import io.github.rudicilibrasi.pianotrainer.ui.screens.PhraseResultsScreen
import io.github.rudicilibrasi.pianotrainer.ui.screens.SongCompleteScreen
import io.github.rudicilibrasi.pianotrainer.ui.screens.SongDetailScreen
import io.github.rudicilibrasi.pianotrainer.ui.screens.SongsScreen
import kotlin.reflect.KClass

/** The tabs in the bottom navigation bar. */
private enum class TopLevelDestination(val route: Any, val routeClass: KClass<*>, val label: String, val icon: ImageVector) {
    Songs(SongsRoute, SongsRoute::class, "Songs", Icons.AutoMirrored.Filled.List),
    History(HistoryRoute, HistoryRoute::class, "History", Icons.Filled.DateRange),
    Practice(FreePracticeRoute, FreePracticeRoute::class, "Practice", Icons.Filled.PlayArrow),
}

private fun NavDestination?.isIn(routeClass: KClass<*>): Boolean = this?.hierarchy?.any { it.hasRoute(routeClass) } == true

private fun NavDestination?.title(): String = when {
    isIn(SongsRoute::class) -> AppInfo.NAME
    isIn(SongDetailRoute::class) -> "Song"
    isIn(LessonRoute::class) -> "Lesson"
    isIn(PhraseResultsRoute::class) -> "Results"
    isIn(SongCompleteRoute::class) -> "Song complete"
    isIn(HistoryRoute::class) -> "Test history"
    isIn(HistoryDetailRoute::class) -> "Saved attempt"
    isIn(FreePracticeRoute::class) -> "Free practice"
    else -> ""
}

/**
 * The app's screens and how to move between them: a bottom bar for the three top-level tabs, and
 * a top bar with a back button on every other screen (iOS has no system back button).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(
    container: AppContainer,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val currentTab = TopLevelDestination.entries.firstOrNull { destination.isIn(it.routeClass) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(destination.title()) },
                navigationIcon = {
                    if (currentTab == null && navController.previousBackStackEntry != null) {
                        IconButton(onClick = { navController.navigateUp() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (currentTab != null) {
                NavigationBar {
                    TopLevelDestination.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = tab == currentTab,
                            onClick = { navController.navigateToTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(navController, startDestination = SongsRoute, modifier = Modifier.padding(padding)) {
            composable<SongsRoute> {
                SongsScreen(container, onOpenSong = { songId -> navController.navigate(SongDetailRoute(songId)) })
            }
            composable<SongDetailRoute> { entry ->
                val route = entry.toRoute<SongDetailRoute>()
                SongDetailScreen(
                    songId = route.songId,
                    onPractisePhrase = { phraseIndex -> navController.navigate(LessonRoute(route.songId, phraseIndex)) },
                    onSongComplete = { navController.navigate(SongCompleteRoute(route.songId)) },
                    container = container,
                )
            }
            composable<LessonRoute> { entry ->
                val route = entry.toRoute<LessonRoute>()
                LessonScreen(
                    songId = route.songId,
                    phraseIndex = route.phraseIndex,
                    onAttemptAnalyzed = { attemptId -> navController.navigate(PhraseResultsRoute(attemptId)) },
                )
            }
            composable<PhraseResultsRoute> { entry ->
                PhraseResultsScreen(attemptId = entry.toRoute<PhraseResultsRoute>().attemptId)
            }
            composable<SongCompleteRoute> { entry ->
                SongCompleteScreen(songId = entry.toRoute<SongCompleteRoute>().songId)
            }
            composable<HistoryRoute> {
                HistoryScreen(container, onOpenAttempt = { attemptId -> navController.navigate(HistoryDetailRoute(attemptId)) })
            }
            composable<HistoryDetailRoute> { entry ->
                HistoryDetailScreen(attemptId = entry.toRoute<HistoryDetailRoute>().attemptId, container = container)
            }
            composable<FreePracticeRoute> {
                FreePracticeScreen()
            }
        }
    }
}

/** Switches tabs the standard way: one copy of each tab on the stack, keeping each tab's state. */
private fun NavHostController.navigateToTab(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
