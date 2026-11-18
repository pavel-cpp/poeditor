package io.github.pavelcpp.poeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PoActionsTest {
    private val xml = javaClass.classLoader.getResource("META-INF/plugin.xml")!!.readText()

    @Test
    fun pluginXmlMatchesActionList() {
        val actions = Regex("""<action id="([^"]+)" class="([^"]+)"""").findAll(xml).toList()
        assertEquals(PO_ACTION_IDS.sorted(), actions.map { it.groupValues[1] }.sorted())
        // every declared class exists and is a PoAction (no initialization, so no IDE needed)
        actions.forEach {
            val c = Class.forName(it.groupValues[2], false, javaClass.classLoader)
            assertTrue("${c.name} must extend PoAction", PoAction::class.java.isAssignableFrom(c))
        }
        // each action has a default shortcut, like in Poedit
        assertEquals(PO_ACTION_IDS.size, Regex("""<keyboard-shortcut keymap="\${'$'}default"""").findAll(xml).count())
    }
}
