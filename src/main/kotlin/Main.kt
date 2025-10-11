/**
 * Sample showcasing Kotlin Build Tools API (BTA) usage against different compiler implementations.
 *
 * What this sample does
 * - Loads a Kotlin compiler implementation (kotlin-build-tools-impl) from an explicit classpath
 *   that can point to older/newer compiler distributions.
 * - Uses the stable Build Tools API (BTA, provided by kotlin-build-tools-api) from the application
 *   classpath to compile a tiny Kotlin source on the JVM.
 * - Optionally relies on kotlin-build-tools-compat when the chosen compiler implementation only
 *   provides the previous v1 BTA, enabling a seamless fallback so the same app code keeps working.
 *
 * About compatibility
 * - The BTA itself guarantees compatibility across compiler versions within a window:
 *   it supports the three previous major Kotlin compiler versions and one major version forward.
 *   See official docs: https://kotlinlang.org/docs/build-tools-api.html
 * - When you select an older compiler (via kotlin-build-tools-impl on a custom classpath),
 *   kotlin-build-tools-compat bridges your app's BTA v2 calls to a v1 implementation when needed.
 *
 * How versions are wired here
 * - Application depends on:
 *   - kotlin-build-tools-api: the API your code uses (v2).
 *   - kotlin-build-tools-compat: the bridge to older compiler impls when they only expose v1.
 * - The actual compiler implementation to run is provided via a separate configuration (myCompiler),
 *   so you can switch versions without changing the app code.
 */
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.SharedApiClassesClassLoader
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain.Companion.jvm
import org.jetbrains.kotlin.buildtools.api.arguments.ExperimentalCompilerArgument
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.jetbrains.kotlin.buildtools.api.arguments.enums.JvmTarget
import java.net.URLClassLoader
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Entry point used to demonstrate a minimal BTA-driven compilation.
 *
 * Parameters
 * - args: absolute file paths to jars that form the Kotlin compiler implementation classpath.
 *   In this project, Gradle task `run` wires `configurations.myCompiler` into these arguments,
 *   allowing you to swap the compiler version independently of the app's compile/runtime classpath.
 *
 * Notes
 * - The toolchain is loaded via KotlinToolchains.loadImplementation from the provided classloader.
 * - If the selected compiler only implements BTA v1, kotlin-build-tools-compat will adapt calls
 *   from v2 API used by this program to v1 at runtime.
 */
@OptIn(ExperimentalBuildToolsApi::class, ExperimentalCompilerArgument::class)
fun main(args: Array<String>) {
    println("Compiler classpath: ${args.joinToString(java.io.File.pathSeparator)}")
    
    // Create temporary directories and test source
    val (tmpDir, outDir) = CompilationUtils.createTempDirectories()
    val src = CompilationUtils.createTestSource(
        tmpDir,
        sourceContent = $$"""
            package demo

            data class User(val name: String, val age: Int)

            fun main() {
                val user = User("Alice", 30)
                println("User: ${user.name}, Age: ${user.age}")
            }
        """.trimIndent()
    )

    // Set up the compiler toolchain
    val compilerClassloader = URLClassLoader(args.map { Path.of(it).toUri().toURL() }.toTypedArray(), SharedApiClassesClassLoader())
    val toolchain = KotlinToolchains.loadImplementation(compilerClassloader)
    
    println("toolchain.getCompilerVersion() = ${toolchain.getCompilerVersion()}")

    // Create and configure a compilation operation
    val operation = toolchain.jvm.createJvmCompilationOperation(listOf(src), outDir)
    val compilerArgs = operation.compilerArguments
    
    // Basic compiler configuration
    compilerArgs[JvmCompilerArguments.JVM_TARGET] = JvmTarget.JVM_17
    compilerArgs[JvmCompilerArguments.MODULE_NAME] = "KT-78196-sample"
    compilerArgs[JvmCompilerArguments.NO_REFLECT] = true
    compilerArgs[JvmCompilerArguments.NO_STDLIB] = true

    // Configure stdlib classpath
    StdlibResolver.configureStdlibClasspath(compilerArgs, toolchain.getCompilerVersion())
    
    // Log the classpath being used
    CompilationUtils.logClasspath(compilerArgs)

    // Execute compilation
    val useDaemon = false // set to true to run with Kotlin Daemon; false for in-process
    val policy = if (useDaemon) toolchain.createDaemonExecutionPolicy() else toolchain.createInProcessExecutionPolicy()
    val logger = ConsoleLogger(isDebugEnabled = true)
    
    toolchain.createBuildSession().use { session ->
        val result = session.executeOperation(operation, policy, logger)
        CompilationUtils.reportCompilationResults(result, outDir)
    }

    // Force process termination to prevent hanging
    println("DEBUG: Forcing process termination to prevent hanging")
    exitProcess(0)
}
