import CoreML
import UIKit
import CoatiCheckShared

/// Motor de reconocimiento facial real con CoreML.
///
/// Carga `mobilefacenet.mlmodelc` (o `.mlmodel`) del bundle de la app.
/// Si el modelo no está presente, el inicializador falla y la app usa
/// el motor simulado determinista de Kotlin (mismo motor para enrolar
/// y reconocer, consistente entre sí).
///
/// Pre-procesamiento idéntico a Android (EmbeddingService):
/// 112x112 px, normalizado a [-1, 1], canales RGB.
final class CoreMLFaceEngine: NSObject, FaceRecognitionEngine {

    private let model: MLModel
    private let inputSize = 112
    private let embeddingDimension: Int32 = 192

    init?(bundle: Bundle = .main) {
        let compiled = bundle.url(forResource: "mobilefacenet", withExtension: "mlmodelc")
        let source = bundle.url(forResource: "mobilefacenet", withExtension: "mlmodel")
        guard let modelURL = compiled ?? source,
              let model = try? MLModel(contentsOf: modelURL) else {
            return nil
        }
        self.model = model
    }

    var versionModelo: String { "mobilefacenet_coreml_v1" }

    var embeddingSize: Int32 { embeddingDimension }

    var isRealModelAvailable: Bool { true }

    func generarEmbedding(faceImage: KotlinByteArray) -> KotlinFloatArray {
        guard let image = UIImage(data: faceImage.toData()),
              let pixels = Self.rgbaPixels(from: image, size: inputSize),
              let embedding = predict(pixels: pixels) else {
            // Imagen no decodificable o predicción fallida: embedding de
            // ceros (la similitud coseno no superará ningún umbral).
            return zeroEmbedding()
        }
        return normalized(embedding)
    }

    // MARK: - Predicción

    private func predict(pixels: [UInt8]) -> [Float]? {
        let description = model.modelDescription
        guard let inputName = description.inputDescriptionsByName.keys.first,
              let outputName = description.outputDescriptionsByName.keys.first else {
            return nil
        }
        guard let constraint =
                description.inputDescriptionsByName[inputName]?.multiArrayConstraint,
              let shape = constraint.shape as? [Int], shape.count >= 3 else {
            return nil
        }

        // Soporta CHW [1,3,H,W] y NHWC [1,H,W,3] según el modelo convertido.
        let height: Int
        let width: Int
        let channelLayout: Bool // true = CHW, false = NHWC
        if shape.count == 4 && shape[1] == 3 {
            channelLayout = true
            height = shape[2]
            width = shape[3]
        } else if shape.count == 4 && shape[3] == 3 {
            channelLayout = false
            height = shape[1]
            width = shape[2]
        } else {
            return nil
        }
        guard height == inputSize, width == inputSize else { return nil }

        guard let multiArray = try? MLMultiArray(shape: constraint.shape, dataType: .float32) else {
            return nil
        }
        let pointer = multiArray.dataPointer.assumingMemoryBound(to: Float.self)
        let plane = inputSize * inputSize
        for index in 0..<plane {
            let r = Float(pixels[index * 4]) / 255.0
            let g = Float(pixels[index * 4 + 1]) / 255.0
            let b = Float(pixels[index * 4 + 2]) / 255.0
            let rn = (r - 0.5) * 2.0
            let gn = (g - 0.5) * 2.0
            let bn = (b - 0.5) * 2.0
            if channelLayout {
                pointer[index] = rn
                pointer[plane + index] = gn
                pointer[plane * 2 + index] = bn
            } else {
                pointer[index * 3] = rn
                pointer[index * 3 + 1] = gn
                pointer[index * 3 + 2] = bn
            }
        }

        guard let provider = try? MLDictionaryFeatureProvider(dictionary: [inputName: multiArray]),
              let prediction = try? model.prediction(from: provider),
              let output = prediction.featureValue(for: outputName)?.multiArrayValue else {
            return nil
        }
        var values = [Float](repeating: 0, count: Int(output.count))
        for index in 0..<Int(output.count) {
            values[index] = output[index].floatValue
        }
        return values
    }

    // MARK: - Utilidades

    private func zeroEmbedding() -> KotlinFloatArray {
        let array = KotlinFloatArray(size: embeddingDimension)
        for index in 0..<embeddingDimension {
            array.set(index: index, value: 0)
        }
        return array
    }

    private func normalized(_ values: [Float]) -> KotlinFloatArray {
        let norm = sqrt(values.reduce(0) { $0 + $1 * $1 })
        let array = KotlinFloatArray(size: Int32(values.count))
        for (index, value) in values.enumerated() {
            array.set(index: Int32(index), value: norm > 0 ? value / norm : 0)
        }
        return array
    }

    /// Reescala la imagen a size x size y devuelve píxeles RGBA [R,G,B,A].
    private static func rgbaPixels(from image: UIImage, size: Int) -> [UInt8]? {
        guard let cgImage = image.cgImage else { return nil }
        var pixels = [UInt8](repeating: 0, count: size * size * 4)
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let context = CGContext(
            data: &pixels,
            width: size,
            height: size,
            bitsPerComponent: 8,
            bytesPerRow: size * 4,
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        )
        guard let context else { return nil }
        context.interpolationQuality = .high
        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: size, height: size))
        return pixels
    }
}
