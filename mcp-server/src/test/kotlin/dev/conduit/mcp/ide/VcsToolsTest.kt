package dev.conduit.mcp.ide

import dev.conduit.core.AgentMode
import dev.conduit.mcp.ide.tools.GitBranchesTool
import dev.conduit.mcp.ide.tools.GitCommitTool
import dev.conduit.mcp.ide.tools.GitCreateBranchTool
import dev.conduit.mcp.ide.tools.GitDiffTool
import dev.conduit.mcp.ide.tools.GitLogTool
import dev.conduit.mcp.ide.tools.GitStatusTool
import java.io.File

class VcsToolsTest : ToolTestCase() {
    private lateinit var dir: File

    override fun setUp() {
        super.setUp()
        dir = File(checkNotNull(project.basePath)).apply { mkdirs() }
        git("init", "-q", "-b", "main")
        File(dir, "a.txt").writeText("one\n")
        git("add", "a.txt")
        git("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-qm", "first")
    }

    override fun tearDown() {
        try {
            File(dir, ".git").deleteRecursively()
            File(dir, "a.txt").delete()
        } finally {
            super.tearDown()
        }
    }

    private fun git(vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true).start()
        process.inputStream.readAllBytes()
        assertEquals(0, process.waitFor())
    }

    fun testReadingTheRepository() {
        File(dir, "a.txt").writeText("one\ntwo\n")
        assertTrue(call(GitStatusTool).text.contains("a.txt"))
        assertTrue(call(GitDiffTool, "file" to "a.txt").text.contains("+two"))
        assertTrue(call(GitLogTool).text.contains("first"))
        assertTrue(call(GitBranchesTool).text.contains("main"))
    }

    fun testProtectedFilesAreNotRead() {
        assertTrue(call(GitDiffTool, "file" to ".env").isError)
    }

    fun testCommitsAndBranchesNeedTheUser() {
        File(dir, "a.txt").writeText("changed\n")
        interaction.approval = false
        assertTrue(call(GitCommitTool, "message" to "nope", "files" to listOf("a.txt")).isError)
        assertTrue(call(GitCreateBranchTool, "name" to "feature/x").isError)

        interaction.approval = true
        val commit = call(GitCommitTool, "message" to "change a", "files" to listOf("a.txt"))
        assertFalse(commit.text, commit.isError)
        assertTrue(commit.text.contains("change a"))
        assertTrue(call(GitCreateBranchTool, "name" to "-bad").isError)
    }

    fun testAskModeRefusesToCommit() {
        mode = AgentMode.ASK
        assertTrue(call(GitCommitTool, "message" to "x").isError)
    }
}
