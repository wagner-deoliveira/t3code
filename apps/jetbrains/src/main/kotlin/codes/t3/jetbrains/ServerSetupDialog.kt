package codes.t3.jetbrains

import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

internal class ServerSetupDialog(private val project: Project) : DialogWrapper(project) {
    private val settings = project.service<T3Settings>().state
    private val runtime = if (settings.serverExecutable.isBlank()) ServerLauncher.discover() else null
    private val executable = JBTextField(settings.serverExecutable.ifBlank { runtime?.executable.orEmpty() })
    private val entryScript = JBTextField(settings.serverEntryScript.ifBlank { runtime?.entryScript.orEmpty() })
    private val home = JBTextField(settings.sharedHome)
    private val autoStart = JBCheckBox("Connect shared server when this project's T3 panel opens", settings.autoStartServer)

    init { title = "T3 Code Server Setup"; init() }

    override fun createCenterPanel(): JComponent = JPanel(GridLayout(0, 1, 0, 6)).apply {
        add(JBLabel("T3 executable, or Node/Electron executable:"))
        add(fileField(executable))
        add(JBLabel("T3 CLI script (only when using Node/Electron):"))
        add(fileField(entryScript))
        add(JBLabel("Shared T3 home (blank uses ~/.t3, like the desktop app):"))
        add(home)
        add(autoStart)
        add(JBLabel("Projects using the same T3 home share one background server and its threads."))
    }

    private fun fileField(field: JBTextField) = JPanel(BorderLayout(4, 0)).apply {
        add(field, BorderLayout.CENTER)
        add(JButton("Browse…").apply {
            addActionListener {
                FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFileDescriptor(), project, null)?.let {
                    field.text = it.path
                }
            }
        }, BorderLayout.EAST)
    }

    override fun doOKAction() {
        try {
            if (executable.text.isNotBlank()) ServerRuntime(executable.text.trim(), entryScript.text.trim()).resolve()
            ServerLauncher.sharedHome(home.text)
        } catch (error: Exception) {
            setErrorText(error.message ?: "Choose a valid T3 runtime.")
            return
        }
        settings.serverExecutable = executable.text.trim()
        settings.serverEntryScript = entryScript.text.trim()
        settings.autoStartServer = autoStart.isSelected
        settings.sharedHome = home.text.trim()
        super.doOKAction()
    }
}
