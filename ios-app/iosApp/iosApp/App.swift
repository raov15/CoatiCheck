import SwiftUI
import BackgroundTasks

/// AppDelegate: registra la tarea de fondo de sincronización
/// (BGTaskScheduler, identificador "com.coati.checador.ios.sync").
final class AppDelegate: NSObject, UIApplicationDelegate {

    static let syncTaskId = "com.coati.checador.ios.sync"

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.syncTaskId, using: nil) { task in
            self.runSync(task: task)
        }
        scheduleBackgroundSync()
        return true
    }

    private func runSync(task: BGTask) {
        scheduleBackgroundSync()
        task.expirationHandler = {
            // El sistema pide finalizar: la corrutina interna sigue su curso.
        }
        AppDependencies.shared.sharedApp.syncAwait { success, error in
            task.setTaskCompleted(success: error == nil && (success?.boolValue ?? false))
        }
    }

    private func scheduleBackgroundSync() {
        let request = BGAppRefreshTaskRequest(identifier: Self.syncTaskId)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }
}

@main
struct iosApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
