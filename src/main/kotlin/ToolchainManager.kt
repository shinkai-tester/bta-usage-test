import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.ExecutionPolicy
import org.jetbrains.kotlin.buildtools.api.arguments.ExperimentalCompilerArgument
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmCompilationOperation
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Path

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
     * Temporarily sets a URLClassLoader as TCCL while calling toolchain.createDaemonExecutionPolicy().
     * 
     * @param toolchain The Kotlin toolchain to create the daemon execution policy for
     * @return Configured ExecutionPolicy for daemon usage
     */
    fun createDaemonExecutionPolicy(toolchain: KotlinToolchains): ExecutionPolicy {
        val cl = buildIsolatedImplClassLoader()
        val prev = Thread.currentThread().contextClassLoader
        return try {
            Thread.currentThread().contextClassLoader = cl
            toolchain.createDaemonExecutionPolicy()
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
     * Configures minimal compilation settings for a JVM compilation operation.
     * 
     * Sets up the compilation with:
     * - NO_STDLIB = true (prevent automatic inclusion)
     * - NO_REFLECT = true (prevent automatic inclusion)
     * - CLASSPATH = explicit stdlib path (full control)
     * - Module name for incremental compilation support
     * - JDK home and module path for Java source compilation
     * 
     * @param operation The JVM compilation operation to configure
     * @param workspace Optional workspace path for Java source roots
     */
    fun configureMinimalCompilation(operation: JvmCompilationOperation, workspace: Path? = null) {
        val args = operation.compilerArguments
        args[JvmCompilerArguments.NO_STDLIB] = true
        args[JvmCompilerArguments.NO_REFLECT] = true

        val stdlib = findStdlibJar()
        val classpath = listOfNotNull(stdlib)
            .filter { it.isNotBlank() && File(it).exists() }
            .joinToString(File.pathSeparator)
        args[JvmCompilerArguments.CLASSPATH] = classpath
        
        // Set module name for incremental compilation support
        args[JvmCompilerArguments.MODULE_NAME] = "test-module"

        // Provide JDK home and module path to enable Java source compilation alongside Kotlin
        configureJdkSettings(args)
        
        // Configure Java source roots if workspace is provided
        workspace?.let { configureJavaSourceRoots(args, it) }
    }
    
    /**
     * Locates the kotlin-stdlib JAR file on the classpath.
     * Uses shared StdlibUtils to avoid code duplication with Main.kt.
     * 
     * @return Path to the kotlin-stdlib JAR file
     * @throws IllegalStateException if the stdlib JAR cannot be located
     */
    fun findStdlibJar(): String = StdlibUtils.findStdlibJar()
    
    // --- Implementation classloader isolation helpers ---
    private fun buildIsolatedImplClassLoader(): URLClassLoader {
        val urls = getImplClasspathUrls()
        val parent = tryLoadSharedApiParent()
        return URLClassLoader(urls, parent)
    }
    
    private fun tryLoadSharedApiParent(): ClassLoader {
        // Try to instantiate SharedApiClassesClassLoader reflectively (available since 2.3.0)
        return try {
            val clazz = Class.forName("org.jetbrains.kotlin.buildtools.api.classloaders.SharedApiClassesClassLoader")
            val ctor = clazz.getDeclaredConstructor()
            ctor.isAccessible = true
            val instance = ctor.newInstance()
            instance as ClassLoader
        } catch (_: Throwable) {
            // Fallback: system classloader
            ClassLoader.getSystemClassLoader()
        }
    }
    
    private fun getImplClasspathUrls(): Array<URL> {
        val prop = System.getProperty("compiler.impl.classpath")?.takeIf { it.isNotBlank() }
        val candidates: List<String> = if (prop != null) {
            prop.split(File.pathSeparator)
        } else {
            // Fallback: scan current process classpath for impl/compat jars
            System.getProperty("java.class.path").orEmpty().split(File.pathSeparator)
                .filter { path ->
                    val name = File(path).name
                    name.contains("kotlin-build-tools-impl") || name.contains("kotlin-build-tools-compat")
                }
        }
        val urls = candidates
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { File(it).toURI().toURL() }.getOrNull() }
            .toTypedArray()
        if (urls.isEmpty()) {
            throw IllegalStateException(
                "No Kotlin compiler implementation jars found. Pass them as program args or set -Dcompiler.impl.classpath."
            )
        }
        return urls
    }

    /**
     * Configures JDK settings for the compilation arguments.
     * 
     * @param args The compiler arguments to configure
     */
    private fun configureJdkSettings(args: JvmCompilerArguments) {
        val javaHomeProp = System.getProperty("java.home")
        if (javaHomeProp != null) {
            val javaHome = Path.of(javaHomeProp)
            val isJre = javaHome.fileName?.toString()?.equals("jre", ignoreCase = true) == true
            val jdkHome = if (isJre) (javaHome.parent ?: javaHome) else javaHome
            if (jdkHome.toFile().isDirectory) {
                args[JvmCompilerArguments.JDK_HOME] = jdkHome.toString()
                val jmods = jdkHome.resolve("jmods").toFile()
                if (jmods.isDirectory) {
                    args[JvmCompilerArguments.X_MODULE_PATH] = jmods.absolutePath
                    args[JvmCompilerArguments.X_ADD_MODULES] = arrayOf("ALL-MODULE-PATH")
                }
            }
        }
    }
    
    /**
     * Configures Java source roots and compiles Java files if present.
     * 
     * @param args The compiler arguments to configure
     * @param workspace The workspace containing source files
     */
    private fun configureJavaSourceRoots(args: JvmCompilerArguments, workspace: Path) {
        args[JvmCompilerArguments.X_JAVA_SOURCE_ROOTS] = arrayOf(workspace.toString())

        // Proactively compile any Java sources in the workspace into the conventional out directory
        // used by tests (workspace/out), to ensure Java classes are present alongside Kotlin output.
        try {
            val javaFiles = workspace.toFile().walkTopDown()
                .filter { it.isFile && it.extension.equals("java", ignoreCase = true) }
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
    private fun compileJavaFiles(javaFiles: List<File>, workspace: Path) {
        val outDir = workspace.resolve("out").toFile()
        outDir.mkdirs()
        val compiler = javax.tools.ToolProvider.getSystemJavaCompiler()
        if (compiler != null) {
            val options = mutableListOf("-d", outDir.absolutePath)
            val fileManager = compiler.getStandardFileManager(null, null, null)
            val compilationUnits = fileManager.getJavaFileObjectsFromFiles(javaFiles)
            val task = compiler.getTask(null, fileManager, null, options, null, compilationUnits)
            task?.call()
            fileManager.close()
        }
    }
}