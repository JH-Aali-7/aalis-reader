package com.aali.ebookreader

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import java.io.File
import java.io.FileOutputStream

class LibraryActivity : AppCompatActivity() {

    private lateinit var adapter: BookAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var txtEmpty: TextView

    private val importBooks =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (!uris.isNullOrEmpty()) copyIn(uris)
        }

    private val importBackup =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                Thread {
                    val msg = try {
                        BackupManager.import(this, uri)
                    } catch (e: Exception) {
                        "Import failed: ${e.message}"
                    }
                    runOnUiThread {
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        refresh()
                    }
                }.start()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))

        txtEmpty = findViewById(R.id.txtEmpty)
        recycler = findViewById(R.id.recycler)
        val span = (resources.displayMetrics.widthPixels /
            (130 * resources.displayMetrics.density)).toInt().coerceIn(2, 5)
        recycler.layoutManager = GridLayoutManager(this, span)
        adapter = BookAdapter(
            onClick = { openBook(it) },
            onLongClick = { bookOptions(it) }
        )
        recycler.adapter = adapter

        findViewById<ExtendedFloatingActionButton>(R.id.fabImport).setOnClickListener {
            importBooks.launch(
                arrayOf(
                    "application/pdf",
                    "application/epub+zip",
                    "text/plain",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                )
            )
        }

        findViewById<android.widget.Button>(R.id.btnGrant).setOnClickListener {
            openAccessSettings()
        }

        DictionaryHelper.warmUp(this)
        UrduDictionary.warmUp(this)
        showCrashReportIfAny()
        checkStoragePermission(showDialog = true)
    }

    override fun onResume() {
        super.onResume()
        checkStoragePermission(showDialog = false)
        refresh()
    }

    private fun showCrashReportIfAny() {
        try {
            val f = App.crashFile(this)
            if (f.exists() && f.length() > 0) {
                val text = f.readText().take(4000)
                MaterialAlertDialogBuilder(this)
                    .setTitle("The app crashed last time")
                    .setMessage(text)
                    .setPositiveButton("Copy and dismiss") { _, _ ->
                        val cm = getSystemService(CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(
                            android.content.ClipData.newPlainText("crash", text)
                        )
                        f.delete()
                    }
                    .setNegativeButton("Dismiss") { _, _ -> f.delete() }
                    .show()
            }
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------ permission

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun checkStoragePermission(showDialog: Boolean) {
        val bar = findViewById<android.view.View>(R.id.permissionBar)
        if (hasStorageAccess()) {
            bar.visibility = android.view.View.GONE
            Storage.ensureFolders()
            return
        }
        bar.visibility = android.view.View.VISIBLE
        if (!showDialog) return
        if (Build.VERSION.SDK_INT >= 30) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Storage access needed")
                .setMessage(
                    "Aali's Reader keeps your books, highlight and note txt files in a visible " +
                        "EbookReader folder on your phone storage.\n\n" +
                        "Tap Continue, then switch ON “Allow access to manage all files” " +
                        "for Aali's Reader.\n\n" +
                        "If that screen does not appear, go to phone Settings > Apps > " +
                        "Special app access > All files access > Aali's Reader.\n\n" +
                        "You can also tap the Grant button in the app at any time."
                )
                .setPositiveButton("Continue") { _, _ -> openAccessSettings() }
                .setNegativeButton("Later", null)
                .show()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                7
            )
        }
    }

    /** Opens the right permission screen, with fallbacks for different phones. */
    private fun openAccessSettings() {
        if (Build.VERSION.SDK_INT < 30) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                7
            )
            return
        }
        // 1. direct screen for this app
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        } catch (_: Exception) {
        }
        // 2. general "All files access" list
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            Toast.makeText(
                this, "Find Aali's Reader in the list and switch it on", Toast.LENGTH_LONG
            ).show()
            return
        } catch (_: Exception) {
        }
        // 3. this app's info page
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
            Toast.makeText(
                this,
                "Open Permissions or Additional permissions and allow all files access",
                Toast.LENGTH_LONG
            ).show()
            return
        } catch (_: Exception) {
        }
        Toast.makeText(
            this,
            "Please open phone Settings > Apps > Special app access > All files access " +
                "and switch on Aali's Reader",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Storage.ensureFolders()
        refresh()
    }

    // ------------------------------------------------ library

    private fun refresh() {
        if (!hasStorageAccess()) return
        Storage.ensureFolders()
        Thread {
            val items = BookRepo.load(this)
            runOnUiThread {
                adapter.submit(items)
                txtEmpty.visibility =
                    if (items.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            }
        }.start()
    }

    private fun openBook(item: BookItem) {
        val cls = if (item.file.extension.equals("pdf", true))
            PdfReaderActivity::class.java else HtmlReaderActivity::class.java
        startActivity(Intent(this, cls).putExtra("path", item.file.absolutePath))
    }

    private fun bookOptions(item: BookItem) {
        val opts = arrayOf(
            "Open", "AI summary", "Word list", "Export notes as PDF",
            "Highlights file", "Notes file", "Delete book"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(item.title)
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> openBook(item)
                    1 -> startActivity(
                        Intent(this, AiSummaryActivity::class.java)
                            .putExtra("path", item.file.absolutePath)
                    )
                    2 -> WordListUi.show(this, item.file.absolutePath)
                    3 -> exportNotesPdf(item.file.absolutePath)
                    4 -> showTextFile(
                        File(Storage.highlights, Storage.safeName(item.title) + ".txt"),
                        "No highlights yet for this book"
                    )
                    5 -> showTextFile(
                        File(Storage.notes, Storage.safeName(item.title) + ".txt"),
                        "No notes yet for this book"
                    )
                    6 -> {
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Delete \"${item.title}\"?")
                            .setMessage("The book file will be removed from the EbookReader/Books folder. Highlights txt files are kept.")
                            .setPositiveButton("Delete") { _, _ ->
                                item.file.delete()
                                refresh()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
            .show()
    }

    private fun exportNotesPdf(path: String) {
        Toast.makeText(this, "Building PDF…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val f = PdfExporter.export(this, path)
                runOnUiThread {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Export ready")
                        .setMessage("Saved to EbookReader/Exports/${f.name}")
                        .setPositiveButton("Share") { _, _ ->
                            startActivity(
                                Intent.createChooser(
                                    PdfExporter.shareIntent(this, f), "Share notes PDF"
                                )
                            )
                        }
                        .setNegativeButton("Done", null)
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showTextFile(f: File, emptyMsg: String) {
        if (f.exists() && f.length() > 0) {
            MaterialAlertDialogBuilder(this)
                .setTitle(f.name)
                .setMessage(f.readText().take(6000))
                .setPositiveButton("Close", null)
                .show()
        } else {
            Toast.makeText(this, emptyMsg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyIn(uris: List<Uri>) {
        if (!hasStorageAccess()) {
            checkStoragePermission(showDialog = true)
            return
        }
        Thread {
            var ok = 0
            for (uri in uris) {
                try {
                    var name = "book"
                    contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
                    }
                    if (name.substringAfterLast('.', "").lowercase() !in
                        setOf("pdf", "epub", "txt", "pptx", "docx")
                    ) continue
                    val target = File(Storage.books, Storage.safeName(name))
                    contentResolver.openInputStream(uri)?.use { ins ->
                        FileOutputStream(target).use { os -> ins.copyTo(os) }
                    }
                    ok++
                } catch (_: Exception) {
                }
            }
            runOnUiThread {
                Toast.makeText(this, "Imported $ok book(s) into EbookReader/Books", Toast.LENGTH_LONG).show()
                refresh()
            }
        }.start()
    }

    // ------------------------------------------------ menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.library_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_refresh -> refresh()
            R.id.action_stats -> startActivity(Intent(this, StatsActivity::class.java))
            R.id.action_all_words -> {
                val any = Storage.listBooks().firstOrNull()?.absolutePath ?: ""
                if (Db.get(this).allWords().isEmpty()) {
                    Toast.makeText(
                        this, "No saved words yet. Tap a word while reading, then Save word.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    WordListUi.show(this, any)
                }
            }
            R.id.action_backup_export -> {
                Thread {
                    try {
                        val f = BackupManager.export(this)
                        runOnUiThread {
                            MaterialAlertDialogBuilder(this)
                                .setTitle("Backup created")
                                .setMessage(
                                    "Saved to EbookReader/Backups/${f.name}\n\n" +
                                        "Share it now to Google Drive or email so you can " +
                                        "restore your highlights, bookmarks and progress on " +
                                        "any device."
                                )
                                .setPositiveButton("Share") { _, _ ->
                                    startActivity(
                                        Intent.createChooser(
                                            BackupManager.shareIntent(this, f), "Share backup"
                                        )
                                    )
                                }
                                .setNegativeButton("Done", null)
                                .show()
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(this, "Backup failed: ${e.message}", Toast.LENGTH_LONG)
                                .show()
                        }
                    }
                }.start()
            }
            R.id.action_backup_import ->
                importBackup.launch(arrayOf("application/zip", "application/octet-stream"))
            R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }
}
