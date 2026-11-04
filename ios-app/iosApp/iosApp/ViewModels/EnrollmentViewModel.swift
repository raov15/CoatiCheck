import Foundation
import CoatiCheckShared

@MainActor
final class EnrollmentViewModel: ObservableObject {
    @Published var firstName = ""
    @Published var lastNamePaternal = ""
    @Published var lastNameMaternal = ""
    @Published var rfc = ""
    @Published var curp = ""
    @Published var nss = ""
    @Published var department = ""
    @Published var facePhoto: Data?
    @Published var isEnrolling = false
    @Published var successMessage: String?
    @Published var errorMessage: String?

    let positions: [String]

    private let sharedApp = AppDependencies.shared.sharedApp

    init() {
        positions = AppDependencies.shared.sharedApp.allowedPositions()
        if let first = positions.first {
            department = first
        }
    }

    var canEnroll: Bool {
        !firstName.trimmingCharacters(in: .whitespaces).isEmpty &&
            !lastNamePaternal.trimmingCharacters(in: .whitespaces).isEmpty &&
            !rfc.trimmingCharacters(in: .whitespaces).isEmpty &&
            facePhoto != nil &&
            !isEnrolling
    }

    func enroll() {
        guard canEnroll, let facePhoto else { return }
        isEnrolling = true
        errorMessage = nil
        successMessage = nil
        sharedApp.enrollEmployee(
            firstName: firstName,
            lastNamePaternal: lastNamePaternal,
            lastNameMaternal: lastNameMaternal,
            rfc: rfc,
            curp: curp,
            nss: nss,
            department: department,
            faceImage: KotlinByteArray.from(data: facePhoto),
            completionHandler: { [weak self] result, error in
                Task { @MainActor in
                    guard let self else { return }
                    self.isEnrolling = false
                    if let error {
                        self.errorMessage = error.localizedDescription
                        return
                    }
                    switch unwrapKResult(result) {
                    case .success:
                        self.successMessage =
                            "Empleado \(self.firstName) registrado correctamente"
                        self.resetForm()
                        AppState.shared.refreshPending()
                    case .failure(let message):
                        self.errorMessage = message
                    }
                }
            }
        )
    }

    private func resetForm() {
        firstName = ""
        lastNamePaternal = ""
        lastNameMaternal = ""
        rfc = ""
        curp = ""
        nss = ""
        if let first = positions.first {
            department = first
        }
        facePhoto = nil
    }
}
