package io.agedm.tv.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
}
