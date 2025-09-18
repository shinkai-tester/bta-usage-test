import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Utility for resolving kotlin-stdlib dependencies for compilation.
 * 
 * This class handles finding the appropriate kotlin-stdlib jar that matches
 * the compiler version, first checking the Gradle cache and falling back
 * to the current process classpath if needed.
 */
object StdlibResolver {
    
    /**
     * Configures the classpath for the given compiler arguments by finding
     * and setting the appropriate kotlin-stdlib jar.
     * 
     * @param compilerArguments The JVM compiler arguments to configure
     * @param compilerVersion The version of the Kotlin compiler being used
     */
    fun configureStdlibClasspath(compilerArguments: JvmCompilerArguments, compilerVersion: String) {
        val stdlibFromCache = findStdlibInGradleCache(compilerVersion)
        when {
            stdlibFromCache != null -> {
                println("✓ Using kotlin-stdlib $compilerVersion from Gradle cache: $stdlibFromCache")
                compilerArguments[JvmCompilerArguments.CLASSPATH] = stdlibFromCache
            }
            else -> {
                val cpUrls = ClasspathUtils.buildCurrentProcessClasspathUrls()
                val stdlibEntries = cpUrls.map { it.file }.filter { it.contains("kotlin-stdlib") }
                when {
                    stdlibEntries.isNotEmpty() -> {
                        println("⚠ kotlin-stdlib $compilerVersion not found in Gradle cache")
                        println("  Falling back to process classpath stdlib(s): ${stdlibEntries.joinToString()}")
                        compilerArguments[JvmCompilerArguments.CLASSPATH] = stdlibEntries.joinToString(File.pathSeparator)
                    }
                    else -> {
                        println("❌ No kotlin-stdlib found anywhere - compilation will likely fail")
                    }
                }
            }
        }
    }
    
    /**
     * Attempts to find the kotlin-stdlib jar in the Gradle cache for the specified version.
     * 
     * @param version The Kotlin version to search for
     * @return The path to the stdlib jar if found, null otherwise
     */
    private fun findStdlibInGradleCache(version: String): String? {
        val userHome = System.getProperty("user.home") ?: return null
        val base = Paths.get(userHome, ".gradle", "caches", "modules-2", "files-2.1", "org.jetbrains.kotlin", "kotlin-stdlib", version)
        if (!Files.exists(base)) return null
        
        // Prefer jar with exact file name match kotlin-stdlib-<version>.jar, otherwise take any jar found
        val jar = Files.walk(base).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".jar") }
                .sorted()
                .toList()
                .firstOrNull { it.fileName.toString() == "kotlin-stdlib-$version.jar" }
                ?: run {
                    Files.walk(base).use { s2 ->
                        s2.filter { Files.isRegularFile(it) && it.toString().endsWith(".jar") }
                            .findFirst()
                            .orElse(null)
                    }
                }
        }
        return jar?.toString()
    }
}