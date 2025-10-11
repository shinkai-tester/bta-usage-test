import org.jetbrains.kotlin.buildtools.api.BuildOperation
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Tests demonstrating error scenarios from KotlinToolchainsV1Adapter
 * when using BTA as a dependency.
 * 
 * These tests verify that appropriate error messages are thrown when:
 * 1. Requesting an unsupported platform toolchain type
 * 2. Executing an unsupported operation type
 */
@OptIn(ExperimentalBuildToolsApi::class)
class ErrorScenarioDemonstrationTest : TestBase() {
    
    /**
     * A fake toolchain interface to simulate requesting an unsupported toolchain type.
     * This is not JvmPlatformToolchain, so it will trigger the error.
     */
    interface FakePlatformToolchain : KotlinToolchains.Toolchain
    
    /**
     * A fake operation to simulate executing an unsupported operation type.
     * This is neither JvmCompilationOperation nor JvmClasspathSnapshottingOperation,
     * so it will trigger the error.
     */
    class FakeOperation : BuildOperation<String> {
        override fun <V> get(key: BuildOperation.Option<V>): V {
            throw UnsupportedOperationException("This is a fake operation")
        }
        
        override fun <V> set(key: BuildOperation.Option<V>, value: V) {
            throw UnsupportedOperationException("This is a fake operation")
        }
    }
    
    
    /**
     * Tests that requesting an unsupported platform toolchain type throws the correct error.
     * 
     * Expected error message:
     * "Unsupported platform toolchain type: $type. Only JVM compilation is supported 
     *  in BTA API v1 fallback (compiler version ${getCompilerVersion()})."
     */
    @Test
    fun testUnsupportedPlatformToolchainError() {
        val toolchain = loadToolchain()

        val exception = assertFailsWith<IllegalStateException> {
            toolchain.getToolchain(FakePlatformToolchain::class.java)
        }

        logger.info("✓ Unsupported Platform Toolchain Error:")
        logger.info("  ${exception.message}")

        assertEquals(exception.message?.contains("Unsupported platform toolchain type"), true)
        assertEquals(exception.message?.contains("Only JVM compilation is supported"), true)
        assertEquals(exception.message?.contains("BTA API v1 fallback"), true)
    }
    
    /**
     * Tests that executing an unsupported operation type throws the correct error.
     * 
     * Expected error message:
     * "Unsupported operation type with BTA API v1 fallback (compiler version 
     *  ${kotlinToolchains.getCompilerVersion()}): ${operation::class.simpleName}."
     */
    @Test
    fun testUnsupportedOperationTypeError() {
        val toolchain = loadToolchain()
        
        val exception = assertFailsWith<IllegalStateException> {
            val fakeOperation = FakeOperation()
            val policy = toolchain.createInProcessExecutionPolicy()

            toolchain.createBuildSession().use { session ->
                session.executeOperation(fakeOperation, policy, logger)
            }
        }

        logger.info("✓ Unsupported Operation Type Error:")
        logger.info("  ${exception.message}")

        assertEquals(exception.message?.contains("Unsupported operation type"), true)
        assertEquals(exception.message?.contains("BTA API v1 fallback"), true)
        assertEquals(exception.message?.contains("FakeOperation"), true)
    }
}
