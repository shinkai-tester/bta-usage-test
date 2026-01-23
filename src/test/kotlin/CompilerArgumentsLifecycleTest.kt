import CompilationTestUtils.newJvmOp
import org.jetbrains.kotlin.buildtools.api.*
import org.jetbrains.kotlin.buildtools.api.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.buildtools.api.arguments.ExperimentalCompilerArgument
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.junit.jupiter.api.*
import kotlin.test.assertTrue

@OptIn(ExperimentalBuildToolsApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Suppress("DEPRECATION") // Tests intentionally use deprecated mutable compiler arguments API
class CompilerArgumentsLifecycleTest : TestBase() {

    private lateinit var toolchain: KotlinToolchains
    private lateinit var daemonPolicy: ExecutionPolicy

    @BeforeAll
    fun initCommonToolchain() {
        toolchain = framework.loadToolchain(useDaemon = false) // Use in-process mode
        daemonPolicy = toolchain.createInProcessExecutionPolicy()
    }

    @Test
    @DisplayName("Experimental option with opt-in -> should work")
    @OptIn(ExperimentalCompilerArgument::class)
    fun testExperimentalArgument() {
        val setup = createTestSetup()
        val src = framework.createKotlinSource(
            setup.workspace, "Foo.kt", """
            fun foo() = "test"
        """
        )

        val op = newJvmOp(toolchain, listOf(src), setup.outputDirectory, framework)
        val args = op.compilerArguments

        args[CommonCompilerArguments.X_NO_INLINE] = true
        args[JvmCompilerArguments.X_DEBUG] = true

        val result = framework.withDaemonContext {
            CompilationTestUtils.runCompile(toolchain, op, daemonPolicy)
        }

        assertCompilationSuccessful(result, "Experimental argument with opt-in should work")
    }

    @Test
    @DisplayName("Usage of removed option -> fails with clear message on unsupported compiler")
    @OptIn(ExperimentalCompilerArgument::class, RemovedCompilerArgument::class)
    fun testRemovedArgument() {
        val setup = createTestSetup()
        val src = framework.createKotlinSource(
            setup.workspace, "Simple.kt", """
            fun test() = "test"
        """
        )

        val op = newJvmOp(toolchain, listOf(src), setup.outputDirectory, framework)
        val args = op.compilerArguments

        // Use an option marked as removed within the support window in your API build
        args[JvmCompilerArguments.X_USE_K2_KAPT] = true

        val logger = TestLogger(true)
        var thrown: Throwable? = null
        val result = try {
            framework.withDaemonContext {
                CompilationTestUtils.runCompile(toolchain, op, daemonPolicy, logger)
            }
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

        // Must mention an unrecognized parameter and that it was removed ("introduced in" may or may not appear)
        assertTrue(joined.contains("Compiler parameter not recognized", ignoreCase = true))
        assertTrue(joined.contains("removed in", ignoreCase = true))

        // Cause should originate from reflective access to a missing property
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
        val src = framework.createKotlinSource(
            setup.workspace, "Simple.kt", """
            fun test() = "test"
        """
        )

        val op = newJvmOp(toolchain, listOf(src), setup.outputDirectory, framework)
        val args = op.compilerArguments

        // Use a key annotated with @DeprecatedCompilerArgument in the generated API
        args[JvmCompilerArguments.X_JVM_DEFAULT] = "all"

        val result = framework.withDaemonContext {
            CompilationTestUtils.runCompile(toolchain, op, daemonPolicy, logger)
        }

        // Should compile successfully when opted in; BTA doesn’t guarantee a runtime deprecation warning
        assertCompilationSuccessful(result, "Deprecated argument should still work at runtime with opt-in")
    }

    @Test
    @DisplayName("applyArgumentStrings: missing value for required option -> CompilerArgumentsParseException")
    fun testApplyArgumentMissingValue() {
        val setup = createTestSetup()
        val src = framework.createKotlinSource(setup.workspace, "Foo.kt", """
        fun foo() = 42
    """)

        val op = newJvmOp(toolchain, listOf(src), setup.outputDirectory, framework)

        val ex = Assertions.assertThrows(CompilerArgumentsParseException::class.java) {
            op.compilerArguments.applyArgumentStrings(listOf("-jvm-target"))
        }
        println("[testApplyArgumentMissingValue] Caught expected CompilerArgumentsParseException: ${ex.message}")
    }

    @Test
    @DisplayName("Setting a key with availableSinceVersion > current BTA version fails early")
    fun testAvailableSince_guard_on_set() {
        val setup = createTestSetup()
        val src = framework.createKotlinSource(setup.workspace, "Bar.kt", """
        fun bar() = 0
    """
        )

        val op = newJvmOp(toolchain, listOf(src), setup.outputDirectory, framework)
        val args = op.compilerArguments

        val future = JvmCompilerArguments.JvmCompilerArgument<Boolean>(
            "X_FAKE_FUTURE_FLAG",
            KotlinReleaseVersion(9, 9, 9)
        )

        val ex = Assertions.assertThrows(IllegalStateException::class.java) {
            args[future] = true
        }

        val msg = ex.message ?: ""
        println("[testAvailableSince_guard_on_set] Caught expected IllegalStateException: $msg")
        // Always must mention the key and the phrase
        assertTrue(msg.contains("X_FAKE_FUTURE_FLAG is available only since"), msg)
        // Accept either the pretty version (if the generator later switches to releaseName) or the current object toString
        val hasReadableOrObjectVersion =
            msg.contains("9.9.9") || msg.contains("org.jetbrains.kotlin.buildtools.api.KotlinReleaseVersion@")
        assertTrue(
            hasReadableOrObjectVersion,
            "Expected message to contain a version (either '9.9.9' or the KotlinReleaseVersion object); was: $msg"
        )
    }
}