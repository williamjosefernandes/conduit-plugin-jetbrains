plugins {
    alias(libs.plugins.kotlin.jvm)
    id("org.jetbrains.intellij.platform.module")
}

dependencies {
    implementation(project(":core"))

    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
    }
}
