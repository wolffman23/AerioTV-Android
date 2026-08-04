package com.aeriotv.android.core.network.adaptarr

/**
 * Device-local, network-free grammar transformation for a trusted Dispatcharr live
 * stream URL that carries a per-client `output_profile` selection.
 *
 * Callers must establish trusted Dispatcharr channel provenance before invoking this
 * transformer. The path marker checked here is URL grammar only; it does not validate
 * an authority or host.
 *
 * This performs no network access, never parses or re-encodes the URL through a URI
 * codec (which would mutate already-encoded API keys and paths), and only ever
 * touches the single `output_profile` query parameter. Every other byte of the URL —
 * scheme, host, port, encoded path, unrelated query parameters, and fragment — is
 * preserved exactly.
 */
internal object AdaptiveStreamUrl {

    private const val PARAM = "output_profile"

    // Trusted Dispatcharr live proxy stream URLs use this stable path grammar. This is
    // not an authority validation; callers establish trusted provenance separately.
    private const val DISPATCHARR_MARKER = "/proxy/ts/stream/"

    /**
     * Returns [url] with `output_profile=<id>` appended or replaced, or null when the
     * URL does not match the HTTP(S) trusted-Dispatcharr stream grammar, contains
     * leading or trailing whitespace, or [outputProfileId] is not a positive id.
     * Caller-established trusted Dispatcharr provenance is required; no host or
     * authority validation is performed here.
     */
    fun withOutputProfile(url: String, outputProfileId: Int): String? {
        if (outputProfileId <= 0) return null
        if (url != url.trim()) return null
        if (url.isEmpty()) return null
        if (!isHttpScheme(url)) return null

        // Split off a fragment so it always trails the rewritten query untouched.
        val hashIndex = url.indexOf('#')
        val beforeFragment = if (hashIndex >= 0) url.substring(0, hashIndex) else url
        val fragment = if (hashIndex >= 0) url.substring(hashIndex) else ""

        val queryIndex = beforeFragment.indexOf('?')
        val path = if (queryIndex < 0) beforeFragment else beforeFragment.substring(0, queryIndex)

        // Match the marker only inside the path region (after the authority, before any
        // query/fragment) and case-sensitively against Dispatcharr's actual lowercase
        // path. Authority trust is intentionally outside this grammar transformer.
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
     * playback: a null id or a URL refused by the supported stream grammar yields the
     * original URL unchanged. Callers must separately establish trusted Dispatcharr
     * provenance before treating a rewritten URL as safe. This is the fail-open entry
     * point for the tune path.
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
