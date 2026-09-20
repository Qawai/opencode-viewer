package com.opencode.viewer

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
        private val ARGS = arrayOf("serve", "--port", "$PORT", "--hostname", "127.0.0.1")
    }

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var statusText: TextView
    private val executor = Executors.newSingleThreadExecutor()
    private var serverUp = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        progress = findViewById(R.id.progress)
        statusText = findViewById(R.id.statusText)

        setupWebView()
        ensureServerAndLoad()
    }

    private fun setupWebView() {
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
        }
    }

    private fun ensureServerAndLoad() {
        setLoading("Проверка сервера...")
        executor.execute {
            val up = isPortOpen()
            if (up) {
                runOnUiThread { loadServer() }
            } else {
                startServer()
                waitForServer()
            }
        }
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
                runOnUiThread { loadServer() }
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
        if (::webView.isInitialized && !serverUp && webView.visibility != View.VISIBLE) {
            ensureServerAndLoad()
        }
    }

    override fun onBackPressed() {
        if (webView.visibility == View.VISIBLE && webView.canGoBack()) {
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