package dev.conduit.mcp.ide

import dev.conduit.mcp.ide.tools.Endpoint
import dev.conduit.mcp.ide.tools.EndpointScanner
import dev.conduit.mcp.ide.tools.FrameworkDetector
import org.junit.Assert.assertEquals
import org.junit.Test

/** The text heuristics behind `endpoints` and `frameworks_detected`; no IDE needed. */
class HeuristicsTest {
    @Test
    fun springMappingsAreJoinedWithTheClassPrefix() {
        val source =
            """
            @RestController
            @RequestMapping("/api/users")
            public class UserController {
                @GetMapping
                List<User> all() { return null; }

                @PostMapping(value = "/{id}/activate", consumes = "application/json")
                void activate() {}

                @RequestMapping(path = "legacy")
                void legacy() {}
            }
            """.trimIndent()

        assertEquals(
            listOf(
                Endpoint("GET", "/api/users", 4),
                Endpoint("POST", "/api/users/{id}/activate", 7),
                Endpoint("ANY", "/api/users/legacy", 10),
            ),
            EndpointScanner.scan("UserController.java", source),
        )
    }

    @Test
    fun jaxRsMethodsUseTheirOwnPathWhenPresent() {
        val source =
            """
            @Path("/fruits")
            class FruitResource {
                @GET
                fun list() = fruits

                @DELETE
                @Path("{name}")
                fun remove(name: String) {}
            }
            """.trimIndent()

        assertEquals(
            listOf(Endpoint("GET", "/fruits", 3), Endpoint("DELETE", "/fruits/{name}", 6)),
            EndpointScanner.scan("FruitResource.kt", source),
        )
    }

    @Test
    fun scriptingFrameworksAreRecognised() {
        assertEquals(
            listOf(Endpoint("GET", "/health", 1), Endpoint("POST", "/items", 2)),
            EndpointScanner.scan("server.js", "app.get('/health', handler)\nrouter.post(\"/items\", create)\nconst x = map.get('key')"),
        )
        assertEquals(
            listOf(Endpoint("GET", "/items/{id}", 1), Endpoint("ANY", "/legacy", 4)),
            EndpointScanner.scan("main.py", "@app.get(\"/items/{id}\")\ndef item(id): ...\n\n@bp.route('/legacy')\ndef legacy(): ..."),
        )
        assertEquals(
            listOf(Endpoint("ANY", "/articles/<int:id>/", 2)),
            EndpointScanner.scan("blog/urls.py", "urlpatterns = [\n    path('articles/<int:id>/', views.article),\n]"),
        )
        assertEquals(
            listOf(Endpoint("GET", "/orders", 1), Endpoint("ANY", "/webhook", 2)),
            EndpointScanner.scan(
                "web.php",
                "Route::get('/orders', [OrderController::class, 'index']);\nRoute::any('/webhook', Hook::class);",
            ),
        )
        assertEquals(emptyList<Endpoint>(), EndpointScanner.scan("README.md", "@GetMapping(\"/x\")"))
    }

    @Test
    fun frameworksComeFromLibrariesOrBuildFiles() {
        assertEquals(
            listOf("Quarkus", "Spring Boot"),
            FrameworkDetector.detect(listOf("io.quarkus:quarkus-core:3.20.0", "org.springframework.boot:spring-boot:3.5.0"), emptyMap()),
        )
        assertEquals(
            listOf("Django", "Laravel", "Next.js", "React"),
            FrameworkDetector.detect(
                emptyList(),
                mapOf(
                    "package.json" to """{"dependencies":{"next":"15.0.0","react":"19.0.0","reactive-lib":"1"}}""",
                    "requirements.txt" to "requests==2.0\nDjango>=5.0\n",
                    "composer.json" to """{"require":{"laravel/framework":"^12.0"}}""",
                ),
            ),
        )
        assertEquals(emptyList<String>(), FrameworkDetector.detect(listOf("junit:junit:4.13.2"), mapOf("package.json" to "{}")))
    }
}
