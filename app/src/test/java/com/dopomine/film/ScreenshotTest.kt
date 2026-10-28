package com.dopomine.film

import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
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
import com.dopomine.film.player.GestureKind
import com.dopomine.film.player.Mode
import com.dopomine.film.player.NativeState
import com.dopomine.film.player.PlayerSession
import com.dopomine.film.player.Sheet
import com.dopomine.film.player.SourceChoice
import com.dopomine.film.player.TrackOption
import com.dopomine.film.player.resolve.Episode
import com.dopomine.film.player.resolve.Playable
import com.dopomine.film.player.resolve.Season
import com.dopomine.film.player.resolve.Stream
import com.dopomine.film.player.resolve.StreamFormat
import com.dopomine.film.player.skip.Segment
import com.dopomine.film.player.skip.SegmentKind
import com.dopomine.film.player.ui.PlayerControls
import com.dopomine.film.player.ui.PlayerScreen
import com.dopomine.film.ui.components.LocalAnimations
import com.dopomine.film.ui.navigation.AppNavHost
import com.dopomine.film.ui.theme.DopomineTheme
import com.dopomine.film.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
 * Renders the screens against the bundled catalog, with canned answers instead of the web,
 * so the design can be reviewed without a device.
 * Run: ./gradlew :app:recordRoborazziDebug (images in app/build/outputs/roborazzi).
 * Real posters are used when -Ddpm.posters=<dir with ID.jpg> is passed, dotted placeholders otherwise.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h880dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var container: AppContainer
    private val out = "build/outputs/roborazzi"

    private val canned = Interceptor { chain ->
        val url = chain.request().url
        val (type, body) = when {
            url.host == "rating.kinopoisk.ru" ->
                "text/xml" to """<rating><kp_rating num_vote="817503">8.501</kp_rating><imdb_rating num_vote="2200000">8.7</imdb_rating></rating>"""
            url.host.endsWith("wikipedia.org") -> "application/json" to """{"extract":"«Матрица» — научно-фантастический боевик 1999 года. Хакер Нео узнаёт, что привычный мир — компьютерная симуляция, созданная машинами, и присоединяется к восстанию против них."}"""
            url.host == "query.wikidata.org" -> "application/json" to """{"results":{"bindings":[
                {"role":{"value":"d"},"name":{"value":"Лана Вачовски"}},{"role":{"value":"d"},"name":{"value":"Лилли Вачовски"}},
                {"role":{"value":"c"},"name":{"value":"Киану Ривз"}},{"role":{"value":"c"},"name":{"value":"Лоренс Фишбёрн"}},
                {"role":{"value":"c"},"name":{"value":"Кэрри-Энн Мосс"}},{"role":{"value":"c"},"name":{"value":"Хьюго Уивинг"}}]}}"""
            else -> return@Interceptor Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(404).message("no")
                .body("".toResponseBody(null)).build()
        }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body.toResponseBody(type.toMediaType())).build()
    }

    @OptIn(DelicateCoilApi::class)
    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication() as DopomineApp
        container = AppContainer(app, canned)
        app.container = container
        runBlocking { container.settings.current() }

        val engine = FakeImageLoaderEngine.Builder().apply {
            System.getProperty("dpm.posters")?.let(::File)?.listFiles { f -> f.extension == "jpg" }?.forEach { file ->
                val bitmap = BitmapFactory.decodeFile(file.path) ?: return@forEach
                val id = file.nameWithoutExtension
                intercept({ it is String && (it.endsWith("_$id.jpg") || it.endsWith("/$id.jpg")) }, bitmap.asImage())
            }
        }.build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(app).components { add(engine) }.build())
    }

    private fun app(theme: ThemeMode = ThemeMode.LIGHT, block: (NavHostController) -> Unit = {}) {
        lateinit var controller: NavHostController
        compose.setContent {
            DopomineTheme(theme) {
                controller = rememberNavController()
                AppNavHost(deepLinks = emptyFlow(), animations = false, controller = controller)
            }
        }
        settle()
        compose.runOnUiThread { block(controller) }
        settle()
    }

    private fun settle() {
        repeat(5) {
            Thread.sleep(300)
            compose.mainClock.advanceTimeBy(400)
            compose.waitForIdle()
        }
    }

    @Test
    fun home() {
        runBlocking {
            container.library.markOpened(Movie(258687, "Интерстеллар", year = 2014, genres = listOf("Фантастика")))
            container.library.saveProgress(258687, "movie", 3_900_000, 10_140_000)
            container.library.markOpened(Movie(404900, "Во все тяжкие", year = 2008, type = ContentType.SERIES))
            container.library.saveProgress(404900, "s2e4", 1_200_000, 2_820_000, 2, 4)
        }
        app()
        compose.onRoot().captureRoboImage("$out/01_home_light.png")
    }

    @Test
    fun homeDarkSeries() {
        app(ThemeMode.DARK)
        compose.onNodeWithText("Сериалы").performClick()
        settle()
        compose.onRoot().captureRoboImage("$out/02_home_dark_series.png")
    }

    @Test
    fun homeAnime() {
        app()
        compose.onNodeWithText("Аниме").performClick()
        settle()
        compose.onRoot().captureRoboImage("$out/13_home_anime.png")
    }

    @Test
    fun details() {
        app { it.navigate("details/301") }
        compose.onRoot().captureRoboImage("$out/03_details.png")
    }

    @Test
    fun detailsDark() {
        app(ThemeMode.DARK) { it.navigate("details/464963") }
        compose.onRoot().captureRoboImage("$out/04_details_dark.png")
    }

    @Test
    fun catalog() {
        app { it.navigate("catalog") }
        compose.onRoot().captureRoboImage("$out/05_catalog.png")
    }

    @Test
    fun search() {
        app { it.navigate("search") }
        compose.runOnUiThread { }
        compose.onNodeWithText("Фильм, сериал или ссылка Кинопоиска…").performClick()
        settle()
        compose.onRoot().captureRoboImage("$out/06_search_empty.png")
    }

    @Test
    fun library() {
        runBlocking {
            listOf(301, 326, 464963, 535341, 41519).forEach { id -> container.library.toggleFavorite(container.catalog.movie(id)) }
        }
        app { it.navigate("library") }
        compose.onRoot().captureRoboImage("$out/07_library.png")
    }

    @Test
    fun settings() {
        app { it.navigate("settings") }
        compose.onRoot().captureRoboImage("$out/08_settings.png")
    }

    // ---- player ----------------------------------------------------------------------------

    private fun playerSession() = PlayerSession().apply {
        title = "Игра престолов"
        subtitle = "Collaps · 1 сезон · 3 серия"
        mode = Mode.NATIVE
        controlsVisible = true
        canPip = true
        choices = listOf(
            SourceChoice(SourceChoice.COLLAPS, "Collaps", "Прямой поток · озвучки, качество и серии в приложении", null),
            SourceChoice("src:Alloha", "Alloha", "Поток через сайт · Дублированный · 4K", null),
            SourceChoice("src:Turbo", "Turbo", "Поток через сайт", null),
            SourceChoice(SourceChoice.SITE_PLAYER, "Плеер сайта", "Страница сайта без рекламы, без нативных функций", null),
        )
        currentChoice = SourceChoice.COLLAPS
        val stream = Stream("https://x/y.m3u8", StreamFormat.HLS)
        playable = Playable.Series("Collaps", (1..3).map { s -> Season(s, (1..10).map { e -> Episode(s, e, if (e == 1) "Зима близко" else null, stream) }) })
        season = 1
        episode = 3
        resumedAt = 1325
    }

    private fun nativeState() = NativeState().apply {
        isPlaying = true
        buffering = false
        positionMs = 1_325_000
        durationMs = 3_720_000
        bufferedMs = 1_700_000
        audio = listOf(TrackOption("0:0", "LostFilm", null, true), TrackOption("1:0", "AlexFilm", null, false), TrackOption("2:0", "Оригинал", "5.1", false))
        qualities = listOf(TrackOption("auto", "Авто", null, true), TrackOption("3:0", "1080p", "6.2 Мбит/с", false), TrackOption("3:1", "720p", null, false))
    }

    private object NoControls : PlayerControls {
        override fun close() = Unit
        override fun togglePlay() = Unit
        override fun seekBy(ms: Long) = Unit
        override fun seekTo(ms: Long) = Unit
        override fun selectAudio(key: String) = Unit
        override fun selectQuality(key: String) = Unit
        override fun selectText(key: String) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun chooseSource(key: String) = Unit
        override fun playEpisode(season: Int, episode: Int) = Unit
        override fun hasNextEpisode() = true
        override fun nextEpisode() = Unit
        override fun openSheet(sheet: Sheet?) = Unit
        override fun restartFromBeginning() = Unit
        override fun cycleResize() = Unit
        override fun rotate() = Unit
        override fun enterPip() = Unit
        override fun showSiteOverlay() = Unit
        override fun hideSiteOverlay() = Unit
        override fun canUseSiteOverlay() = false
        override fun retry() = Unit
        override fun readLevel(kind: GestureKind) = 0.5f
        override fun setLevel(kind: GestureKind, value: Float) = Unit
        override fun gestureEnd() = Unit
        override fun skipSegment() = Unit
        override fun undoSkip() = Unit
        override fun cancelNextEpisode() = Unit
        override fun toggleAutoSkip() = Unit
        override fun markIntro() = Unit
        override fun clearIntroMark() = Unit
    }

    @Test
    @Config(qualifiers = "w880dp-h400dp-xxhdpi")
    fun playerControls() {
        val session = playerSession()
        compose.setContent { DopomineTheme(ThemeMode.DARK) { PlayerScreen(session, nativeState(), NoControls, gesturesEnabled = true) } }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(300)
        compose.onRoot().captureRoboImage("$out/09_player_controls.png")
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    @Config(qualifiers = "w880dp-h400dp-xxhdpi")
    fun playerEpisodes() {
        val session = playerSession().apply { sheet = Sheet.EPISODES }
        compose.setContent { DopomineTheme(ThemeMode.DARK) { PlayerScreen(session, nativeState(), NoControls, gesturesEnabled = true) } }
        settle()
        captureScreenRoboImage("$out/10_player_episodes.png")
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    @Config(qualifiers = "w880dp-h400dp-xxhdpi")
    fun playerVoices() {
        val session = playerSession().apply { sheet = Sheet.AUDIO }
        compose.setContent { DopomineTheme(ThemeMode.DARK) { PlayerScreen(session, nativeState(), NoControls, gesturesEnabled = true) } }
        settle()
        captureScreenRoboImage("$out/11_player_voices.png")
    }

    @Test
    @Config(qualifiers = "w880dp-h400dp-xxhdpi")
    fun playerResolving() {
        val session = PlayerSession().apply { title = "Матрица"; status = "Collaps: получаем поток…" }
        compose.setContent {
            DopomineTheme(ThemeMode.DARK) {
                CompositionLocalProvider(LocalAnimations provides false) { PlayerScreen(session, NativeState(), NoControls, gesturesEnabled = true) }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(300)
        compose.onRoot().captureRoboImage("$out/12_player_resolving.png")
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    @Config(qualifiers = "w880dp-h400dp-xxhdpi")
    fun playerAnimeSources() {
        val session = playerSession().apply {
            title = "Провожающая в последний путь Фрирен"
            subtitle = "AniLiberty · 1 сезон · 5 серия"
            choices = listOf(
                SourceChoice(SourceChoice.ANILIBERTY, "AniLiberty", "Аниме · озвучка AniLibria, до 1080p, все сезоны", null),
                SourceChoice(SourceChoice.ANIMEVOST, "AnimeVost", "Аниме · озвучка AnimeVost, 720p", null),
                SourceChoice(SourceChoice.VEOVEO, "VeoVeo", "Прямой поток · озвучки, субтитры, качество и серии в приложении", null),
                SourceChoice(SourceChoice.COLLAPS, "Collaps", "Прямой поток · озвучки, качество и серии в приложении", null),
                SourceChoice(SourceChoice.SITE_PLAYER, "Плеер сайта", "Страница сайта без рекламы, без нативных функций", null),
            )
            currentChoice = SourceChoice.ANILIBERTY
            sheet = Sheet.SOURCES
        }
        val state = nativeState().apply {
            audio = emptyList()
            qualities = listOf(TrackOption("v:0", "1080p", null, true), TrackOption("v:1", "720p", null, false), TrackOption("v:2", "480p", null, false))
        }
        compose.setContent { DopomineTheme(ThemeMode.DARK) { PlayerScreen(session, state, NoControls, gesturesEnabled = true) } }
        settle()
        captureScreenRoboImage("$out/14_player_anime_sources.png")
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    @Config(qualifiers = "w880dp-h400dp-xxhdpi")
    fun playerSkipIntro() {
        val intro = Segment(SegmentKind.INTRO, 6_000, 102_000, "TheIntroDB")
        val session = playerSession().apply {
            resumedAt = null
            autoSkip = true
            segments = listOf(intro, Segment(SegmentKind.CREDITS, 3_366_000, 3_437_000, "IntroDB"))
            activeSegment = intro
        }
        val state = nativeState().apply { positionMs = 40_000 }
        compose.setContent { DopomineTheme(ThemeMode.DARK) { PlayerScreen(session, state, NoControls, gesturesEnabled = true) } }
        settle()
        captureScreenRoboImage("$out/15_player_skip_intro.png")
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    @Config(qualifiers = "w880dp-h400dp-xxhdpi")
    fun playerSkipSheet() {
        val session = playerSession().apply {
            resumedAt = null
            segments = listOf(Segment(SegmentKind.INTRO, 6_000, 102_000, "TheIntroDB"), Segment(SegmentKind.CREDITS, 3_366_000, 3_437_000, "IntroDB"))
            introMark = 30 to 95
            sheet = Sheet.SKIP
        }
        compose.setContent { DopomineTheme(ThemeMode.DARK) { PlayerScreen(session, nativeState(), NoControls, gesturesEnabled = true) } }
        settle()
        captureScreenRoboImage("$out/16_player_skip_sheet.png")
    }
}
