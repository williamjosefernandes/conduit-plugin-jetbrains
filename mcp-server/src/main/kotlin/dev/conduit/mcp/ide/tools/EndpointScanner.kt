package dev.conduit.mcp.ide.tools

/** An HTTP route found in source code. */
data class Endpoint(
    val method: String,
    val path: String,
    val line: Int,
)

/**
 * Finds HTTP endpoints by recognising how the common web frameworks declare them. It is a text
 * heuristic that works in every IDE edition; framework packs replace it with exact, PSI-based
 * discovery where the framework is known.
 *
 * Covers Spring MVC, JAX-RS (Quarkus, Jakarta REST), NestJS, Express-style routers, Flask and
 * FastAPI decorators, Django `urls.py` and Laravel routes.
 */
object EndpointScanner {
    private val spring = Regex("""@(Get|Post|Put|Delete|Patch|Request)Mapping\b(?:\s*\(([^)]*)\))?""")
    private val jaxRsVerb = Regex("""^\s*@(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\b""")
    private val jaxRsPath = Regex("""@Path\(\s*"([^"]*)"\s*\)""")
    private val nestController = Regex("""@Controller\(\s*(?:['"`]([^'"`]*)['"`])?\s*\)""")
    private val nestVerb = Regex("""@(Get|Post|Put|Delete|Patch|Head|Options|All)\(\s*(?:['"`]([^'"`]*)['"`])?\s*\)""")
    private val express = Regex("""\b(?:app|router|server|api)\.(get|post|put|delete|patch|all)\(\s*['"`]([^'"`]+)['"`]""")
    private val pythonDecorator = Regex("""@\w+(?:\.\w+)*\.(get|post|put|delete|patch|route|websocket)\(\s*['"]([^'"]+)['"]""")
    private val djangoPath = Regex("""\b(?:path|re_path)\(\s*r?['"]([^'"]*)['"]""")
    private val laravel = Regex("""Route::(get|post|put|delete|patch|any)\(\s*['"]([^'"]+)['"]""")
    private val quoted = Regex(""""([^"]*)"""")
    private val classDeclaration = Regex("""\b(class|interface)\s+\w+""")

    /** Endpoints declared in [text]; [fileName] selects which frameworks' patterns apply. */
    fun scan(
        fileName: String,
        text: String,
    ): List<Endpoint> {
        val lines = text.lines()
        return when (fileName.substringAfterLast('.').lowercase()) {
            "java", "kt", "groovy", "scala" -> jvm(lines)
            "ts", "js", "mjs", "cjs", "tsx", "jsx" -> javascript(lines)
            "py" -> python(fileName, lines)
            "php" -> match(lines, laravel)
            else -> emptyList()
        }
    }

    private fun jvm(lines: List<String>): List<Endpoint> {
        val classLine = lines.indexOfFirst { classDeclaration.containsMatchIn(it) }.let { if (it < 0) lines.size else it }
        val head = lines.take(classLine)
        val springPrefix =
            head
                .firstNotNullOfOrNull {
                    spring.find(it)?.takeIf { m ->
                        m.groupValues[1] == "Request"
                    }
                }?.let(::springPath)
                .orEmpty()
        val jaxPrefix = head.firstNotNullOfOrNull { jaxRsPath.find(it)?.groupValues?.get(1) }.orEmpty()

        val endpoints = ArrayList<Endpoint>()
        lines.forEachIndexed { index, line ->
            if (index < classLine) return@forEachIndexed
            spring.find(line)?.let { match ->
                val verb = match.groupValues[1].uppercase().let { if (it == "REQUEST") "ANY" else it }
                endpoints += Endpoint(verb, join(springPrefix, springPath(match)), index + 1)
            }
            jaxRsVerb.find(line)?.let { match ->
                val own = annotationBlock(lines, index).firstNotNullOfOrNull { jaxRsPath.find(it)?.groupValues?.get(1) }.orEmpty()
                endpoints += Endpoint(match.groupValues[1], join(jaxPrefix, own), index + 1)
            }
        }
        return endpoints
    }

    /** The run of annotation lines around [index]: the annotations of one method. */
    private fun annotationBlock(
        lines: List<String>,
        index: Int,
    ): List<String> {
        fun isAnnotation(i: Int) = lines.getOrNull(i)?.trimStart()?.startsWith("@") == true
        var first = index
        while (isAnnotation(first - 1)) first--
        var last = index
        while (isAnnotation(last + 1)) last++
        return lines.subList(first, last + 1)
    }

    /** The path of a Spring mapping annotation: its first string literal, or empty. */
    private fun springPath(match: MatchResult): String =
        quoted
            .find(match.groupValues[2])
            ?.groupValues
            ?.get(1)
            .orEmpty()

    private fun javascript(lines: List<String>): List<Endpoint> {
        val prefix = lines.firstNotNullOfOrNull { nestController.find(it)?.groupValues?.get(1) }
        val endpoints = ArrayList<Endpoint>()
        lines.forEachIndexed { index, line ->
            if (prefix != null) {
                nestVerb
                    .find(
                        line,
                    )?.let { endpoints += Endpoint(it.groupValues[1].uppercase(), join(prefix, it.groupValues[2]), index + 1) }
            }
            express.find(line)?.let { endpoints += Endpoint(it.groupValues[1].uppercase(), it.groupValues[2], index + 1) }
        }
        return endpoints
    }

    private fun python(
        fileName: String,
        lines: List<String>,
    ): List<Endpoint> {
        val endpoints = ArrayList(match(lines, pythonDecorator))
        if (fileName.substringAfterLast('/').substringAfterLast('\\') == "urls.py") {
            lines.forEachIndexed { index, line ->
                djangoPath.find(line)?.let { endpoints += Endpoint("ANY", "/" + it.groupValues[1], index + 1) }
            }
        }
        return endpoints
    }

    private fun match(
        lines: List<String>,
        regex: Regex,
    ): List<Endpoint> =
        lines.mapIndexedNotNull { index, line ->
            regex.find(line)?.let {
                val verb = it.groupValues[1].uppercase().let { v -> if (v == "ROUTE" || v == "ANY") "ANY" else v }
                Endpoint(verb, it.groupValues[2], index + 1)
            }
        }

    private fun join(
        prefix: String,
        path: String,
    ): String {
        val joined = listOf(prefix, path).map { it.trim('/') }.filter { it.isNotEmpty() }.joinToString("/")
        return "/$joined"
    }
}
