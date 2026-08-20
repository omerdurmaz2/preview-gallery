import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        local(providers.gradleProperty("platformLocalPath"))
        bundledPlugins("org.jetbrains.kotlin", "org.jetbrains.android", "com.android.tools.design")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        // What this build contains, taken from CHANGELOG.md and shown in Settings > Plugins. The changelog
        // plugin was already applied and unused; a zip handed round a team is exactly the case where "which
        // build is this and what is in it" has to be answerable from inside the IDE.
        changeNotes = provider {
            with(changelog) {
                renderItem(
                    (getOrNull(version.get()) ?: getUnreleased()).withHeader(false).withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }
        ideaVersion {
            sinceBuild = "253"
            // Left open on purpose: an aggressive untilBuild turns a soft
            // rendering failure into a plugin that refuses to load.
            untilBuild = provider { null }
        }
    }
}
