import framework.TestLogger
import framework.configureDaemonPolicy
import org.jetbrains.kotlin.buildtools.api.*
import org.jetbrains.kotlin.buildtools.api.arguments.ExperimentalCompilerArgument
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.jetbrains.kotlin.buildtools.api.arguments.enums.JvmTarget
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain
import utils.CompilationTestUtils
import utils.StdlibUtils
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
    val framework = BtaTestFacade()
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
    val toolchain = framework.loadToolchain()

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

    // Build the immutable operation
    val op = opBuilder.build()

    // --- 4) Choose execution policy ---
    val policy: ExecutionPolicy = if (useDaemon) {
        configureDaemonPolicy(toolchain, listOf("Xmx3g", "Xms1g"), shutdownDelayMs = 2000L)
    } else {
        toolchain.createInProcessExecutionPolicy()
    }

    // --- 5) Execute using infrastructure ---
    val logger = TestLogger(printToConsole = true)
    val result = CompilationTestUtils.runCompile(toolchain, op, policy, logger)

    // --- 6) Print summary and verify output ---
    println("Compilation result: $result")
    println("Compiler version: ${toolchain.getCompilerVersion()}")
    println("Classpath used:\n${op.compilerArguments[JvmCompilerArguments.CLASSPATH] ?: "Not set"}")

    val produced = Files.walk(outDir).filter { it.isRegularFile() }.map { it.relativeTo(outDir).toString() }.toList()
    println("Produced files (relative to out):\n" + produced.joinToString(System.lineSeparator()))
}

