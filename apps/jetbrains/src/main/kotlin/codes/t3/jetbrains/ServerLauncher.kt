package codes.t3.jetbrains

import com.google.gson.JsonParser
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal data class ServerRuntime(val executable: String, val entryScript: String = "") {
    fun resolve(): ServerRuntime {
        val path = Path.of(executable).toAbsolutePath().normalize()
        require(Files.isRegularFile(path)) { "Choose an installed T3 executable, or Node/Electron with a T3 CLI script." }
        if (path.toString().endsWith(".cmd", ignoreCase = true)) {
            val shim = ServerLauncher.readWindowsShim(Files.readString(path))
                ?: error("This command shim is not supported. Choose t3.exe or Node/Electron and its T3 CLI script.")
            return shim.copy(entryScript = entryScript.ifBlank { shim.entryScript }).resolve()
        }
        require(!path.toString().endsWith(".bat", ignoreCase = true)) { "Choose an executable rather than a batch file." }
        if (entryScript.isNotBlank()) {
            val script = Path.of(entryScript.trim())
            require(script.isAbsolute) { "Use an absolute path for the T3 CLI script." }
            val archiveExists = generateSequence(script.parent) { it.parent }
                .any { it.toString().endsWith(".asar") && Files.isRegularFile(it) }
            require(Files.isRegularFile(script) || archiveExists) { "The selected T3 CLI script does not exist." }
        }
        return copy(executable = path.toString(), entryScript = entryScript.trim())
    }
}

internal data class ServerLaunch(
    val command: List<String>,
    val environment: Map<String, String>,
    val projectPath: Path,
    val dataPath: Path,
)

internal data class SharedConnection(val url: URI, val pid: Long)

internal object ServerLauncher {
    private const val DESKTOP_SHIM_MARKER = "Written by T3 Code: runs the desktop app's bundled t3 CLI."
    private val ansi = Regex("\u001B\\[[0-9;]*[A-Za-z]")

    fun readWindowsShim(contents: String): ServerRuntime? {
        val invocation = Regex("""^"([^"\r\n]+)"(?: "([^"\r\n]+)")? %\*\s*$""", RegexOption.MULTILINE)
            .find(contents) ?: return null
        val executable = invocation.groupValues[1].replace("%%", "%")
        val script = invocation.groupValues[2].replace("%%", "%")
        if (script.isNotBlank() && !contents.contains(DESKTOP_SHIM_MARKER)) return null
        if (!contents.trimStart().startsWith("@echo off", ignoreCase = true)) return null
        return ServerRuntime(executable, script)
    }

    fun discover(): ServerRuntime? {
        val home = Path.of(System.getProperty("user.home"))
        val windows = System.getProperty("os.name").startsWith("Windows")
        val name = if (windows) "t3.cmd" else "t3"
        val candidates = listOf(home.resolve(".local/bin/$name"), home.resolve(".t3/bin/$name")) +
            (System.getenv("PATH") ?: "").split(java.io.File.pathSeparator)
                .filter(String::isNotBlank)
                .flatMap { directory ->
                    if (windows) listOf(Path.of(directory, "t3.exe"), Path.of(directory, "t3.cmd"))
                    else listOf(Path.of(directory, "t3"))
                }
        return candidates.firstNotNullOfOrNull { path ->
            runCatching { ServerRuntime(path.toString()).resolve() }.getOrNull()
        }
    }

    fun sharedHome(configured: String = "", home: Path = Path.of(System.getProperty("user.home"))): Path =
        if (configured.isBlank()) home.resolve(".t3")
        else Path.of(configured.trim()).also { require(it.isAbsolute) { "Use an absolute T3 home path." } }.normalize()

    fun prepare(runtime: ServerRuntime, projectPath: Path, dataPath: Path): ServerLaunch {
        val resolved = runtime.resolve()
        val prefix = listOf(resolved.executable) + listOf(resolved.entryScript).filter(String::isNotBlank)
        val environment = System.getenv().filterKeys {
            !it.startsWith("T3CODE_", ignoreCase = true) &&
                !it.startsWith("VITE_", ignoreCase = true) &&
                !it.startsWith("ELECTRON_", ignoreCase = true) &&
                !it.equals("NODE_OPTIONS", ignoreCase = true)
        }.toMutableMap()
        environment["T3CODE_HOME"] = dataPath.toString()
        environment["T3CODE_NO_BROWSER"] = "true"
        environment["T3CODE_TAILSCALE_SERVE"] = "false"
        environment["NO_COLOR"] = "1"
        if (resolved.entryScript.isNotBlank()) environment["ELECTRON_RUN_AS_NODE"] = "1"
        return ServerLaunch(
            prefix + listOf("shared", "--base-dir", dataPath.toString(), projectPath.toString()),
            environment, projectPath, dataPath,
        )
    }

    fun connectionUrl(line: String, nowMs: Long = System.currentTimeMillis()): URI? =
        connection(line, nowMs)?.url

    fun connection(line: String, nowMs: Long = System.currentTimeMillis()): SharedConnection? {
        val clean = ansi.replace(line, "").trim()
        if (!clean.startsWith("sharedServer:")) return null
        return runCatching {
            val result = JsonParser.parseString(clean.removePrefix("sharedServer:").trim()).asJsonObject
            require(result["version"].asInt == 1 && result["pid"].asLong > 0)
            val origin = BridgePolicy.connectionUrl(result["origin"].asString)
            require(origin.scheme == "http" && BridgePolicy.supportsEditorBridge(origin) &&
                origin.path in listOf("", "/") && origin.rawQuery == null && origin.rawFragment == null)
            val secret = result["bootstrapSecret"].asString
            require(secret.matches(Regex("[a-f0-9]{64}")))
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val token = mac.doFinal("t3-desktop-bootstrap:${nowMs / (12 * 60 * 60 * 1000)}".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            SharedConnection(URI("${BridgePolicy.persistentUrl(origin).trimEnd('/')}/pair#token=$token"), result["pid"].asLong)
        }.getOrNull()
    }

    fun redact(line: String): String {
        val clean = ansi.replace(line, "")
        if (Regex("""^\s*(?:Token|Pairing URL|pairingUrl|App link|sharedServer):""").containsMatchIn(clean)) {
            return "[Pairing credential hidden]"
        }
        return Regex("""([#?&]token=)[^&\s]+""").replace(clean) { "${it.groupValues[1]}[redacted]" }
    }
}
