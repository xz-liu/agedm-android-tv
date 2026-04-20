package io.agedm.tv.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.agedm.tv.AgeTvApplication
import io.agedm.tv.databinding.ActivityBangumiLoginBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class BangumiLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBangumiLoginBinding

    private val app: AgeTvApplication
        get() = application as AgeTvApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBangumiLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.closeButton.setOnClickListener { finish() }
        renderQr()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.bangumiAccountService.account.collectLatest {
                    renderAccountState()
                }
            }
        }
    }

    private fun renderQr() {
        val baseUrl = app.linkCastManager.ensureStarted()
        val loginUrl = baseUrl?.trimEnd('/')?.plus("/bgm/login")
        binding.urlText.text = loginUrl ?: "本地登录入口启动失败"
        if (!loginUrl.isNullOrBlank()) {
            binding.qrImage.setImageBitmap(createQrBitmap(loginUrl))
        }
        renderAccountState()
    }

    private fun renderAccountState() {
        val account = app.bangumiAccountService.currentAccount()
        binding.accountStatusText.text = if (account == null) {
            "扫码后在手机上输入 Bangumi 账号、密码和验证码。"
        } else {
            "当前已登录：${account.displayName.ifBlank { account.username }}（${account.username}）"
        }
        binding.loggedInHintText.isVisible = account != null
    }

    companion object {
        fun createIntent(context: Context): Intent {
            return Intent(context, BangumiLoginActivity::class.java)
        }
    }
}
