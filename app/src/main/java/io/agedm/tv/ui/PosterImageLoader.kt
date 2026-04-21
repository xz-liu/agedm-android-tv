package io.agedm.tv.ui

import android.graphics.drawable.ColorDrawable
import android.widget.ImageView
import androidx.core.content.ContextCompat
import coil.load
import io.agedm.tv.R

fun ImageView.loadPosterImage(url: String) {
    val normalizedUrl = url.trim()
    val placeholder = ColorDrawable(ContextCompat.getColor(context, R.color.age_surface_alt))
    if (getTag(R.id.tag_poster_image_url) == normalizedUrl && drawable !is ColorDrawable) {
        return
    }
    setTag(R.id.tag_poster_image_url, normalizedUrl)
    if (normalizedUrl.isBlank()) {
        setImageDrawable(placeholder)
        return
    }
    load(normalizedUrl) {
        crossfade(false)
        placeholder(placeholder)
        error(placeholder)
        allowHardware(false)
    }
}
