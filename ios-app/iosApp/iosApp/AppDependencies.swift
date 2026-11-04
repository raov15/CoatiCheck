import Foundation
import CoatiCheckShared

/// Estado global observable de la app (badge de pendientes, etc.).
final class AppState: ObservableObject {
    static let shared = AppState()

    @Published var pendingCount: Int64 = 0

    func refreshPending() {
        pendingCount = AppDependencies.shared.sharedApp.countPendingToSync()
    }
}

/// Contenedor de dependencias: construye SharedApp una sola vez.
///
/// Importante: el motor CoreML se registra ANTES de construir SharedApp
/// porque la fachada captura el motor en su inicialización.
final class AppDependencies {
    static let shared = AppDependencies()

    let sharedApp: SharedApp

    private init() {
        if let coreMLEngine = CoreMLFaceEngine() {
            FaceRecognitionEngineRegistry.shared.externalEngine = coreMLEngine
        }
        sharedApp = SharedApp(
            databaseDriverFactory: DatabaseDriverFactory(),
            platformContext: PlatformContext()
        )
    }
}
