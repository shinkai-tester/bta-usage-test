import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Utilities for setting up and managing Kotlin compilation tasks.
 * 
 * This class provides helper methods for creating test sources, logging compilation
 * information, and reporting compilation results.
 */
object CompilationUtils {
    
    /**
     * Creates a temporary test source file with a Kotlin program.
     * 
     * @param tmpDir The temporary directory where the source file should be created
     * @param fileName The name of the source file to create (default: "Hello.kt")
     * @param sourceContent The Kotlin source code content (default: a simple Hello World program)
     * @return The path to the created source file
     */
    fun createTestSource(
        tmpDir: Path, 
        fileName: String = "Hello.kt",
        sourceContent: String = getDefaultTestSource()
    ): Path {
        val src = tmpDir.resolve(fileName)
        src.writeText(sourceContent)
        return src
    }
    
    /**
     * Provides the default test source code for compilation testing.
     * 
     * @return A simple Kotlin program with a class and main function
     */
    private fun getDefaultTestSource(): String = """
        package sample
        
        class Greeter {
            fun hello(): String = "Hello, World!"
        }
        
        fun main() {
            println(Greeter().hello())
        }
        """.trimIndent()
    
    /**
     * Creates the necessary temporary directories for compilation.
     * 
     * @param prefix The prefix for the temporary directory name (default: "bta-usage-KT-78196")
     * @return A pair of (temporary directory, output directory)
     */
    fun createTempDirectories(prefix: String = "bta-usage-KT-78196"): Pair<Path, Path> {
        val tmpDir = Files.createTempDirectory(prefix)
        val outDir = tmpDir.resolve("out").createDirectories()
        return tmpDir to outDir
    }
    
    /**
     * Logs the classpath being used for compilation in a readable format.
     * 
     * @param compilerArguments The compiler arguments containing the classpath
     */
    fun logClasspath(compilerArguments: JvmCompilerArguments) {
        val cp = compilerArguments[JvmCompilerArguments.CLASSPATH].orEmpty()
        if (cp.isNotBlank()) {
            println("Classpath used:")
            cp.split(File.pathSeparator).filter { it.isNotBlank() }.forEach { println(it) }
        }
    }
    
    /**
     * Reports the results of a compilation operation.
     * 
     * @param result The compilation result
     * @param outDir The output directory where compiled files are located
     */
    fun reportCompilationResults(result: Any, outDir: Path) {
        println("Compilation result: $result")
        println("Output directory: $outDir")
        
        // List produced files
        val produced = outDir.toFile().walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(outDir.toFile()).path }
            .toList()
        println("Produced files: ${produced.joinToString(", ")}")
    }
}