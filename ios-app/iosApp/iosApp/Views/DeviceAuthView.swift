import SwiftUI
import CoatiCheckShared

/// Registro inicial del dispositivo contra el backend.
struct DeviceAuthView: View {
    let onSuccess: () -> Void

    @StateObject private var viewModel = DeviceAuthViewModel()

    var body: some View {
        NavigationView {
            Form {
                Section {
                    TextField("URL del servidor (https://...)", text: $viewModel.serverUrl)
                        .keyboardType(.URL)
                        .autocapitalization(.none)
                        .disableAutocorrection(true)
                    TextField("Nombre del dispositivo", text: $viewModel.deviceName)
                        .autocapitalization(.none)
                        .disableAutocorrection(true)
                } header: {
                    Text("Configuración")
                } footer: {
                    Text("Registra este dispositivo con el servidor CoatiCheck para poder checar asistencias.")
                }

                Section {
                    TextField("Código de enrolamiento (opcional)", text: $viewModel.enrollmentCode)
                        .autocapitalization(.allCharacters)
                        .disableAutocorrection(true)
                    Button("Enrolar con código") {
                        viewModel.enrollDevice()
                    }
                    .disabled(viewModel.isLoading || viewModel.enrollmentCode.isEmpty)
                } header: {
                    Text("Enrolamiento administrado")
                } footer: {
                    Text("Si el servidor requiere código de enrolamiento, úsalo en lugar del registro directo.")
                }

                Section {
                    Button {
                        viewModel.registerDevice()
                    } label: {
                        if viewModel.isLoading {
                            ProgressView()
                                .frame(maxWidth: .infinity)
                        } else {
                            Text("Registrar dispositivo")
                                .frame(maxWidth: .infinity)
                        }
                    }
                    .disabled(viewModel.isLoading)
                }

                if let error = viewModel.errorMessage {
                    Section {
                        Text(error)
                            .foregroundColor(.red)
                            .font(.footnote)
                    }
                }
            }
            .navigationTitle("CoatiCheck")
            .onChange(of: viewModel.registered) { registered in
                if registered {
                    onSuccess()
                }
            }
        }
    }
}

struct DeviceAuthView_Previews: PreviewProvider {
    static var previews: some View {
        DeviceAuthView(onSuccess: {})
    }
}
