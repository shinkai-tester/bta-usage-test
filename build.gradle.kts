@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    kotlin("jvm") version "2.3.0-dev-7798"
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-build-tools-api:2.3.0-dev-7798")

    runtimeOnly("org.jetbrains.kotlin:kotlin-build-tools-compat:2.3.0-dev-7798")
    runtimeOnly("org.jetbrains.kotlin:kotlin-build-tools-impl:2.2.20-RC2")
    /*
    Tried 2.0.21 and 2.1.21 ->
    Exception in thread "main" java.lang.NoSuchFieldError: Companion
	at org.jetbrains.kotlin.buildtools.internal.compat.KotlinToolchainV1Adapter.createBuildSession(KotlinToolchainV1Adapter.kt:68)
	at MainKt.main(Main.kt:114)
	at MainKt.main(Main.kt)
     */
}

