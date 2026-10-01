package com.coati.checador.shared.domain.facerecognition

import com.coati.checador.shared.security.PlatformContext

/**
 * Motor iOS.
 *
 * La inferencia real (CoreML + Vision con MobileFaceNet) se implementa en
 * Swift dentro de la app iOS —el modelo mobilefacenet.mlmodelc y la
 * detección de rostros con Vision viven en el bundle nativo— y se registra
 * en [FaceRecognitionEngineRegistry.externalEngine] al arrancar:
 *
 * ```swift
 * FaceRecognitionEngineRegistry.companion.externalEngine =
 *     CoreMLFaceRecognitionEngine() // implementación Swift
 * ```
 *
 * Si no hay motor registrado se usa el embedding simulado determinista,
 * que mantiene funcional el flujo enrolamiento -> reconocimiento 1:N.
 */
actual fun createFaceRecognitionEngine(platformContext: PlatformContext): FaceRecognitionEngine =
    FaceRecognitionEngineRegistry.externalEngine
        ?: SimulatedFaceRecognitionEngine()
