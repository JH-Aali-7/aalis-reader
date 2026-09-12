package com.aali.ebookreader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Reading statistics: time per day, streak and totals. */
class StatsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        val db = Db.get(this)
        val totals = db.dailyTotals(30)
        val byDay = totals.toMap()

        val box = findViewById<LinearLayout>(R.id.content)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val today = todayKey()
        val todaySec = byDay[today] ?: 0L
        val streak = streakDays(byDay)
        val total = db.totalSeconds()

        // headline cards
        box.addView(statCard("Today", fmt(todaySec), "time spent reading"))
        box.addView(
            statCard(
                "Streak",
                if (streak == 0) "No streak yet" else "$streak day" + if (streak > 1) "s" else "",
                if (streak == 0) "read today to start one" else "days in a row"
            )
        )
        box.addView(statCard("All time", fmt(total), "since you installed the app"))

        // last 14 days chart
        box.addView(sectionTitle("LAST 14 DAYS"))
        val chartDays = ArrayList<Pair<String, Long>>()
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -13)
        val keyFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val labelFmt = SimpleDateFormat("EEE", Locale.US)
        repeat(14) {
            val key = keyFmt.format(cal.time)
            chartDays.add(labelFmt.format(cal.time).take(1) to (byDay[key] ?: 0L))
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        box.addView(BarChartView(this, chartDays).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(170)
            ).apply { topMargin = dp(4) }
        })

        // per book
        val perBook = db.secondsPerBook(8)
        if (perBook.isNotEmpty()) {
            box.addView(sectionTitle("TIME PER BOOK"))
            for ((path, secs) in perBook) {
                box.addView(rowLine(File(path).nameWithoutExtension, fmt(secs)))
            }
        }

        box.addView(sectionTitle("YOUR COLLECTION"))
        box.addView(rowLine("Books in library", Storage.listBooks().size.toString()))
        box.addView(rowLine("Highlights saved", db.countHighlights().toString()))
        box.addView(rowLine("Notes written", db.countNotes().toString()))
        box.addView(rowLine("Words in word list", db.countWords().toString()))
        box.addView(
            rowLine(
                "Days read",
                totals.count { it.second > 0 }.toString()
            )
        )
    }

    // ------------------------------------------------ pieces

    private fun statCard(label: String, big: String, sub: String): View {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val card = com.google.android.material.card.MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        col.addView(TextView(this).apply {
            text = label.uppercase()
            textSize = 11f
            letterSpacing = 0.08f
            setTextColor(themeColor(com.google.android.material.R.attr.colorPrimary))
        })
        col.addView(TextView(this).apply {
            text = big
            textSize = 27f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
        })
        col.addView(TextView(this).apply {
            text = sub
            textSize = 12f
            alpha = 0.7f
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
        })
        card.addView(col)
        return card
    }

    private fun sectionTitle(t: String): TextView {
        val d = resources.displayMetrics.density
        return TextView(this).apply {
            text = t
            textSize = 12f
            letterSpacing = 0.1f
            setPadding(0, (16 * d).toInt(), 0, (6 * d).toInt())
            setTextColor(themeColor(com.google.android.material.R.attr.colorPrimary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
    }

    private fun rowLine(left: String, right: String): View {
        val d = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (7 * d).toInt(), 0, (7 * d).toInt())
        }
        row.addView(TextView(this).apply {
            text = left
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply {
            text = right
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(themeColor(com.google.android.material.R.attr.colorPrimary))
        })
        return row
    }

    private fun themeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    // ------------------------------------------------ helpers

    private fun todayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** Consecutive days ending today (or yesterday) with any reading time. */
    private fun streakDays(byDay: Map<String, Long>): Int {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = Calendar.getInstance()
        if ((byDay[fmt.format(cal.time)] ?: 0L) <= 0L) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
            if ((byDay[fmt.format(cal.time)] ?: 0L) <= 0L) return 0
        }
        var count = 0
        while ((byDay[fmt.format(cal.time)] ?: 0L) > 0L) {
            count++
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return count
    }

    private fun fmt(seconds: Long): String {
        if (seconds <= 0) return "0 min"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            m > 0 -> "$m min"
            else -> "under a minute"
        }
    }

    /** Simple purple bar chart of daily reading minutes. */
    private class BarChartView(
        context: Context,
        private val data: List<Pair<String, Long>>
    ) : View(context) {

        private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        private val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#227C3AED")
        }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10f * context.resources.displayMetrics.density
            textAlign = Paint.Align.CENTER
            color = Color.parseColor("#8A7C3AED")
        }
        private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 9f * context.resources.displayMetrics.density
            textAlign = Paint.Align.CENTER
            color = Color.parseColor("#7C3AED")
        }

        override fun onDraw(canvas: Canvas) {
            if (data.isEmpty()) return
            val d = resources.displayMetrics.density
            val labelH = 18f * d
            val valueH = 13f * d
            val top = valueH
            val bottom = height - labelH
            val usable = bottom - top
            val slot = width.toFloat() / data.size
            val barW = slot * 0.56f
            val maxV = (data.maxOf { it.second }).coerceAtLeast(60L).toFloat()

            bar.shader = LinearGradient(
                0f, top, 0f, bottom,
                Color.parseColor("#9F72F0"), Color.parseColor("#5B21B6"),
                Shader.TileMode.CLAMP
            )

            for ((i, item) in data.withIndex()) {
                val cx = slot * i + slot / 2
                val minutes = item.second / 60f
                val frac = (item.second / maxV).coerceIn(0f, 1f)
                val h = usable * frac
                val r = RectF(cx - barW / 2, bottom - h, cx + barW / 2, bottom)
                if (item.second <= 0) {
                    val stub = RectF(cx - barW / 2, bottom - 3f * d, cx + barW / 2, bottom)
                    canvas.drawRoundRect(stub, 3f * d, 3f * d, empty)
                } else {
                    canvas.drawRoundRect(r, 5f * d, 5f * d, bar)
                    val text = if (minutes >= 60) "%.1fh".format(minutes / 60f)
                    else "${minutes.toInt()}m"
                    canvas.drawText(text, cx, (bottom - h - 4f * d), value)
                }
                canvas.drawText(item.first, cx, height - 5f * d, label)
            }
        }
    }
}
