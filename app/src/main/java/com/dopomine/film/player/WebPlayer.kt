package com.dopomine.film.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
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
import androidx.core.graphics.createBitmap
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.dopomine.film.player.resolve.Stream
import com.dopomine.film.player.resolve.StreamFormat
import org.json.JSONObject

/**
 * The player site in a WebView. Two jobs:
 *  - sniffing: the page runs (hidden behind the loading screen, or shown so the viewer can press
 *    play) and we catch the stream address its player requests (HLS/DASH/MP4), with the headers
 *    it used, then ExoPlayer plays it;
 *  - fallback: if no stream can be caught, the page itself is the player, reduced to the bare
 *    player, with ads, popups and redirects blocked.
 */
class WebPlayer(
    private val context: Context,
    private val adBlocker: AdBlocker,
    private val listener: Listener,
) {
    interface Listener {
        fun onStreamFound(stream: Stream)
        fun onSources(items: List<String>, selected: Int)
        fun onPlayingChanged(playing: Boolean)
        /** Position of the page's video, about once a second while it plays. */
        fun onVideoTime(seconds: Double, duration: Double)
        fun onPageError(message: String)
        fun onShowFullscreen(view: View, callback: WebChromeClient.CustomViewCallback)
        fun onHideFullscreen()
        fun onRendererGone()
        fun onBlocked(url: String)
        fun onLog(line: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private var stateHandler: ScriptHandler? = null
    private var sourcesProxy: JavaScriptReplyProxy? = null
    private var videoProxy: JavaScriptReplyProxy? = null
    /** Every frame that has spoken: commands go to all of them (the player is often a nested iframe). */
    private val frames = LinkedHashSet<JavaScriptReplyProxy>()
    private var playerHost: String = ""
    private var pickerHost: String = ""

    @Volatile var adBlock = true
    /** Only HLS/DASH manifests count as a catch (used when ads are not blocked: ad videos are MP4). */
    @Volatile var manifestsOnly = false
    @Volatile var sniffing = false
        private set
    private var visible = false
    private var silent = false
    private val reported: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
    private val rejected: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    @SuppressLint("SetJavaScriptEnabled", "RestrictedApi")
    val view: WebView = WebView(context).apply {
        setBackgroundColor(Color.BLACK)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        with(settings) {
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
            userAgentString = userAgentString.replace("; wv", "").replace(Regex("\\s*Version/\\d+(\\.\\d+)*"), "")
        }
        presentAsChrome(settings)
        // Streaming mirrors often sit on Safe Browsing lists; the interstitial would replace the player.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(settings, false)
        }
        // Do not announce the app in X-Requested-With: some players refuse embedded browsers.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
        }
        val webView = this
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        setDownloadListener { _, _, _, _, _ -> }
        webViewClient = Client()
        webChromeClient = Chrome()
    }

    private val bridge = WebViewCompat.WebMessageListener { _: WebView, message: WebMessageCompat, _: Uri, _: Boolean, reply: JavaScriptReplyProxy ->
        val json = message.data?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@WebMessageListener
        remember(reply)
        when (json.optString("type")) {
            "sources" -> {
                val items = json.optJSONArray("items") ?: return@WebMessageListener
                val list = (0 until items.length()).map { items.optString(it) }.filter { it.isNotBlank() }
                if (list.isNotEmpty()) {
                    sourcesProxy = reply
                    listener.onSources(list, json.optInt("selected", 0))
                    post(reply, """{"cmd":"nativeMenu"}""")
                }
            }
            "media" -> {
                videoProxy = reply
                val url = json.optString("url")
                val referer = json.optString("referer")
                // a manifest recognised by its content may have no telling extension
                val format = when (json.optString("kind")) {
                    "hls" -> StreamFormat.HLS
                    "dash" -> StreamFormat.DASH
                    else -> null
                }
                if (url.startsWith("http")) {
                    val headers = buildMap {
                        if (referer.startsWith("http")) {
                            put("Referer", referer)
                            // a script's request to another site carries the page's origin
                            val from = Uri.parse(referer)
                            if (format != null && from.authority != Uri.parse(url).authority) put("Origin", "${from.scheme}://${from.authority}")
                        }
                    }
                    caught(url, headers, format)
                }
            }
            "state", "video", "progress" -> {
                videoProxy = reply
                if (json.optString("type") == "state") listener.onPlayingChanged(json.optBoolean("playing"))
            }
            "time" -> {
                videoProxy = reply
                listener.onVideoTime(json.optDouble("t"), json.optDouble("d"))
            }
        }
    }

    init {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            runCatching { WebViewCompat.addWebMessageListener(view, "dpmBridge", setOf("*"), bridge) }
        }
    }

    private var scriptsInstalledFor: String? = null

    private fun installScripts(mirror: String) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        if (scriptsInstalledFor != null) return
        scriptsInstalledFor = mirror
        val host = Uri.parse(mirror).host.orEmpty()
        val rules = buildSet {
            add(mirror.trimEnd('/'))
            registrableDomain(host)?.let { add("https://*.$it"); add("https://$it") }
        }
        val top = asset("player/top.js")
        // a hand-typed mirror address may not form a valid origin rule; top.js checks it runs in the top frame
        runCatching { WebViewCompat.addDocumentStartJavaScript(view, top, rules) }
            .onFailure { runCatching { WebViewCompat.addDocumentStartJavaScript(view, top, setOf("*")) } }
        runCatching { WebViewCompat.addDocumentStartJavaScript(view, asset("player/frames.js"), setOf("*")) }
    }

    /**
     * Sniff mode: the page is run to catch its stream. [shown]: the viewer sees it (no forced
     * silence, no automatic clicks). The flags reach frames already open by message and frames
     * opened later at document start.
     */
    fun setSniffing(value: Boolean, shown: Boolean = false) {
        if (value && !sniffing) reported.clear()
        sniffing = value
        visible = shown
        applyState()
    }

    /** A caught address that turned out to be an ad: never offered again for this page. */
    fun reject(url: String) {
        rejected += url.substringBefore('?')
    }

    /** Everything caught on this page so far belongs to an ad. */
    fun rejectCaught() {
        synchronized(reported) { rejected += reported }
    }

    private fun applyState() {
        broadcast("""{"cmd":"mode","sniff":$sniffing,"visible":$visible}""")
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        stateHandler?.remove()
        stateHandler = runCatching {
            WebViewCompat.addDocumentStartJavaScript(
                view, "window.__dpmSniff = $sniffing; window.__dpmVisible = $visible; window.__dpmSilent = $silent;", setOf("*"),
            )
        }.getOrNull()
    }

    /** The page from the userscript: `<mirror>/<id>`. */
    fun loadSite(mirror: String, kp: Int) {
        playerHost = Uri.parse(mirror).host.orEmpty()
        installScripts(mirror)
        forgetFrames()
        listener.onLog("Страница плеера: $mirror/$kp")
        view.loadUrl("$mirror/$kp")
    }

    /**
     * One player's iframe on its own, embedded the way the picker embeds it (same parent origin),
     * so that players bound to the site accept it.
     */
    fun loadEmbed(mirror: String, picker: String, iframeUrl: String) {
        playerHost = Uri.parse(mirror).host.orEmpty()
        pickerHost = picker
        installScripts(mirror)
        forgetFrames()
        listener.onLog("Плеер напрямую: $iframeUrl")
        val safe = iframeUrl.replace("&", "&amp;").replace("\"", "&quot;")
        val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            |<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}iframe{position:fixed;inset:0;width:100%;height:100%;border:0}</style>
            |</head><body><iframe src="$safe" allow="autoplay; fullscreen; encrypted-media" allowfullscreen referrerpolicy="origin"></iframe></body></html>""".trimMargin()
        view.loadDataWithBaseURL("https://$picker/", html, "text/html", "utf-8", null)
    }

    fun selectSource(index: Int) {
        post(sourcesProxy, """{"cmd":"source","index":$index}""")
    }

    /** Silences the page once its stream has been taken over by the native player. */
    fun muteAll() {
        silent = true
        applyState()
        broadcast("""{"cmd":"mute"}""")
        view.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){v.muted=true;v.pause()})", null)
    }

    /** The page is the player again (its stream could not be played natively): sound back on. */
    fun release() {
        silent = false
        applyState()
        broadcast("""{"cmd":"release"}""")
    }

    /** Resumes the page's video (an ad was caught instead of the film: the page must go on to the film). */
    fun resumePage() = broadcast("""{"cmd":"play"}""")

    fun pausePage() = broadcast("""{"cmd":"pause"}""")

    fun onResume() = view.onResume()
    fun onPause() = view.onPause()

    fun destroy() {
        stateHandler?.remove()
        view.stopLoading()
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        view.removeAllViews()
        view.destroy()
    }

    /** Seeks the video playing in the page (the site's own player). */
    fun seekVideo(seconds: Double) = post(videoProxy, """{"cmd":"seek","t":$seconds}""")

    private fun post(proxy: JavaScriptReplyProxy?, json: String) {
        if (proxy == null || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        runCatching { proxy.postMessage(json) }
    }

    private fun broadcast(json: String) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        frames.toList().forEach { proxy -> runCatching { proxy.postMessage(json) }.onFailure { frames.remove(proxy) } }
    }

    private fun remember(proxy: JavaScriptReplyProxy) {
        if (frames.add(proxy) && frames.size > MAX_FRAMES) frames.remove(frames.first())
    }

    /** A new page: no frames, nothing caught or rejected yet, sound allowed. */
    private fun forgetFrames() {
        frames.clear()
        sourcesProxy = null
        videoProxy = null
        rejected.clear()
        silent = false
        applyState()
    }

    /**
     * Client hints of a WebView name it ("Android WebView"), and some players' bot checks turn such
     * visitors away; the page is told it runs in Chrome of the same version instead.
     */
    private fun presentAsChrome(settings: WebSettings) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
        runCatching {
            val full = Regex("""Chrome/([\d.]+)""").find(settings.userAgentString)?.groupValues?.get(1) ?: return
            val major = full.substringBefore('.')
            fun brand(name: String, version: String, fullVersion: String) =
                UserAgentMetadata.BrandVersion.Builder().setBrand(name).setMajorVersion(version).setFullVersion(fullVersion).build()
            val current = WebSettingsCompat.getUserAgentMetadata(settings)
            WebSettingsCompat.setUserAgentMetadata(
                settings,
                UserAgentMetadata.Builder(current)
                    .setBrandVersionList(listOf(brand("Chromium", major, full), brand("Google Chrome", major, full), brand("Not)A;Brand", "99", "99.0.0.0")))
                    .setFullVersion(full)
                    .build(),
            )
        }
    }

    private fun asset(path: String): String = context.assets.open(path).bufferedReader().use { it.readText() }

    private fun registrableDomain(host: String): String? {
        val labels = host.split('.').filter { it.isNotEmpty() }
        return if (labels.size >= 2) labels.takeLast(2).joinToString(".") else null
    }

    private fun isPlayerSite(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        if (host == playerHost || host == pickerHost) return true
        val domain = registrableDomain(playerHost) ?: return false
        return host == domain || host.endsWith(".$domain")
    }

    private fun caught(url: String, headers: Map<String, String>, known: StreamFormat? = null) {
        val key = url.substringBefore('?')
        if (!sniffing || key in rejected || looksLikeAd(url) || !reported.add(key)) return
        val format = known ?: Stream.formatOf(url)
        if (manifestsOnly && format == StreamFormat.PROGRESSIVE) return
        val cookies = CookieManager.getInstance().getCookie(url)
        val all = buildMap {
            headers.forEach { (k, v) ->
                // headers ExoPlayer manages itself, or that only make sense inside the page
                if (!k.equals("Range", true) && !k.equals("Accept-Encoding", true) && !k.equals("Upgrade-Insecure-Requests", true)) put(k, v)
            }
            if (!cookies.isNullOrBlank() && keys.none { it.equals("Cookie", true) }) put("Cookie", cookies)
            if (keys.none { it.equals("User-Agent", true) }) put("User-Agent", view.settings.userAgentString)
        }
        main.post {
            listener.onLog("Найден поток: ${url.take(120)}")
            listener.onStreamFound(Stream(url, format, all))
        }
    }

    /** Pre-roll and banner videos that slipped past the block list. */
    private fun looksLikeAd(url: String): Boolean {
        val uri = Uri.parse(url)
        if (adBlocker.shouldBlock(uri)) return true
        return AD_PATH.containsMatchIn(uri.path.orEmpty())
    }

    private fun looksLikeStream(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".mpd") || path.contains(".m3u8/") ||
            (!manifestsOnly && path.endsWith(".mp4") && !path.contains("preview") && !path.contains("trailer"))
    }

    private companion object {
        const val MAX_FRAMES = 24
        val AD_PATH = Regex("""(^|[/._-])(ads?|adv|vast|vpaid|preroll|midroll|reklama|creatives?)([/._-]|$)""", RegexOption.IGNORE_CASE)
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url
            if (request.isForMainFrame) return null
            if (adBlock && !isPlayerSite(url.host) && adBlocker.shouldBlock(url)) {
                val text = url.toString()
                main.post { listener.onBlocked(text) }
                return AdBlocker.blockedResponse()
            }
            if (sniffing) {
                val text = url.toString()
                if (looksLikeStream(text)) caught(text, request.requestHeaders.orEmpty())
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

        override fun onPageFinished(view: WebView, url: String?) {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                view.evaluateJavascript(asset("player/top.js"), null)
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                listener.onPageError("Не удалось открыть страницу плеера (${error.description}). Проверьте интернет или адрес зеркала в настройках.")
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            listener.onRendererGone()
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onShowCustomView(view: View, callback: CustomViewCallback) = listener.onShowFullscreen(view, callback)
        override fun onHideCustomView() = listener.onHideFullscreen()
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?) = false
        override fun getDefaultVideoPoster(): Bitmap = createBitmap(1, 1)
        override fun onPermissionRequest(request: PermissionRequest) = request.deny()
        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) =
            callback.invoke(origin, false, false)

        // Ad scripts like to throw alert()/confirm() dialogs; they are silently dismissed.
        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean { result.cancel(); return true }
        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean { result.cancel(); return true }
        override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
            result.cancel(); return true
        }
    }
}
