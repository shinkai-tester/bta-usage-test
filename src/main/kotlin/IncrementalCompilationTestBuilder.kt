import org.jetbrains.kotlin.buildtools.api.*
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.jetbrains.kotlin.buildtools.api.jvm.JvmSnapshotBasedIncrementalCompilationOptions
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmCompilationOperation
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories

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
         * Adds a Java source file to this module.
         */
        fun javaSource(fileName: String, content: String): ModuleBuilder {
            val source = framework.createJavaSource(module.workspace, fileName, content)
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
        
        return CompilationResults(results, modules)
    }
    
    /**
     * Compiles a specific module with its dependencies on the classpath.
     */
    private fun compileModule(module: ModuleSetup, sourcesChanges: SourcesChanges): CompilationResult {
        val operation = CompilationTestUtils.newJvmOp(toolchain, module.sources, module.outputDir, framework)
        
        // Add dependencies to classpath
        if (module.dependencies.isNotEmpty()) {
            addDependenciesToClasspath(operation, module.dependencies)
        }
        
        // Configure incremental compilation
        val dependencySnapshots = module.dependencies.mapNotNull { depName ->
            modules[depName]?.icDir?.resolve("shrunk.bin")?.takeIf { it.toFile().exists() }
        }
        val icConfig = IncrementalCompilationUtils.icConfig(
            module.icDir,
            sourcesChanges,
            module.icDir.resolve("shrunk.bin"),
            dependencySnapshots
        ) { options ->
            // Avoid requiring Gradle classpath snapshots for modules whose classpath is stable
            options[JvmSnapshotBasedIncrementalCompilationOptions.ASSURED_NO_CLASSPATH_SNAPSHOT_CHANGES] = true
            // Keep caches hot and point IC to our outputs
            options[JvmSnapshotBasedIncrementalCompilationOptions.KEEP_IC_CACHES_IN_MEMORY] = true
            options[JvmSnapshotBasedIncrementalCompilationOptions.OUTPUT_DIRS] = setOf(module.outputDir, module.icDir)
            options[JvmSnapshotBasedIncrementalCompilationOptions.PRECISE_JAVA_TRACKING] = true
            options[JvmSnapshotBasedIncrementalCompilationOptions.MODULE_BUILD_DIR] = module.workspace
            options[JvmSnapshotBasedIncrementalCompilationOptions.ROOT_PROJECT_DIR] = root
        }
        IncrementalCompilationUtils.attachIcTo(operation, icConfig)
        
        return CompilationTestUtils.runCompile(toolchain, operation, framework.createDaemonExecutionPolicy(toolchain), logger)
    }
    
    /**
     * Adds dependency modules' output directories to the compilation operation's classpath.
     * Includes transitive dependencies to ensure all required classes are available.
     */
    private fun addDependenciesToClasspath(operation: JvmCompilationOperation, dependencies: List<String>) {
        val args = operation.compilerArguments
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
                dependencyPaths.joinToString(File.pathSeparator)
            } else {
                currentCp + File.pathSeparator + dependencyPaths.joinToString(File.pathSeparator)
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
     * Compiles only the specified modules in dependency order.
     */
    fun compileModules(moduleNames: List<String>, sourcesChanges: SourcesChanges = SourcesChanges.ToBeCalculated): CompilationResults {
        val results = mutableMapOf<String, CompilationResult>()
        val allModules = topologicalSort()
        val targetModules = allModules.filter { it in moduleNames }
        
        for (moduleName in targetModules) {
            val module = modules[moduleName]!!
            val result = compileModule(module, sourcesChanges)
            results[moduleName] = result
            
            // Stop compilation on first failure to avoid cascading errors
            if (result != CompilationResult.COMPILATION_SUCCESS) {
                break
            }
        }
        
        return CompilationResults(results, modules)
    }
    
    /**
     * Updates a source file in a module and recompiles with known changes.
     */
    fun updateSourceAndRecompile(moduleName: String, fileName: String, newContent: String): CompilationResults {
        val module = modules[moduleName] ?: throw IllegalArgumentException("Module $moduleName not found")
        val sourceFile = module.sources.find { it.fileName.toString() == fileName }
            ?: throw IllegalArgumentException("Source file $fileName not found in module $moduleName")
        
        sourceFile.toFile().writeText(newContent.trimIndent())
        
        val sourcesChanges = SourcesChanges.Known(listOf(sourceFile.toFile()), emptyList())
        return compileAll(sourcesChanges, moduleName)
    }
    
    /**
     * Updates a source file in a module and recompiles only the specified modules.
     */
    fun updateSourceAndRecompileModules(moduleName: String, fileName: String, newContent: String, targetModules: List<String>): CompilationResults {
        val module = modules[moduleName] ?: throw IllegalArgumentException("Module $moduleName not found")
        val sourceFile = module.sources.find { it.fileName.toString() == fileName }
            ?: throw IllegalArgumentException("Source file $fileName not found in module $moduleName")
        
        sourceFile.toFile().writeText(newContent.trimIndent())
        
        val sourcesChanges = SourcesChanges.Known(listOf(sourceFile.toFile()), emptyList())
        return compileModules(targetModules, sourcesChanges)
    }
    
    /**
     * Gets the output state of a class file in a specific module.
     */
    fun getClassOutputState(moduleName: String, relativeClassPath: String): CompilationTestUtils.OutputState {
        val module = modules[moduleName] ?: throw IllegalArgumentException("Module $moduleName not found")
        return CompilationTestUtils.classOutputState(module.outputDir, relativeClassPath)
    }
    
    /**
     * Reads the bytes of a class file in a specific module.
     */
    fun readClassBytes(moduleName: String, relativeClassPath: String): ByteArray? {
        val module = modules[moduleName] ?: throw IllegalArgumentException("Module $moduleName not found")
        return CompilationTestUtils.readClassOutputBytes(module.outputDir, relativeClassPath)
    }
}

/**
 * Results of a compilation operation across multiple modules.
 */
data class CompilationResults(
    private val results: Map<String, CompilationResult>,
    private val modules: Map<String, IncrementalCompilationTestBuilder.ModuleSetup>
) {
    
    /**
     * Gets the compilation result for a specific module.
     */
    fun getResult(moduleName: String): CompilationResult {
        return results[moduleName] ?: throw IllegalArgumentException("No result for module $moduleName")
    }
    
    /**
     * Checks if all modules compiled successfully.
     */
    fun allSuccessful(): Boolean = results.values.all { it == CompilationResult.COMPILATION_SUCCESS }
    
    /**
     * Gets all module names that were compiled.
     */
    fun getModuleNames(): Set<String> = results.keys
}