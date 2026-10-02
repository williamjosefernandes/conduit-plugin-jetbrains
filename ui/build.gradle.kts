plugins {
    alias(libs.plugins.kotlin.jvm)
    id("org.jetbrains.intellij.platform.module")
}

dependencies {
    implementation(project(":ide"))

    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
    }
}
