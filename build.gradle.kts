@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    kotlin("jvm") version "2.3.0-dev-6460"
    kotlin("plugin.power-assert") version "2.3.0-dev-6460"
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.0-dev-6460")
    implementation("org.jetbrains.kotlin:kotlin-build-tools-api:2.3.0-dev-6460")
    runtimeOnly("org.jetbrains.kotlin:kotlin-build-tools-impl:2.3.0-dev-6460")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
}

tasks.test {
    useJUnitPlatform()
}

powerAssert {
    functions = listOf("kotlin.assert", "kotlin.test.assertTrue", "kotlin.test.assertEquals", "kotlin.test.assertNull")
}