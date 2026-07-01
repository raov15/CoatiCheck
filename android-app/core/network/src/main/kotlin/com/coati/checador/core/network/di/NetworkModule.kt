package com.coati.checador.core.network.di

import com.coati.checador.core.database.dao.AppSettingDao
import com.coati.checador.core.database.entity.AppSettingEntity
import com.coati.checador.core.network.CoatiApiService
import com.coati.checador.core.network.CoatiApiServiceFactory
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                }
            )
            .build()

    @Provides
    @Singleton
    fun provideCoatiApiService(
        apiServiceFactory: CoatiApiServiceFactory,
        appSettingDao: AppSettingDao
    ): CoatiApiService {
        val savedUrl = runBlocking {
            appSettingDao.getValue(AppSettingEntity.KEY_API_BASE_URL)
        }
        return apiServiceFactory.create(savedUrl)
    }
}
