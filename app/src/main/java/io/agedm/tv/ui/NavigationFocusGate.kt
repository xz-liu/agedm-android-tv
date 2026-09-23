package io.agedm.tv.ui

/** A tab change must match the destination of the user's latest left/right press. */
internal class NavigationFocusGate(private val timeoutMs: Long) {
    private var targetId: Int? = null
    private var deadlineMs = 0L

    fun arm(targetId: Int, nowMs: Long) {
        this.targetId = targetId
        deadlineMs = nowMs + timeoutMs
    }

    fun consume(focusedId: Int, nowMs: Long): Boolean {
        val matches = targetId == focusedId && nowMs <= deadlineMs
        clear()
        return matches
    }

    fun clear() {
        targetId = null
        deadlineMs = 0L
    }
}
