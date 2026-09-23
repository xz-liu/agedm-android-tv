package io.agedm.tv.ui

import kotlinx.coroutines.withTimeoutOrNull

/** Only the local timeout activates fallback; cancellation by a newer selection propagates. */
internal suspend fun <T : Any> resolveWithTimeoutFallback(
    timeoutMs: Long,
    resolve: suspend () -> T,
    fallback: suspend () -> T,
): T = withTimeoutOrNull(timeoutMs) { resolve() } ?: fallback()
