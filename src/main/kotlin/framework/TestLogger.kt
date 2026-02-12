package framework

import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinLogger

/**
 * Test-focused KotlinLogger implementation that captures and optionally prints compilation logs.
 *
 * Captures all log messages (error, warn, info, lifecycle, debug) for later inspection
 * and assertion in tests. Optionally prints messages to the console for debugging.
 *
 * @param printToConsole If true, logs are printed to the console in addition to being captured
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
    override val isDebugEnabled: Boolean = true

    private fun print(prefix: String, msg: String, t: Throwable? = null) {
        if (!printToConsole) return
        println("$prefix $msg")
        if (t != null) println(prefix + t.stackTraceToString())
    }

    override fun error(msg: String, throwable: Throwable?) {
        errorMessages.add(msg)
        if (throwable != null) {
            errorMessages.add(throwable.message ?: throwable.toString())
        }
        print("e:", msg, throwable)
    }

    override fun warn(msg: String) {
        warnMessages.add(msg)
        print("w:", msg)
    }

    override fun warn(msg: String, throwable: Throwable?) {
        warnMessages.add(msg)
        if (throwable != null) {
            warnMessages.add(throwable.message ?: throwable.toString())
        }
        print("w:", msg, throwable)
    }

    override fun info(msg: String) {
        infoMessages.add(msg)
        print("i:", msg)
    }

    override fun lifecycle(msg: String) {
        lifecycleMessages.add(msg)
        print("l:", msg)
    }

    override fun debug(msg: String) {
        debugMessages.add(msg)
        print("d:", msg)
    }

    /** Returns all captured error messages as an immutable list. */
    fun getAllErrorMessages(): List<String> = errorMessages.toList()

    /** Returns all captured warning messages as an immutable list. */
    fun getAllWarnMessages(): List<String> = warnMessages.toList()

    /** Returns all captured info messages as an immutable list. */
    fun getAllInfoMessages(): List<String> = infoMessages.toList()

    /** Returns all captured lifecycle messages as an immutable list. */
    fun getAllLifecycleMessages(): List<String> = lifecycleMessages.toList()

    /** Returns all captured debug messages as an immutable list. */
    fun getAllDebugMessages(): List<String> = debugMessages.toList()

    /** Returns all captured messages grouped by log level. */
    fun getAllMessages(): Map<String, List<String>> = mapOf(
        "error" to errorMessages.toList(),
        "warn" to warnMessages.toList(),
        "info" to infoMessages.toList(),
        "lifecycle" to lifecycleMessages.toList(),
        "debug" to debugMessages.toList()
    )

}