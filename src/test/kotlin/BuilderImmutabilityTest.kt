import support.TestBase
import framework.applyBasicCompilerArguments
import framework.loadToolchain
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.jetbrains.kotlin.buildtools.api.arguments.enums.JvmTarget
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain.Companion.jvm
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import utils.CompilationTestUtils
import kotlin.test.assertEquals

/**
 * Tests verifying the immutability contract of BTA build operations and builders.
 *
 * These tests validate from a consumer perspective that:
 * - Modifying a builder after build() does not affect the already-built operation
 * - toBuilder() round-trip preserves immutability of the original operation
 */

@OptIn(ExperimentalBuildToolsApi::class)
class BuilderImmutabilityTest : TestBase() {

    @Test
    @DisplayName("Modifying builder after build does not affect the built operation")
    fun testBuilderImmutability() {
        val setup = createTestSetup()
        val source = createKotlinSource(setup.workspace, "Immutable.kt", """
            class Immutable
        """)

        val toolchain = loadToolchain()
        val builder = toolchain.jvm.jvmCompilationOperationBuilder(listOf(source), setup.outputDirectory)
        builder.compilerArguments.applyBasicCompilerArguments("original-module")
        builder.compilerArguments[JvmCompilerArguments.JVM_TARGET] = JvmTarget.JVM_17

        val operation = builder.build()

        builder.compilerArguments[JvmCompilerArguments.MODULE_NAME] = "modified-module"
        builder.compilerArguments[JvmCompilerArguments.JVM_TARGET] = JvmTarget.JVM_21

        assertEquals("original-module", operation.compilerArguments[JvmCompilerArguments.MODULE_NAME])
        assertEquals(JvmTarget.JVM_17, operation.compilerArguments[JvmCompilerArguments.JVM_TARGET])

        val policy = toolchain.createInProcessExecutionPolicy()
        val result = CompilationTestUtils.runCompile(toolchain, operation, policy)
        assertCompilationSuccessful(result)
    }

    @Test
    @DisplayName("toBuilder round-trip preserves immutability of original operation")
    fun testToBuilderImmutability() {
        val setup = createTestSetup()
        val source = createKotlinSource(setup.workspace, "RoundTrip.kt", """
            class RoundTrip
        """)

        val toolchain = loadToolchain()
        val builder = toolchain.jvm.jvmCompilationOperationBuilder(listOf(source), setup.outputDirectory)
        builder.compilerArguments.applyBasicCompilerArguments("original")
        builder.compilerArguments[JvmCompilerArguments.JVM_TARGET] = JvmTarget.JVM_17

        val original = builder.build()

        val newBuilder = original.toBuilder()
        newBuilder.compilerArguments[JvmCompilerArguments.MODULE_NAME] = "modified"
        newBuilder.compilerArguments[JvmCompilerArguments.JVM_TARGET] = JvmTarget.JVM_21

        assertEquals("original", original.compilerArguments[JvmCompilerArguments.MODULE_NAME])
        assertEquals(JvmTarget.JVM_17, original.compilerArguments[JvmCompilerArguments.JVM_TARGET])
    }
}
