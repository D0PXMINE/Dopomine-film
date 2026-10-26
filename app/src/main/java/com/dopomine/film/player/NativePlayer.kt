package com.dopomine.film.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.dopomine.film.data.net.BROWSER_UA
import com.dopomine.film.player.resolve.Stream
import com.dopomine.film.player.resolve.StreamFormat
import okhttp3.OkHttpClient
import java.util.Locale

@Immutable
data class TrackOption(val key: String, val label: String, val detail: String?, val selected: Boolean)

/** Observable playback state for the Compose controls. */
@Stable
class NativeState {
    var isPlaying by mutableStateOf(false)
    var buffering by mutableStateOf(true)
    var ended by mutableStateOf(false)
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var bufferedMs by mutableLongStateOf(0L)
    var aspect by mutableFloatStateOf(16f / 9f)
    var speed by mutableFloatStateOf(1f)
    var audio by mutableStateOf<List<TrackOption>>(emptyList())
    var qualities by mutableStateOf<List<TrackOption>>(emptyList())
    var texts by mutableStateOf<List<TrackOption>>(emptyList())
    var error by mutableStateOf<String?>(null)
}

/**
 * ExoPlayer behind the native controls. Streams come with the headers their site expects
 * (Referer, Origin, cookies), which are sent with every playlist and segment request.
 */
@OptIn(UnstableApi::class)
class NativePlayer(context: Context, private val http: OkHttpClient) {
    val state = NativeState()

    val exo: ExoPlayer = ExoPlayer.Builder(context)
        .setLoadControl(
            DefaultLoadControl.Builder()
                // start fast, then keep a comfortable buffer for shaky mobile networks
                .setBufferDurationsMs(15_000, 60_000, 1_500, 3_000)
                .build()
        )
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .build()

    private var stream: Stream? = null
    var preferredVoice: String? = null
    var onEnded: (() -> Unit)? = null
    var onError: ((PlaybackException) -> Unit)? = null
    var onPlayingChanged: ((Boolean) -> Unit)? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            state.isPlaying = isPlaying
            onPlayingChanged?.invoke(isPlaying)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            state.buffering = playbackState == Player.STATE_BUFFERING
            state.ended = playbackState == Player.STATE_ENDED
            if (playbackState == Player.STATE_READY) state.durationMs = exo.duration.coerceAtLeast(0)
            if (playbackState == Player.STATE_ENDED) onEnded?.invoke()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                state.aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            }
        }

        override fun onTracksChanged(tracks: Tracks) = updateTracks(tracks)

        override fun onPlayerError(error: PlaybackException) {
            state.error = describe(error)
            onError?.invoke(error)
        }
    }

    init {
        exo.addListener(listener)
    }

    fun play(next: Stream, startMs: Long = 0L, autoPlay: Boolean = true) {
        stream = next
        state.error = null
        state.ended = false
        state.audio = emptyList()
        state.qualities = emptyList()
        state.texts = emptyList()
        val headers = next.headers.filterKeys { !it.equals("User-Agent", true) }
        val dataSource = OkHttpDataSource.Factory(http)
            .setUserAgent(next.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: BROWSER_UA)
            .setDefaultRequestProperties(headers)
        val item = MediaItem.Builder()
            .setUri(next.url)
            .apply {
                when (next.format) {
                    StreamFormat.HLS -> setMimeType(MimeTypes.APPLICATION_M3U8)
                    StreamFormat.DASH -> setMimeType(MimeTypes.APPLICATION_MPD)
                    StreamFormat.PROGRESSIVE -> Unit
                }
                if (next.subtitles.isNotEmpty()) {
                    setSubtitleConfigurations(next.subtitles.map { sub ->
                        MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(sub.url))
                            .setMimeType(if (sub.url.substringBefore('?').endsWith(".srt")) MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT)
                            .setLabel(sub.label)
                            .setLanguage(sub.language)
                            .build()
                    })
                }
            }
            .build()
        val source = DefaultMediaSourceFactory(dataSource).createMediaSource(item)
        exo.setMediaSource(source, startMs.coerceAtLeast(0))
        exo.prepare()
        exo.playWhenReady = autoPlay
    }

    fun toggle() {
        if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0)
        exo.playWhenReady = !exo.playWhenReady
    }

    fun seekBy(deltaMs: Long) = seekTo(exo.currentPosition + deltaMs)

    fun seekTo(ms: Long) {
        val d = exo.duration
        exo.seekTo(if (d > 0) ms.coerceIn(0, d) else ms.coerceAtLeast(0))
    }

    fun setSpeed(speed: Float) {
        exo.playbackParameters = PlaybackParameters(speed)
        state.speed = speed
    }

    /** Called a few times per second by the activity while the controls are visible. */
    fun tick() {
        state.positionMs = exo.currentPosition.coerceAtLeast(0)
        state.bufferedMs = exo.bufferedPosition.coerceAtLeast(0)
        if (exo.duration > 0) state.durationMs = exo.duration
    }

    fun selectAudio(key: String): String? {
        val label = state.audio.firstOrNull { it.key == key }?.label
        return select(C.TRACK_TYPE_AUDIO, key)?.let { label ?: it }?.also { preferredVoice = it }
    }

    fun selectQuality(key: String) {
        if (key == AUTO) {
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
        } else {
            select(C.TRACK_TYPE_VIDEO, key)
        }
    }

    fun selectText(key: String) {
        val params = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, key == OFF).build()
        exo.trackSelectionParameters = params
        if (key != OFF) select(C.TRACK_TYPE_TEXT, key)
    }

    /** Key format: "<groupIndex>:<trackIndex>"; returns the label of the chosen track. */
    private fun select(type: Int, key: String): String? {
        val (g, t) = key.split(':').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 } ?: return null
        val group = exo.currentTracks.groups.getOrNull(g) ?: return null
        if (group.type != type) return null
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, t))
            .build()
        return labelFor(type, group.getTrackFormat(t), audioIndex(g))
    }

    private fun audioIndex(groupIndex: Int): Int =
        exo.currentTracks.groups.take(groupIndex).count { it.type == C.TRACK_TYPE_AUDIO }

    private fun labelFor(type: Int, f: Format, ordinal: Int): String = when (type) {
        C.TRACK_TYPE_AUDIO -> (stream?.audioNames?.getOrNull(ordinal)?.takeIf { it.isNotBlank() }
            ?: f.label?.takeIf { it.isNotBlank() }
            ?: f.language?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale("ru")).replaceFirstChar(Char::uppercase) }
            ?: "Дорожка ${ordinal + 1}").let(::cleanName)
        C.TRACK_TYPE_VIDEO -> qualityName(f)
        else -> (f.label ?: f.language?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale("ru")) } ?: "Субтитры").let(::cleanName)
    }

    /** "01. Дубляж (RUS)" -> "Дубляж (RUS)" */
    private fun cleanName(name: String) = name.replace(Regex("""^\s*\d{1,3}[.)]\s*"""), "").trim()

    /** Wide films are 1920×800: name the quality by width, the way players usually do. */
    private fun qualityName(f: Format): String {
        val w = maxOf(f.width, f.height * 16 / 9)
        return when {
            w >= 3800 -> "2160p"
            w >= 2500 -> "1440p"
            w >= 1900 -> "1080p"
            w >= 1260 -> "720p"
            w >= 840 -> "540p"
            w >= 700 -> "480p"
            w > 0 -> "${f.height}p"
            else -> f.label ?: "Видео"
        }
    }

    private fun updateTracks(tracks: Tracks) {
        val audio = mutableListOf<TrackOption>()
        val video = mutableListOf<Pair<Int, TrackOption>>()
        val text = mutableListOf<TrackOption>()
        var audioOrdinal = 0
        tracks.groups.forEachIndexed { gi, group ->
            when (group.type) {
                C.TRACK_TYPE_AUDIO -> {
                    // HLS voice-overs are separate renditions: one group each
                    for (ti in 0 until group.length) {
                        if (!group.isTrackSupported(ti)) continue
                        val f = group.getTrackFormat(ti)
                        val label = labelFor(C.TRACK_TYPE_AUDIO, f, audioOrdinal)
                        audio += TrackOption("$gi:$ti", label, f.channelCount.takeIf { it > 2 }?.let { "$it.1" }, group.isTrackSelected(ti))
                    }
                    audioOrdinal++
                }
                C.TRACK_TYPE_VIDEO -> for (ti in 0 until group.length) {
                    if (!group.isTrackSupported(ti)) continue
                    val f = group.getTrackFormat(ti)
                    if (f.height <= 0) continue
                    video += f.height to TrackOption("$gi:$ti", qualityName(f), f.bitrate.takeIf { it > 0 }?.let { "%.1f Мбит/с".format(it / 1_000_000f) }, group.isTrackSelected(ti))
                }
                C.TRACK_TYPE_TEXT -> for (ti in 0 until group.length) {
                    if (!group.isTrackSupported(ti)) continue
                    text += TrackOption("$gi:$ti", labelFor(C.TRACK_TYPE_TEXT, group.getTrackFormat(ti), 0), null, group.isTrackSelected(ti))
                }
            }
        }
        val autoQuality = exo.trackSelectionParameters.overrides.keys.none { it.type == C.TRACK_TYPE_VIDEO }
        state.qualities = if (video.isEmpty()) emptyList() else
            listOf(TrackOption(AUTO, "Авто", null, autoQuality)) +
                video.distinctBy { it.first }.sortedByDescending { it.first }.map { (_, o) -> if (autoQuality) o.copy(selected = false) else o }
        // several renditions can share a name ("Оригинал. Комментарии" ×4): number the repeats
        val seen = HashMap<String, Int>()
        val named = audio.map { o ->
            val n = (seen[o.label] ?: 0) + 1
            seen[o.label] = n
            if (n > 1) o.copy(label = "${o.label} · $n") else o
        }
        state.audio = named
        val textOff = text.none { it.selected }
        state.texts = if (text.isEmpty()) emptyList() else listOf(TrackOption(OFF, "Выключены", null, textOff)) + text

        // keep the voice-over the viewer picked when the next episode starts
        val wanted = preferredVoice
        if (wanted != null && named.size > 1 && named.none { it.selected && it.label == wanted }) {
            named.firstOrNull { it.label == wanted }?.let { select(C.TRACK_TYPE_AUDIO, it.key) }
        }
    }

    private fun describe(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Источник отказал в доступе к видео"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Нет соединения с источником"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "Источник отдал видео в непонятном формате"
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "Устройство не может декодировать это видео"
        else -> "Ошибка воспроизведения: ${e.errorCodeName}"
    }

    fun release() {
        exo.removeListener(listener)
        exo.release()
    }

    companion object {
        const val AUTO = "auto"
        const val OFF = "off"
    }
}
