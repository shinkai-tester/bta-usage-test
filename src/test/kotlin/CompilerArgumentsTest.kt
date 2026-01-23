import org.jetbrains.kotlin.buildtools.api.ExecutionPolicy
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.jetbrains.kotlin.buildtools.api.arguments.enums.JvmTarget
import org.jetbrains.kotlin.buildtools.api.arguments.enums.KotlinVersion
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalBuildToolsApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Suppress("DEPRECATION") // Tests intentionally use deprecated mutable compiler arguments API
class CompilerArgumentsTest : TestBase() {

    private lateinit var toolchain: KotlinToolchains
    private lateinit var daemonPolicy: ExecutionPolicy

    @BeforeAll
    fun initCommonToolchain() {
        toolchain = framework.loadToolchain(useDaemon = true)
        daemonPolicy = framework.createDaemonExecutionPolicy(toolchain).also { it.configureDaemon() }
    }

    @Test
    @DisplayName("Check usage of common compiler arguments")
    fun testCommonCompilerArguments() {
        val setup = createTestSetup()
        // Define an opt-in marker and an API that requires it
        val annotationsKt = framework.createKotlinSource(
            setup.workspace, "annotations.kt", """
        package test.common
        
        @RequiresOptIn(level = RequiresOptIn.Level.ERROR)
        annotation class MyExperimental
        
        @MyExperimental
        fun experimentalApi(): String = "exp"
    """
        )
        val usageKt = framework.createKotlinSource(
            setup.workspace, "useExperimental.kt", """
        package test.common
        
        fun callExp(): String {
            // Experimental API requires opt-in
            return experimentalApi()
        }
    """
        )

        val op = CompilationTestUtils.newJvmOp(
            toolchain,
            listOf(annotationsKt, usageKt),
            setup.outputDirectory,
            framework
        )
        val args = op.compilerArguments
        args[CommonCompilerArguments.LANGUAGE_VERSION] = KotlinVersion.V2_3
        args[CommonCompilerArguments.API_VERSION] = KotlinVersion.V2_3
        args[CommonCompilerArguments.PROGRESSIVE] = true
        args[CommonCompilerArguments.OPT_IN] = arrayOf("test.common.MyExperimental")

        run {
            val k2 = toCompilerArgumentsOrNull(args)
            if (k2 != null) {
                assertEquals("2.3", getStringField(k2, "languageVersion"))
                assertEquals("2.3", getStringField(k2, "apiVersion"))
                val optIn = getArrayField(k2, "optIn")?.toList() ?: emptyList()
                assertTrue("test.common.MyExperimental" in optIn)
                assertEquals(getBooleanField(k2, "progressiveMode"), true)
            }
        }

        val logger = TestLogger()
        val result = framework.withDaemonContext {
            CompilationTestUtils.runCompile(toolchain, op, daemonPolicy, logger)
        }
        assertCompilationSuccessful(result)
        assertClassFilesExist(setup.outputDirectory, "AnnotationsKt", "UseExperimentalKt")
    }

    @Test
    @DisplayName("Check usage of JVM-specific arguments")
    fun testJvmSpecificArguments() {
        val setup = createTestSetup()
        val src = framework.createKotlinSource(
            setup.workspace, "Simple.kt", """
        class Greeter {
            fun greet(name: String): String = "Hello, " + name
        }
        
        fun callIt(): String = Greeter().greet("QA")
        """
        )

        val op = CompilationTestUtils.newJvmOp(toolchain, listOf(src), setup.outputDirectory, framework)
        val args = op.compilerArguments

        args[JvmCompilerArguments.JVM_TARGET] = JvmTarget.JVM_17
        args[JvmCompilerArguments.MODULE_NAME] = "simple-module"
        args[JvmCompilerArguments.JAVA_PARAMETERS] = true

        // Verify CLI arguments conversion for selected options
        val cli = invokeToArgumentStrings(args)
        val joined = cli.joinToString(" ") { it.replace("\"", "") }
        assertTrue(joined.contains("-jvm-target 17"), joined)
        assertTrue(joined.contains("-module-name simple-module"), joined)
        assertTrue(joined.contains("-java-parameters"), joined)

        run {
            val k2 = toCompilerArgumentsOrNull(args)
            if (k2 != null) {
                assertEquals("17", getStringField(k2, "jvmTarget"))
                assertEquals("simple-module", getStringField(k2, "moduleName"))
                assertEquals(getBooleanField(k2, "javaParameters"), true)
            }
        }

        val result = framework.withDaemonContext {
            CompilationTestUtils.runCompile(toolchain, op, daemonPolicy, TestLogger())
        }
        assertCompilationSuccessful(result)
        assertClassFilesExist(setup.outputDirectory, "SimpleKt", "Greeter")

        // Bytecode target for JVM 17 -> major version 61
        val bytes = CompilationTestUtils.readClassOutputBytes(setup.outputDirectory, "Greeter.class")
        assertNotNull(bytes)
        val major = readClassFileMajorVersion(bytes)
        assertEquals(61, major, "-jvm-target 17 should produce classfile major version 61")
    }

    @Test
    @DisplayName("Error handling: invalid/missing arguments cause compilation issues")
    fun testErrorHandlingForInvalidArgs() {
        val setup = createTestSetup()
        val src = framework.createKotlinSource(
            setup.workspace, "NeedsStdlib.kt", """
            fun build(): List<String> = listOf("a", "b")
        """
        )

        // Force an invalid classpath (no stdlib) together with NO_STDLIB to provoke errors when stdlib is needed
        val op = CompilationTestUtils.newJvmOp(toolchain, listOf(src), setup.outputDirectory, framework)
        val args = op.compilerArguments
        args[JvmCompilerArguments.NO_STDLIB] = true
        args[JvmCompilerArguments.CLASSPATH] = "" // clear classpath

        val logger = TestLogger()
        val result = framework.withDaemonContext {
            CompilationTestUtils.runCompile(toolchain, op, daemonPolicy, logger)
        }
        assertCompilationFailed(result)
        assertTrue(
            logger.getAllErrorMessages()
                .any { it.contains("kotlin", ignoreCase = true) && it.contains("unresolved", ignoreCase = true) } ||
                    logger.getAllErrorMessages().any {
                        it.contains("listOf", ignoreCase = true) && it.contains("unresolved", ignoreCase = true)
                    },
            "Expected unresolved references due to missing stdlib in classpath"
        )
    }


    // ------------------------- Helpers -------------------------

    private fun readClassFileMajorVersion(bytes: ByteArray): Int {
        // Class file format: 0-3: 0xCAFEBABE, 4-5: minor, 6-7: major
        require(bytes.size >= 8)
        fun u2(i: Int) = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
        val magic =
            (((bytes[0].toInt() and 0xFF) shl 24) or
             ((bytes[1].toInt() and 0xFF) shl 16) or
             ((bytes[2].toInt() and 0xFF) shl 8) or
             (bytes[3].toInt() and 0xFF))
        require(magic == 0xCAFEBABE.toInt()) { "Invalid class file" }
        return u2(6)
    }

    private fun invokeToArgumentStrings(args: Any): List<String> {
        val method = args.javaClass.methods.firstOrNull { it.name == "toArgumentStrings" && it.parameterCount == 0 }
            ?: throw IllegalStateException("toArgumentStrings() not found on ${args.javaClass.name}")
        
        val result = method.invoke(args)
        @Suppress("UNCHECKED_CAST")
        return result as List<String>
    }

    // Tries to call toCompilerArguments() reflectively and return the resulting K2JVMCompilerArguments instance.
    private fun toCompilerArgumentsOrNull(args: Any): Any? {
        val method = args.javaClass.methods.firstOrNull { it.name == "toCompilerArguments" && it.parameterCount == 0 }
            ?: return null
        return try {
            method.invoke(args)
        } catch (_: Throwable) {
            null
        }
    }

    private fun accessorSuffix(name: String): String =
        name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    private fun getProperty(instance: Any, name: String): Any? {
        val cls = instance.javaClass
        val suffix = accessorSuffix(name)
        val candidates = listOf("is$suffix", "get$suffix")
        for (candidate in candidates) {
            try {
                val m = cls.getMethod(candidate)
                return m.invoke(instance)
            } catch (_: Throwable) {
                // try next
            }
        }
        return try {
            val f = cls.getDeclaredField(name)
            f.isAccessible = true
            f.get(instance)
        } catch (_: Throwable) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun getStringField(instance: Any, name: String): String? =
        getProperty(instance, name) as? String

    private fun getBooleanField(instance: Any, name: String): Boolean? =
        getProperty(instance, name) as? Boolean

    @Suppress("UNCHECKED_CAST", "SameParameterValue")
    private fun getArrayField(instance: Any, name: String): Array<String>? =
        getProperty(instance, name) as? Array<String>
}