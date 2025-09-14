import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Tests verifying Build Tools API execution policies:
 * - In-process policy compiles successfully.
 * - Daemon policy compiles successfully when configured correctly.
 * - Daemon policy accepts custom JVM args.
 * 
 * Each test uses clear Arrange/Act/Assert (Given/When/Then) sections so it’s obvious what is being tested.
 */
@OptIn(ExperimentalBuildToolsApi::class)
@Order(2)
class ExecutionPolicyTest : TestBase() {



    @Test
    @DisplayName("In-process execution policy")
    fun testInProcessExecution() {
        val setup = createTestSetup()
        val source = framework.createKotlinSource(setup.workspace, "MathUtils.kt", """
            fun square(x: Int) = x * x
        """)

        val toolchain = framework.loadToolchain()
        val operation = CompilationTestUtils.newJvmOp(toolchain, listOf(source), setup.outputDirectory, framework)

        val inProcessPolicy = toolchain.createInProcessExecutionPolicy()
        val result = CompilationTestUtils.runCompile(toolchain, operation, inProcessPolicy)

        assertEquals(CompilationResult.COMPILATION_SUCCESS, result)
    }

    @Test
    @DisplayName("Daemon execution policy")
    fun testDaemonExecution() {
        val setup = createTestSetup()
        val source = framework.createKotlinSource(setup.workspace, "StringUtils.kt", """
            fun reverse(s: String) = s.reversed()
        """)
        
        val toolchain = framework.loadToolchain(useDaemon = true)
        val operation = CompilationTestUtils.newJvmOp(toolchain, listOf(source), setup.outputDirectory, framework)

        val daemonPolicy = framework.createDaemonExecutionPolicy(toolchain)
        val result = framework.withDaemonContext {
            CompilationTestUtils.runCompile(toolchain, operation, daemonPolicy)
        }

        assertEquals(CompilationResult.COMPILATION_SUCCESS, result)
    }

    @Test
    @DisplayName("Daemon execution with JVM args")
    fun testDaemonExecutionWithJvmArgs() {
        val setup = createTestSetup()
        val source = framework.createKotlinSource(setup.workspace, "Calculator.kt", """
            fun double(x: Int) = x * 2
        """)

        val toolchain = framework.loadToolchain(useDaemon = true)
        val operation = CompilationTestUtils.newJvmOp(toolchain, listOf(source), setup.outputDirectory, framework)

        val daemonPolicy = framework.createDaemonExecutionPolicy(toolchain)

        daemonPolicy.configureDaemon(listOf("Xmx3g", "Xms1g"))

        val result = framework.withDaemonContext {
            CompilationTestUtils.runCompile(toolchain, operation, daemonPolicy)
        }

        assertEquals(CompilationResult.COMPILATION_SUCCESS, result)
    }
}