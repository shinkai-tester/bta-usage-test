import org.jetbrains.kotlin.buildtools.api.*
import org.jetbrains.kotlin.buildtools.api.jvm.JvmSnapshotBasedIncrementalCompilationOptions
import java.nio.file.Path
import kotlin.io.path.createDirectories

/**
 * Specialized test scenario for Java-Kotlin interop incremental compilation tests.
 * Handles the complexity of separate Java compilation followed by Kotlin compilation with Java on classpath.
 */
@OptIn(ExperimentalBuildToolsApi::class)
class JavaInteropTestScenario(private val framework: BtaTestFramework) {
    
    private val root = framework.createTempWorkspace()
    private val javaWs = root.resolve("java").createDirectories()
    private val kotlinWs = root.resolve("kotlin").createDirectories()
    private val javaOut = javaWs.resolve("out").createDirectories()
    private val kotlinOut = kotlinWs.resolve("out").createDirectories()
    private val kotlinIc = kotlinWs.resolve("ic").createDirectories()
    private val toolchain = framework.loadToolchain(useDaemon = true)
    
    private var javaSource: Path? = null
    private var kotlinSource: Path? = null
    
    /**
     * Sets up the Java source file for the test scenario.
     */
    fun withJavaSource(fileName: String, content: String): JavaInteropTestScenario {
        javaSource = framework.createJavaSource(javaWs, fileName, content)
        return this
    }
    
    /**
     * Sets up the Kotlin source file for the test scenario.
     */
    fun withKotlinSource(fileName: String, content: String): JavaInteropTestScenario {
        kotlinSource = framework.createKotlinSource(kotlinWs, fileName, content)
        return this
    }
    
    /**
     * Compiles Java source using javac and then compiles Kotlin with Java on classpath.
     */
    fun compileInitial(preciseJavaTracking: Boolean): JavaInteropCompilationResult {
        requireNotNull(javaSource) { "Java source must be set before compilation" }
        requireNotNull(kotlinSource) { "Kotlin source must be set before compilation" }
        
        // Compile Java first
        compileJava()
        
        // Compile Kotlin with Java on classpath
        val kotlinOp = CompilationTestUtils.newJvmOp(toolchain, listOf(kotlinSource!!), kotlinOut, framework)
        addJavaToClasspath(kotlinOp)
        
        val icConfig = IncrementalCompilationUtils.icConfig(
            kotlinIc, 
            SourcesChanges.ToBeCalculated, 
            kotlinIc.resolve("shrunk.bin")
        ) {
            it[JvmSnapshotBasedIncrementalCompilationOptions.PRECISE_JAVA_TRACKING] = preciseJavaTracking
            it[JvmSnapshotBasedIncrementalCompilationOptions.KEEP_IC_CACHES_IN_MEMORY] = true
            it[JvmSnapshotBasedIncrementalCompilationOptions.OUTPUT_DIRS] = setOf(kotlinOut, kotlinIc)
            it[JvmSnapshotBasedIncrementalCompilationOptions.MODULE_BUILD_DIR] = kotlinWs
            it[JvmSnapshotBasedIncrementalCompilationOptions.ROOT_PROJECT_DIR] = root
        }
        IncrementalCompilationUtils.attachIcTo(kotlinOp, icConfig)
        
        val result = CompilationTestUtils.runCompile(
            toolchain,
            kotlinOp,
            framework.createDaemonExecutionPolicy(toolchain)
        )
        return JavaInteropCompilationResult(result, preciseJavaTracking)
    }
    
    /**
     * Updates Java source with new content and recompiles both Java and Kotlin.
     */
    fun updateJavaAndRecompile(newJavaContent: String, preciseJavaTracking: Boolean): JavaInteropCompilationResult {
        requireNotNull(javaSource) { "Java source must be set before update" }
        requireNotNull(kotlinSource) { "Kotlin source must be set before compilation" }
        
        // Update and recompile Java
        javaSource!!.toFile().writeText(newJavaContent.trimIndent())
        compileJava()
        
        // Recompile Kotlin with updated Java on classpath
        val kotlinOp = CompilationTestUtils.newJvmOp(toolchain, listOf(kotlinSource!!), kotlinOut, framework)
        addJavaToClasspath(kotlinOp)
        
        val icConfig = IncrementalCompilationUtils.icConfig(
            kotlinIc,
            SourcesChanges.Known(emptyList(), emptyList()), // No Kotlin sources changed
            kotlinIc.resolve("shrunk.bin")
        ) {
            it[JvmSnapshotBasedIncrementalCompilationOptions.PRECISE_JAVA_TRACKING] = preciseJavaTracking
            it[JvmSnapshotBasedIncrementalCompilationOptions.KEEP_IC_CACHES_IN_MEMORY] = true
            it[JvmSnapshotBasedIncrementalCompilationOptions.OUTPUT_DIRS] = setOf(kotlinOut, kotlinIc)
            it[JvmSnapshotBasedIncrementalCompilationOptions.MODULE_BUILD_DIR] = kotlinWs
            it[JvmSnapshotBasedIncrementalCompilationOptions.ROOT_PROJECT_DIR] = root
        }
        IncrementalCompilationUtils.attachIcTo(kotlinOp, icConfig)
        
        val result = CompilationTestUtils.runCompile(
            toolchain,
            kotlinOp,
            framework.createDaemonExecutionPolicy(toolchain)
        )
        return JavaInteropCompilationResult(result, preciseJavaTracking)
    }
    
    /**
     * Gets the output state of a Kotlin class file.
     */
    fun getKotlinClassState(relativeClassPath: String): CompilationTestUtils.OutputState {
        return CompilationTestUtils.classOutputState(kotlinOut, relativeClassPath)
    }
    
    /**
     * Reads the bytes of a Kotlin class file.
     */
    fun readKotlinClassBytes(relativeClassPath: String): ByteArray? {
        return CompilationTestUtils.readClassOutputBytes(kotlinOut, relativeClassPath)
    }
    
    /**
     * Compiles Java source using javac.
     */
    private fun compileJava() {
        val javac = javax.tools.ToolProvider.getSystemJavaCompiler()
            ?: throw IllegalStateException("javac is required but not available")
        
        javac.getStandardFileManager(null, null, null).use<javax.tools.StandardJavaFileManager, Unit> { fm ->
            val units = fm.getJavaFileObjectsFromFiles(listOf(javaSource!!.toFile()))
            val success = javac.getTask(null, fm, null, listOf("-d", javaOut.toString()), null, units).call()
            if (!success) {
                throw RuntimeException("Java compilation failed")
            }
        }
    }
    
    /**
     * Adds Java output directory to Kotlin compilation classpath.
     */
    private fun addJavaToClasspath(kotlinOp: org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmCompilationOperation) {
        val args = kotlinOp.compilerArguments
        val currentCp = args[org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments.CLASSPATH]
        val newCp = if (currentCp.isNullOrEmpty()) {
            javaOut.toString()
        } else {
            currentCp + java.io.File.pathSeparator + javaOut.toString()
        }
        args[org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments.CLASSPATH] = newCp
    }
}

/**
 * Result of a Java-Kotlin interop compilation test.
 */
data class JavaInteropCompilationResult(
    val compilationResult: CompilationResult,
    val preciseJavaTracking: Boolean
)