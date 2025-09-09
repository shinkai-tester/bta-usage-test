import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinLogger

/**
 * Single, test-focused KotlinLogger that both captures and (optionally) prints
 * BTA logs. This avoids multiple logger implementations and keeps tests clean
 * while still showing the same output style as Main (d:, i:, w:, e:, l:).
 */
@OptIn(ExperimentalBuildToolsApi::class)
class TestLogger(
    private val printToConsole: Boolean = false
) : KotlinLogger {
    private val errorMessages = mutableListOf<String>()
    private val warnMessages = mutableListOf<String>()
    private val infoMessages = mutableListOf<String>()
    private val lifecycleMessages = mutableListOf<String>()
    private val debugMessages = mutableListOf<String>()

    private fun print(prefix: String, msg: String, t: Throwable? = null) {
        if (!printToConsole) return
        println("$prefix $msg")
        if (t != null) println(prefix + t.stackTraceToString())
    }

    // Capture + print error messages
    override fun error(msg: String, throwable: Throwable?) {
        errorMessages.add(msg)
        print("e:", msg, throwable)
    }

    // Capture + print warning messages
    override fun warn(msg: String) {
        warnMessages.add(msg)
        print("w:", msg)
    }

    override fun warn(msg: String, throwable: Throwable?) {
        warnMessages.add(msg)
        print("w:", msg, throwable)
    }

    // Capture + print info messages
    override fun info(msg: String) {
        infoMessages.add(msg)
        print("i:", msg)
    }

    // Capture + print lifecycle messages
    override fun lifecycle(msg: String) {
        lifecycleMessages.add(msg)
        print("l:", msg)
    }

    // Enable debug logging and capture + print debug messages
    override val isDebugEnabled: Boolean = true

    override fun debug(msg: String) {
        debugMessages.add(msg)
        print("d:", msg)
    }

    // Methods to access captured messages
    fun getAllErrorMessages(): List<String> = errorMessages.toList()
    fun getAllWarnMessages(): List<String> = warnMessages.toList()
    fun getAllInfoMessages(): List<String> = infoMessages.toList()
    fun getAllLifecycleMessages(): List<String> = lifecycleMessages.toList()
    fun getAllDebugMessages(): List<String> = debugMessages.toList()

    fun getAllMessages(): Map<String, List<String>> = mapOf(
        "error" to errorMessages.toList(),
        "warn" to warnMessages.toList(),
        "info" to infoMessages.toList(),
        "lifecycle" to lifecycleMessages.toList(),
        "debug" to debugMessages.toList()
    )

    // Specific extraction methods for common patterns
    fun getRetryCount(): Int? {
        // Look for messages like "Failed connecting to the daemon in 4 retries"
        val retryPattern = Regex("""Failed connecting to the daemon in (\d+) retries""")
        return errorMessages.firstNotNullOfOrNull { message ->
            retryPattern.find(message)?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    fun hasCompilationErrors(): Boolean {
        return errorMessages.any { it.contains("compilation", ignoreCase = true) }
    }

    fun hasAnyMessages(): Boolean {
        return errorMessages.isNotEmpty() || warnMessages.isNotEmpty() ||
               infoMessages.isNotEmpty() || lifecycleMessages.isNotEmpty() ||
               debugMessages.isNotEmpty()
    }

    fun clear() {
        errorMessages.clear()
        warnMessages.clear()
        infoMessages.clear()
        lifecycleMessages.clear()
        debugMessages.clear()
    }
}