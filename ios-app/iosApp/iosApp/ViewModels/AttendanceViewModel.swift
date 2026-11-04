import Foundation
import CoatiCheckShared

@MainActor
final class AttendanceViewModel: ObservableObject {
    @Published var employees: [EmployeeInfo] = []
    @Published var lastResult: AttendanceRegistration?
    @Published var isProcessing = false
    @Published var errorMessage: String?

    private let sharedApp = AppDependencies.shared.sharedApp

    func refresh() {
        employees = sharedApp.getActiveEmployees()
        AppState.shared.refreshPending()
    }

    /// Registra la asistencia a partir del fotograma JPEG de la cámara.
    /// El pipeline (detección de rostro, reconocimiento 1:N, ubicación
    /// y guardado PENDING) vive en el módulo compartido.
    func registerAttendance(frame: Data) {
        guard !isProcessing else { return }
        isProcessing = true
        errorMessage = nil
        lastResult = nil
        sharedApp.registrarAsistencia(frame: KotlinByteArray.from(data: frame)) {
            [weak self] result, error in
            Task { @MainActor in
                guard let self else { return }
                self.isProcessing = false
                if let error {
                    self.errorMessage = error.localizedDescription
                    return
                }
                guard let result else {
                    self.errorMessage = "Sin respuesta del registro de asistencia"
                    return
                }
                self.lastResult = result
            }
        }
    }
}
