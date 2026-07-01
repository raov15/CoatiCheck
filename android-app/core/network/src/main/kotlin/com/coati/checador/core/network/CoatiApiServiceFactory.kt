package com.coati.checador.core.network

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CoatiApiServiceFactory @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json
) {
    fun create(rawBaseUrl: String?): CoatiApiService =
        Retrofit.Builder()
            .baseUrl(normalizeApiBaseUrl(rawBaseUrl))
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(CoatiApiService::class.java)
}