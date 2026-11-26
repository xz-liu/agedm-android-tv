package io.agedm.tv.ui

import android.view.KeyEvent

internal class SkipIntroGesture {
    private var lastForwardMs: Long? = null

    fun onKey(event: KeyEvent, videoHasControlsHidden: Boolean): Boolean {
        if (!videoHasControlsHidden || event.isCanceled) {
            clear()
            return false
        }
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (event.action == KeyEvent.ACTION_DOWN || (event.action == KeyEvent.ACTION_UP && lastForwardMs != null)) {
                lastForwardMs = event.eventTime
            }
            return false
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val forwardMs = lastForwardMs
        clear()
        return event.keyCode == KeyEvent.KEYCODE_DPAD_UP && event.repeatCount == 0 &&
            forwardMs != null && event.eventTime - forwardMs in 0L..1500L
    }

    fun clear() { lastForwardMs = null }
}
