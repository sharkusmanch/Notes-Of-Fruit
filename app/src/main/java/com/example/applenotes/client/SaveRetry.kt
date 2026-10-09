package com.example.applenotes.client

/** What a per-record error from `records/modify` means for the save that hit it. */
internal enum class SaveErrorKind {
    /** The zone was being written by someone else. Send the same request again shortly. */
    ZONE_BUSY,

    /** The record changed since we read it. Our bytes are built on a stale copy. */
    RECORD_CONFLICT,

    /** Retrying will not help. */
    FATAL,
}

internal fun classifySaveError(serverErrorCode: String, reason: String?): SaveErrorKind = when {
    serverErrorCode == "CONFLICT" -> SaveErrorKind.RECORD_CONFLICT
    serverErrorCode == "TRY_AGAIN_LATER" || serverErrorCode == "THROTTLED" -> SaveErrorKind.ZONE_BUSY
    // Seen as the reason on CONFLICT; kept in case a server version reports
    // it under another code.
    reason?.contains("oplock", ignoreCase = true) == true -> SaveErrorKind.RECORD_CONFLICT
    else -> SaveErrorKind.FATAL
}

internal const val MAX_RETRY_DELAY_MS = 5_000L

/**
 * How long to wait before retry number [attempt] (1-based). The server's own
 * `retryAfter` hint wins when it sends one.
 */
internal fun retryDelayMs(attempt: Int, retryAfterSec: Double?): Long {
    val ms = if (retryAfterSec != null && retryAfterSec > 0) {
        (retryAfterSec * 1000).toLong()
    } else {
        400L shl (attempt - 1).coerceIn(0, 10)
    }
    return ms.coerceAtMost(MAX_RETRY_DELAY_MS)
}
