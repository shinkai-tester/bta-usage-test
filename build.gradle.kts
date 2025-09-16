@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    kotlin("jvm") version "2.3.0-dev-7798"
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.0-dev-7798")
    implementation("org.jetbrains.kotlin:kotlin-build-tools-api:2.3.0-dev-7798")

    runtimeOnly("org.jetbrains.kotlin:kotlin-build-tools-compat:2.3.0-dev-7798")
    runtimeOnly("org.jetbrains.kotlin:kotlin-build-tools-impl:2.2.20-RC2") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-build-tools-api")
    }
}

configurations.all {
    resolutionStrategy {
        force(
            "org.jetbrains.kotlin:kotlin-build-tools-api:2.3.0-dev-7798",
            "org.jetbrains.kotlin:kotlin-build-tools-compat:2.3.0-dev-7798"
        )
    }
}
