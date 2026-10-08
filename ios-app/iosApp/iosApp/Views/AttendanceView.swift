import SwiftUI
import CoatiCheckShared

/// Pantalla principal de checador: cámara + reconocimiento facial 1:N.
struct AttendanceView: View {
    @StateObject private var viewModel = AttendanceViewModel()
    @StateObject private var camera = CameraService()
    @ObservedObject private var appState = AppState.shared

    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                cameraSection
                resultSection
                employeeList
            }
            .navigationTitle("Asistencia")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    if appState.pendingCount > 0 {
                        Text("\(appState.pendingCount) pendientes")
                            .font(.caption)
                            .foregroundColor(.orange)
                    }
                }
            }
        }
        .navigationViewStyle(.stack)
        .onAppear {
            camera.checkPermissionAndStart()
            viewModel.refresh()
        }
        .onDisappear {
            camera.stop()
        }
    }

    // MARK: - Cámara

    private var cameraSection: some View {
        ZStack {
            if camera.authorizationDenied {
                VStack(spacing: 12) {
                    Image(systemName: "camera.fill")
                        .font(.system(size: 44))
                        .foregroundColor(.secondary)
                    Text("Se requiere acceso a la cámara")
                        .font(.headline)
                    Button("Abrir Ajustes") {
                        if let url = URL(string: UIApplication.openSettingsURLString) {
                            UIApplication.shared.open(url)
                        }
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                CameraPreview(session: camera.session)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                captureButton
            }
        }
        .frame(maxHeight: 380)
    }

    private var captureButton: some View {
        VStack {
            Spacer()
            Button {
                guard !viewModel.isProcessing else { return }
                camera.capturePhoto { data in
                    guard let data else {
                        viewModel.errorMessage = "No se pudo capturar la foto"
                        return
                    }
                    viewModel.registerAttendance(frame: data)
                }
            } label: {
                ZStack {
                    Circle()
                        .strokeBorder(Color.white, lineWidth: 4)
                        .background(Circle().fill(Color.white.opacity(0.2)))
                        .frame(width: 72, height: 72)
                    if viewModel.isProcessing {
                        ProgressView()
                            .scaleEffect(1.4)
                    } else {
                        Circle()
                            .fill(Color.white)
                            .frame(width: 58, height: 58)
                    }
                }
            }
            .disabled(viewModel.isProcessing)
            .padding(.bottom, 24)
        }
    }

    // MARK: - Resultado

    @ViewBuilder
    private var resultSection: some View {
        if viewModel.isProcessing {
            Label("Procesando rostro...", systemImage: "faceid")
                .font(.subheadline)
                .foregroundColor(.secondary)
                .padding(.vertical, 10)
        } else if let error = viewModel.errorMessage {
            Label(error, systemImage: "exclamationmark.triangle.fill")
                .font(.subheadline)
                .foregroundColor(.red)
                .padding(.vertical, 10)
        } else if let result = viewModel.lastResult {
            resultBanner(result)
                .padding(.vertical, 10)
        }
    }

    private func resultBanner(_ result: AttendanceRegistration) -> some View {
        let (icon, color, text): (String, Color, String) = {
            switch result.status {
            case AttendanceStatus.ok:
                return ("checkmark.circle.fill", .green, "OK: \(result.employeeName ?? "Empleado")")
            case AttendanceStatus.noFace:
                return ("person.crop.circle.badge.exclamationmark", .orange, "No se detectó ningún rostro")
            case AttendanceStatus.notRecognized:
                return ("person.crop.circle.badge.questionmark", .orange, "Rostro no reconocido")
            default:
                return ("xmark.octagon.fill", .red, result.message ?? "Error en el registro")
            }
        }()
        return Label {
            VStack(alignment: .leading, spacing: 2) {
                Text(text).font(.subheadline).bold()
                if result.status == AttendanceStatus.ok,
                   let confidence = result.confidence?.floatValue {
                    Text(String(format: "Confianza: %.0f%%", confidence * 100))
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
        } icon: {
            Image(systemName: icon)
                .foregroundColor(color)
                .font(.title3)
        }
    }

    // MARK: - Lista de empleados

    private var employeeList: some View {
        List(viewModel.employees, id: \.idLocal) { employee in
            VStack(alignment: .leading, spacing: 2) {
                Text(employee.fullName)
                    .font(.body)
                Text(employee.department)
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
        }
        .listStyle(.plain)
        .overlay {
            if viewModel.employees.isEmpty {
                Text("Sin empleados enrolados")
                    .foregroundColor(.secondary)
            }
        }
    }
}

struct AttendanceView_Previews: PreviewProvider {
    static var previews: some View {
        AttendanceView()
    }
}
