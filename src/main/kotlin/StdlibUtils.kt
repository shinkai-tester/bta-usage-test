import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.name

/**
 * Utility object for common stdlib and reflect JAR resolution logic.
 * 
 * This eliminates code duplication between Main.kt and ToolchainManager.kt
 * by providing shared functions for locating Kotlin runtime JARs.
 */
object StdlibUtils {
    
    /**
     * Locates the kotlin-stdlib JAR file on the classpath.
     * 
     * @return Path to the kotlin-stdlib JAR file
     * @throws IllegalStateException if the stdlib JAR cannot be located
     */
    fun findStdlibJar(): String {
        val resourceName = KotlinVersion::class.java.name.replace('.', '/') + ".class"
        val url = KotlinVersion::class.java.classLoader.getResource(resourceName)
            ?: error("Could not locate kotlin-stdlib (resource $resourceName not found on classpath)")

        val spec = url.toString()

        if (spec.startsWith("jar:") && spec.contains(".jar!")) {
            val jarPart = spec.substringAfter("jar:").substringBefore("!")
            val path = jarPart.removePrefix("file:")
            val normalized = normalizeFilePath(path)
            if (Path.of(normalized).exists()) return normalized
        } else if (spec.startsWith("file:")) {
            // Class loaded from an exploded directory. Find stdlib jar on classpath.
            val cpEntries = System.getProperty("java.class.path").orEmpty().split(java.io.File.pathSeparator)
            val candidate = cpEntries.firstNotNullOfOrNull { entry ->
                if (entry.contains("kotlin-stdlib") && entry.endsWith(".jar") && Path.of(entry).exists()) entry else null
            }
            if (candidate != null) return candidate
        }

        // Fallback: scan classpath thoroughly for kotlin-stdlib jar
        val cpEntries = System.getProperty("java.class.path").orEmpty().split(java.io.File.pathSeparator)
        val regex = Regex("""kotlin-stdlib(-jdk[0-9]+)?(-\d[\w.+-]*)?\.jar$""")
        val match = cpEntries.firstNotNullOfOrNull { entry ->
            if (entry.isNotBlank() && regex.containsMatchIn(Path.of(entry).name) && Path.of(entry).exists()) entry else null
        }
        if (match != null) return match

        error("Could not locate kotlin-stdlib JAR. URL was: $spec; classpath=" + System.getProperty("java.class.path"))
    }
    
    /**
     * Normalizes a file path for the current operating system.
     * 
     * @param path The file path to normalize
     * @return Normalized file path
     */
    private fun normalizeFilePath(path: String): String {
        var p = path
        // Remove leading slash from Windows drive paths BEFORE converting slashes
        if (java.io.File.separatorChar == '\\' && p.matches(Regex("^/[A-Za-z]:.*"))) {
            p = p.removePrefix("/")
        }
        // Convert forward slashes to platform-specific separators
        return p.replace('/', java.io.File.separatorChar)
    }
}