import support.ExecutionPolicyArgumentProvider
import support.TestBase
import framework.TestLogger
import org.jetbrains.kotlin.buildtools.api.ExecutionPolicy
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.OperationCancelledException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ArgumentsSource
import utils.IncrementalCompilationUtils

/**
 * Tests for compilation cancellation functionality.
 *
 * Verifies that compilation operations can be canceled with different execution strategies:
 * - In-process execution (uses parameterized tests)
 * - Daemon execution (requires special threading setup, tested separately)
 */
@OptIn(ExperimentalBuildToolsApi::class)
class CancellationTest : TestBase() {

    @ParameterizedTest(name = "{0}: {displayName}")
    @ArgumentsSource(ExecutionPolicyArgumentProvider::class)
    @DisplayName("Cancellation of non-incremental compilation")
    fun cancelNonIncrementalCompilation(execution: Pair<KotlinToolchains, ExecutionPolicy>) {
        val (toolchain, policy) = execution
        val setup = createTestSetup()
        val source = framework.createKotlinSource(setup.workspace, "SimpleTest.kt", """
            class SimpleTest {
                fun greet(): String = "Hello!"
            }
        """)

        val operation = framework.createJvmCompilationOperation(toolchain, listOf(source), setup.outputDirectory)

        val exception = assertThrows<OperationCancelledException> {
            toolchain.createBuildSession().use { session ->
                operation.cancel()
                session.executeOperation(operation, policy, TestLogger())
            }
        }

        assertEquals("Operation has been cancelled.", exception.message)
    }

    @ParameterizedTest(name = "{0}: {displayName}")
    @ArgumentsSource(ExecutionPolicyArgumentProvider::class)
    @DisplayName("Cancellation of incremental compilation")
    fun cancelIncrementalCompilation(execution: Pair<KotlinToolchains, ExecutionPolicy>) {
        val (toolchain, policy) = execution
        val setup = createTestSetup()
        val source = framework.createKotlinSource(setup.workspace, "IncrementalTest.kt", """
            class IncrementalTest {
                fun compute(): Int = 42
            }
        """)

        val operation = IncrementalCompilationUtils.createIncrementalJvmOperation(
            toolchain,
            listOf(source),
            setup.outputDirectory,
            setup.icDirectory,
            setup.workspace,
            framework
        )

        val exception = assertThrows<OperationCancelledException> {
            toolchain.createBuildSession().use { session ->
                operation.cancel()
                session.executeOperation(operation, policy, TestLogger())
            }
        }

        assertEquals("Operation has been cancelled.", exception.message)
    }

    @Test
    @DisplayName("Cancellation not supported with old compiler (pre-2.3.20) throws IllegalStateException")
    fun cancelNotSupportedWithOldCompiler() {
        val setup = createTestSetup()
        val source = framework.createKotlinSource(setup.workspace, "OldCompilerTest.kt", """
            class OldCompilerTest {
                fun greet(): String = "Hello from old compiler!"
            }
        """)

        val oldClasspath = System.getProperty("old.compiler.impl.classpath")
            ?: throw IllegalStateException("old.compiler.impl.classpath system property not set")
        val toolchain = framework.loadToolchainWithClasspath(oldClasspath)
        val operation = framework.createJvmCompilationOperation(toolchain, listOf(source), setup.outputDirectory)

        val exception = assertThrows<IllegalStateException> {
            operation.cancel()
        }

        kotlin.test.assertEquals(
            exception.message?.contains("Cancellation is supported from compiler version 2.3.20"),
            true,
            "Expected error message about cancellation not supported, but got: ${exception.message}"
        )
    }
}
