import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinLogger
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.arguments.ExperimentalCompilerArgument
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.jetbrains.kotlin.buildtools.api.arguments.enums.JvmTarget
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.relativeTo
import kotlin.io.path.writeText

/**
 * Minimal end-to-end demo of running a JVM Kotlin compilation in-process via the Build Tools API.
 *
 * Pass the BTA implementation classpath (kotlin-build-tools-impl + its transitive deps) as program args.
 */
@OptIn(ExperimentalBuildToolsApi::class, ExperimentalCompilerArgument::class)
fun main(vararg implClasspath: String) {
    val implPaths = implClasspath.map { Path(it) }
    require(implPaths.isNotEmpty()) {
        "Pass the BTA implementation classpath (kotlin-build-tools-impl + deps) as program arguments."
    }

    val workspace = Files.createTempDirectory("bta-demo-")
    val outDir = workspace.resolve("out").createDirectories()
    val src = workspace.resolve("Hello.kt").apply {
        writeText(
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
    }

    // Load the toolchain in an isolated classloader
    val toolchain = KotlinToolchains.loadImplementation(implPaths)

    // Build the compilation operation.
    val jvm = toolchain.getToolchain(JvmPlatformToolchain::class.java)
    val op = jvm.jvmCompilationOperationBuilder(listOf(src), outDir).apply {
        compilerArguments[JvmCompilerArguments.NO_STDLIB] = true
        compilerArguments[JvmCompilerArguments.NO_REFLECT] = true
        compilerArguments[JvmCompilerArguments.CLASSPATH] = listOf(findStdlibJar())
        compilerArguments[JvmCompilerArguments.JVM_TARGET] = JvmTarget.JVM_11
        compilerArguments[JvmCompilerArguments.MODULE_NAME] = "bta.main.example"
    }.build()

    // Execute in-process within a build session.
    val result = toolchain.createBuildSession().use { session ->
        session.executeOperation(op, toolchain.createInProcessExecutionPolicy(), ConsoleLogger)
    }

    println("Compilation result: $result")
    println("Compiler version:   ${toolchain.getCompilerVersion()}")

    val produced = Files.walk(outDir).use { stream ->
        stream.filter { it.isRegularFile() }.map { it.relativeTo(outDir).toString() }.toList()
    }
    println("Produced files:\n" + produced.joinToString(System.lineSeparator()))
}

private fun findStdlibJar(): Path {
    val regex = Regex("""kotlin-stdlib(-jdk[0-9]+)?(-\d[\w.+-]*)?\.jar$""")
    return System.getProperty("java.class.path").orEmpty()
        .split(File.pathSeparator)
        .asSequence()
        .filter { it.isNotBlank() }
        .map { Path(it) }
        .firstOrNull { regex.containsMatchIn(it.name) }
        ?: error("Could not locate kotlin-stdlib JAR on the runtime classpath.")
}

@OptIn(ExperimentalBuildToolsApi::class)
private object ConsoleLogger : KotlinLogger {
    override val isDebugEnabled: Boolean = false
    override fun error(msg: String, throwable: Throwable?) {
        System.err.println("e: $msg"); throwable?.printStackTrace(System.err)
    }
    override fun warn(msg: String) = println("w: $msg")
    override fun warn(msg: String, throwable: Throwable?) {
        println("w: $msg"); throwable?.printStackTrace()
    }
    override fun info(msg: String) = println("i: $msg")
    override fun lifecycle(msg: String) = println("l: $msg")
    override fun debug(msg: String) = println("d: $msg")
}
