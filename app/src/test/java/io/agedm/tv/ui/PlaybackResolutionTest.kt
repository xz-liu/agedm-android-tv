package io.agedm.tv.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlaybackResolutionTest {
    @Test fun successfulParserDoesNotInvokeFallback() = runBlocking {
        assertEquals("primary", resolveWithTimeoutFallback(1000, { "primary" }) { error("unexpected fallback") })
    }

    @Test fun localTimeoutInvokesAgeFallback() = runBlocking {
        assertEquals("fallback", resolveWithTimeoutFallback(10, { awaitCancellation() }) { "fallback" })
    }

    @Test fun switchingEpisodeCancelsWithoutTryingAnotherSource() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var fallbackInvoked = false
        val job = async {
            resolveWithTimeoutFallback(10_000, { started.complete(Unit); awaitCancellation() }) {
                fallbackInvoked = true
                "wrong episode"
            }
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(fallbackInvoked)
    }
}
