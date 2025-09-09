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

    /**
     * Creates a URLClassLoader over the current process classpath with the system classloader
     * as parent. Parent-first delegation ensures shared API types are not duplicated.
     */
    @JvmStatic
    fun createUrlClassLoaderWithSystemParent(): URLClassLoader {
        val system = ClassLoader.getSystemClassLoader()
        val urls = buildCurrentProcessClasspathUrls()
        return URLClassLoader(urls, system)
    }

    /**
     * Child-first URLClassLoader that delegates specific package prefixes to the parent first.
     * This allows us to share API classes from the parent while loading implementation classes
     * (that might also be present on the parent) from this child to satisfy URLClassLoader
     * expectations in daemon mode.
     */
    class ChildFirstUrlClassLoader(urls: Array<URL>, private val parentCL: ClassLoader, private val parentFirstPrefixes: List<String>) : URLClassLoader(urls, parentCL) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            synchronized(getClassLoadingLock(name)) {
                var c = findLoadedClass(name)
                if (c == null) {
                    // Parent-first for designated API packages
                    if (parentFirstPrefixes.any { name.startsWith(it) }) {
                        try {
                            c = parentCL.loadClass(name)
                        } catch (_: ClassNotFoundException) { /* fallthrough */ }
                    }
                    // Try to find in this classloader first (child-first)
                    if (c == null) {
                        try {
                            c = findClass(name)
                        } catch (_: ClassNotFoundException) { /* ignored */ }
                    }
                    // Fallback to parent chain
                    if (c == null) {
                        c = parentCL.loadClass(name)
                    }
                }
                if (resolve) resolveClass(c)
                return c
            }
        }
    }

    @JvmStatic
    fun createChildFirstUrlClassLoaderWithSystemParent(parentFirstPrefixes: List<String>): URLClassLoader {
        val parent = ClassLoader.getSystemClassLoader()
        val urls = buildCurrentProcessClasspathUrls()
        return ChildFirstUrlClassLoader(urls, parent, parentFirstPrefixes)
    }
}
