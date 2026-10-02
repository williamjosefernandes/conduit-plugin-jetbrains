package dev.conduit.ide.packs

import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.conduit.ide.settings.ConduitSettings
import dev.conduit.mcp.ide.tools.detectFrameworks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Conventions for one framework, given to the agent when the project uses it, so that generated
 * code and tests follow the framework's idioms instead of generic ones.
 *
 * @property framework the name the framework detector reports
 * @property testCommand how the framework's tests are usually run
 */
data class FrameworkPack(
    val id: String,
    val name: String,
    val framework: String,
    val guidance: String,
    val testCommand: String,
)

/** The packs Conduit ships. */
object Packs {
    val ALL: List<FrameworkPack> =
        listOf(
            FrameworkPack(
                "spring",
                "Spring Boot",
                "Spring Boot",
                "Spring Boot project: use constructor injection, @RestController with validated (@Valid) request DTOs, a service " +
                    "layer for business rules, repositories for persistence, @ControllerAdvice for error mapping, and " +
                    "@WebMvcTest/@SpringBootTest with MockMvc for tests (MockK in Kotlin code, Mockito in Java code).",
                "./gradlew test (or ./mvnw test)",
            ),
            FrameworkPack(
                "quarkus",
                "Quarkus",
                "Quarkus",
                "Quarkus project: use CDI (@ApplicationScoped, constructor or @Inject), RESTEasy Reactive resources with @Path, " +
                    "Bean Validation on request bodies, Panache for persistence, configuration in application.properties, and " +
                    "@QuarkusTest with RestAssured for tests.",
                "./mvnw test (or ./gradlew test)",
            ),
            FrameworkPack(
                "nestjs",
                "NestJS",
                "NestJS",
                "NestJS project: keep modules, controllers and providers separate, validate DTOs with class-validator and the " +
                    "ValidationPipe, throw Nest HTTP exceptions, and test with Jest and Test.createTestingModule.",
                "npm test",
            ),
            FrameworkPack(
                "react",
                "React",
                "React",
                "React project: function components and hooks, props typed when the project uses TypeScript, no direct DOM " +
                    "manipulation, accessible markup, and tests with Testing Library (queries by role/label).",
                "npm test",
            ),
            FrameworkPack(
                "nextjs",
                "Next.js",
                "Next.js",
                "Next.js project: follow the router the project already uses (app/ or pages/), keep server and client " +
                    "components apart, fetch data on the server where possible.",
                "npm test",
            ),
            FrameworkPack(
                "angular",
                "Angular",
                "Angular",
                "Angular project: components, services with dependency injection, reactive forms with validators, RxJS " +
                    "without nested subscriptions, TestBed for tests.",
                "npm test",
            ),
            FrameworkPack(
                "vue",
                "Vue",
                "Vue",
                "Vue project: single-file components in the API style the project uses (Composition or Options), props and " +
                    "emits declared, tests with Vue Test Utils.",
                "npm test",
            ),
            FrameworkPack(
                "express",
                "Express",
                "Express",
                "Express project: routers per resource, input validation middleware, errors passed to next() and handled by " +
                    "an error middleware, tests with supertest.",
                "npm test",
            ),
            FrameworkPack(
                "django",
                "Django",
                "Django",
                "Django project: apps with models, views and urls, validation in forms or serializers, a migration for every " +
                    "model change (makemigrations), tests with django.test.TestCase.",
                "python manage.py test",
            ),
            FrameworkPack(
                "fastapi",
                "FastAPI",
                "FastAPI",
                "FastAPI project: Pydantic models for request and response bodies, dependencies with Depends, HTTPException " +
                    "for errors, tests with TestClient and pytest.",
                "pytest",
            ),
            FrameworkPack(
                "flask",
                "Flask",
                "Flask",
                "Flask project: blueprints, validated input, application factory if the project has one, tests with the test " +
                    "client and pytest.",
                "pytest",
            ),
            FrameworkPack(
                "laravel",
                "Laravel",
                "Laravel",
                "Laravel project: controllers with FormRequest validation, Eloquent models, a migration for every schema " +
                    "change, policies for authorization, and PHPUnit or Pest feature tests.",
                "php artisan test",
            ),
            FrameworkPack(
                "android",
                "Android",
                "Android",
                "Android project: ViewModels with state exposed as flows, UI in the toolkit the project uses (Compose or " +
                    "Views), dependency injection as configured (Hilt/Koin), coroutines for background work, local unit tests " +
                    "under test/ and instrumented tests under androidTest/.",
                "./gradlew test",
            ),
        )
}

/** Which packs apply to this project. Detection reads build files and library names, nothing else. */
@Service(Service.Level.PROJECT)
class PackService(
    private val project: Project,
) {
    private val detectedState = MutableStateFlow<List<FrameworkPack>?>(null)

    /** Packs whose framework the project uses; null until [detect] ran. */
    val detected: StateFlow<List<FrameworkPack>?> = detectedState.asStateFlow()

    suspend fun detect(): List<FrameworkPack> {
        val frameworks = smartReadAction(project) { detectFrameworks(project) }.toSet()
        val packs = Packs.ALL.filter { it.framework in frameworks }
        detectedState.value = packs
        return packs
    }

    /** Detected packs the user did not switch off. */
    suspend fun active(): List<FrameworkPack> {
        val disabled = ConduitSettings.getInstance().state.disabledPacks
        return (detectedState.value ?: detect()).filter { it.id !in disabled }
    }

    companion object {
        fun getInstance(project: Project): PackService = project.service()
    }
}
