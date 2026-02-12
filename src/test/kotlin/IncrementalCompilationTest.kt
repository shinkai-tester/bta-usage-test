import support.ExecutionPolicyArgumentProvider
import support.TestBase
import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.ExecutionPolicy
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.arguments.ExperimentalCompilerArgument
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ArgumentsSource
import utils.CompilationTestUtils
import utils.IncrementalCompilationUtils
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Incremental compilation behavior tests for single-module scenarios.
 * Verifies that IC correctly distinguishes between ABI changes and non-ABI changes.
 *
 * Uses parameterized tests to ensure IC behavior is consistent across execution policies.
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
        val manaSource = framework.createKotlinSource(setup.workspace, "ManaSource.kt", """
            package fantasy.resources
            class ManaSource { fun mana(): Int = 1 }
        """)

        val spellcaster = framework.createKotlinSource(setup.workspace, "Spellcaster.kt", """
            package fantasy.casters
            import fantasy.resources.ManaSource
            class Spellcaster { fun use() = ManaSource().mana().toString().length }
        """)

        // Initial incremental compilation
        val operation1 = IncrementalCompilationUtils.createIncrementalJvmOperation(
            toolchain,
            listOf(manaSource, spellcaster),
            setup.outputDirectory,
            setup.icDirectory,
            setup.workspace,
            framework
        )
        val result1 = CompilationTestUtils.runCompile(toolchain, operation1, policy)
        assertEquals(result1, CompilationResult.COMPILATION_SUCCESS, "Initial compilation should succeed")

        val spellcasterBeforeBytes = CompilationTestUtils.readClassOutputBytes(setup.outputDirectory, "fantasy/casters/Spellcaster.class")
        val manaSourceBeforeBytes = CompilationTestUtils.readClassOutputBytes(setup.outputDirectory, "fantasy/resources/ManaSource.class")
        assertTrue(spellcasterBeforeBytes != null && manaSourceBeforeBytes != null, "Classes should exist after initial compilation")

        // Non-ABI change in ManaSource: change method body only (return value 1 -> 2)
        manaSource.writeText("""
            package fantasy.resources
            class ManaSource { fun mana(): Int = 2 }
        """.trimIndent())

        // Recompile with known changes
        val operation2 = IncrementalCompilationUtils.createIncrementalJvmOperation(
            toolchain,
            listOf(manaSource, spellcaster),
            setup.outputDirectory,
            setup.icDirectory,
            setup.workspace,
            framework
        )
        val result2 = CompilationTestUtils.runCompile(toolchain, operation2, policy)
        assertEquals(
            result2,
            CompilationResult.COMPILATION_SUCCESS,
            "Compilation after body-only change should succeed"
        )

        val spellcasterAfterBytes = CompilationTestUtils.readClassOutputBytes(setup.outputDirectory, "fantasy/casters/Spellcaster.class")
        val manaSourceAfterBytes = CompilationTestUtils.readClassOutputBytes(setup.outputDirectory, "fantasy/resources/ManaSource.class")
        assertTrue(spellcasterAfterBytes != null && manaSourceAfterBytes != null, "Classes should still exist after change")

        // Dependent should not be recompiled for body-only change
        assertTrue(
            spellcasterBeforeBytes.contentEquals(spellcasterAfterBytes),
            "Consumer class bytes should remain unchanged for a non-ABI change"
        )
        // Producer should change for body-only change
        assertTrue(
            !manaSourceBeforeBytes.contentEquals(manaSourceAfterBytes),
            "Producer class bytes should change after a body-only modification"
        )
    }

    @ParameterizedTest(name = "{0}: {displayName}")
    @ArgumentsSource(ExecutionPolicyArgumentProvider::class)
    @DisplayName("Kotlin signature change: recompiles dependent")
    fun kotlinSignatureChangeRecompiles(execution: Pair<KotlinToolchains, ExecutionPolicy>) {
        val (toolchain, policy) = execution
        val setup = createTestSetup()

        // Create two source files
        val rune = framework.createKotlinSource(setup.workspace, "Rune.kt", """
            package fantasy.runes
            class Rune { fun power(): Int = 1 }
        """)

        val runeReader = framework.createKotlinSource(setup.workspace, "RuneReader.kt", """
            package fantasy.readers
            import fantasy.runes.Rune
            class RuneReader { fun use() = Rune().power().toString().length }
        """)

        // Initial incremental compilation
        val operation1 = IncrementalCompilationUtils.createIncrementalJvmOperation(
            toolchain,
            listOf(rune, runeReader),
            setup.outputDirectory,
            setup.icDirectory,
            setup.workspace,
            framework
        )
        val result1 = CompilationTestUtils.runCompile(toolchain, operation1, policy)
        assertEquals(result1, CompilationResult.COMPILATION_SUCCESS, "Initial compilation should succeed")

        val runeReaderBeforeBytes = CompilationTestUtils.readClassOutputBytes(setup.outputDirectory, "fantasy/readers/RuneReader.class")
        val runeBeforeBytes = CompilationTestUtils.readClassOutputBytes(setup.outputDirectory, "fantasy/runes/Rune.class")
        assertTrue(runeReaderBeforeBytes != null && runeBeforeBytes != null, "Classes should exist after initial compilation")

        // ABI change in Rune: change signature by adding a parameter (with default)
        rune.writeText("""
            package fantasy.runes
            class Rune { fun power(x: String = "x"): Int = 1 }
        """.trimIndent())

        // Recompile with known changes
        val operation2 = IncrementalCompilationUtils.createIncrementalJvmOperation(
            toolchain,
            listOf(rune, runeReader),
            setup.outputDirectory,
            setup.icDirectory,
            setup.workspace,
            framework
        )
        val result2 = CompilationTestUtils.runCompile(toolchain, operation2, policy)
        assertEquals(result2, CompilationResult.COMPILATION_SUCCESS, "Compilation after ABI change should succeed")

        val runeReaderAfterBytes = CompilationTestUtils.readClassOutputBytes(setup.outputDirectory, "fantasy/readers/RuneReader.class")
        val runeAfterBytes = CompilationTestUtils.readClassOutputBytes(setup.outputDirectory, "fantasy/runes/Rune.class")

        // Both should be recompiled for signature change
        assertTrue(
            !runeReaderBeforeBytes.contentEquals(runeReaderAfterBytes),
            "Consumer class should be recompiled after producer ABI change"
        )
        assertTrue(
            !runeBeforeBytes.contentEquals(runeAfterBytes),
            "Producer class should be recompiled due to its signature change"
        )
    }

}


