package io.agedm.tv.ui

import org.junit.Assert.*
import org.junit.Test

class NavigationFocusGateTest {
    @Test fun leftRightNavigationCanEnterCastOnce() {
        val gate = NavigationFocusGate(600)
        gate.arm(targetId = 10, nowMs = 1000)
        assertTrue(gate.consume(focusedId = 10, nowMs = 1010))
        assertFalse(gate.consume(focusedId = 10, nowMs = 1020))
    }

    @Test fun focusRestoredToCastCannotConsumeAnotherTabsNavigation() {
        val gate = NavigationFocusGate(600)
        gate.arm(targetId = 20, nowMs = 1000)
        assertFalse(gate.consume(focusedId = 10, nowMs = 1010))
        assertFalse(gate.consume(focusedId = 20, nowMs = 1020))
    }

    @Test fun returningFromAnotherWindowDoesNotChangeTabs() {
        val gate = NavigationFocusGate(600)
        assertFalse(gate.consume(focusedId = 10, nowMs = 0))
        gate.arm(targetId = 10, nowMs = 1000)
        gate.clear()
        assertFalse(gate.consume(focusedId = 10, nowMs = 1010))
    }

    @Test fun delayedFocusCannotActivateExpiredNavigation() {
        val gate = NavigationFocusGate(600)
        gate.arm(targetId = 10, nowMs = 1000)
        assertFalse(gate.consume(focusedId = 10, nowMs = 1601))
    }
}
