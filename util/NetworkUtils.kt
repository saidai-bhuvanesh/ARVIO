package com.arflix.tv.util

object NetworkUtils {
    /**
     * Returns true if the given URL uses HTTPS scheme.
     */
    fun isSecureUrl(url: String): Boolean {
        return url.trim().startsWith("https://", ignoreCase = true)
    }

    /**
     * Ensures the URL has an HTTPS scheme. If the URL starts with http:// it will be replaced.
     * If no scheme is present, https:// is prefixed.
     */
    fun ensureHttps(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        return when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed.replaceFirst("http://", "https://")
            else -> "https://$trimmed"
        }
    }
}
