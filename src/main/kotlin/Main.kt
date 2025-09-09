import org.jetbrains.kotlin.buildtools.api.*
import org.jetbrains.kotlin.buildtools.api.arguments.ExperimentalCompilerArgument
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.jetbrains.kotlin.buildtools.api.arguments.enums.JvmTarget
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmCompilationOperation
import org.jetbrains.kotlin.buildtools.api.trackers.BuildMetricsCollector
import org.jetbrains.kotlin.buildtools.api.trackers.CompilerLookupTracker
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

@OptIn(ExperimentalBuildToolsApi::class, ExperimentalCompilerArgument::class)
fun main() {
    // --- 1) Prepare input source and output dir in a temp workspace ---
    val tmpDir: Path = Files.createTempDirectory("bta-example-usage")
    val outDir: Path = tmpDir.resolve("out").createDirectories()
    val src: Path = tmpDir.resolve("Hello.kt")
    src.writeText(
        """
        package sample
        
        class Greeter {
            fun hello(): String = "OK"
        }
        
        fun main() {
            println(Greeter().hello())
        }
        """.trimIndent()
    )

    // --- 2) Load toolchain ---
    val useDaemon = false // set to true if you want to test daemon; logs differ slightly
    val toolchain = if (useDaemon) {
        // For daemon policy, use a URLClassLoader with system CL as parent
        val urlCl = ClasspathUtils.createUrlClassLoaderWithSystemParent()
        KotlinToolchain.loadImplementation(urlCl)
    } else {
        // In-process policy: use system/application classloader directly
        KotlinToolchain.loadImplementation(ClassLoader.getSystemClassLoader())
    }

    // --- 3) Create operation and configure ---
    val op = toolchain.jvm.createJvmCompilationOperation(listOf(src), outDir)
    val args = op.compilerArguments

    // Build a minimal, explicit classpath from jars actually present on the current process classpath.
    // Here we resolve kotlin-stdlib by locating the jar that contains KotlinVersion.
    fun containingJarOf(clazz: Class<*>): String? {
        val resourceName = clazz.name.replace('.', '/') + ".class"
        val url: URL = clazz.classLoader.getResource(resourceName) ?: return null
        val spec = url.toString()
        return when {
            spec.startsWith("jar:") && spec.contains(".jar!") -> {
                val jarPart = spec.substringAfter("jar:").substringBefore("!")
                val path = jarPart.removePrefix("file:")
                // Normalize Windows leading slash in URI paths like /C:/...
                var p = path.replace('/', File.separatorChar)
                if (File.separatorChar == '\\' && p.matches(Regex("^/[A-Za-z]:.*"))) p = p.removePrefix("/")
                p
            }
            spec.startsWith("file:") -> {
                var p = spec.removePrefix("file:").replace('/', File.separatorChar)
                if (File.separatorChar == '\\' && p.matches(Regex("^/[A-Za-z]:.*"))) p = p.removePrefix("/")
                p
            }
            else -> null
        }
    }

    val stdlibJar = containingJarOf(KotlinVersion::class.java)
        ?: error("Could not locate kotlin-stdlib on the classpath. Ensure stdlib is present.")

    val classpath = listOf(stdlibJar)
        .filter { it.isNotBlank() && File(it).exists() }
        .joinToString(File.pathSeparator)

    args[JvmCompilerArguments.CLASSPATH] = classpath

    // Be explicit: don’t try to auto-add stdlib/reflect from “Kotlin home”
    args[JvmCompilerArguments.NO_STDLIB] = true
    args[JvmCompilerArguments.NO_REFLECT] = true

    // Target and module name
    args[JvmCompilerArguments.JVM_TARGET] = JvmTarget.JVM_17
    args[JvmCompilerArguments.MODULE_NAME] = "bta.sample"

    // Encourage JDK/module related debug output where available
    fun inferJdkHome(): Path? {
        val raw = System.getProperty("java.home") ?: return null
        val javaHome = Paths.get(raw)
        val isJreDir = javaHome.fileName?.toString()?.equals("jre", ignoreCase = true) == true
        val jdkHome = if (isJreDir) (javaHome.parent ?: javaHome) else javaHome
        return jdkHome.takeIf { it.toFile().isDirectory }
    }

    inferJdkHome()?.let { jdk ->
        val jmods = jdk.resolve("jmods").toFile()
        args[JvmCompilerArguments.JDK_HOME] = jdk.toString()
        if (jmods.isDirectory) {
            args[JvmCompilerArguments.X_MODULE_PATH] = jmods.absolutePath
            args[JvmCompilerArguments.X_ADD_MODULES] = arrayOf("ALL-MODULE-PATH")
        }
    }

    // Optional trackers/metrics printed to stdout (kept simple for visibility)
    op[BuildOperation.METRICS_COLLECTOR] = object : BuildMetricsCollector {
        override fun collectMetric(name: String, type: BuildMetricsCollector.ValueType, value: Long) {
            println("Metric: $name ${type.name}=$value")
        }
    }
    op[JvmCompilationOperation.LOOKUP_TRACKER] = object : CompilerLookupTracker {
        override fun recordLookup(filePath: String, scopeFqName: String, scopeKind: CompilerLookupTracker.ScopeKind, name: String) {
            println("Lookup: $filePath $scopeFqName ${scopeKind.name} $name")
        }
        override fun clear() { println("Lookup: clear()") }
    }

    // --- 4) Choose execution policy ---
    val policy: ExecutionPolicy = if (useDaemon) toolchain.createDaemonExecutionPolicy() else toolchain.createInProcessExecutionPolicy()
    if (useDaemon) policy.configureDaemon(listOf("-Xmx3g", "-Xms1g"), 2000)

    // --- 5) Logger with debug enabled to print the desired "d:" lines ---
    val logger = object : KotlinLogger {
        private fun printWith(prefix: String, msg: String, t: Throwable? = null) {
            println("$prefix $msg")
            if (t != null) println(prefix + t.stackTraceToString())
        }
        override fun error(msg: String, throwable: Throwable?) = printWith("e:", msg, throwable)
        override fun warn(msg: String) = printWith("w:", msg)
        override fun warn(msg: String, throwable: Throwable?) = printWith("w:", msg, throwable)
        override fun info(msg: String) = printWith("i:", msg)
        override fun lifecycle(msg: String) = printWith("l:", msg)
        override val isDebugEnabled: Boolean = true // ensure debug callbacks are invoked
        override fun debug(msg: String) = printWith("d:", msg)
    }

    // --- 6) Execute inside a build session ---
    val result = toolchain.createBuildSession().use { session ->
        session.executeOperation(op, policy, logger)
    }

    // --- 7) Print summary and a quick check that output was produced ---
    println("Compilation result: $result")
    println("Compiler version: ${toolchain.getCompilerVersion()}")
    println("Classpath used:\n$classpath")

    val produced = outDir.toFile().walkTopDown().filter { it.isFile }.map { it.relativeTo(outDir.toFile()).path }.toList()
    println("Produced files (relative to out):\n" + produced.joinToString(System.lineSeparator()))
}

