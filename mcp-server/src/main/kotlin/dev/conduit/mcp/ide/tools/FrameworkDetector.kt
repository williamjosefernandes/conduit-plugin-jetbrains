package dev.conduit.mcp.ide.tools

/**
 * Tells which frameworks a project uses from two cheap signals: the names of its libraries and
 * the content of a few well-known files. Pure, so it is the same in every IDE edition.
 */
object FrameworkDetector {
    /** Files worth reading, relative to a project or module root. */
    val PROBED_FILES =
        listOf(
            "package.json",
            "requirements.txt",
            "pyproject.toml",
            "composer.json",
            "pom.xml",
            "build.gradle",
            "build.gradle.kts",
            "manage.py",
            "artisan",
            "app/src/main/AndroidManifest.xml",
            "src/main/AndroidManifest.xml",
        )

    private class Rule(
        val framework: String,
        val library: Regex? = null,
        val file: String? = null,
        val content: Regex? = null,
    )

    private val rules =
        listOf(
            Rule("Quarkus", library = Regex("io\\.quarkus[:.]|quarkus-core")),
            Rule("Quarkus", file = "pom.xml", content = Regex("io\\.quarkus")),
            Rule("Quarkus", file = "build.gradle", content = Regex("io\\.quarkus")),
            Rule("Quarkus", file = "build.gradle.kts", content = Regex("io\\.quarkus")),
            Rule("Spring Boot", library = Regex("spring-boot")),
            Rule("Spring Boot", file = "pom.xml", content = Regex("spring-boot")),
            Rule("Spring Boot", file = "build.gradle", content = Regex("org\\.springframework\\.boot")),
            Rule("Spring Boot", file = "build.gradle.kts", content = Regex("org\\.springframework\\.boot")),
            Rule("NestJS", file = "package.json", content = Regex("\"@nestjs/core\"")),
            Rule("Next.js", file = "package.json", content = Regex("\"next\"\\s*:")),
            Rule("React", file = "package.json", content = Regex("\"react\"\\s*:")),
            Rule("Angular", file = "package.json", content = Regex("\"@angular/core\"")),
            Rule("Vue", file = "package.json", content = Regex("\"vue\"\\s*:")),
            Rule("Express", file = "package.json", content = Regex("\"express\"\\s*:")),
            Rule("Django", file = "manage.py", content = Regex("django", RegexOption.IGNORE_CASE)),
            Rule("Django", file = "requirements.txt", content = Regex("(?im)^django\\b")),
            Rule("Django", file = "pyproject.toml", content = Regex("(?i)\\bdjango\\b")),
            Rule("FastAPI", file = "requirements.txt", content = Regex("(?im)^fastapi\\b")),
            Rule("FastAPI", file = "pyproject.toml", content = Regex("(?i)\\bfastapi\\b")),
            Rule("Flask", file = "requirements.txt", content = Regex("(?im)^flask\\b")),
            Rule("Laravel", file = "composer.json", content = Regex("\"laravel/framework\"")),
            Rule("Laravel", file = "artisan", content = Regex("(?i)laravel|Illuminate")),
            Rule("Android", file = "app/src/main/AndroidManifest.xml", content = Regex("<manifest")),
            Rule("Android", file = "src/main/AndroidManifest.xml", content = Regex("<manifest")),
            Rule("Android", library = Regex("com\\.android\\.|androidx\\.")),
        )

    /**
     * @param libraries names of the project's libraries as the IDE lists them
     * @param files content of the [PROBED_FILES] that exist, by relative path
     * @return detected framework names, sorted, without duplicates
     */
    fun detect(
        libraries: Collection<String>,
        files: Map<String, String>,
    ): List<String> =
        rules
            .filter { rule ->
                val byLibrary = rule.library?.let { regex -> libraries.any(regex::containsMatchIn) } == true
                val byFile = rule.file?.let(files::get)?.let { text -> rule.content?.containsMatchIn(text) } == true
                byLibrary || byFile
            }.map { it.framework }
            .distinct()
            .sorted()
}
