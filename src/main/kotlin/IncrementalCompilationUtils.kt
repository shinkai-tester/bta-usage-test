import org.jetbrains.kotlin.buildtools.api.*
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain
import org.jetbrains.kotlin.buildtools.api.jvm.JvmSnapshotBasedIncrementalCompilationConfiguration
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmCompilationOperation
import org.jetbrains.kotlin.buildtools.api.trackers.CompilerLookupTracker
import java.nio.file.Path

/**
 * Utilities for incremental compilation testing, providing helper methods for IC configuration.
 * Uses the new builder pattern API to avoid deprecated interfaces.
 */
@OptIn(ExperimentalBuildToolsApi::class)
object IncrementalCompilationUtils {
    
    /**
     * Configures incremental compilation on a JvmCompilationOperation.Builder with inline configuration.
     * 
     * @param opBuilder The JVM compilation operation builder to configure
     * @param workingDir The working directory for incremental compilation
     * @param changes The source changes to apply
     * @param shrunkSnapshot Path to the shrunk classpath snapshot file
     * @param dependencySnapshots List of dependency snapshot files
     * @param configure Lambda to configure IC options on the builder
     */
    @JvmStatic
    fun configureIcOnBuilder(
        opBuilder: JvmCompilationOperation.Builder,
        workingDir: Path,
        changes: SourcesChanges,
        shrunkSnapshot: Path,
        dependencySnapshots: List<Path> = emptyList(),
        configure: (JvmSnapshotBasedIncrementalCompilationConfiguration.Builder) -> Unit = {}
    ) {
        val icBuilder = opBuilder.snapshotBasedIcConfigurationBuilder(
            workingDir,
            changes,
            dependencySnapshots,
            shrunkSnapshot
        )
        configure(icBuilder)
        val icConfig = icBuilder.build()
        opBuilder[JvmCompilationOperation.INCREMENTAL_COMPILATION] = icConfig
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
     * @param framework The test framework instance for configuration
     * @param lookupTracker Optional lookup tracker to attach to the operation
     * @return Configured JvmCompilationOperation with incremental compilation
     */
    @JvmStatic
    fun newIncrementalJvmOp(
        toolchain: KotlinToolchains,
        sources: List<Path>,
        outDir: Path,
        icDir: Path,
        workspace: Path,
        framework: BtaTestFramework,
        lookupTracker: CompilerLookupTracker? = null
    ): JvmCompilationOperation {
        val jvmToolchain = toolchain.getToolchain(JvmPlatformToolchain::class.java)
        val opBuilder = jvmToolchain.jvmCompilationOperationBuilder(sources, outDir)

        // Configure basic compiler arguments
        framework.configureBasicCompilerArguments(opBuilder.compilerArguments, "test-module")

        // Configure incremental compilation
        configureIcOnBuilder(
            opBuilder,
            icDir,
            SourcesChanges.ToBeCalculated,
            icDir.resolve("shrunk.bin"),
            emptyList()
        ) { icBuilder ->
            icBuilder[JvmSnapshotBasedIncrementalCompilationConfiguration.ASSURED_NO_CLASSPATH_SNAPSHOT_CHANGES] = true
            icBuilder[JvmSnapshotBasedIncrementalCompilationConfiguration.KEEP_IC_CACHES_IN_MEMORY] = true
            icBuilder[JvmSnapshotBasedIncrementalCompilationConfiguration.OUTPUT_DIRS] = setOf(outDir, icDir)
            icBuilder[JvmSnapshotBasedIncrementalCompilationConfiguration.PRECISE_JAVA_TRACKING] = true
            icBuilder[JvmSnapshotBasedIncrementalCompilationConfiguration.MODULE_BUILD_DIR] = workspace
            icBuilder[JvmSnapshotBasedIncrementalCompilationConfiguration.ROOT_PROJECT_DIR] = workspace
        }

        // Attach the lookup tracker if provided
        if (lookupTracker != null) {
            opBuilder[JvmCompilationOperation.LOOKUP_TRACKER] = lookupTracker
        }

        return opBuilder.build()
    }
}
