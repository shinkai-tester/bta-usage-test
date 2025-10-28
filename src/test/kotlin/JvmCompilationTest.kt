import org.jetbrains.kotlin.buildtools.api.*
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Order
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
@Order(1)
class JvmCompilationTest : TestBase() {

    @Test
    @DisplayName("Compile simple Kotlin class")
    fun compileSimpleKotlinClass() {
        // Given: A test setup with a simple Kotlin class
        val setup = createTestSetup()
        val source = framework.createKotlinSource(setup.workspace, "Bard.kt", """
            class Bard {
                fun sing(): String = "Hail, BTA v2!"
            }
        """)

        // When: Compiling the Kotlin source with console logging enabled
        val toolchain = framework.loadToolchain()
        val operation = CompilationTestUtils.newJvmOp(toolchain, listOf(source), setup.outputDirectory, framework)
        val logger = TestLogger(printToConsole = true)
        val result = CompilationTestUtils.runCompile(toolchain, operation, logger)

        // Then: Compilation should succeed and generate the expected class file
        assertCompilationSuccessful(result)
        assertClassFilesExist(setup.outputDirectory, "Bard")
    }

    @Test
    @DisplayName("Compile mixed Kotlin and Java sources")
    fun compileMixedKotlinAndJava() {
        // Given: A test setup with both Kotlin and Java sources that interact
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

        // When: Compiling both sources together
        val toolchain = framework.loadToolchain()
        val operation = CompilationTestUtils.newJvmOp(
            toolchain,
            listOf(kotlinSource, javaSource),
            setup.outputDirectory,
            framework
        )
        val result = CompilationTestUtils.runCompile(toolchain, operation)

        // Then: Compilation should succeed and generate both class files
        assertCompilationSuccessful(result)
        assertClassFilesExist(setup.outputDirectory, "Adventurer", "LoreCodex")
    }

    @Test
    @DisplayName("Fail compilation for invalid Kotlin code")
    fun failCompilationForInvalidCode() {
        // Given: A test setup with invalid Kotlin code (unresolved reference)
        val setup = createTestSetup()
        val source = framework.createKotlinSource(setup.workspace, "CursedScroll.kt", """
            class CursedScroll {
                fun readSpell(): String = forbiddenSpell()
            }
        """)

        // When: Attempting to compile the invalid source
        val toolchain = framework.loadToolchain()
        val operation = CompilationTestUtils.newJvmOp(toolchain, listOf(source), setup.outputDirectory, framework)
        val testLogger = framework.createTestLogger()
        val result = CompilationTestUtils.runCompile(toolchain, operation, testLogger)

        // Then: Compilation should fail with appropriate error messages
        assertCompilationFailed(result)
        assertNoClassFilesGenerated(setup.outputDirectory)
        
        // And: Error messages should be captured and contain relevant information
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
        // Given: A test setup with daemon configuration that will fail
        val setup = createTestSetup()
        val source = framework.createKotlinSource(setup.workspace, "ErrorTest.kt", """
            fun test() = "should fail due to daemon startup error"
        """)

        // When: Attempting compilation with invalid daemon JVM arguments
        val toolchain = framework.loadToolchain(useDaemon = true)
        val operation = CompilationTestUtils.newJvmOp(toolchain, listOf(source), setup.outputDirectory, framework)
        val daemonPolicy = framework.createDaemonExecutionPolicy(toolchain)
        
        // Configure daemon with invalid JVM arguments that will cause startup failure
        daemonPolicy.configureDaemon(listOf("--Xmx3g"))
        
        val testLogger = framework.createTestLogger()
        val result = framework.withDaemonContext {
            CompilationTestUtils.runCompile(toolchain, operation, daemonPolicy, testLogger)
        }

        // Then: Should get internal compilation error due to daemon startup failure
        assertCompilationInternalError(result)

        // And: Should verify the expected number of retry attempts were made
        val retryCount = testLogger.getRetryCount()
        assertTrue(retryCount != null, "Expected to find retry count in error messages")
        assertEquals(4, retryCount, "Expected daemon to fail after 4 retries (3 startup attempts + 1 final failure)")
    }
}