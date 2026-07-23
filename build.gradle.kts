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
        bundledPlugins("org.jetbrains.kotlin", "org.jetbrains.android")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
            // Left open on purpose: an aggressive untilBuild turns a soft
            // rendering failure into a plugin that refuses to load.
            untilBuild = provider { null }
        }
    }
}
