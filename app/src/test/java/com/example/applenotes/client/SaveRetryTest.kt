package com.example.applenotes.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveRetryTest {

    @Test
    fun zoneLockRejectionIsRetriedAsIs() {
        // The exact error iCloud returns when two writes hit the Notes zone at once.
        assertEquals(
            SaveErrorKind.ZONE_BUSY,
            classifySaveError(
                "TRY_AGAIN_LATER",
                "Sync zone CAS Op-Lock failed. There was a concurrent write and this operation was rejected. Retry request...",
            ),
        )
        assertEquals(SaveErrorKind.ZONE_BUSY, classifySaveError("THROTTLED", null))
    }

    @Test
    fun staleRecordTagIsAConflict() {
        assertEquals(
            SaveErrorKind.RECORD_CONFLICT,
            classifySaveError("CONFLICT", "client oplock error updating record"),
        )
    }

    @Test
    fun anythingElseIsFatal() {
        assertEquals(SaveErrorKind.FATAL, classifySaveError("ACCESS_DENIED", null))
        assertEquals(SaveErrorKind.FATAL, classifySaveError("BAD_REQUEST", "missing field"))
    }

    @Test
    fun delayGrowsAndHonoursTheServersHint() {
        assertTrue(retryDelayMs(1, null) < retryDelayMs(2, null))
        assertTrue(retryDelayMs(2, null) < retryDelayMs(3, null))
        assertEquals(2000L, retryDelayMs(1, 2.0))
        // Never wait longer than the cap, whatever the server asks for.
        assertEquals(MAX_RETRY_DELAY_MS, retryDelayMs(1, 600.0))
        assertEquals(MAX_RETRY_DELAY_MS, retryDelayMs(30, null))
    }
}
