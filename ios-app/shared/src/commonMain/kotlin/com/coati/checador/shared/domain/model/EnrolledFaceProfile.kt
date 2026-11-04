package com.coati.checador.shared.domain.model

/**
 * Perfil facial activo de un empleado, con el embedding cifrado tal como
 * está almacenado en la base local.
 */
class EnrolledFaceProfile(
    val id: String,
    val employeeId: String,
    val employeeName: String,
    /** Blob cifrado del embedding (ver [EmbeddingCipher]). */
    val embeddingBlob: ByteArray,
    val modelVersion: String,
    val qualityScore: Float?
) {
    override fun equals(other: Any?): Boolean =
        other is EnrolledFaceProfile && other.id == id

    override fun hashCode(): Int = id.hashCode()
}
