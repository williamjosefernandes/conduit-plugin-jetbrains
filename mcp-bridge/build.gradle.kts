plugins {
    java
}

// Runs outside the IDE on the IDE's bundled runtime, so it has no dependencies at all.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.jar {
    manifest {
        attributes("Main-Class" to "dev.conduit.mcp.bridge.StdioBridge")
    }
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
