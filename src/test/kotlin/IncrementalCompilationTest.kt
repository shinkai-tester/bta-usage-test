import framework.TestLogger
import org.jetbrains.kotlin.buildtools.api.ExecutionPolicy
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.arguments.ExperimentalCompilerArgument
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ArgumentsSource
import support.ExecutionPolicyArgumentProvider
import support.TestBase
import utils.CompilationTestUtils.runCompile
import utils.IncrementalCompilationUtils
import kotlin.io.path.writeText

/**
 * Incremental compilation behavior tests for single-module scenarios.
 * Verifies that IC correctly distinguishes between ABI changes and non-ABI changes.
 *
 * Uses parameterized tests to ensure IC behavior is consistent across execution policies.
 * All IC rounds within a test share the same BuildSession to preserve IC cache state.
 *
 * The tests assert on which sources the compiler actually recompiled, parsed from the
 * `compile iteration:` debug log.
 */
@OptIn(ExperimentalBuildToolsApi::class, ExperimentalCompilerArgument::class)
class IncrementalCompilationTest : TestBase() {

    @ParameterizedTest(name = "{0}: {displayName}")
    @ArgumentsSource(ExecutionPolicyArgumentProvider::class)
    @DisplayName("Kotlin body change: no dependent recompile")
    fun kotlinBodyChangeNoRecompile(execution: Pair<KotlinToolchains, ExecutionPolicy>) {
        val (toolchain, policy) = execution
        val setup = createTestSetup()

        // Create two source files
        val manaSource = createKotlinSource(setup.workspace, "ManaSource.kt", """
            package fantasy.resources
            class ManaSource { fun mana(): Int = 1 }
        """)

        val spellcaster = createKotlinSource(setup.workspace, "Spellcaster.kt", """
            package fantasy.casters
            import fantasy.resources.ManaSource
            class Spellcaster { fun use() = ManaSource().mana().toString().length }
        """)

        toolchain.createBuildSession().use { session ->
            // Initial incremental compilation
            val operation1 = IncrementalCompilationUtils.createIncrementalJvmOperation(
                toolchain,
                listOf(manaSource, spellcaster),
                setup.outputDirectory,
                setup.icDirectory,
                setup.workspace
            )
            assertCompilationSuccessful(runCompile(session, operation1, policy))

            // Non-ABI change in ManaSource: change method body only (return value 1 -> 2)
            manaSource.writeText("""
                package fantasy.resources
                class ManaSource { fun mana(): Int = 2 }
            """.trimIndent())

            // Recompile within the same session to preserve IC caches, capturing its log.
            val operation2 = IncrementalCompilationUtils.createIncrementalJvmOperation(
                toolchain,
                listOf(manaSource, spellcaster),
                setup.outputDirectory,
                setup.icDirectory,
                setup.workspace
            )
            val logger = TestLogger(false)
            assertCompilationSuccessful(runCompile(session, operation2, policy, logger))

            // The producer changed, so it is recompiled; the consumer must NOT be (non-ABI change).
            assertRecompiled(logger, "ManaSource.kt")
            assertNotRecompiled(logger, "Spellcaster.kt")
        }
    }

    @ParameterizedTest(name = "{0}: {displayName}")
    @ArgumentsSource(ExecutionPolicyArgumentProvider::class)
    @DisplayName("Kotlin signature change: recompiles dependent")
    fun kotlinSignatureChangeRecompiles(execution: Pair<KotlinToolchains, ExecutionPolicy>) {
        val (toolchain, policy) = execution
        val setup = createTestSetup()

        // Create two source files
        val rune = createKotlinSource(setup.workspace, "Rune.kt", """
            package fantasy.runes
            class Rune { fun power(): Int = 1 }
        """)

        val runeReader = createKotlinSource(setup.workspace, "RuneReader.kt", """
            package fantasy.readers
            import fantasy.runes.Rune
            class RuneReader { fun use() = Rune().power().toString().length }
        """)

        toolchain.createBuildSession().use { session ->
            // Initial incremental compilation
            val operation1 = IncrementalCompilationUtils.createIncrementalJvmOperation(
                toolchain,
                listOf(rune, runeReader),
                setup.outputDirectory,
                setup.icDirectory,
                setup.workspace
            )
            assertCompilationSuccessful(runCompile(session, operation1, policy))

            // ABI change in Rune: change signature by adding a parameter (with default)
            rune.writeText("""
                package fantasy.runes
                class Rune { fun power(x: String = "x"): Int = 1 }
            """.trimIndent())

            // Recompile within the same session to preserve IC caches, capturing its log.
            val operation2 = IncrementalCompilationUtils.createIncrementalJvmOperation(
                toolchain,
                listOf(rune, runeReader),
                setup.outputDirectory,
                setup.icDirectory,
                setup.workspace
            )
            val logger = TestLogger(false)
            assertCompilationSuccessful(runCompile(session, operation2, policy, logger))

            // The signature (ABI) change forces both the producer and its consumer to be recompiled.
            assertRecompiled(logger, "Rune.kt")
            assertRecompiled(logger, "RuneReader.kt")
        }
    }

}
