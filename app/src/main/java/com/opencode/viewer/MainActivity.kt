package com.opencode.viewer

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PORT = 4096
        private const val BASE_URL = "http://127.0.0.1:$PORT"
        private const val MIN_SPLASH_MS = 3200L
        private const val ASSET_BIN = "opencode.gz"
        private const val BIN_NAME = "opencode"

        private val HOME_DIR = "home"
        private val DATA_DIR = "home/.local/share/opencode"
        private val CONFIG_DIR = "home/.config/opencode"

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
    }

    private lateinit var webView: WebView
    private lateinit var loadingBg: ImageView
    private lateinit var progress: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var settingsView: View
    private val executor = Executors.newSingleThreadExecutor()
    private var serverUp = false
    private var serverProcess: Process? = null
    private var startedAt = 0L
    private var firstRunDone = false

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
            settingsView = findViewById(R.id.settingsView)
            OpencodeApp.log("views ok")

            settingsView.setOnClickListener { showSettings() }

            setupLoadingBg()
            setupWebView()
            maybeShowFirstRunHelp()
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
            view?.evaluateJavascript(CUSTOM_CSS_JS, null)
            OpencodeApp.log("design injected")
        } catch (t: Throwable) {
            OpencodeApp.log("design inject FAILED: " + t)
        }
    }

    private fun ensureServerAndLoad() {
        startedAt = System.currentTimeMillis()
        setLoading("Проверка сервера...")
        executor.execute {
            if (isPortOpen()) {
                serverUp = true
                OpencodeApp.log("external server already up")
                runOnUiThread { showSplashThenServer() }
            } else {
                startEmbeddedServer()
                waitForServer()
            }
        }
    }

    private fun startEmbeddedServer() {
        OpencodeApp.log("starting embedded server")
        runOnUiThread { setLoading("Подготовка opencode...") }
        val bin = ensureBinary()
        if (bin == null) {
            runOnUiThread {
                statusText.text = "Не удалось распаковать opencode.\nОсвободи место на устройстве и повтори."
                setErrorState()
            }
            return
        }
        runOnUiThread { setLoading("Запуск opencode сервера...") }
        try {
            val pb = ProcessBuilder(
                bin.absolutePath,
                "serve",
                "--port", "$PORT",
                "--hostname", "127.0.0.1",
                "--print-logs", "--log-level", "INFO"
            )
            val env = pb.environment()
            env["HOME"] = File(filesDir, HOME_DIR).absolutePath
            env["XDG_DATA_HOME"] = File(filesDir, "home/.local/share").absolutePath
            env["XDG_CONFIG_HOME"] = File(filesDir, "home/.config").absolutePath
            env["XDG_CACHE_HOME"] = File(filesDir, "home/.cache").absolutePath
            env["TMPDIR"] = File(filesDir, "home/tmp").absolutePath
            env["PATH"] = File(filesDir, "bin").absolutePath + ":/system/bin:/system/xbin"
            env["TERMUX_VERSION"] = ""
            env["TERMUX_APP_PACKAGE"] = ""
            pb.redirectErrorStream(true)
            val process = pb.start()
            serverProcess = process
            OpencodeApp.log("embedded server process started")
            readServerLogs(process)
        } catch (t: Throwable) {
            OpencodeApp.log("embedded start FAILED: " + t)
            runOnUiThread {
                statusText.text = "Не удалось запустить сервер.\n" + t.message
                setErrorState()
            }
        }
    }

    private fun readServerLogs(process: Process) {
        executor.execute {
            try {
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val buf = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    buf.append(line).append("\n")
                    if (buf.length > 2000) buf.delete(0, 1000)
                    OpencodeApp.log("server: " + (line?.takeLast(400) ?: ""))
                }
                OpencodeApp.log("server exited: " + buf.toString().takeLast(2000))
                serverLogTail = buf.toString().takeLast(1500)
            } catch (t: Throwable) {
                OpencodeApp.log("server log read FAILED: " + t)
            }
        }
    }

    private var serverLogTail = ""

    private fun stopEmbeddedServer() {
        serverProcess?.let {
            try {
                it.destroy()
                it.waitFor(3000, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (t: Throwable) {
                OpencodeApp.log("stop server FAILED: " + t)
            }
        }
        serverProcess = null
    }

    private fun ensureBinary(): File? {
        return try {
            val binDir = File(filesDir, "bin")
            val bin = File(binDir, BIN_NAME)
            if (bin.exists() && bin.length() > 100_000_000) {
                return bin
            }
            binDir.mkdirs()
            val tmp = File(binDir, "$BIN_NAME.tmp")
            if (tmp.exists()) tmp.delete()
            val total = assets.open(ASSET_BIN).use { it.available() }
            var done = 0L
            val o = FileOutputStream(tmp)
            val gz = GZIPInputStream(assets.open(ASSET_BIN))
            val buffer = ByteArray(1 shl 16)
            var read: Int
            while (gz.read(buffer).also { read = it } != -1) {
                o.write(buffer, 0, read)
                done += read
                publishProgress(done, total)
            }
            o.flush()
            o.close()
            gz.close()
            if (tmp.renameTo(bin)) tmp.delete()
            bin.setExecutable(true, false)
            OpencodeApp.log("binary ready size=" + bin.length())
            bin
        } catch (t: Throwable) {
            OpencodeApp.log("binary extract FAILED: " + t)
            null
        }
    }

    private var lastProgressUpdate = 0L

    private fun publishProgress(done: Long, total: Int) {
        val now = System.currentTimeMillis()
        if (now - lastProgressUpdate < 100) return
        lastProgressUpdate = now
        val doneMb = done / (1024 * 1024)
        val totalMb = total / (1024 * 1024)
        runOnUiThread {
            progress.isIndeterminate = false
            try {
                if (total > 0) progress.max = total
                progress.progress = done.toInt()
            } catch (u: Throwable) {
                progress.isIndeterminate = true
            }
            statusText.text = "Распаковка opencode...\n$doneMb / $totalMb МБ"
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

    private fun waitForServer() {
        runOnUiThread { setLoading("Ожидание сервера ($BASE_URL)...") }
        var attempts = 0
        while (attempts < 90) {
            Thread.sleep(500)
            if (isPortOpen()) {
                serverUp = true
                showSplashThenServer()
                return
            }
            attempts++
        }
        runOnUiThread {
            val log = serverLogTail.trim()
            statusText.text = if (log.isEmpty()) {
                "Сервер не ответил за 45 сек.\nСервер сам запускается внутри приложения,\nпопробуй открыть настройки и указать ключ."
            } else {
                "Сервер не запустился. Логи:\n" + log.takeLast(500)
            }
            setErrorState()
        }
    }

    private fun loadServer() {
        progress.visibility = View.GONE
        statusText.visibility = View.GONE
        loadingBg.visibility = View.GONE
        webView.visibility = View.VISIBLE
        settingsView.visibility = View.VISIBLE
        webView.loadUrl(BASE_URL)
    }

    private fun setLoading(msg: String) {
        webView.visibility = View.GONE
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        statusText.visibility = View.VISIBLE
        statusText.text = msg
    }

    private fun setErrorState() {
        progress.visibility = View.GONE
        statusText.visibility = View.VISIBLE
        webView.visibility = View.GONE
    }

    private fun maybeShowFirstRunHelp() {
        if (firstRunDone) return
        firstRunDone = true
        val prefs = getSharedPreferences("opencode_viewer", Context.MODE_PRIVATE)
        if (prefs.getBoolean("first_run_help_shown", false)) return
        prefs.edit().putBoolean("first_run_help_shown", true).apply()

        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(48, 24, 48, 8)

        val hint = TextView(this)
        hint.text = "Добро пожаловать в OpenCode!\n\nЧтобы начать работу, нужен API-ключ твоего поставщика ИИ (Anthropic, OpenAI, OpenRouter и т.д.). Приложение поднимает собственный сервер opencode прямо на телефоне — Termux и браузер не требуются.\n\nКак ввести ключ:\n1. Нажми «Ввести ключ» ниже.\n2. Скопируй ключ из личного кабинета провайдера (начинается с sk-...).\n3. Вставь его в поле и нажми «Сохранить».\n\nКлюч можно поменять в любой момент по кнопке ⚙ в углу экрана."
        hint.textSize = 15f
        container.addView(hint)

        AlertDialog.Builder(this)
            .setTitle("Первая настройка")
            .setView(container)
            .setPositiveButton("Ввести ключ") { _, _ -> showSettings() }
            .setNegativeButton("Позже", null)
            .show()
    }

    private fun showSettings() {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(48, 24, 48, 8)

        val hint = TextView(this)
        hint.text = "Введи API-ключ поставщика (Anthropic/OpenAI/OpenRouter и т.д.).\nПриложение создаст локальную учётную запись opencode и поднимёт сервер самостоятельно — Termux не нужен."
        hint.textSize = 14f
        container.addView(hint)

        val input = EditText(this)
        input.hint = "sk-..."
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = 24
        input.layoutParams = lp
        container.addView(input)

        val hint2 = TextView(this)
        hint2.text = "Уже настроен ключ? Просто переустанови приложение или введи заново."
        hint2.textSize = 12f
        hint2.setTextColor(0xFF888888.toInt())
        val lp2 = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp2.topMargin = 12
        hint2.layoutParams = lp2
        container.addView(hint2)

        AlertDialog.Builder(this)
            .setTitle("Ключ API")
            .setView(container)
            .setPositiveButton("Сохранить") { _, _ ->
                val key = input.text.toString().trim()
                if (key.isNotEmpty()) {
                    saveKey(key)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun saveKey(key: String) {
        OpencodeApp.log("saving api key")
        executor.execute {
            val authFile = File(filesDir, "$DATA_DIR/auth.json")
            authFile.parentFile?.mkdirs()
            val json = "{\n  \"opencode\": {\n    \"type\": \"api\",\n    \"key\": \"$key\"\n  }\n}\n"
            try {
                authFile.writeText(json)
                OpencodeApp.log("auth saved to " + authFile.absolutePath)
            } catch (t: Throwable) {
                OpencodeApp.log("auth save FAILED: " + t)
            }
            if (serverUp && !isPortOpen()) {
                runOnUiThread {
                    setLoading("Перезапуск с новым ключом...")
                    stopEmbeddedServer()
                    ensureServerAndLoad()
                }
            }
        }
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
        stopEmbeddedServer()
        executor.shutdownNow()
    }
}