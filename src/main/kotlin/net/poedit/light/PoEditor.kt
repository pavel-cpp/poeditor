package net.poedit.light

import com.intellij.icons.AllIcons
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.*
import com.intellij.ui.components.*
import com.intellij.ui.table.JBTable
import com.intellij.util.Alarm
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.beans.PropertyChangeListener
import javax.swing.*
import javax.swing.table.AbstractTableModel

object PoFileType : LanguageFileType(PlainTextLanguage.INSTANCE) {
    override fun getName() = "PO"
    override fun getDescription() = "Gettext translation catalog"
    override fun getDefaultExtension() = "po"
    override fun getIcon(): Icon = AllIcons.FileTypes.Text
}

class PoEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file.extension.equals("po", true)
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = PoEditor(project, file)
    override fun getEditorTypeId() = "poedit-light"
    override fun getPolicy() = FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR
}

/**
 * Poedit-style catalog editor: entry list on top, source/translation pane below.
 * All edits are spliced into the IDE [Document] entry by entry, so Undo, VCS and the text tab keep working.
 */
class PoEditor(private val project: Project, private val vfile: VirtualFile) : UserDataHolderBase(), FileEditor {
    private val doc = FileDocumentManager.getInstance().getDocument(vfile)!!
    private var po = parsePo(doc.text)
    private var shown = emptyList<PoEntry>()
    private var current: PoEntry? = null
    private var loading = false          // filling widgets from an entry: ignore their change events
    private var writing = false          // our own document write: don't re-parse
    private var userToggledFuzzy = false // like Poedit's m_dontAutoclearFuzzyStatus

    private val search = SearchTextField()
    private val filter = ComboBox(arrayOf("All", "Needs work", "Untranslated", "Translated"))
    private val stats = JBLabel().apply { border = JBUI.Borders.empty(2, 6) }

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
    private val notes = readOnly()
    private val refs = JBList<String>()
    private val tabs = JBTabbedPane()
    private val areas = mutableListOf<JBTextArea>()
    private val needsWork = JBCheckBox("Needs work")
    private val comment = JBTextArea(2, 40).apply { lineWrap = true; wrapStyleWord = true }

    private val pluralBox = titled("Source text (plural)", JBScrollPane(sourcePlural))
    private val notesBox = titled("Notes for translators", JBScrollPane(notes))
    private val refsBox = titled("Found in (double-click to open)", JBScrollPane(refs).apply { preferredSize = JBUI.size(100, 60) })
    private val detail = FormBuilder.createFormBuilder()
        .addComponent(titled("Source text", JBScrollPane(source)))
        .addComponent(pluralBox)
        .addComponent(notesBox)
        .addComponent(refsBox)
        .addComponent(titled("Translation", tabs))
        .addComponent(needsWork)
        .addComponent(titled("Comment", JBScrollPane(comment)))
        .addComponentFillVertically(JPanel(), 0)
        .panel.apply { border = JBUI.Borders.empty(4) }

    private val root = JBSplitter(true, 0.5f)
    private val reloadAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    init {
        // ---- list ----
        table.setShowGrid(false)
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.columnModel.getColumn(0).apply { minWidth = 28; maxWidth = 28 }
        table.setDefaultRenderer(Any::class.java, object : ColoredTableCellRenderer() {
            override fun customizeCellRenderer(t: JTable, value: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int) {
                val e = shown.getOrNull(row) ?: return
                val state = e.state(po.nplurals)
                if (col == 0) { if (state == 1) icon = AllIcons.General.Warning; return }
                append(value as? String ?: "", when (state) {
                    1 -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor(0xB07000, 0xD9A343))
                    2 -> SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                    else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
                })
            }
        })
        table.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) show(shown.getOrNull(table.selectedRow)) }
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) = applyFilter()
        })
        filter.addActionListener { applyFilter() }

        // ---- detail ----
        refs.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) refs.selectedValue?.let(::openRef) }
        })
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
            add(JPanel(BorderLayout(JBUI.scale(6), 0)).apply { add(search, BorderLayout.CENTER); add(filter, BorderLayout.EAST) }, BorderLayout.NORTH)
            add(JBScrollPane(table), BorderLayout.CENTER)
            add(stats, BorderLayout.SOUTH)
        }
        root.firstComponent = top
        root.secondComponent = JBScrollPane(detail)

        // Poedit shortcuts: Ctrl+Down/Up = next/previous, Ctrl+Shift+Down = next unfinished, Ctrl+U = needs work
        bind("ctrl DOWN") { go(1, false) }
        bind("ctrl UP") { go(-1, false) }
        bind("ctrl shift DOWN") { go(1, true) }
        bind("ctrl U") { if (current != null) needsWork.doClick() }

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
        po = parsePo(doc.text)
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
        stats.text = "${po.entries.size} strings · ${s[0] ?: 0} translated · ${s[1] ?: 0} need work · ${s[2] ?: 0} untranslated" +
            (if (po.language.isNotEmpty()) " · ${po.language}" else "")
        table.repaint()
    }

    private fun go(delta: Int, unfinished: Boolean) {
        var r = table.selectedRow
        do { r += delta; if (r !in shown.indices) return } while (unfinished && shown[r].state(po.nplurals) == 0)
        table.setRowSelectionInterval(r, r)
        table.scrollRectToVisible(table.getCellRect(r, 0, true))
        areas.firstOrNull()?.requestFocusInWindow()
    }

    // ------------------------------------------------------------------ detail

    private fun show(e: PoEntry?) {
        current = e
        loading = true
        try {
            detail.isVisible = e != null
            if (e == null) return
            userToggledFuzzy = false
            source.text = e.id
            pluralBox.isVisible = e.plural != null
            sourcePlural.text = e.plural.orEmpty()
            val note = (listOfNotNull(e.ctx?.let { "Context: $it" }) + e.extracted).joinToString("\n")
            notes.text = note
            notesBox.isVisible = note.isNotEmpty()
            val rs = e.refs.flatMap { it.split(Regex("\\s+")) }.filter { it.isNotEmpty() }
            refs.setListData(rs.toTypedArray())
            refsBox.isVisible = rs.isNotEmpty()

            val n = if (e.plural != null) maxOf(po.nplurals, e.strs.size, 1) else 1
            tabs.removeAll()
            for (i in 0 until n) {
                val a = areaAt(i)
                a.text = e.strs.getOrElse(i) { "" }
                a.caretPosition = 0
                tabs.addTab(if (e.plural != null) "Form $i" else "Translation", JBScrollPane(a))
            }
            needsWork.isSelected = e.fuzzy
            comment.text = e.comment
        } finally { loading = false }
    }

    private fun areaAt(i: Int): JBTextArea {
        while (areas.size <= i) {
            val idx = areas.size
            areas += JBTextArea(4, 40).apply {
                lineWrap = true
                wrapStyleWord = true
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
        refreshRow()
    }

    private fun openRef(ref: String) {
        val m = Regex("^(.*?)(?::(\\d+))?$").matchEntire(ref) ?: return
        val line = m.groupValues[2].toIntOrNull() ?: 1
        var dir = vfile.parent // refs are relative to the project root, which is usually somewhere above the .po
        while (dir != null) {
            dir.findFileByRelativePath(m.groupValues[1])?.let { OpenFileDescriptor(project, it, line - 1, 0).navigate(true); return }
            dir = dir.parent
        }
    }

    private fun bind(key: String, action: () -> Unit) {
        root.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(key), key)
        root.actionMap.put(key, object : AbstractAction() { override fun actionPerformed(e: ActionEvent?) = action() })
    }

    // ------------------------------------------------------------------ FileEditor boilerplate

    override fun getComponent(): JComponent = root
    override fun getPreferredFocusedComponent(): JComponent = table
    override fun getName() = "Poedit"
    override fun getFile(): VirtualFile = vfile
    override fun setState(state: FileEditorState) {}
    override fun isModified() = false
    override fun isValid() = vfile.isValid
    override fun addPropertyChangeListener(l: PropertyChangeListener) {}
    override fun removePropertyChangeListener(l: PropertyChangeListener) {}
    override fun dispose() {}

    private companion object {
        fun readOnly() = JBTextArea(2, 40).apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
        fun titled(title: String, c: JComponent) = JPanel(BorderLayout()).apply {
            border = IdeBorderFactory.createTitledBorder(title, false)
            add(c)
        }
    }
}
