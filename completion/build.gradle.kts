import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    id("org.jetbrains.intellij.platform.module")
}

dependencies {
    api(project(":core"))
    api(project(":llm-fast"))
    implementation(project(":ide"))

    testImplementation(libs.junit4)

    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        testBundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
    }
}
