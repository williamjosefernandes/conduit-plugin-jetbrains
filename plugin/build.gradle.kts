import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    alias(libs.plugins.kotlin.jvm)
    id("org.jetbrains.intellij.platform")
}

dependencies {
    implementation(project(":core"))

    testImplementation(libs.junit4)

    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        // Platform modules share the plugin's jar and plugin.xml; pure modules ship as jars in lib/.
        pluginComposedModule(implementation(project(":ui")))
        pluginComposedModule(implementation(project(":ide")))
        pluginComposedModule(implementation(project(":mcp-server")))
        pluginComposedModule(implementation(project(":completion")))

        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    projectName = "conduit"

    pluginConfiguration {
        id = "dev.conduit"
        name = "Conduit"
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // Open-ended: compatibility with new IDEs is checked by the verifier matrix, not capped here.
            untilBuild = provider { null }
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    pluginVerification {
        ides {
            providers.gradleProperty("verifyIdeVersions").get().split(',').forEach { version ->
                create(IntelliJPlatformType.IntellijIdea, version.trim())
            }
        }
    }
}

tasks.verifyPlugin {
    // Keep the verifier's working files inside the project: its default home (~/.pluginVerifier) is
    // shared by every project on the machine, and concurrent runs delete each other's files.
    systemProperty(
        "plugin.verifier.home.dir",
        rootProject.layout.projectDirectory
            .dir(".intellijPlatform/pluginVerifier")
            .asFile.absolutePath,
    )
}
