import java.io.File
import java.net.URL

/**
 * Utilities for working with the current process classpath and classloaders.
 *
 * Note: This helper does not detect kotlin-stdlib by itself. It only reads the
 * JVM process classpath (java.class.path) and converts it into URL entries.
 * The BTA sample uses these URLs elsewhere (see Main.kt) to search for any
 * kotlin-stdlib jars on the process classpath as a fallback when an exact
 * stdlib matching the selected compiler version is not found in the Gradle cache.
 */
object ClasspathUtils {
    /**
     * Builds an array of URLs representing the current process classpath (java.class.path),
     * filtering out blank entries and skipping malformed paths.
     */
    @JvmStatic
    fun buildCurrentProcessClasspathUrls(): Array<URL> {
        val classpath = System.getProperty("java.class.path").orEmpty()
        val urls = classpath.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { File(it).toURI().toURL() }.getOrNull() }
            .toTypedArray()
        return urls
    }
}