import support.ExecutionPolicyArgumentProvider
import support.TestBase
import framework.applyTestDefaults
import framework.configureDaemonPolicy
import framework.loadToolchain
import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.ExecutionPolicy
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain.Companion.jvm
import org.jetbrains.kotlin.buildtools.api.jvm.jvmCompilationOperation
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ArgumentsSource
import utils.CompilationTestUtils
import kotlin.test.assertEquals

/**
 * Tests verifying Build Tools API execution policies:
 * - In-process policy compiles successfully.
 * - Daemon policy compiles successfully when configured correctly.
 * - Daemon policy accepts custom JVM args.
 *
 * Demonstrates both individual tests and parameterized tests for execution policy variations.
 */
@OptIn(ExperimentalBuildToolsApi::class)
class ExecutionPolicyTest : TestBase() {

    @ParameterizedTest(name = "{0}: {displayName}")
    @ArgumentsSource(ExecutionPolicyArgumentProvider::class)
    @DisplayName("Basic compilation succeeds")
    fun testBasicCompilation(execution: Pair<KotlinToolchains, ExecutionPolicy>) {
        val (toolchain, policy) = execution
        val setup = createTestSetup()
        val source = createKotlinSource(setup.workspace, "Utils.kt", $$"""
            fun greet(name: String) = "Hello, $name"
        """
        )

        val operation = toolchain.jvm.jvmCompilationOperation(listOf(source), setup.outputDirectory) {
            compilerArguments.applyTestDefaults()
        }
        val result = CompilationTestUtils.runCompile(toolchain, operation, policy)

        assertEquals(CompilationResult.COMPILATION_SUCCESS, result)
    }

    @Test
    @DisplayName("Daemon execution with JVM args")
    fun testDaemonExecutionWithJvmArgs() {
        val setup = createTestSetup()
        val source = createKotlinSource(setup.workspace, "Calculator.kt", """
            fun double(x: Int) = x * 2
        """)

        val toolchain = loadToolchain()
        val operation = toolchain.jvm.jvmCompilationOperation(listOf(source), setup.outputDirectory) {
            compilerArguments.applyTestDefaults()
        }

        val daemonPolicy = configureDaemonPolicy(toolchain, listOf("Xmx3g", "Xms1g"))

        val result = CompilationTestUtils.runCompile(toolchain, operation, daemonPolicy)

        assertEquals(CompilationResult.COMPILATION_SUCCESS, result)
    }
}
