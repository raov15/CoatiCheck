package com.coati.checador.core.network

import retrofit2.http.GET

interface CoatiApiService {
    // TODO: Define API endpoints
    // See architecture.md section 7 for the backend API structure
    @GET("health")
    suspend fun healthCheck(): Map<String, String>
}
