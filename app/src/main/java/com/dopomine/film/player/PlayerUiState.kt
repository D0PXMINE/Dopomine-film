package com.dopomine.film.player

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

@Immutable
data class SourceItem(val title: String, val subtitle: String?)

@Immutable
data class GestureIndicator(val kind: GestureKind, val value: Float)

/** Observable state of the native player chrome drawn over the WebView. */
@Stable
class PlayerUiState {
    var title by mutableStateOf("")
    var subtitle by mutableStateOf<String?>(null)
    var loading by mutableStateOf(true)
    var slowLoading by mutableStateOf(false)
    var progress by mutableIntStateOf(0)
    var error by mutableStateOf<String?>(null)
    var controlsVisible by mutableStateOf(true)
    var inPip by mutableStateOf(false)
    var fullscreen by mutableStateOf(false)
    var canPip by mutableStateOf(false)
    var sources by mutableStateOf<List<SourceItem>>(emptyList())
    var selectedSource by mutableIntStateOf(-1)
    var sourcesSheet by mutableStateOf(false)
    var gesture by mutableStateOf<GestureIndicator?>(null)
    var resumedAt by mutableStateOf<Int?>(null)
    var blockedCount by mutableIntStateOf(0)

    fun setSources(raw: List<String>, selected: Int) {
        sources = raw.map { line ->
            val parts = line.split(" · ").map { it.trim() }.filter { it.isNotEmpty() && it != "-" }
            SourceItem(parts.firstOrNull() ?: line, parts.drop(1).joinToString(" · ").ifBlank { null })
        }
        selectedSource = selected
    }
}
