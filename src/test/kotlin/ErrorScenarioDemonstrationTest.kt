import org.jetbrains.kotlin.buildtools.api.BuildOperation
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.SharedApiClassesClassLoader
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path
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
class ErrorScenarioDemonstrationTest {
    
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
     * Helper function to load the toolchain from the compiler classpath.
     * This mimics what Main.kt does but for testing purposes.
     */
    private fun loadToolchain(): KotlinToolchains {
        // Get compiler classpath from system property (set by a Gradle test task)
        val compilerClasspath = System.getProperty("test.compiler.classpath")
            ?: error("test.compiler.classpath system property not set")
        
        val compilerUrls = compilerClasspath.split(File.pathSeparator)
            .map { Path.of(it).toUri().toURL() }
            .toTypedArray()
        
        val compilerClassloader = URLClassLoader(compilerUrls, SharedApiClassesClassLoader())
        return KotlinToolchains.loadImplementation(compilerClassloader)
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
        
        println("✓ Unsupported Platform Toolchain Error:")
        println("  ${exception.message}")

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
                session.executeOperation(fakeOperation, policy, null)
            }
        }
        
        println("✓ Unsupported Operation Type Error:")
        println("  ${exception.message}")

        assertEquals(exception.message?.contains("Unsupported operation type"), true)
        assertEquals(exception.message?.contains("BTA API v1 fallback"), true)
        assertEquals(exception.message?.contains("FakeOperation"), true)
    }
}
