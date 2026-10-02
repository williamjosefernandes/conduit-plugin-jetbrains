package dev.conduit.agents.acp

import dev.conduit.agents.acp.process.ExecutableResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExecutableResolverTest {
    @Test
    fun `unix lookup walks the path in order`() {
        val files = setOf("/usr/local/bin/claude", "/usr/bin/claude")
        val resolver = ExecutableResolver(mapOf("PATH" to "/opt/x:/usr/local/bin:/usr/bin"), windows = false, isFile = files::contains)

        assertEquals("/usr/local/bin/claude", resolver.resolve("claude"))
        assertNull(resolver.resolve("codex"))
        assertEquals(listOf("/usr/bin/claude", "acp"), resolver.commandLine("/usr/bin/claude", listOf("acp")))
    }

    @Test
    fun `windows lookup prefers real launchers over the bare npm shim`() {
        val files = setOf("C:\\npm\\claude", "C:\\npm\\claude.cmd", "C:\\bin\\tool.exe")
        val env = mapOf("Path" to "C:\\npm\\;C:\\bin", "PATHEXT" to ".COM;.EXE;.BAT;.CMD")
        val resolver = ExecutableResolver(env, windows = true, isFile = { path -> files.any { it.equals(path, ignoreCase = true) } })

        assertEquals("C:\\npm\\claude.cmd", resolver.resolve("claude"))
        assertEquals("C:\\bin\\tool.exe", resolver.resolve("tool"))
        assertNull(resolver.resolve("missing"))
    }

    @Test
    fun `windows script shims run through cmd`() {
        val resolver = ExecutableResolver(emptyMap(), windows = true, isFile = { false })

        assertEquals(
            listOf("cmd.exe", "/c", "C:\\npm\\npx.cmd", "-y", "pkg"),
            resolver.commandLine("C:\\npm\\npx.cmd", listOf("-y", "pkg")),
        )
        assertEquals(listOf("C:\\bin\\tool.exe"), resolver.commandLine("C:\\bin\\tool.exe", emptyList()))
    }

    @Test
    fun `explicit paths are checked directly`() {
        val resolver = ExecutableResolver(emptyMap(), windows = false, isFile = { it == "/opt/agent/bin/agent" })

        assertEquals("/opt/agent/bin/agent", resolver.resolve("/opt/agent/bin/agent"))
        assertNull(resolver.resolve("/opt/other"))
    }
}
