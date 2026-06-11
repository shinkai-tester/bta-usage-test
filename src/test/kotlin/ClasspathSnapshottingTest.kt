import framework.TestLogger
import framework.applyTestDefaults
import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.ExecutionPolicy
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.arguments.ExperimentalCompilerArgument
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain.Companion.jvm
import org.jetbrains.kotlin.buildtools.api.jvm.jvmCompilationOperation
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmCompilationOperation
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ArgumentsSource
import support.ExecutionPolicyArgumentProvider
import support.TestBase
import utils.CompilationTestUtils
import utils.IncrementalCompilationUtils
import utils.StdlibUtils
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for JvmClasspathSnapshottingOperation and multi-module incremental compilation.
 *
 * Validates that classpath snapshots can be generated for a compiled module's output and used as
 * dependency snapshots in a downstream module's incremental compilation. The multi-module tests
 * assert on which sources the compiler actually recompiled, parsed from the `compile iteration:`
 * debug log.
 */
@OptIn(ExperimentalBuildToolsApi::class, ExperimentalCompilerArgument::class)
class ClasspathSnapshottingTest : TestBase() {

    @ParameterizedTest(name = "{0}: {displayName}")
    @ArgumentsSource(ExecutionPolicyArgumentProvider::class)
    @DisplayName("Generate classpath snapshot for compiled module output")
    fun generateClasspathSnapshot(execution: Pair<KotlinToolchains, ExecutionPolicy>) {
        val (toolchain, policy) = execution
        val setup = createTestSetup()
        val snapshotDir = setup.workspace.resolve("snapshots").createDirectories()

        val source = createKotlinSource(setup.workspace, "LibClass.kt", """
            package lib
            class LibClass {
                fun greet(): String = "hello"
            }
        """)

        val compileOp = toolchain.jvm.jvmCompilationOperation(listOf(source), setup.outputDirectory) {
            compilerArguments.applyTestDefaults()
        }
        val result = CompilationTestUtils.runCompile(toolchain, compileOp, policy)
        assertCompilationSuccessful(result)

        toolchain.createBuildSession().use { session ->
            val snapshotFile = IncrementalCompilationUtils.generateClasspathSnapshot(
                toolchain, session, setup.outputDirectory, snapshotDir
            )
            assertTrue(snapshotFile.toFile().exists(), "Snapshot file should be created")
            assertTrue(snapshotFile.toFile().length() > 0, "Snapshot file should not be empty")
        }
    }

    @ParameterizedTest(name = "{0}: {displayName}")
    @ArgumentsSource(ExecutionPolicyArgumentProvider::class)
    @DisplayName("Multi-module IC with snapshots: non-ABI change in lib does NOT recompile app")
    fun multiModuleIcWithSnapshotsNonAbiChange(execution: Pair<KotlinToolchains, ExecutionPolicy>) {
        val (toolchain, policy) = execution

        // Body-only change in lib: the consumer must not be recompiled (snapshot diff finds no ABI change).
        val appRecompileLogger = runLibChangeScenario(
            toolchain, policy,
            libBefore = """
                package lib
                class Provider { fun value(): Int = 100 }
            """,
            libAfter = """
                package lib
                class Provider { fun value(): Int = 200 }
            """,
        )

        assertNotRecompiled(appRecompileLogger, "Consumer.kt")
    }

    @ParameterizedTest(name = "{0}: {displayName}")
    @ArgumentsSource(ExecutionPolicyArgumentProvider::class)
    @DisplayName("Multi-module IC with snapshots: ABI change in lib recompiles app")
    fun multiModuleIcWithSnapshotsAbiChange(execution: Pair<KotlinToolchains, ExecutionPolicy>) {
        val (toolchain, policy) = execution

        // Signature change in lib (extra default parameter): the snapshot diff detects the ABI change,
        // so the consumer must be recompiled.
        val appRecompileLogger = runLibChangeScenario(
            toolchain, policy,
            libBefore = """
                package lib
                class Provider { fun value(): Int = 100 }
            """,
            libAfter = """
                package lib
                class Provider { fun value(multiplier: Int = 1): Int = 100 * multiplier }
            """,
        )

        assertRecompiled(appRecompileLogger, "Consumer.kt")
    }

    /**
     * Builds a tiny two-module project (`lib` + `app`, where `app` depends on `lib`), then changes
     * `lib` from [libBefore] to [libAfter] and incrementally recompiles `app` against a fresh
     * classpath snapshot of `lib`.
     *
     * Returns the logger of that final incremental `app` compilation so the caller can assert
     * which `app` sources were recompiled.
     */
    private fun runLibChangeScenario(
        toolchain: KotlinToolchains,
        policy: ExecutionPolicy,
        libBefore: String,
        libAfter: String,
    ): TestLogger {
        // lib module
        val libWorkspace = createTempWorkspace()
        val libOutDir = libWorkspace.resolve("out").createDirectories()
        val snapshotDir = libWorkspace.resolve("snapshots").createDirectories()
        // app module (depends on lib)
        val appWorkspace = createTempWorkspace()
        val appOutDir = appWorkspace.resolve("out").createDirectories()
        val appIcDir = appWorkspace.resolve("ic").createDirectories()

        val libSource = createKotlinSource(libWorkspace, "Provider.kt", libBefore)
        val appSource = createKotlinSource(appWorkspace, "Consumer.kt", """
            package app
            import lib.Provider
            class Consumer { fun use() = Provider().value().toString().length }
        """)

        // 1. Compile lib (initial version).
        assertCompilationSuccessful(compileLib(toolchain, libSource, libOutDir, policy))

        return toolchain.createBuildSession().use { session ->
            // 2. Snapshot lib and do the first incremental compile of app against it.
            val snapshot1 = IncrementalCompilationUtils.generateClasspathSnapshot(
                toolchain, session, libOutDir, snapshotDir
            )
            val appOp1 = createAppOperation(
                toolchain, appWorkspace, appOutDir, appIcDir, libOutDir,
                listOf(appSource), listOf(snapshot1)
            )
            assertEquals(
                CompilationResult.COMPILATION_SUCCESS,
                CompilationTestUtils.runCompile(session, appOp1, policy),
                "Initial app compilation should succeed"
            )

            // 3. Change lib and recompile it.
            libSource.writeText(libAfter.trimIndent())
            assertCompilationSuccessful(compileLib(toolchain, libSource, libOutDir, policy))

            // 4. Re-snapshot lib and incrementally recompile app, capturing its log.
            val snapshot2 = IncrementalCompilationUtils.generateClasspathSnapshot(
                toolchain, session, libOutDir, snapshotDir
            )
            val appOp2 = createAppOperation(
                toolchain, appWorkspace, appOutDir, appIcDir, libOutDir,
                listOf(appSource), listOf(snapshot2)
            )
            val logger = TestLogger(false)
            assertEquals(
                CompilationResult.COMPILATION_SUCCESS,
                CompilationTestUtils.runCompile(session, appOp2, policy, logger),
                "App recompilation should succeed"
            )
            logger
        }
    }

    /** Compiles the single-file `lib` module as a standalone (non-incremental) compilation. */
    private fun compileLib(
        toolchain: KotlinToolchains,
        libSource: Path,
        libOutDir: Path,
        policy: ExecutionPolicy,
    ): CompilationResult {
        val op = toolchain.jvm.jvmCompilationOperation(listOf(libSource), libOutDir) {
            compilerArguments.applyTestDefaults()
        }
        return CompilationTestUtils.runCompile(toolchain, op, policy)
    }

    /**
     * Creates an "app" module JVM compilation operation with IC configured,
     * including the lib output on the classpath and dependency snapshots.
     */
    private fun createAppOperation(
        toolchain: KotlinToolchains,
        appWorkspace: Path,
        appOutDir: Path,
        appIcDir: Path,
        libOutDir: Path,
        sources: List<Path>,
        dependencySnapshots: List<Path>
    ): JvmCompilationOperation = toolchain.jvm.jvmCompilationOperation(sources, appOutDir) {
        // Configure classpath to include lib output + stdlib
        val stdlib = StdlibUtils.findStdlibJar()
        val classpathPaths = listOfNotNull(libOutDir, stdlib.takeIf { it.isNotBlank() }?.let { Path(it) })

        compilerArguments[JvmCompilerArguments.NO_STDLIB] = true
        compilerArguments[JvmCompilerArguments.NO_REFLECT] = true
        compilerArguments[JvmCompilerArguments.CLASSPATH] = classpathPaths
        compilerArguments[JvmCompilerArguments.MODULE_NAME] = "app-module"

        IncrementalCompilationUtils.configureIcOnBuilder(
            this, appIcDir, appOutDir, appWorkspace,
            dependencySnapshots = dependencySnapshots
        )
    }
}