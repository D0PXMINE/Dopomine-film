package com.dopomine.film.data

import kotlinx.coroutines.CancellationException
import java.io.IOException

enum class ErrorKind { NOT_FOUND, NETWORK, UNKNOWN }

class AppException(val kind: ErrorKind, cause: Throwable? = null) : Exception(kind.name, cause) {
    val userMessage: String
        get() = when (kind) {
            ErrorKind.NOT_FOUND -> "Ничего не найдено"
            ErrorKind.NETWORK -> "Нет соединения с интернетом"
            ErrorKind.UNKNOWN -> "Что-то пошло не так. Попробуйте ещё раз"
        }
}

fun Throwable.toAppException(): AppException = when (this) {
    is AppException -> this
    is IOException -> AppException(ErrorKind.NETWORK, this)
    else -> AppException(ErrorKind.UNKNOWN, this)
}

/** Runs [block] and converts failures to [AppException], keeping coroutine cancellation intact. */
suspend inline fun <T> appCall(crossinline block: suspend () -> T): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw e.toAppException()
    }
