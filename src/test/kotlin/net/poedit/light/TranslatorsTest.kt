package net.poedit.light

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class TranslatorsTest {
    @Test
    fun placeholdersAreWrappedAndSurviveTheRoundTrip() {
        val src = "Delete %1% files in {path} (%d of %s), \${home} & <b>%(name)s</b> 100% sure %1\$s"
        val p = protect(src)
        for (ph in listOf("%1%", "{path}", "%d", "%s", "\${home}", "%(name)s", "%1\$s"))
            assertEquals("<x>$ph</x>", Regex("<x>" + Regex.escape(ph) + "</x>").find(p)?.value)
        assertEquals(src, unprotect(p)) // what DeepL gives back keeps tags, we strip them again
        assertEquals("100% sure", unprotect(protect("100% sure"))) // a lone % is not a placeholder
    }

    @Test
    fun deepLLanguageCodes() {
        assertEquals("RU", deepLTarget("ru_RU"))
        assertEquals("PT-BR", deepLTarget("pt_BR"))
        assertEquals("PT-PT", deepLTarget("pt"))
        assertEquals("EN-GB", deepLTarget("en_GB"))
        assertEquals("EN-US", deepLTarget("en"))
        assertEquals("ZH", deepLTarget("zh_CN"))
        assertEquals("DE", deepLTarget("de"))
    }

    @Test
    fun parsesResponses() {
        assertEquals("Привет", parseDeepL("""{"translations":[{"detected_source_language":"EN","text":"Привет"}]}"""))
        assertEquals("It's <ok>", parseMyMemory("""{"responseData":{"translatedText":"It&#39;s &lt;ok&gt;","match":1},"responseStatus":200}"""))
        // quota errors carry the status in the body, MyMemory answers 200 at HTTP level
        try {
            parseMyMemory("""{"responseData":{"translatedText":"MYMEMORY WARNING: USED ALL"},"responseStatus":"429","responseDetails":"Limit reached"}""")
            fail()
        } catch (e: TranslationException) { assertEquals("MyMemory: Limit reached", e.message) }
        try { parseDeepL("{}"); fail() } catch (e: TranslationException) { assertEquals("Unexpected response from DeepL.", e.message) }
    }
}
