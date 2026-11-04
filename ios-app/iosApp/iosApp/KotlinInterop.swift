import Foundation
import CoatiCheckShared

/// Alias para desambiguar el Result de Kotlin frente al Result de Swift.
typealias KResult<T: AnyObject> = CoatiCheckShared.Result<T>

extension KotlinByteArray {

    /// Convierte `Data` (JPEG/PNG) a `KotlinByteArray` para pasarlo a shared.
    static func from(data: Data) -> KotlinByteArray {
        let array = KotlinByteArray(size: Int32(data.count))
        data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            for index in 0..<data.count {
                array.set(index: Int32(index), value: Int8(bitPattern: raw[index]))
            }
        }
        return array
    }

    /// Convierte `KotlinByteArray` a `Data`.
    func toData() -> Data {
        var data = Data(count: Int(size))
        for index in 0..<Int(size) {
            data[index] = UInt8(bitPattern: get(index: Int32(index)))
        }
        return data
    }
}

extension KotlinFloatArray {

    /// Convierte `KotlinFloatArray` (embedding) a `[Float]`.
    func toSwiftArray() -> [Float] {
        (0..<Int(size)).map { get(index: Int32($0)) }
    }
}

extension KotlinThrowable {

    /// Mensaje legible de un Throwable de Kotlin.
    var displayMessage: String {
        message ?? description
    }
}

// MARK: - Manejo de Result de Kotlin

/// Describe un `Result` de Kotlin como éxito con datos o mensaje de error.
enum KResultOutcome<T> {
    case success(T)
    case failure(String)
}

/// Desenvuelve el Result de Kotlin exportado a Swift.
func unwrapKResult<T: AnyObject>(_ result: KResult<T>?) -> KResultOutcome<T> {
    guard let result else {
        return .failure("Respuesta vacía del módulo compartido")
    }
    if let success = result as? ResultSuccess<T> {
        if let data = success.data {
            return .success(data)
        }
        return .failure("Resultado exitoso sin datos")
    }
    if let error = result as? ResultError {
        return .failure(error.message ?? error.exception.displayMessage)
    }
    return .failure("Estado inesperado del resultado")
}
