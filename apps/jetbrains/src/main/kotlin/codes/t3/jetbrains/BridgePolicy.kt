package codes.t3.jetbrains

import java.net.URI
import java.nio.file.Path

internal object BridgePolicy {
    const val MAX_SELECTION_LENGTH = 100_000

    fun connectionUrl(value: String): URI {
        val uri = URI(value.trim())
        require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            "Enter a T3 server or pairing URL beginning with http:// or https://."
        }
        return uri
    }

    fun origin(uri: URI): String {
        val port = if (uri.port == -1) {
            if (uri.scheme == "https") 443 else 80
        } else uri.port
        return "${uri.scheme.lowercase()}://${uri.host.lowercase()}:$port"
    }

    fun sameOrigin(url: String, trusted: URI): Boolean =
        runCatching { origin(connectionUrl(url)) == origin(trusted) }.getOrDefault(false)

    fun supportsEditorBridge(uri: URI): Boolean =
        uri.host.lowercase() in listOf("localhost", "127.0.0.1", "[::1]", "::1")

    fun persistentUrl(uri: URI): String = URI(uri.scheme, null, uri.host, uri.port, "/", null, null).toString()

    fun resolveProjectFile(projectPath: Path, requestedPath: String): Path {
        require(requestedPath.isNotBlank() && !requestedPath.contains('\u0000')) { "Invalid file path." }
        // Avoid probing network shares or device paths supplied by a chat link on Windows.
        require(!Regex("^[\\\\/]{2}").containsMatchIn(requestedPath)) {
            "Network paths cannot be opened through the editor bridge."
        }
        val lexicalRoot = projectPath.toAbsolutePath().normalize()
        val root = lexicalRoot.toRealPath()
        val requested = Path.of(requestedPath)
        val normalized = (if (requested.isAbsolute) requested else lexicalRoot.resolve(requested)).normalize()
        require(normalized.startsWith(lexicalRoot) || normalized.startsWith(root)) {
            "This file belongs to a different IDE project."
        }
        val real = normalized.toRealPath()
        require(real.startsWith(root)) { "This file resolves outside the IDE project." }
        return real
    }
}
