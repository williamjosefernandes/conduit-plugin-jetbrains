plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // Provided by the IDE at runtime; must not be bundled.
    compileOnly(kotlin("stdlib"))
    compileOnly(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
