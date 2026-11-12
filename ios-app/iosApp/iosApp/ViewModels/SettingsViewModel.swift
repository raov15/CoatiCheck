import Foundation
import CoatiCheckShared

@MainActor
final class SettingsViewModel: ObservableObject {
    @Published var serverUrl: String
    @Published var serverSaved = false
    @Published var newPin = ""
    @Published var pinVerification = ""
    @Published var pinMessage: String?
    @Published var isSyncing = false
    @Published var syncMessage: String?
    @Published var syncSucceeded = false

    let hasPinInitially: Bool
    private(set) var pinVerified = false

    private let sharedApp = AppDependencies.shared.sharedApp

    init() {
        serverUrl = AppDependencies.shared.sharedApp.getServerUrl()
        hasPinInitially = AppDependencies.shared.sharedApp.hasAdminPin()
    }

    // MARK: - Servidor

    var canSaveServer: Bool {
        !serverUrl.trimmingCharacters(in: .whitespaces).isEmpty
    }

    func saveServerUrl() {
        guard canSaveServer else { return }
        sharedApp.setServerUrl(url: serverUrl)
        serverSaved = true
        // Feedback temporizado
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            self.serverSaved = false
        }
    }

    // MARK: - PIN admin

    var canSetPin: Bool {
        newPin.trimmingCharacters(in: .whitespaces).count >= 4
    }

    func setAdminPin() {
        guard canSetPin else {
            pinMessage = "El PIN debe tener al menos 4 dígitos"
            return
        }
        sharedApp.setAdminPin(pin: newPin)
        pinMessage = "PIN de administrador actualizado"
        newPin = ""
        pinVerified = false
    }

    func verifyAdminPin() {
        let pin = pinVerification.trimmingCharacters(in: .whitespaces)
        guard !pin.isEmpty else {
            pinMessage = "Ingresa el PIN"
            return
        }
        pinVerified = sharedApp.verifyAdminPin(pin: pin)
        pinMessage = pinVerified
            ? "PIN verificado"
            : "PIN incorrecto"
        pinVerification = ""
    }

    // MARK: - Sincronización

    func syncNow() {
        guard !isSyncing else { return }
        isSyncing = true
        syncMessage = nil
        syncSucceeded = false
        sharedApp.syncAwait { result, error in
            Task { @MainActor in
                self.isSyncing = false
                self.syncSucceeded = error == nil && (result?.success ?? false)
                self.syncMessage = error?.localizedDescription
                    ?? result?.message
                    ?? "No se pudo sincronizar"
                AppState.shared.refreshPending()
            }
        }
    }
}
