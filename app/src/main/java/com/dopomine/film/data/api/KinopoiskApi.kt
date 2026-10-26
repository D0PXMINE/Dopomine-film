package com.dopomine.film.data.api

import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

interface KinopoiskApi {

    @GET("api/v2.2/films/collections")
    suspend fun collection(
        @Query("type") type: String,
        @Query("page") page: Int = 1,
    ): PagedFilmsDto

    @GET("api/v2.2/films")
    suspend fun films(
        @Query("type") type: String? = null,
        @Query("order") order: String = "NUM_VOTE",
        @Query("genres") genre: Int? = null,
        @Query("countries") country: Int? = null,
        @Query("yearFrom") yearFrom: Int? = null,
        @Query("yearTo") yearTo: Int? = null,
        @Query("ratingFrom") ratingFrom: Int? = null,
        @Query("keyword") keyword: String? = null,
        @Query("page") page: Int = 1,
    ): PagedFilmsDto

    @GET("api/v2.1/films/search-by-keyword")
    suspend fun searchByKeyword(
        @Query("keyword") keyword: String,
        @Query("page") page: Int = 1,
    ): KeywordSearchDto

    @GET("api/v2.2/films/{id}")
    suspend fun film(@Path("id") id: Int): FilmDto

    @GET("api/v2.2/films/{id}/seasons")
    suspend fun seasons(@Path("id") id: Int): SeasonsDto

    @GET("api/v2.2/films/{id}/similars")
    suspend fun similars(@Path("id") id: Int): SimilarsDto

    @GET("api/v1/staff")
    suspend fun staff(@Query("filmId") id: Int): List<StaffDto>

    @GET("api/v2.2/films/filters")
    suspend fun filters(): FiltersDto

    @GET("api/v1/api_keys/{key}")
    suspend fun keyInfo(@Path("key") key: String, @Header("X-API-KEY") header: String): ApiKeyDto
}
