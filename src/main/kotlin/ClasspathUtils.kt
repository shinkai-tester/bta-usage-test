import java.io.File
import java.net.URL
import java.net.URLClassLoader

/**
 * Utilities for working with the current process classpath and classloaders.
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