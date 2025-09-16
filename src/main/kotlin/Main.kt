import org.jetbrains.kotlin.buildtools.api.*
import org.jetbrains.kotlin.buildtools.api.arguments.ExperimentalCompilerArgument
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.jetbrains.kotlin.buildtools.api.arguments.enums.JvmTarget
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.system.exitProcess

@OptIn(ExperimentalBuildToolsApi::class, ExperimentalCompilerArgument::class)
fun main() {
    // Create test source file
    val tmpDir: Path = Files.createTempDirectory("kotlin-compilation")
    val outDir: Path = tmpDir.resolve("out").createDirectories()
    val src: Path = tmpDir.resolve("Hello.kt")
    src.writeText(
        """
        package sample
        
        class Greeter {
            fun hello(): String = "Hello, World!"
        }
        
        fun main() {
            println(Greeter().hello())
        }
        """.trimIndent()
    )

    // Choose execution mode
    val useDaemon = false // Change to false for in-process compilation
    
    // Load toolchain with appropriate ClassLoader
    val classLoader = if (useDaemon) {
        // For daemon mode, use URLClassLoader with child-first delegation,
        // but parent-first for API package to avoid duplicating API classes
        val apiPrefixes = listOf(
            "kotlin.",
            "kotlinx."
        )
        ClasspathUtils.createChildFirstUrlClassLoaderWithSystemParent(apiPrefixes)
    } else {
        // For in-process mode, use system ClassLoader
        ClassLoader.getSystemClassLoader()
    }

    // Robust load: try API v2 direct, then fall back to v1 adapter via compat.
    // Also set TCCL to ensure ServiceLoader and reflective loads use the same CL.
    val prevCl = Thread.currentThread().contextClassLoader
    Thread.currentThread().contextClassLoader = classLoader
    val toolchain = try {
        try {
            KotlinToolchain.loadImplementation(classLoader)
        } catch (e: IllegalStateException) {
            // Fallback to CompilationService if KotlinToolchain is not available
            val cs = CompilationService.loadImplementation(classLoader)
            val adapter = Class.forName(
                "org.jetbrains.kotlin.buildtools.internal.compat.KotlinToolchainV1Adapter",
                true,
                classLoader
            )
            val ctor = adapter.getConstructor(CompilationService::class.java)
            ctor.newInstance(cs) as KotlinToolchain
        }
    } finally {
        Thread.currentThread().contextClassLoader = prevCl
    }
    
    // Log for compiler version
    println("toolchain.getCompilerVersion() = ${toolchain.getCompilerVersion()}")
    
    // Create a compilation operation
    val operation = toolchain.jvm.createJvmCompilationOperation(listOf(src), outDir)
    
    // Basic configuration
    val args = operation.compilerArguments
    args[JvmCompilerArguments.JVM_TARGET] = JvmTarget.JVM_17
    args[JvmCompilerArguments.MODULE_NAME] = "sample"
    args[JvmCompilerArguments.NO_REFLECT] = true
    args[JvmCompilerArguments.NO_STDLIB] = true
    
    // Add kotlin-stdlib to classpath
    val stdlibJar = KotlinVersion::class.java.protectionDomain.codeSource.location.path
    args[JvmCompilerArguments.CLASSPATH] = stdlibJar
    
    // Choose execution policy
    val policy = if (useDaemon) {
        toolchain.createDaemonExecutionPolicy()
    } else {
        toolchain.createInProcessExecutionPolicy()
    }
    
    // Simple logger
    val logger = object : KotlinLogger {
        override fun error(msg: String, throwable: Throwable?) = println("ERROR: $msg")
        override fun warn(msg: String) = println("WARN: $msg")
        override fun warn(msg: String, throwable: Throwable?) = println("WARN: $msg")
        override fun info(msg: String) = println("INFO: $msg")
        override fun lifecycle(msg: String) = println("LIFECYCLE: $msg")
        override val isDebugEnabled: Boolean = false
        override fun debug(msg: String) = println("DEBUG: $msg")
    }
    
    // Execute compilation
    toolchain.createBuildSession().use { session ->
        val result = session.executeOperation(operation, policy, logger)
        println("Compilation result: $result")
        println("Output directory: $outDir")
        
        // List produced files
        val produced = outDir.toFile().walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(outDir.toFile()).path }
            .toList()
        println("Produced files: ${produced.joinToString(", ")}")
    }
    
    // Force process termination to prevent hanging
    println("d: Forcing process termination to prevent hanging")
    exitProcess(0)
}