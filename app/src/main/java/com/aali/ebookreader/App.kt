package com.aali.ebookreader

import android.app.Application
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        try {
            PDFBoxResourceLoader.init(applicationContext)
        } catch (_: Exception) {
        }
        // big PDFs are parsed through a temp file instead of the heap
        PdfTextExtractor.init(applicationContext)
        try {
            Storage.ensureFolders()
        } catch (_: Exception) {
        }
    }

    /**
     * If the app ever crashes, the full error is written to
     * Android/data/com.aali.ebookreader/files/crash.txt and shown
     * on the next launch so the problem can be reported and fixed.
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val f = File(getExternalFilesDir(null), "crash.txt")
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val ver = try {
                    packageManager.getPackageInfo(packageName, 0).versionName
                } catch (e: Exception) {
                    "?"
                }
                f.writeText(
                    "Crash at $stamp\nVersion: $ver\n\n" + Log.getStackTraceString(error)
                )
            } catch (_: Exception) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        fun crashFile(app: android.content.Context): File =
            File(app.getExternalFilesDir(null), "crash.txt")
    }
}
