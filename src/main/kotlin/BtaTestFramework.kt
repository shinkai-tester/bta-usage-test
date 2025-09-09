import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinToolchain
import org.jetbrains.kotlin.buildtools.api.ExecutionPolicy
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
 * This design follows the Single Responsibility Principle and makes the framework
 * more maintainable and testable.
 */
@OptIn(ExperimentalBuildToolsApi::class)
class BtaTestFramework {

    private val workspaceManager = WorkspaceManager()
    private val toolchainManager = ToolchainManager()

    /**
     * Creates a new TestLogger instance for capturing compilation messages.
     * 
     * @return A new TestLogger instance
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
     * Creates a Java source file in the specified workspace.
     * 
     * @param workspace The workspace directory where the file should be created
     * @param fileName The name of the Java source file (should end with .java)
     * @param content The content of the source file (will be trimmed of indentation)
     * @return Path to the created source file
     */
    fun createJavaSource(workspace: Path, fileName: String, content: String): Path {
        return workspaceManager.createJavaSource(workspace, fileName, content)
    }

    /**
     * Loads the Kotlin toolchain with appropriate classloader based on daemon usage.
     * 
     * @param useDaemon If true, uses URLClassLoader and sets it as TCCL during initialization.
     *                  If false, uses system/application classloader.
     * @return Configured KotlinToolchain instance
     */
    fun loadToolchain(useDaemon: Boolean = false): KotlinToolchain {
        return toolchainManager.loadToolchain(useDaemon)
    }

    /**
     * Creates a DaemonExecutionPolicy in a daemon-friendly context.
     * 
     * @param toolchain The Kotlin toolchain to create the daemon execution policy for
     * @return Configured ExecutionPolicy for daemon usage
     */
    fun createDaemonExecutionPolicy(toolchain: KotlinToolchain): ExecutionPolicy {
        return toolchainManager.createDaemonExecutionPolicy(toolchain)
    }

    /**
     * Executes a block with a URLClassLoader set as the thread context classloader to support daemon mode.
     * 
     * @param block The code block to execute in daemon context
     * @return The result of executing the block
     */
    fun <T> withDaemonContext(block: () -> T): T {
        return toolchainManager.withDaemonContext(block)
    }

    /**
     * Configures minimal compilation settings for a JVM compilation operation.
     * 
     * @param operation The JVM compilation operation to configure
     */
    fun configureMinimalCompilation(operation: JvmCompilationOperation) {
        val workspace = workspaceManager.getLastWorkspace()
        toolchainManager.configureMinimalCompilation(operation, workspace)
    }
}