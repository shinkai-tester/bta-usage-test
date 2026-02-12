import framework.TestLogger
import framework.ToolchainManager
import framework.WorkspaceManager
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmCompilationOperation
import java.nio.file.Path

/**
 * Main facade for the Build Tools API (BTA) testing framework.
 * 
 * This class provides a simplified interface for testing Kotlin compilation operations.
 * It delegates to specialized managers for different concerns:
 * - TestLogger: for capturing and analyzing log messages
 * - WorkspaceManager: for workspace and source file management
 * - ToolchainManager: for toolchain loading and configuration
 *
 */
@OptIn(ExperimentalBuildToolsApi::class)
class BtaTestFacade {

    private val workspaceManager = WorkspaceManager()
    private val toolchainManager = ToolchainManager()

    /**
     * Creates a new framework.TestLogger instance for capturing compilation messages.
     * 
     * @return a new framework.TestLogger instance
     */
    fun createTestLogger(): TestLogger = TestLogger()

    /**
     * Creates a temporary workspace directory for testing.
     * 
     * @return Path to the created temporary directory
     */
    fun createTempWorkspace(): Path = workspaceManager.createTempWorkspace()

    /**
     * Creates a Kotlin source file in the specified workspace.
     * 
     * @param workspace The workspace directory where the file should be created
     * @param fileName The name of the Kotlin source file (should end with .kt)
     * @param content The content of the source file (will be trimmed of indentation)
     * @return Path to the created source file
     */
    fun createKotlinSource(workspace: Path, fileName: String, content: String): Path {
        return workspaceManager.createKotlinSource(workspace, fileName, content)
    }

    /**
     * Loads the Kotlin toolchain with an isolated classloader.
     * 
     * @return Configured KotlinToolchains instance
     */
    fun loadToolchain(): KotlinToolchains {
        return toolchainManager.loadToolchain()
    }

    /**
     * Loads the Kotlin toolchain using a custom classpath.
     * This is useful for testing with different compiler versions.
     * 
     * @param classpath The classpath string (paths separated by system path separator)
     * @return Configured KotlinToolchains instance
     */
    fun loadToolchainWithClasspath(classpath: String): KotlinToolchains {
        return toolchainManager.loadToolchainWithClasspath(classpath)
    }

    /**
     * Creates a new JVM compilation operation with standard test configuration.
     * Delegates to ToolchainManager for setup.
     *
     * @param toolchain The Kotlin toolchain to use
     * @param sources List of source files to compile
     * @param outDir Output directory for compiled classes
     * @return Configured JvmCompilationOperation
     */
    fun createJvmCompilationOperation(
        toolchain: KotlinToolchains,
        sources: List<Path>,
        outDir: Path
    ): JvmCompilationOperation {
        return toolchainManager.setupJvmCompilationOperation(toolchain, sources, outDir)
    }
    
    
    /**
     * Configures basic compiler arguments that are common across all compilation scenarios.
     * Sets NO_STDLIB, NO_REFLECT, CLASSPATH (with stdlib), and MODULE_NAME.
     * 
     * This is a convenience method that delegates to ToolchainManager.configureBasicCompilerArguments.
     * 
     * @param args The compiler arguments builder to configure
     * @param moduleName The module name to set for the compilation
     */
    fun configureBasicCompilerArguments(args: JvmCompilerArguments.Builder, moduleName: String) {
        toolchainManager.configureBasicCompilerArguments(args, moduleName)
    }
}