package com.aali.ebookreader

/**
 * AI answers often come back with LaTeX maths and markdown marks. Books and
 * summaries should read like plain prose, so this turns things such as
 * $\text{V}_2\text{O}_5$ into V₂O₅ and **bold** into real bold text.
 */
object TextFormat {

    /** Written as an escape so Kotlin never treats it as string interpolation. */
    private const val D = "$"

    private val subs = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
        '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
        '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
        'a' to 'ₐ', 'e' to 'ₑ', 'o' to 'ₒ', 'x' to 'ₓ', 'h' to 'ₕ',
        'k' to 'ₖ', 'l' to 'ₗ', 'm' to 'ₘ', 'n' to 'ₙ', 'p' to 'ₚ',
        's' to 'ₛ', 't' to 'ₜ', 'i' to 'ᵢ', 'j' to 'ⱼ', 'r' to 'ᵣ',
        'u' to 'ᵤ', 'v' to 'ᵥ'
    )

    private val sups = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
        '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
        '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
        'n' to 'ⁿ', 'i' to 'ⁱ'
    )

    private val greek = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
        "epsilon" to "ε", "zeta" to "ζ", "eta" to "η", "theta" to "θ",
        "kappa" to "κ", "lambda" to "λ", "mu" to "μ", "nu" to "ν",
        "pi" to "π", "rho" to "ρ", "sigma" to "σ", "tau" to "τ",
        "phi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
        "Delta" to "Δ", "Omega" to "Ω", "Sigma" to "Σ", "Phi" to "Φ",
        "times" to "×", "cdot" to "·", "pm" to "±", "approx" to "≈",
        "leq" to "≤", "geq" to "≥", "neq" to "≠", "rightarrow" to "→",
        "to" to "→", "infty" to "∞", "degree" to "°", "circ" to "°",
        "sim" to "~", "propto" to "∝", "partial" to "∂", "nabla" to "∇"
    )

    private fun mapRun(run: String, table: Map<Char, Char>): String? {
        if (run.isEmpty()) return null
        val sb = StringBuilder()
        for (c in run) {
            val m = table[c] ?: return null
            sb.append(m)
        }
        return sb.toString()
    }

    /** Removes LaTeX wrappers and converts what it can into real characters. */
    fun latexToPlain(input: String): String {
        var s = input

        // \text{...}, \mathrm{...} and friends: keep only the content
        val wrappers = listOf(
            "text", "mathrm", "mathbf", "mathit", "textbf", "textit",
            "mathsf", "mathtt", "operatorname", "bm", "boldsymbol"
        )
        for (w in wrappers) {
            var guard = 0
            while (s.contains("\\$w{") && guard++ < 60) {
                s = replaceBraceCommand(s, "\\$w{") { it }
            }
        }

        // \frac{a}{b} -> a/b
        var guard = 0
        while (s.contains("\\frac{") && guard++ < 40) {
            val i = s.indexOf("\\frac{")
            val first = readBraces(s, i + 5) ?: break
            val second = readBraces(s, first.second) ?: break
            s = s.substring(0, i) + "(" + first.first + "/" + second.first + ")" +
                s.substring(second.second)
        }

        // ^{...} and _{...}
        s = Regex("\\^\\{([^{}]{1,12})\\}").replace(s) { m ->
            mapRun(m.groupValues[1], sups) ?: ("^" + m.groupValues[1])
        }
        s = Regex("_\\{([^{}]{1,12})\\}").replace(s) { m ->
            mapRun(m.groupValues[1], subs) ?: ("_" + m.groupValues[1])
        }
        // single character ^2 or _2
        s = Regex("\\^([A-Za-z0-9+\\-])").replace(s) { m ->
            sups[m.groupValues[1][0]]?.toString() ?: ("^" + m.groupValues[1])
        }
        s = Regex("_([A-Za-z0-9+\\-])").replace(s) { m ->
            subs[m.groupValues[1][0]]?.toString() ?: ("_" + m.groupValues[1])
        }

        // greek letters and symbols
        for ((name, sym) in greek) {
            s = s.replace("\\$name", sym)
        }

        // spacing commands and leftovers
        s = s.replace("\\,", " ").replace("\\;", " ").replace("\\!", "")
            .replace("\\quad", "  ").replace("\\qquad", "   ")
            .replace("\\left", "").replace("\\right", "")
            .replace("\\%", "%").replace("\\" + D, D).replace("\\&", "&")

        // strip the maths delimiters
        s = s.replace("\\[", "").replace("\\]", "")
            .replace("\\(", "").replace("\\)", "")
        s = Regex("\\" + D + "\\" + D + "([\\s\\S]*?)\\" + D + "\\" + D)
            .replace(s) { it.groupValues[1] }
        s = Regex("\\" + D + "([^" + D + "\\n]{1,400}?)\\" + D)
            .replace(s) { it.groupValues[1] }

        // any remaining braces around plain runs
        s = Regex("\\{([^{}]{0,80})\\}").replace(s) { it.groupValues[1] }

        return s.replace(Regex("[ \\t]{2,}"), " ")
    }

    private fun readBraces(s: String, openIdx: Int): Pair<String, Int>? {
        if (openIdx >= s.length || s[openIdx] != '{') return null
        var depth = 0
        for (i in openIdx until s.length) {
            when (s[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return s.substring(openIdx + 1, i) to (i + 1)
                }
            }
        }
        return null
    }

    private fun replaceBraceCommand(
        s: String,
        prefix: String,
        transform: (String) -> String
    ): String {
        val i = s.indexOf(prefix)
        if (i < 0) return s
        val open = i + prefix.length - 1
        val body = readBraces(s, open) ?: return s.replaceFirst(prefix, "")
        return s.substring(0, i) + transform(body.first) + s.substring(body.second)
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Turns a markdown style AI answer into tidy HTML for the reader view. */
    fun markdownToHtml(input: String): String {
        val clean = latexToPlain(input)
        val out = StringBuilder()
        var inList = false

        fun closeList() {
            if (inList) {
                out.append("</ul>\n")
                inList = false
            }
        }

        for (rawLine in clean.lines()) {
            val line = rawLine.trimEnd()
            val t = line.trim()

            if (t.isEmpty()) {
                closeList()
                continue
            }
            // headings
            val h = Regex("^(#{1,4})\\s+(.*)$").find(t)
            if (h != null) {
                closeList()
                val level = h.groupValues[1].length + 1
                out.append("<h$level>").append(inline(h.groupValues[2])).append("</h$level>\n")
                continue
            }
            // horizontal rule
            if (t.matches(Regex("^([-*_])\\1{2,}$"))) {
                closeList()
                out.append("<hr/>\n")
                continue
            }
            // bullets: -, *, • (but not **bold** at line start)
            val bullet = Regex("^([-•]|\\*(?!\\*))\\s+(.*)$").find(t)
            if (bullet != null) {
                if (!inList) {
                    out.append("<ul>\n")
                    inList = true
                }
                out.append("<li>").append(inline(bullet.groupValues[2])).append("</li>\n")
                continue
            }
            // numbered list stays as a paragraph so the numbers are preserved
            closeList()
            out.append("<p>").append(inline(t)).append("</p>\n")
        }
        closeList()
        return out.toString()
    }

    /** Bold, italic and inline code inside one line. */
    private fun inline(text: String): String {
        var s = esc(text)
        s = Regex("\\*\\*\\*(.+?)\\*\\*\\*").replace(s) { "<b><i>${it.groupValues[1]}</i></b>" }
        s = Regex("\\*\\*(.+?)\\*\\*").replace(s) { "<b>${it.groupValues[1]}</b>" }
        s = Regex("(?<![\\w*])\\*([^*\\n]+?)\\*(?![\\w*])").replace(s) {
            "<i>${it.groupValues[1]}</i>"
        }
        s = Regex("__(.+?)__").replace(s) { "<b>${it.groupValues[1]}</b>" }
        s = Regex("`([^`]+)`").replace(s) { "<code>${it.groupValues[1]}</code>" }
        return s
    }

    /** Same content as plain text, for saving to a txt file or sharing. */
    fun markdownToPlain(input: String): String {
        var s = latexToPlain(input)
        s = Regex("^#{1,6}\\s*", RegexOption.MULTILINE).replace(s, "")
        s = Regex("\\*\\*(.+?)\\*\\*").replace(s) { it.groupValues[1] }
        s = Regex("(?<![\\w*])\\*([^*\\n]+?)\\*(?![\\w*])").replace(s) { it.groupValues[1] }
        s = Regex("__(.+?)__").replace(s) { it.groupValues[1] }
        s = Regex("^\\s*[-•]\\s+", RegexOption.MULTILINE).replace(s, "  • ")
        s = s.replace("`", "")
        return s.trim()
    }
}
