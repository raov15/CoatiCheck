import Foundation
import CoreLocation
import CoatiCheckShared

@MainActor
final class AttendanceViewModel: ObservableObject {
    @Published var employees: [EmployeeInfo] = []
    @Published var lastResult: AttendanceRegistration?
    @Published var isProcessing = false
    @Published var errorMessage: String?
    @Published var recognizedName: String?
    @Published var allowedEvents: [String] = []
    @Published var showLocationWarning = false

    private let locationAuthorization = AttendanceLocationAuthorization()
    private var eventWithoutLocation: String?

    private let sharedApp = AppDependencies.shared.sharedApp
    private var pendingFrame: Data?
    private var recognizedEmployeeId: String?

    func refresh() {
        employees = sharedApp.getActiveEmployees()
        AppState.shared.refreshPending()
    }

    func recognize(frame: Data) {
        guard !isProcessing else { return }
        cancelSelection()
        isProcessing = true
        errorMessage = nil
        lastResult = nil
        sharedApp.reconocerParaAsistencia(frame: KotlinByteArray.from(data: frame)) {
            [weak self] result, error in
            Task { @MainActor in
                guard let self else { return }
                self.isProcessing = false
                if let error {
                    self.errorMessage = error.localizedDescription
                    return
                }
                guard let result else {
                    self.errorMessage = "Sin respuesta del reconocimiento facial"
                    return
                }
                guard result.status == .ok, let employeeId = result.employeeId else {
                    self.lastResult = result
                    return
                }
                let allowed = self.sharedApp.getAllowedAttendanceEvents(employeeId: employeeId)
                guard !allowed.isEmpty else {
                    self.errorMessage = "La jornada de hoy ya terminó o tiene un estado desconocido."
                    return
                }
                self.pendingFrame = frame
                self.recognizedEmployeeId = employeeId
                self.recognizedName = result.employeeName
                self.allowedEvents = allowed
            }
        }
    }

    func registerAttendance(eventType: String) {
        guard !isProcessing, pendingFrame != nil,
              recognizedEmployeeId != nil, allowedEvents.contains(eventType) else { return }
        isProcessing = true
        locationAuthorization.request { [weak self] authorized in
            guard let self else { return }
            self.isProcessing = false
            if authorized {
                self.performAttendance(eventType: eventType)
            } else {
                self.eventWithoutLocation = eventType
                self.showLocationWarning = true
            }
        }
    }

    func registerWithoutLocation() {
        guard let eventType = eventWithoutLocation else { return }
        eventWithoutLocation = nil
        performAttendance(eventType: eventType)
    }

    private func performAttendance(eventType: String) {
        guard !isProcessing, let frame = pendingFrame,
              let employeeId = recognizedEmployeeId, allowedEvents.contains(eventType) else { return }
        isProcessing = true
        sharedApp.registrarAsistencia(
            frame: KotlinByteArray.from(data: frame),
            eventType: eventType,
            expectedEmployeeId: employeeId
        ) { [weak self] result, error in
            Task { @MainActor in
                guard let self else { return }
                self.isProcessing = false
                self.cancelSelection()
                if let error {
                    self.errorMessage = error.localizedDescription
                } else if let result {
                    self.lastResult = result
                    AppState.shared.refreshPending()
                } else {
                    self.errorMessage = "Sin respuesta del registro de asistencia"
                }
            }
        }
    }

    func cancelSelection() {
        pendingFrame = nil
        recognizedEmployeeId = nil
        recognizedName = nil
        allowedEvents = []
        eventWithoutLocation = nil
    }
}

@MainActor
private final class AttendanceLocationAuthorization: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var completion: ((Bool) -> Void)?

    override init() {
        super.init()
        manager.delegate = self
    }

    func request(completion: @escaping (Bool) -> Void) {
        switch manager.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse:
            completion(true)
        case .notDetermined:
            self.completion = completion
            manager.requestWhenInUseAuthorization()
        default:
            completion(false)
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor [weak self] in
            guard status != .notDetermined, let self, let completion = self.completion else { return }
            self.completion = nil
            completion(status == .authorizedAlways || status == .authorizedWhenInUse)
        }
    }
}
