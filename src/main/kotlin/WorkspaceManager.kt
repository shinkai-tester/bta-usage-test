import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Manages workspace creation and source file operations for testing.
 * 
 * This class is responsible for creating temporary workspaces and managing
 * source files (both Kotlin and Java) within those workspaces. It follows
 * the Single Responsibility Principle by focusing solely on file system
 * operations related to test workspaces.
 */
class WorkspaceManager {
    private var lastWorkspace: Path? = null
    
    /**
     * Creates a temporary workspace directory for testing.
     * 
     * @return Path to the created temporary directory
     */
    fun createTempWorkspace(): Path {
        return Files.createTempDirectory("bta-test").also { 
            lastWorkspace = it 
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
     * Creates a Java source file in the specified workspace.
     * 
     * @param workspace The workspace directory where the file should be created
     * @param fileName The name of the Java source file (should end with .java)
     * @param content The content of the source file (will be trimmed of indentation)
     * @return Path to the created source file
     */
    fun createJavaSource(workspace: Path, fileName: String, content: String): Path {
        require(fileName.endsWith(".java")) { 
            "Java source file name must end with .java, got: $fileName" 
        }
        return createSourceFile(workspace, fileName, content)
    }
    
    /**
     * Gets the last workspace that was created or used.
     * 
     * @return Path to the last workspace, or null if no workspace has been created yet
     */
    fun getLastWorkspace(): Path? = lastWorkspace
    
    /**
     * Creates a source file with the given content in the specified workspace.
     * 
     * @param workspace The workspace directory where the file should be created
     * @param fileName The name of the source file
     * @param content The content of the source file (will be trimmed of indentation)
     * @return Path to the created source file
     */
    private fun createSourceFile(workspace: Path, fileName: String, content: String): Path {
        lastWorkspace = workspace
        val source = workspace.resolve(fileName)
        source.writeText(content.trimIndent())
        return source
    }
}