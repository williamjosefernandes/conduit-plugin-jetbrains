package dev.conduit.mcp.ide

import com.intellij.openapi.application.runReadAction
import dev.conduit.core.AgentMode
import dev.conduit.core.HunkStatus
import dev.conduit.mcp.ide.tools.ApplyPatchTool
import dev.conduit.mcp.ide.tools.CreateFileTool
import dev.conduit.mcp.ide.tools.ProposeEditTool
import dev.conduit.mcp.ide.tools.RenameSymbolTool

class EditToolsTest : ToolTestCase() {
    private val greeter =
        """
        package shop;

        public class Greeter {
            public String greet(String name) {
                return "Hello, " + name;
            }

            public String shout(String name) {
                return greet(name).toUpperCase();
            }
        }
        """.trimIndent()

    override fun setUp() {
        super.setUp()
        java("shop/Greeter.java", greeter)
        java(
            "shop/Checkout.java",
            """
            package shop;

            public class Checkout {
                String welcome(Greeter greeter) {
                    return greeter.greet("you");
                }
            }
            """,
        )
    }

    private fun text(path: String): String = runReadAction { ProjectFiles.text(ProjectFiles.require(project, path)) }

    fun testProposeEditSubmitsADiffAndNeverWrites() {
        val result = call(ProposeEditTool, "file" to "shop/Greeter.java", "old_text" to "\"Hello, \"", "new_text" to "\"Hi, \"")

        assertFalse(result.text, result.isError)
        assertTrue(result.text, result.text.contains("NOT applied"))
        val diff =
            gate.proposed
                .single()
                .diffs
                .single()
        assertEquals("shop/Greeter.java", diff.path)
        assertEquals(greeter, diff.originalContent)
        assertEquals(greeter.replace("Hello, ", "Hi, "), diff.proposedContent)
        assertEquals(1, diff.hunks.size)
        assertEquals(greeter, text("shop/Greeter.java"))
    }

    fun testProposeEditRequiresAnExactUniqueMatch() {
        val missing = call(ProposeEditTool, "file" to "shop/Greeter.java", "old_text" to "nothing like this", "new_text" to "x")
        assertTrue(missing.isError && missing.text.contains("not found"))

        val twice = call(ProposeEditTool, "file" to "shop/Greeter.java", "old_text" to "String name", "new_text" to "String who")
        assertTrue(twice.text, twice.isError && twice.text.contains("2 times"))
        assertTrue(gate.proposed.isEmpty())

        call(ProposeEditTool, "file" to "shop/Greeter.java", "old_text" to "String name", "new_text" to "String who", "replace_all" to true)
        assertEquals(
            greeter.replace("String name", "String who"),
            gate.proposed
                .single()
                .diffs
                .single()
                .proposedContent,
        )
    }

    fun testOutcomeIsReportedToTheAgent() {
        gate.outcome = ReviewOutcome.REJECTED
        val rejected = call(ProposeEditTool, "file" to "shop/Greeter.java", "old_text" to "Hello", "new_text" to "Hi")
        assertTrue(rejected.isError && rejected.text.contains("rejected"))

        gate.outcome = ReviewOutcome.APPLIED
        val applied = call(ProposeEditTool, "file" to "shop/Greeter.java", "old_text" to "Hello", "new_text" to "Hi")
        assertEquals("Applied to shop/Greeter.java.", applied.text)
    }

    fun testWritesAreRefusedByPolicyBeforeReachingReview() {
        myFixture.addFileToProject(".env", "KEY=1")
        val secret = call(ProposeEditTool, "file" to ".env", "old_text" to "KEY=1", "new_text" to "KEY=2")
        assertTrue(secret.text, secret.isError && secret.text.contains("excluded"))

        mode = AgentMode.ASK
        val readOnly = call(ProposeEditTool, "file" to "shop/Greeter.java", "old_text" to "Hello", "new_text" to "Hi")
        assertTrue(readOnly.text, readOnly.isError && readOnly.text.contains("read-only"))
        assertTrue(gate.proposed.isEmpty())
    }

    fun testCreateFileProposesANewFileAndRefusesExistingOnes() {
        call(CreateFileTool, "path" to "shop/Banner.java", "content" to "package shop;\r\nclass Banner {}\r\n")
        val diff =
            gate.proposed
                .single()
                .diffs
                .single()
        assertNull(diff.originalContent)
        assertEquals("package shop;\nclass Banner {}\n", diff.proposedContent)
        assertEquals("package shop;\nclass Banner {}\n", diff.withAllHunks(HunkStatus.ACCEPTED).resolvedContent())

        assertTrue(call(CreateFileTool, "path" to "shop/Greeter.java", "content" to "x").isError)
    }

    fun testApplyPatchCoversSeveralFilesInOneChangeSet() {
        val patch =
            """
            --- a/shop/Greeter.java
            +++ b/shop/Greeter.java
            @@ -5,1 +5,1 @@
            -        return "Hello, " + name;
            +        return "Hi, " + name;
            --- /dev/null
            +++ b/shop/Notes.txt
            @@ -0,0 +1,1 @@
            +remember
            --- a/shop/Checkout.java
            +++ b/shop/Till.java
            @@ -3,1 +3,1 @@
            -public class Checkout {
            +public class Till {
            """.trimIndent()
        val result = call(ApplyPatchTool, "patch" to patch)

        assertFalse(result.text, result.isError)
        val changes = gate.proposed.single()
        assertEquals("Patch 3 files", changes.title)
        assertEquals(listOf("shop/Greeter.java", "shop/Notes.txt", "shop/Checkout.java"), changes.diffs.map { it.path })
        assertTrue(changes.diffs[0].proposedContent!!.contains("\"Hi, \""))
        assertNull(changes.diffs[1].originalContent)
        assertEquals("shop/Till.java", changes.diffs[2].newPath)

        val broken = call(ApplyPatchTool, "patch" to "--- a/shop/Greeter.java\n+++ b/shop/Greeter.java\n@@ -1,1 +1,1 @@\n-nope\n+x\n")
        assertTrue(broken.text, broken.isError && broken.text.contains("does not apply"))
        assertTrue(call(ApplyPatchTool, "patch" to "not a patch").isError)
    }

    fun testRenameSymbolPreviewsEveryUsageWithoutChangingAnything() {
        val result = call(RenameSymbolTool, "symbol" to "greet", "new_name" to "salute")

        assertFalse(result.text, result.isError)
        val changes = gate.proposed.single()
        assertEquals("Rename greet to salute", changes.title)
        val byPath = changes.diffs.associateBy { it.path }
        assertEquals(setOf("shop/Greeter.java", "shop/Checkout.java"), byPath.keys)
        assertEquals(greeter.replace("greet(", "salute("), byPath.getValue("shop/Greeter.java").proposedContent)
        assertTrue(byPath.getValue("shop/Checkout.java").proposedContent!!.contains("greeter.salute(\"you\")"))
        assertNotNull(changes.applyAll)
        assertEquals(greeter, text("shop/Greeter.java"))
    }

    fun testAcceptedRenameRunsTheRealRefactoringIncludingTheFile() {
        gate.runApplyAll = true
        gate.outcome = ReviewOutcome.APPLIED
        val result = call(RenameSymbolTool, "symbol" to "Greeter", "new_name" to "Welcomer")

        assertFalse(result.text, result.isError)
        val declaration =
            gate.proposed
                .single()
                .diffs
                .first { it.path == "shop/Greeter.java" }
        assertEquals("shop/Welcomer.java", declaration.newPath)
        assertTrue(text("shop/Welcomer.java").contains("public class Welcomer {"))
        assertTrue(text("shop/Checkout.java").contains("String welcome(Welcomer greeter)"))
        assertNull(runReadAction { ProjectFiles.find(project, "shop/Greeter.java") })
    }

    fun testRenameRejectsInvalidAndUnchangedNames() {
        assertTrue(call(RenameSymbolTool, "symbol" to "Greeter", "new_name" to "Greeter").isError)
        assertTrue(call(RenameSymbolTool, "symbol" to "Greeter", "new_name" to "not valid").isError)
        assertTrue(call(RenameSymbolTool, "symbol" to "Unknown", "new_name" to "X").isError)
        assertTrue(gate.proposed.isEmpty())
    }
}
