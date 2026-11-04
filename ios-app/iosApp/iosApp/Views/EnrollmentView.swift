import SwiftUI

/// Alta de empleados con captura facial para el embedding.
struct EnrollmentView: View {
    @StateObject private var viewModel = EnrollmentViewModel()
    @StateObject private var camera = CameraService()

    var body: some View {
        NavigationView {
            Form {
                Section("Datos del empleado") {
                    TextField("Nombre", text: $viewModel.firstName)
                    TextField("Apellido paterno", text: $viewModel.lastNamePaternal)
                    TextField("Apellido materno", text: $viewModel.lastNameMaternal)
                    TextField("RFC", text: $viewModel.rfc)
                        .autocapitalization(.allCharacters)
                        .disableAutocorrection(true)
                    TextField("CURP", text: $viewModel.curp)
                        .autocapitalization(.allCharacters)
                        .disableAutocorrection(true)
                    TextField("NSS", text: $viewModel.nss)
                        .keyboardType(.numberPad)
                }

                Section("Cargo") {
                    if viewModel.positions.isEmpty {
                        TextField("Departamento", text: $viewModel.department)
                    } else {
                        Picker("Cargo", selection: $viewModel.department) {
                            ForEach(viewModel.positions, id: \.self) { position in
                                Text(position).tag(position)
                            }
                        }
                    }
                }

                Section {
                    if let photo = viewModel.facePhoto {
                        Image(uiImage: UIImage(data: photo) ?? UIImage())
                            .resizable()
                            .scaledToFit()
                            .frame(maxHeight: 220)
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                    } else {
                        cameraPreview
                    }
                    Button(viewModel.facePhoto == nil ? "Capturar rostro" : "Volver a capturar") {
                        camera.capturePhoto { data in
                            guard let data else {
                                viewModel.errorMessage = "No se pudo capturar la foto"
                                return
                            }
                            viewModel.facePhoto = data
                        }
                    }
                    .disabled(camera.authorizationDenied)
                } header: {
                    Text("Rostro del empleado")
                } footer: {
                    if camera.authorizationDenied {
                        Text("Se requiere acceso a la cámara para enrolar.")
                            .foregroundColor(.red)
                    } else {
                        Text("La foto se procesa para generar el embedding cifrado; no se almacena la imagen en disco.")
                    }
                }

                Section {
                    Button {
                        viewModel.enroll()
                    } label: {
                        if viewModel.isEnrolling {
                            ProgressView().frame(maxWidth: .infinity)
                        } else {
                            Text("Registrar empleado")
                                .frame(maxWidth: .infinity)
                                .bold()
                        }
                    }
                    .disabled(!viewModel.canEnroll)
                }

                if let success = viewModel.successMessage {
                    Text(success).foregroundColor(.green).font(.footnote)
                }
                if let error = viewModel.errorMessage {
                    Text(error).foregroundColor(.red).font(.footnote)
                }
            }
            .navigationTitle("Enrolamiento")
        }
        .navigationViewStyle(.stack)
        .onAppear {
            camera.checkPermissionAndStart()
        }
        .onDisappear {
            camera.stop()
        }
    }

    private var cameraPreview: some View {
        ZStack {
            if camera.authorizationDenied {
                Text("Cámara no disponible")
                    .foregroundColor(.secondary)
                    .frame(maxWidth: .infinity, minHeight: 180)
            } else {
                CameraPreview(session: camera.session)
                    .frame(maxWidth: .infinity, minHeight: 180)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
            }
        }
    }
}

struct EnrollmentView_Previews: PreviewProvider {
    static var previews: some View {
        EnrollmentView()
    }
}
