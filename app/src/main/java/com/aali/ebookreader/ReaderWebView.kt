package com.aali.ebookreader

import android.content.Context
import android.util.AttributeSet
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebView

/**
 * WebView that keeps Android's normal text selection (drag handles and all)
 * but replaces the floating toolbar items with the reader's own actions.
 *
 * Earlier versions blocked the action mode entirely, which on many phones
 * also cancelled the selection itself. That is why selecting text did not work.
 */
class ReaderWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : WebView(context, attrs) {

    /** Receives one of: highlight, note, define, speak, wordlist, copy */
    var onReaderAction: ((String) -> Unit)? = null

    /** Set false in screens where highlighting is not meaningful. */
    var allowHighlight = true

    private val items = listOf(
        1 to ("highlight" to "Highlight"),
        2 to ("note" to "Note"),
        3 to ("define" to "Define"),
        4 to ("wordlist" to "Word list"),
        5 to ("speak" to "Speak"),
        6 to ("copy" to "Copy")
    )

    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? =
        super.startActionMode(wrap(callback))

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? =
        super.startActionMode(wrap(callback), type)

    private fun wrap(inner: ActionMode.Callback?) = object : ActionMode.Callback {

        private fun fill(menu: Menu?) {
            menu ?: return
            menu.clear()
            for ((id, pair) in items) {
                if (pair.first == "highlight" && !allowHighlight) continue
                menu.add(Menu.NONE, id, id, pair.second)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
        }

        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            fill(menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            fill(menu)
            return true
        }

        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
            val key = items.firstOrNull { it.first == item?.itemId }?.second?.first
            if (key != null) {
                onReaderAction?.invoke(key)
                mode?.finish()
                return true
            }
            return inner?.onActionItemClicked(mode, item) ?: false
        }

        override fun onDestroyActionMode(mode: ActionMode?) {
            try {
                inner?.onDestroyActionMode(mode)
            } catch (_: Exception) {
            }
        }
    }
}
