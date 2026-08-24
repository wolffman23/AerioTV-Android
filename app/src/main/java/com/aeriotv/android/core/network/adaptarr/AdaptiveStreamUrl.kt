package com.aeriotv.android.core.network.adaptarr

/**
 * Byte-preserving query transformation for a caller-authorized Dispatcharr live
 * proxy URL. This object validates only URL grammar; callers establish source
 * authority before invoking it.
 */
internal object AdaptiveStreamUrl {

    private const val OutputProfileParameter = "output_profile"
    private const val DispatcharrProxyMarker = "/proxy/ts/stream/"

    fun withOutputProfile(url: String, outputProfileId: Int): String? {
        if (outputProfileId <= 0 || url.isEmpty() || url != url.trim()) return null

        val schemeEnd = when {
            url.startsWith("https://", ignoreCase = true) -> "https://".length
            url.startsWith("http://", ignoreCase = true) -> "http://".length
            else -> return null
        }
        val fragmentIndex = url.indexOf('#')
        val beforeFragment = if (fragmentIndex >= 0) url.substring(0, fragmentIndex) else url
        val fragment = if (fragmentIndex >= 0) url.substring(fragmentIndex) else ""
        val queryIndex = beforeFragment.indexOf('?')
        val path = if (queryIndex >= 0) beforeFragment.substring(0, queryIndex) else beforeFragment
        val pathStart = path.indexOf('/', schemeEnd)
        if (pathStart < 0 || !path.substring(pathStart).contains(DispatcharrProxyMarker)) return null

        val rewrittenQuery = if (queryIndex < 0) {
            "$OutputProfileParameter=$outputProfileId"
        } else {
            rewriteQuery(beforeFragment.substring(queryIndex + 1), outputProfileId)
        }
        return "$path?$rewrittenQuery$fragment"
    }

    fun withOptionalOutputProfile(url: String, outputProfileId: Int?): String =
        outputProfileId?.let { withOutputProfile(url, it) } ?: url

    private fun rewriteQuery(query: String, outputProfileId: Int): String {
        var replaced = false
        val parameters = query.split('&').mapNotNull { parameter ->
            if (parameter.substringBefore('=') != OutputProfileParameter) {
                parameter
            } else if (replaced) {
                null
            } else {
                replaced = true
                "$OutputProfileParameter=$outputProfileId"
            }
        }
        return if (replaced) {
            parameters.joinToString("&")
        } else {
            (parameters + "$OutputProfileParameter=$outputProfileId").joinToString("&")
        }
    }
}
