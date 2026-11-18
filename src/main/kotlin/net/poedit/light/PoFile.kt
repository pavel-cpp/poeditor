package net.poedit.light

/** One PO entry. [start]/[end] are its offsets in the document text, so edits can be spliced in place. */
class PoEntry {
    var ctx: String? = null
    var id = ""
    var plural: String? = null
    var strs = mutableListOf("")
    var comment = ""                       // translator comment (`# ...`), without the "# " prefix
    val extracted = mutableListOf<String>() // `#. ...`
    val refs = mutableListOf<String>()      // `#: ...`
    val flags = mutableListOf<String>()     // `#, fuzzy, c-format`
    val prev = mutableListOf<String>()      // `#| ...` (old msgid of a fuzzy match)
    var start = 0
    var end = 0

    var fuzzy: Boolean
        get() = "fuzzy" in flags
        set(v) {
            if (v == fuzzy) return
            if (v) flags.add(0, "fuzzy") else { flags.remove("fuzzy"); prev.clear() } // same as Poedit's CatalogItem::SetFuzzy
        }

    fun translated(nplurals: Int) =
        (0 until if (plural != null) maxOf(nplurals, 1) else 1).all { strs.getOrElse(it) { "" }.isNotEmpty() }

    /** 0 = translated, 1 = needs work, 2 = untranslated. */
    fun state(nplurals: Int) = if (fuzzy) 1 else if (translated(nplurals)) 0 else 2

    fun render(nplurals: Int): String = buildString {
        if (comment.isNotEmpty()) comment.split('\n').forEach { append(if (it.isEmpty()) "#" else "# $it").append('\n') }
        extracted.forEach { append(if (it.isEmpty()) "#." else "#. $it").append('\n') }
        refs.forEach { append("#: $it\n") }
        if (flags.isNotEmpty()) append("#, ").append(flags.joinToString(", ")).append('\n')
        prev.forEach { append("#| $it\n") }
        ctx?.let { append(field("msgctxt", it)) }
        append(field("msgid", id))
        if (plural != null) {
            append(field("msgid_plural", plural!!))
            for (i in 0 until maxOf(nplurals, strs.size)) append(field("msgstr[$i]", strs.getOrElse(i) { "" }))
        } else append(field("msgstr", strs.firstOrNull().orEmpty()))
    }.trimEnd('\n')
}

class PoFile(val entries: List<PoEntry>, val nplurals: Int, val language: String)

fun parsePo(text: String): PoFile {
    val entries = mutableListOf<PoEntry>()
    var nplurals = 0
    var lang = ""
    val lines = mutableListOf<String>()
    var bStart = 0
    var bEnd = 0
    fun flush() {
        if (lines.isEmpty()) return
        parseBlock(lines)?.let { e ->
            e.start = bStart; e.end = bEnd
            if (e.id.isEmpty() && e.ctx == null) { // header
                val h = e.strs.joinToString("")
                nplurals = Regex("""nplurals\s*=\s*(\d+)""").find(h)?.groupValues?.get(1)?.toInt() ?: 0
                lang = Regex("""(?m)^Language:\s*(\S+)""").find(h)?.groupValues?.get(1).orEmpty()
            } else entries += e
        }
        lines.clear()
    }
    var i = 0
    while (i < text.length) {
        val nl = text.indexOf('\n', i).let { if (it < 0) text.length else it }
        val l = text.substring(i, nl).trimEnd('\r')
        if (l.isBlank()) flush() else {
            if (lines.isEmpty()) bStart = i
            lines += l
            bEnd = i + l.length
        }
        i = nl + 1
    }
    flush()
    return PoFile(entries, nplurals, lang)
}

private val KW = Regex("""^(msgctxt|msgid_plural|msgid|msgstr)(?:\[(\d+)])?\s*"(.*)"\s*$""")

/** Returns null for obsolete (`#~`) blocks and blocks without msgid. ponytail: assumes entries are separated by blank lines. */
private fun parseBlock(lines: List<String>): PoEntry? {
    if (lines.any { it.startsWith("#~") }) return null
    val e = PoEntry()
    val comment = mutableListOf<String>()
    var sink: ((String) -> Unit)? = null
    var sawId = false
    var sawStr = false
    for (l in lines) when {
        l.startsWith("#.") -> e.extracted += l.drop(2).removePrefix(" ")
        l.startsWith("#:") -> e.refs += l.drop(2).trim()
        l.startsWith("#,") -> e.flags += l.drop(2).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        l.startsWith("#|") -> e.prev += l.drop(2).removePrefix(" ")
        l.startsWith("#") -> comment += l.drop(1).removePrefix(" ")
        l.trimStart().startsWith("\"") -> sink?.invoke(unescape(l.trim().removeSurrounding("\"")))
        else -> KW.matchEntire(l)?.let { m ->
            val v = unescape(m.groupValues[3])
            when (m.groupValues[1]) {
                "msgctxt" -> { e.ctx = v; sink = { s -> e.ctx = e.ctx + s } }
                "msgid" -> { sawId = true; e.id = v; sink = { s -> e.id += s } }
                "msgid_plural" -> { e.plural = v; sink = { s -> e.plural += s } }
                else -> {
                    val idx = m.groupValues[2].ifEmpty { "0" }.toInt()
                    if (!sawStr) { e.strs.clear(); sawStr = true }
                    while (e.strs.size <= idx) e.strs.add("")
                    e.strs[idx] = v
                    sink = { s -> e.strs[idx] += s }
                }
            }
        }
    }
    if (!sawId) return null
    if (e.strs.isEmpty()) e.strs.add("")
    e.comment = comment.joinToString("\n")
    return e
}

/** gettext style: single line, or `""` followed by one line per `\n`-terminated piece. */
private fun field(name: String, v: String): String {
    val pieces = v.split(Regex("(?<=\n)"))
    if (pieces.size == 1) return "$name \"${escape(v)}\"\n"
    return "$name \"\"\n" + pieces.joinToString("") { "\"${escape(it)}\"\n" }
}

fun escape(s: String) = buildString {
    for (c in s) when (c) {
        '\\' -> append("\\\\"); '"' -> append("\\\""); '\n' -> append("\\n"); '\t' -> append("\\t"); '\r' -> append("\\r")
        else -> append(c)
    }
}

fun unescape(s: String) = buildString {
    var i = 0
    while (i < s.length) {
        val c = s[i++]
        if (c != '\\' || i >= s.length) { append(c); continue }
        when (val n = s[i++]) {
            'n' -> append('\n'); 't' -> append('\t'); 'r' -> append('\r'); '"' -> append('"'); '\\' -> append('\\')
            else -> { append('\\'); append(n) }
        }
    }
}
