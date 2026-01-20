import org.jetbrains.kotlin.buildtools.api.*
import org.jetbrains.kotlin.buildtools.api.jvm.JvmSnapshotBasedIncrementalCompilationOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.net.URLClassLoader
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.io.path.*

/**
 * Tests for compilation cancellation functionality.
 * 
 * This test class verifies that compilation operations can be cancelled:
 * - Non-incremental compilation with in-process execution strategy
 * - Non-incremental compilation with daemon execution strategy
 * - Incremental compilation with in-process execution strategy
 * - Incremental compilation with daemon execution strategy
 */
@OptIn(ExperimentalBuildToolsApi::class)
@Order(6)
class CancellationTest : TestBase() {

    @Test
    @DisplayName("Cancellation of non-incremental compilation with in-process execution strategy")
    fun cancelNonIncrementalInProcess() {
        val setup = createTestSetup()
        val source = framework.createKotlinSource(setup.workspace, "InProcessTest.kt", """
        class InProcessTest {
            fun greet(): String = "Hello from in-process!"
        }
    """)

        val toolchain = framework.loadToolchain()
        val operation = CompilationTestUtils.newJvmOp(toolchain, listOf(source), setup.outputDirectory, framework)

        val exception = assertThrows<OperationCancelledException> {
            toolchain.createBuildSession().use { session ->
                operation.cancel()
                session.executeOperation(operation, toolchain.createInProcessExecutionPolicy(), TestLogger())
            }
        }

        assertEquals("Operation has been cancelled.", exception.message)
    }

    @Test
    @DisplayName("Cancellation of non-incremental compilation with daemon execution strategy")
    fun cancelNonIncrementalDaemon() {
        val setup = createTestSetup()
        val source = framework.createKotlinSource(setup.workspace, "DaemonTest.kt", """
            class DaemonTest {
                fun greet(): String = "Hello from daemon!"
            }
        """)
        
        val daemonRunPath: Path = createTempDirectory("test-daemon-files-non-incremental")

        val toolchain = framework.loadToolchain(useDaemon = true)
        val operation = CompilationTestUtils.newJvmOp(toolchain, listOf(source), setup.outputDirectory, framework)
        
        val daemonPolicy = createDaemonPolicyWithWait(toolchain, daemonRunPath)

        @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
        val caughtException = kotlin.concurrent.atomics.AtomicReference<OperationCancelledException?>(null)

        val executionThread = thread {
            try {
                framework.withDaemonContext {
                    toolchain.createBuildSession().use { session ->
                        session.executeOperation(operation, daemonPolicy, TestLogger())
                    }
                }
            } catch (e: OperationCancelledException) {
                @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
                caughtException.store(e)
            }
        }

        operation.cancel()
        daemonRunPath.resolve("daemon-test-start").createFile().toFile().deleteOnExit()
        
        executionThread.join()

        @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
        val exception = caughtException.load()
        assertEquals("Operation has been cancelled.", exception?.message)
        
        attemptCleanupDaemon(daemonRunPath)
    }

    @Test
    @DisplayName("Cancellation of incremental compilation with in-process execution strategy")
    fun cancelIncrementalInProcess() {
        val setup = createTestSetup()
        val icDir = setup.workspace.resolve("ic").createDirectories()
        val source = framework.createKotlinSource(setup.workspace, "IncrementalInProcess.kt", """
            class IncrementalInProcess {
                fun compute(): Int = 42
            }
        """)

        val toolchain = framework.loadToolchain()
        val operation = CompilationTestUtils.newJvmOp(toolchain, listOf(source), setup.outputDirectory, framework)
        
        // Configure incremental compilation
        val icConfig = IncrementalCompilationUtils.icConfig(
            icDir,
            SourcesChanges.Unknown,
            icDir.resolve("shrunk-classpath-snapshot.bin"),
            emptyList()
        ) { options ->
            options[JvmSnapshotBasedIncrementalCompilationOptions.KEEP_IC_CACHES_IN_MEMORY] = true
            options[JvmSnapshotBasedIncrementalCompilationOptions.OUTPUT_DIRS] = setOf(setup.outputDirectory, icDir)
        }
        IncrementalCompilationUtils.attachIcTo(operation, icConfig)

        val exception = assertThrows<OperationCancelledException> {
            toolchain.createBuildSession().use { session ->
                operation.cancel()
                session.executeOperation(operation, toolchain.createInProcessExecutionPolicy(), TestLogger())
            }
        }

        assertEquals("Operation has been cancelled.", exception.message)
    }

    @Test
    @DisplayName("Cancellation of incremental compilation with daemon execution strategy")
    fun cancelIncrementalDaemon() {
        val setup = createTestSetup()
        val icDir = setup.workspace.resolve("ic").createDirectories()
        val source = framework.createKotlinSource(setup.workspace, "IncrementalDaemon.kt", """
            class IncrementalDaemon {
                fun compute(): Int = 100
            }
        """)
        
        val daemonRunPath: Path = createTempDirectory("test-daemon-files-incremental")

        val toolchain = framework.loadToolchain(useDaemon = true)
        val operation = CompilationTestUtils.newJvmOp(toolchain, listOf(source), setup.outputDirectory, framework)

        val icConfig = IncrementalCompilationUtils.icConfig(
            icDir,
            SourcesChanges.Unknown,
            icDir.resolve("shrunk-classpath-snapshot.bin"),
            emptyList()
        ) { options ->
            options[JvmSnapshotBasedIncrementalCompilationOptions.KEEP_IC_CACHES_IN_MEMORY] = true
            options[JvmSnapshotBasedIncrementalCompilationOptions.OUTPUT_DIRS] = setOf(setup.outputDirectory, icDir)
        }
        IncrementalCompilationUtils.attachIcTo(operation, icConfig)
        
        val daemonPolicy = createDaemonPolicyWithWait(toolchain, daemonRunPath)

        @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
        val caughtException = kotlin.concurrent.atomics.AtomicReference<OperationCancelledException?>(null)

        val executionThread = thread {
            try {
                framework.withDaemonContext {
                    toolchain.createBuildSession().use { session ->
                        session.executeOperation(operation, daemonPolicy, TestLogger())
                    }
                }
            } catch (e: OperationCancelledException) {
                @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
                caughtException.store(e)
            }
        }

        operation.cancel()
        daemonRunPath.resolve("daemon-test-start").createFile().toFile().deleteOnExit()
        
        executionThread.join()

        @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
        val exception = caughtException.load()
        assertEquals("Operation has been cancelled.", exception?.message)
        
        attemptCleanupDaemon(daemonRunPath)
    }
    
    /**
     * Creates a daemon execution policy configured to wait before starting compilation.
     * This allows the test to cancel the operation before compilation actually begins.
     */
    @OptIn(DelicateBuildToolsApi::class)
    private fun createDaemonPolicyWithWait(toolchain: KotlinToolchains, daemonRunPath: Path): ExecutionPolicy {
        return framework.withDaemonContext {
            val daemonPolicy = toolchain.createDaemonExecutionPolicy()
            // Configure daemon with wait flag and custom run directory using reflection
            configureDaemonPolicyWithWait(daemonPolicy, daemonRunPath)
            daemonPolicy
        }
    }
    
    /**
     * Configures the daemon policy with wait-before-compilation flag and custom run directory.
     */
    @OptIn(DelicateBuildToolsApi::class)
    private fun configureDaemonPolicyWithWait(daemonPolicy: ExecutionPolicy, daemonRunPath: Path) {
        runCatching {
            // Use reflection to set daemon options
            val withDaemonClass = Class.forName("org.jetbrains.kotlin.buildtools.api.ExecutionPolicy\$WithDaemon")
            
            // Get JVM_ARGUMENTS option
            val jvmArgsField = withDaemonClass.getField("JVM_ARGUMENTS")
            val jvmArgsKey = jvmArgsField.get(null)
            
            // Get DAEMON_RUN_DIR_PATH option
            val runDirField = withDaemonClass.getField("DAEMON_RUN_DIR_PATH")
            val runDirKey = runDirField.get(null)
            
            // Get SHUTDOWN_DELAY_MILLIS option
            val shutdownField = withDaemonClass.getField("SHUTDOWN_DELAY_MILLIS")
            val shutdownKey = shutdownField.get(null)
            
            // Find the set method
            val setMethod = daemonPolicy.javaClass.methods.firstOrNull { method ->
                method.name == "set" && method.parameterCount == 2
            }
            
            if (setMethod != null) {
                // Set JVM arguments with wait flag
                setMethod.invoke(daemonPolicy, jvmArgsKey, listOf("-Dkotlin.daemon.wait.before.compilation.for.tests=true"))
                // Set daemon run directory
                setMethod.invoke(daemonPolicy, runDirKey, daemonRunPath)
                // Set shutdown delay to 0
                setMethod.invoke(daemonPolicy, shutdownKey, 0L)
            }
        }
    }
    
    /**
     * Attempts to clean up the daemon by deleting its run file and waiting for it to shut down.
     * This is essential on Windows where the directory cannot be deleted while the daemon is running.
     */
    private fun attemptCleanupDaemon(daemonRunPath: Path) {
        daemonRunPath.resolve("daemon-test-start").deleteIfExists()
        var tries = 10
        do {
            val deleted = try {
                daemonRunPath.listDirectoryEntries("*.run").forEach { it.deleteIfExists() }
                daemonRunPath.deleteExisting()
                true
            } catch (_: NoSuchFileException) {
                true
            } catch (_: Exception) {
                false
            }
            if (deleted) {
                break
            }
            Thread.sleep(150)
        } while (tries-- > 0)
    }
    
    @Test
    @DisplayName("Cancellation not supported with old compiler (pre-2.3.20) throws IllegalStateException")
    fun cancelNotSupportedWithOldCompiler() {
        val setup = createTestSetup()
        val source = framework.createKotlinSource(setup.workspace, "OldCompilerTest.kt", """
            class OldCompilerTest {
                fun greet(): String = "Hello from old compiler!"
            }
        """)

        // Load toolchain with old compiler (2.3.0) that doesn't support cancellation
        val toolchain = loadToolchainWithOldCompiler()
        val operation = CompilationTestUtils.newJvmOp(toolchain, listOf(source), setup.outputDirectory, framework)

        val exception = assertThrows<IllegalStateException> {
            operation.cancel()
        }

        kotlin.test.assertEquals(
            exception.message?.contains("Cancellation is supported from compiler version 2.3.20"),
            true,
            "Expected error message about cancellation not supported, but got: ${exception.message}"
        )
    }
    
    /**
     * Loads the Kotlin toolchain using the old compiler implementation (2.3.0).
     * This is used for backward compatibility testing.
     */
    private fun loadToolchainWithOldCompiler(): KotlinToolchains {
        val oldClasspath = System.getProperty("old.compiler.impl.classpath")
            ?: throw IllegalStateException("old.compiler.impl.classpath system property not set")
        
        val urls = oldClasspath.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { File(it).toURI().toURL() }
            .toTypedArray()
        
        val parent = tryLoadSharedApiParent()
        val implCl = URLClassLoader(urls, parent)
        return KotlinToolchains.loadImplementation(implCl)
    }
    
    /**
     * Tries to load SharedApiClassesClassLoader as parent for proper class isolation.
     */
    private fun tryLoadSharedApiParent(): ClassLoader {
        return try {
            val clazz = Class.forName("org.jetbrains.kotlin.buildtools.api.classloaders.SharedApiClassesClassLoader")
            val ctor = clazz.getDeclaredConstructor()
            ctor.isAccessible = true
            ctor.newInstance() as ClassLoader
        } catch (_: Throwable) {
            ClassLoader.getSystemClassLoader()
        }
    }
}
