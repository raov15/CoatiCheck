import SwiftUI

/// Router raíz: registro de dispositivo o la app principal.
struct ContentView: View {
    @State private var deviceRegistered =
        AppDependencies.shared.sharedApp.isDeviceRegistered()

    var body: some View {
        if deviceRegistered {
            MainTabView()
        } else {
            DeviceAuthView {
                deviceRegistered = true
            }
        }
    }
}

/// Navegación principal con pestañas, equivalente al bottom nav Android.
struct MainTabView: View {
    @ObservedObject private var appState = AppState.shared

    var body: some View {
        TabView {
            AttendanceView()
                .tabItem {
                    Label("Asistencia", systemImage: "faceid")
                }
                .badge(appState.pendingCount > 0 ? Int(appState.pendingCount) : 0)

            EnrollmentView()
                .tabItem {
                    Label("Enrolar", systemImage: "person.badge.plus")
                }

            HistoryView()
                .tabItem {
                    Label("Historial", systemImage: "clock.arrow.circlepath")
                }

            SettingsView()
                .tabItem {
                    Label("Configuración", systemImage: "gearshape")
                }
        }
    }
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View {
        ContentView()
    }
}
