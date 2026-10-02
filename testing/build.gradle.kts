plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Test support only: never packaged into the plugin, so it carries its own runtime libraries.
dependencies {
    implementation(project(":agents-acp"))
    implementation(kotlin("stdlib"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// Records a session against a CLI installed on this machine:
//   ./gradlew :testing:record -Pagent=claude-code -Pscenario=hello
tasks.register<JavaExec>("record") {
    group = "verification"
    description = "Records an ACP session of a locally installed agent CLI for replay tests."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "dev.conduit.testing.RecordSessionKt"
    args(
        providers.gradleProperty("agent").getOrElse("claude-code"),
        providers.gradleProperty("scenario").getOrElse("hello"),
        layout.projectDirectory
            .dir("src/main/resources/recordings")
            .asFile.absolutePath,
        providers.gradleProperty("model").getOrElse(""),
    )
}
