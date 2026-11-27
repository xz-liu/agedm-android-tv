package io.agedm.tv.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

internal fun createQrBitmap(content: String): Bitmap {
    val modules = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0)
    val pixels = IntArray(modules.width * modules.height) { i ->
        if (modules[i % modules.width, i / modules.width]) Color.BLACK else Color.WHITE
    }
    val bitmap = Bitmap.createBitmap(pixels, modules.width, modules.height, Bitmap.Config.RGB_565)
    return Bitmap.createScaledBitmap(bitmap, modules.width * 8, modules.height * 8, false)
}
