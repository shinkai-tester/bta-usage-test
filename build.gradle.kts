@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    kotlin("jvm") version "2.3.0-dev-7798"
    kotlin("plugin.power-assert") version "2.3.0-dev-7798"
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.0-dev-7798")
    implementation("org.jetbrains.kotlin:kotlin-build-tools-api:2.3.0-dev-7798")
    runtimeOnly("org.jetbrains.kotlin:kotlin-build-tools-impl:2.3.0-dev-7798")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("junit.jupiter.testclass.order.default", "org.junit.jupiter.api.ClassOrderer\$OrderAnnotation")
}

powerAssert {
    functions = listOf("kotlin.assert", "kotlin.test.assertTrue", "kotlin.test.assertEquals", "kotlin.test.assertNull")
}