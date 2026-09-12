package com.aali.ebookreader

import android.app.Activity
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Search box that remembers what was searched in this book (and shows recent
 * searches from other books too), so a word can be looked up again in one tap.
 */
object SearchUi {

    fun show(activity: Activity, bookPath: String, onSearch: (String) -> Unit) {
        val db = Db.get(activity)
        val history = db.searchHistory(bookPath)
        val others = db.searchHistoryAll()
            .filter { it.second != bookPath }
            .map { it.first }
            .distinct()
            .filter { it !in history }
            .take(8)

        val (edit, editWrap) = Ui.inputBox(activity, "Word or phrase to find")
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        col.addView(editWrap)

        val listCol = Ui.verticalBox(activity)
        val dialog = Ui.builder(activity)
            .setTitle("Search in book")
            .setView(ScrollView(activity).apply {
                addView(col.also { it.addView(listCol) })
                isVerticalScrollBarEnabled = true
            })
            .setPositiveButton("Search", null)
            .setNegativeButton("Cancel", null)
            .apply {
                if (history.isNotEmpty()) {
                    setNeutralButton("Clear history") { _, _ ->
                        db.clearSearchHistory(bookPath)
                        Ui.toast(activity, "Search history cleared for this book")
                    }
                }
            }
            .create()

        fun addRow(text: String, dim: Boolean) {
            listCol.addView(TextView(activity).apply {
                this.text = text
                textSize = 15f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Ui.dp(activity, 4), Ui.dp(activity, 11),
                    Ui.dp(activity, 4), Ui.dp(activity, 11))
                if (dim) alpha = 0.75f
                isClickable = true
                val outValue = android.util.TypedValue()
                activity.theme.resolveAttribute(
                    android.R.attr.selectableItemBackground, outValue, true
                )
                setBackgroundResource(outValue.resourceId)
                setOnClickListener {
                    dialog.dismiss()
                    db.addSearch(bookPath, text)
                    onSearch(text)
                }
            })
        }

        if (history.isNotEmpty()) {
            listCol.addView(Ui.sectionLabel(activity, "RECENT IN THIS BOOK"))
            history.forEach { addRow(it, false) }
        }
        if (others.isNotEmpty()) {
            listCol.addView(Ui.sectionLabel(activity, "FROM OTHER BOOKS"))
            others.forEach { addRow(it, true) }
        }
        if (history.isEmpty() && others.isEmpty()) {
            listCol.addView(Ui.sectionLabel(activity, "Your searches will be saved here"))
        }

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val q = edit.text.toString().trim()
                if (q.length < 2) {
                    Ui.toast(activity, "Type at least 2 letters")
                } else {
                    dialog.dismiss()
                    db.addSearch(bookPath, q)
                    onSearch(q)
                }
            }
        }
        dialog.show()
    }
}
