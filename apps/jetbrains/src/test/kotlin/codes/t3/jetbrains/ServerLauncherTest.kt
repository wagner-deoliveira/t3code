package codes.t3.jetbrains

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ServerLauncherTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `known Windows shims become direct arguments without running a shell`() {
        assertEquals(
            ServerRuntime("C:\\Program Files\\T3 (Beta)\\T3.exe", "C:\\T3\\resources\\server.asar\\bin.mjs"),
            ServerLauncher.readWindowsShim("""
                @echo off
                rem Written by T3 Code: runs the desktop app's bundled t3 CLI.
                :run
                "C:\Program Files\T3 (Beta)\T3.exe" "C:\T3\resources\server.asar\bin.mjs" %*
            """.trimIndent()),
        )
        assertEquals(
            ServerRuntime("C:\\T3 & tools\\100%\\t3.exe"),
            ServerLauncher.readWindowsShim("@echo off\r\n\"C:\\T3 & tools\\100%%\\t3.exe\" %*"),
        )
        assertNull(ServerLauncher.readWindowsShim("@echo off\r\nnpx t3 %*"))
        assertNull(ServerLauncher.readWindowsShim("@echo off\r\n\"node.exe\" \"unknown.mjs\" %*"))
    }

    @Test fun `shared connection credentials require a valid loopback descriptor and stay out of logs`() {
        val secret = "11".repeat(32)
        val line = """sharedServer: {"version":1,"origin":"http://127.0.0.1:6071","pid":123,"bootstrapSecret":"$secret"}"""
        val uri = ServerLauncher.connectionUrl(line, 0)
        assertNotNull(uri)
        assertEquals("http://127.0.0.1:6071/", BridgePolicy.persistentUrl(uri!!))
        assertTrue(uri.rawFragment.startsWith("token="))
        assertEquals(70, uri.rawFragment.length)
        assertEquals("token=c642e382914e9becc1f4690b7dc4856a86bfe0c52186b0063051aee8e421ab25", uri.rawFragment)
        assertNull(ServerLauncher.connectionUrl(line.replace("127.0.0.1", "remote.example"), 0))
        assertNull(ServerLauncher.connectionUrl(line.replace("http:", "https:"), 0))
        assertNull(ServerLauncher.connectionUrl(line.replace("\"version\":1", "\"version\":2"), 0))
        assertNull(ServerLauncher.connectionUrl(line.replace(secret, "invalid"), 0))
        assertFalse(ServerLauncher.redact(line).contains(secret))
        assertFalse(ServerLauncher.redact("Error loading $uri").contains(uri.rawFragment.removePrefix("token=")))
    }

    @Test fun `projects default to the desktop home and can select another shared home`() {
        val home = temporary.newFolder("home").toPath()
        assertEquals(home.resolve(".t3"), ServerLauncher.sharedHome("", home))
        assertEquals(home, ServerLauncher.sharedHome(home.toString()))
        assertThrows(IllegalArgumentException::class.java) { ServerLauncher.sharedHome("relative/path") }
    }
}
