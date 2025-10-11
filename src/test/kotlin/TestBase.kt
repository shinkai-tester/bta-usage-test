import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.SharedApiClassesClassLoader
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.test.assertTrue

/**
 * Base class for BTA tests providing common test utilities.
 *
 * This class provides reusable functionality for:
 * - Loading the Kotlin toolchain from the test compiler classpath
 * - Creating test source files and directories
 * - Asserting compilation results (class file presence/absence)
 * - Automatic cleanup of temporary directories after each test
 */
@OptIn(ExperimentalBuildToolsApi::class)
open class TestBase {

    /**
     * Console logger for test output with debug enabled.
     */
    protected val logger = ConsoleLogger(isDebugEnabled = true)

    /**
     * List of temporary directories created during the test, to be cleaned up automatically.
     */
    private val tempDirectories = mutableListOf<Path>()

    private val defaultTestSource = """
        package sample

        class Greeter {
            fun hello(): String = "Hello, World!"
        }

        fun main() {
            println(Greeter().hello())
        }
    """.trimIndent()

    /**
     * Loads the Kotlin toolchain from the compiler classpath specified via system property.
     *
     * @return KotlinToolchains instance loaded from the test compiler classpath
     * @throws IllegalStateException if test.compiler.classpath system property is not set
     */
    protected fun loadToolchain(): KotlinToolchains {
        val compilerClasspath =
            System.getProperty("test.compiler.classpath") ?: error("test.compiler.classpath system property not set")

        val compilerUrls = compilerClasspath.split(File.pathSeparator).map { Path(it).toUri().toURL() }.toTypedArray()

        val compilerClassloader = URLClassLoader(compilerUrls, SharedApiClassesClassLoader())
        return KotlinToolchains.loadImplementation(compilerClassloader)
    }

    /**
     * Creates temporary test files and directories for compilation.
     * The temporary directory is automatically tracked and will be cleaned up after the test.
     *
     * @param prefix The prefix for the temporary directory name
     * @return A pair of (source file path, output directory path)
     */
    protected fun createTestFiles(prefix: String = "test"): Pair<Path, Path> {
        val (tmpDir, outDir) = CompilationUtils.createTempDirectories(prefix)
        tempDirectories.add(tmpDir)
        val src = CompilationUtils.createTestSource(tmpDir, sourceContent = defaultTestSource)
        return src to outDir
    }

    /**
     * Cleans up all temporary directories created during the test.
     * This method is automatically called after each test via @AfterTest.
     */
    @kotlin.test.AfterTest
    fun cleanup() {
        tempDirectories.forEach { tmpDir ->
            try {
                tmpDir.toFile().deleteRecursively()
            } catch (e: Exception) {
                // Log but don't fail the test if cleanup fails
                logger.warn("Failed to delete temporary directory $tmpDir", e)
            }
        }
        tempDirectories.clear()
    }

    /**
     * Lists all .class files in the given directory.
     *
     * @param outDir The directory to search for class files
     * @return List of .class files found
     */
    protected fun listClassFiles(outDir: Path): List<File> =
        outDir.toFile().walkTopDown().filter { it.isFile && it.extension == "class" }.toList()

    /**
     * Asserts that no class files were produced in the output directory.
     *
     * @param outDir The output directory to check
     */
    protected fun assertNoClasses(outDir: Path) {
        val classes = listClassFiles(outDir)
        assertTrue(classes.isEmpty(), "Expected no class files to be produced, but found: ${classes.map { it.name }}")
    }

    /**
     * Asserts that class files were produced in the output directory.
     *
     * @param outDir The output directory to check
     */
    protected fun assertHasClasses(outDir: Path) {
        val classes = listClassFiles(outDir)
        assertTrue(
            classes.isNotEmpty(), "Expected compiled class files to be produced, but none were found in: $outDir"
        )
    }

}
