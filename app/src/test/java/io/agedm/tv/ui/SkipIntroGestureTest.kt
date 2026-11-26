package io.agedm.tv.ui

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Application

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SkipIntroGestureTest {
    private val gesture = SkipIntroGesture()
    private val right = KeyEvent.KEYCODE_DPAD_RIGHT
    private val up = KeyEvent.KEYCODE_DPAD_UP

    private fun press(key: Int, time: Long, repeat: Int = 0, video: Boolean = true): Boolean =
        gesture.onKey(KeyEvent(time, time, KeyEvent.ACTION_DOWN, key, repeat), video)

    private fun release(key: Int, time: Long): Boolean =
        gesture.onKey(KeyEvent(time, time, KeyEvent.ACTION_UP, key, 0), true)

    @Test fun repeatedFastForwardsThenUpSkipsOnce() {
        repeat(5) {
            assertFalse(press(right, it * 200L))
            release(right, it * 200L + 80)
        }
        assertTrue(press(up, 1100))
        assertFalse(press(up, 1130, repeat = 1))
        assertFalse(press(up, 1190))
    }

    @Test fun holdingRightThenReleasingStartsWindowAtRelease() {
        press(right, 100)
        repeat(8) { press(right, 300L + 250L * it, repeat = it + 1) }
        release(right, 2600)
        assertTrue(press(up, 3600))
    }

    @Test fun upAloneExpiredGesturesAndControlNavigationDoNotSkip() {
        assertFalse(press(up, 90))
        press(right, 100)
        assertFalse(press(up, 1700))
        press(right, 2000)
        press(KeyEvent.KEYCODE_DPAD_LEFT, 2200)
        assertFalse(press(up, 2300))
        press(right, 2600, video = false)
        assertFalse(press(up, 2720))
        press(right, 2900)
        gesture.clear()
        release(right, 2950)
        assertFalse(press(up, 3000))
    }
}
