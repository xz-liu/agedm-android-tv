package io.agedm.tv.ui

import android.content.Context
import android.content.DialogInterface
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.agedm.tv.R

internal fun showResumeDialog(
    context: Context, episode: String, position: String, onChoice: (Boolean) -> Unit,
): AlertDialog {
    val dialog = MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_AgeTv_Resume)
        .setTitle("继续播放")
        .setMessage("$episode · 上次看到 $position")
        .setPositiveButton("继续播放") { _, _ -> onChoice(true) }
        .setNegativeButton("从本集开头播放") { _, _ -> onChoice(false) }
        .setCancelable(false)
        .create()
    dialog.setOnShowListener {
        val resume = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        val restart = dialog.getButton(DialogInterface.BUTTON_NEGATIVE)
        for (button in listOf(resume, restart)) {
            button.backgroundTintList = null
            button.setBackgroundResource(R.drawable.bg_resume_action)
            button.setTextColor(context.getColorStateList(R.color.resume_action_text))
            button.isFocusableInTouchMode = true
        }
        resume.requestFocus()
    }
    dialog.show()
    return dialog
}
