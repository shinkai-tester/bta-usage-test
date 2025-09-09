import org.jetbrains.kotlin.buildtools.api.ExecutionPolicy
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import kotlin.time.Duration.Companion.milliseconds

// Extension function to configure daemon execution policy with JVM arguments and shutdown delay
@OptIn(ExperimentalBuildToolsApi::class)
fun ExecutionPolicy.configureDaemon(
    jvmArgs: List<String> = listOf("-Xmx3g", "-Xms1g"),
    shutdownDelayMs: Long = 1000
) {
    // Use reflection to access the daemon policy fields directly
    runCatching {
        val jvmArgsKey = this.javaClass.getField("JVM_ARGUMENTS").get(null)
        val shutdownKey = this.javaClass.getField("SHUTDOWN_DELAY").get(null)
        
        val setMethod = this.javaClass.methods.firstOrNull { method ->
            method.name == "set" && 
            method.parameterCount == 2 && 
            method.parameterTypes[0].name == "org.jetbrains.kotlin.buildtools.api.ExecutionPolicy\$WithDaemon\$Option"
        } ?: error("No setter method to configure daemon policy with ExecutionPolicy\$WithDaemon\$Option")
        
        // Set JVM args (already a list as expected by the daemon)
        setMethod.invoke(this, jvmArgsKey, jvmArgs)
        
        // Set shutdown delay using Kotlin Duration
        runCatching {
            val duration = shutdownDelayMs.milliseconds
            setMethod.invoke(this, shutdownKey, duration)
        }.getOrElse {
            // Fallback to milliseconds if Duration fails
            setMethod.invoke(this, shutdownKey, shutdownDelayMs)
        }
    }
}

