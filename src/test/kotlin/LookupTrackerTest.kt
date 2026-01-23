import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmCompilationOperation
import org.jetbrains.kotlin.buildtools.api.trackers.CompilerLookupTracker
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.io.path.createDirectories
import kotlin.test.assertTrue

/**
 * Tests for the Lookup Tracker functionality in the Build Tools API.
 * 
 * Each test verifies a different type of lookup with a different execution strategy:
 * - Function call lookups with incremental and daemon
 * - Class reference lookups with incremental and in-process
 * - Extension function lookups with non-incremental and daemon
 * - Property access lookups with non-incremental and in-process
 */
@OptIn(ExperimentalBuildToolsApi::class)
class LookupTrackerTest : TestBase() {

    /**
     * Data class to store recorded lookups
     */
    data class RecordedLookup(
        val filePath: String,
        val scopeFqName: String,
        val scopeKind: CompilerLookupTracker.ScopeKind,
        val name: String
    )

    /**
     * Creates a lookup tracker that records all lookups to the provided list.
     */
    private fun createLookupTracker(recordedLookups: MutableList<RecordedLookup>): CompilerLookupTracker {
        return object : CompilerLookupTracker {
            override fun recordLookup(
                filePath: String,
                scopeFqName: String,
                scopeKind: CompilerLookupTracker.ScopeKind,
                name: String,
            ) {
                recordedLookups.add(RecordedLookup(filePath, scopeFqName, scopeKind, name))
            }
            override fun clear() { recordedLookups.clear() }
        }
    }

    @Test
    @DisplayName("Function call lookups with incremental + daemon")
    fun functionCallLookupsIncrementalDaemon() {
        val setup = createTestSetup()
        val icDir = setup.workspace.resolve("ic").createDirectories()

        val utilsSource = framework.createKotlinSource(setup.workspace, "StringUtils.kt", """
            package com.example.utils
            
            fun formatDate(timestamp: Long): String = timestamp.toString()
            fun formatNumber(value: Int): String = value.toString()
        """)

        val serviceSource = framework.createKotlinSource(setup.workspace, "MyService.kt", """
            package com.example.service
            import com.example.utils.formatDate
            
            class MyService {
                fun process(ts: Long): String = formatDate(ts)
            }
        """)

        val recordedLookups = mutableListOf<RecordedLookup>()
        val lookupTracker = createLookupTracker(recordedLookups)

        val toolchain = framework.loadToolchain(useDaemon = true)
        val sources = listOf(utilsSource, serviceSource)
        
        val operation = IncrementalCompilationUtils.newIncrementalJvmOp(
            toolchain, sources, setup.outputDirectory, icDir, setup.workspace, framework, lookupTracker
        )

        val daemonPolicy = framework.createDaemonExecutionPolicy(toolchain)
        val result = framework.withDaemonContext {
            CompilationTestUtils.runCompile(toolchain, operation, daemonPolicy)
        }

        assertCompilationSuccessful(result)
        assertTrue(recordedLookups.isNotEmpty(), "Lookup tracker should record lookups")

        val formatDateLookup = recordedLookups.find {
            it.name == "formatDate" &&
                    it.scopeFqName == "com.example.utils" &&
                    it.scopeKind == CompilerLookupTracker.ScopeKind.PACKAGE
        }
        assertTrue(formatDateLookup != null,
            "Should record lookup for 'formatDate' function in package 'com.example.utils'")

        println("Total lookups recorded: ${recordedLookups.size}")
        recordedLookups.distinctBy { "${it.scopeFqName}#${it.name}" }.forEach { lookup ->
            println("${lookup.scopeKind}: ${lookup.scopeFqName}#${lookup.name}")
        }
    }

    @Test
    @DisplayName("Class reference lookups with incremental + in-process")
    fun classReferenceLookupsIncrementalInProcess() {
        val setup = createTestSetup()
        val icDir = setup.workspace.resolve("ic").createDirectories()

        val modelSource = framework.createKotlinSource(setup.workspace, "User.kt", """
            package com.example.model
            
            data class User(val id: Int, val name: String)
        """)

        val repoSource = framework.createKotlinSource(setup.workspace, "UserRepository.kt", """
            package com.example.repository
            import com.example.model.User
            
            class UserRepository {
                fun findById(id: Int): User? = null
                fun save(user: User): Unit = Unit
            }
        """)

        val recordedLookups = mutableListOf<RecordedLookup>()
        val lookupTracker = createLookupTracker(recordedLookups)

        val toolchain = framework.loadToolchain(useDaemon = false)
        val sources = listOf(modelSource, repoSource)
        
        val operation = IncrementalCompilationUtils.newIncrementalJvmOp(
            toolchain, sources, setup.outputDirectory, icDir, setup.workspace, framework, lookupTracker
        )

        val inProcessPolicy = toolchain.createInProcessExecutionPolicy()
        val result = CompilationTestUtils.runCompile(toolchain, operation, inProcessPolicy)

        assertCompilationSuccessful(result)
        assertTrue(recordedLookups.isNotEmpty(), "Lookup tracker should record lookups")

        val userClassLookup = recordedLookups.find {
            it.name == "User" &&
                    it.scopeFqName == "com.example.model" &&
                    it.scopeKind == CompilerLookupTracker.ScopeKind.PACKAGE
        }
        assertTrue(userClassLookup != null,
            "Should record lookup for 'User' class in package 'com.example.model'")

        println("Total lookups recorded: ${recordedLookups.size}")
        recordedLookups.distinctBy { "${it.scopeFqName}#${it.name}" }.forEach { lookup ->
            println("${lookup.scopeKind}: ${lookup.scopeFqName}#${lookup.name}")
        }
    }

    @Test
    @DisplayName("Extension function lookups with non-incremental + daemon")
    fun extensionFunctionLookupsNonIncrementalDaemon() {
        val setup = createTestSetup()

        val extensionsSource = framework.createKotlinSource(setup.workspace, "StringExtensions.kt", """
            package com.example.extensions
            
            fun String.toTitleCase(): String = 
                this.split(" ").joinToString(" ") { it.capitalize() }
        """)

        val usageSource = framework.createKotlinSource(setup.workspace, "TextProcessor.kt", """
            package com.example.processor
            import com.example.extensions.toTitleCase
            
            class TextProcessor {
                fun process(text: String): String = text.toTitleCase()
            }
        """)

        val recordedLookups = mutableListOf<RecordedLookup>()
        val lookupTracker = createLookupTracker(recordedLookups)

        val toolchain = framework.loadToolchain(useDaemon = true)
        val baseOperation = CompilationTestUtils.newJvmOp(
            toolchain,
            listOf(extensionsSource, usageSource),
            setup.outputDirectory,
            framework
        )

        val opBuilder = baseOperation.toBuilder()
        opBuilder[JvmCompilationOperation.LOOKUP_TRACKER] = lookupTracker
        val operation = opBuilder.build()

        val daemonPolicy = framework.createDaemonExecutionPolicy(toolchain)
        val result = framework.withDaemonContext {
            CompilationTestUtils.runCompile(toolchain, operation, daemonPolicy)
        }

        assertCompilationSuccessful(result)
        assertTrue(recordedLookups.isNotEmpty(), "Lookup tracker should record lookups")

        val extensionLookup = recordedLookups.find {
            it.name == "toTitleCase" &&
                    it.scopeFqName == "com.example.extensions"
        }
        assertTrue(extensionLookup != null,
            "Should record lookup for extension function 'toTitleCase'")

        println("Total lookups recorded: ${recordedLookups.size}")
        recordedLookups.distinctBy { "${it.scopeFqName}#${it.name}" }.forEach { lookup ->
            println("${lookup.scopeKind}: ${lookup.scopeFqName}#${lookup.name}")
        }
    }

    @Test
    @DisplayName("Property access lookups with non-incremental + in-process")
    fun propertyAccessLookupsNonIncrementalInProcess() {
        val setup = createTestSetup()

        val configSource = framework.createKotlinSource(setup.workspace, "AppConfig.kt", """
            package com.example.config
            
            object AppConfig {
                val appName: String = "MyApp"
                val version: Int = 1
                val debugMode: Boolean = false
            }
        """)

        val usageSource = framework.createKotlinSource(setup.workspace, "ConfigReader.kt", """
            package com.example.reader
            import com.example.config.AppConfig
            
            class ConfigReader {
                fun getAppInfo(): String = AppConfig.appName + " v" + AppConfig.version
                fun isDebug(): Boolean = AppConfig.debugMode
            }
        """)

        val recordedLookups = mutableListOf<RecordedLookup>()
        val lookupTracker = createLookupTracker(recordedLookups)

        val toolchain = framework.loadToolchain(useDaemon = false)
        val baseOperation = CompilationTestUtils.newJvmOp(
            toolchain,
            listOf(configSource, usageSource),
            setup.outputDirectory,
            framework
        )

        val opBuilder = baseOperation.toBuilder()
        opBuilder[JvmCompilationOperation.LOOKUP_TRACKER] = lookupTracker
        val operation = opBuilder.build()

        val inProcessPolicy = toolchain.createInProcessExecutionPolicy()
        val result = CompilationTestUtils.runCompile(toolchain, operation, inProcessPolicy)

        assertCompilationSuccessful(result)
        assertTrue(recordedLookups.isNotEmpty(), "Lookup tracker should record lookups")

        val appNameLookup = recordedLookups.find {
            it.name == "appName" &&
                    it.scopeFqName == "com.example.config.AppConfig"
        }
        assertTrue(appNameLookup != null,
            "Should record lookup for 'appName' property in 'com.example.config.AppConfig'")

        val versionLookup = recordedLookups.find {
            it.name == "version" &&
                    it.scopeFqName == "com.example.config.AppConfig"
        }
        assertTrue(versionLookup != null,
            "Should record lookup for 'version' property in 'com.example.config.AppConfig'")

        println("Total lookups recorded: ${recordedLookups.size}")
        recordedLookups.distinctBy { "${it.scopeFqName}#${it.name}" }.forEach { lookup ->
            println("${lookup.scopeKind}: ${lookup.scopeFqName}#${lookup.name}")
        }
    }
}
