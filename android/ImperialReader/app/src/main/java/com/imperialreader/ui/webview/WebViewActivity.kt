package com.imperialreader.ui.webview

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import com.google.android.material.appbar.MaterialToolbar
import com.imperialreader.R
import com.imperialreader.util.app

/**
 * In-app browser. Cookies are shared with the HTTP client, so solving a Cloudflare
 * challenge here lets the sources work again afterwards.
 */
class WebViewActivity : androidx.appcompat.app.AppCompatActivity() {

    companion object {
        private const val EXTRA_URL = "url"
        fun intent(ctx: Context, url: String): Intent =
            Intent(ctx, WebViewActivity::class.java).putExtra(EXTRA_URL, url)
    }

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        val progress = findViewById<ProgressBar>(R.id.progress)
        web = findViewById(R.id.web)
        toolbar.setNavigationOnClickListener { finish() }

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(web, true)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.userAgentString = app.network.userAgent
        web.webViewClient = WebViewClient()
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress >= 100) android.view.View.GONE else android.view.View.VISIBLE
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                toolbar.title = title
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        web.loadUrl(intent.getStringExtra(EXTRA_URL).orEmpty())
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
