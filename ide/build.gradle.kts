import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    alias(libs.plugins.kotlin.jvm)
    id("org.jetbrains.intellij.platform.module")
}

dependencies {
    api(project(":core"))
    api(project(":agents-api"))
    api(project(":agents-acp"))
    api(project(":policy"))
    api(project(":orchestrator"))
    api(project(":mcp-server"))
    implementation(project(":mcp-bridge"))

    testImplementation(libs.junit4)

    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        testBundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
    }
}

// Replays of real CLI sessions drive the session tests; the recordings live in :testing.
sourceSets.test {
    resources.srcDir(rootProject.file("testing/src/main/resources"))
}
