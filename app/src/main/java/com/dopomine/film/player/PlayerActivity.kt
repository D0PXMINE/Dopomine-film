package com.dopomine.film.player

import android.annotation.SuppressLint
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
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Rational
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.graphics.createBitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.dopomine.film.R
import com.dopomine.film.appContainer
import com.dopomine.film.data.model.ContentType
import com.dopomine.film.data.model.Movie
import com.dopomine.film.data.settings.AppSettings
import com.dopomine.film.ui.theme.DopomineTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Plays a title through the player site from the userscript, inside a WebView:
 * the page is reduced to the bare player, ads and popups are blocked, and native
 * controls (source picker, gestures, PiP, resume) are drawn on top.
 */
class PlayerActivity : ComponentActivity(), PlayerActions {

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_ORIGINAL = "original"
        private const val EXTRA_YEAR = "year"
        private const val EXTRA_POSTER = "poster"
        private const val EXTRA_POSTER_LARGE = "poster_large"
        private const val EXTRA_COVER = "cover"
        private const val EXTRA_KP = "kp"
        private const val EXTRA_IMDB = "imdb"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_GENRES = "genres"

        private const val ACTION_PIP_TOGGLE = "com.dopomine.film.PIP_TOGGLE"

        fun intent(context: Context, movie: Movie): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_ID, movie.id)
                .putExtra(EXTRA_TITLE, movie.title)
                .putExtra(EXTRA_ORIGINAL, movie.originalTitle)
                .putExtra(EXTRA_YEAR, movie.year ?: 0)
                .putExtra(EXTRA_POSTER, movie.posterUrl)
                .putExtra(EXTRA_POSTER_LARGE, movie.posterLargeUrl)
                .putExtra(EXTRA_COVER, movie.coverUrl)
                .putExtra(EXTRA_KP, movie.ratingKp ?: 0.0)
                .putExtra(EXTRA_IMDB, movie.ratingImdb ?: 0.0)
                .putExtra(EXTRA_TYPE, movie.type.name)
                .putStringArrayListExtra(EXTRA_GENRES, ArrayList(movie.genres))

        private fun Intent.toMovie(): Movie? {
            val id = getIntExtra(EXTRA_ID, 0).takeIf { it > 0 } ?: return null
            return Movie(
                id = id,
                title = getStringExtra(EXTRA_TITLE) ?: "Кинопоиск #$id",
                originalTitle = getStringExtra(EXTRA_ORIGINAL),
                year = getIntExtra(EXTRA_YEAR, 0).takeIf { it > 0 },
                posterUrl = getStringExtra(EXTRA_POSTER),
                posterLargeUrl = getStringExtra(EXTRA_POSTER_LARGE),
                coverUrl = getStringExtra(EXTRA_COVER),
                ratingKp = getDoubleExtra(EXTRA_KP, 0.0).takeIf { it > 0 },
                ratingImdb = getDoubleExtra(EXTRA_IMDB, 0.0).takeIf { it > 0 },
                type = runCatching { ContentType.valueOf(getStringExtra(EXTRA_TYPE) ?: "") }.getOrDefault(ContentType.FILM),
                genres = getStringArrayListExtra(EXTRA_GENRES) ?: emptyList(),
            )
        }
    }

    private val container by lazy { appContainer }
    private val ui = PlayerUiState()

    private lateinit var root: FrameLayout
    private lateinit var fullscreenContainer: FrameLayout
    private var webView: WebView? = null

    private lateinit var movie: Movie
    private lateinit var settings: AppSettings
    private lateinit var gestures: PlayerGestures
    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private var videoProxy: JavaScriptReplyProxy? = null
    private var sourcesProxy: JavaScriptReplyProxy? = null
    private var isPlaying = false
    private val resumeChecked = HashSet<Int>()
    private var loadingJob: Job? = null
    private var gestureHideJob: Job? = null
    private var pipReceiverRegistered = false

    private val playerOrigin: String get() = settings.playerHost.trimEnd('/')
    private val playerHostName: String get() = playerOrigin.toUri().host.orEmpty()

    // ---- Lifecycle -----------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val parsed = intent.toMovie()
        if (parsed == null) {
            finish()
            return
        }
        movie = parsed
        settings = container.settings.state.value ?: AppSettings()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        requestedOrientation = if (settings.landscapePlayer) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        ui.canPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        applyMovieToUi()

        gestures = PlayerGestures(
            context = this,
            read = ::readGestureValue,
            apply = ::applyGestureValue,
            onEnd = ::onGestureEnd,
            onReveal = { ui.controlsVisible = true },
        ).apply { enabled = settings.gestures }

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        fullscreenContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        val overlay = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { DopomineTheme { PlayerOverlay(ui, this@PlayerActivity) } }
        }
        val webView = createWebView()
        this.webView = webView
        root.addView(webView, matchParent())
        root.addView(fullscreenContainer, matchParent())
        root.addView(overlay, matchParent())
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    ui.sourcesSheet -> ui.sourcesSheet = false
                    customView != null -> {
                        customViewCallback?.onCustomViewHidden()
                        exitFullscreen()
                    }
                    else -> finish()
                }
            }
        })

        registerPipReceiver()
        startPlayback()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val next = intent.toMovie() ?: return
        if (next.id == movie.id) return
        movie = next
        applyMovieToUi()
        startPlayback()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        hideSystemBars()
    }

    override fun onStop() {
        super.onStop()
        if (!isInPipModeCompat()) {
            sendToVideo("""{"cmd":"pause"}""")
            webView?.onPause()
        }
    }

    override fun onDestroy() {
        if (pipReceiverRegistered) {
            runCatching { unregisterReceiver(pipReceiver) }
            pipReceiverRegistered = false
        }
        webView?.let { view ->
            view.stopLoading()
            (view.parent as? ViewGroup)?.removeView(view)
            view.removeAllViews()
            view.destroy()
        }
        webView = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // ---- Playback ------------------------------------------------------------------------------

    private fun applyMovieToUi() {
        ui.title = movie.title
        ui.subtitle = listOfNotNull(
            if (movie.type == ContentType.SERIES) "Сериал" else null,
            movie.year?.toString(),
        ).joinToString(" · ").ifBlank { null }
    }

    private fun startPlayback() {
        videoProxy = null
        sourcesProxy = null
        isPlaying = false
        resumeChecked.clear()
        ui.sources = emptyList()
        ui.selectedSource = -1
        ui.error = null
        ui.loading = true
        ui.slowLoading = false
        ui.resumedAt = null
        ui.blockedCount = 0
        lifecycleScope.launch { container.library.markOpened(movie) }
        webView?.loadUrl("$playerOrigin/${movie.id}")
        loadingJob?.cancel()
        loadingJob = lifecycleScope.launch {
            delay(15_000)
            if (ui.loading) ui.slowLoading = true
        }
    }

    private fun finishLoading(afterMs: Long = 0) {
        lifecycleScope.launch {
            if (afterMs > 0) delay(afterMs)
            if (ui.loading) {
                ui.loading = false
                ui.controlsVisible = true
            }
        }
    }

    private fun sendToVideo(json: String) {
        post(videoProxy, json)
    }

    /** Proxies only exist when WEB_MESSAGE_LISTENER is supported, but the check keeps lint honest. */
    private fun post(proxy: JavaScriptReplyProxy?, json: String) {
        if (proxy == null || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        runCatching { proxy.postMessage(json) }
    }

    // ---- WebView setup -------------------------------------------------------------------------

    // RestrictedApi: the X-Requested-With allow-list feature constant is still library-restricted in androidx.webkit;
    // it is guarded by isFeatureSupported, so older WebViews simply skip it.
    @SuppressLint("SetJavaScriptEnabled", "RestrictedApi")
    private fun createWebView(): WebView {
        val view = WebView(this)
        view.setBackgroundColor(Color.BLACK)
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = false
        view.overScrollMode = View.OVER_SCROLL_NEVER
        with(view.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportMultipleWindows(true) // popups go to onCreateWindow, which refuses them
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = userAgentString
                .replace("; wv", "")
                .replace(Regex("\\s*Version/\\d+(\\.\\d+)*"), "")
        }
        // Mirrors of streaming sites are often on Safe Browsing lists; its interstitial would replace the player.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(view.settings, false)
        }
        // Do not announce the app package in X-Requested-With: some players refuse embedded browsers.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(view.settings, emptySet())
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
        view.setDownloadListener { _, _, _, _, _ -> }
        view.webViewClient = PlayerClient()
        view.webChromeClient = ChromeClient()
        installScripts(view)
        return view
    }

    private fun installScripts(view: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            runCatching { WebViewCompat.addWebMessageListener(view, "dpmBridge", setOf("*"), bridgeListener) }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val host = playerHostName
            val rules = buildSet {
                add(playerOrigin)
                registrableDomain(host)?.let { add("https://*.$it"); add("https://$it") }
            }
            val top = asset("player/top.js")
            // a hand-typed mirror address may not form a valid origin rule; top.js checks it runs in the top frame
            runCatching { WebViewCompat.addDocumentStartJavaScript(view, top, rules) }
                .onFailure { runCatching { WebViewCompat.addDocumentStartJavaScript(view, top, setOf("*")) } }
            runCatching { WebViewCompat.addDocumentStartJavaScript(view, asset("player/frames.js"), setOf("*")) }
        }
    }

    private fun asset(path: String): String = assets.open(path).bufferedReader().use { it.readText() }

    private fun registrableDomain(host: String): String? {
        val labels = host.split('.').filter { it.isNotEmpty() }
        return if (labels.size >= 2) labels.takeLast(2).joinToString(".") else null
    }

    private fun isPlayerSite(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        if (host == playerHostName) return true
        val domain = registrableDomain(playerHostName) ?: return false
        return host == domain || host.endsWith(".$domain")
    }

    private val bridgeListener = WebViewCompat.WebMessageListener { _: WebView, message: WebMessageCompat, _: Uri, _: Boolean, reply: JavaScriptReplyProxy ->
        val json = message.data?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@WebMessageListener
        when (json.optString("type")) {
            "sources" -> {
                val items = json.optJSONArray("items") ?: return@WebMessageListener
                val list = (0 until items.length()).map { items.optString(it) }.filter { it.isNotBlank() }
                if (list.isEmpty()) return@WebMessageListener
                sourcesProxy = reply
                ui.setSources(list, json.optInt("selected", 0))
                // the picker inside the page is replaced by the native one
                post(reply, """{"cmd":"nativeMenu"}""")
                finishLoading(afterMs = 600)
            }
            "video" -> {
                videoProxy = reply
                onVideoStarted(json.optDouble("t", 0.0), json.optDouble("d", 0.0))
            }
            "progress" -> {
                videoProxy = reply
                val t = json.optDouble("t", 0.0)
                val d = json.optDouble("d", 0.0)
                if (d > 0) lifecycleScope.launch { container.library.saveProgress(movie.id, t, d) }
            }
            "state" -> {
                videoProxy = reply
                isPlaying = json.optBoolean("playing", false)
                if (isPlaying && ui.loading) finishLoading()
                updatePipParams()
            }
        }
    }

    private fun onVideoStarted(position: Double, duration: Double) {
        if (ui.loading) finishLoading()
        if (!settings.autoResume || duration <= 0) return
        if (!resumeChecked.add(duration.roundToInt())) return
        val proxy = videoProxy ?: return
        lifecycleScope.launch {
            val saved = container.library.findProgress(movie.id, duration) ?: return@launch
            val target = saved.positionSec
            if (target > 30 && target < duration - 90 && position < 15) {
                post(proxy, """{"cmd":"seek","t":$target}""")
                ui.resumedAt = target
            }
        }
    }

    private inner class PlayerClient : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (!settings.adBlock || request.isForMainFrame) return null
            val url = request.url
            if (isPlayerSite(url.host)) return null
            if (container.adBlocker.shouldBlock(url)) {
                runOnUiThread { ui.blockedCount++ }
                return AdBlocker.blockedResponse()
            }
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            val scheme = url.scheme?.lowercase()
            // intent://, market://, tg:// and other app links opened by ads
            if (scheme != "http" && scheme != "https") return true
            if (!request.isForMainFrame) return false
            if (isPlayerSite(url.host)) return false
            // a server redirect to a new mirror is fine; anything else leaving the player is an ad
            return !request.isRedirect
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            ui.error = null
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                view.evaluateJavascript(asset("player/top.js"), null)
            }
            // the source picker usually reports in; this is the fallback for pages without it
            finishLoading(afterMs = 4_000)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            ui.loading = false
            ui.error = "Не удалось открыть плеер. Проверьте интернет или адрес зеркала в настройках."
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // Chromium renderer crashed or was killed: rebuild the WebView instead of crashing the app.
            root.removeView(view)
            view.destroy()
            val fresh = createWebView()
            webView = fresh
            root.addView(fresh, 0, matchParent())
            startPlayback()
            return true
        }
    }

    private inner class ChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            ui.progress = newProgress
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customViewCallback = callback
            fullscreenContainer.addView(view, matchParent())
            fullscreenContainer.visibility = View.VISIBLE
            ui.fullscreen = true
            hideSystemBars()
            if (!settings.landscapePlayer) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        override fun onHideCustomView() {
            exitFullscreen()
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean = false

        override fun getDefaultVideoPoster(): Bitmap = createBitmap(1, 1)

        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
            callback.invoke(origin, false, false)
        }

        // Ad scripts like to throw alert()/confirm() dialogs; they are silently dismissed.
        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            result.cancel()
            return true
        }

        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            result.cancel()
            return true
        }

        override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
            result.cancel()
            return true
        }
    }

    private fun exitFullscreen() {
        val view = customView ?: return
        fullscreenContainer.removeView(view)
        fullscreenContainer.visibility = View.GONE
        customView = null
        customViewCallback = null
        ui.fullscreen = false
        hideSystemBars()
        if (!settings.landscapePlayer) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    // ---- Controls (PlayerActions) --------------------------------------------------------------

    override fun close() = finish()

    override fun reload() {
        exitFullscreen()
        startPlayback()
    }

    override fun rotate() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    override fun openSources() {
        ui.sourcesSheet = true
    }

    override fun selectSource(index: Int) {
        val proxy = sourcesProxy ?: return
        ui.selectedSource = index
        videoProxy = null
        resumeChecked.clear()
        post(proxy, """{"cmd":"source","index":$index}""")
    }

    override fun restartFromBeginning() {
        sendToVideo("""{"cmd":"seek","t":0}""")
    }

    // ---- Gestures ------------------------------------------------------------------------------

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ui.inPip) return super.dispatchTouchEvent(ev)
        val wasActive = gestures.active
        val consumed = gestures.onTouch(ev, root.width, root.height)
        if (consumed) {
            if (!wasActive && gestures.active) {
                // the player already saw ACTION_DOWN: cancel its gesture now that we own it
                val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
            }
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun readGestureValue(kind: GestureKind): Float = when (kind) {
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

    private fun applyGestureValue(kind: GestureKind, value: Float) {
        gestureHideJob?.cancel()
        when (kind) {
            GestureKind.BRIGHTNESS -> {
                window.attributes = window.attributes.apply { screenBrightness = value.coerceIn(0.01f, 1f) }
            }
            GestureKind.VOLUME -> {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, (value * max).roundToInt(), 0) }
            }
        }
        ui.gesture = GestureIndicator(kind, value)
    }

    private fun onGestureEnd() {
        gestureHideJob?.cancel()
        gestureHideJob = lifecycleScope.launch {
            delay(700)
            ui.gesture = null
        }
    }

    // ---- System UI -----------------------------------------------------------------------------

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun matchParent() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    // ---- Picture-in-picture --------------------------------------------------------------------

    private fun isInPipModeCompat(): Boolean = isInPictureInPictureMode

    override fun enterPip() {
        if (!ui.canPip || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching { enterPictureInPictureMode(buildPipParams()) }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(): PictureInPictureParams {
        val playing = isPlaying
        val action = RemoteAction(
            Icon.createWithResource(this, if (playing) R.drawable.ic_pip_pause else R.drawable.ic_pip_play),
            getString(if (playing) R.string.pip_pause else R.string.pip_play),
            getString(if (playing) R.string.pip_pause else R.string.pip_play),
            PendingIntent.getBroadcast(
                this,
                1,
                Intent(ACTION_PIP_TOGGLE).setPackage(packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(listOf(action))
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setAutoEnterEnabled(playing)
                    setSeamlessResizeEnabled(true)
                }
            }
            .build()
    }

    private fun updatePipParams() {
        if (!ui.canPip || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching { setPictureInPictureParams(buildPipParams()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters PiP automatically via setAutoEnterEnabled
        if (isPlaying && Build.VERSION.SDK_INT in Build.VERSION_CODES.O until Build.VERSION_CODES.S) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        ui.inPip = isInPictureInPictureMode
        if (!isInPictureInPictureMode && lifecycle.currentState == Lifecycle.State.CREATED) {
            // the PiP window was closed by the user
            finish()
        }
    }

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_PIP_TOGGLE) return
            sendToVideo(if (isPlaying) """{"cmd":"pause"}""" else """{"cmd":"play"}""")
        }
    }

    private fun registerPipReceiver() {
        if (!ui.canPip) return
        ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(ACTION_PIP_TOGGLE), ContextCompat.RECEIVER_NOT_EXPORTED)
        pipReceiverRegistered = true
    }
}
