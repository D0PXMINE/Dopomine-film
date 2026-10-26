package com.dopomine.film.ui.common

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri
import com.dopomine.film.data.model.Movie
import com.dopomine.film.player.PlayerActivity

object Launchers {
    fun openPlayer(context: Context, movie: Movie) {
        context.startActivity(PlayerActivity.intent(context, movie))
    }

    fun openUrl(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "Нет приложения для открытия ссылки", Toast.LENGTH_SHORT).show()
        }
    }

    fun share(context: Context, movie: Movie) {
        val text = buildString {
            append(movie.title)
            movie.year?.let { append(" ($it)") }
            append("\nhttps://www.kinopoisk.ru/film/${movie.id}/")
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, "Поделиться").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun copyId(context: Context, id: Int) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Kinopoisk ID", id.toString()))
        Toast.makeText(context, "ID $id скопирован", Toast.LENGTH_SHORT).show()
    }
}

/** Extracts a Kinopoisk id from "301", "kinopoisk.ru/film/301/", "…/series/464963/" and similar. */
object KpLinks {
    private val link = Regex("""kinopoisk\.[a-z]+/(?:film|series)/(\d+)""", RegexOption.IGNORE_CASE)
    private val bareId = Regex("""^\s*(\d{3,8})\s*$""")

    fun extractId(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        link.find(text)?.let { return it.groupValues[1].toIntOrNull() }
        return bareId.find(text)?.groupValues?.get(1)?.toIntOrNull()
    }
}
