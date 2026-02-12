import support.ExecutionPolicyArgumentProvider
import support.TestBase
import framework.TestLogger
import org.jetbrains.kotlin.buildtools.api.ExecutionPolicy
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ArgumentsSource
import utils.CompilationTestUtils
import kotlin.test.assertTrue

/**
 * Tests for basic Kotlin compilation scenarios.
 *
 * This test class verifies fundamental compilation operations including:
 * - Simple JVM compilation of Kotlin sources
 * - Compilation error handling and validation
 *
 * Uses parameterized tests to verify behavior works consistently across
 * in-process and daemon execution policies.
 */
@OptIn(ExperimentalBuildToolsApi::class)
class JvmCompilationTest : TestBase() {

    @ParameterizedTest(name = "{0}: {displayName}")
    @ArgumentsSource(ExecutionPolicyArgumentProvider::class)
    @DisplayName("Compile simple Kotlin class")
    fun compileSimpleKotlinClass(execution: Pair<KotlinToolchains, ExecutionPolicy>) {
        val (toolchain, policy) = execution
        val setup = createTestSetup()
        val source = framework.createKotlinSource(setup.workspace, "Bard.kt", """
            class Bard {
                fun sing(): String = "Hail, BTA v2!"
            }
        """)

        val operation = framework.createJvmCompilationOperation(toolchain, listOf(source), setup.outputDirectory)
        val logger = TestLogger(printToConsole = true)
        val result = CompilationTestUtils.runCompile(toolchain, operation, policy, logger)

        assertCompilationSuccessful(result)
        assertClassFilesExist(setup.outputDirectory, "Bard")
    }

    @ParameterizedTest(name = "{0}: {displayName}")
    @ArgumentsSource(ExecutionPolicyArgumentProvider::class)
    @DisplayName("Fail compilation for invalid Kotlin code")
    fun failCompilationForInvalidCode(execution: Pair<KotlinToolchains, ExecutionPolicy>) {
        val (toolchain, policy) = execution
        val setup = createTestSetup()
        val source = framework.createKotlinSource(setup.workspace, "CursedScroll.kt", """
            class CursedScroll {
                fun readSpell(): String = forbiddenSpell()
            }
        """)

        val operation = framework.createJvmCompilationOperation(toolchain, listOf(source), setup.outputDirectory)
        val testLogger = framework.createTestLogger()
        val result = CompilationTestUtils.runCompile(toolchain, operation, policy, testLogger)

        assertCompilationFailed(result)
        assertNoClassFilesGenerated(setup.outputDirectory)

        val errorMessages = testLogger.getAllErrorMessages()
        assertTrue(errorMessages.isNotEmpty(), "Expected compilation error messages to be logged")

        val hasUnresolvedError = errorMessages.any {
            it.contains("Unresolved reference", ignoreCase = true) ||
            it.contains("forbiddenSpell", ignoreCase = true)
        }
        assertTrue(hasUnresolvedError, "Expected error messages to contain unresolved reference information")
    }
}
