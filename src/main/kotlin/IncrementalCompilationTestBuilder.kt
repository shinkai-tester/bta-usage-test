import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.SourcesChanges
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain
import org.jetbrains.kotlin.buildtools.api.jvm.JvmSnapshotBasedIncrementalCompilationConfiguration
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * Builder for creating and managing incremental compilation test scenarios.
 * Provides a fluent API to set up multi-module compilation tests with proper separation of concerns.
 */

@OptIn(ExperimentalBuildToolsApi::class)
class IncrementalCompilationTestBuilder(private val framework: BtaTestFramework, private val logger: TestLogger? = null) {
    
    private val modules = mutableMapOf<String, ModuleSetup>()
    private val toolchain = framework.loadToolchain(useDaemon = true)
    private val root = framework.createTempWorkspace()
    
    /**
     * Represents a module in the test scenario with its workspace, output, and IC directories.
     */
    data class ModuleSetup(
        val name: String,
        val workspace: Path,
        val outputDir: Path,
        val icDir: Path,
        val sources: MutableList<Path> = mutableListOf(),
        val dependencies: MutableList<String> = mutableListOf()
    )
    
    /**
     * Creates a new module with the given name.
     */
    fun module(name: String): ModuleBuilder {
        val workspace = root.resolve(name).createDirectories()
        val outputDir = workspace.resolve("out").createDirectories()
        val icDir = workspace.resolve("ic").createDirectories()
        
        val module = ModuleSetup(name, workspace, outputDir, icDir)
        modules[name] = module
        
        return ModuleBuilder(module)
    }
    
    /**
     * Builder for configuring a specific module.
     */
    inner class ModuleBuilder(private val module: ModuleSetup) {
        
        /**
         * Adds a Kotlin source file to this module.
         */
        fun kotlinSource(fileName: String, content: String): ModuleBuilder {
            val source = framework.createKotlinSource(module.workspace, fileName, content)
            module.sources.add(source)
            return this
        }
        
        /**
         * Declares that this module depends on another module.
         */
        fun dependsOn(moduleName: String): ModuleBuilder {
            module.dependencies.add(moduleName)
            return this
        }
        
        /**
         * Returns to the .main builder to configure other modules.
         */
        fun and(): IncrementalCompilationTestBuilder = this@IncrementalCompilationTestBuilder
    }
    
    /**
     * Compiles all modules in dependency order with incremental compilation enabled.
     */
    fun compileAll(sourcesChanges: SourcesChanges = SourcesChanges.ToBeCalculated, changedModuleName: String? = null): CompilationResults {
        val results = mutableMapOf<String, CompilationResult>()
        val compilationOrder = topologicalSort()
        
        for (moduleName in compilationOrder) {
            val module = modules[moduleName]!!
            // Only pass the specific sourcesChanges to the module that was actually changed
            // Other modules should use ToBeCalculated to let incremental compilation determine what to recompile
            val moduleSourcesChanges = if (moduleName == changedModuleName) sourcesChanges else SourcesChanges.ToBeCalculated
            val result = compileModule(module, moduleSourcesChanges)
            results[moduleName] = result
        }
        
        return CompilationResults(results)
    }
    
    /**
     * Compiles a specific module with its dependencies on the classpath.
     * Uses IncrementalCompilationUtils for IC configuration to avoid code duplication.
     */
    private fun compileModule(module: ModuleSetup, sourcesChanges: SourcesChanges): CompilationResult {
        val jvmToolchain = toolchain.getToolchain(JvmPlatformToolchain::class.java)
        val opBuilder = jvmToolchain.jvmCompilationOperationBuilder(module.sources, module.outputDir)
        
        // Configure basic compiler arguments using shared method
        framework.configureBasicCompilerArguments(opBuilder.compilerArguments, module.name)
        
        // Add dependencies to classpath
        if (module.dependencies.isNotEmpty()) {
            addDependenciesToClasspath(opBuilder.compilerArguments, module.dependencies)
        }
        
        // Collect dependency snapshots for incremental compilation
        val dependencySnapshots = module.dependencies.mapNotNull { depName ->
            modules[depName]?.icDir?.resolve("shrunk.bin")?.takeIf { it.exists() }
        }
        
        // Configure incremental compilation using shared utility
        IncrementalCompilationUtils.configureIcOnBuilder(
            opBuilder,
            module.icDir,
            sourcesChanges,
            module.icDir.resolve("shrunk.bin"),
            dependencySnapshots
        ) { icBuilder ->
            icBuilder[JvmSnapshotBasedIncrementalCompilationConfiguration.ASSURED_NO_CLASSPATH_SNAPSHOT_CHANGES] = true
            icBuilder[JvmSnapshotBasedIncrementalCompilationConfiguration.KEEP_IC_CACHES_IN_MEMORY] = true
            icBuilder[JvmSnapshotBasedIncrementalCompilationConfiguration.OUTPUT_DIRS] = setOf(module.outputDir, module.icDir)
            icBuilder[JvmSnapshotBasedIncrementalCompilationConfiguration.PRECISE_JAVA_TRACKING] = true
            icBuilder[JvmSnapshotBasedIncrementalCompilationConfiguration.MODULE_BUILD_DIR] = module.workspace
            icBuilder[JvmSnapshotBasedIncrementalCompilationConfiguration.ROOT_PROJECT_DIR] = root
        }
        
        val operation = opBuilder.build()
        return CompilationTestUtils.runCompile(toolchain, operation, framework.createDaemonExecutionPolicy(toolchain), logger)
    }
    
    
    /**
     * Adds dependency modules' output directories to the compilation operation's classpath.
     * Includes transitive dependencies to ensure all required classes are available.
     */
    private fun addDependenciesToClasspath(args: JvmCompilerArguments.Builder, dependencies: List<String>) {
        val currentCp = args[JvmCompilerArguments.CLASSPATH]
        
        // Collect all transitive dependencies
        val allDependencies = mutableSetOf<String>()
        fun collectTransitiveDependencies(depName: String) {
            if (depName in allDependencies) return
            allDependencies.add(depName)
            
            val module = modules[depName]
            module?.dependencies?.forEach { transitiveDep ->
                collectTransitiveDependencies(transitiveDep)
            }
        }
        
        dependencies.forEach { collectTransitiveDependencies(it) }
        
        val dependencyPaths = allDependencies.mapNotNull { depName ->
            modules[depName]?.outputDir?.toString()
        }
        
        if (dependencyPaths.isNotEmpty()) {
            val newCp = if (currentCp.isNullOrEmpty()) {
                dependencyPaths.joinToString(java.io.File.pathSeparator)
            } else {
                currentCp + java.io.File.pathSeparator + dependencyPaths.joinToString(java.io.File.pathSeparator)
            }
            args[JvmCompilerArguments.CLASSPATH] = newCp
        }
    }
    
    /**
     * Performs topological sort to determine compilation order based on dependencies.
     */
    private fun topologicalSort(): List<String> {
        val visited = mutableSetOf<String>()
        val result = mutableListOf<String>()
        
        fun visit(moduleName: String) {
            if (moduleName in visited) return
            visited.add(moduleName)
            
            val module = modules[moduleName] ?: return
            for (dep in module.dependencies) {
                visit(dep)
            }
            result.add(moduleName)
        }
        
        for (moduleName in modules.keys) {
            visit(moduleName)
        }
        
        return result
    }
    
    /**
     * Updates a source file and recompiles all affected modules.
     */
    fun updateSourceAndRecompile(moduleName: String, fileName: String, newContent: String): CompilationResults {
        val module = modules[moduleName] ?: throw IllegalArgumentException("Module $moduleName not found")
        val sourceFile = module.sources.find { it.fileName.toString() == fileName }
            ?: throw IllegalArgumentException("Source file $fileName not found in module $moduleName")
        
        sourceFile.writeText(newContent.trimIndent())
        
        val sourcesChanges = SourcesChanges.Known(listOf(sourceFile.toFile()), emptyList())
        return compileAll(sourcesChanges, moduleName)
    }
    
    /**
     * Gets the output state of a class file in a module.
     */
    fun getClassOutputState(moduleName: String, relativeClassPath: String): CompilationTestUtils.OutputState {
        val module = modules[moduleName] ?: throw IllegalArgumentException("Module $moduleName not found")
        return CompilationTestUtils.classOutputState(module.outputDir, relativeClassPath)
    }
    
    /**
     * Reads the bytes of a class file in a module.
     */
    fun readClassBytes(moduleName: String, relativeClassPath: String): ByteArray? {
        val module = modules[moduleName] ?: throw IllegalArgumentException("Module $moduleName not found")
        return CompilationTestUtils.readClassOutputBytes(module.outputDir, relativeClassPath)
    }
}

/**
 * Results of compiling multiple modules.
 */
class CompilationResults(
    private val results: Map<String, CompilationResult>
) {
    
    fun allSuccessful(): Boolean {
        return results.values.all { it == CompilationResult.COMPILATION_SUCCESS }
    }
}
