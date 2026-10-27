package com.dopomine.film.player

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dopomine.film.player.resolve.Playable
import com.dopomine.film.player.resolve.SourceInfo

enum class Mode { RESOLVING, NATIVE, WEB, ERROR }

enum class Sheet { SOURCES, EPISODES, AUDIO, QUALITY, SUBTITLES, SPEED, LOG }

/** One way to play the title, as listed in the "Источник" sheet. */
@Immutable
data class SourceChoice(
    val key: String,
    val title: String,
    val subtitle: String,
    val info: SourceInfo?,
) {
    companion object {
        const val COLLAPS = "collaps"
        const val VEOVEO = "veoveo"
        const val ANILIBERTY = "aniliberty"
        const val ANIMEVOST = "animevost"
        const val SITE = "site"
        const val SITE_PLAYER = "site_player"
    }
}

@Immutable
data class GestureHint(val kind: GestureKind, val value: Float)

/** Everything the player screen shows, besides the playback state itself. */
@Stable
class PlayerSession {
    var title by mutableStateOf("")
    var subtitle by mutableStateOf<String?>(null)
    var mode by mutableStateOf(Mode.RESOLVING)
    var status by mutableStateOf("Ищем видео…")
    var error by mutableStateOf<String?>(null)
    val log = mutableStateListOf<String>()

    var choices by mutableStateOf<List<SourceChoice>>(emptyList())
    var currentChoice by mutableStateOf<String?>(null)
    var playable by mutableStateOf<Playable?>(null)
    var season by mutableIntStateOf(0)
    var episode by mutableIntStateOf(0)

    var controlsVisible by mutableStateOf(true)
    var sheet by mutableStateOf<Sheet?>(null)
    var resumedAt by mutableStateOf<Int?>(null)
    var gesture by mutableStateOf<GestureHint?>(null)
    var seekHint by mutableStateOf<String?>(null)
    var inPip by mutableStateOf(false)
    var canPip by mutableStateOf(false)
    var webFullscreen by mutableStateOf(false)
    var webOverlay by mutableStateOf(false)
    var blocked by mutableIntStateOf(0)
    var resize by mutableIntStateOf(0)

    val isSeries: Boolean get() = playable is Playable.Series

    fun log(line: String) {
        log += line
        if (log.size > 200) log.removeAt(0)
    }
}
