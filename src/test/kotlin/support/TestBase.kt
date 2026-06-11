package support

import framework.TestLogger
import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.assertTrue

/**
 * Base class for compilation tests that provides the small amount of state shared across the suite:
 * - temporary workspace/source creation (with automatic cleanup at JVM shutdown)
 * - common compilation assertions
 *
 * Toolchain loading and compiler-argument configuration live in the [framework] package.
 */
@OptIn(ExperimentalBuildToolsApi::class)
abstract class TestBase {

    /**
     * Represents the setup for a compilation test including workspace, output, and IC directories.
     */
    protected data class CompilationTestSetup(
        val workspace: Path,
        val outputDirectory: Path,
        val icDirectory: Path
    )

    /**
     * Creates a standard test setup with workspace, output directory, and IC directory.
     */
    protected fun createTestSetup(): CompilationTestSetup {
        val workspace = createTempWorkspace()
        val outputDirectory = workspace.resolve("out").createDirectories()
        val icDirectory = workspace.resolve("ic").createDirectories()
        return CompilationTestSetup(workspace, outputDirectory, icDirectory)
    }

    /**
     * Creates a temporary workspace directory that is recursively deleted when the JVM exits.
     */
    protected fun createTempWorkspace(): Path {
        ensureShutdownHookInstalled()
        return Files.createTempDirectory("bta-test").also { createdWorkspaces.add(it) }
    }

    /**
     * Creates a Kotlin source file (`content` is trimmed of indentation) inside [workspace].
     */
    protected fun createKotlinSource(workspace: Path, fileName: String, content: String): Path {
        require(fileName.endsWith(".kt")) { "Kotlin source file name must end with .kt, got: $fileName" }
        return workspace.resolve(fileName).apply { writeText(content.trimIndent()) }
    }

    /**
     * Verifies that compilation was successful.
     */
    protected fun assertCompilationSuccessful(
        result: CompilationResult,
        message: String = "Expected compilation to succeed"
    ) {
        kotlin.test.assertEquals(CompilationResult.COMPILATION_SUCCESS, result, message)
    }

    /**
     * Verifies that compilation failed with an error.
     */
    protected fun assertCompilationFailed(
        result: CompilationResult,
        message: String = "Expected compilation to fail"
    ) {
        kotlin.test.assertEquals(CompilationResult.COMPILATION_ERROR, result, message)
    }

    /**
     * Verifies that specific class files exist in the output directory.
     */
    protected fun assertClassFilesExist(outputDirectory: Path, vararg expectedClassNames: String) {
        val classFiles = getGeneratedClassFiles(outputDirectory)

        expectedClassNames.forEach { expectedClassName ->
            val expectedFileName = "$expectedClassName.class"
            assertTrue(
                classFiles.contains(expectedFileName),
                "Expected class file '$expectedFileName' not found. Generated files: $classFiles"
            )
        }
    }

    /**
     * Verifies that no class files were generated in the output directory.
     */
    protected fun assertNoClassFilesGenerated(outputDirectory: Path) {
        val classFiles = getGeneratedClassFiles(outputDirectory)
        assertTrue(classFiles.isEmpty(), "No class files should be produced on compilation error, but found: $classFiles")
    }

    /**
     * Gets the list of generated class files in the output directory.
     */
    protected fun getGeneratedClassFiles(outputDirectory: Path): List<String> {
        return Files.walk(outputDirectory)
            .filter { it.isRegularFile() && it.extension == "class" }
            .map { it.name }
            .toList()
    }

    /**
     * Returns the set of source files the compiler reported as (re)compiled, parsed from the
     * `compile iteration:` debug log lines emitted by the incremental compiler.
     */
    protected fun recompiledSources(logger: TestLogger): Set<String> =
        logger.getAllDebugMessages()
            .asSequence()
            .map { it.removePrefix("[KOTLIN] ") }
            .filter { it.startsWith("compile iteration:") }
            .flatMap { it.removePrefix("compile iteration:").trim().split(", ") }
            .filter { it.isNotBlank() }
            .toSet()

    /**
     * Asserts that [fileName] was among the sources recompiled in the given compilation (per the
     * compiler's `compile iteration:` log).
     */
    protected fun assertRecompiled(logger: TestLogger, fileName: String) {
        val compiled = recompiledSources(logger)
        assertTrue(
            compiled.any { it.endsWith(fileName) },
            "Expected '$fileName' to be recompiled, but recompiled sources were: $compiled"
        )
    }

    /**
     * Asserts that [fileName] was NOT recompiled in the given compilation (per the compiler's
     * `compile iteration:` log).
     */
    protected fun assertNotRecompiled(logger: TestLogger, fileName: String) {
        val compiled = recompiledSources(logger)
        assertTrue(
            compiled.none { it.endsWith(fileName) },
            "Expected '$fileName' NOT to be recompiled, but recompiled sources were: $compiled"
        )
    }

    companion object {
        private val createdWorkspaces: ConcurrentLinkedQueue<Path> = ConcurrentLinkedQueue()

        @Volatile
        private var shutdownHookInstalled: Boolean = false

        private fun ensureShutdownHookInstalled() {
            if (shutdownHookInstalled) return
            synchronized(this) {
                if (shutdownHookInstalled) return
                Runtime.getRuntime().addShutdownHook(Thread {
                    createdWorkspaces.forEach { path ->
                        runCatching { deleteWorkspaceRecursively(path) }
                    }
                })
                shutdownHookInstalled = true
            }
        }

        @OptIn(ExperimentalPathApi::class)
        private fun deleteWorkspaceRecursively(path: Path) {
            try {
                if (path.exists()) path.deleteRecursively()
            } catch (_: Throwable) {
                if (Files.exists(path)) {
                    Files.walk(path)
                        .sorted(Comparator.reverseOrder())
                        .forEach { p -> runCatching { Files.deleteIfExists(p) }.getOrElse { if (it !is IOException) throw it } }
                }
            }
        }
    }
}
