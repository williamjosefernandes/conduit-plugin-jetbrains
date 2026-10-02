package dev.conduit.mcp.ide

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.runInEdtAndGet
import com.intellij.testFramework.runInEdtAndWait
import dev.conduit.mcp.ide.tools.AskUserTool
import dev.conduit.mcp.ide.tools.DependenciesTool
import dev.conduit.mcp.ide.tools.EndpointsTool
import dev.conduit.mcp.ide.tools.FrameworksDetectedTool
import dev.conduit.mcp.ide.tools.OpenFileAtTool
import dev.conduit.mcp.ide.tools.OpenFilesTool
import dev.conduit.mcp.ide.tools.ProjectModulesTool
import dev.conduit.mcp.ide.tools.RequestApprovalTool
import dev.conduit.mcp.ide.tools.SelectionTool

class ProjectAndInteractionToolsTest : ToolTestCase() {
    fun testProjectModulesListsTheModuleAndItsRoots() {
        val result = call(ProjectModulesTool)
        assertFalse(result.text, result.isError)
        assertTrue(result.text, result.text.lines().first() == module.name)
        assertTrue(result.text, result.text.contains("content:"))
    }

    fun testFrameworksAreDetectedFromBuildFiles() {
        assertEquals("No known framework detected.", call(FrameworksDetectedTool).text)

        myFixture.addFileToProject("package.json", """{"dependencies": {"@nestjs/core": "^11.0.0", "react": "^19.0.0"}}""")
        myFixture.addFileToProject("pom.xml", "<project><dependency><groupId>io.quarkus</groupId></dependency></project>")
        assertEquals("NestJS\nQuarkus\nReact", call(FrameworksDetectedTool).text)
    }

    fun testEndpointsAreFoundAcrossFrameworksAndFiltered() {
        java(
            "shop/OrderResource.java",
            """
            package shop;

            @Path("/orders")
            public class OrderResource {
                @GET
                @Path("/{id}")
                public String get(String id) { return id; }

                @POST
                public void create() {}
            }
            """,
        )
        myFixture.addFileToProject(
            "web/users.controller.ts",
            "@Controller('users')\nexport class UsersController {\n  @Get(':id')\n  one() {}\n}\n",
        )

        val all = call(EndpointsTool).text.lines()
        assertEquals(
            listOf(
                "GET /orders/{id} — shop/OrderResource.java:5",
                "GET /users/:id — web/users.controller.ts:3",
                "POST /orders — shop/OrderResource.java:9",
            ),
            all,
        )
        assertEquals("GET /users/:id — web/users.controller.ts:3", call(EndpointsTool, "filter" to "users").text)
    }

    fun testDependenciesReportsWhenTheIdeKnowsNone() {
        assertEquals("The IDE knows no libraries for this project.", call(DependenciesTool).text)
        assertEquals("No library matches 'quarkus'.", call(DependenciesTool, "filter" to "quarkus").text)
    }

    fun testOpenFilesAndSelectionDescribeTheEditor() {
        assertEquals("No files are open.", call(OpenFilesTool).text)
        assertEquals("No editor is active.", call(SelectionTool).text)

        val file = myFixture.addFileToProject("notes/todo.txt", "first line\nsecond line\nthird line\n")
        runInEdtAndWait {
            myFixture.openFileInEditor(file.virtualFile)
            myFixture.editor.selectionModel.setSelection(11, 22)
            myFixture.editor.caretModel.moveToOffset(22)
        }

        assertEquals("* notes/todo.txt", call(OpenFilesTool).text)
        assertEquals("notes/todo.txt:2:12\nselected lines 2-2:\nsecond line", call(SelectionTool).text)
    }

    fun testOpenFileAtNavigatesTheEditor() {
        myFixture.addFileToProject("notes/todo.txt", "first line\nsecond line\nthird line\n")

        val result = call(OpenFileAtTool, "file" to "notes/todo.txt", "line" to 3, "column" to 4)

        assertEquals("Opened notes/todo.txt:3.", result.text)
        val position =
            runInEdtAndGet {
                FileEditorManager
                    .getInstance(project)
                    .selectedTextEditor!!
                    .caretModel.logicalPosition
            }
        assertEquals(2 to 3, position.line to position.column)
        assertTrue(call(OpenFileAtTool, "file" to "missing.txt").isError)
    }

    fun testQuestionsAndApprovalsGoThroughTheUser() {
        interaction.answer = "Postgres"
        assertEquals("Postgres", call(AskUserTool, "question" to "Which database?", "options" to listOf("Postgres", "MySQL")).text)
        assertEquals("Which database?" to listOf("Postgres", "MySQL"), interaction.questions.single())

        interaction.answer = null
        assertTrue(call(AskUserTool, "question" to "Still there?").isError)
        assertTrue(call(AskUserTool).isError)

        assertEquals("approved", call(RequestApprovalTool, "description" to "Delete the cache").text)
        interaction.approval = false
        val rejected = call(RequestApprovalTool, "description" to "Delete the cache")
        assertTrue(rejected.isError && rejected.text == "rejected")
    }
}
