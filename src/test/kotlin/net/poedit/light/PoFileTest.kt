package net.poedit.light

import org.junit.Assert.*
import org.junit.Test

class PoFileTest {
    private val po = """
# Translator header
msgid ""
msgstr ""
"Language: ru\n"
"Plural-Forms: nplurals=3; plural=(n%10==1 ? 0 : 1);\n"

#. Shown on button
#: src/a.c:12 src/b.c:7
#, fuzzy, c-format
#| msgid "Old"
msgctxt "menu"
msgid "Open %d"
msgstr "Открыть %d"

msgid "Multi"
msgstr ""
"line1\n"
"line2 \"q\""

msgid "File"
msgid_plural "Files"
msgstr[0] "Файл"
msgstr[1] ""

#~ msgid "Gone"
#~ msgstr "Ушло"
""".trimStart()

    @Test
    fun parseAndSplice() {
        val f = parsePo(po)
        assertEquals(3, f.nplurals); assertEquals("ru", f.language)
        assertEquals(3, f.entries.size) // header and obsolete skipped
        val (a, b, c) = f.entries
        assertEquals("menu", a.ctx); assertTrue(a.fuzzy); assertEquals(listOf("fuzzy", "c-format"), a.flags)
        assertEquals("line1\nline2 \"q\"", b.strs[0]); assertEquals(0, b.state(3))
        assertEquals("Files", c.plural); assertFalse(c.translated(3))

        // unchanged entry round-trips byte for byte
        assertEquals(po.substring(a.start, a.end), a.render(3))
        assertEquals(po.substring(b.start, b.end), b.render(3))

        // unfuzzy drops old msgid, like Poedit
        a.fuzzy = false
        assertEquals(0, a.state(3)); assertTrue(a.prev.isEmpty()); assertFalse(a.render(3).contains("fuzzy"))

        // plural rendering pads to nplurals
        assertEquals(3, Regex("msgstr\\[").findAll(c.render(3)).count())
    }
}
