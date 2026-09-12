package com.aali.ebookreader

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup = one zip with the reader database (progress, highlights, bookmarks),
 * app settings and the exported highlight txt files. Save it anywhere
 * (Google Drive, email, another phone) and import it on any device.
 */
object BackupManager {

    fun export(context: Context): File {
        Storage.ensureFolders()
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
        val out = File(Storage.backups, "AaliReader-backup-$stamp.zip")

        // make sure db changes are on disk
        try {
            Db.get(context).writableDatabase
                .rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
        } catch (_: Exception) {
        }

        ZipOutputStream(FileOutputStream(out)).use { zip ->
            val dbFile = context.getDatabasePath("reader.db")
            if (dbFile.exists()) addFile(zip, dbFile, "reader.db")
            val prefs = File(
                context.applicationInfo.dataDir,
                "shared_prefs/" + context.packageName + "_preferences.xml"
            )
            if (prefs.exists()) addFile(zip, prefs, "preferences.xml")
            (Storage.highlights.listFiles() ?: emptyArray())
                .filter { it.isFile && it.extension == "txt" }
                .forEach { addFile(zip, it, "Highlights/" + it.name) }
            (Storage.notes.listFiles() ?: emptyArray())
                .filter { it.isFile && it.extension == "txt" }
                .forEach { addFile(zip, it, "Notes/" + it.name) }
            (Storage.wordlists.listFiles() ?: emptyArray())
                .filter { it.isFile && it.extension == "txt" }
                .forEach { addFile(zip, it, "Wordlist/" + it.name) }
            (Storage.summaries.listFiles() ?: emptyArray())
                .filter { it.isFile && it.extension == "txt" }
                .forEach { addFile(zip, it, "Summaries/" + it.name) }
        }
        return out
    }

    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(
            context, "com.aali.ebookreader.fileprovider", file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Restores a backup zip. Returns a human readable result message. */
    fun import(context: Context, uri: Uri): String {
        var restored = 0
        val tmp = File(context.cacheDir, "restore")
        tmp.deleteRecursively()
        tmp.mkdirs()
        context.contentResolver.openInputStream(uri).use { ins ->
            if (ins == null) return "Could not open the selected file"
            ZipInputStream(ins).use { zip ->
                var e: ZipEntry? = zip.nextEntry
                while (e != null) {
                    val name = e.name.replace('\\', '/')
                    if (!e.isDirectory && !name.contains("..")) {
                        val f = File(tmp, name)
                        f.parentFile?.mkdirs()
                        FileOutputStream(f).use { os -> zip.copyTo(os) }
                        restored++
                    }
                    zip.closeEntry()
                    e = zip.nextEntry
                }
            }
        }
        if (restored == 0) return "Nothing found inside that zip"

        // database
        val newDb = File(tmp, "reader.db")
        if (newDb.exists()) {
            Db.reset()
            val target = context.getDatabasePath("reader.db")
            target.parentFile?.mkdirs()
            // remove stale wal/shm
            File(target.absolutePath + "-wal").delete()
            File(target.absolutePath + "-shm").delete()
            copy(newDb, target)
        }
        // preferences
        val newPrefs = File(tmp, "preferences.xml")
        if (newPrefs.exists()) {
            val target = File(
                context.applicationInfo.dataDir,
                "shared_prefs/" + context.packageName + "_preferences.xml"
            )
            target.parentFile?.mkdirs()
            copy(newPrefs, target)
        }
        // highlight and summary txt files
        Storage.ensureFolders()
        File(tmp, "Highlights").listFiles()?.forEach { copy(it, File(Storage.highlights, it.name)) }
        File(tmp, "Notes").listFiles()?.forEach { copy(it, File(Storage.notes, it.name)) }
        File(tmp, "Wordlist").listFiles()?.forEach { copy(it, File(Storage.wordlists, it.name)) }
        File(tmp, "Summaries").listFiles()?.forEach { copy(it, File(Storage.summaries, it.name)) }
        tmp.deleteRecursively()
        return "Backup restored ($restored files). Please reopen the app."
    }

    private fun addFile(zip: ZipOutputStream, file: File, name: String) {
        zip.putNextEntry(ZipEntry(name))
        FileInputStream(file).use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun copy(src: File, dst: File) {
        FileInputStream(src).use { i -> FileOutputStream(dst).use { o -> i.copyTo(o) } }
    }
}
