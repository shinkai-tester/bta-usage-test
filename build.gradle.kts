@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import java.io.File

plugins {
    kotlin("jvm") version "2.3.0-Beta1"
    kotlin("plugin.power-assert") version "2.3.0-Beta1"
}

configurations {
    create("myCompiler")
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.0-Beta1")
    implementation("org.jetbrains.kotlin:kotlin-build-tools-api:2.3.0-Beta1")
    runtimeOnly("org.jetbrains.kotlin:kotlin-reflect:2.3.0-Beta1")
    // Keep compiler impl off the app classpath; they will be passed explicitly at runtime
    "myCompiler"("org.jetbrains.kotlin:kotlin-build-tools-impl:2.3.0-Beta1")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.0")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("junit.jupiter.testclass.order.default", $$"org.junit.jupiter.api.ClassOrderer$OrderAnnotation")
    // Provide an isolated impl classpath to tests for deterministic behavior
    systemProperty(
        "compiler.impl.classpath",
        configurations.getByName("myCompiler").files.joinToString(File.pathSeparator) { it.absolutePath }
    )
}

// Run the sample MainKt and pass the compiler implementation classpath as args
tasks.register<JavaExec>("run") {
    group = "application"
    description = "Runs MainKt with isolated compiler implementation classpath"
    mainClass.set("MainKt")
    classpath = sourceSets.main.get().runtimeClasspath
    args = configurations.getByName("myCompiler").files.map { it.absolutePath }
}

powerAssert {
    functions = listOf("kotlin.assert", "kotlin.test.assertTrue", "kotlin.test.assertEquals", "kotlin.test.assertNull")
}