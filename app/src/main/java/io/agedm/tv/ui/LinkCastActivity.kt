package io.agedm.tv.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.agedm.tv.AgeTvApplication
import io.agedm.tv.databinding.ActivityLinkCastBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

class LinkCastActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLinkCastBinding
    private val app: AgeTvApplication
        get() = application as AgeTvApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLinkCastBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.closeButton.setOnClickListener { finish() }

        val serverUrl = app.linkCastManager.ensureStarted()
        binding.urlText.text = serverUrl ?: "启动投送入口失败"
        binding.statusText.text = app.linkCastManager.status.value
        if (!serverUrl.isNullOrBlank()) {
            binding.qrImage.setImageBitmap(createQrBitmap(serverUrl))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.linkCastManager.status.collectLatest { status ->
                        binding.statusText.text = status
                    }
                }
                launch {
                    app.linkCastManager.pendingRoute.filterNotNull().collectLatest {
                        delay(800L)
                        finish()
                    }
                }
            }
        }
    }

    private fun createQrBitmap(content: String): Bitmap {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 1000, 1000)
        val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }
}
