package com.dopomine.film.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dopomine.film.ui.catalog.CatalogScreen
import com.dopomine.film.ui.collection.CollectionScreen
import com.dopomine.film.ui.details.DetailsScreen
import com.dopomine.film.ui.home.HomeScreen
import com.dopomine.film.ui.library.LibraryScreen
import com.dopomine.film.ui.onboarding.OnboardingScreen
import com.dopomine.film.ui.search.SearchScreen
import com.dopomine.film.ui.settings.SettingsScreen
import com.dopomine.film.ui.theme.Palette
import kotlinx.coroutines.flow.Flow

object Routes {
    const val HOME = "home"
    const val CATALOG = "catalog"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val ONBOARDING = "onboarding"
    const val DETAILS = "details/{id}"
    const val COLLECTION = "collection/{key}"

    fun details(id: Int) = "details/$id"
    fun collection(key: String) = "collection/$key"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Главная", Icons.Rounded.Home),
    Tab(Routes.CATALOG, "Каталог", Icons.Rounded.GridView),
    Tab(Routes.SEARCH, "Поиск", Icons.Rounded.Search),
    Tab(Routes.LIBRARY, "Моё", Icons.Rounded.Bookmarks),
)

/** Navigation actions shared by all screens. */
class Nav(private val controller: NavHostController) {
    fun details(id: Int) = controller.navigate(Routes.details(id)) { launchSingleTop = true }
    fun collection(key: String) = controller.navigate(Routes.collection(key))
    fun settings() = controller.navigate(Routes.SETTINGS) { launchSingleTop = true }
    fun search() = tab(Routes.SEARCH)
    fun back() = controller.popBackStack()

    fun tab(route: String) = controller.navigate(route) {
        popUpTo(controller.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    fun finishOnboarding() = controller.navigate(Routes.HOME) {
        popUpTo(Routes.ONBOARDING) { inclusive = true }
    }
}

@Composable
fun AppNavHost(
    showOnboarding: Boolean,
    deepLinks: Flow<Int>,
    controller: NavHostController = rememberNavController(),
) {
    val nav = remember(controller) { Nav(controller) }
    val backStack by controller.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = tabs.any { it.route == route }

    LaunchedEffect(deepLinks) {
        deepLinks.collect { id -> nav.details(id) }
    }

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        NavHost(
            navController = controller,
            startDestination = if (showOnboarding) Routes.ONBOARDING else Routes.HOME,
            modifier = Modifier.fillMaxSize(),
            enterTransition = { fadeIn() + slideInHorizontally { it / 12 } },
            exitTransition = { fadeOut() },
            popEnterTransition = { fadeIn() },
            popExitTransition = { fadeOut() + slideOutHorizontally { it / 12 } },
        ) {
            composable(Routes.ONBOARDING) { OnboardingScreen(onDone = nav::finishOnboarding) }
            composable(Routes.HOME, enterTransition = { fadeIn() }, exitTransition = { fadeOut() }) { HomeScreen(nav) }
            composable(Routes.CATALOG, enterTransition = { fadeIn() }, exitTransition = { fadeOut() }) { CatalogScreen(nav) }
            composable(Routes.SEARCH, enterTransition = { fadeIn() }, exitTransition = { fadeOut() }) { SearchScreen(nav) }
            composable(Routes.LIBRARY, enterTransition = { fadeIn() }, exitTransition = { fadeOut() }) { LibraryScreen(nav) }
            composable(Routes.SETTINGS) { SettingsScreen(nav) }
            composable(Routes.DETAILS, arguments = listOf(navArgument("id") { type = NavType.IntType })) { DetailsScreen(nav) }
            composable(Routes.COLLECTION, arguments = listOf(navArgument("key") { type = NavType.StringType })) { CollectionScreen(nav) }
        }

        AnimatedVisibility(
            visible = showBar,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            BottomBar(current = route, onSelect = nav::tab)
        }
    }
}

/** Height reserved at the bottom of tab screens so content can scroll above the floating bar. */
val BottomBarSpace = 96.dp

@Composable
private fun BottomBar(current: String?, onSelect: (String) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .shadow(24.dp, RoundedCornerShape(24.dp), ambientColor = Color.Black, spotColor = Color.Black)
                .clip(RoundedCornerShape(24.dp))
                .background(Palette.SurfaceHigh)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            tabs.forEach { tab ->
                val selected = tab.route == current
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(tab.route) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .then(if (selected) Modifier.background(Palette.AccentGradient) else Modifier)
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Icon(
                            tab.icon,
                            contentDescription = tab.label,
                            tint = if (selected) Color.White else Palette.TextSecondary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        tab.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) Palette.TextPrimary else Palette.TextMuted,
                    )
                }
            }
        }
    }
}

