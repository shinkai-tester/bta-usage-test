import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.ExecutionPolicy
import org.jetbrains.kotlin.buildtools.api.SharedApiClassesClassLoader
import org.jetbrains.kotlin.buildtools.api.arguments.ExperimentalCompilerArgument
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmCompilationOperation
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Manages Kotlin toolchain operations including loading, daemon management, and compilation configuration.
 * 
 * This class is responsible for all toolchain-related operations such as loading the Kotlin toolchain,
 * managing daemon execution policies, and configuring compilation operations. It follows the Single
 * Responsibility Principle by focusing solely on toolchain management.
 */
@OptIn(ExperimentalBuildToolsApi::class, ExperimentalCompilerArgument::class)
class ToolchainManager {
    
    /**
     * Loads the Kotlin toolchain with the appropriate classloader based on daemon usage.
     * 
     * @param useDaemon If true, uses URLClassLoader and sets it as TCCL during initialization.
     *                  If false, uses system/application classloader.
     * @return Configured KotlinToolchains instance
     */
    fun loadToolchain(useDaemon: Boolean = false): KotlinToolchains {
        // Always load implementation in an isolated classloader with SharedApiClassesClassLoader as parent
        val implCl = buildIsolatedImplClassLoader()
        return loadToolchainWithClassLoader(implCl, useDaemon)
    }
    
    /**
     * Loads the Kotlin toolchain using a custom classpath.
     * This is useful for testing with different compiler versions.
     * 
     * @param classpath The classpath string (paths separated by system path separator)
     * @param useDaemon If true, sets the classloader as TCCL during initialization
     * @return Configured KotlinToolchains instance
     */
    fun loadToolchainWithClasspath(classpath: String, useDaemon: Boolean = false): KotlinToolchains {
        val urls = classpath.split(java.io.File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { Path.of(it).toUri().toURL() }
            .toTypedArray()
        val parent = SharedApiClassesClassLoader()
        val implCl = URLClassLoader(urls, parent)
        return loadToolchainWithClassLoader(implCl, useDaemon)
    }
    
    private fun loadToolchainWithClassLoader(implCl: URLClassLoader, useDaemon: Boolean): KotlinToolchains {
        return if (useDaemon) {
            val prev = Thread.currentThread().contextClassLoader
            try {
                Thread.currentThread().contextClassLoader = implCl
                KotlinToolchains.loadImplementation(implCl)
            } finally {
                try { Thread.currentThread().contextClassLoader = prev } catch (_: Throwable) {}
            }
        } else {
            KotlinToolchains.loadImplementation(implCl)
        }
    }
    
    /**
     * Creates a DaemonExecutionPolicy in a daemon-friendly context.
     * Temporarily sets a URLClassLoader as TCCL while calling toolchain.daemonExecutionPolicyBuilder().
     * 
     * @param toolchain The Kotlin toolchain to create the daemon execution policy for
     * @return Configured ExecutionPolicy for daemon usage
     */
    fun createDaemonExecutionPolicy(toolchain: KotlinToolchains): ExecutionPolicy {
        val cl = buildIsolatedImplClassLoader()
        val prev = Thread.currentThread().contextClassLoader
        return try {
            Thread.currentThread().contextClassLoader = cl
            toolchain.daemonExecutionPolicyBuilder().build()
        } finally {
            try { Thread.currentThread().contextClassLoader = prev } catch (_: Throwable) {}
        }
    }
    
    /**
     * Executes a block with a URLClassLoader set as the thread context classloader to support daemon mode.
     * 
     * @param block The code block to execute in daemon context
     * @return The result of executing the block
     */
    fun <T> withDaemonContext(block: () -> T): T {
        val cl = buildIsolatedImplClassLoader()
        val prev = Thread.currentThread().contextClassLoader
        return try {
            Thread.currentThread().contextClassLoader = cl
            block()
        } finally {
            try { Thread.currentThread().contextClassLoader = prev } catch (_: Throwable) {}
        }
    }
    
    /**
     * Creates a new JVM compilation operation with minimal compilation settings using the builder pattern.
     * 
     * Sets up the compilation with:
     * - NO_STDLIB = true (prevent automatic inclusion)
     * - NO_REFLECT = true (prevent automatic inclusion)
     * - CLASSPATH = explicit stdlib path (full control)
     * - Module name for incremental compilation support
     * - JDK home and module path for Java source compilation
     * 
     * @param toolchain The Kotlin toolchain to use
     * @param sources List of source files to compile
     * @param outDir Output directory for compiled classes
     * @param workspace Optional workspace path for Java source roots
     * @return Configured JvmCompilationOperation
     */
    fun createJvmCompilationOperation(
        toolchain: KotlinToolchains,
        sources: List<Path>,
        outDir: Path,
        workspace: Path? = null
    ): JvmCompilationOperation {
        val jvmToolchain = toolchain.getToolchain(JvmPlatformToolchain::class.java)
        val builder = jvmToolchain.jvmCompilationOperationBuilder(sources, outDir)
        val args = builder.compilerArguments
        
        // Use shared configuration for basic compiler arguments
        configureBasicCompilerArguments(args, "test-module")

        // Provide JDK home and module path to enable Java source compilation alongside Kotlin
        configureJdkSettings(args)
        
        // Configure Java source roots if workspace is provided
        workspace?.let { configureJavaSourceRoots(args, it) }
        
        return builder.build()
    }
    
    
    /**
     * Locates the kotlin-stdlib JAR file on the classpath.
     * Uses shared StdlibUtils to avoid code duplication with Main.kt.
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
     * across IncrementalCompilationTestBuilder, JavaInteropTestScenario, CancellationTest, etc.
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
            .joinToString(java.io.File.pathSeparator)
        args[JvmCompilerArguments.CLASSPATH] = classpath
        
        args[JvmCompilerArguments.MODULE_NAME] = moduleName
    }
    
    // --- Implementation classloader isolation helpers ---
    private fun buildIsolatedImplClassLoader(): URLClassLoader {
        val urls = getImplClasspathUrls()
        val parent = SharedApiClassesClassLoader()
        return URLClassLoader(urls, parent)
    }
    
    private fun getImplClasspathUrls(): Array<URL> {
        val prop = System.getProperty("compiler.impl.classpath")?.takeIf { it.isNotBlank() }
        val candidates: List<String> = prop?.split(java.io.File.pathSeparator)
            ?: // Fallback: scan current process classpath for impl/compat jars
            System.getProperty("java.class.path").orEmpty().split(java.io.File.pathSeparator)
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

    /**
     * Configures Java source roots and compiles Java files if present.
     * 
     * @param args The compiler arguments builder to configure
     * @param workspace The workspace containing source files
     */
    private fun configureJavaSourceRoots(args: JvmCompilerArguments.Builder, workspace: Path) {
        args[JvmCompilerArguments.X_JAVA_SOURCE_ROOTS] = arrayOf(workspace.toString())

        // Proactively compile any Java sources in the workspace into the conventional out directory
        // used by tests (workspace/out), to ensure Java classes are present alongside Kotlin output.
        try {
            val javaFiles = Files.walk(workspace)
                .filter { it.isRegularFile() && it.extension.equals("java", ignoreCase = true) }
                .toList()
            if (javaFiles.isNotEmpty()) {
                compileJavaFiles(javaFiles, workspace)
            }
        } catch (_: Throwable) {
            // If Java compiler is unavailable or fails, ignore; Kotlin compilation may still succeed
        }
    }
    
    /**
     * Compiles Java files using the system Java compiler.
     * 
     * @param javaFiles List of Java files to compile
     * @param workspace The workspace containing the files
     */
    private fun compileJavaFiles(javaFiles: List<Path>, workspace: Path) {
        val outDir = workspace.resolve("out").createDirectories()
        val compiler = javax.tools.ToolProvider.getSystemJavaCompiler()
        if (compiler != null) {
            val options = mutableListOf("-d", outDir.absolutePathString())
            val fileManager = compiler.getStandardFileManager(null, null, null)
            val compilationUnits = fileManager.getJavaFileObjectsFromPaths(javaFiles)
            val task = compiler.getTask(null, fileManager, null, options, null, compilationUnits)
            task?.call()
            fileManager.close()
        }
    }
}