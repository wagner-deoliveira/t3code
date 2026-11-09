package codes.t3.jetbrains

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import java.nio.file.Path

internal data class EditorSelection(
    val projectPath: String,
    val filePath: String,
    val text: String,
    val startLine: Int,
    val endLine: Int,
    val unsaved: Boolean,
)

class SendSelectionAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible =
            event.project != null && event.getData(CommonDataKeys.EDITOR)?.selectionModel?.hasSelection() == true
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        send(project, event.getData(CommonDataKeys.EDITOR))
    }

    companion object {
        internal fun send(project: Project, editor: Editor? = FileEditorManager.getInstance(project).selectedTextEditor) {
            try {
                require(editor != null && editor.selectionModel.hasSelection()) { "Select some code in an editor first." }
                val projectPath = project.basePath ?: error("Open a project directory first.")
                val file = FileDocumentManager.getInstance().getFile(editor.document)
                    ?: error("This editor has no project file.")
                require(file.isInLocalFileSystem) { "Selection sharing currently supports local files." }
                BridgePolicy.resolveProjectFile(Path.of(projectPath), file.path)
                val selection = editor.selectionModel
                val text = selection.selectedText ?: error("Select some code in an editor first.")
                require(text.length <= BridgePolicy.MAX_SELECTION_LENGTH) { "Select at most 100,000 characters." }
                val payload = EditorSelection(
                    projectPath, file.path, text,
                    editor.document.getLineNumber(selection.selectionStart) + 1,
                    editor.document.getLineNumber((selection.selectionEnd - 1).coerceAtLeast(selection.selectionStart)) + 1,
                    FileDocumentManager.getInstance().isDocumentUnsaved(editor.document),
                )
                val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("T3 Code")
                    ?: error("The T3 Code tool window is unavailable.")
                toolWindow.activate {
                    (toolWindow.contentManager.getContent(0)?.component as? T3Panel)?.sendSelection(payload)
                }
            } catch (error: Exception) {
                T3Panel.notify(project, error.message ?: "Could not share the selection.", true)
            }
        }
    }
}
