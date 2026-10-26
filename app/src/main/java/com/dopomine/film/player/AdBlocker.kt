package com.dopomine.film.player

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream

/**
 * Host based ad/tracker blocking for the player WebView.
 * The list (~19k domains) is loaded once in the background; lookups walk up the domain labels.
 */
class AdBlocker(context: Context, scope: CoroutineScope) {
    private val app = context.applicationContext
    private val hosts = CompletableDeferred<Set<String>>()

    init {
        scope.launch(Dispatchers.IO) {
            val set = runCatching {
                app.assets.open("adblock/hosts.txt").bufferedReader().useLines { lines ->
                    lines.map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .toHashSet()
                }
            }.getOrDefault(emptySet())
            hosts.complete(set)
        }
    }

    /** Blocks until the list is loaded (a few ms at most); only called from WebView's IO thread. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun hostSet(): Set<String> = if (hosts.isCompleted) hosts.getCompleted() else runBlocking { hosts.await() }

    fun isBlockedHost(host: String): Boolean {
        val set = hostSet()
        var h = host.lowercase().trimEnd('.')
        while (true) {
            if (h in set) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false
            h = h.substring(dot + 1)
        }
    }

    fun shouldBlock(url: Uri): Boolean {
        val host = url.host ?: return false
        if (isBlockedHost(host)) return true
        return AD_URL.containsMatchIn(url.toString().lowercase())
    }

    companion object {
        /** Typical VAST/VPAID/ad-server request patterns used by embedded video players. */
        private val AD_URL = Regex(
            "(^|[/._?&=-])(vast|vpaid|vmap|preroll|midroll|adfox|prebid|adserver|adsystem|adframe|banners?)([/._?&=-]|$)" +
                "|/ads?/|/advert|/getcode\\?|/rtb/|[?&](ad_?type|adunit|ad_?id)="
        )

        fun blockedResponse(): WebResourceResponse =
            WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }
}
