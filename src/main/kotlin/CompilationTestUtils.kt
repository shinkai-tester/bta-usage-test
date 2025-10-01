import org.jetbrains.kotlin.buildtools.api.*
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmCompilationOperation
import java.nio.file.Path

/**
 * Utilities for compilation testing, providing common helper methods for test scenarios.
 */
@OptIn(ExperimentalBuildToolsApi::class)
object CompilationTestUtils {
    
    /**
     * Represents the state of a compiled output file.
     * 
     * @param fileExists Whether the output file exists
     * @param lastModified The last modification timestamp of the file, or null if the file doesn't exist
     */
    data class OutputState(val fileExists: Boolean, val lastModified: Long?)
    
    /**
     * Gets the current state of a class output file.
     * 
     * @param outDir The output directory containing compiled classes
     * @param relPath The relative path to the class file within the output directory
     * @return OutputState representing the current state of the file
     */
    @JvmStatic
    fun classOutputState(outDir: Path, relPath: String): OutputState {
        val f = outDir.resolve(relPath).toFile()
        return OutputState(f.exists(), if (f.exists()) f.lastModified() else null)
    }
    
    /**
     * Creates a new JVM compilation operation with basic configuration.
     * Uses the BtaTestFramework for minimal compilation setup.
     * 
     * @param toolchain The Kotlin toolchain to use for compilation
     * @param sources List of source files to compile
     * @param outDir Output directory for compiled classes
     * @param framework The test framework instance for configuration
     * @return Configured JvmCompilationOperation
     */
    @JvmStatic
    fun newJvmOp(
        toolchain: KotlinToolchains,
        sources: List<Path>,
        outDir: Path,
        framework: BtaTestFramework
    ): JvmCompilationOperation {
        val op = toolchain.getToolchain<JvmPlatformToolchain>().createJvmCompilationOperation(sources, outDir)
        framework.configureMinimalCompilation(op)
        return op
    }
    
    /**
     * Executes a compilation operation and returns the result.
     * 
     * @param toolchain The Kotlin toolchain to use for compilation
     * @param op The compilation operation to execute
     * @param logger Optional logger for compilation messages (defaults to null)
     * @return CompilationResult indicating success or failure
     */
    @JvmStatic
    fun runCompile(
        toolchain: KotlinToolchains,
        op: JvmCompilationOperation,
        logger: KotlinLogger? = null
    ): CompilationResult {
        val effectiveLogger = logger ?: TestLogger(false)
        return toolchain.createBuildSession().use { session ->
            session.executeOperation(op, toolchain.createInProcessExecutionPolicy(), effectiveLogger)
        }
    }
    
    /**
     * Executes a compilation operation with a custom execution policy and returns the result.
     * 
     * @param toolchain The Kotlin toolchain to use for compilation
     * @param op The compilation operation to execute
     * @param executionPolicy The execution policy to use for compilation
     * @param logger Optional logger for compilation messages (defaults to null)
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
     * Reads the bytes of a class output file if it exists.
     * @return ByteArray of the file contents, or null if file does not exist
     */
    @JvmStatic
    fun readClassOutputBytes(outDir: Path, relPath: String): ByteArray? {
        val f = outDir.resolve(relPath).toFile()
        return if (f.exists()) f.readBytes() else null
    }


}