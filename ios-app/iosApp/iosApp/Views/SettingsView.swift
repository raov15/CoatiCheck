import SwiftUI
import CoatiCheckShared

/// Configuración: servidor, PIN admin y sincronización manual.
struct SettingsView: View {
    @StateObject private var viewModel = SettingsViewModel()
    @ObservedObject private var appState = AppState.shared

    var body: some View {
        NavigationView {
            Form {
                Section {
                    TextField("URL del servidor", text: $viewModel.serverUrl)
                        .keyboardType(.URL)
                        .autocapitalization(.none)
                        .disableAutocorrection(true)
                    Button(viewModel.serverSaved ? "Guardado" : "Guardar") {
                        viewModel.saveServerUrl()
                    }
                    .disabled(!viewModel.canSaveServer)
                } header: {
                    Text("Servidor")
                } footer: {
                    Text("URL base del backend CoatiCheck (https).")
                }

                Section {
                    if viewModel.hasPinInitially && !viewModel.pinVerified {
                        SecureField("PIN actual", text: $viewModel.pinVerification)
                        Button("Verificar PIN") {
                            viewModel.verifyAdminPin()
                        }
                    }
                    if !viewModel.hasPinInitially || viewModel.pinVerified {
                        SecureField("Nuevo PIN (mín. 4 dígitos)", text: $viewModel.newPin)
                        Button("Guardar PIN") {
                            viewModel.setAdminPin()
                        }
                        .disabled(!viewModel.canSetPin)
                    }
                    if let message = viewModel.pinMessage {
                        Text(message).font(.footnote)
                    }
                } header: {
                    Text("PIN de administrador")
                } footer: {
                    Text("El PIN se almacena con hash PBKDF2; nunca en texto plano.")
                }

                Section {
                    HStack {
                        Text("Registros pendientes")
                        Spacer()
                        Text("\(appState.pendingCount)")
                            .foregroundColor(appState.pendingCount > 0 ? .orange : .secondary)
                            .bold()
                    }
                    Button {
                        viewModel.syncNow()
                    } label: {
                        if viewModel.isSyncing {
                            ProgressView().frame(maxWidth: .infinity)
                        } else {
                            Text("Sincronizar ahora")
                                .frame(maxWidth: .infinity)
                        }
                    }
                    .disabled(viewModel.isSyncing)
                    if let message = viewModel.syncMessage {
                        Text(message).font(.footnote).foregroundColor(.secondary)
                    }
                } header: {
                    Text("Sincronización")
                } footer: {
                    Text("Sube empleados, asistencias y ubicaciones pendientes al servidor.")
                }

                Section("Acerca de") {
                    LabeledRow(label: "App", value: "CoatiCheck iOS")
                    LabeledRow(label: "Versión", value: appVersion)
                    LabeledRow(
                        label: "Motor facial",
                        value: FaceRecognitionEngineRegistry.shared.externalEngine != nil
                            ? "CoreML (MobileFaceNet)" : "Simulado"
                    )
                }
            }
            .navigationTitle("Configuración")
        }
        .navigationViewStyle(.stack)
    }

    private var appVersion: String {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
        return version
    }
}

private struct LabeledRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack {
            Text(label)
            Spacer()
            Text(value).foregroundColor(.secondary)
        }
    }
}

struct SettingsView_Previews: PreviewProvider {
    static var previews: some View {
        SettingsView()
    }
}
