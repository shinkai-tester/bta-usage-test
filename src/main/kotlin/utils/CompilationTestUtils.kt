package utils

import framework.TestLogger
import org.jetbrains.kotlin.buildtools.api.*
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmCompilationOperation
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readBytes

/**
 * Utilities for compilation testing, providing common helper methods for test scenarios.
 */
@OptIn(ExperimentalBuildToolsApi::class)
object CompilationTestUtils {

    /**
     * Executes a compilation operation with a specified execution policy and returns the result.
     * Creates a new build session for each call — suitable for single-shot compilations.
     *
     * @param toolchain The Kotlin toolchain to use for compilation
     * @param op The compilation operation to execute
     * @param executionPolicy The execution policy to use for compilation
     * @param logger Optional logger for compilation messages (defaults to framework.TestLogger)
     * @return CompilationResult indicating success or failure
     */
    @JvmStatic
    fun runCompile(
        toolchain: KotlinToolchains,
        op: JvmCompilationOperation,
        executionPolicy: ExecutionPolicy,
        logger: KotlinLogger? = null
    ): CompilationResult {
        val effectiveLogger = logger ?: TestLogger(false)
        return toolchain.createBuildSession().use { session ->
            session.executeOperation(op, executionPolicy, effectiveLogger)
        }
    }

    /**
     * Executes a compilation operation within an existing build session.
     * This is essential for incremental compilation where the session retains IC caches
     * between compilation rounds.
     *
     * @param session The existing build session to use
     * @param op The compilation operation to execute
     * @param executionPolicy The execution policy to use for compilation
     * @param logger Optional logger for compilation messages (defaults to framework.TestLogger)
     * @return CompilationResult indicating success or failure
     */
    @JvmStatic
    fun runCompile(
        session: KotlinToolchains.BuildSession,
        op: JvmCompilationOperation,
        executionPolicy: ExecutionPolicy,
        logger: KotlinLogger? = null
    ): CompilationResult {
        val effectiveLogger = logger ?: TestLogger(false)
        return session.executeOperation(op, executionPolicy, effectiveLogger)
    }
    
    /**
     * Reads the bytes of a class output file if it exists.
     *
     * @param outDir The output directory containing compiled classes
     * @param relPath The relative path to the class file within the output directory
     * @return ByteArray of the file contents, or null if the file does not exist
     */
    @JvmStatic
    fun readClassOutputBytes(outDir: Path, relPath: String): ByteArray? {
        val p = outDir.resolve(relPath)
        return if (p.exists()) p.readBytes() else null
    }
}