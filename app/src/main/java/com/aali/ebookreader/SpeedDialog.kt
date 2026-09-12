package com.aali.ebookreader

import android.app.Activity

/** Reading speed picker for auto scroll, in words per minute. */
object SpeedDialog {

    private val presets = listOf(
        150 to "Slow · 150 wpm (careful reading)",
        200 to "Relaxed · 200 wpm",
        250 to "Average · 250 wpm (typical adult)",
        300 to "Brisk · 300 wpm",
        400 to "Fast · 400 wpm (fast reader)",
        500 to "Very fast · 500 wpm (skimming)"
    )

    fun show(activity: Activity, onChosen: (Int) -> Unit) {
        val current = Prefs.autoWpm(activity)
        val labels = presets.map { it.second }.toMutableList()
        labels.add("Custom speed…")
        val checked = presets.indexOfFirst { it.first == current }

        Ui.builder(activity)
            .setTitle("Reading speed")
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dlg, which ->
                dlg.dismiss()
                if (which < presets.size) {
                    val wpm = presets[which].first
                    Prefs.setAutoWpm(activity, wpm)
                    onChosen(wpm)
                } else {
                    val (edit, wrap) = Ui.inputBox(
                        activity, "Words per minute (60 - 900)",
                        current.toString(), numeric = true
                    )
                    Ui.builder(activity)
                        .setTitle("Custom speed")
                        .setView(wrap)
                        .setPositiveButton("Use this speed") { _, _ ->
                            val wpm = (edit.text.toString().toIntOrNull() ?: current)
                                .coerceIn(60, 900)
                            Prefs.setAutoWpm(activity, wpm)
                            onChosen(wpm)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
