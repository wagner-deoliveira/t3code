package codes.t3.jetbrains

import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal enum class ServerPhase { STOPPED, STARTING, RUNNING, STOPPING, FAILED }
internal data class ServerState(val phase: ServerPhase, val message: String)

internal class ManagedServer(
    private val onState: (ServerState) -> Unit,
    private val onReady: (URI) -> Unit,
) : AutoCloseable {
    private class Run {
        val process = CompletableFuture<Process?>()
        val stopped = CompletableFuture<Void>()
        var stopping = false
        var connection: SharedConnection? = null
        var failure: String? = null
        var timeout: java.util.concurrent.ScheduledFuture<*>? = null
    }

    private val lock = Any()
    private val workers = Executors.newScheduledThreadPool(2) { task ->
        Thread(task, "T3 Code connection").apply { isDaemon = true }
    }
    private val output = ArrayDeque<String>()
    private var run: Run? = null
    private var closed = false
    private var state = ServerState(ServerPhase.STOPPED, "Shared server is disconnected.")
    private val shutdownHook = Thread({ stop().join() }, "T3 Code connection shutdown")

    init { Runtime.getRuntime().addShutdownHook(shutdownHook) }

    fun snapshot(): ServerState = synchronized(lock) { state }
    fun logs(): String = synchronized(lock) { output.joinToString("\n") }

    private fun publish(next: ServerState) {
        state = next
        onState(next)
    }

    fun start(prepare: () -> ServerLaunch) {
        val active = synchronized(lock) {
            check(!closed) { "The IDE project is closing." }
            check(run == null) { "This project is already connecting or connected." }
            output.clear()
            Run().also {
                run = it
                publish(ServerState(ServerPhase.STARTING, "Connecting to the shared T3 server…"))
            }
        }
        workers.execute {
            try {
                val launch = prepare()
                synchronized(lock) { if (active.stopping) return@execute }
                val builder = ProcessBuilder(launch.command).directory(launch.projectPath.toFile()).redirectErrorStream(true)
                builder.environment().apply { clear(); putAll(launch.environment) }
                val child = builder.start()
                active.process.complete(child)
                active.timeout = workers.schedule({
                    synchronized(lock) {
                        if (run === active && state.phase == ServerPhase.STARTING) {
                            active.failure = "Shared server startup timed out. See Server log."
                            stop()
                        }
                    }
                }, 210, TimeUnit.SECONDS)
                child.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(lock) {
                            output.addLast(ServerLauncher.redact(line).take(4_000))
                            while (output.size > 200) output.removeFirst()
                            if (run === active && !active.stopping && state.phase == ServerPhase.STARTING) {
                                ServerLauncher.connection(line)?.let { active.connection = it }
                            }
                        }
                    }
                }
                val exitCode = child.waitFor()
                synchronized(lock) {
                    if (run === active && !active.stopping) {
                        val connection = active.connection
                        if (exitCode == 0 && connection != null) {
                            val uri = connection.url
                            active.timeout?.cancel(false)
                            publish(ServerState(ServerPhase.RUNNING, "Shared server connected at ${BridgePolicy.persistentUrl(uri)}"))
                            onReady(uri)
                            val host = ProcessHandle.of(connection.pid)
                            if (host.isPresent) host.get().onExit().thenRun {
                                synchronized(lock) {
                                    if (run === active && !active.stopping) {
                                        active.failure = "The shared server stopped. Connect again to restart it."
                                        stop()
                                    }
                                }
                            } else {
                                active.failure = "The shared server stopped. Connect again to restart it."
                                stop()
                            }
                        } else {
                            active.failure = "Could not connect ($exitCode). Use a T3 runtime with shared-server support; see Server log."
                            stop()
                        }
                    }
                }
            } catch (error: Exception) {
                synchronized(lock) {
                    if (run === active && !active.stopping) {
                        active.failure = error.message ?: "Could not start the server."
                        stop()
                    }
                }
            } finally {
                active.process.complete(null)
                active.timeout?.cancel(false)
            }
        }
    }

    fun stop(): CompletableFuture<Void> {
        val active = synchronized(lock) {
            val current = run ?: return CompletableFuture.completedFuture(null)
            if (current.stopping) return current.stopped
            current.stopping = true
            current.timeout?.cancel(false)
            publish(ServerState(ServerPhase.STOPPING, "Disconnecting this project…"))
            current
        }
        workers.execute {
            try {
                val child = active.process.join()
                // Detached servers may serve other clients; disconnect owns only this short-lived helper.
                if (child?.isAlive == true) {
                    child.destroy()
                    if (!child.waitFor(2, TimeUnit.SECONDS)) child.destroyForcibly().waitFor()
                }
            } finally {
                synchronized(lock) {
                    if (run === active) {
                        run = null
                        publish(
                            active.failure?.let { ServerState(ServerPhase.FAILED, it) }
                                ?: ServerState(ServerPhase.STOPPED, "Disconnected. The shared server keeps running."),
                        )
                    }
                    active.stopped.complete(null)
                }
            }
        }
        return active.stopped
    }

    override fun close() {
        synchronized(lock) { closed = true }
        stop().whenComplete { _, _ ->
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
            workers.shutdown()
        }
    }
}
