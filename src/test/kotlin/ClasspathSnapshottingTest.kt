import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.ExecutionPolicy
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.arguments.ExperimentalCompilerArgument
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.jetbrains.kotlin.buildtools.api.jvm.ClassSnapshotGranularity
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain.Companion.jvm
import org.jetbrains.kotlin.buildtools.api.jvm.jvmCompilationOperation
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmCompilationOperation
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ArgumentsSource
import support.ExecutionPolicyArgumentProvider
import support.TestBase
import framework.applyTestDefaults
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
 * Validates that classpath snapshots can be generated for compiled module outputs
 * and used as dependency snapshots in downstream incremental compilations,
 * following the pattern from the Kotlin Build Tools API source of truth.
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
    @DisplayName("Classpath snapshot with CLASS_LEVEL granularity")
    fun classpathSnapshotClassLevelGranularity(execution: Pair<KotlinToolchains, ExecutionPolicy>) {
        val (toolchain, policy) = execution
        val setup = createTestSetup()
        val snapshotDir = setup.workspace.resolve("snapshots").createDirectories()

        val source = createKotlinSource(setup.workspace, "DataModel.kt", """
            package model
            data class DataModel(val id: Int, val name: String)
        """)

        val compileOp = toolchain.jvm.jvmCompilationOperation(listOf(source), setup.outputDirectory) {
            compilerArguments.applyTestDefaults()
        }
        val result = CompilationTestUtils.runCompile(toolchain, compileOp, policy)
        assertCompilationSuccessful(result)

        toolchain.createBuildSession().use { session ->
            val snapshotFile = IncrementalCompilationUtils.generateClasspathSnapshot(
                toolchain, session, setup.outputDirectory, snapshotDir,
                granularity = ClassSnapshotGranularity.CLASS_LEVEL
            )
            assertTrue(snapshotFile.toFile().exists(), "CLASS_LEVEL snapshot file should be created")
            assertTrue(snapshotFile.toFile().length() > 0, "CLASS_LEVEL snapshot file should not be empty")
        }
    }

    @ParameterizedTest(name = "{0}: {displayName}")
    @ArgumentsSource(ExecutionPolicyArgumentProvider::class)
    @DisplayName("Multi-module IC with classpath snapshots: non-ABI change in dependency")
    fun multiModuleIcWithSnapshots(execution: Pair<KotlinToolchains, ExecutionPolicy>) {
        val (toolchain, policy) = execution

        // Set up "lib" module workspace
        val libWorkspace = createTempWorkspace()
        val libOutDir = libWorkspace.resolve("out").createDirectories()
        val snapshotDir = libWorkspace.resolve("snapshots").createDirectories()

        // Set up "app" module workspace
        val appWorkspace = createTempWorkspace()
        val appOutDir = appWorkspace.resolve("out").createDirectories()
        val appIcDir = appWorkspace.resolve("ic").createDirectories()

        // --- Step 1: Compile the "lib" module ---
        val libSource = createKotlinSource(libWorkspace, "Provider.kt", """
            package lib
            class Provider { fun value(): Int = 100 }
        """)

        val libCompileOp = toolchain.jvm.jvmCompilationOperation(listOf(libSource), libOutDir) {
            compilerArguments.applyTestDefaults()
        }
        val libResult = CompilationTestUtils.runCompile(toolchain, libCompileOp, policy)
        assertCompilationSuccessful(libResult)

        toolchain.createBuildSession().use { session ->
            // --- Step 2: Generate classpath snapshot of "lib" output ---
            val libSnapshotFile = IncrementalCompilationUtils.generateClasspathSnapshot(
                toolchain, session, libOutDir, snapshotDir
            )

            // --- Step 3: Initial IC compile of "app" module with "lib" as dependency ---
            val appSource = createKotlinSource(appWorkspace, "Consumer.kt", """
                package app
                import lib.Provider
                class Consumer { fun use() = Provider().value().toString().length }
            """)

            val appOp1 = createAppOperation(
                toolchain, appWorkspace, appOutDir, appIcDir, libOutDir,
                listOf(appSource), listOf(libSnapshotFile)
            )
            val appResult1 = CompilationTestUtils.runCompile(session, appOp1, policy)
            assertEquals(CompilationResult.COMPILATION_SUCCESS, appResult1, "Initial app compilation should succeed")

            val consumerBeforeBytes = CompilationTestUtils.readClassOutputBytes(appOutDir, "app/Consumer.class")
            assertTrue(consumerBeforeBytes != null, "Consumer class should exist after initial compilation")

            // --- Step 4: Non-ABI change in "lib" (body-only) and recompile lib ---
            libSource.writeText("""
                package lib
                class Provider { fun value(): Int = 200 }
            """.trimIndent())

            val libCompileOp2 = toolchain.jvm.jvmCompilationOperation(listOf(libSource), libOutDir) {
                compilerArguments.applyTestDefaults()
            }
            val libResult2 = CompilationTestUtils.runCompile(session, libCompileOp2, policy)
            assertCompilationSuccessful(libResult2)

            // --- Step 5: Re-snapshot "lib" and re-compile "app" incrementally ---
            val libSnapshotFile2 = IncrementalCompilationUtils.generateClasspathSnapshot(
                toolchain, session, libOutDir, snapshotDir
            )

            val appOp2 = createAppOperation(
                toolchain, appWorkspace, appOutDir, appIcDir, libOutDir,
                listOf(appSource), listOf(libSnapshotFile2)
            )
            val appResult2 = CompilationTestUtils.runCompile(session, appOp2, policy)
            assertEquals(CompilationResult.COMPILATION_SUCCESS, appResult2, "App recompilation should succeed")

            val consumerAfterBytes = CompilationTestUtils.readClassOutputBytes(appOutDir, "app/Consumer.class")
            assertTrue(consumerAfterBytes != null, "Consumer class should still exist after recompilation")

            // For a non-ABI change in the dependency, the consumer should ideally not be recompiled
            // (the snapshot diff should detect no ABI change). This assertion validates the snapshot-based IC.
            assertTrue(
                consumerBeforeBytes.contentEquals(consumerAfterBytes),
                "Consumer should not be recompiled when dependency has only a non-ABI change (detected via classpath snapshot)"
            )
        }
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