package dev.conduit.ide.actions

import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import dev.conduit.mcp.ide.tools.projectLibraries

/** The test libraries a project uses, named the way developers say them, e.g. "JUnit 5 and MockK". */
object TestStack {
    private val known =
        listOf(
            Regex("junit-jupiter|org[.]junit[.]jupiter", RegexOption.IGNORE_CASE) to "JUnit 5",
            Regex("(^|[^a-z])junit[:-]4|junit:junit", RegexOption.IGNORE_CASE) to "JUnit 4",
            Regex("testng", RegexOption.IGNORE_CASE) to "TestNG",
            Regex("kotest", RegexOption.IGNORE_CASE) to "Kotest",
            Regex("mockk", RegexOption.IGNORE_CASE) to "MockK",
            Regex("mockito", RegexOption.IGNORE_CASE) to "Mockito",
            Regex("assertj", RegexOption.IGNORE_CASE) to "AssertJ",
            Regex("rest-assured|restassured", RegexOption.IGNORE_CASE) to "REST Assured",
            Regex("spring-boot-starter-test", RegexOption.IGNORE_CASE) to "Spring Boot Test",
            Regex("testcontainers", RegexOption.IGNORE_CASE) to "Testcontainers",
        )

    /** The test libraries found among [libraries], in a stable order. */
    fun detect(libraries: Collection<String>): List<String> =
        known.filter { (pattern, _) -> libraries.any(pattern::containsMatchIn) }.map { it.second }.distinct()

    /** The test libraries of [project]. */
    suspend fun of(project: Project): List<String> = detect(smartReadAction(project) { projectLibraries(project) })
}
