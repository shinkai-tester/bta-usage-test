pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
        maven("https://redirector.kotlinlang.org/maven/dev")
        google()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        maven("https://redirector.kotlinlang.org/maven/dev")
        google()
    }
}

rootProject.name = "bta-usage-test"