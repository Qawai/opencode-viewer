package com.opencode.viewer

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileWriter

class OpencodeApp : Application() {

    companion object {
        const val TAG = "OpencodeViewer"

        @Volatile
        private var context: Application? = null

        fun log(msg: String) {
            val ctx = context
            android.util.Log.e(TAG, msg)
            if (ctx == null) return
            try {
                val f = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "crash.log")
                FileWriter(f, true).use { it.write(System.currentTimeMillis().toString() + " " + msg + "\n") }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "opencode-viewer-crash.log")
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        ctx.contentResolver.openOutputStream(uri, "wa").use {
                            it?.write((System.currentTimeMillis().toString() + " " + msg + "\n").toByteArray())
                            it?.flush()
                        }
                    }
                } else {
                    val pub = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "opencode-viewer-crash.log")
                    FileWriter(pub, true).use { it.write(System.currentTimeMillis().toString() + " " + msg + "\n") }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "log write failed: $e")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        context = this
        val def = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            log("CRASH in ${t.name}: $e")
            e.printStackTrace()
            def?.uncaughtException(t, e)
        }
        log("App.onCreate started, sdk=" + Build.VERSION.SDK_INT)
    }
}