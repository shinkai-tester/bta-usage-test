import support.TestBase
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.RemovedCompilerArgument
import org.jetbrains.kotlin.buildtools.api.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.buildtools.api.arguments.CompilerPlugin
import org.jetbrains.kotlin.buildtools.api.arguments.ExperimentalCompilerArgument
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments.Companion.JAVA_PARAMETERS
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the Compiler Arguments API behavior without actual compilation.
 *
 * This test class focuses on:
 * - Reading arguments with and without defaults
 * - Exception behavior for unset arguments
 * - Default value verification
 */
@OptIn(ExperimentalBuildToolsApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompilerArgumentsApiTest : TestBase() {

    private lateinit var toolchain: KotlinToolchains

    @BeforeAll
    fun initToolchain() {
        toolchain = framework.loadToolchain()
    }

    @OptIn(ExperimentalCompilerArgument::class, RemovedCompilerArgument::class)
    @Test
    @DisplayName("Throws IllegalStateException when argument has no default and was not set")
    fun testArgumentWithNoDefaultThrowsWhenNotSet() {
        val op = framework.createJvmCompilationOperation(toolchain, emptyList(), framework.createTempWorkspace())
        val args = op.compilerArguments

        val ex = assertThrows<IllegalStateException> {
            args[JvmCompilerArguments.X_USE_K2_KAPT]
        }

        assertEquals(
            "Argument X_USE_K2_KAPT is not set and has no default value",
            ex.message
        )
    }

    @Test
    @DisplayName("Default exists (JVM, Boolean): read succeeds without setting")
    fun testJvmArgumentWithDefault() {
        val op = framework.createJvmCompilationOperation(toolchain, emptyList(), framework.createTempWorkspace())
        val args = op.compilerArguments

        val value: Boolean = args[JAVA_PARAMETERS]
        assertFalse(value, "Expected JAVA_PARAMETERS to have default value 'false'")
    }

    @Test
    @DisplayName("Default exists (Common, empty list): read succeeds without setting")
    fun testCommonArgumentWithDefault() {
        val op = framework.createJvmCompilationOperation(toolchain, emptyList(), framework.createTempWorkspace())
        val args = op.compilerArguments

        val value: List<CompilerPlugin> = args[CommonCompilerArguments.COMPILER_PLUGINS]
        assertTrue(value.isEmpty(), "Expected COMPILER_PLUGINS to have default value 'emptyList()'")
    }
}