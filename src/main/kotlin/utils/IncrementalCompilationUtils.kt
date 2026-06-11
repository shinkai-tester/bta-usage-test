package utils

import framework.applyBasicCompilerArguments
import org.jetbrains.kotlin.buildtools.api.*
import org.jetbrains.kotlin.buildtools.api.jvm.ClassSnapshotGranularity
import org.jetbrains.kotlin.buildtools.api.jvm.ClasspathEntrySnapshot
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain.Companion.jvm
import org.jetbrains.kotlin.buildtools.api.jvm.classpathSnapshottingOperation
import org.jetbrains.kotlin.buildtools.api.jvm.jvmCompilationOperation
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmClasspathSnapshottingOperation
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmCompilationOperation
import org.jetbrains.kotlin.buildtools.api.trackers.CompilerLookupTracker
import java.nio.file.Path
import kotlin.io.path.createDirectories

/**
 * Utilities for incremental compilation testing, providing helper methods for IC configuration.
 * Built on the `toolchain.jvm` extension and the `jvmCompilationOperation { ... }` /
 * `classpathSnapshottingOperation { ... }` convenience functions.
 */
@OptIn(ExperimentalBuildToolsApi::class)
object IncrementalCompilationUtils {

    /**
     * Configures incremental compilation on a JvmCompilationOperation.Builder with standard test settings.
     *
     * Sets up IC with:
     * - KEEP_IC_CACHES_IN_MEMORY = true
     * - OUTPUT_DIRS = {outDir, icDir}
     * - MODULE_BUILD_DIR = workspace
     * - ROOT_PROJECT_DIR = workspace
     *
     * @param opBuilder The JVM compilation operation builder to configure
     * @param icDir The directory for incremental compilation caches
     * @param outDir The output directory for compiled classes
     * @param workspace The workspace/module directory
     * @param changes The source changes to apply (defaults to ToBeCalculated)
     * @param dependencySnapshots List of dependency snapshot files (defaults to empty)
     */
    @JvmStatic
    fun configureIcOnBuilder(
        opBuilder: JvmCompilationOperation.Builder,
        icDir: Path,
        outDir: Path,
        workspace: Path,
        changes: SourcesChanges = SourcesChanges.ToBeCalculated,
        dependencySnapshots: List<Path> = emptyList()
    ) {
        val icBuilder = opBuilder.snapshotBasedIcConfigurationBuilder(
            icDir,
            changes,
            dependencySnapshots
        )

        // Configure standard IC settings for tests
        icBuilder[BaseIncrementalCompilationConfiguration.KEEP_IC_CACHES_IN_MEMORY] = true
        icBuilder[BaseIncrementalCompilationConfiguration.OUTPUT_DIRS] = setOf(outDir, icDir)
        icBuilder[BaseIncrementalCompilationConfiguration.MODULE_BUILD_DIR] = workspace
        icBuilder[BaseIncrementalCompilationConfiguration.ROOT_PROJECT_DIR] = workspace

        opBuilder[JvmCompilationOperation.INCREMENTAL_COMPILATION] = icBuilder.build()
    }

    /**
     * Creates a new JVM compilation operation with incremental compilation configured.
     * Optionally attaches a lookup tracker.
     *
     * @param toolchain The Kotlin toolchain to use for compilation
     * @param sources List of source files to compile
     * @param outDir Output directory for compiled classes
     * @param icDir Directory for incremental compilation caches
     * @param workspace The workspace/module directory
     * @param lookupTracker Optional lookup tracker to attach to the operation
     * @param dependencySnapshots List of dependency snapshot file paths for classpath change detection
     * @return Configured JvmCompilationOperation with incremental compilation
     */
    @JvmStatic
    fun createIncrementalJvmOperation(
        toolchain: KotlinToolchains,
        sources: List<Path>,
        outDir: Path,
        icDir: Path,
        workspace: Path,
        lookupTracker: CompilerLookupTracker? = null,
        dependencySnapshots: List<Path> = emptyList()
    ): JvmCompilationOperation = toolchain.jvm.jvmCompilationOperation(sources, outDir) {
        compilerArguments.applyBasicCompilerArguments("test-module")

        configureIcOnBuilder(this, icDir, outDir, workspace, dependencySnapshots = dependencySnapshots)

        if (lookupTracker != null) {
            this[BaseCompilationOperation.LOOKUP_TRACKER] = lookupTracker
        }
    }

    /**
     * Generates a classpath snapshot for a given classpath entry (e.g., a compiled module's output directory
     * or a JAR dependency) and saves it to disk.
     *
     * Uses [JvmClasspathSnapshottingOperation] to compute the snapshot, then persists it via
     * [ClasspathEntrySnapshot.saveSnapshot].
     *
     * @param toolchain The Kotlin toolchain to use
     * @param session The build session for executing the snapshotting operation
     * @param classpathEntry Path to the classpath entry to snapshot (directory or JAR)
     * @param snapshotOutputDir Directory where the snapshot file will be saved
     * @param granularity Snapshot granularity (defaults to CLASS_MEMBER_LEVEL for fine-grained tracking)
     * @param parseInlinedLocalClasses Whether to enable extended snapshotting for inline methods
     * @return Path to the saved snapshot file
     */
    @JvmStatic
    fun generateClasspathSnapshot(
        toolchain: KotlinToolchains,
        session: KotlinToolchains.BuildSession,
        classpathEntry: Path,
        snapshotOutputDir: Path,
        granularity: ClassSnapshotGranularity = ClassSnapshotGranularity.CLASS_MEMBER_LEVEL,
        parseInlinedLocalClasses: Boolean = false
    ): Path {
        val snapshotOperation = toolchain.jvm.classpathSnapshottingOperation(classpathEntry) {
            this[JvmClasspathSnapshottingOperation.GRANULARITY] = granularity
            this[JvmClasspathSnapshottingOperation.PARSE_INLINED_LOCAL_CLASSES] = parseInlinedLocalClasses
        }

        val snapshotResult = session.executeOperation(snapshotOperation)

        snapshotOutputDir.createDirectories()
        // The snapshot file name must be unique per snapshot content: the compiler's
        // CachedClasspathSnapshotSerializer caches deserialized snapshots by file path, so reusing a
        // fixed name would make a later re-snapshot of the same dependency
        // read the stale, previously cached snapshot and miss ABI changes.
        val snapshotFile = snapshotOutputDir.resolve("dep-${snapshotResult.hashCode()}.snapshot")
        snapshotResult.saveSnapshot(snapshotFile)

        return snapshotFile
    }
}