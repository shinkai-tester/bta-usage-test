@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    kotlin("jvm") version "2.3.255-SNAPSHOT"
}
configurations {
    create("myCompiler")
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-build-tools-api:2.3.255-SNAPSHOT")

    "myCompiler"("org.jetbrains.kotlin:kotlin-build-tools-impl:2.0.20")
    "myCompiler"("org.jetbrains.kotlin:kotlin-build-tools-compat:2.3.255-SNAPSHOT")
}

tasks.register<JavaExec>("run") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("MainKt")
    args = configurations.getByName("myCompiler").files.map { it.absolutePath }
}