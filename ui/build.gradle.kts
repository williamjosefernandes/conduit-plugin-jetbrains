plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.intellij.platform.module")
}

dependencies {
    implementation(project(":ide"))
    implementation(project(":completion"))

    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))

        // Compose and Jewel ship inside the IDE since 2025.1; they are consumed as bundled modules,
        // and plugin.xml declares the same modules as dependencies.
        bundledModule("intellij.libraries.compose.runtime.desktop")
        bundledModule("intellij.libraries.compose.foundation.desktop")
        bundledModule("intellij.libraries.skiko")
        bundledModule("intellij.platform.jewel.foundation")
        bundledModule("intellij.platform.jewel.ui")
        bundledModule("intellij.platform.jewel.ideLafBridge")
        bundledModule("intellij.platform.jewel.markdown.core")
        bundledModule("intellij.platform.jewel.markdown.ideLafBridgeStyling")
    }
}

dependencies {
    testImplementation(libs.junit4)
    intellijPlatform {
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
}

kotlin {
    compilerOptions {
        // Jewel's tooltips and some layout APIs are marked experimental in the bundled Compose; their
        // binary compatibility across 253/261/262 is checked by the Plugin Verifier.
        optIn.addAll("androidx.compose.foundation.ExperimentalFoundationApi", "org.jetbrains.jewel.foundation.ExperimentalJewelApi")
    }
}
