package io.agedm.tv.ui

import android.graphics.drawable.ColorDrawable
import android.widget.ImageView
import androidx.core.content.ContextCompat
import coil.load
import io.agedm.tv.R

fun ImageView.loadPosterImage(url: String) {
    val placeholder = ColorDrawable(ContextCompat.getColor(context, R.color.age_surface_alt))
    load(url) {
        crossfade(true)
        placeholder(placeholder)
        error(placeholder)
        allowHardware(false)
    }
}
