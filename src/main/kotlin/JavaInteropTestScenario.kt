import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.SourcesChanges
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain
import org.jetbrains.kotlin.buildtools.api.jvm.JvmSnapshotBasedIncrementalCompilationConfiguration
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Specialized test scenario for Java-Kotlin interop incremental compilation tests.
 * Handles the complexity of separate Java compilation followed by Kotlin compilation with Java on classpath.
 * Uses the new builder pattern API to avoid deprecated interfaces.
 */
@OptIn(ExperimentalBuildToolsApi::class)
class JavaInteropTestScenario(private val framework: BtaTestFramework) {
    
    private val root = framework.createTempWorkspace()
    private val javaWs = root.resolve("java").createDirectories()
    private val kotlinWs = root.resolve("kotlin").createDirectories()
    private val javaOut = javaWs.resolve("out").createDirectories()
    private val kotlinOut = kotlinWs.resolve("out").createDirectories()
    private val kotlinIc = kotlinWs.resolve("ic").createDirectories()
    private val toolchain = framework.loadToolchain()
    
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
        
        // Compile Kotlin with Java on classpath using builder pattern
        val jvmToolchain = toolchain.getToolchain(JvmPlatformToolchain::class.java)
        val opBuilder = jvmToolchain.jvmCompilationOperationBuilder(listOf(kotlinSource!!), kotlinOut)
        
        // Configure compiler arguments using shared method
        framework.configureBasicCompilerArguments(opBuilder.compilerArguments, "kotlin-interop-module")
        addJavaToClasspath(opBuilder.compilerArguments)
        
        // Configure incremental compilation using shared utility
        IncrementalCompilationUtils.configureIcOnBuilder(
            opBuilder,
            kotlinIc,
            SourcesChanges.ToBeCalculated,
            kotlinIc.resolve("shrunk.bin")
        ) { icBuilder ->
            icBuilder[JvmSnapshotBasedIncrementalCompilationConfiguration.PRECISE_JAVA_TRACKING] = preciseJavaTracking
            icBuilder[JvmSnapshotBasedIncrementalCompilationConfiguration.KEEP_IC_CACHES_IN_MEMORY] = true
            icBuilder[JvmSnapshotBasedIncrementalCompilationConfiguration.OUTPUT_DIRS] = setOf(kotlinOut, kotlinIc)
            icBuilder[JvmSnapshotBasedIncrementalCompilationConfiguration.MODULE_BUILD_DIR] = kotlinWs
            icBuilder[JvmSnapshotBasedIncrementalCompilationConfiguration.ROOT_PROJECT_DIR] = root
        }
        
        val result = CompilationTestUtils.runCompile(
            toolchain,
            opBuilder.build(),
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
        javaSource!!.writeText(newJavaContent.trimIndent())
        compileJava()
        
        // Recompile Kotlin with updated Java on classpath using builder pattern
        val jvmToolchain = toolchain.getToolchain(JvmPlatformToolchain::class.java)
        val opBuilder = jvmToolchain.jvmCompilationOperationBuilder(listOf(kotlinSource!!), kotlinOut)
        
        // Configure compiler arguments using shared method
        framework.configureBasicCompilerArguments(opBuilder.compilerArguments, "kotlin-interop-module")
        addJavaToClasspath(opBuilder.compilerArguments)
        
        // Configure incremental compilation - no Kotlin sources changed
        IncrementalCompilationUtils.configureIcOnBuilder(
            opBuilder,
            kotlinIc,
            SourcesChanges.Known(emptyList(), emptyList()),
            kotlinIc.resolve("shrunk.bin")
        ) { icBuilder ->
            icBuilder[JvmSnapshotBasedIncrementalCompilationConfiguration.PRECISE_JAVA_TRACKING] = preciseJavaTracking
            icBuilder[JvmSnapshotBasedIncrementalCompilationConfiguration.KEEP_IC_CACHES_IN_MEMORY] = true
            icBuilder[JvmSnapshotBasedIncrementalCompilationConfiguration.OUTPUT_DIRS] = setOf(kotlinOut, kotlinIc)
            icBuilder[JvmSnapshotBasedIncrementalCompilationConfiguration.MODULE_BUILD_DIR] = kotlinWs
            icBuilder[JvmSnapshotBasedIncrementalCompilationConfiguration.ROOT_PROJECT_DIR] = root
        }
        
        val result = CompilationTestUtils.runCompile(
            toolchain,
            opBuilder.build(),
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
    private fun addJavaToClasspath(args: JvmCompilerArguments.Builder) {
        val currentCp = args[JvmCompilerArguments.CLASSPATH]
        val newCp = if (currentCp.isNullOrEmpty()) {
            javaOut.toString()
        } else {
            currentCp + java.io.File.pathSeparator + javaOut.toString()
        }
        args[JvmCompilerArguments.CLASSPATH] = newCp
    }
}

/**
 * Result of a Java-Kotlin interop compilation test.
 */
data class JavaInteropCompilationResult(
    val compilationResult: CompilationResult,
    val preciseJavaTracking: Boolean
)
