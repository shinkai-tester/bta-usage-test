import org.jetbrains.kotlin.buildtools.api.*
import org.jetbrains.kotlin.buildtools.api.arguments.ExperimentalCompilerArgument
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.jetbrains.kotlin.buildtools.api.arguments.enums.JvmTarget
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmCompilationOperation
import org.jetbrains.kotlin.buildtools.api.trackers.BuildMetricsCollector
import org.jetbrains.kotlin.buildtools.api.trackers.CompilerLookupTracker
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo

@OptIn(ExperimentalBuildToolsApi::class, ExperimentalCompilerArgument::class)
fun main(vararg implClasspath: String) {
    // If the implementation classpath is provided as program args, publish it via system property
    if (implClasspath.isNotEmpty()) {
        System.setProperty("compiler.impl.classpath", implClasspath.joinToString(java.io.File.pathSeparator))
    }

    // --- 1) Use infrastructure to prepare workspace and source ---
    val framework = BtaTestFramework()
    val workspace = framework.createTempWorkspace()
    val outDir = workspace.resolve("out").createDirectories()
    val src = framework.createKotlinSource(workspace, "Hello.kt", """
        package sample
        
        class Greeter {
            fun hello(): String = "OK"
        }
        
        fun main() {
            println(Greeter().hello())
        }
    """)

    // --- 2) Load toolchain using isolated classloader (via framework/manager) ---
    val useDaemon = false // set to true if you want to test daemon; logs differ slightly
    val toolchain = framework.loadToolchain(useDaemon)

    // --- 3) Create operation using builder pattern ---
    val jvmToolchain = toolchain.getToolchain(JvmPlatformToolchain::class.java)
    val opBuilder = jvmToolchain.jvmCompilationOperationBuilder(listOf(src), outDir)
    
    // Configure compiler arguments via builder
    val args = opBuilder.compilerArguments
    args[JvmCompilerArguments.NO_STDLIB] = true
    args[JvmCompilerArguments.NO_REFLECT] = true
    args[JvmCompilerArguments.CLASSPATH] = StdlibUtils.findStdlibJar()
    args[JvmCompilerArguments.JVM_TARGET] = JvmTarget.JVM_11
    args[JvmCompilerArguments.MODULE_NAME] = "bta.main.example"

    // Optional trackers/metrics printed to stdout (kept simple for visibility)
    opBuilder[BuildOperation.METRICS_COLLECTOR] = object : BuildMetricsCollector {
        override fun collectMetric(name: String, type: BuildMetricsCollector.ValueType, value: Long) {
            println("Metric: $name ${type.name}=$value")
        }
    }
    opBuilder[JvmCompilationOperation.LOOKUP_TRACKER] = object : CompilerLookupTracker {
        override fun recordLookup(filePath: String, scopeFqName: String, scopeKind: CompilerLookupTracker.ScopeKind, name: String) {
            println("Lookup: $filePath $scopeFqName ${scopeKind.name} $name")
        }
        override fun clear() { println("Lookup: clear()") }
    }
    
    // Build the immutable operation
    val op = opBuilder.build()

    // --- 4) Choose execution policy using builder pattern ---
    val policy: ExecutionPolicy = if (useDaemon) {
        val daemonBuilder = toolchain.daemonExecutionPolicyBuilder()
        daemonBuilder[ExecutionPolicy.WithDaemon.JVM_ARGUMENTS] = listOf("-Xmx3g", "-Xms1g")
        daemonBuilder[ExecutionPolicy.WithDaemon.SHUTDOWN_DELAY_MILLIS] = 2000L
        daemonBuilder.build()
    } else {
        toolchain.createInProcessExecutionPolicy()
    }

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

    // --- 6) Execute using infrastructure ---
    val result = CompilationTestUtils.runCompile(toolchain, op, policy, logger)

    // --- 7) Print summary and a quick check that output was produced ---
    println("Compilation result: $result")
    println("Compiler version: ${toolchain.getCompilerVersion()}")
    println("Classpath used:\n${op.compilerArguments[JvmCompilerArguments.CLASSPATH] ?: "Not set"}")

    val produced = Files.walk(outDir).filter { it.isRegularFile() }.map { it.relativeTo(outDir).toString() }.toList()
    println("Produced files (relative to out):\n" + produced.joinToString(System.lineSeparator()))
}

