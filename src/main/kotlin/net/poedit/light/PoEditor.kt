package net.poedit.light

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.*
import com.intellij.ui.components.*
import com.intellij.ui.table.JBTable
import com.intellij.util.Alarm
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.ActionEvent
import java.beans.PropertyChangeListener
import java.io.File
import javax.swing.*
import javax.swing.event.HyperlinkEvent
import javax.swing.table.AbstractTableModel

object PoFileType : LanguageFileType(PlainTextLanguage.INSTANCE) {
    override fun getName() = "PO"
    override fun getDescription() = "Gettext translation catalog"
    override fun getDefaultExtension() = "po"
    override fun getIcon(): Icon = IconLoader.getIcon("/icons/po.svg", PoFileType::class.java)
}

private const val EDITOR_ID = "poedit-light"

class PoEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file.extension.equals("po", true)
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = PoEditor(project, file)
    override fun getEditorTypeId() = EDITOR_ID
    override fun getPolicy() = FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR
}

/** Makes the Poedit tab the selected one whenever a .po file is opened (the plain-text tab stays available). */
class PoOpenListener : FileEditorManagerListener {
    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        if (file.extension.equals("po", true)) source.setSelectedEditor(file, EDITOR_ID)
    }
}

/** Small status dot for the list: blue = untranslated, orange = needs work (like Poedit). */
private class Dot(private val color: Color) : Icon {
    override fun getIconWidth() = JBUI.scale(8)
    override fun getIconHeight() = JBUI.scale(8)
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            g2.fillOval(x, y, iconWidth, iconHeight)
        } finally { g2.dispose() }
    }
}

private val BLUE = JBColor(0x3D7EFF, 0x5A91FF)
private val ORANGE = JBColor(0xE08A00, 0xD9A343)

/**
 * Poedit-style catalog editor: entry list on top, source/translation pane below.
 * All edits are spliced into the IDE [Document] entry by entry, so Undo, VCS and the text tab keep working.
 */
class PoEditor(private val project: Project, private val vfile: VirtualFile) : UserDataHolderBase(), FileEditor {
    private val doc = FileDocumentManager.getInstance().getDocument(vfile)!!
    private var po = parsePo(runReadActionBlocking { doc.text })
    private var shown = emptyList<PoEntry>()
    private var current: PoEntry? = null
    private var loading = false          // filling widgets from an entry: ignore their change events
    private var writing = false          // our own document write: don't re-parse
    private var userToggledFuzzy = false // like Poedit's m_dontAutoclearFuzzyStatus
    private var formCount = 1            // translation forms shown for the current entry (the "Comment" tab comes after them)
    private val history = ArrayDeque<Pair<String?, String>>() // entries edited by hand, for "Previously Edited"

    private val search = SearchTextField()
    private val filter = ComboBox(arrayOf("All", "Needs work", "Untranslated", "Translated"))
    private val stats = JBLabel("", UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER)

    private val model = object : AbstractTableModel() {
        override fun getRowCount() = shown.size
        override fun getColumnCount() = 3
        override fun getColumnName(c: Int) = arrayOf("", "Source text", "Translation")[c]
        override fun getValueAt(r: Int, c: Int): Any = shown[r].let {
            when (c) {
                1 -> it.id.replace("\n", "↵")
                2 -> it.strs.firstOrNull().orEmpty().replace("\n", "↵")
                else -> ""
            }
        }
    }
    private val table = JBTable(model)

    private val source = readOnly()
    private val sourcePlural = readOnly()
    private val notes = readOnly().apply { foreground = UIUtil.getContextHelpForeground() }
    private val tabs = JBTabbedPane()       // translation forms + a "Comment" tab
    private val areas = mutableListOf<JBTextArea>()
    private val needsWork = JBCheckBox("Needs work")
    private val comment = editable()
    private val refsBar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply { isOpaque = false }

    private val sourceBox = captioned("SOURCE TEXT", flat(source))
    private val pluralBox = captioned("SOURCE TEXT (PLURAL)", flat(sourcePlural))
    private val notesBox = captioned("NOTES FOR TRANSLATORS", flat(notes))
    private val detail = FormBuilder.createFormBuilder()
        .addComponent(sourceBox)
        .addComponent(pluralBox)
        .addComponent(notesBox)
        .addComponentFillVertically(tabs, 0) // the translation gets all the spare height
        .panel.apply { border = JBUI.Borders.empty(8, 12, 4, 12) }
    // everything below the list: the form plus one thin line ("Needs work" on the left, source references next to it)
    private val bottom = JPanel(BorderLayout()).apply {
        add(JBScrollPane(detail).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        add(JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0), JBUI.Borders.empty(4, 12))
            add(needsWork, BorderLayout.WEST)
            add(refsBar, BorderLayout.CENTER)
        }, BorderLayout.SOUTH)
    }

    // 1px divider with a visible line = the top edge of the bottom panel; the size is remembered between sessions
    private val root = OnePixelSplitter(true, "poeditor.splitter", 0.66f)
    private val reloadAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    init {
        // ---- list ----
        table.setShowGrid(false)
        table.intercellSpacing = Dimension(0, 0)
        table.rowHeight = JBUI.scale(26)
        table.tableHeader.reorderingAllowed = false
        table.emptyText.text = "No matching strings"
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.columnModel.getColumn(0).apply { minWidth = JBUI.scale(30); maxWidth = JBUI.scale(30) }
        table.setDefaultRenderer(Any::class.java, object : ColoredTableCellRenderer() {
            override fun customizeCellRenderer(t: JTable, value: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int) {
                val e = shown.getOrNull(row) ?: return
                val state = e.state(po.nplurals)
                ipad = JBUI.insets(0, 8)
                if (col == 0) { if (state != 0) icon = Dot(if (state == 1) ORANGE else BLUE); return }
                append(value as? String ?: "", when (state) {
                    1 -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, ORANGE)
                    2 -> SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                    else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
                })
            }
        })
        table.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) show(shown.getOrNull(table.selectedRow)) }
        search.textEditor.emptyText.setText("Search strings")
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) = applyFilter()
        })
        filter.addActionListener { applyFilter() }

        // ---- detail ----
        comment.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                val en = current ?: return
                if (loading || en.comment == comment.text) return
                en.comment = comment.text
                commit(en)
            }
        })
        needsWork.addActionListener {
            val en = current ?: return@addActionListener
            userToggledFuzzy = true
            en.fuzzy = needsWork.isSelected
            commit(en)
            refreshRow()
        }

        // ---- layout ----
        val top = JPanel(BorderLayout()).apply {
            add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                border = JBUI.Borders.empty(6, 8)
                add(search, BorderLayout.CENTER)
                add(filter, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(JBScrollPane(table).apply { border = JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0) }, BorderLayout.CENTER)
            add(stats.apply { border = JBUI.Borders.empty(4, 12) }, BorderLayout.SOUTH) // no own line: the divider below is the only edge
        }
        root.firstComponent = top
        root.secondComponent = bottom

        // Poedit's shortcuts: the actions live in plugin.xml (so they show up in Settings | Keymap) and are attached
        // to this component with the *active keymap's* shortcuts, which makes them win over IDE actions like Ctrl+K = Commit.
        ActionManager.getInstance().let { am ->
            PO_ACTION_IDS.forEach { id -> am.getAction(id)?.registerCustomShortcutSet(KeymapUtil.getActiveKeymapShortcuts(id), root, this) }
        }

        doc.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (!writing) reloadAlarm.cancelAllRequests().also { reloadAlarm.addRequest({ reload() }, 200) }
            }
        }, this)

        applyFilter()
    }

    // ------------------------------------------------------------------ model <-> document

    /** Writes [e] back into the document text, in place (keeps the rest of the file byte-identical). */
    private fun commit(e: PoEntry) {
        val text = e.render(po.nplurals)
        if (!FileDocumentManager.getInstance().requestWriting(doc, project)) return
        val old = e.end - e.start
        writing = true
        try {
            WriteCommandAction.runWriteCommandAction(project, "Edit Translation", "poedit-light", Runnable {
                doc.replaceString(e.start, e.end, text)
            })
        } finally { writing = false }
        val delta = text.length - old
        e.end += delta
        for (x in po.entries) if (x !== e && x.start > e.start) { x.start += delta; x.end += delta }
        updateStats()
    }

    private fun reload() {
        val key = current?.let { it.ctx to it.id }
        po = parsePo(runReadActionBlocking { doc.text })
        current = null
        applyFilter(key)
    }

    // ------------------------------------------------------------------ list

    private fun applyFilter(keepKey: Pair<String?, String>? = current?.let { it.ctx to it.id }) {
        val q = search.text.trim().lowercase()
        shown = po.entries.filter { e ->
            val st = e.state(po.nplurals)
            val byState = when (filter.selectedIndex) { 1 -> st == 1; 2 -> st == 2; 3 -> st == 0; else -> true }
            byState && (q.isEmpty() || e.id.lowercase().contains(q) || e.ctx.orEmpty().lowercase().contains(q) ||
                e.strs.any { it.lowercase().contains(q) } || e.comment.lowercase().contains(q))
        }
        model.fireTableDataChanged()
        val row = if (keepKey == null) 0 else shown.indexOfFirst { (it.ctx to it.id) == keepKey }
        if (row in shown.indices) {
            table.setRowSelectionInterval(row, row)
            table.scrollRectToVisible(table.getCellRect(row, 0, true))
        } else show(null)
        updateStats()
    }

    private fun refreshRow() {
        val r = shown.indexOf(current)
        if (r >= 0) model.fireTableRowsUpdated(r, r)
        updateStats()
    }

    private fun updateStats() {
        val s = po.entries.groupingBy { it.state(po.nplurals) }.eachCount()
        stats.text = "${po.entries.size} strings  ·  ${s[0] ?: 0} translated  ·  ${s[1] ?: 0} need work  ·  ${s[2] ?: 0} untranslated" +
            (if (po.language.isNotEmpty()) "  ·  ${po.language}" else "")
        table.repaint()
    }

    // ------------------------------------------------------------------ actions (see PoActions.kt for the shortcuts)

    private fun key(e: PoEntry) = e.ctx to e.id

    private fun select(row: Int, focus: Boolean = true) {
        table.setRowSelectionInterval(row, row)
        table.scrollRectToVisible(table.getCellRect(row, 0, true))
        if (focus) areas.firstOrNull()?.requestFocusInWindow()
    }

    /** Moves the selection by [delta] entries, optionally only onto unfinished ones, optionally wrapping around. False if nothing was found. */
    fun navigate(delta: Int, unfinished: Boolean, wrap: Boolean = false): Boolean {
        val n = shown.size
        val start = table.selectedRow
        var r = start
        repeat(n) {
            r += delta
            if (r !in 0 until n) { if (!wrap) return false; r = (r + n) % n }
            if (r == start) return false
            if (!unfinished || shown[r].state(po.nplurals) != 0) { select(r); return true }
        }
        return false
    }

    fun page(dir: Int) {
        if (shown.isEmpty()) return
        val rows = maxOf(1, table.visibleRect.height / table.rowHeight - 1)
        select((table.selectedRow.coerceAtLeast(0) + dir * rows).coerceIn(0, shown.size - 1), focus = false)
    }

    /** Poedit: an approved entry loses "needs work" (unless it was just set), then jump to the next unfinished one, wrapping. */
    fun doneAndNext() {
        val e = current ?: return
        if (e.fuzzy && !userToggledFuzzy) {
            e.fuzzy = false
            needsWork.isSelected = false
            commit(e)
            refreshRow()
        }
        recordEdit(e)
        navigate(1, unfinished = true, wrap = true)
    }

    fun previouslyEdited() {
        val here = current?.let(::key)
        while (history.isNotEmpty()) {
            val k = history.removeLast()
            if (k == here) continue
            val r = shown.indexOfFirst { key(it) == k }
            if (r >= 0) { select(r); return }
        }
    }

    private fun recordEdit(e: PoEntry) {
        if (history.lastOrNull() != key(e)) history.addLast(key(e))
        if (history.size > 50) history.removeFirst()
    }

    fun pluralForm(delta: Int) {
        if (current == null) return
        val i = (tabs.selectedIndex + delta).coerceIn(0, formCount - 1)
        tabs.selectedIndex = i
        areas[i].requestFocusInWindow()
    }

    /** Sets the forms through the text areas, so the normal edit path (document write, fuzzy auto-clear) applies. */
    private fun setForms(texts: List<String>) {
        texts.forEachIndexed { i, t -> areas.getOrNull(i)?.text = t }
        areas.firstOrNull()?.requestFocusInWindow()
    }

    fun clearTranslation() { if (current != null) setForms(List(formCount) { "" }) }

    /** Poedit: form 0 from the singular, the rest from the plural; [singularOnly] puts the singular into every form. */
    fun copyFromSource(singularOnly: Boolean) {
        val e = current ?: return
        setForms(List(formCount) { i -> if (i > 0 && !singularOnly) e.plural ?: e.id else e.id })
    }

    fun toggleNeedsWork() { if (current != null) needsWork.doClick() }

    fun editComment() {
        if (current == null) return
        tabs.selectedIndex = tabs.tabCount - 1
        comment.requestFocusInWindow()
    }

    fun focusSearch() {
        search.textEditor.requestFocusInWindow()
        search.textEditor.selectAll()
    }

    // ------------------------------------------------------------------ detail

    private fun show(e: PoEntry?) {
        current = e
        loading = true
        try {
            bottom.isVisible = e != null
            if (e == null) return
            userToggledFuzzy = false
            fill(source, e.id)
            pluralBox.isVisible = e.plural != null
            fill(sourcePlural, e.plural.orEmpty())
            val note = (listOfNotNull(e.ctx?.let { "Context: $it" }) + e.extracted).joinToString("\n")
            fill(notes, note)
            notesBox.isVisible = note.isNotEmpty()

            val rs = e.refs.flatMap { it.split(Regex("\\s+")) }.filter { it.isNotEmpty() }
            refsBar.removeAll()
            if (rs.isNotEmpty()) refsBar.add(JBLabel("Found in:", UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER))
            rs.take(3).forEach { r -> refsBar.add(link(r.trim().substringAfterLast('/').substringAfterLast('\\'), r) { openRef(r) }) }
            if (rs.size > 3) refsBar.add(link("+${rs.size - 3} more", null) { l ->
                JBPopupFactory.getInstance().createPopupChooserBuilder(rs).setItemChosenCallback { openRef(it) }
                    .createPopup().showUnderneathOf(l)
            })
            refsBar.revalidate()
            refsBar.repaint()

            val n = if (e.plural != null) maxOf(po.nplurals, e.strs.size, 1) else 1
            formCount = n
            tabs.removeAll()
            for (i in 0 until n) {
                val a = areaAt(i)
                a.text = e.strs.getOrElse(i) { "" }
                a.caretPosition = 0
                tabs.addTab(if (e.plural != null) "Form $i" else "Translation", JBScrollPane(a))
            }
            comment.text = e.comment
            comment.caretPosition = 0
            tabs.addTab(if (e.comment.isEmpty()) "Comment" else "Comment •", JBScrollPane(comment))
            needsWork.isSelected = e.fuzzy
            bottom.revalidate()
        } finally { loading = false }
    }

    private fun areaAt(i: Int): JBTextArea {
        while (areas.size <= i) {
            val idx = areas.size
            areas += editable().apply {
                document.addDocumentListener(object : DocumentAdapter() {
                    override fun textChanged(e: javax.swing.event.DocumentEvent) = onTranslation(idx, text)
                })
            }
        }
        return areas[i]
    }

    private fun onTranslation(i: Int, text: String) {
        val e = current ?: return
        if (loading || e.strs.getOrElse(i) { "" } == text) return
        while (e.strs.size <= i) e.strs.add("")
        e.strs[i] = text
        if (e.fuzzy && !userToggledFuzzy) { // Poedit clears "needs work" once you edit the translation
            e.fuzzy = false
            needsWork.isSelected = false
        }
        commit(e)
        recordEdit(e)
        refreshRow()
    }

    // ------------------------------------------------------------------ source references

    private fun openRef(ref: String) {
        val m = Regex("^(.*?)(?::(\\d+))?$").matchEntire(ref.trim())!! // always matches
        val path = m.groupValues[1].replace('\\', '/').removePrefix("./")
        val line = m.groupValues[2].toIntOrNull() ?: 1
        val target = runReadActionBlocking { findRef(path) } // the file index needs an explicit read action
        if (target == null) {
            JBPopupFactory.getInstance()
                .createHtmlTextBalloonBuilder("File <b>${StringUtil.escapeXmlEntities(path)}</b> not found in the project", MessageType.WARNING, null)
                .createBalloon().showInCenterOf(root)
            return
        }
        OpenFileDescriptor(project, target, line - 1, 0).navigate(true)
    }

    /**
     * Refs are whatever xgettext saw: absolute, or relative to the directory it ran in.
     * Try the path as is, then every directory above the .po, then a project-wide match by file name,
     * where the candidate sharing the longest tail of the ref's path wins.
     */
    private fun findRef(path: String): VirtualFile? {
        if (File(path).isAbsolute) LocalFileSystem.getInstance().findFileByPath(path)?.let { return it }
        var dir = vfile.parent
        while (dir != null) {
            dir.findFileByRelativePath(path)?.let { return it }
            dir = dir.parent
        }
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }.reversed()
        val name = parts.firstOrNull() ?: return null
        val candidates = try {
            FilenameIndex.getVirtualFilesByName(name, GlobalSearchScope.projectScope(project))
        } catch (e: IndexNotReadyException) { return null }
        fun score(f: VirtualFile) = parts.zip(f.path.split('/').reversed()).takeWhile { (a, b) -> a == b }.size
        return candidates.maxByOrNull(::score)?.takeIf { candidates.size == 1 || score(it) >= 2 }
    }

    private fun link(text: String, tip: String?, onClick: (HyperlinkLabel) -> Unit) = HyperlinkLabel(text).also { l ->
        l.toolTipText = tip
        l.addHyperlinkListener(object : HyperlinkAdapter() {
            override fun hyperlinkActivated(e: HyperlinkEvent) = onClick(l)
        })
    }

    // ------------------------------------------------------------------ FileEditor boilerplate

    override fun getComponent(): JComponent = root
    override fun getPreferredFocusedComponent(): JComponent = table
    override fun getName() = "POEditor"
    override fun getFile(): VirtualFile = vfile
    override fun setState(state: FileEditorState) {}
    override fun isModified() = false
    override fun isValid() = vfile.isValid
    override fun addPropertyChangeListener(l: PropertyChangeListener) {}
    override fun removePropertyChangeListener(l: PropertyChangeListener) {}
    override fun dispose() {}

    private companion object {
        /** UI font (not monospace) with some breathing room around the text. */
        fun styled(a: JBTextArea) = a.apply {
            font = UIUtil.getLabelFont()
            lineWrap = true
            wrapStyleWord = true
            margin = JBUI.insets(6, 8)
        }

        fun editable() = styled(JBTextArea(4, 40))

        fun readOnly() = styled(JBTextArea(1, 40)).apply {
            isEditable = false
            background = UIUtil.getPanelBackground()
            margin = JBUI.insets(2, 0)
        }

        /** Borderless scroll pane on the panel background, so read-only text looks like a label, not a field. */
        fun flat(a: JBTextArea) = JBScrollPane(a).apply {
            border = JBUI.Borders.empty()
            viewport.background = UIUtil.getPanelBackground()
        }

        /** Small grey caption above a component (instead of a heavy titled border). */
        fun captioned(caption: String, c: JComponent) = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
            border = JBUI.Borders.emptyBottom(8)
            add(JBLabel(caption, UIUtil.ComponentStyle.MINI, UIUtil.FontColor.BRIGHTER), BorderLayout.NORTH)
            add(c, BorderLayout.CENTER)
        }

        /** Read-only areas are sized to their text (ponytail: estimates wrapping at ~70 chars, so very long lines scroll). */
        fun fill(a: JBTextArea, text: String) {
            a.text = text
            a.rows = text.split('\n').sumOf { maxOf(1, (it.length + 69) / 70) }.coerceIn(1, 4)
            a.caretPosition = 0
        }
    }
}
