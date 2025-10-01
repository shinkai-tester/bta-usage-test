import org.jetbrains.kotlin.buildtools.api.BuildOperation
import org.jetbrains.kotlin.buildtools.api.ExecutionPolicy
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinLogger
import org.jetbrains.kotlin.buildtools.api.arguments.ExperimentalCompilerArgument
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.jetbrains.kotlin.buildtools.api.arguments.enums.JvmTarget
import org.jetbrains.kotlin.buildtools.api.getToolchain
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmCompilationOperation
import org.jetbrains.kotlin.buildtools.api.trackers.BuildMetricsCollector
import org.jetbrains.kotlin.buildtools.api.trackers.CompilerLookupTracker
import kotlin.io.path.createDirectories
import kotlin.system.exitProcess

@OptIn(ExperimentalBuildToolsApi::class, ExperimentalCompilerArgument::class)
fun main() {
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

    // --- 2) Load toolchain using infrastructure ---
    val useDaemon = false // set to true if you want to test daemon; logs differ slightly
    val toolchain = framework.loadToolchain(useDaemon)

    // --- 3) Create operation and configure using infrastructure ---
    val op = toolchain.getToolchain<JvmPlatformToolchain>().createJvmCompilationOperation(listOf(src), outDir)
    framework.configureMinimalCompilation(op)
    
    // Override specific settings for this example
    val args = op.compilerArguments
    args[JvmCompilerArguments.JVM_TARGET] = JvmTarget.JVM_11
    args[JvmCompilerArguments.MODULE_NAME] = "bta.main.example"

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
    if (useDaemon) policy.configureDaemon(listOf("Xmx3g", "Xms1g"), 2000)

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
    println("Classpath used:\n${args[JvmCompilerArguments.CLASSPATH] ?: "Not set"}")

    val produced = outDir.toFile().walkTopDown().filter { it.isFile }.map { it.relativeTo(outDir.toFile()).path }.toList()
    println("Produced files (relative to out):\n" + produced.joinToString(System.lineSeparator()))

    // Force process termination to prevent hanging
    println("d: Forcing process termination to prevent hanging")
    exitProcess(0)
}

