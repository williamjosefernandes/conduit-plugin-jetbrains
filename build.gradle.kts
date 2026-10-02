import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

allprojects {
    group = providers.gradleProperty("pluginGroup").get()
    version = providers.gradleProperty("pluginVersion").get()
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
        apply(plugin = "io.gitlab.arturbosch.detekt")

        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(21)
            compilerOptions {
                // The 2025.3 baseline bundles stdlib 2.2.20: never use newer language or stdlib API.
                languageVersion.set(KotlinVersion.KOTLIN_2_2)
                apiVersion.set(KotlinVersion.KOTLIN_2_2)
                allWarningsAsErrors.set(true)
            }
        }

        extensions.configure<DetektExtension> {
            buildUponDefaultConfig = true
            config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        }
    }
}
