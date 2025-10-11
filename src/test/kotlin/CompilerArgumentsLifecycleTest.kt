import org.jetbrains.kotlin.buildtools.api.DeprecatedCompilerArgument
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.RemovedCompilerArgument
import org.jetbrains.kotlin.buildtools.api.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.buildtools.api.arguments.ExperimentalCompilerArgument
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain.Companion.jvm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Tests for compiler argument lifecycle scenarios.
 * 
 * These tests verify the behavior of compiler arguments with different lifecycle states:
 * 1. Arguments removed in versions older than the current implementation
 * 2. Arguments introduced in newer versions than the current implementation
 * 3. Arguments removed in the current API version but still available in the implementation
 * 4. Deprecated arguments (should work with opt-in)
 * 5. Experimental compiler arguments (should work with opt-in)
 * 
 * Test setup:
 * - API version: 2.3.0-Beta1
 * - Implementation version: 2.2.20 (with compat layer)
 */
@OptIn(ExperimentalBuildToolsApi::class)
class CompilerArgumentsLifecycleTest : TestBase() {

    /**
     * Configures compiler arguments to disable automatic stdlib and reflect lookups.
     *
     * This prevents warnings about missing kotlin-stdlib.jar, kotlin-script-runtime.jar,
     * and kotlin-reflect.jar in the Kotlin home directory.
     *
     * @param compilerArguments The compiler arguments to configure
     */
    private fun configureNoStdlibNoReflect(compilerArguments: JvmCompilerArguments) {
        compilerArguments[JvmCompilerArguments.NO_STDLIB] = true
        compilerArguments[JvmCompilerArguments.NO_REFLECT] = true
    }

    /**
     * Test 1: Unknown CLI argument should be rejected with a clear error message.
     *
     * This test uses a deliberately non-existent flag to avoid coupling to any
     * specific Kotlin version or lifecycle state (removed/introduced).
     * The goal is to assert that the compiler front-end surfaces an "unknown option"
     * style error and the build fails cleanly.
     */
    @Test
    fun testUnknownCompilerArgument() {
        val toolchain = loadToolchain()
        val (src, outDir) = createTestFiles("bta-test-unknown-arg")

        val exception = assertFailsWith<Exception> {
            val operation = toolchain.jvm.createJvmCompilationOperation(listOf(src), outDir)
            configureNoStdlibNoReflect(operation.compilerArguments)

            val unknownFlag = "-foo"
            operation.compilerArguments.applyArgumentStrings(listOf(unknownFlag))

            StdlibResolver.configureStdlibClasspath(operation.compilerArguments, toolchain.getCompilerVersion())

            toolchain.createBuildSession().use { session ->
                session.executeOperation(operation, toolchain.createInProcessExecutionPolicy(), logger)
            }
        }

        logger.debug("Unknown compiler argument error: ${exception.message}")
        assertNoClasses(outDir)
    }
    
    /**
     * Test 2: Option introduced in 2.3.0
     * 
     * This test attempts to use an argument that was introduced in a version newer than
     * the current implementation. When using impl 2.2.20 with an argument introduced in 2.3.0,
     * the system should detect this and fail with a clear error message.
     * 
     * Example: X_NAME_BASED_DESTRUCTURING was introduced in 2.3.0
     */
    @OptIn(ExperimentalCompilerArgument::class)
    @Test
    fun testOptionIntroducedInNewerVersion() {
        val toolchain = loadToolchain()
        val (src, outDir) = createTestFiles("bta-test-newer-option")

        val argument = CommonCompilerArguments.X_NAME_BASED_DESTRUCTURING
        val exception = assertFailsWith<Exception> {
            val operation = toolchain.jvm.createJvmCompilationOperation(listOf(src), outDir)
            configureNoStdlibNoReflect(operation.compilerArguments)

            StdlibResolver.configureStdlibClasspath(operation.compilerArguments, toolchain.getCompilerVersion())

            // X_NAME_BASED_DESTRUCTURING was introduced in 2.3.0
            operation.compilerArguments[argument] = "complete"

            toolchain.createBuildSession().use { session ->
                session.executeOperation(operation, toolchain.createInProcessExecutionPolicy(), logger)
            }
        }

        assertEquals(
            exception.message?.contains("${argument.id} is available only since", ignoreCase = true),
            true,
            "Expected error message about argument introduced in newer BTA version, got: ${exception.message}"
        )

        logger.debug("Option introduced in newer version error: ${exception.message}")
        assertNoClasses(outDir)
    }
    
    /**
     * Test 3: Option removed in 2.3.0 but available in impl 2.2.20
     * 
     * This test demonstrates the scenario where an argument was removed in API 2.3.0
     * but is still available in impl 2.2.20. Such arguments should work successfully
     * with the compat layer.
     * 
     * Example: X_USE_K2_KAPT was removed in 2.3.0 but exists in 2.2.20
     *
     */
    @OptIn(RemovedCompilerArgument::class, ExperimentalCompilerArgument::class)
    @Test
    fun testOptionRemovedInCurrentApiButAvailableInImpl() {
        val toolchain = loadToolchain()
        val (src, outDir) = createTestFiles("bta-test-removed-option")

        // X_USE_K2_KAPT was removed in 2.3.0, but impl 2.2.20 should still support it
        val operation = toolchain.jvm.createJvmCompilationOperation(listOf(src), outDir)
        configureNoStdlibNoReflect(operation.compilerArguments)
        StdlibResolver.configureStdlibClasspath(operation.compilerArguments, toolchain.getCompilerVersion())
        operation.compilerArguments[JvmCompilerArguments.X_USE_K2_KAPT] = false

        toolchain.createBuildSession().use { session ->
            val result = session.executeOperation(operation, toolchain.createInProcessExecutionPolicy(), logger)
            assertEquals("COMPILATION_SUCCESS", result.toString())
            assertHasClasses(outDir)
        }
    }
    
    /**
     * Test 4: Deprecated argument
     * 
     * This test uses a deprecated argument. Deprecated arguments should still work
     * but require opt-in to DeprecatedCompilerArgument.
     * 
     * Example: X_JVM_DEFAULT was deprecated in 2.2.0
     */
    @Test
    @OptIn(DeprecatedCompilerArgument::class, ExperimentalCompilerArgument::class)
    fun testDeprecatedArgument() {
        val toolchain = loadToolchain()
        val (src, outDir) = createTestFiles("bta-test-deprecated-arg")

        val operation = toolchain.jvm.createJvmCompilationOperation(listOf(src), outDir)
        configureNoStdlibNoReflect(operation.compilerArguments)
        StdlibResolver.configureStdlibClasspath(operation.compilerArguments, toolchain.getCompilerVersion())
        operation.compilerArguments[JvmCompilerArguments.X_JVM_DEFAULT] = "all-compatibility"

        toolchain.createBuildSession().use { session ->
            val result = session.executeOperation(operation, toolchain.createInProcessExecutionPolicy(), logger)
            assertEquals("COMPILATION_SUCCESS", result.toString())
            assertHasClasses(outDir)
        }
    }

    /**
     * Test 5: Experimental compiler argument
     * 
     * This test uses an experimental compiler argument. Experimental arguments
     * require opt-in to ExperimentalCompilerArgument (already opted in at class level).
     * 
     * Example: PROGRESSIVE mode
     */
    @OptIn(ExperimentalCompilerArgument::class)
    @Test
    fun testExperimentalArgument() {
        val toolchain = loadToolchain()
        val (src, outDir) = createTestFiles("bta-test-experimental-arg")

        val operation = toolchain.jvm.createJvmCompilationOperation(listOf(src), outDir)
        configureNoStdlibNoReflect(operation.compilerArguments)

        StdlibResolver.configureStdlibClasspath(operation.compilerArguments, toolchain.getCompilerVersion())

        operation.compilerArguments[JvmCompilerArguments.X_NO_CALL_ASSERTIONS] = true

        toolchain.createBuildSession().use { session ->
            val result = session.executeOperation(operation, toolchain.createInProcessExecutionPolicy(), logger)
            assertEquals("COMPILATION_SUCCESS", result.toString())
            assertHasClasses(outDir)
        }
    }
}
