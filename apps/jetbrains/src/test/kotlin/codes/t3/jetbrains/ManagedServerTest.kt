package codes.t3.jetbrains

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

class ManagedServerTest {
    @get:Rule val temporary = TemporaryFolder()

    private data class Fixture(val launch: ServerLaunch, val host: Process) : AutoCloseable {
        override fun close() { host.destroyForcibly().waitFor() }
    }

    private fun fixture(): Fixture {
        val root = temporary.newFolder().toPath()
        val data = temporary.newFolder().toPath()
        val entry = Files.writeString(root.resolve("fixture.mjs"), """
            import http from "node:http";
            import fs from "node:fs";
            import path from "node:path";
            const data = process.argv[process.argv.indexOf("--base-dir") + 1];
            const record = path.join(data, "connection.json");
            if (process.argv[2] === "fixture-host") {
              const server = http.createServer((_request, response) => response.end("shared-thread"));
              server.listen(0, "127.0.0.1", () => {
                fs.writeFileSync(record, JSON.stringify({
                  version: 1, origin: "http://127.0.0.1:" + server.address().port,
                  pid: process.pid, bootstrapSecret: "11".repeat(32)
                }));
                console.log("ready");
              });
            } else {
              if (process.argv[2] !== "shared" || process.env.T3CODE_HOME !== data) process.exit(2);
              fs.writeFileSync(path.join(data, "client-launched"), "yes");
              console.log("sharedServer: " + fs.readFileSync(record, "utf8"));
            }
        """.trimIndent())
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "node.exe" else "node"
        val node = (System.getenv("PATH") ?: "").split(java.io.File.pathSeparator)
            .filter(String::isNotBlank).map { Path.of(it, executable) }.first(Files::isRegularFile)
        val host = ProcessBuilder(node.toString(), entry.toString(), "fixture-host", "--base-dir", data.toString()).start()
        try {
            assertEquals("ready", host.inputStream.bufferedReader().readLine())
            return Fixture(ServerLauncher.prepare(ServerRuntime(node.toString(), entry.toString()), root, data), host)
        } catch (error: Throwable) { host.destroyForcibly().waitFor(); throw error }
    }

    private fun response(uri: URI): String = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI(BridgePolicy.persistentUrl(uri))).GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    ).body()

    @Test fun `two projects connect to one server and disconnect without stopping it`() {
        fixture().use { fixture ->
            val firstReady = CompletableFuture<URI>()
            val secondReady = CompletableFuture<URI>()
            val first = ManagedServer({
                if (it.phase == ServerPhase.FAILED) firstReady.completeExceptionally(IllegalStateException(it.message))
            }, { firstReady.complete(it) })
            val second = ManagedServer({
                if (it.phase == ServerPhase.FAILED) secondReady.completeExceptionally(IllegalStateException(it.message))
            }, { secondReady.complete(it) })
            try {
                first.start { fixture.launch }
                second.start { fixture.launch.copy(projectPath = temporary.newFolder().toPath()) }
                val firstUri = firstReady.join()
                val secondUri = secondReady.join()
                assertEquals(firstUri, secondUri)
                assertEquals("shared-thread", response(firstUri))
                assertThrows(IllegalStateException::class.java) { first.start { fixture.launch } }
                assertFalse(first.logs().contains("11".repeat(32)))
                first.close()
                first.stop().join()
                assertEquals(ServerPhase.STOPPED, first.snapshot().phase)
                assertTrue(fixture.host.isAlive)
                assertEquals("shared-thread", response(secondUri))
                second.stop().join()
                assertTrue(fixture.host.isAlive)
                assertEquals("shared-thread", response(firstUri))
            } finally { first.close(); second.close(); first.stop().join(); second.stop().join() }
        }
    }

    @Test fun `disconnect during preparation prevents a late helper and close prevents restart`() {
        fixture().use { fixture ->
            val entered = CompletableFuture<Void>()
            val release = CompletableFuture<Void>()
            val ready = CompletableFuture<URI>()
            val server = ManagedServer({}, { ready.complete(it) })
            try {
                server.start { entered.complete(null); release.join(); fixture.launch }
                entered.join()
                val stopped = server.stop()
                release.complete(null)
                stopped.join()
                assertEquals(ServerPhase.STOPPED, server.snapshot().phase)
                assertFalse(ready.isDone)
                assertFalse(Files.exists(fixture.launch.dataPath.resolve("client-launched")))
                assertTrue(fixture.host.isAlive)
                server.close()
                assertThrows(IllegalStateException::class.java) { server.start { fixture.launch } }
            } finally { release.complete(null); server.close(); server.stop().join() }
        }
    }

    @Test fun `a stopped shared host releases the connection for retry`() {
        fixture().use { fixture ->
            val ready = CompletableFuture<URI>()
            val failed = CompletableFuture<ServerState>()
            val server = ManagedServer({
                if (it.phase == ServerPhase.FAILED) failed.complete(it)
            }, { ready.complete(it) })
            try {
                server.start { fixture.launch }
                ready.join()
                fixture.host.destroyForcibly().waitFor()
                assertEquals(ServerPhase.FAILED, failed.join().phase)
                assertTrue(server.snapshot().message.contains("Connect again"))
            } finally { server.close(); server.stop().join() }
        }
    }

    @Test fun `a failed helper releases the connection and can be retried`() {
        fixture().use { fixture ->
            val failure = CompletableFuture<ServerState>()
            val ready = CompletableFuture<URI>()
            val server = ManagedServer({
                if (it.phase == ServerPhase.FAILED) {
                    if (failure.isDone) ready.completeExceptionally(IllegalStateException(it.message))
                    else failure.complete(it)
                }
            }, { ready.complete(it) })
            try {
                server.start { fixture.launch.copy(command = listOf(fixture.launch.projectPath.resolve("missing-executable").toString())) }
                assertEquals(ServerPhase.FAILED, failure.join().phase)
                server.start { fixture.launch }
                assertEquals("shared-thread", response(ready.join()))
            } finally { server.close(); server.stop().join() }
        }
    }
}
