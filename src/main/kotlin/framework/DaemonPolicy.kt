package framework

import org.jetbrains.kotlin.buildtools.api.ExecutionPolicy
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains

/**
 * Creates and configures a daemon execution policy with JVM arguments and shutdown delay.
 * Uses the proper BTA builder API instead of reflection.
 *
 * @param toolchains The Kotlin toolchains instance to create the daemon policy from
 * @param jvmArgs List of JVM arguments to pass to the daemon (e.g., "Xmx3g", "Xms1g")
 * @param shutdownDelayMs Shutdown delay in milliseconds (default: 1000ms)
 * @return Configured ExecutionPolicy.WithDaemon instance
 */
@OptIn(ExperimentalBuildToolsApi::class)
fun configureDaemonPolicy(
    toolchains: KotlinToolchains,
    jvmArgs: List<String> = listOf("Xmx3g", "Xms1g"),
    shutdownDelayMs: Long = 1000
): ExecutionPolicy {
    return toolchains.daemonExecutionPolicyBuilder().apply {
        this[ExecutionPolicy.WithDaemon.JVM_ARGUMENTS] = jvmArgs
        this[ExecutionPolicy.WithDaemon.SHUTDOWN_DELAY_MILLIS] = shutdownDelayMs
    }.build()
}

