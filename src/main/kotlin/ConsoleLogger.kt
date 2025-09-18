import org.jetbrains.kotlin.buildtools.api.KotlinLogger

/**
 * A simple console-based implementation of [KotlinLogger] that outputs log messages to stdout.
 * 
 * This logger formats messages with appropriate prefixes (ERROR, WARN, INFO, DEBUG, LIFECYCLE)
 * and optionally includes throwable information when provided.
 * 
 * @param isDebugEnabled Whether debug messages should be printed. When false, debug messages are ignored.
 */
class ConsoleLogger(override val isDebugEnabled: Boolean = false) : KotlinLogger {
    override fun error(msg: String, throwable: Throwable?) =
        println("ERROR: $msg${throwable?.let { " - $it" } ?: ""}")
    override fun warn(msg: String, throwable: Throwable?) =
        println("WARN: $msg${throwable?.let { " - $it" } ?: ""}")
    override fun info(msg: String) = println("INFO: $msg")
    override fun debug(msg: String) = if (isDebugEnabled) println("DEBUG: $msg") else Unit
    override fun lifecycle(msg: String) = println("LIFECYCLE: $msg")
}