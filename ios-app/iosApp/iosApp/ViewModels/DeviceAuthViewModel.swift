import Foundation
import SwiftUI
import CoatiCheckShared

/// Huella de dispositivo estable, persistida en UserDefaults.
enum DeviceFingerprint {
    private static let key = "device_fingerprint"

    static func get() -> String {
        if let existing = UserDefaults.standard.string(forKey: key) {
            return existing
        }
        let fingerprint = UUID().uuidString
        UserDefaults.standard.set(fingerprint, forKey: key)
        return fingerprint
    }
}

@MainActor
final class DeviceAuthViewModel: ObservableObject {
    @Published var serverUrl: String
    @Published var deviceName: String = UIDevice.current.name
    @Published var enrollmentCode: String = ""
    @Published var isLoading = false
    @Published var errorMessage: String?
    @Published var registered = false

    private let sharedApp = AppDependencies.shared.sharedApp
    private let fingerprint = DeviceFingerprint.get()

    init() {
        serverUrl = AppDependencies.shared.sharedApp.getServerUrl()
    }

    func registerDevice() {
        performCall { completion in
            self.sharedApp.registerDevice(
                deviceName: self.deviceName,
                deviceFingerprint: self.fingerprint,
                completionHandler: completion
            )
        }
    }

    func enrollDevice() {
        guard !enrollmentCode.trimmingCharacters(in: .whitespaces).isEmpty else {
            errorMessage = "Ingresa el código de enrolamiento"
            return
        }
        performCall { completion in
            self.sharedApp.enrollDevice(
                deviceName: self.deviceName,
                deviceFingerprint: self.fingerprint,
                enrollmentCode: self.enrollmentCode,
                completionHandler: completion
            )
        }
    }

    private func performCall(
        _ call: (@escaping (KResult<Device>?, Error?) -> Void) -> Void
    ) {
        let url = serverUrl.trimmingCharacters(in: .whitespaces)
        guard !url.isEmpty else {
            errorMessage = "Configura la URL del servidor"
            return
        }
        isLoading = true
        errorMessage = nil
        sharedApp.setServerUrl(url: url)
        call { result, error in
            Task { @MainActor in
                self.isLoading = false
                if let error {
                    self.errorMessage = error.localizedDescription
                    return
                }
                switch unwrapKResult(result) {
                case .success:
                    self.registered = true
                    AppState.shared.refreshPending()
                case .failure(let message):
                    self.errorMessage = message
                }
            }
        }
    }
}
