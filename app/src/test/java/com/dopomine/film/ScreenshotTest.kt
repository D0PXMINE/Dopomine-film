package com.dopomine.film

import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.asImage
import coil3.test.FakeImageLoaderEngine
import com.dopomine.film.data.model.ContentType
import com.dopomine.film.data.model.Movie
import com.dopomine.film.player.PlayerActions
import com.dopomine.film.player.PlayerOverlay
import com.dopomine.film.player.PlayerUiState
import com.dopomine.film.ui.navigation.AppNavHost
import com.dopomine.film.ui.onboarding.OnboardingScreen
import com.dopomine.film.ui.theme.DopomineTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the main screens with a fake API so the design can be reviewed without a device.
 * Run: ./gradlew :app:recordRoborazziDebug (images land in app/build/outputs/roborazzi).
 * Real posters are used when -Ddpm.posters=<dir with ID.jpg files> is passed, colored tiles otherwise.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h880dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var container: AppContainer
    private val out = "build/outputs/roborazzi"

    @OptIn(DelicateCoilApi::class)
    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication() as DopomineApp
        container = AppContainer(app, FakeApi())
        app.container = container
        runBlocking {
            container.settings.setApiKey("test-key")
            container.settings.setOnboardingDone()
        }

        val engine = FakeImageLoaderEngine.Builder().apply {
            val dir = System.getProperty("dpm.posters")?.let(::File)
            dir?.listFiles { f -> f.extension == "jpg" }?.forEach { file ->
                val bitmap = BitmapFactory.decodeFile(file.path) ?: return@forEach
                intercept({ it is String && it.endsWith("/${file.name}") }, bitmap.asImage())
            }
            default(android.graphics.drawable.ColorDrawable(0xFF3A2D5C.toInt()).asImage())
        }.build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(app).components { add(engine) }.build())
    }

    private fun app(block: (NavHostController) -> Unit = {}) {
        lateinit var controller: NavHostController
        compose.setContent {
            DopomineTheme {
                controller = rememberNavController()
                AppNavHost(showOnboarding = false, deepLinks = emptyFlow(), controller = controller)
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread { block(controller) }
        settle()
    }

    private fun settle() {
        repeat(4) {
            Thread.sleep(250)
            compose.mainClock.advanceTimeBy(400)
            compose.waitForIdle()
        }
    }

    @Test
    fun home() {
        runBlocking {
            container.library.markOpened(Movie(258687, "Интерстеллар", year = 2014, posterUrl = "https://posters.test/258687.jpg", type = ContentType.FILM, genres = listOf("фантастика")))
            container.library.saveProgress(258687, 3900.0, 10140.0)
            container.library.markOpened(Movie(404900, "Во все тяжкие", year = 2008, posterUrl = "https://posters.test/404900.jpg", type = ContentType.SERIES, genres = listOf("драма")))
            container.library.saveProgress(404900, 1200.0, 2820.0)
        }
        app()
        compose.onRoot().captureRoboImage("$out/01_home.png")
    }

    @Test
    fun homeSeries() {
        app()
        compose.onNodeWithText("Сериалы").performClick()
        settle()
        compose.onRoot().captureRoboImage("$out/02_home_series.png")
    }

    @Test
    fun details() {
        app { it.navigate("details/301") }
        compose.onRoot().captureRoboImage("$out/03_details.png")
    }

    @Test
    fun detailsSeries() {
        app { it.navigate("details/464963") }
        compose.onRoot().captureRoboImage("$out/04_details_series.png")
    }

    @Test
    fun catalog() {
        app { it.navigate("catalog") }
        compose.onRoot().captureRoboImage("$out/05_catalog.png")
    }

    @Test
    fun library() {
        runBlocking {
            listOf(301, 326, 464963, 535341, 178707).forEach { id ->
                container.library.toggleFavorite(container.movies.details(id).movie)
            }
        }
        app { it.navigate("library") }
        Thread.sleep(500)
        settle()
        compose.onRoot().captureRoboImage("$out/06_library.png")
    }

    @Test
    fun settings() {
        app { it.navigate("settings") }
        compose.onRoot().captureRoboImage("$out/07_settings.png")
    }

    @Test
    fun onboarding() {
        compose.setContent { DopomineTheme { OnboardingScreen(onDone = {}) } }
        settle()
        compose.onRoot().captureRoboImage("$out/08_onboarding.png")
    }

    @Test
    @Config(qualifiers = "w880dp-h400dp-xxhdpi")
    fun playerControls() {
        val ui = playerUi()
        compose.setContent { DopomineTheme { PlayerOverlay(ui, NoActions) } }
        settle()
        compose.onRoot().captureRoboImage("$out/09_player_controls.png")
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    @Config(qualifiers = "w880dp-h400dp-xxhdpi")
    fun playerSources() {
        val ui = playerUi().apply { sourcesSheet = true }
        compose.setContent { DopomineTheme { PlayerOverlay(ui, NoActions) } }
        settle()
        captureScreenRoboImage("$out/10_player_sources.png")
    }

    @Test
    @Config(qualifiers = "w880dp-h400dp-xxhdpi")
    fun playerLoading() {
        val ui = PlayerUiState().apply { title = "Матрица"; progress = 60 }
        compose.setContent { DopomineTheme { PlayerOverlay(ui, NoActions) } }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(500)
        compose.onRoot().captureRoboImage("$out/11_player_loading.png")
    }

    private fun playerUi() = PlayerUiState().apply {
        title = "Матрица"
        subtitle = "1999"
        loading = false
        controlsVisible = true
        canPip = true
        blockedCount = 7
        setSources(
            listOf(
                "Alloha · Оригинальный · BDRip",
                "Collaps · Рус. Дублированный · FHD (1080p)",
                "Turbo · Не указано · -",
                "Veoveo · Русский. Авторский. CDV. AAC · -",
                "Vibix · Профессиональный (дублированный) · FullHD",
            ),
            selected = 1,
        )
        resumedAt = 3723
    }

    private object NoActions : PlayerActions {
        override fun close() = Unit
        override fun reload() = Unit
        override fun enterPip() = Unit
        override fun rotate() = Unit
        override fun selectSource(index: Int) = Unit
        override fun restartFromBeginning() = Unit
        override fun openSources() = Unit
    }
}
