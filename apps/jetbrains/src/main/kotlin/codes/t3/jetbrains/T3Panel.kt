package codes.t3.jetbrains

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

internal class T3Panel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val gson = Gson()
    private val urlField = JBTextField(project.service<T3Settings>().state.serverUrl)
    private val status = JBLabel("Connect to T3, then open a thread for this project.")
    private val managed = project.service<T3ManagedServer>()
    private val serverStatus = JBLabel()
    private val startButton = JButton("Connect shared server")
    private val stopButton = JButton("Disconnect")
    private var disposed = false
    private var trustedUrl: URI? = null
    private var browser: JBCefBrowser? = null
    private var query: JBCefJSQuery? = null

    init {
        val controls = JPanel(BorderLayout(0, 4))
        urlField.toolTipText = "T3 server URL or a fresh pairing URL"
        controls.add(urlField, BorderLayout.NORTH)
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        buttons.add(JButton("Connect").apply { addActionListener { connect() } })
        buttons.add(JButton("Send selection").apply {
            addActionListener { SendSelectionAction.send(project) }
        })
        controls.add(buttons, BorderLayout.SOUTH)
        val serverButtons = JPanel(java.awt.GridLayout(2, 2, 4, 4))
        startButton.addActionListener { startServer() }
        stopButton.addActionListener {
            trustedUrl = null
            browser?.loadURL("about:blank")
            status.text = "Disconnected from T3."
            stopButton.isEnabled = false
            managed.stop()
        }
        serverButtons.add(startButton)
        serverButtons.add(stopButton)
        serverButtons.add(JButton("Server setup…").apply {
            addActionListener { ServerSetupDialog(project).show() }
        })
        serverButtons.add(JButton("Server log").apply {
            addActionListener {
                object : DialogWrapper(project) {
                    init { title = "T3 Code Server Log"; init() }
                    override fun createCenterPanel(): JComponent = JBScrollPane(
                        JBTextArea(managed.logs().ifBlank { "No server output yet." }, 20, 80).apply { isEditable = false },
                    )
                }.show()
            }
        })
        controls.add(serverButtons, BorderLayout.CENTER)
        add(controls, BorderLayout.NORTH)
        add(JPanel(java.awt.GridLayout(0, 1)).apply { add(serverStatus); add(status) }, BorderLayout.SOUTH)
        urlField.addActionListener { connect() }
        if (JBCefApp.isSupported()) {
            initializeBrowser()
        } else {
            add(JBLabel("T3 Code needs the bundled JetBrains Runtime with JCEF enabled."), BorderLayout.CENTER)
        }
        managed.listen(this, { state ->
            ApplicationManager.getApplication().invokeLater {
                if (!disposed && !project.isDisposed) {
                    serverStatus.text = state.message
                    startButton.isEnabled = browser != null && state.phase in listOf(ServerPhase.STOPPED, ServerPhase.FAILED)
                    stopButton.isEnabled = trustedUrl != null || state.phase in listOf(ServerPhase.STARTING, ServerPhase.RUNNING)
                    if (state.phase == ServerPhase.FAILED) notify(project, state.message, true)
                }
            }
        }, { uri ->
            ApplicationManager.getApplication().invokeLater {
                if (!disposed && !project.isDisposed) connect(uri)
            }
        })
        if (browser != null && project.service<T3Settings>().state.autoStartServer && !managed.isActive()) startServer()
    }

    private fun startServer() {
        try {
            require(browser != null) { "JCEF is unavailable. Use the bundled JetBrains Runtime." }
            managed.start()
        } catch (error: Exception) {
            notify(project, error.message ?: "Could not start the server.", true)
        }
    }

    private fun initializeBrowser() {
        val web = JBCefBrowser()
        browser = web
        Disposer.register(this, web)
        add(web.component, BorderLayout.CENTER)
        val jsQuery = JBCefJSQuery.create(web)
        query = jsQuery
        Disposer.register(this, jsQuery)
        jsQuery.addHandler { message ->
            try {
                val trusted = trustedUrl ?: error("Connect to a T3 server first.")
                require(BridgePolicy.sameOrigin(web.cefBrowser.url, trusted) && BridgePolicy.supportsEditorBridge(trusted)) {
                    "The editor bridge is available only on the connected local T3 server."
                }
                val request = JsonParser.parseString(message).asJsonObject
                when (request.get("type")?.asString) {
                    "openFile" -> {
                        val root = project.basePath ?: error("Open a project directory first.")
                        val path = BridgePolicy.resolveProjectFile(Path.of(root), request.get("path").asString)
                        require(Files.isRegularFile(path)) { "Choose a file, rather than a directory." }
                        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
                            ?: error("File not found.")
                        val line = ((request.get("line")?.asInt ?: 1) - 1).coerceAtLeast(0)
                        val column = ((request.get("column")?.asInt ?: 1) - 1).coerceAtLeast(0)
                        ApplicationManager.getApplication().invokeLater {
                            if (!project.isDisposed) OpenFileDescriptor(project, file, line, column).navigate(true)
                        }
                    }
                    "selectionResult" -> notify(
                        project,
                        request.get("error")?.takeUnless { it.isJsonNull }?.asString
                            ?: "Selection added to the draft. Review it in T3 before sending.",
                        request.get("error")?.isJsonNull == false,
                    )
                    else -> error("Unknown editor bridge request.")
                }
                JBCefJSQuery.Response("OK")
            } catch (error: Exception) {
                JBCefJSQuery.Response(null, 1, error.message ?: "Editor bridge request failed.")
            }
        }
        web.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                val trusted = trustedUrl ?: return
                if (!frame.isMain || !BridgePolicy.sameOrigin(frame.url, trusted)) return
                if (!BridgePolicy.supportsEditorBridge(trusted)) return
                val root = project.basePath ?: return
                val request = jsQuery.inject("JSON.stringify(payload)", "response => resolve()", "(code, message) => reject(new Error(message))")
                cefBrowser.executeJavaScript("""
                    window.t3JetBrains = {
                      protocolVersion: 1,
                      projectPath: ${gson.toJson(root)},
                      openFile: target => new Promise((resolve, reject) => {
                        const payload = {...target, type: "openFile"};
                        $request
                      })
                    };
                    window.dispatchEvent(new Event("t3code:jetbrains-ready"));
                """.trimIndent(), frame.url, 0)
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) status.text = "Connected. Open a T3 thread for ${project.name} to share a selection."
                }
            }
        }, web.cefBrowser)
        web.jbCefClient.addRequestHandler(object : CefRequestHandlerAdapter() {
            override fun onBeforeBrowse(
                cefBrowser: CefBrowser, frame: CefFrame, request: CefRequest,
                userGesture: Boolean, isRedirect: Boolean,
            ): Boolean {
                if (request.url == "about:blank") return false
                val trusted = trustedUrl ?: return true
                if (!frame.isMain || BridgePolicy.sameOrigin(request.url, trusted)) return false
                if (request.url.startsWith("https://") || request.url.startsWith("http://")) {
                    BrowserUtil.browse(request.url)
                }
                return true
            }

            override fun onOpenURLFromTab(
                cefBrowser: CefBrowser, frame: CefFrame, targetUrl: String,
                userGesture: Boolean,
            ): Boolean {
                if (targetUrl.startsWith("https://") || targetUrl.startsWith("http://")) BrowserUtil.browse(targetUrl)
                return true
            }
        }, web.cefBrowser)
    }

    private fun connect() {
        try {
            val uri = BridgePolicy.connectionUrl(urlField.text)
            managed.stop()
            connect(uri)
        } catch (error: Exception) {
            notify(project, error.message ?: "Could not connect.", true)
        }
    }

    private fun connect(uri: URI) {
        val web = browser ?: error("JCEF is unavailable. Use the bundled JetBrains Runtime.")
        trustedUrl = uri
        stopButton.isEnabled = true
        val persistentUrl = BridgePolicy.persistentUrl(uri)
        project.service<T3Settings>().state.serverUrl = persistentUrl
        urlField.text = persistentUrl
        status.text = if (BridgePolicy.supportsEditorBridge(uri)) {
            "Connecting… Complete T3 pairing, then open a thread for this project."
        } else {
            "Remote T3 connected in this panel. Selection sharing and IDE navigation currently require a local server."
        }
        web.loadURL(uri.toString())
    }

    fun sendSelection(selection: EditorSelection) {
        val web = browser
        val jsQuery = query
        val trusted = trustedUrl
        if (web == null || jsQuery == null || trusted == null) {
            notify(project, "Connect to your T3 server first, then send the selection again.", true)
            return
        }
        if (!BridgePolicy.supportsEditorBridge(trusted)) {
            notify(project, "Selection sharing currently requires a local T3 server.", true)
            return
        }
        if (!BridgePolicy.sameOrigin(web.cefBrowser.url, trusted)) {
            notify(project, "Wait for the connected T3 server to load, then send the selection again.", true)
            return
        }
        val report = jsQuery.inject("JSON.stringify(result)")
        web.cefBrowser.executeJavaScript("""
            (() => {
              let error = null;
              try {
                if (window.t3JetBrainsClient?.protocolVersion !== 1) {
                  throw new Error("Open a thread in a T3 build with JetBrains support, then send the selection again.");
                }
                window.t3JetBrainsClient.addSelection(${gson.toJson(selection)});
              } catch (cause) { error = cause.message || String(cause); }
              const result = {type: "selectionResult", error};
              $report
            })();
        """.trimIndent(), web.cefBrowser.url, 0)
    }

    override fun dispose() { disposed = true }

    companion object {
        internal fun notify(project: Project, message: String, error: Boolean) {
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) NotificationGroupManager.getInstance().getNotificationGroup("T3 Code")
                    .createNotification(message, if (error) NotificationType.ERROR else NotificationType.INFORMATION)
                    .notify(project)
            }
        }
    }
}
