package com.dopomine.film.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Rational
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.dopomine.film.R
import com.dopomine.film.appContainer
import com.dopomine.film.data.model.ContentType
import com.dopomine.film.data.model.Movie
import com.dopomine.film.data.settings.AppSettings
import com.dopomine.film.player.resolve.AniLibertyResolver
import com.dopomine.film.player.resolve.AnimevostResolver
import com.dopomine.film.player.resolve.Playable
import com.dopomine.film.player.resolve.SourceInfo
import com.dopomine.film.player.resolve.Stream
import com.dopomine.film.player.skip.Segment
import com.dopomine.film.player.skip.SegmentKind
import com.dopomine.film.player.skip.SegmentRules
import com.dopomine.film.player.skip.SkipRequest
import com.dopomine.film.player.ui.PlayerControls
import com.dopomine.film.player.ui.PlayerScreen
import com.dopomine.film.ui.components.Format
import com.dopomine.film.ui.theme.DopomineTheme
import com.dopomine.film.ui.theme.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * The player. It finds where the player site takes the video from and plays that stream
 * natively (ExoPlayer):
 *  1. Collaps — its embed page lists the HLS stream, voice-overs, subtitles and all episodes;
 *  2. any other player — the site runs hidden and the stream address its player requests is caught;
 *  3. if neither works, the site's own player is shown (ads and popups blocked).
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity(), PlayerControls, WebPlayer.Listener {

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_ORIGINAL = "original"
        private const val EXTRA_YEAR = "year"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_IMDB = "imdb"
        private const val EXTRA_MAL = "mal"
        private const val EXTRA_ANIME = "anime"
        private const val EXTRA_KP = "kp"
        private const val EXTRA_IMDB_RATING = "imdb_rating"
        private const val ACTION_PIP_TOGGLE = "com.dopomine.film.PIP_TOGGLE"
        private const val SNIFF_TIMEOUT_MS = 30_000L
        private const val MAX_NETWORK_RETRIES = 2
        private const val NEXT_COUNTDOWN = 5

        fun intent(context: Context, movie: Movie): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_ID, movie.id)
                .putExtra(EXTRA_TITLE, movie.title)
                .putExtra(EXTRA_ORIGINAL, movie.originalTitle)
                .putExtra(EXTRA_YEAR, movie.year ?: 0)
                .putExtra(EXTRA_TYPE, movie.type.name)
                .putExtra(EXTRA_IMDB, movie.imdbId)
                .putExtra(EXTRA_MAL, movie.malId ?: 0)
                .putExtra(EXTRA_ANIME, movie.isAnime)
                .putExtra(EXTRA_KP, movie.ratingKp ?: 0.0)
                .putExtra(EXTRA_IMDB_RATING, movie.ratingImdb ?: 0.0)

        private fun Intent.toMovie(): Movie? {
            val id = getIntExtra(EXTRA_ID, 0).takeIf { it > 0 } ?: return null
            return Movie(
                id = id,
                title = getStringExtra(EXTRA_TITLE) ?: "Кинопоиск #$id",
                originalTitle = getStringExtra(EXTRA_ORIGINAL),
                year = getIntExtra(EXTRA_YEAR, 0).takeIf { it > 0 },
                type = runCatching { ContentType.valueOf(getStringExtra(EXTRA_TYPE) ?: "") }.getOrDefault(ContentType.FILM),
                imdbId = getStringExtra(EXTRA_IMDB),
                malId = getIntExtra(EXTRA_MAL, 0).takeIf { it > 0 },
                genres = if (getBooleanExtra(EXTRA_ANIME, false)) listOf(Movie.ANIME) else emptyList(),
                ratingKp = getDoubleExtra(EXTRA_KP, 0.0).takeIf { it > 0 },
                ratingImdb = getDoubleExtra(EXTRA_IMDB_RATING, 0.0).takeIf { it > 0 },
            )
        }
    }

    private val container by lazy { appContainer }
    private val session = PlayerSession()
    private lateinit var movie: Movie
    private lateinit var settings: AppSettings
    private lateinit var native: NativePlayer
    private var web: WebPlayer? = null

    private lateinit var root: FrameLayout
    private lateinit var webContainer: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var overlayView: ComposeView
    private lateinit var gestures: PlayerGestures
    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private var resolveJob: Job? = null
    private var sources: List<SourceInfo> = emptyList()
    private var sniffWaiter: CompletableDeferred<Stream>? = null
    private var sniffedName: String? = null
    private var itemKey: String? = null
    private var itemSeason: Int? = null
    private var itemEpisode: Int? = null
    private var durationResumeChecked = false

    // intro skipping
    private var sourceSegments: List<Segment> = emptyList()
    private var segmentsRequested = false
    private var segmentsGeneration = 0
    private var segmentsJob: Job? = null
    private var countdownJob: Job? = null
    /** Segments skipped (or declined) in the current video: never skipped automatically again. */
    private val doneSegments = HashSet<Segment>()
    private var webDurationMs = 0L
    private var networkRetries = 0
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var customView: View? = null
    private var gestureHideJob: Job? = null
    private var pipReceiverRegistered = false

    private val mirror: String get() = settings.playerHost.trimEnd('/')

    // ---- Lifecycle -----------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        movie = intent.toMovie() ?: run { finish(); return }
        settings = container.settings.state.value ?: AppSettings()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        requestedOrientation = if (settings.landscapePlayer) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        session.title = movie.title
        session.canPip = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        session.autoSkip = settings.autoSkip

        native = NativePlayer(this, container.mediaClient).apply {
            onEnded = ::onItemEnded
            onError = ::onNativeError
            onPlayingChanged = { updatePipParams() }
            preferredVoice = getSharedPreferences("voices", MODE_PRIVATE).getString(movie.id.toString(), null)
        }

        gestures = PlayerGestures(
            context = this,
            read = ::readLevel,
            apply = ::setLevel,
            onEnd = ::gestureEnd,
            onReveal = { session.controlsVisible = true },
        ).apply { enabled = settings.gestures }

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        webContainer = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            useController = false
            player = native.exo
            setShutterBackgroundColor(Color.BLACK)
            setBackgroundColor(Color.BLACK)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setKeepContentOnPlayerReset(true)
        }
        fullscreenContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        overlayView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val appSettings by container.settings.state.collectAsStateWithLifecycle()
                // the player is always dark, whatever the app theme
                DopomineTheme(ThemeMode.DARK) {
                    PlayerScreen(session, native.state, this@PlayerActivity, gesturesEnabled = appSettings?.gestures ?: true)
                }
            }
        }
        root.addView(webContainer, matchParent())
        root.addView(playerView, matchParent())
        root.addView(fullscreenContainer, matchParent())
        root.addView(overlayView, matchParent())
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    session.sheet != null -> session.sheet = null
                    customView != null -> { customViewCallback?.onCustomViewHidden(); hideWebFullscreen() }
                    session.webOverlay -> hideSiteOverlay()
                    else -> finish()
                }
            }
        })

        registerPipReceiver()
        lifecycleScope.launch { container.library.markOpened(movie) }
        startTicker()
        resolveJob = lifecycleScope.launch { resolve() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val next = intent.toMovie() ?: return
        if (next.id == movie.id) return
        saveProgress()
        movie = next
        session.title = movie.title
        native.preferredVoice = getSharedPreferences("voices", MODE_PRIVATE).getString(movie.id.toString(), null)
        lifecycleScope.launch { container.library.markOpened(movie) }
        resolveJob?.cancel()
        resolveJob = lifecycleScope.launch { resolve() }
    }

    override fun onResume() {
        super.onResume()
        web?.onResume()
        hideSystemBars()
    }

    override fun onStop() {
        super.onStop()
        saveProgress()
        if (!isInPictureInPictureMode) {
            native.exo.pause()
            if (session.mode == Mode.WEB) web?.pausePage()
            web?.onPause()
        }
    }

    override fun onDestroy() {
        if (pipReceiverRegistered) runCatching { unregisterReceiver(pipReceiver) }
        if (::native.isInitialized) native.release()
        web?.destroy()
        web = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // ---- Finding the stream ----------------------------------------------------------------------

    private fun log(line: String) = session.log(line)

    private suspend fun resolve() {
        session.mode = Mode.RESOLVING
        session.error = null
        session.status = "Ищем, откуда сайт берёт видео…"
        session.log.clear()
        // favourites and history keep only a few fields: take the IDs the players match by from the catalog
        if (movie.malId == null || movie.imdbId == null) {
            runCatching { container.catalogDb.byId(movie.id) }.getOrNull()?.let { full ->
                movie = movie.copy(
                    imdbId = movie.imdbId ?: full.imdbId,
                    malId = movie.malId ?: full.malId,
                    genres = (movie.genres + full.genres).distinct(),
                )
            }
        }
        log("Фильм: ${movie.title} (Кинопоиск ${movie.id}${if (movie.isAnime) ", аниме" else ""})")
        session.introMark = container.settings.introMark(movie.id)
        session.markStart = null

        val sources = runCatching { container.playerSite.sources(mirror, movie.id, movie.imdbId) }
            .onFailure { if (it is CancellationException) throw it; log("Список плееров не получен: ${it.message}") }
            .getOrDefault(emptyList())
        if (sources.isNotEmpty()) log("Плееры на сайте: ${sources.joinToString { it.name }}")
        this.sources = sources
        session.choices = buildChoices(sources)

        for (key in directOrder) if (tryDirect(key)) return

        if (sniff("Ловим поток со страницы плеера…", SourceChoice.SITE, "Сайт") { it.loadSite(mirror, movie.id) }) return

        // the blocker may have cut something the player needs: one more try without it (manifests only, so ads cannot be caught)
        if (settings.adBlock && sniff("Пробуем ещё раз без фильтра рекламы…", SourceChoice.SITE, "Сайт", adBlock = false) { it.loadSite(mirror, movie.id) }) return

        log("Прямой поток не найден — открываем плеер сайта")
        showSitePlayer()
    }

    /** Sources played natively, in the order they are tried; anime has its own dub teams first. */
    private val directOrder: List<String>
        get() = buildList {
            if (movie.isAnime) {
                add(SourceChoice.ANILIBERTY)
                add(SourceChoice.ANIMEVOST)
            }
            add(SourceChoice.VEOVEO)
            add(SourceChoice.COLLAPS)
        }

    private suspend fun tryDirect(key: String): Boolean = when (key) {
        SourceChoice.ANILIBERTY -> tryAnime(key, AniLibertyResolver.NAME) { container.aniliberty.resolve(movie, ::log) }
        SourceChoice.ANIMEVOST -> tryAnime(key, AnimevostResolver.NAME) { container.animevost.resolve(movie, ::log) }
        SourceChoice.VEOVEO -> sources.firstOrNull { it.isVeoVeo }?.let {
            session.status = "VeoVeo: получаем поток…"
            tryVeoVeo(it)
        } ?: false
        SourceChoice.COLLAPS -> {
            session.status = "Collaps: получаем поток…"
            tryCollaps(sources.firstOrNull { it.isCollaps })
        }
        else -> false
    }

    private suspend fun tryAnime(key: String, name: String, resolve: suspend () -> Playable): Boolean = try {
        session.status = "$name: ищем аниме…"
        val playable = resolve()
        log("$name: поток найден")
        startNative(playable, key)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("$name: ${e.message}")
        false
    }

    private fun buildChoices(sources: List<SourceInfo>): List<SourceChoice> = buildList {
        if (movie.isAnime) {
            add(SourceChoice(SourceChoice.ANILIBERTY, "AniLiberty", "Аниме · озвучка AniLibria, до 1080p, все сезоны", null))
            add(SourceChoice(SourceChoice.ANIMEVOST, "AnimeVost", "Аниме · озвучка AnimeVost, 720p", null))
        }
        sources.firstOrNull { it.isVeoVeo }?.let {
            add(SourceChoice(SourceChoice.VEOVEO, "VeoVeo", "Прямой поток · озвучки, субтитры, качество и серии в приложении", it))
        }
        add(SourceChoice(SourceChoice.COLLAPS, "Collaps", "Прямой поток · озвучки, качество и серии в приложении",
            sources.firstOrNull { it.isCollaps }))
        sources.filterNot { it.isDirect }.forEach { s ->
            add(SourceChoice("src:${s.name}", s.name, "Поток через сайт" + (s.voices.firstOrNull()?.let { " · $it" } ?: ""), s))
        }
        add(SourceChoice(SourceChoice.SITE, "Авто", "Поток плеера, который выберет сайт", null))
        add(SourceChoice(SourceChoice.SITE_PLAYER, "Плеер сайта", "Страница сайта без рекламы, без нативных функций", null))
    }

    private suspend fun tryVeoVeo(info: SourceInfo): Boolean = try {
        val playable = container.veoveo.resolve(info.iframeUrl, ::log)
        log("VeoVeo: поток найден")
        startNative(playable, SourceChoice.VEOVEO)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("VeoVeo: ${e.message}")
        false
    }

    private suspend fun tryCollaps(info: SourceInfo?): Boolean {
        val picker = runCatching { container.playerSite.pickerHost(mirror, movie.id) }.getOrDefault("linkpp.ink")
        return try {
            val playable = container.collaps.resolve(movie.id, movie.imdbId, info?.iframeUrl, "https://$picker/", ::log)
            log("Collaps: поток найден")
            startNative(playable, SourceChoice.COLLAPS)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Collaps: ${e.message}")
            false
        }
    }

    /** Runs the page hidden and waits for its player to request a stream. */
    private suspend fun sniff(
        status: String,
        choiceKey: String,
        name: String,
        adBlock: Boolean = settings.adBlock,
        load: (WebPlayer) -> Unit,
    ): Boolean {
        session.mode = Mode.RESOLVING
        session.status = status
        val w = ensureWeb()
        w.adBlock = adBlock
        w.manifestsOnly = !adBlock
        w.setSniffing(true)
        webContainer.visibility = View.VISIBLE
        playerView.visibility = View.VISIBLE
        native.exo.stop()
        val waiter = CompletableDeferred<Stream>()
        sniffWaiter = waiter
        sniffedName = name
        load(w)
        val stream = withTimeoutOrNull(SNIFF_TIMEOUT_MS) { waiter.await() }
        sniffWaiter = null
        if (stream == null) {
            log("$name: поток не появился за ${SNIFF_TIMEOUT_MS / 1000} с")
            w.setSniffing(false)
            return false
        }
        w.muteAll()
        session.currentChoice = choiceKey
        playSniffed(stream, name)
        return true
    }

    private fun ensureWeb(): WebPlayer = web ?: WebPlayer(this, container.adBlocker, this).also {
        web = it
        webContainer.addView(it.view, matchParent())
    }

    private suspend fun startNative(playable: Playable, choiceKey: String) {
        web?.setSniffing(false)
        web?.pausePage()
        webContainer.visibility = View.INVISIBLE
        session.playable = playable
        session.currentChoice = choiceKey
        sniffedName = null
        when (playable) {
            is Playable.Single -> playItem(playable.stream, "movie", null, null, playable.sourceName)
            is Playable.Series -> {
                val last = container.library.lastEpisode(movie.id)
                val season = playable.seasons.firstOrNull { it.number == last?.first }
                    ?: playable.seasons.firstOrNull { it.number == playable.startSeason }
                    ?: playable.seasons.first()
                val episode = season.episodes.firstOrNull { it.number == last?.second } ?: season.episodes.first()
                playEpisodeInternal(season.number, episode.number)
            }
        }
    }

    private fun playSniffed(stream: Stream, name: String) {
        web?.let { webContainer.visibility = View.INVISIBLE }
        session.playable = Playable.Single(name, stream)
        session.webOverlay = false
        durationResumeChecked = false
        lifecycleScope.launch { playItem(stream, null, null, null, name) }
    }

    private suspend fun playItem(stream: Stream, key: String?, season: Int?, episode: Int?, sourceName: String) {
        saveProgress()
        networkRetries = 0
        itemKey = key
        itemSeason = season
        itemEpisode = episode
        resetSegments(stream.segments)
        val start = if (key != null && settings.autoResume) {
            container.library.position(movie.id, key)?.takeIf { it > 30 }?.let { it * 1000L } ?: 0L
        } else 0L
        val playable = runCatching { container.veoveo.finalize(stream) }.getOrElse {
            if (it is CancellationException) throw it
            log("Не удалось получить ссылку: ${it.message}")
            stream
        }
        playerView.visibility = View.VISIBLE
        native.play(playable, start)
        session.resumedAt = if (start > 0) (start / 1000).toInt() else null
        session.mode = Mode.NATIVE
        session.controlsVisible = true
        session.subtitle = listOfNotNull(
            sourceName,
            if (season != null && episode != null) "$season сезон · $episode серия" else null,
        ).joinToString(" · ")
        log("Воспроизведение: ${stream.url.take(100)}")
    }

    private suspend fun playEpisodeInternal(season: Int, episode: Int) {
        val series = session.playable as? Playable.Series ?: return
        val s = series.seasons.firstOrNull { it.number == season } ?: return
        val e = s.episodes.firstOrNull { it.number == episode } ?: return
        session.season = season
        session.episode = episode
        playItem(e.stream, "s${season}e$episode", season, episode, series.sourceName)
    }

    private fun showSitePlayer() {
        val w = ensureWeb()
        native.exo.stop()
        w.adBlock = settings.adBlock
        w.manifestsOnly = false
        w.setSniffing(false)
        session.playable = null
        session.currentChoice = SourceChoice.SITE_PLAYER
        resetSegments(emptyList())
        webDurationMs = 0L
        session.mode = Mode.WEB
        session.subtitle = "Плеер сайта"
        playerView.visibility = View.GONE
        webContainer.visibility = View.VISIBLE
        w.loadSite(mirror, movie.id)
    }

    // ---- Playback events --------------------------------------------------------------------------

    private fun startTicker() {
        lifecycleScope.launch {
            var sinceSave = 0
            while (isActive) {
                delay(500)
                if (session.mode != Mode.NATIVE) continue
                native.tick()
                if (!durationResumeChecked && itemKey == null && native.state.durationMs > 0) {
                    durationResumeChecked = true
                    resumeByDuration()
                }
                if (!segmentsRequested && native.state.durationMs > 0) {
                    segmentsRequested = true
                    loadSegments(native.exo.duration, itemSeason, itemEpisode)
                }
                checkSegments(native.exo.currentPosition, native.exo.duration, native.state.isPlaying)
                sinceSave++
                if (sinceSave >= 10 && native.state.isPlaying) {
                    sinceSave = 0
                    saveProgress()
                }
            }
        }
    }

    // ---- Intro skipping ---------------------------------------------------------------------------

    private fun resetSegments(own: List<Segment>) {
        sourceSegments = own
        segmentsRequested = false
        segmentsJob?.cancel()
        doneSegments.clear()
        cancelCountdown()
        session.segments = emptyList()
        session.activeSegment = null
        session.skipped = null
    }

    /** Asks the intro databases once the duration is known (it tells their cuts apart). */
    private fun loadSegments(duration: Long, season: Int?, episode: Int?) {
        if (duration <= 0) return
        val own = sourceSegments
        val mark = session.introMark?.let { (a, b) -> listOf(Segment(SegmentKind.INTRO, a * 1000L, b * 1000L, "Ваша разметка")) }.orEmpty()
        // a caught stream of a show has no episode number, so only the viewer's own mark applies to it
        val lookup = !(movie.type.isSeries && episode == null)
        val mal = (session.playable as? Playable.Series)?.seasons?.firstOrNull { it.number == season }?.malId
            ?: movie.malId.takeIf { season == null || season == 1 }
        val request = SkipRequest(movie.imdbId, mal, season, episode, duration)
        val generation = ++segmentsGeneration
        segmentsJob = lifecycleScope.launch {
            val found = if (lookup) runCatching { container.skipSegments.fetch(request, ::log) }.getOrDefault(emptyList()) else emptyList()
            if (generation != segmentsGeneration) return@launch
            session.segments = SegmentRules.merge(duration, own, found, mark)
            if (session.segments.isNotEmpty()) {
                log("Пропуск: " + session.segments.joinToString { s ->
                    "${s.kind.title.lowercase()} ${Format.clock((s.startMs / 1000).toInt())}–${s.endMs?.let { Format.clock((it / 1000).toInt()) } ?: "конец"} (${s.source})"
                })
            }
        }
    }

    private fun checkSegments(position: Long, duration: Long, playing: Boolean) {
        if (duration <= 0) return
        val segment = session.segments.firstOrNull { it.contains(position, duration) }
        if (segment?.kind != SegmentKind.CREDITS && session.nextCountdown != null) cancelCountdown()
        when {
            segment == null -> session.activeSegment = null
            // a film's credits lead nowhere; a show's lead to the next episode
            segment.kind == SegmentKind.CREDITS -> {
                if (nextEpisodeRef() == null) {
                    session.activeSegment = null
                } else {
                    session.activeSegment = segment
                    if (session.autoSkip && segment !in doneSegments && countdownJob == null && playing) startCountdown(segment)
                }
            }
            session.autoSkip && segment !in doneSegments -> {
                doneSegments += segment
                seekCurrent(segment.end(duration))
                session.activeSegment = null
                session.skipped = segment
            }
            else -> session.activeSegment = segment
        }
    }

    private fun startCountdown(segment: Segment) {
        countdownJob = lifecycleScope.launch {
            for (left in NEXT_COUNTDOWN downTo 1) {
                session.nextCountdown = left
                delay(1_000)
                if (!native.state.isPlaying) {
                    cancelCountdown()
                    return@launch
                }
            }
            countdownJob = null
            session.nextCountdown = null
            doneSegments += segment
            nextEpisode()
        }
    }

    private fun cancelCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        session.nextCountdown = null
    }

    override fun skipSegment() {
        val segment = session.activeSegment ?: return
        doneSegments += segment
        session.activeSegment = null
        if (segment.kind == SegmentKind.CREDITS && nextEpisodeRef() != null) {
            cancelCountdown()
            nextEpisode()
        } else {
            seekCurrent(segment.end(if (session.mode == Mode.WEB) webDurationMs else native.exo.duration))
        }
    }

    override fun undoSkip() {
        val segment = session.skipped ?: return
        session.skipped = null
        seekCurrent(segment.startMs)
    }

    private fun seekCurrent(ms: Long) {
        if (session.mode == Mode.WEB) web?.seekVideo(ms / 1000.0) else native.seekTo(ms)
    }

    override fun cancelNextEpisode() {
        session.activeSegment?.let { doneSegments += it }
        cancelCountdown()
    }

    override fun toggleAutoSkip() {
        val value = !session.autoSkip
        session.autoSkip = value
        flash(if (value) "Автопропуск заставок включён" else "Автопропуск заставок выключен")
        lifecycleScope.launch { container.settings.setAutoSkip(value) }
    }

    /** First tap marks where the intro starts, the second where it ends; it then applies to every episode. */
    override fun markIntro() {
        val position = ((if (session.mode == Mode.WEB) session.webPositionMs else native.exo.currentPosition) / 1000).toInt()
        val start = session.markStart
        if (start == null || position <= start + 3) {
            session.markStart = position
            return
        }
        val mark = start to position
        session.markStart = null
        session.introMark = mark
        lifecycleScope.launch { container.settings.setIntroMark(movie.id, mark) }
        log("Ваша разметка заставки: ${Format.clock(start)}–${Format.clock(position)}")
        segmentsRequested = false
    }

    override fun clearIntroMark() {
        session.introMark = null
        session.markStart = null
        lifecycleScope.launch { container.settings.setIntroMark(movie.id, null) }
        segmentsRequested = false
    }

    private fun flash(text: String) {
        session.seekHint = text
        lifecycleScope.launch {
            delay(1_200)
            if (session.seekHint == text) session.seekHint = null
        }
    }

    /** Sniffed streams have no episode IDs; the video is recognised by its duration instead. */
    private suspend fun resumeByDuration() {
        val duration = (native.state.durationMs / 1000).toInt()
        if (duration < 300) return
        itemKey = "d$duration"
        if (!settings.autoResume) return
        val saved = (-5..5).firstNotNullOfOrNull { delta -> container.library.position(movie.id, "d${duration + delta}") }
        if (saved != null && saved > 30 && saved < duration - 90 && native.state.positionMs < 15_000) {
            native.seekTo(saved * 1000L)
            session.resumedAt = saved
        }
    }

    private fun saveProgress() {
        val key = itemKey ?: return
        if (!::native.isInitialized) return
        val pos = native.exo.currentPosition
        val dur = native.exo.duration
        if (dur <= 0 || pos <= 0) return
        val season = itemSeason
        val episode = itemEpisode
        lifecycleScope.launch { container.library.saveProgress(movie.id, key, pos, dur, season, episode) }
    }

    private fun onItemEnded() {
        saveProgress()
        if (settings.autoNext && nextEpisodeRef() != null) nextEpisode() else session.controlsVisible = true
    }

    private fun onNativeError(e: PlaybackException) {
        log("Ошибка плеера: ${e.errorCodeName} ${e.cause?.message.orEmpty()}")
        // a dropped connection is not the source's fault: retry in place before switching sources
        val transient = e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        if (transient && networkRetries < MAX_NETWORK_RETRIES) {
            networkRetries++
            log("Сеть прервалась — повтор $networkRetries из $MAX_NETWORK_RETRIES")
            lifecycleScope.launch {
                delay(2000L * networkRetries)
                native.exo.prepare()
                native.exo.play()
            }
            return
        }
        val choice = session.currentChoice
        resolveJob?.cancel()
        resolveJob = lifecycleScope.launch {
            when (choice) {
                // a direct source refused the video: go on down the chain, then catch the site's stream
                in directOrder -> {
                    session.mode = Mode.RESOLVING
                    for (key in directOrder.dropWhile { it != choice }.drop(1)) if (tryDirect(key)) return@launch
                    if (!sniff("Ищем другой поток…", SourceChoice.SITE, "Сайт") { it.loadSite(mirror, movie.id) }) showSitePlayer()
                }
                else -> {
                    log("Поток не воспроизводится в приложении — открываем плеер сайта")
                    showSitePlayer()
                }
            }
        }
    }

    // ---- PlayerControls -----------------------------------------------------------------------------

    override fun close() = finish()

    override fun togglePlay() {
        native.toggle()
        session.controlsVisible = true
    }

    override fun seekBy(ms: Long) = native.seekBy(ms)
    override fun seekTo(ms: Long) = native.seekTo(ms)

    override fun selectAudio(key: String) {
        val label = native.selectAudio(key) ?: return
        getSharedPreferences("voices", MODE_PRIVATE).edit().putString(movie.id.toString(), label).apply()
        session.sheet = null
    }

    override fun selectQuality(key: String) { native.selectQuality(key); session.sheet = null }
    override fun selectText(key: String) { native.selectText(key); session.sheet = null }
    override fun setSpeed(speed: Float) { native.setSpeed(speed); session.sheet = null }

    override fun chooseSource(key: String) {
        session.sheet = null
        if (key == session.currentChoice && session.mode == Mode.NATIVE) return
        saveProgress()
        val choice = session.choices.firstOrNull { it.key == key } ?: return
        resolveJob?.cancel()
        resolveJob = lifecycleScope.launch {
            when {
                key in directOrder -> {
                    session.mode = Mode.RESOLVING
                    if (!tryDirect(key)) {
                        session.error = "${choice.title} не отдал поток для этого фильма. Выберите другой источник."
                        session.mode = Mode.ERROR
                    }
                }
                key == SourceChoice.SITE_PLAYER -> showSitePlayer()
                key == SourceChoice.SITE ->
                    if (!sniff("Ловим поток со страницы плеера…", key, "Сайт") { it.loadSite(mirror, movie.id) }) showSitePlayer()
                choice.info != null -> {
                    val picker = container.playerSite.pickerHost(mirror, movie.id)
                    val ok = sniff("${choice.title}: ловим поток…", key, choice.title) { it.loadEmbed(mirror, picker, choice.info.iframeUrl) }
                    if (!ok) {
                        log("${choice.title}: показываем его плеер как есть")
                        native.exo.stop()
                        session.mode = Mode.WEB
                        session.subtitle = choice.title
                        playerView.visibility = View.GONE
                        webContainer.visibility = View.VISIBLE
                        web?.setSniffing(false)
                        web?.loadEmbed(mirror, picker, choice.info.iframeUrl)
                    }
                }
            }
        }
    }

    override fun playEpisode(season: Int, episode: Int) {
        session.sheet = null
        lifecycleScope.launch { playEpisodeInternal(season, episode) }
    }

    private fun nextEpisodeRef(): Pair<Int, Int>? {
        val series = session.playable as? Playable.Series ?: return null
        val seasons = series.seasons
        val si = seasons.indexOfFirst { it.number == session.season }.takeIf { it >= 0 } ?: return null
        val eps = seasons[si].episodes
        val ei = eps.indexOfFirst { it.number == session.episode }
        return when {
            ei in 0 until eps.lastIndex -> seasons[si].number to eps[ei + 1].number
            si < seasons.lastIndex -> seasons[si + 1].number to seasons[si + 1].episodes.first().number
            else -> null
        }
    }

    override fun hasNextEpisode(): Boolean = nextEpisodeRef() != null

    override fun nextEpisode() {
        val (s, e) = nextEpisodeRef() ?: return
        playEpisode(s, e)
    }

    override fun openSheet(sheet: Sheet?) {
        session.sheet = sheet
    }

    override fun restartFromBeginning() {
        native.seekTo(0)
        session.resumedAt = null
    }

    override fun cycleResize() {
        session.resize = (session.resize + 1) % 3
        playerView.resizeMode = when (session.resize) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    override fun rotate() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    /** For players without an episode list in the app: pick the episode/voice in the site's own UI. */
    override fun showSiteOverlay() {
        val w = web ?: return
        w.setSniffing(true)
        session.webOverlay = true
        native.exo.pause()
        // the site's UI goes between the video and the native chrome
        root.removeView(webContainer)
        root.addView(webContainer, root.indexOfChild(overlayView), matchParent())
        webContainer.visibility = View.VISIBLE
    }

    override fun hideSiteOverlay() {
        session.webOverlay = false
        web?.muteAll()
        root.removeView(webContainer)
        root.addView(webContainer, 0, matchParent())
        webContainer.visibility = View.INVISIBLE
        native.exo.play()
    }

    override fun retry() {
        resolveJob?.cancel()
        resolveJob = lifecycleScope.launch { resolve() }
    }

    override fun canUseSiteOverlay(): Boolean = sniffedName != null && web != null

    // ---- WebPlayer.Listener ----------------------------------------------------------------------

    override fun onStreamFound(stream: Stream) {
        val waiter = sniffWaiter
        if (waiter != null && waiter.isActive) {
            waiter.complete(stream)
            return
        }
        // a new episode or voice picked in the site's UI while the native player runs
        if (session.webOverlay && session.mode == Mode.NATIVE) {
            saveProgress()
            hideSiteOverlay()
            web?.muteAll()
            playSniffed(stream, sniffedName ?: "Сайт")
        }
    }

    override fun onSources(items: List<String>, selected: Int) = Unit

    override fun onPlayingChanged(playing: Boolean) {
        // the hidden page must never play over the native player
        if (playing && session.mode == Mode.NATIVE && !session.webOverlay) web?.muteAll()
    }

    /** The site's own player: the same skipping, driven by the page's video position. */
    override fun onVideoTime(seconds: Double, duration: Double) {
        if (session.mode != Mode.WEB || seconds.isNaN() || duration.isNaN()) return
        val position = (seconds * 1000).toLong()
        val length = (duration * 1000).toLong()
        session.webPositionMs = position
        // another video started in the page (another episode or source)
        if (kotlin.math.abs(length - webDurationMs) > 2_000) {
            webDurationMs = length
            resetSegments(emptyList())
        }
        if (!segmentsRequested) {
            segmentsRequested = true
            loadSegments(length, null, null)
        }
        checkSegments(position, length, playing = true)
    }

    override fun onPageError(message: String) {
        if (session.mode == Mode.WEB) {
            session.error = message
            session.mode = Mode.ERROR
        } else {
            log(message)
        }
    }

    override fun onShowFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (session.mode != Mode.WEB) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        fullscreenContainer.addView(view, matchParent())
        fullscreenContainer.visibility = View.VISIBLE
        session.webFullscreen = true
        hideSystemBars()
    }

    override fun onHideFullscreen() = hideWebFullscreen()

    private fun hideWebFullscreen() {
        val view = customView ?: return
        fullscreenContainer.removeView(view)
        fullscreenContainer.visibility = View.GONE
        customView = null
        customViewCallback = null
        session.webFullscreen = false
        hideSystemBars()
    }

    override fun onRendererGone() {
        web?.let { old ->
            webContainer.removeView(old.view)
            runCatching { old.destroy() }
        }
        web = null
        log("Веб-движок перезапущен")
        if (session.mode == Mode.WEB) showSitePlayer()
    }

    private val loggedBlocked = HashSet<String>()

    override fun onBlocked(url: String) {
        session.blocked++
        val host = android.net.Uri.parse(url).host ?: return
        if (loggedBlocked.size < 40 && loggedBlocked.add(host)) log("Реклама заблокирована: $host")
    }

    override fun onLog(line: String) = log(line)

    // ---- Gestures (web mode uses the activity-level detector; native mode uses Compose) ---------------

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (session.inPip || session.mode != Mode.WEB || session.webOverlay || session.sheet != null) {
            return super.dispatchTouchEvent(ev)
        }
        val wasActive = gestures.active
        if (gestures.onTouch(ev, root.width, root.height)) {
            if (!wasActive && gestures.active) {
                val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
            }
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun readLevel(kind: GestureKind): Float = when (kind) {
        GestureKind.BRIGHTNESS -> {
            val current = window.attributes.screenBrightness
            if (current >= 0f) current else {
                runCatching { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)
            }
        }
        GestureKind.VOLUME -> {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
        }
    }

    override fun setLevel(kind: GestureKind, value: Float) {
        gestureHideJob?.cancel()
        when (kind) {
            GestureKind.BRIGHTNESS -> window.attributes = window.attributes.apply { screenBrightness = value.coerceIn(0.01f, 1f) }
            GestureKind.VOLUME -> {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, (value * max).roundToInt(), 0) }
            }
        }
        session.gesture = GestureHint(kind, value)
    }

    override fun gestureEnd() {
        gestureHideJob?.cancel()
        gestureHideJob = lifecycleScope.launch {
            delay(700)
            session.gesture = null
        }
    }

    // ---- System UI and PiP --------------------------------------------------------------------------

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun matchParent() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    override fun enterPip() {
        if (!session.canPip || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching { enterPictureInPictureMode(buildPipParams()) }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(): PictureInPictureParams {
        val playing = native.exo.isPlaying
        val action = RemoteAction(
            Icon.createWithResource(this, if (playing) R.drawable.ic_pip_pause else R.drawable.ic_pip_play),
            getString(if (playing) R.string.pip_pause else R.string.pip_play),
            getString(if (playing) R.string.pip_pause else R.string.pip_play),
            PendingIntent.getBroadcast(this, 1, Intent(ACTION_PIP_TOGGLE).setPackage(packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
        )
        val aspect = native.state.aspect.takeIf { it in 0.42f..2.38f } ?: (16f / 9f)
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational((aspect * 1000).roundToInt(), 1000))
            .setActions(if (session.mode == Mode.NATIVE) listOf(action) else emptyList())
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setAutoEnterEnabled(playing && session.mode == Mode.NATIVE)
                    setSeamlessResizeEnabled(true)
                }
            }
            .build()
    }

    private fun updatePipParams() {
        if (!session.canPip || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching { setPictureInPictureParams(buildPipParams()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (native.exo.isPlaying && Build.VERSION.SDK_INT in Build.VERSION_CODES.O until Build.VERSION_CODES.S) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        session.inPip = isInPictureInPictureMode
        if (!isInPictureInPictureMode && lifecycle.currentState == Lifecycle.State.CREATED) finish()
    }

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_PIP_TOGGLE) {
                native.toggle()
                updatePipParams()
            }
        }
    }

    private fun registerPipReceiver() {
        if (!session.canPip) return
        ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(ACTION_PIP_TOGGLE), ContextCompat.RECEIVER_NOT_EXPORTED)
        pipReceiverRegistered = true
    }

}
