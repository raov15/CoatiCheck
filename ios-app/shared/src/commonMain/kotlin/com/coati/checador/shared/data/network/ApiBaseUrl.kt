package com.coati.checador.shared.data.network

/**
 * Normaliza la URL base de la API configurada por el usuario.
 *
 * Reglas de seguridad:
 * - Solo se aceptan esquemas https:// y http:// explícitos
 *   (http se permite únicamente en desarrollo local, ver [isInsecureAllowed]).
 * - Se agrega ruta /api/ si el host no la incluye.
 * - No existe URL de fallback hardcodeada; sin configuración no hay red.
 */
object ApiBaseUrl {

    private val IP_REGEX = Regex(
        """^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(:\d+)?$"""
    )

    const val DEFAULT_PORT = "3000"

    /**
     * Normaliza la URL introducida por el usuario.
     * Retorna null si la entrada no es válida.
     */
    fun normalize(raw: String?, allowInsecure: Boolean = false): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim().trimEnd('/')

        return when {
            trimmed.startsWith("https://") -> withApiSuffix(trimmed.removePrefix("https://").let { "https://$it" })

            trimmed.startsWith("http://") -> {
                if (!allowInsecure) return null
                withApiSuffix(trimmed)
            }

            trimmed.startsWith("localhost") -> {
                if (!allowInsecure) return null
                withApiSuffix("http://$trimmed")
            }

            IP_REGEX.matches(trimmed) -> {
                if (!allowInsecure) return null
                withApiSuffix("http://$trimmed")
            }

            else -> null
        }
    }

    /**
     * Acepta un host sin esquema (p. ej. "api.coati.mx" o "192.168.1.10:3000")
     * y decide el esquema: https por defecto, http solo si está permitido.
     */
    fun fromHost(rawHost: String?, allowInsecure: Boolean = false): String? {
        if (rawHost.isNullOrBlank()) return null
        val host = rawHost.trim().trimEnd('/')
        return if (host.startsWith("http://") || host.startsWith("https://")) {
            normalize(host, allowInsecure)
        } else {
            normalize("https://$host", allowInsecure)
        }
    }

    /**
     * Garantiza que la URL termine en /api/ (ruta base del backend).
     * Si el usuario ya la incluyó, se respeta; si no, se agrega.
     */
    private fun withApiSuffix(url: String): String = when {
        url.endsWith("/api/") -> url
        url.endsWith("/api") -> "$url/"
        else -> "$url/api/"
    }
}
