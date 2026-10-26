package com.dopomine.film.data

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException

enum class ErrorKind { NO_KEY, BAD_KEY, QUOTA, RATE_LIMIT, NOT_FOUND, NETWORK, UNKNOWN }

class AppException(val kind: ErrorKind, cause: Throwable? = null) : Exception(kind.name, cause) {
    val userMessage: String
        get() = when (kind) {
            ErrorKind.NO_KEY -> "Укажите API-ключ Кинопоиска в настройках"
            ErrorKind.BAD_KEY -> "API-ключ не подходит. Проверьте его в настройках"
            ErrorKind.QUOTA -> "Дневной лимит запросов к Кинопоиску исчерпан. Попробуйте завтра или укажите другой ключ"
            ErrorKind.RATE_LIMIT -> "Слишком много запросов. Повторите через пару секунд"
            ErrorKind.NOT_FOUND -> "Ничего не найдено"
            ErrorKind.NETWORK -> "Нет соединения с интернетом"
            ErrorKind.UNKNOWN -> "Что-то пошло не так. Попробуйте ещё раз"
        }

    val needsKey: Boolean get() = kind == ErrorKind.NO_KEY || kind == ErrorKind.BAD_KEY
}

fun Throwable.toAppException(): AppException = when (this) {
    is AppException -> this
    is HttpException -> when (code()) {
        401, 403 -> AppException(ErrorKind.BAD_KEY, this)
        402 -> AppException(ErrorKind.QUOTA, this)
        429 -> AppException(ErrorKind.RATE_LIMIT, this)
        404 -> AppException(ErrorKind.NOT_FOUND, this)
        else -> AppException(ErrorKind.UNKNOWN, this)
    }
    is IOException -> AppException(ErrorKind.NETWORK, this)
    is SerializationException -> AppException(ErrorKind.UNKNOWN, this)
    else -> AppException(ErrorKind.UNKNOWN, this)
}

/** Runs [block] and converts failures to [AppException], keeping coroutine cancellation intact. */
suspend inline fun <T> apiCall(crossinline block: suspend () -> T): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw e.toAppException()
    }
