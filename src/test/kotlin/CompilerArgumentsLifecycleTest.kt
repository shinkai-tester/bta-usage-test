import support.TestBase
import framework.TestLogger
import framework.applyTestDefaults
import framework.loadToolchain
import org.jetbrains.kotlin.buildtools.api.*
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain.Companion.jvm
import org.jetbrains.kotlin.buildtools.api.jvm.jvmCompilationOperation
import org.jetbrains.kotlin.buildtools.api.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.buildtools.api.arguments.ExperimentalCompilerArgument
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.junit.jupiter.api.*
import utils.CompilationTestUtils
import kotlin.test.assertTrue

@OptIn(ExperimentalBuildToolsApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Suppress("DEPRECATION")
class CompilerArgumentsLifecycleTest : TestBase() {

    private lateinit var toolchain: KotlinToolchains
    private lateinit var daemonPolicy: ExecutionPolicy

    @BeforeAll
    fun initCommonToolchain() {
        toolchain = loadToolchain()
        daemonPolicy = toolchain.createInProcessExecutionPolicy()
    }

    @Test
    @DisplayName("Experimental option with opt-in -> should work")
    @OptIn(ExperimentalCompilerArgument::class)
    fun testExperimentalArgument() {
        val setup = createTestSetup()
        val src = createKotlinSource(
            setup.workspace, "Foo.kt", """
            fun foo() = "test"
        """
        )

        val op = toolchain.jvm.jvmCompilationOperation(listOf(src), setup.outputDirectory) {
            compilerArguments.applyTestDefaults()
            compilerArguments[CommonCompilerArguments.X_NO_INLINE] = true
            compilerArguments[JvmCompilerArguments.X_DEBUG] = true
        }

        val result = CompilationTestUtils.runCompile(toolchain, op, daemonPolicy)

        assertCompilationSuccessful(result, "Experimental argument with opt-in should work")
    }

    @Test
    @DisplayName("Usage of removed option -> fails with clear message on unsupported compiler")
    @OptIn(ExperimentalCompilerArgument::class, RemovedCompilerArgument::class)
    fun testRemovedArgument() {
        val setup = createTestSetup()
        val src = createKotlinSource(
            setup.workspace, "Simple.kt", """
            fun test() = "test"
        """
        )

        val logger = TestLogger(true)
        var thrown: Throwable? = null
        val result = try {
            // Building the operation already validates arguments and (with a removed argument)
            // throws here, so the construction must stay inside the try-catch.
            val op = toolchain.jvm.jvmCompilationOperation(listOf(src), setup.outputDirectory) {
                compilerArguments.applyTestDefaults()
                compilerArguments[JvmCompilerArguments.X_USE_K2_KAPT] = true
            }
            CompilationTestUtils.runCompile(toolchain, op, daemonPolicy, logger)
        } catch (t: Throwable) {
            thrown = t
            null
        }

        val failedByResult = (result == CompilationResult.COMPILATION_ERROR)
        assertTrue(
            thrown != null || failedByResult,
            "Expected compilation to fail (either by throwing or by returning an error). Result=$result, thrown=$thrown"
        )

        val errors = logger.getAllErrorMessages()
        val extra = buildString {
            var c = thrown
            while (c != null) {
                append(c.toString()).append('\n')
                append(c.message ?: "").append('\n')
                c = c.cause
            }
        }
        val joined = (errors + extra).joinToString("\n")
        println("[testRemovedArgument] Diagnostics (errors + throwable chain):\n$joined")

        assertTrue(joined.contains("Compiler parameter not recognized", ignoreCase = true))
        assertTrue(joined.contains("removed in", ignoreCase = true))

        assertTrue(
            joined.contains("NoSuchMethodError") ||
                    joined.contains("No property found with name", ignoreCase = true),
            "Expected a hint about missing compiler argument property (NoSuchMethodError / No property found ...)\n$joined"
        )
    }

    @Test
    @DisplayName("Deprecated argument: compiles with opt-in (no runtime deprecation log expected)")
    @OptIn(ExperimentalCompilerArgument::class, DeprecatedCompilerArgument::class)
    fun testDeprecatedArgument() {
        val setup = createTestSetup()
        val logger = TestLogger(true)
        val src = createKotlinSource(
            setup.workspace, "Simple.kt", """
            fun test() = "test"
        """
        )

        val op = toolchain.jvm.jvmCompilationOperation(listOf(src), setup.outputDirectory) {
            compilerArguments.applyTestDefaults()
            compilerArguments[JvmCompilerArguments.X_JVM_DEFAULT] = "all"
        }

        val result = CompilationTestUtils.runCompile(toolchain, op, daemonPolicy, logger)

        assertCompilationSuccessful(result, "Deprecated argument should still work at runtime with opt-in")
    }

    @Test
    @DisplayName("applyArgumentStrings: missing value for required option -> CompilerArgumentsParseException")
    fun testApplyArgumentMissingValue() {
        val setup = createTestSetup()
        val src = createKotlinSource(setup.workspace, "Foo.kt", """
        fun foo() = 42
    """)

        val builder = toolchain.jvm.jvmCompilationOperationBuilder(listOf(src), setup.outputDirectory).apply {
            compilerArguments.applyTestDefaults()
        }

        val ex = Assertions.assertThrows(CompilerArgumentsParseException::class.java) {
            builder.compilerArguments.applyArgumentStrings(listOf("-jvm-target"))
        }
        println("[testApplyArgumentMissingValue] Caught expected CompilerArgumentsParseException: ${ex.message}")
    }

    @Test
    @DisplayName("Setting a key with availableSinceVersion > current BTA version fails early")
    fun testAvailableSince_guard_on_set() {
        val setup = createTestSetup()
        val src = createKotlinSource(setup.workspace, "Bar.kt", """
        fun bar() = 0
    """
        )

        val builder = toolchain.jvm.jvmCompilationOperationBuilder(listOf(src), setup.outputDirectory).apply {
            compilerArguments.applyTestDefaults()
        }
        val args = builder.compilerArguments

        val future = JvmCompilerArguments.JvmCompilerArgument<Boolean>(
            "X_FAKE_FUTURE_FLAG",
            KotlinReleaseVersion(9, 9, 9)
        )

        val ex = Assertions.assertThrows(IllegalStateException::class.java) {
            args[future] = true
        }

        val msg = ex.message ?: ""
        println("[testAvailableSince_guard_on_set] Caught expected IllegalStateException: $msg")
        assertTrue(msg.contains("X_FAKE_FUTURE_FLAG is available only since"), msg)
        val hasReadableOrObjectVersion =
            msg.contains("9.9.9") || msg.contains("org.jetbrains.kotlin.buildtools.api.KotlinReleaseVersion@")
        assertTrue(
            hasReadableOrObjectVersion,
            "Expected message to contain a version (either '9.9.9' or the KotlinReleaseVersion object); was: $msg"
        )
    }
}
