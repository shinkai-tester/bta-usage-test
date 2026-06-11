package framework

import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.arguments.ExperimentalCompilerArgument
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import utils.StdlibUtils
import java.io.File
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Shared helpers for loading the Kotlin toolchain and configuring compiler arguments in tests.
 */

/**
 * Loads the Kotlin toolchain using the isolated implementation classpath provided to the tests
 * (via the `compiler.impl.classpath` system property, falling back to a scan of the process classpath).
 */
@OptIn(ExperimentalBuildToolsApi::class)
fun loadToolchain(): KotlinToolchains = KotlinToolchains.loadImplementation(discoverImplClasspath())

/**
 * Loads the Kotlin toolchain from an explicit classpath string (paths separated by the system path separator).
 * Useful for testing against a different compiler version.
 */
@OptIn(ExperimentalBuildToolsApi::class)
fun loadToolchain(classpath: String): KotlinToolchains {
    val paths = classpath.split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .map { Path(it) }
    return KotlinToolchains.loadImplementation(paths)
}

/**
 * Applies the basic compiler arguments shared by every test compilation:
 * `NO_STDLIB`, `NO_REFLECT`, `CLASSPATH` (with kotlin-stdlib) and `MODULE_NAME`.
 */
@OptIn(ExperimentalBuildToolsApi::class)
fun JvmCompilerArguments.Builder.applyBasicCompilerArguments(moduleName: String = "test-module") {
    this[JvmCompilerArguments.NO_STDLIB] = true
    this[JvmCompilerArguments.NO_REFLECT] = true

    val classpathPaths = listOfNotNull(StdlibUtils.findStdlibJar())
        .filter { it.isNotBlank() && Path(it).exists() }
        .map { Path(it) }
    this[JvmCompilerArguments.CLASSPATH] = classpathPaths

    this[JvmCompilerArguments.MODULE_NAME] = moduleName
}

/**
 * Wires up JDK settings (`JDK_HOME` and, when available, the JDK module path) so that Java sources
 * from the same module can be resolved during compilation.
 */
@OptIn(ExperimentalBuildToolsApi::class, ExperimentalCompilerArgument::class)
fun JvmCompilerArguments.Builder.applyJdkSettings() {
    val javaHomeProp = System.getProperty("java.home") ?: return
    val javaHome = Path(javaHomeProp)
    val isJre = javaHome.fileName?.toString()?.equals("jre", ignoreCase = true) == true
    val jdkHome = if (isJre) (javaHome.parent ?: javaHome) else javaHome
    if (jdkHome.isDirectory()) {
        this[JvmCompilerArguments.JDK_HOME] = jdkHome
        val jmods = jdkHome.resolve("jmods")
        if (jmods.isDirectory()) {
            this[JvmCompilerArguments.X_MODULE_PATH] = listOf(jmods)
            this[JvmCompilerArguments.X_ADD_MODULES] = listOf("ALL-MODULE-PATH")
        }
    }
}

/**
 * Standard compiler-argument configuration for a test compilation: [applyBasicCompilerArguments] plus
 * [applyJdkSettings].
 */
@OptIn(ExperimentalBuildToolsApi::class)
fun JvmCompilerArguments.Builder.applyTestDefaults(moduleName: String = "test-module") {
    applyBasicCompilerArguments(moduleName)
    applyJdkSettings()
}

private fun discoverImplClasspath(): List<Path> {
    val prop = System.getProperty("compiler.impl.classpath")?.takeIf { it.isNotBlank() }
    val candidates: List<String> = prop?.split(File.pathSeparator)
        ?: // Fallback: scan the current process classpath for impl/compat jars
        System.getProperty("java.class.path").orEmpty().split(File.pathSeparator)
            .filter { path ->
                val name = Path(path).name
                name.contains("kotlin-build-tools-impl") || name.contains("kotlin-build-tools-compat")
            }
    val paths = candidates
        .filter { it.isNotBlank() }
        .mapNotNull { runCatching { Path(it) }.getOrNull() }
    check(paths.isNotEmpty()) {
        "No Kotlin compiler implementation jars found. Pass them as program args or set -Dcompiler.impl.classpath."
    }
    return paths
}
