import support.TestBase
import framework.TestLogger
import framework.configureDaemonPolicy
import org.jetbrains.kotlin.buildtools.api.ExecutionPolicy
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.jetbrains.kotlin.buildtools.api.arguments.enums.JvmTarget
import org.jetbrains.kotlin.buildtools.api.arguments.enums.KotlinVersion
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import utils.CompilationTestUtils
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for compiler arguments with actual compilation.
 *
 * This test class focuses on:
 * - End-to-end compilation with various arguments
 * - Verifying that arguments affect compilation behavior correctly
 * - Testing argument combinations in real scenarios
 */
@OptIn(ExperimentalBuildToolsApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompilerArgumentsIntegrationTest : TestBase() {

    private lateinit var toolchain: KotlinToolchains
    private lateinit var daemonPolicy: ExecutionPolicy

    @BeforeAll
    fun initCommonToolchain() {
        toolchain = framework.loadToolchain()
        daemonPolicy = configureDaemonPolicy(toolchain)
    }

    @Test
    @DisplayName("Check setting of common compiler arguments")
    fun testCommonCompilerArguments() {
        val setup = createTestSetup()
        val annotationsKt = framework.createKotlinSource(
            setup.workspace, "annotations.kt", """
        package test.common

        @RequiresOptIn(level = RequiresOptIn.Level.ERROR)
        annotation class MyExperimental

        @MyExperimental
        fun experimentalApi(): String = "exp"
    """
        )
        val usageKt = framework.createKotlinSource(
            setup.workspace, "useExperimental.kt", """
        package test.common

        fun callExp(): String {
            return experimentalApi()
        }
    """
        )

        val jvmToolchain = toolchain.getToolchain(JvmPlatformToolchain::class.java)
        val builder = jvmToolchain.jvmCompilationOperationBuilder(
            listOf(annotationsKt, usageKt),
            setup.outputDirectory
        )

        framework.configureBasicCompilerArguments(builder.compilerArguments, "test-module")
        builder.compilerArguments[CommonCompilerArguments.LANGUAGE_VERSION] = KotlinVersion.V2_4
        builder.compilerArguments[CommonCompilerArguments.API_VERSION] = KotlinVersion.V2_4
        builder.compilerArguments[CommonCompilerArguments.PROGRESSIVE] = true
        builder.compilerArguments[CommonCompilerArguments.OPT_IN] = arrayOf("test.common.MyExperimental")

        val op = builder.build()

        val logger = TestLogger()
        val result = CompilationTestUtils.runCompile(toolchain, op, daemonPolicy, logger)
        assertCompilationSuccessful(result)
        assertClassFilesExist(setup.outputDirectory, "AnnotationsKt", "UseExperimentalKt")
    }

    @Test
    @DisplayName("JVM-specific arguments: jvm-target, module-name")
    fun testJvmSpecificArguments() {
        val setup = createTestSetup()
        val src = framework.createKotlinSource(
            setup.workspace, "Simple.kt", """
        class Greeter {
            fun greet(name: String): String = "Hello, " + name
        }

        fun callIt(): String = Greeter().greet("QA")
        """
        )

        val jvmToolchain = toolchain.getToolchain(JvmPlatformToolchain::class.java)
        val builder = jvmToolchain.jvmCompilationOperationBuilder(listOf(src), setup.outputDirectory)

        framework.configureBasicCompilerArguments(builder.compilerArguments, "simple-module")
        builder.compilerArguments[JvmCompilerArguments.JVM_TARGET] = JvmTarget.JVM_17

        val op = builder.build()

        val result = CompilationTestUtils.runCompile(toolchain, op, daemonPolicy, TestLogger())
        assertCompilationSuccessful(result)
        assertClassFilesExist(setup.outputDirectory, "SimpleKt", "Greeter")

        // Verify JVM target by checking class file version
        val bytes = CompilationTestUtils.readClassOutputBytes(setup.outputDirectory, "Greeter.class")
        assertNotNull(bytes)
        val major = readClassFileMajorVersion(bytes)
        assertEquals(61, major, "-jvm-target 17 should produce classfile major version 61")
    }

    @Test
    @DisplayName("Error handling: missing stdlib causes compilation failure")
    fun testMissingStdlibCausesFailure() {
        val setup = createTestSetup()
        val src = framework.createKotlinSource(
            setup.workspace, "NeedsStdlib.kt", """
            fun build(): List<String> = listOf("a", "b")
        """
        )

        val jvmToolchain = toolchain.getToolchain(JvmPlatformToolchain::class.java)
        val builder = jvmToolchain.jvmCompilationOperationBuilder(listOf(src), setup.outputDirectory)

        builder.compilerArguments[JvmCompilerArguments.NO_STDLIB] = true
        builder.compilerArguments[JvmCompilerArguments.CLASSPATH] = ""
        builder.compilerArguments[JvmCompilerArguments.MODULE_NAME] = "test-module"

        val op = builder.build()

        val logger = TestLogger()
        val result = CompilationTestUtils.runCompile(toolchain, op, daemonPolicy, logger)
        assertCompilationFailed(result)
        assertTrue(
            logger.getAllErrorMessages()
                .any { it.contains("kotlin", ignoreCase = true) && it.contains("unresolved", ignoreCase = true) } ||
                    logger.getAllErrorMessages().any {
                        it.contains("listOf", ignoreCase = true) && it.contains("unresolved", ignoreCase = true)
                    },
            "Expected unresolved references due to missing stdlib in classpath"
        )
    }

    private fun readClassFileMajorVersion(bytes: ByteArray): Int {
        require(bytes.size >= 8)
        fun u2(i: Int) = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
        val magic =
            (((bytes[0].toInt() and 0xFF) shl 24) or
             ((bytes[1].toInt() and 0xFF) shl 16) or
             ((bytes[2].toInt() and 0xFF) shl 8) or
             (bytes[3].toInt() and 0xFF))
        require(magic == 0xCAFEBABE.toInt()) { "Invalid class file" }
        return u2(6)
    }
}