package com.opencode.viewer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "OpencodeViewer"
        private const val PORT = 4096
        private const val BASE_URL = "http://127.0.0.1:$PORT"
        private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
        private const val BIN_OPENCODE = "$TERMUX_PREFIX/bin/opencode"
        private const val HOME_DIR = "$TERMUX_PREFIX/home"
        private const val MIN_SPLASH_MS = 3200L
        private val ARGS = arrayOf("serve", "--port", "$PORT", "--hostname", "127.0.0.1")

        private val CUSTOM_CSS_JS = buildString {
            append("(function(){")
            append("var s=document.createElement('style');")
            append("s.id='oc-viewer-theme';")
            append("s.textContent=`")
            append(LOADING_CSS)
            append("`;")
            append("document.head.appendChild(s);")
            append("})();")
        }

        private val LOADING_CSS = """
:root {
    --v2-background-bg-deep: #0A0A0F;
    --v2-background-bg-base: #0A0A0F;
    --v2-background-bg-layer-01: #111118;
    --v2-background-bg-layer-02: #171720;
    --v2-background-bg-layer-03: #1E1E28;
    --v2-background-bg-layer-04: #262632;
    --v2-background-bg-accent: #FF4C00;
    --v2-background-bg-contrast: #0A0A0F;
    --v2-background-bg-button-neutral: #262632;
    --v2-border-border-base: #262632;
    --v2-border-border-muted: #171720;
    --v2-text-text-base: #F2F2F7;
    --v2-text-text-muted: #9CA3AF;
    --v2-text-text-accent: #FF7A3D;
    --v2-icon-icon-base: #F2F2F7;
    --v2-icon-icon-accent: #FF4C00;
    --v2-state-fg-success: #3DD68C;
    --v2-state-fg-danger: #FF5C6C;
    --v2-state-fg-warning: #FFC24D;
    --v2-state-fg-info: #4DA8FF;
    --v2-grey-50: #F2F2F7;
    --v2-grey-100: #E4E4EB;
    --v2-grey-200: #C8C8D4;
    --v2-grey-300: #A8A8B8;
    --v2-grey-400: #8A8A9A;
    --v2-grey-500: #6E6E7D;
    --v2-grey-600: #565662;
    --v2-grey-700: #41414B;
    --v2-grey-800: #2F2F38;
    --v2-grey-900: #22222A;
    --v2-grey-1000: #17171D;
    --v2-grey-1100: #101015;
    --v2-grey-1200: #0A0A0F;
}
html { background: #0A0A0F !important; }
body { background: #0A0A0F !important; }
"""
    }

    private lateinit var webView: WebView
    private lateinit var loadingBg: ImageView
    private lateinit var progress: ProgressBar
    private lateinit var statusText: TextView
    private val executor = Executors.newSingleThreadExecutor()
    private var serverUp = false
    private var startedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        OpencodeApp.log("onCreate start")
        try {
            super.onCreate(savedInstanceState)
            setContentView(R.layout.activity_main)
            OpencodeApp.log("setContentView ok")

            webView = findViewById(R.id.webView)
            loadingBg = findViewById(R.id.loadingBg)
            progress = findViewById(R.id.progress)
            statusText = findViewById(R.id.statusText)
            OpencodeApp.log("views ok")

            setupLoadingBg()
            setupWebView()
            ensureServerAndLoad()
            OpencodeApp.log("onCreate done")
        } catch (t: Throwable) {
            OpencodeApp.log("onCreate FAILED: " + t)
            t.printStackTrace()
            throw t
        }
    }

    private fun setupLoadingBg() {
        try {
            Glide.with(this)
                .asGif()
                .load("file:///android_asset/blackhole.gif")
                .into(loadingBg)
            OpencodeApp.log("gif configured")
        } catch (t: Throwable) {
            OpencodeApp.log("gif setup FAILED: " + t)
        }
    }

    private fun setupWebView() {
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(false)
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.toString().startsWith(BASE_URL)) {
                    view.loadUrl(request.url.toString())
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url?.startsWith(BASE_URL) == true) {
                    injectDesign(view)
                }
            }
        }
    }

    private fun injectDesign(view: WebView?) {
        try {
            val css = CUSTOM_CSS_JS
            view?.evaluateJavascript(css, null)
            OpencodeApp.log("design injected")
        } catch (t: Throwable) {
            OpencodeApp.log("design inject FAILED: " + t)
        }
    }

    private fun ensureServerAndLoad() {
        startedAt = System.currentTimeMillis()
        setLoading("Проверка сервера...")
        executor.execute {
            val up = isPortOpen()
            if (up) {
                showSplashThenServer()
            } else {
                startServer()
                waitForServer()
            }
        }
    }

    private fun showSplashThenServer() {
        progress.visibility = View.GONE
        statusText.text = "Подключение..."
        val remaining = MIN_SPLASH_MS - (System.currentTimeMillis() - startedAt)
        if (remaining > 0) {
            Thread.sleep(remaining)
        }
        runOnUiThread { loadServer() }
    }

    private fun isPortOpen(): Boolean {
        return try {
            val conn = URL(BASE_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            conn.requestMethod = "GET"
            val code = conn.responseCode
            conn.disconnect()
            code in 200..499
        } catch (e: Exception) {
            false
        }
    }

    private fun startServer() {
        val intent = Intent("com.termux.RUN_COMMAND")
        intent.component = ComponentName("com.termux", "com.termux.app.RunCommandService")
        intent.putExtra("com.termux.RUN_COMMAND_PATH", BIN_OPENCODE)
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", ARGS)
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME_DIR)
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            runOnUiThread {
                statusText.text = "Сервер не запустился.\nУбедись, что установлен Termux\nи включён параметр allow-external-apps"
                setErrorState()
            }
        }
    }

    private fun waitForServer() {
        runOnUiThread { setLoading("Запуск сервера ($BASE_URL)...") }
        var attempts = 0
        while (attempts < 60) {
            Thread.sleep(500)
            if (isPortOpen()) {
                serverUp = true
                showSplashThenServer()
                return
            }
            attempts++
        }
        runOnUiThread {
            statusText.text = "Сервер не ответил за 30 сек.\nПроверь, что версия opencode\nподдерживает serve, и повтори."
            setErrorState()
        }
    }

    private fun loadServer() {
        progress.visibility = View.GONE
        statusText.visibility = View.GONE
        loadingBg.visibility = View.GONE
        webView.visibility = View.VISIBLE
        webView.loadUrl(BASE_URL)
    }

    private fun setLoading(msg: String) {
        webView.visibility = View.GONE
        progress.visibility = View.VISIBLE
        statusText.visibility = View.VISIBLE
        statusText.text = msg
    }

    private fun setErrorState() {
        progress.visibility = View.GONE
        statusText.visibility = View.VISIBLE
        webView.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        if (::loadingBg.isInitialized && !serverUp && webView.visibility != View.VISIBLE) {
            ensureServerAndLoad()
        }
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.visibility == View.VISIBLE && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }
}