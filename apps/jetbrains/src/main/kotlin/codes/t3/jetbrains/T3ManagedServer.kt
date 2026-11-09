package codes.t3.jetbrains

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.PROJECT)
internal class T3ManagedServer(private val project: Project) : Disposable {
    private data class Listener(val state: (ServerState) -> Unit, val ready: (URI) -> Unit)
    private val listeners = CopyOnWriteArrayList<Listener>()
    @Volatile private var origin: URI? = null
    private val server = ManagedServer(
        { value -> listeners.forEach { it.state(value) } },
        { uri ->
            origin = URI(BridgePolicy.persistentUrl(uri))
            listeners.forEach { it.ready(uri) }
        },
    )

    fun listen(owner: Disposable, state: (ServerState) -> Unit, ready: (URI) -> Unit) {
        val listener = Listener(state, ready)
        listeners.add(listener)
        Disposer.register(owner, Disposable { listeners.remove(listener) })
        state(server.snapshot())
        if (server.snapshot().phase == ServerPhase.RUNNING) origin?.let(ready)
    }

    fun isActive(): Boolean = server.snapshot().phase in listOf(ServerPhase.STARTING, ServerPhase.RUNNING, ServerPhase.STOPPING)

    fun start() {
        val settings = project.service<T3Settings>().state
        val configured = ServerRuntime(settings.serverExecutable.trim(), settings.serverEntryScript.trim())
        val home = ServerLauncher.sharedHome(settings.sharedHome)
        server.start {
            val runtime = if (configured.executable.isBlank()) {
                ServerLauncher.discover() ?: error("No T3 runtime found. Open Server setup and choose your T3 executable.")
            } else configured
            val root = Path.of(project.basePath ?: error("Open a project directory first."))
            ServerLauncher.prepare(runtime, root, home)
        }
    }

    fun stop() { server.stop() }
    fun logs(): String = server.logs()
    override fun dispose() { listeners.clear(); server.close() }
}
