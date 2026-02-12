package framework

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * Manages workspace creation and source file operations for testing.
 *
 * This class is responsible for creating temporary workspaces and managing
 * Kotlin source files within those workspaces.
 */
class WorkspaceManager {
    companion object {
        private val createdWorkspaces: ConcurrentLinkedQueue<Path> = ConcurrentLinkedQueue()

        @Volatile
        private var shutdownHookInstalled: Boolean = false

        private fun ensureShutdownHookInstalled() {
            if (!shutdownHookInstalled) {
                synchronized(this) {
                    if (!shutdownHookInstalled) {
                        Runtime.getRuntime().addShutdownHook(Thread {
                            createdWorkspaces.forEach { path ->
                                try {
                                    deleteWorkspaceRecursively(path)
                                } catch (_: Throwable) {
                                }
                            }
                        })
                        shutdownHookInstalled = true
                    }
                }
            }
        }

        private fun deleteWorkspaceRecursively(path: Path) {
            try {
                @OptIn(ExperimentalPathApi::class)
                if (path.exists()) path.deleteRecursively()
            } catch (_: Throwable) {
                try {
                    if (Files.exists(path)) {
                        Files.walk(path)
                            .sorted(Comparator.reverseOrder())
                            .forEach { p ->
                                try {
                                    Files.deleteIfExists(p)
                                } catch (_: IOException) {
                                }
                            }
                    }
                } catch (_: Throwable) {
                }
            }
        }
    }

    /**
     * Creates a temporary workspace directory for testing.
     *
     * @return Path to the created temporary directory
     */
    fun createTempWorkspace(): Path {
        ensureShutdownHookInstalled()
        return Files.createTempDirectory("bta-test").also { dir ->
            createdWorkspaces.add(dir)
        }
    }

    /**
     * Creates a Kotlin source file in the specified workspace.
     *
     * @param workspace The workspace directory where the file should be created
     * @param fileName The name of the Kotlin source file (should end with .kt)
     * @param content The content of the source file (will be trimmed of indentation)
     * @return Path to the created source file
     */
    fun createKotlinSource(workspace: Path, fileName: String, content: String): Path {
        require(fileName.endsWith(".kt")) {
            "Kotlin source file name must end with .kt, got: $fileName"
        }
        return createSourceFile(workspace, fileName, content)
    }

    /**
     * Creates a source file with the given content in the specified workspace.
     *
     * @param workspace The workspace directory where the file should be created
     * @param fileName The name of the source file
     * @param content The content of the source file (will be trimmed of indentation)
     * @return Path to the created source file
     */
    private fun createSourceFile(workspace: Path, fileName: String, content: String): Path {
        val source = workspace.resolve(fileName)
        source.writeText(content.trimIndent())
        return source
    }
}