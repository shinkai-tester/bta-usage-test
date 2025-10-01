import org.jetbrains.kotlin.buildtools.api.*
import org.jetbrains.kotlin.buildtools.api.arguments.ExperimentalCompilerArgument
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.jetbrains.kotlin.buildtools.api.jvm.JvmSnapshotBasedIncrementalCompilationConfiguration
import org.jetbrains.kotlin.buildtools.api.jvm.JvmSnapshotBasedIncrementalCompilationOptions
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmCompilationOperation
import java.nio.file.Path

/**
 * Utilities for incremental compilation testing, providing helper methods for IC configuration.
 */
@OptIn(ExperimentalBuildToolsApi::class, ExperimentalCompilerArgument::class)
object IncrementalCompilationUtils {
    
    /**
     * Creates an incremental compilation configuration with the specified parameters.
     * 
     * @param workingDir The working directory for incremental compilation
     * @param changes The source changes to apply
     * @param shrunkSnapshot Path to the shrunk classpath snapshot file
     * @param dependencySnapshots List of dependency snapshot files for proper incremental compilation
     * @param configure Optional lambda to configure additional IC options
     * @return Configured JvmSnapshotBasedIncrementalCompilationConfiguration
     */
    @JvmStatic
    fun icConfig(
        workingDir: Path,
        changes: SourcesChanges,
        shrunkSnapshot: Path,
        dependencySnapshots: List<Path> = emptyList(),
        configure: (JvmSnapshotBasedIncrementalCompilationOptions) -> Unit = {}
    ): JvmSnapshotBasedIncrementalCompilationConfiguration {
        // Create IC options, set what we need, allow customization
        object : JvmCompilationOperation {
            override val compilerArguments: JvmCompilerArguments
                get() = throw UnsupportedOperationException()
            override fun createSnapshotBasedIcOptions(): JvmSnapshotBasedIncrementalCompilationOptions =
            // KotlinToolchain requires a real op to create options; we fake by throwing here.
                // We'll never call this path. The actual options are created on the real op below.
                throw UnsupportedOperationException()
            override fun <V> get(key: JvmCompilationOperation.Option<V>): V = throw UnsupportedOperationException()
            override fun <V> set(key: JvmCompilationOperation.Option<V>, value: V) = throw UnsupportedOperationException()
            override fun <V> get(key: BuildOperation.Option<V>): V = throw UnsupportedOperationException()
            override fun <V> set(key: BuildOperation.Option<V>, value: V) = throw UnsupportedOperationException()
        }
        // We cannot create options without a real operation, so we do it later on the actual op.
        // This helper just packages parameters. See usage below (we apply options from the real op).
        return JvmSnapshotBasedIncrementalCompilationConfiguration(
            workingDir,
            changes,
            dependenciesSnapshotFiles = dependencySnapshots,
            shrunkClasspathSnapshot = shrunkSnapshot,
            options = object : JvmSnapshotBasedIncrementalCompilationOptions {
                private val map = mutableMapOf<JvmSnapshotBasedIncrementalCompilationOptions.Option<*>, Any?>()
                @Suppress("UNCHECKED_CAST")
                override fun <V> get(key: JvmSnapshotBasedIncrementalCompilationOptions.Option<V>): V = map[key] as V
                override fun <V> set(key: JvmSnapshotBasedIncrementalCompilationOptions.Option<V>, value: V) { map[key] = value }
            }
        ).also { cfg ->
            configure(cfg.options)
        }
    }

    /**
     * Attaches incremental compilation configuration to a JVM compilation operation.
     * This method properly transfers options from the configuration to the operation's real IC options.
     * 
     * @param op The JVM compilation operation to configure
     * @param cfg The incremental compilation configuration to attach
     */
    @JvmStatic
    fun attachIcTo(op: JvmCompilationOperation, cfg: JvmSnapshotBasedIncrementalCompilationConfiguration) {
        // Replace the placeholder options with real ones obtained from the op
        val realOptions = op.createSnapshotBasedIcOptions()
        // Copy values from cfg.options (which we filled already) to realOptions
        val customOptionsAny = cfg.options as Any
        val hasKeyFun: (JvmSnapshotBasedIncrementalCompilationOptions.Option<*>) -> Boolean = if (
            customOptionsAny.javaClass.methods.any { it.name == "hasKey" }
        ) {
            val hasKeyMethod = customOptionsAny.javaClass.getMethod(
                "hasKey",
                JvmSnapshotBasedIncrementalCompilationOptions.Option::class.java
            )
            ({ opt: JvmSnapshotBasedIncrementalCompilationOptions.Option<*> ->
                hasKeyMethod.invoke(customOptionsAny, opt) as Boolean
            })
        } else {
            { _: JvmSnapshotBasedIncrementalCompilationOptions.Option<*> -> false }
        }

        fun <T> copyIfPresent(key: JvmSnapshotBasedIncrementalCompilationOptions.Option<T>) {
            if (hasKeyFun(key)) {
                // Generics are respected because key carries T
                realOptions[key] = cfg.options[key]
            }
        }

        copyIfPresent(JvmSnapshotBasedIncrementalCompilationOptions.ROOT_PROJECT_DIR)
        copyIfPresent(JvmSnapshotBasedIncrementalCompilationOptions.MODULE_BUILD_DIR)
        copyIfPresent(JvmSnapshotBasedIncrementalCompilationOptions.PRECISE_JAVA_TRACKING)
        copyIfPresent(JvmSnapshotBasedIncrementalCompilationOptions.BACKUP_CLASSES)
        copyIfPresent(JvmSnapshotBasedIncrementalCompilationOptions.KEEP_IC_CACHES_IN_MEMORY)
        copyIfPresent(JvmSnapshotBasedIncrementalCompilationOptions.FORCE_RECOMPILATION)
        copyIfPresent(JvmSnapshotBasedIncrementalCompilationOptions.OUTPUT_DIRS)
        copyIfPresent(JvmSnapshotBasedIncrementalCompilationOptions.ASSURED_NO_CLASSPATH_SNAPSHOT_CHANGES)
        copyIfPresent(JvmSnapshotBasedIncrementalCompilationOptions.USE_FIR_RUNNER)

        val realCfg = JvmSnapshotBasedIncrementalCompilationConfiguration(
            cfg.workingDirectory,
            cfg.sourcesChanges,
            cfg.dependenciesSnapshotFiles,
            cfg.shrunkClasspathSnapshot,
            realOptions
        )
        op[JvmCompilationOperation.INCREMENTAL_COMPILATION] = realCfg
    }
}