plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.pokedex"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Built against IntelliJ IDEA Community. The plugin declares no
        // language-specific module dependency, so the same artifact installs
        // on PyCharm, WebStorm, GoLand, Rider and Android Studio.
        create("IC", providers.gradleProperty("platformVersion"))
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    // Nothing in this project uses form files or @NotNull instrumentation.
    instrumentCode = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "233"
            // Left open so a new IDE release does not mark the plugin
            // incompatible before it has been verified.
            untilBuild = provider { null }
        }
    }

    // Credentials are read from the environment and never committed.
    signing {
        certificateChainFile = providers.environmentVariable("CERTIFICATE_CHAIN").map { file(it) }
        privateKeyFile = providers.environmentVariable("PRIVATE_KEY").map { file(it) }
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    runIde {
        jvmArgs("-Xmx2g")
    }
}
