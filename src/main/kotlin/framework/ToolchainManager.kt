package framework

import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.SharedApiClassesClassLoader
import org.jetbrains.kotlin.buildtools.api.arguments.ExperimentalCompilerArgument
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmCompilationOperation
import utils.StdlibUtils
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Manages Kotlin toolchain operations including loading and compilation configuration.
 *
 * This class is responsible for all toolchain-related operations such as loading the Kotlin toolchain,
 * configuring compiler arguments, and setting up compilation operations.
 */
@OptIn(ExperimentalBuildToolsApi::class, ExperimentalCompilerArgument::class)
class ToolchainManager {

    /**
     * Loads the Kotlin toolchain with an isolated classloader.
     * 
     * @return KotlinToolchains instance
     */
    fun loadToolchain(): KotlinToolchains {
        return KotlinToolchains.loadImplementation(getIsolatedClassLoader())
    }

    /**
     * Loads the Kotlin toolchain using a custom classpath.
     * This is useful for testing with different compiler versions.
     * 
     * @param classpath The classpath string (paths separated by system path separator)
     * @return Configured KotlinToolchains instance
     */
    fun loadToolchainWithClasspath(classpath: String): KotlinToolchains {
        val urls = classpath.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { Path.of(it).toUri().toURL() }
            .toTypedArray()
        return KotlinToolchains.loadImplementation(URLClassLoader(urls, SharedApiClassesClassLoader()))
    }

    /**
     * Sets up a JVM compilation operation with standard test configuration.
     *
     * Configures the compilation with basic compiler arguments (NO_STDLIB, NO_REFLECT, CLASSPATH with stdlib,
     * MODULE_NAME) and JDK settings (JDK_HOME, MODULE_PATH) to enable Java source compilation alongside Kotlin.
     *
     * @param toolchain The Kotlin toolchain to use
     * @param sources List of source files to compile
     * @param outDir Output directory for compiled classes
     * @return Configured JvmCompilationOperation ready to execute
     */
    fun setupJvmCompilationOperation(
        toolchain: KotlinToolchains,
        sources: List<Path>,
        outDir: Path
    ): JvmCompilationOperation {
        val jvmToolchain = toolchain.getToolchain(JvmPlatformToolchain::class.java)
        val builder = jvmToolchain.jvmCompilationOperationBuilder(sources, outDir)
        val args = builder.compilerArguments

        configureBasicCompilerArguments(args, "test-module")
        configureJdkSettings(args)

        return builder.build()
    }

    /**
     * Locates the kotlin-stdlib JAR file on the classpath.
     * Delegates to utils.StdlibUtils to avoid code duplication.
     *
     * @return Path to the kotlin-stdlib JAR file
     * @throws IllegalStateException if the stdlib JAR cannot be located
     */
    fun findStdlibJar(): String = StdlibUtils.findStdlibJar()

    /**
     * Configures basic compiler arguments that are common across all compilation scenarios.
     * Sets NO_STDLIB, NO_REFLECT, CLASSPATH (with stdlib), and MODULE_NAME.
     *
     * This method centralizes the common compiler argument configuration to avoid code duplication
     * across different test scenarios and IC operations.
     *
     * @param args The compiler arguments builder to configure
     * @param moduleName The module name to set for the compilation
     */
    fun configureBasicCompilerArguments(args: JvmCompilerArguments.Builder, moduleName: String) {
        args[JvmCompilerArguments.NO_STDLIB] = true
        args[JvmCompilerArguments.NO_REFLECT] = true

        val stdlib = findStdlibJar()
        val classpath = listOfNotNull(stdlib)
            .filter { it.isNotBlank() && Path.of(it).exists() }
            .joinToString(File.pathSeparator)
        args[JvmCompilerArguments.CLASSPATH] = classpath

        args[JvmCompilerArguments.MODULE_NAME] = moduleName
    }

    private fun getIsolatedClassLoader(): URLClassLoader {
        val urls = getImplClasspathUrls()
        val parent = SharedApiClassesClassLoader()
        return URLClassLoader(urls, parent)
    }

    private fun getImplClasspathUrls(): Array<URL> {
        val prop = System.getProperty("compiler.impl.classpath")?.takeIf { it.isNotBlank() }
        val candidates: List<String> = prop?.split(File.pathSeparator)
            ?: // Fallback: scan current process classpath for impl/compat jars
            System.getProperty("java.class.path").orEmpty().split(File.pathSeparator)
                .filter { path ->
                    val name = Path.of(path).name
                    name.contains("kotlin-build-tools-impl") || name.contains("kotlin-build-tools-compat")
                }
        val urls = candidates
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { Path.of(it).toUri().toURL() }.getOrNull() }
            .toTypedArray()
        if (urls.isEmpty()) {
            throw IllegalStateException(
                "No Kotlin compiler implementation jars found. Pass them as program args or set -Dcompiler.impl.classpath."
            )
        }
        return urls
    }

    /**
     * Configures JDK settings for the compilation arguments builder.
     * 
     * @param args The compiler arguments builder to configure
     */
    private fun configureJdkSettings(args: JvmCompilerArguments.Builder) {
        val javaHomeProp = System.getProperty("java.home")
        if (javaHomeProp != null) {
            val javaHome = Path.of(javaHomeProp)
            val isJre = javaHome.fileName?.toString()?.equals("jre", ignoreCase = true) == true
            val jdkHome = if (isJre) (javaHome.parent ?: javaHome) else javaHome
            if (jdkHome.isDirectory()) {
                args[JvmCompilerArguments.JDK_HOME] = jdkHome.toString()
                val jmods = jdkHome.resolve("jmods")
                if (jmods.isDirectory()) {
                    args[JvmCompilerArguments.X_MODULE_PATH] = jmods.absolutePathString()
                    args[JvmCompilerArguments.X_ADD_MODULES] = arrayOf("ALL-MODULE-PATH")
                }
            }
        }
    }
}