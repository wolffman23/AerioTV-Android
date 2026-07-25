package com.aeriotv.android.core.network.adaptarr

/**
 * Device-local, network-free rewriting of a resolved Dispatcharr live stream URL to
 * carry a per-client `output_profile` selection.
 *
 * This performs no network access, never parses or re-encodes the URL through a URI
 * codec (which would mutate already-encoded API keys and paths), and only ever
 * touches the single `output_profile` query parameter. Every other byte of the URL —
 * scheme, host, port, encoded path, unrelated query parameters, and fragment — is
 * preserved exactly.
 */
internal object AdaptiveStreamUrl {

    private const val PARAM = "output_profile"

    // Dispatcharr live proxy stream URLs carry this stable path marker; anything else
    // (direct provider sources, non-Dispatcharr hosts) must be refused so the rewrite
    // can never be pointed at an arbitrary destination.
    private const val DISPATCHARR_MARKER = "/proxy/ts/stream/"

    /**
     * Returns [url] with `output_profile=<id>` appended or replaced, or null when the
     * URL is not a rewritable Dispatcharr HTTP(S) stream URL or [outputProfileId] is
     * not a positive id.
     */
    fun withOutputProfile(url: String, outputProfileId: Int): String? {
        if (outputProfileId <= 0) return null
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        if (!isHttpScheme(trimmed)) return null

        // Split off a fragment so it always trails the rewritten query untouched.
        val hashIndex = trimmed.indexOf('#')
        val beforeFragment = if (hashIndex >= 0) trimmed.substring(0, hashIndex) else trimmed
        val fragment = if (hashIndex >= 0) trimmed.substring(hashIndex) else ""

        val queryIndex = beforeFragment.indexOf('?')
        val path = if (queryIndex < 0) beforeFragment else beforeFragment.substring(0, queryIndex)

        // Match the marker only inside the path region (after the authority, before any
        // query/fragment) and case-sensitively against Dispatcharr's actual lowercase
        // path, so a foreign URL cannot qualify by smuggling the marker into a query
        // value, fragment, or a case-shifted path.
        val authorityEnd = path.indexOf('/', "https://".length)
        if (authorityEnd < 0) return null
        if (!path.substring(authorityEnd).contains(DISPATCHARR_MARKER)) return null

        val rewritten = if (queryIndex < 0) {
            "$path?$PARAM=$outputProfileId"
        } else {
            val query = beforeFragment.substring(queryIndex + 1)
            "$path?${rewriteQuery(query, outputProfileId)}"
        }
        return rewritten + fragment
    }

    /**
     * Applies [withOutputProfile] when [outputProfileId] is non-null, but never fails
     * playback: a null id or a refused (non-Dispatcharr) URL yields the original URL
     * unchanged. This is the fail-open entry point for the tune path.
     */
    fun withOptionalOutputProfile(url: String, outputProfileId: Int?): String {
        if (outputProfileId == null) return url
        return withOutputProfile(url, outputProfileId) ?: url
    }

    private fun isHttpScheme(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)

    private fun rewriteQuery(query: String, outputProfileId: Int): String {
        val params = query.split("&")
        var replaced = false
        val rebuilt = params.mapNotNull { param ->
            val name = param.substringBefore('=')
            if (name == PARAM) {
                if (replaced) {
                    // Collapse any duplicate output_profile params to a single one.
                    null
                } else {
                    replaced = true
                    "$PARAM=$outputProfileId"
                }
            } else {
                param
            }
        }
        val joined = rebuilt.joinToString("&")
        return if (replaced) joined else "$joined&$PARAM=$outputProfileId"
    }
}
