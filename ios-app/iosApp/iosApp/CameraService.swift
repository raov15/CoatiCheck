import AVFoundation
import SwiftUI
import UIKit

/// Servicio de cámara frontal para capturar el fotograma de asistencia
/// y la foto de enrolamiento. Entrega JPEG (`Data`) al completar.
final class CameraService: NSObject, ObservableObject {

    let session = AVCaptureSession()
    @Published var authorizationDenied = false
    @Published var isRunning = false

    private let photoOutput = AVCapturePhotoOutput()
    private let sessionQueue = DispatchQueue(label: "com.coati.checador.ios.camera")
    private var isConfigured = false
    private var captureCompletion: ((Data?) -> Void)?

    // MARK: - Permisos

    func checkPermissionAndStart() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configureAndStart()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    if granted {
                        self?.configureAndStart()
                    } else {
                        self?.authorizationDenied = true
                    }
                }
            }
        default:
            authorizationDenied = true
        }
    }

    // MARK: - Sesión

    private func configureAndStart() {
        sessionQueue.async { [self] in
            if !isConfigured {
                guard let device = AVCaptureDevice.default(
                    .builtInWideAngleCamera, for: .video, position: .front
                ), let input = try? AVCaptureDeviceInput(device: device) else {
                    return
                }
                session.beginConfiguration()
                session.sessionPreset = .photo
                if session.canAddInput(input) {
                    session.addInput(input)
                }
                if session.canAddOutput(photoOutput) {
                    session.addOutput(photoOutput)
                }
                session.commitConfiguration()
                isConfigured = true
            }
            if !session.isRunning {
                session.startRunning()
                DispatchQueue.main.async { self.isRunning = true }
            }
        }
    }

    func stop() {
        sessionQueue.async { [self] in
            if session.isRunning {
                session.stopRunning()
                DispatchQueue.main.async { self.isRunning = false }
            }
        }
    }

    // MARK: - Captura

    /// Captura una foto y entrega los bytes JPEG en el hilo principal.
    func capturePhoto(completion: @escaping (Data?) -> Void) {
        sessionQueue.async { [self] in
            guard isConfigured else {
                DispatchQueue.main.async { completion(nil) }
                return
            }
            DispatchQueue.main.async { self.captureCompletion = completion }
            let settings = AVCapturePhotoSettings()
            photoOutput.capturePhoto(with: settings, delegate: self)
        }
    }
}

extension CameraService: AVCapturePhotoCaptureDelegate {

    func photoOutput(
        _ output: AVCapturePhotoOutput,
        didFinishProcessingPhoto photo: AVCapturePhoto,
        error: Error?
    ) {
        let data = error == nil ? photo.fileDataRepresentation() : nil
        DispatchQueue.main.async { [self] in
            let completion = captureCompletion
            captureCompletion = nil
            completion?(data)
        }
    }
}

/// Vista previa en vivo de la sesión de cámara para SwiftUI.
struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {}
}
