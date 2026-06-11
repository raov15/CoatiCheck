package com.coati.checador.feature.facerecognition.data.di

import com.coati.checador.feature.facerecognition.data.service.EmbeddingService
import com.coati.checador.feature.facerecognition.domain.FaceRecognitionEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Módulo Hilt para el motor de reconocimiento facial.
 * Vincula [FaceRecognitionEngine] con su implementación concreta [EmbeddingService].
 *
 * Instalado en [SingletonComponent] — una única instancia del servicio TFLite
 * compartida entre feature:employee-enrollment y feature:attendance.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class FaceRecognitionModule {

    @Binds
    @Singleton
    abstract fun bindFaceRecognitionEngine(
        impl: EmbeddingService
    ): FaceRecognitionEngine
}
