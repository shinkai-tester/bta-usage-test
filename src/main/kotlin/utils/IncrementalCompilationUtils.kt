package utils

import BtaTestFacade
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
        icBuilder[JvmSnapshotBasedIncrementalCompilationConfiguration.KEEP_IC_CACHES_IN_MEMORY] = true
        icBuilder[JvmSnapshotBasedIncrementalCompilationConfiguration.OUTPUT_DIRS] = setOf(outDir, icDir)
        icBuilder[JvmSnapshotBasedIncrementalCompilationConfiguration.MODULE_BUILD_DIR] = workspace
        icBuilder[JvmSnapshotBasedIncrementalCompilationConfiguration.ROOT_PROJECT_DIR] = workspace

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
     * @param framework The test framework instance for configuration
     * @param lookupTracker Optional lookup tracker to attach to the operation
     * @return Configured JvmCompilationOperation with incremental compilation
     */
    @JvmStatic
    fun createIncrementalJvmOperation(
        toolchain: KotlinToolchains,
        sources: List<Path>,
        outDir: Path,
        icDir: Path,
        workspace: Path,
        framework: BtaTestFacade,
        lookupTracker: CompilerLookupTracker? = null
    ): JvmCompilationOperation {
        val jvmToolchain = toolchain.getToolchain(JvmPlatformToolchain::class.java)
        val opBuilder = jvmToolchain.jvmCompilationOperationBuilder(sources, outDir)

        framework.configureBasicCompilerArguments(opBuilder.compilerArguments, "test-module")

        configureIcOnBuilder(opBuilder, icDir, outDir, workspace)

        if (lookupTracker != null) {
            opBuilder[JvmCompilationOperation.LOOKUP_TRACKER] = lookupTracker
        }

        return opBuilder.build()
    }
}
