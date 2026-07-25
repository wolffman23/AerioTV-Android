package com.aeriotv.android.core.network

import android.util.Log
import com.aeriotv.android.BuildConfig
import com.aeriotv.android.core.debug.DebugLogger
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging

/**
 * Install Ktor request/response logging on a [HttpClient], mirroring iOS's
 * NWHTTP console stream. The policy retains only normalized HTTP method and
 * numeric response status markers. Request/source URLs, headers, bodies, and
 * unknown lines are dropped fail-closed before they reach logcat or the
 * shareable file. A line is only emitted on debug builds OR when the user turns
 * on Settings -> Developer -> Enable Debug Logging; otherwise the logger
 * discards it. That makes a release build diagnosable for basic request/status
 * sequencing without disclosing endpoint metadata. Read with
 * `adb logcat -s AerioNet`.
 */
fun HttpClientConfig<*>.installSanitizedLogging() {
    install(Logging) {
        // INFO unconditionally so the plugin is live on release too; whether a
        // given line is actually written is gated per-message below.
        level = LogLevel.INFO
        logger = object : Logger {
            override fun log(message: String) {
                if (BuildConfig.DEBUG || DebugLogger.isLoggingEnabled()) {
                    PersistentHttpLogPolicy.safeMessage(message)?.let { safeMessage ->
                        Log.d("AerioNet", safeMessage)
                    }
                }
            }
        }
    }
}
