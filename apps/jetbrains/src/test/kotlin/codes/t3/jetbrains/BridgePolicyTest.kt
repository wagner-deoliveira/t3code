package codes.t3.jetbrains

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class BridgePolicyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `pairing credentials are never persisted and origins include ports`() {
        val uri = BridgePolicy.connectionUrl("http://localhost:6071/pair?token=private#secret")
        assertEquals("http://localhost:6071/", BridgePolicy.persistentUrl(uri))
        assertTrue(BridgePolicy.sameOrigin("http://localhost:6071/thread", uri))
        assertFalse(BridgePolicy.sameOrigin("http://localhost:6072/thread", uri))
        assertFalse(BridgePolicy.sameOrigin("http://localhost.evil:6071/thread", uri))
        assertFalse(BridgePolicy.supportsEditorBridge(BridgePolicy.connectionUrl("https://remote.example")))
        assertThrows(IllegalArgumentException::class.java) {
            BridgePolicy.connectionUrl("https://user:password@example.com")
        }
        assertThrows(IllegalArgumentException::class.java) { BridgePolicy.connectionUrl("file:///etc/passwd") }
    }

    @Test fun `file navigation stays inside the current project`() {
        val root = temporary.newFolder("project").toPath()
        val file = Files.writeString(root.resolve("source.ts"), "export const value = 1;")
        val outside = temporary.newFile("outside.ts").toPath()
        assertEquals(file.toRealPath(), BridgePolicy.resolveProjectFile(root, "source.ts"))
        assertEquals(file.toRealPath(), BridgePolicy.resolveProjectFile(root, file.toString()))
        assertThrows(IllegalArgumentException::class.java) { BridgePolicy.resolveProjectFile(root, "../outside.ts") }
        assertThrows(IllegalArgumentException::class.java) { BridgePolicy.resolveProjectFile(root, outside.toString()) }
        assertThrows(IllegalArgumentException::class.java) { BridgePolicy.resolveProjectFile(root, "//server/share/file.ts") }
    }
}
