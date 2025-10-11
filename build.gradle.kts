@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    kotlin("jvm") version "2.3.0-Beta1"
}
configurations {
    create("myCompiler")
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-build-tools-api:2.3.0-Beta1")

    "myCompiler"("org.jetbrains.kotlin:kotlin-build-tools-impl:2.2.20")
    "myCompiler"("org.jetbrains.kotlin:kotlin-build-tools-compat:2.3.0-Beta1")
    
    testImplementation(kotlin("test"))
}

tasks.register<JavaExec>("run") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("MainKt")
    args = configurations.getByName("myCompiler").files.map { it.absolutePath }
}

tasks.register<JavaExec>("runErrorDemo") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("ErrorMessageDemoKt")
    args = configurations.getByName("myCompiler").files.map { it.absolutePath }
}

tasks.test {
    systemProperty("test.compiler.classpath", configurations.getByName("myCompiler").files.joinToString(File.pathSeparator) { it.absolutePath })
}