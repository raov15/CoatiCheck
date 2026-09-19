package com.coati.checador.core.network

private val IP_ADDRESS_REGEX = Regex("""^\d{1,3}(\.\d{1,3}){3}(:\d+)?$""")

const val DEFAULT_SERVER_URL = "https://cooatii.com"
const val DEFAULT_API_BASE_URL = "https://cooatii.com/api/"
const val DEFAULT_API_IP_FALLBACK = "http://148.230.222.13:3000/api/"

fun normalizeApiBaseUrl(rawValue: String?): String {
    val trimmed = rawValue?.trim().orEmpty()
    if (trimmed.isBlank()) {
        return DEFAULT_API_BASE_URL
    }

    val normalizedScheme = when {
        trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
        IP_ADDRESS_REGEX.matches(trimmed) -> "http://$trimmed"
        else -> "https://$trimmed"
    }

    val withTrailingSlash = if (normalizedScheme.endsWith("/")) {
        normalizedScheme
    } else {
        "$normalizedScheme/"
    }

    return if (withTrailingSlash.endsWith("/api/", ignoreCase = true)) {
        withTrailingSlash
    } else {
        "${withTrailingSlash}api/"
    }
}