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
import com.intellij.util.ui.JBUI
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu

internal class T3Panel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val gson = Gson()
    private val managed = project.service<T3ManagedServer>()
    private val toolbar = T3Toolbar(
        { startServer() },
        { SendSelectionAction.send(project) },
        { showConnectionMenu(it) },
        { JBUI.scale(it) },
    )
    private var serverState = ServerState(ServerPhase.STOPPED, "Connect to T3, then open a thread for this project.")
    private var loaded = false
    private var sharedConnection = false
    private var connectionDetail = serverState.message
    private var disposed = false
    private var trustedUrl: URI? = null
    private var browser: JBCefBrowser? = null
    private var query: JBCefJSQuery? = null

    init {
        add(toolbar, BorderLayout.NORTH)
        if (JBCefApp.isSupported()) {
            initializeBrowser()
        } else {
            add(JBLabel("T3 Code needs the bundled JetBrains Runtime with JCEF enabled."), BorderLayout.CENTER)
        }
        managed.listen(this, { state ->
            ApplicationManager.getApplication().invokeLater {
                if (!disposed && !project.isDisposed) {
                    serverState = state
                    refreshToolbar()
                    if (state.phase == ServerPhase.FAILED) notify(project, state.message, true)
                }
            }
        }, { uri ->
            ApplicationManager.getApplication().invokeLater {
                if (!disposed && !project.isDisposed) {
                    sharedConnection = true
                    connect(uri)
                }
            }
        })
        if (browser != null && project.service<T3Settings>().state.autoStartServer && !managed.isActive()) startServer()
        refreshToolbar()
    }

    private fun refreshToolbar() {
        val connecting = serverState.phase == ServerPhase.STARTING || (trustedUrl != null && !loaded)
        val failed = sharedConnection && serverState.phase == ServerPhase.FAILED
        val connected = loaded && trustedUrl != null && !failed && !connecting
        toolbar.showConnection(
            when {
                failed -> "Connection failed"
                serverState.phase == ServerPhase.STOPPING && trustedUrl == null -> "Disconnecting…"
                connecting -> "Connecting…"
                connected -> if (sharedConnection) "Shared server" else "Connected"
                else -> "Disconnected"
            },
            trustedUrl?.let(BridgePolicy::persistentUrl),
            if (trustedUrl == null || failed) serverState.message else connectionDetail,
            connected,
            connecting || (serverState.phase == ServerPhase.STOPPING && trustedUrl == null),
            failed,
            browser != null && !managed.isActive(),
            connected && trustedUrl?.let(BridgePolicy::supportsEditorBridge) == true,
        )
    }

    private fun showConnectionMenu(anchor: Component) {
        JPopupMenu().apply {
            fun action(label: String, enabled: Boolean = true, run: () -> Unit) {
                add(JMenuItem(label).apply {
                    isEnabled = enabled
                    addActionListener { run() }
                })
            }
            trustedUrl?.let { add(JMenuItem(BridgePolicy.persistentUrl(it)).apply { isEnabled = false }); addSeparator() }
            action("Connect shared server", browser != null && !managed.isActive()) { startServer() }
            action("Connect to URL…", browser != null) { showConnectionDialog() }
            action("Disconnect", trustedUrl != null || managed.isActive()) {
                trustedUrl = null
                loaded = false
                browser?.loadURL("about:blank")
                managed.stop()
                refreshToolbar()
            }
            addSeparator()
            action("Server setup…") { ServerSetupDialog(project).show() }
            action("Server log") {
                object : DialogWrapper(project) {
                    init { title = "T3 Code Server Log"; init() }
                    override fun createCenterPanel(): JComponent = JBScrollPane(
                        JBTextArea(managed.logs().ifBlank { "No server output yet." }, 20, 80).apply { isEditable = false },
                    )
                }.show()
            }
            show(anchor, (anchor.width - preferredSize.width).coerceAtMost(0), anchor.height)
        }
    }

    private fun showConnectionDialog() {
        object : DialogWrapper(project) {
            private val url = JBTextField(project.service<T3Settings>().state.serverUrl, 40)
            init { title = "Connect to T3"; setOKButtonText("Connect"); init() }
            override fun getPreferredFocusedComponent(): JComponent = url
            override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
                add(JBLabel("Server URL or a fresh pairing URL"), BorderLayout.NORTH)
                add(url, BorderLayout.CENTER)
                add(JBLabel("Complete pairing in T3 after connecting."), BorderLayout.SOUTH)
            }
            override fun doOKAction() {
                try {
                    val uri = BridgePolicy.connectionUrl(url.text)
                    managed.stop()
                    sharedConnection = false
                    connect(uri)
                    super.doOKAction()
                } catch (error: Exception) {
                    setErrorText(error.message ?: "Could not connect.")
                }
            }
        }.show()
    }

    private fun startServer() {
        try {
            require(browser != null) { "JCEF is unavailable. Use the bundled JetBrains Runtime." }
            sharedConnection = true
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
                    "appearance" -> {
                        fun color(name: String): Color {
                            val value = request.get(name)?.asString.orEmpty()
                            require(Regex("^#[0-9a-fA-F]{6}$").matches(value)) { "Invalid toolbar color." }
                            return Color.decode(value)
                        }
                        val colors = ToolbarColors(
                            color("background"), color("foreground"), color("muted"), color("accent"),
                            color("border"), color("primary"), color("primaryForeground"),
                        )
                        ApplicationManager.getApplication().invokeLater {
                            if (!disposed && !project.isDisposed) toolbar.applyColors(colors)
                        }
                    }
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
                ApplicationManager.getApplication().invokeLater {
                    if (!disposed && !project.isDisposed && trustedUrl == trusted) {
                        loaded = true
                        connectionDetail = if (BridgePolicy.supportsEditorBridge(trusted)) {
                            "Open a T3 thread for ${project.name} to share a selection."
                        } else {
                            "Remote T3 connected. Editor integration requires a local server."
                        }
                        refreshToolbar()
                    }
                }
                if (!BridgePolicy.supportsEditorBridge(trusted)) return
                val root = project.basePath ?: return
                val request = jsQuery.inject("JSON.stringify(payload)", "response => resolve()", "(code, message) => reject(new Error(message))")
                cefBrowser.executeJavaScript("""
                    (() => {
                    window.t3JetBrains = {
                      protocolVersion: 1,
                      projectPath: ${gson.toJson(root)},
                      openFile: target => new Promise((resolve, reject) => {
                        const payload = {...target, type: "openFile"};
                        $request
                      })
                    };
                    window.dispatchEvent(new Event("t3code:jetbrains-ready"));
                    window.t3JetBrainsThemeObserver?.disconnect();
                    const syncToolbar = () => {
                      const style = getComputedStyle(document.documentElement);
                      const canvas = document.createElement("canvas");
                      canvas.width = canvas.height = 1;
                      const context = canvas.getContext("2d");
                      const color = (token, background) => {
                        context.clearRect(0, 0, 1, 1);
                        context.fillStyle = background;
                        context.fillRect(0, 0, 1, 1);
                        context.fillStyle = style.getPropertyValue(token).trim();
                        context.fillRect(0, 0, 1, 1);
                        return "#" + [...context.getImageData(0, 0, 1, 1).data].slice(0, 3)
                          .map(value => value.toString(16).padStart(2, "0")).join("");
                      };
                      const background = color("--background", "#000000");
                      const payload = {type: "appearance", background};
                      for (const [name, token] of Object.entries({
                        foreground: "--foreground", muted: "--muted-foreground", accent: "--accent",
                        border: "--border", primary: "--primary", primaryForeground: "--primary-foreground",
                      })) payload[name] = color(token, background);
                      new Promise((resolve, reject) => { $request }).catch(() => {});
                    };
                    window.t3JetBrainsThemeObserver = new MutationObserver(syncToolbar);
                    window.t3JetBrainsThemeObserver.observe(document.documentElement, {attributes: true, attributeFilter: ["class", "style"]});
                    syncToolbar();
                    })();
                """.trimIndent(), frame.url, 0)
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

    private fun connect(uri: URI) {
        val web = browser ?: error("JCEF is unavailable. Use the bundled JetBrains Runtime.")
        trustedUrl = uri
        loaded = false
        val persistentUrl = BridgePolicy.persistentUrl(uri)
        project.service<T3Settings>().state.serverUrl = persistentUrl
        connectionDetail = if (BridgePolicy.supportsEditorBridge(uri)) {
            "Connecting… Complete T3 pairing, then open a thread for this project."
        } else {
            "Remote T3 connected in this panel. Selection sharing and IDE navigation currently require a local server."
        }
        refreshToolbar()
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
