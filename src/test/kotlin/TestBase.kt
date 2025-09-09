import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.assertTrue

/**
 * Base class for compilation tests that provides common setup and utility methods.
 * 
 * This class follows the DRY principle by extracting common test functionality
 * and provides a clear structure for compilation tests. It includes:
 * - Common test setup (workspace and output directory creation)
 * - Utility methods for verification
 * - Consistent patterns for test organization
 */
@OptIn(ExperimentalBuildToolsApi::class)
abstract class TestBase {

    protected val framework = BtaTestFramework()

    /**
     * Represents the setup for a compilation test including workspace and output directory.
     */
    protected data class CompilationTestSetup(
        val workspace: Path,
        val outputDirectory: Path
    )

    /**
     * Creates a standard test setup with workspace and output directory.
     * 
     * @return CompilationTestSetup containing workspace and output directory paths
     */
    protected fun createTestSetup(): CompilationTestSetup {
        val workspace = framework.createTempWorkspace()
        val outputDirectory = workspace.resolve("out").createDirectories()
        return CompilationTestSetup(workspace, outputDirectory)
    }

    /**
     * Verifies that compilation was successful.
     * 
     * @param result The compilation result to verify
     * @param message Optional custom message for assertion failure
     */
    protected fun assertCompilationSuccessful(
        result: CompilationResult, 
        message: String = "Expected compilation to succeed"
    ) {
        kotlin.test.assertEquals(CompilationResult.COMPILATION_SUCCESS, result, message)
    }

    /**
     * Verifies that compilation failed with an error.
     * 
     * @param result The compilation result to verify
     * @param message Optional custom message for assertion failure
     */
    protected fun assertCompilationFailed(
        result: CompilationResult, 
        message: String = "Expected compilation to fail"
    ) {
        kotlin.test.assertEquals(CompilationResult.COMPILATION_ERROR, result, message)
    }

    /**
     * Verifies that compilation failed with an internal error.
     * 
     * @param result The compilation result to verify
     * @param message Optional custom message for assertion failure
     */
    protected fun assertCompilationInternalError(
        result: CompilationResult, 
        message: String = "Expected compilation to fail with internal error"
    ) {
        kotlin.test.assertEquals(CompilationResult.COMPILER_INTERNAL_ERROR, result, message)
    }

    /**
     * Verifies that specific class files exist in the output directory.
     * 
     * @param outputDirectory The output directory to check
     * @param expectedClassNames List of expected class file names (without .class extension)
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
     * 
     * @param outputDirectory The output directory to check
     * @param message Optional custom message for assertion failure
     */
    protected fun assertNoClassFilesGenerated(
        outputDirectory: Path
    ) {
        val classFiles = getGeneratedClassFiles(outputDirectory)
        assertTrue(classFiles.isEmpty(), "No class files should be produced on compilation error, but found: $classFiles")
    }

    /**
     * Gets the list of generated class files in the output directory.
     * 
     * @param outputDirectory The output directory to scan
     * @return List of class file names
     */
    protected fun getGeneratedClassFiles(outputDirectory: Path): List<String> {
        return outputDirectory.toFile().walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .map { it.name }
            .toList()
    }

}