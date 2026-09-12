package com.aali.ebookreader

import android.app.Activity
import android.widget.Toast

/** Dialogs for adding, listing, editing and deleting notes (comments). */
object NotesUi {

    fun showAddNote(activity: Activity, refText: String, onSave: (String) -> Unit) {
        val (edit, wrap) = Ui.inputBox(
            activity, "Write your note or comment", lines = 4
        )
        val title = if (refText.isEmpty()) "Add note"
        else "Note on: \"${refText.take(60)}\""
        Ui.builder(activity)
            .setTitle(title)
            .setView(wrap)
            .setPositiveButton("Save note") { _, _ ->
                val t = edit.text.toString().trim()
                if (t.isNotEmpty()) onSave(t)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showNotesList(
        activity: Activity,
        bookPath: String,
        isPdf: Boolean,
        onJump: (Note) -> Unit
    ) {
        val db = Db.get(activity)
        val list = db.notesFor(bookPath)
        if (list.isEmpty()) {
            Toast.makeText(
                activity,
                "No notes yet. Select text and choose Note, or use Add note here.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val labels = list.map { n ->
            val where = if (isPdf) "[p${n.page}]" else "[Ch ${n.chapter + 1} · p${n.page}]"
            "$where ${n.text.take(70)}"
        }.toTypedArray()
        Ui.builder(activity)
            .setTitle("Notes (${list.size})")
            .setItems(labels) { _, which ->
                val n = list[which]
                Ui.builder(activity)
                    .setTitle("Note")
                    .setMessage(
                        (if (n.refText.isNotEmpty()) "About: \"${n.refText}\"\n\n" else "") + n.text
                    )
                    .setPositiveButton("Go there") { _, _ -> onJump(n) }
                    .setNeutralButton("Edit") { _, _ ->
                        val (edit, wrap) = Ui.inputBox(activity, "Note", n.text, lines = 4)
                        Ui.builder(activity)
                            .setTitle("Edit note")
                            .setView(wrap)
                            .setPositiveButton("Save") { _, _ ->
                                db.updateNote(n.id, bookPath, edit.text.toString())
                                Toast.makeText(
                                    activity, "Note updated (txt file updated)",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    .setNegativeButton("Delete") { _, _ ->
                        db.deleteNote(n.id, bookPath)
                        Toast.makeText(
                            activity, "Note deleted (txt file updated)", Toast.LENGTH_SHORT
                        ).show()
                    }
                    .show()
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
