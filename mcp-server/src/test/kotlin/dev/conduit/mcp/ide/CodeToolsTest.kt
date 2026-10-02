package dev.conduit.mcp.ide

import dev.conduit.mcp.ide.tools.CallHierarchyTool
import dev.conduit.mcp.ide.tools.FileStructureTool
import dev.conduit.mcp.ide.tools.FindSymbolTool
import dev.conduit.mcp.ide.tools.FindUsagesTool
import dev.conduit.mcp.ide.tools.GoToDefinitionTool
import dev.conduit.mcp.ide.tools.TypeHierarchyTool

class CodeToolsTest : ToolTestCase() {
    override fun setUp() {
        super.setUp()
        java(
            "shop/Greeter.java",
            """
            package shop;

            public interface Greeter {
                String greet(String name);
            }
            """,
        )
        java(
            "shop/PoliteGreeter.java",
            """
            package shop;

            public class PoliteGreeter implements Greeter {
                private final String prefix = "Dear ";

                public String greet(String name) {
                    return decorate(name);
                }

                private String decorate(String name) {
                    return prefix + name;
                }
            }
            """,
        )
        java(
            "shop/Checkout.java",
            """
            package shop;

            public class Checkout {
                private final Greeter greeter = new PoliteGreeter();

                public String welcome(String customer) {
                    return greeter.greet(customer);
                }
            }
            """,
        )
    }

    fun testFindSymbolRanksExactMatchesFirstAndPaginates() {
        val result = call(FindSymbolTool, "query" to "Greeter")
        val lines = result.text.lines()
        assertFalse(result.isError)
        assertTrue(lines.first(), lines.first().contains("Greeter — shop/Greeter.java:3"))
        assertTrue(result.text, lines.any { it.contains("PoliteGreeter — shop/PoliteGreeter.java:3") })

        val firstPage = call(FindSymbolTool, "query" to "gree", "limit" to 1)
        assertTrue(firstPage.text, firstPage.text.endsWith("next_cursor: 1"))
        val secondPage = call(FindSymbolTool, "query" to "gree", "limit" to 1, "cursor" to "1")
        assertFalse(secondPage.text.lines().first() == firstPage.text.lines().first())

        assertEquals("No symbol matches 'Nonexistent'.", call(FindSymbolTool, "query" to "Nonexistent").text)
    }

    fun testFindUsagesIsSemanticAndAcceptsNameOrPosition() {
        val byName = call(FindUsagesTool, "symbol" to "PoliteGreeter")
        assertEquals("shop/Checkout.java:4: private final Greeter greeter = new PoliteGreeter();", byName.text)

        val byPosition = call(FindUsagesTool, "file" to "shop/PoliteGreeter.java", "line" to 10, "symbol" to "decorate")
        assertEquals("shop/PoliteGreeter.java:7: return decorate(name);", byPosition.text)

        java(
            "shop/Banner.java",
            """
            package shop;

            class Banner {
                String decorate(String text) {
                    return "* " + text + " *";
                }
            }
            """,
        )
        val ambiguous = call(FindUsagesTool, "symbol" to "decorate")
        assertTrue(ambiguous.text, ambiguous.isError)
        assertTrue(ambiguous.text, ambiguous.text.contains("ambiguous") && ambiguous.text.contains("shop/Banner.java:4"))

        assertTrue(call(FindUsagesTool, "symbol" to "Missing").isError)
    }

    fun testGoToDefinitionResolvesTheReferenceUnderThePosition() {
        val result = call(GoToDefinitionTool, "file" to "shop/Checkout.java", "line" to 7, "symbol" to "greet")
        assertFalse(result.text, result.isError)
        assertTrue(result.text, result.text.contains("shop/Greeter.java:4"))
        assertTrue(result.text, result.text.contains("String greet(String name);"))

        val outOfRange = call(GoToDefinitionTool, "file" to "shop/Checkout.java", "line" to 99)
        assertTrue(outOfRange.isError)
    }

    fun testTypeHierarchyListsImplementations() {
        val result = call(TypeHierarchyTool, "symbol" to "Greeter")
        assertTrue(result.text, result.text.contains("PoliteGreeter — shop/PoliteGreeter.java:3"))
    }

    fun testCallHierarchyInBothDirections() {
        val callers = call(CallHierarchyTool, "file" to "shop/PoliteGreeter.java", "line" to 10, "symbol" to "decorate")
        assertTrue(callers.text, callers.text.contains("greet — shop/PoliteGreeter.java:6"))

        val callees =
            call(
                CallHierarchyTool,
                "file" to "shop/PoliteGreeter.java",
                "line" to 6,
                "symbol" to "greet",
                "direction" to "callees",
            )
        assertTrue(callees.text, callees.text.contains("decorate — shop/PoliteGreeter.java:10"))
    }

    fun testFileStructureOutlinesMembersWithLines() {
        val result = call(FileStructureTool, "file" to "shop/PoliteGreeter.java")
        val lines = result.text.lines()
        assertTrue(result.text, lines.first().startsWith("PoliteGreeter") && lines.first().endsWith(":3"))
        assertTrue(result.text, lines.any { it.trim().startsWith("greet(String)") && it.endsWith(":6") })
        assertTrue(result.text, lines.any { it.trim().startsWith("prefix") })

        assertTrue(call(FileStructureTool, "file" to "shop/Nope.java").isError)
    }

    fun testExcludedFilesAreNotRead() {
        myFixture.addFileToProject(".env", "SECRET=1")
        val result = call(FileStructureTool, "file" to ".env")
        assertTrue(result.isError)
        assertTrue(result.text, result.text.contains("excluded"))
    }
}
