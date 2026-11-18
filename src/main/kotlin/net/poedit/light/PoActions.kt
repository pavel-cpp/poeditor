package net.poedit.light

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import javax.swing.SwingUtilities

/**
 * Editor actions with Poedit's default shortcuts (declared in plugin.xml, so users can rebind them
 * in Settings | Keymap, section POEditor). Keep this list in sync with plugin.xml.
 */
val PO_ACTION_IDS = listOf(
    "POEditor.Next", "POEditor.Previous", "POEditor.NextUnfinished", "POEditor.PreviousUnfinished",
    "POEditor.DoneAndNext", "POEditor.PreviouslyEdited", "POEditor.NextPage", "POEditor.PreviousPage",
    "POEditor.PreviousForm", "POEditor.NextForm", "POEditor.ClearTranslation", "POEditor.CopyFromSource",
    "POEditor.CopyFromSingular", "POEditor.ToggleNeedsWork", "POEditor.EditComment", "POEditor.Find",
)

/** Runs [run] on the POEditor that is selected in the current project *and* has the keyboard focus; otherwise disabled. */
abstract class PoAction(private val run: PoEditor.() -> Unit) : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = editor(e) != null }
    override fun actionPerformed(e: AnActionEvent) { editor(e)?.run() }

    private fun editor(e: AnActionEvent): PoEditor? {
        val ed = e.project?.let { FileEditorManager.getInstance(it).selectedEditor } as? PoEditor ?: return null
        val focus = e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT) ?: return null
        return ed.takeIf { SwingUtilities.isDescendingFrom(focus, it.component) }
    }
}

class PoNext : PoAction({ navigate(1, false) })
class PoPrevious : PoAction({ navigate(-1, false) })
class PoNextUnfinished : PoAction({ navigate(1, true) })
class PoPreviousUnfinished : PoAction({ navigate(-1, true) })
class PoDoneAndNext : PoAction({ doneAndNext() })
class PoPreviouslyEdited : PoAction({ previouslyEdited() })
class PoNextPage : PoAction({ page(1) })
class PoPreviousPage : PoAction({ page(-1) })
class PoPreviousForm : PoAction({ pluralForm(-1) })
class PoNextForm : PoAction({ pluralForm(1) })
class PoClearTranslation : PoAction({ clearTranslation() })
class PoCopyFromSource : PoAction({ copyFromSource(singularOnly = false) })
class PoCopyFromSingular : PoAction({ copyFromSource(singularOnly = true) })
class PoToggleNeedsWork : PoAction({ toggleNeedsWork() })
class PoEditComment : PoAction({ editComment() })
class PoFind : PoAction({ focusSearch() })
