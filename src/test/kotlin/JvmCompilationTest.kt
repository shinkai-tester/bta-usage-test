import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for basic Kotlin compilation scenarios.
 * 
 * This test class verifies fundamental compilation operations including:
 * - Simple JVM compilation of Kotlin sources
 * - Mixed Kotlin and Java source compilation
 * - Compilation error handling and validation
 * - Daemon execution policy with error scenarios
 * 
 * All tests follow clear naming conventions and use the base class utilities
 * to reduce duplication and improve maintainability.
 */
@OptIn(ExperimentalBuildToolsApi::class)
class JvmCompilationTest : TestBase() {

    @Test
    @DisplayName("Compile simple Kotlin class")
    fun compileSimpleKotlinClass() {
        val setup = createTestSetup()
        val source = framework.createKotlinSource(setup.workspace, "Bard.kt", """
            class Bard {
                fun sing(): String = "Hail, BTA v2!"
            }
        """)

        val toolchain = framework.loadToolchain()
        val operation = CompilationTestUtils.newJvmOp(toolchain, listOf(source), setup.outputDirectory, framework)
        val logger = TestLogger(printToConsole = true)
        val result = CompilationTestUtils.runCompile(toolchain, operation, logger)

        assertCompilationSuccessful(result)
        assertClassFilesExist(setup.outputDirectory, "Bard")
    }

    @Test
    @DisplayName("Compile mixed Kotlin and Java sources")
    fun compileMixedKotlinAndJava() {
        val setup = createTestSetup()
        
        val kotlinSource = framework.createKotlinSource(setup.workspace, "Adventurer.kt", """
            class Adventurer {
                fun useCore(): String = LoreCodex.getMessage()
            }
        """)

        val javaSource = framework.createJavaSource(setup.workspace, "LoreCodex.java", """
            public class LoreCodex {
                public static String getMessage() {
                    return "Wisdom from Java!";
                }
            }
        """)

        val toolchain = framework.loadToolchain()
        val operation = CompilationTestUtils.newJvmOp(
            toolchain,
            listOf(kotlinSource, javaSource),
            setup.outputDirectory,
            framework
        )
        val result = CompilationTestUtils.runCompile(toolchain, operation)

        assertCompilationSuccessful(result)
        assertClassFilesExist(setup.outputDirectory, "Adventurer", "LoreCodex")
    }

    @Test
    @DisplayName("Fail compilation for invalid Kotlin code")
    fun failCompilationForInvalidCode() {
        val setup = createTestSetup()
        val source = framework.createKotlinSource(setup.workspace, "CursedScroll.kt", """
            class CursedScroll {
                fun readSpell(): String = forbiddenSpell()
            }
        """)

        val toolchain = framework.loadToolchain()
        val operation = CompilationTestUtils.newJvmOp(toolchain, listOf(source), setup.outputDirectory, framework)
        val testLogger = framework.createTestLogger()
        val result = CompilationTestUtils.runCompile(toolchain, operation, testLogger)

        assertCompilationFailed(result)
        assertNoClassFilesGenerated(setup.outputDirectory)
        
        val errorMessages = testLogger.getAllErrorMessages()
        assertTrue(errorMessages.isNotEmpty(), "Expected compilation error messages to be logged")
        
        val hasUnresolvedError = errorMessages.any { 
            it.contains("Unresolved reference", ignoreCase = true) || 
            it.contains("doesNotExist", ignoreCase = true) 
        }
        assertTrue(hasUnresolvedError, "Expected error messages to contain unresolved reference information")
    }

    @Test
    @Disabled("Not stable")
    @DisplayName("Handle daemon execution failures with retries")
    fun handleDaemonExecutionFailures() {
        val setup = createTestSetup()
        val source = framework.createKotlinSource(setup.workspace, "ErrorTest.kt", """
            fun test() = "should fail due to daemon startup error"
        """)

        val toolchain = framework.loadToolchain()
        val operation = CompilationTestUtils.newJvmOp(toolchain, listOf(source), setup.outputDirectory, framework)
        val daemonPolicy = framework.createDaemonExecutionPolicy(toolchain)
        
        daemonPolicy.configureDaemon(listOf("--Xmx3g"))
        
        val testLogger = framework.createTestLogger()
        val result = CompilationTestUtils.runCompile(toolchain, operation, daemonPolicy, testLogger)

        assertCompilationInternalError(result)

        val retryCount = testLogger.getRetryCount()
        assertTrue(retryCount != null, "Expected to find retry count in error messages")
        assertEquals(4, retryCount, "Expected daemon to fail after 4 retries (3 startup attempts + 1 final failure)")
    }
}
