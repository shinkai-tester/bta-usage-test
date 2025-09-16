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
    // Create a test source file
    val tmpDir: Path = Files.createTempDirectory("bta-usage-KT-78196")
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
    val useDaemon = false // set to true to run with Kotlin Daemon; false for in-process

    // Load toolchain for the chosen mode
    val toolchain = useDaemon.loadKotlinToolchain()

    // Log compiler version
    println("toolchain.getCompilerVersion() = ${toolchain.getCompilerVersion()}")

    // Create a compilation operation
    val operation = toolchain.jvm.createJvmCompilationOperation(listOf(src), outDir)

    // Basic configuration
    val args = operation.compilerArguments
    args[JvmCompilerArguments.JVM_TARGET] = JvmTarget.JVM_17
    args[JvmCompilerArguments.MODULE_NAME] = "KT-78196-sample"
    args[JvmCompilerArguments.NO_REFLECT] = true
    args[JvmCompilerArguments.NO_STDLIB] = true

    // Add kotlin-stdlib matching the compiler version to the classpath
    run {
        val compilerVersion = toolchain.getCompilerVersion()
        fun findStdlibInGradleCache(version: String): String? {
            val userHome = System.getProperty("user.home") ?: return null
            val base = java.nio.file.Paths.get(userHome, ".gradle", "caches", "modules-2", "files-2.1", "org.jetbrains.kotlin", "kotlin-stdlib", version)
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

        val stdlibFromCache = findStdlibInGradleCache(compilerVersion)
        if (stdlibFromCache != null) {
            args[JvmCompilerArguments.CLASSPATH] = stdlibFromCache
        } else {
            // Fallback: use any kotlin-stdlib from the current process classpath (may be a different version)
            val cpUrls = ClasspathUtils.buildCurrentProcessClasspathUrls()
            val stdlibEntries = cpUrls.map { it.file }.filter { it.contains("kotlin-stdlib") }
            if (stdlibEntries.isNotEmpty()) {
                println("WARN: kotlin-stdlib $compilerVersion not found in Gradle cache; falling back to process classpath stdlib(s), which may be a different version.")
                args[JvmCompilerArguments.CLASSPATH] = stdlibEntries.joinToString(java.io.File.pathSeparator)
            } else {
                println("WARN: No kotlin-stdlib found; compilation will likely fail due to missing stdlib on classpath.")
            }
        }
    }

    // Log classpath used (one entry per line)
    run {
        val cp = args[JvmCompilerArguments.CLASSPATH].orEmpty()
        if (cp.isNotBlank()) {
            println("Classpath used:")
            cp.split(java.io.File.pathSeparator).filter { it.isNotBlank() }.forEach { println(it) }
        }
    }

    // Choose an execution policy
    val policy = if (useDaemon) toolchain.createDaemonExecutionPolicy() else toolchain.createInProcessExecutionPolicy()

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
    println("DEBUG: Forcing process termination to prevent hanging")
    exitProcess(0)
}

@OptIn(ExperimentalBuildToolsApi::class)
private fun Boolean.loadKotlinToolchain(): KotlinToolchain {
    val classLoader: ClassLoader = if (this) {
        // Daemon mode: isolate implementation classes, but keep API from parent (stdlib + Build Tools API)
        // Use sane defaults so callers don't need to know the exact package list
        ClasspathUtils.createChildFirstUrlClassLoaderWithSystemParent()
    } else {
        // In-process: use the system classloader
        ClassLoader.getSystemClassLoader()
    }

    val thread = Thread.currentThread()
    val prevCl = thread.contextClassLoader
    thread.contextClassLoader = classLoader

    return try {
        // Prefer API v2
        KotlinToolchain.loadImplementation(classLoader)
    } catch (_: IllegalStateException) {
        // Fallback to v1 via compat adapter
        val cs = CompilationService.loadImplementation(classLoader)
        val adapter = Class.forName(
            "org.jetbrains.kotlin.buildtools.internal.compat.KotlinToolchainV1Adapter",
            true,
            classLoader
        )
        val ctor = adapter.getConstructor(CompilationService::class.java)
        ctor.newInstance(cs) as KotlinToolchain
    }
    finally {
        thread.contextClassLoader = prevCl
    }
}



/*
Doesn't work without a manual fallback to v1 via compat adapter

Exception in thread "main" java.lang.IllegalStateException: The classpath contains no implementation for org.jetbrains.kotlin.buildtools.api.KotlinToolchain
	at org.jetbrains.kotlin.buildtools.api.SharedApiClassesClassLoader.loadImplementation(SharedApiClassesClassLoader.kt:29)
	at org.jetbrains.kotlin.buildtools.api.KotlinToolchain$Companion.loadImplementation(KotlinToolchain.kt:117)
	at MainKt.loadKotlinToolchain(Main.kt:179)
	at MainKt.main(Main.kt:35)
	at MainKt.main(Main.kt)
 */


//@OptIn(ExperimentalBuildToolsApi::class)
//private fun Boolean.loadKotlinToolchain(): KotlinToolchain {
//    val classLoader: ClassLoader = if (this) {
//        ClasspathUtils.createChildFirstUrlClassLoaderWithSystemParent()
//    } else {
//        ClassLoader.getSystemClassLoader()
//    }
//
//    val thread = Thread.currentThread()
//    val prevCl = thread.contextClassLoader
//    thread.contextClassLoader = classLoader
//
//    try {
//        return KotlinToolchain.loadImplementation(classLoader)
//    } finally {
//        thread.contextClassLoader = prevCl
//    }
//}