plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "io.github.pavelcpp.poeditor"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

// Build and verify against an installed IDE with -PideDir="C:/Program Files/JetBrains/CLion 2026.2.2",
// otherwise a matching IntelliJ IDEA is downloaded (so a fresh clone and CI work without any setup).
val ideDir: String? = providers.gradleProperty("ideDir").orNull

dependencies {
    intellijPlatform {
        if (ideDir != null) local(ideDir) else intellijIdea("2026.2")
    }
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion { sinceBuild = "262" } // 2026.2 and newer, no upper bound
        changeNotes = """
            <ul>
                <li>Visual editor for <code>.po</code> files: entry list, search and filters, plural forms, "needs work" flag, translator comments, source references</li>
                <li>Poedit's keyboard shortcuts, rebindable in Settings | Keymap</li>
                <li>Machine translation suggestions from DeepL and MyMemory</li>
            </ul>
        """.trimIndent()
    }

    // Secrets come from the environment, never from the repository.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    pluginVerification {
        ides { if (ideDir != null) local(ideDir) else recommended() }
    }
}
