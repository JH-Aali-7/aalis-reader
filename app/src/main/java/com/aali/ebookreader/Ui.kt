package com.aali.ebookreader

import android.app.Activity
import android.content.Context
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Small shared helpers so every dialog looks the same and stays readable. */
object Ui {

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()

    /** An EditText with proper dialog padding around it. */
    fun inputBox(
        c: Context,
        hint: String,
        preset: String = "",
        numeric: Boolean = false,
        lines: Int = 1
    ): Pair<EditText, View> {
        val edit = EditText(c).apply {
            this.hint = hint
            setText(preset)
            setSelection(preset.length)
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
            if (lines > 1) {
                minLines = lines
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
        }
        val wrap = FrameLayout(c).apply {
            setPadding(dp(c, 24), dp(c, 8), dp(c, 24), 0)
            addView(
                edit,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        return edit to wrap
    }

    fun toast(activity: Activity, msg: String) {
        android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    fun sectionLabel(c: Context, text: String): TextView = TextView(c).apply {
        this.text = text
        textSize = 13f
        setPadding(0, dp(c, 10), 0, dp(c, 2))
        alpha = 0.75f
    }

    fun verticalBox(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(c, 24), dp(c, 6), dp(c, 24), 0)
    }

    fun builder(activity: Activity): MaterialAlertDialogBuilder =
        MaterialAlertDialogBuilder(activity)
}
