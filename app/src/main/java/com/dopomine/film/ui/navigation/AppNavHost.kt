package com.dopomine.film.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.dopomine.film.ui.components.LocalAnimations
import com.dopomine.film.ui.details.DetailsScreen
import com.dopomine.film.ui.home.HomeScreen
import com.dopomine.film.ui.library.LibraryScreen
import com.dopomine.film.ui.search.SearchScreen
import com.dopomine.film.ui.settings.SettingsScreen
import com.dopomine.film.ui.theme.Radius
import com.dopomine.film.ui.theme.T
import com.dopomine.film.ui.theme.Type
import kotlinx.coroutines.flow.Flow

object Routes {
    const val HOME = "home"
    const val CATALOG = "catalog"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val DETAILS = "details/{id}"
    const val COLLECTION = "collection/{key}"

    fun details(id: Int) = "details/$id"
    fun collection(key: String) = "collection/$key"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Главная", Icons.Outlined.Home),
    Tab(Routes.CATALOG, "Каталог", Icons.Outlined.GridView),
    Tab(Routes.SEARCH, "Поиск", Icons.Outlined.Search),
    Tab(Routes.LIBRARY, "Моё", Icons.Outlined.Bookmarks),
)

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
}

/** Height reserved at the bottom of tab screens so content can scroll above the floating bar. */
val BottomBarSpace = 92.dp

@Composable
fun AppNavHost(
    deepLinks: Flow<Int>,
    animations: Boolean = true,
    controller: NavHostController = rememberNavController(),
) {
    val nav = remember(controller) { Nav(controller) }
    val backStack by controller.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = tabs.any { it.route == route }

    LaunchedEffect(deepLinks) { deepLinks.collect { id -> nav.details(id) } }

    CompositionLocalProvider(LocalAnimations provides animations) {
        Box(Modifier.fillMaxSize().background(T.bg)) {
            NavHost(
                navController = controller,
                startDestination = Routes.HOME,
                modifier = Modifier.fillMaxSize(),
                enterTransition = { fadeIn() },
                exitTransition = { fadeOut() },
            ) {
                composable(Routes.HOME) { HomeScreen(nav) }
                composable(Routes.CATALOG) { CatalogScreen(nav) }
                composable(Routes.SEARCH) { SearchScreen(nav) }
                composable(Routes.LIBRARY) { LibraryScreen(nav) }
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
}

/** Floating white card at the bottom, like splicer's player bar; the active item is a filled pill. */
@Composable
private fun BottomBar(current: String?, onSelect: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(T.surface)
                .border(1.dp, T.border, RoundedCornerShape(Radius.card)).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tabs.forEach { tab ->
                val selected = tab.route == current
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(Radius.row))
                        .background(if (selected) T.surface2 else T.surface)
                        .then(if (selected) Modifier.border(1.dp, T.border, RoundedCornerShape(Radius.row)) else Modifier)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(tab.route) }
                        .padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(tab.icon, tab.label, tint = if (selected) T.text else T.text3, modifier = Modifier.size(21.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(tab.label, style = Type.monoSmall, color = if (selected) T.text else T.text3)
                }
            }
        }
    }
}
