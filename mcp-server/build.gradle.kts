import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    alias(libs.plugins.kotlin.jvm)
    id("org.jetbrains.intellij.platform.module")
}

dependencies {
    api(project(":core"))
    api(project(":policy"))
    api(project(":mcp-protocol"))

    testImplementation(libs.junit4)

    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        // Java is only on the test classpath: the tools themselves must work in every IDE.
        testBundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
    }
}
