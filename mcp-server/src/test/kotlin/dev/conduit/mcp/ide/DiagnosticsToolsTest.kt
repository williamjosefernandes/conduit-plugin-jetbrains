package dev.conduit.mcp.ide

import com.siyeh.ig.style.UnnecessarySemicolonInspection
import dev.conduit.mcp.ide.tools.GetBuildErrorsTool
import dev.conduit.mcp.ide.tools.GetProblemsTool
import dev.conduit.mcp.ide.tools.QuickFixTool
import dev.conduit.mcp.ide.tools.RunInspectionTool

class DiagnosticsToolsTest : ToolTestCase() {
    override fun setUp() {
        super.setUp()
        java(
            "shop/Broken.java",
            """
            package shop;

            public class Broken {
                int count() {
                    return "three";
                }
            }
            """,
        )
        java(
            "shop/Tidy.java",
            """
            package shop;

            public class Tidy {
                int one() {
                    int value = 1;;
                    return value;
                }
            }
            """,
        )
    }

    fun testGetProblemsReportsCompilerErrorsWithoutBuilding() {
        val result = call(GetProblemsTool, "file" to "shop/Broken.java")

        assertFalse(result.text, result.isError)
        val error = result.text.lines().single { it.contains("[ERROR]") }
        assertTrue(error, error.startsWith("shop/Broken.java:5 [ERROR]"))
        assertTrue(error, error.contains("Incompatible types") || error.contains("incompatible types"))

        assertEquals(
            "No problems found in shop/Tidy.java.",
            call(GetProblemsTool, "file" to "shop/Tidy.java", "min_severity" to "error").text,
        )
        assertTrue(call(GetProblemsTool, "file" to "shop/Tidy.java", "min_severity" to "fatal").isError)
    }

    fun testGetBuildErrorsChecksOpenFiles() {
        assertEquals("No open or changed files to check.", call(GetBuildErrorsTool).text)

        val file =
            com.intellij.openapi.application
                .runReadAction { ProjectFiles.require(project, "shop/Broken.java") }
        com.intellij.testFramework.runInEdtAndWait { myFixture.openFileInEditor(file) }

        val result = call(GetBuildErrorsTool)
        assertTrue(result.text, result.text.startsWith("shop/Broken.java:5 [ERROR]"))
    }

    fun testRunInspectionRunsTheNamedInspection() {
        myFixture.enableInspections(UnnecessarySemicolonInspection())

        val result = call(RunInspectionTool, "file" to "shop/Tidy.java", "inspection" to "UnnecessarySemicolon")
        assertTrue(result.text, result.text.startsWith("shop/Tidy.java:5 "))

        val unknown = call(RunInspectionTool, "file" to "shop/Tidy.java", "inspection" to "NoSuchInspection")
        assertTrue(unknown.text, unknown.isError && unknown.text.contains("UnnecessarySemicolon"))
    }

    fun testQuickFixListsFixesAndSubmitsTheChosenOneForReview() {
        myFixture.enableInspections(UnnecessarySemicolonInspection())

        val listed = call(QuickFixTool, "file" to "shop/Tidy.java", "line" to 5)
        assertFalse(listed.text, listed.isError)
        assertTrue(listed.text, listed.text.contains("semicolon"))
        assertTrue(gate.proposed.isEmpty())

        val applied = call(QuickFixTool, "file" to "shop/Tidy.java", "line" to 5, "fix" to "semicolon")
        assertFalse(applied.text, applied.isError)
        val diff =
            gate.proposed
                .single()
                .diffs
                .single()
        assertEquals("shop/Tidy.java", diff.path)
        assertTrue(diff.proposedContent!!.contains("int value = 1;\n"))
        assertTrue(diff.originalContent!!.contains("int value = 1;;\n"))

        assertEquals("No quick fixes are available on shop/Tidy.java:1.", call(QuickFixTool, "file" to "shop/Tidy.java", "line" to 1).text)
    }
}
