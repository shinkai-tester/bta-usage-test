import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.arguments.ExperimentalCompilerArgument
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Incremental compilation behavior tests:
 * - Multi-module Kotlin-only projects: downstream recompiles on upstream ABI changes.
 * - Java/Kotlin interop across module/classpath boundary with precise and non-precise Java tracking.
 * - Kotlin-only changes that affect or don’t affect ABI and their recompilation impact.
 * 
 * The scenarios are named and documented so readers can quickly see what behavior is verified.
 */
@OptIn(ExperimentalBuildToolsApi::class, ExperimentalCompilerArgument::class)
@Order(3)
class IncrementalCompilationTest : TestBase() {


    @Test
    @DisplayName("Two modules: lib ABI change recompiles app")
    fun twoModulesLibAbiChangeRecompilesApp() {
        val builder = IncrementalCompilationTestBuilder(framework)
        
        // Set up modules with dependencies
        builder
            .module("spellbook")
                .kotlinSource("Spellbook.kt", """
                    package fantasy.spellbook
                    class Spellbook { fun chant(): String = "Hail" }
                """)
                .and()
            .module("guild")
                .kotlinSource("GuildMessenger.kt", """
                    package fantasy.guild
                    import fantasy.spellbook.Spellbook
                    class GuildMessenger { fun call() = Spellbook().chant().length }
                """)
                .dependsOn("spellbook")
        
        // Initial compilation
        val initialResults = builder.compileAll()
        assertTrue(initialResults.allSuccessful(), "Initial compilation should succeed")
        
        val appBefore = builder.getClassOutputState("guild", "fantasy/guild/GuildMessenger.class")
        assertTrue(appBefore.fileExists, "Consumer class should exist after initial compilation")
        
        // ABI change in spellbook: change return type String -> CharSequence (consumer code stays valid)
        val updateResults = builder.updateSourceAndRecompile("spellbook", "Spellbook.kt", """
            package fantasy.spellbook
            class Spellbook { fun chant(): CharSequence = "Hail" }
        """)
        assertTrue(updateResults.allSuccessful(), "Compilation after library ABI change should succeed")
        
        val appAfter = builder.getClassOutputState("guild", "fantasy/guild/GuildMessenger.class")
        assertTrue((appAfter.lastModified ?: 0L) > (appBefore.lastModified ?: 0L), 
                  "Consumer class should be recompiled after producer ABI change")
    }

    @Test
    @DisplayName("Three modules: transitive changes affect all")
    fun threeModulesTransitiveChanges() {
        val builder = IncrementalCompilationTestBuilder(framework)
        
        // Set up three modules with transitive dependencies: A -> B -> C
        builder
            .module("arcana")
                .kotlinSource("ArcaneCore.kt", """
                    package fantasy.arcana
                    interface ArcaneCore { 
                        fun rune(): String 
                        fun power(): Int = 1
                    }
                """)
                .and()
            .module("mages")
                .kotlinSource("MageService.kt", """
                    package fantasy.mages
                    import fantasy.arcana.ArcaneCore
                    class MageService : ArcaneCore { 
                        override fun rune(): String = "sigil-" + power()
                    }
                """)
                .dependsOn("arcana")
                .and()
            .module("quest")
                .kotlinSource("QuestFacade.kt", """
                    package fantasy.quest
                    import fantasy.mages.MageService
                    class QuestFacade { fun use() = MageService().rune().length }
                """)
                .dependsOn("mages")
        
        // Initial compilation
        val initialResults = builder.compileAll()
        assertTrue(initialResults.allSuccessful(), "Initial compilation should succeed")
        
        val serviceBefore = builder.getClassOutputState("mages", "fantasy/mages/MageService.class")
        val appBefore = builder.getClassOutputState("quest", "fantasy/quest/QuestFacade.class")
        assertTrue(serviceBefore.fileExists && appBefore.fileExists, "Dependent classes should exist after initial compilation")
        
        // Change in ArcaneCore: modify default method implementation (ABI change - should affect downstream)
        val changeResults = builder.updateSourceAndRecompile("arcana", "ArcaneCore.kt", """
            package fantasy.arcana
            interface ArcaneCore { 
                fun rune(): String 
                fun power(): Int = 2
            }
        """)
        assertTrue(changeResults.allSuccessful(), "Compilation after upstream ABI change should succeed")
        
        val serviceAfter = builder.getClassOutputState("mages", "fantasy/mages/MageService.class")
        val appAfter = builder.getClassOutputState("quest", "fantasy/quest/QuestFacade.class")
        
        // Both downstream classes should be recompiled due to the ABI change in ArcaneCore
        assertTrue((serviceAfter.lastModified ?: 0L) > (serviceBefore.lastModified ?: 0L), 
                  "Intermediate module class should be recompiled after upstream ABI change")
        assertTrue((appAfter.lastModified ?: 0L) > (appBefore.lastModified ?: 0L), 
                  "Top-level consumer class should be recompiled transitively due to upstream ABI change")
    }

    @Test
    @DisplayName("Java non-ABI change: no Kotlin recompile with precise tracking")
    // Note: Although we create just two folders (java/ and kotlin/), we compile them as two logical modules:
    // - Java is compiled with javac into its own output (javaOut).
    // - Kotlin is compiled separately with javaOut on its classpath.
    // This models the scenario PRECISE_JAVA_TRACKING is designed for (classpath changes), avoiding mixed-source rounds.
    fun javaNonAbiWithPreciseTracking() {
        val scenario = JavaInteropTestScenario(framework)
            .withJavaSource("Oracle.java", """
                package fantasy.oracle;
                public class Oracle { public static String prophecy() { return "A"; } }
            """)
            .withKotlinSource("Seeker.kt", """
                package fantasy.seeker
                class Seeker { fun call() = fantasy.oracle.Oracle.prophecy().length }
            """)
        
        // Initial compilation with precise Java tracking enabled
        val initialResult = scenario.compileInitial(preciseJavaTracking = true)
        assertEquals(CompilationResult.COMPILATION_SUCCESS, initialResult.compilationResult)
        
        val beforeBytes = scenario.readKotlinClassBytes("fantasy/seeker/Seeker.class")
        assertTrue(beforeBytes != null, "Kotlin class should exist after initial compilation")
        
        // Non-ABI Java change: change string literal from "A" to "B"
        val updateResult = scenario.updateJavaAndRecompile("""
            package fantasy.oracle;
            public class Oracle { public static String prophecy() { return "B"; } }
        """, preciseJavaTracking = true)
        assertEquals(CompilationResult.COMPILATION_SUCCESS, updateResult.compilationResult)
        
        val afterBytes = scenario.readKotlinClassBytes("fantasy/seeker/Seeker.class")
        assertTrue(afterBytes != null, "Kotlin class should still exist after Java change")
        assertTrue(beforeBytes.contentEquals(afterBytes), "Kotlin class should remain unchanged with precise Java tracking")
    }

    @Test
    @DisplayName("Java non-ABI change: Kotlin recompile without precise tracking")
    // See note above: this is a two-module (classpath) setup, not mixed sources in one module.
    fun javaNonAbiNoPreciseTracking() {
        val scenario = JavaInteropTestScenario(framework)
            .withJavaSource("Oracle2.java", """
                package fantasy.oracle2;
                public class Oracle2 { public static String prophecy() { return "A"; } }
            """)
            .withKotlinSource("Seeker2.kt", """
                package fantasy.seeker2
                class Seeker2 { fun call() = fantasy.oracle2.Oracle2.prophecy().length }
            """)
        
        // Initial compilation with precise Java tracking disabled
        val initialResult = scenario.compileInitial(preciseJavaTracking = false)
        assertEquals(CompilationResult.COMPILATION_SUCCESS, initialResult.compilationResult)
        
        val before = scenario.getKotlinClassState("fantasy/seeker2/Seeker2.class")
        assertTrue(before.fileExists, "Kotlin class should exist after initial compilation")
        
        // Non-ABI Java change: change string literal from "A" to "B"
        val updateResult = scenario.updateJavaAndRecompile("""
            package fantasy.oracle2;
            public class Oracle2 { public static String prophecy() { return "B"; } }
        """, preciseJavaTracking = false)
        assertEquals(CompilationResult.COMPILATION_SUCCESS, updateResult.compilationResult)
        
        val after = scenario.getKotlinClassState("fantasy/seeker2/Seeker2.class")
        assertTrue((after.lastModified ?: 0L) > (before.lastModified ?: 0L), 
                  "Kotlin should be recompiled when precise Java tracking is disabled")
    }

    @Test
    @DisplayName("Java ABI change: Kotlin recompile with precise tracking")
    fun javaAbiChangeRecompilesWithPreciseTracking() {
        val scenario = JavaInteropTestScenario(framework)
            .withJavaSource("Oracle3.java", """
                package fantasy.oracle3;
                public class Oracle3 { public static String prophecy() { return "A"; } }
            """)
            .withKotlinSource("Seeker3.kt", """
                package fantasy.seeker3
                class Seeker3 { fun call() = fantasy.oracle3.Oracle3.prophecy().length }
            """)

        // Initial compilation with precise Java tracking enabled
        val initialResult = scenario.compileInitial(preciseJavaTracking = true)
        assertEquals(CompilationResult.COMPILATION_SUCCESS, initialResult.compilationResult)

        val before = scenario.getKotlinClassState("fantasy/seeker3/Seeker3.class")
        assertTrue(before.fileExists, "Kotlin class should exist after initial compilation")

        // ABI Java change: change return type from String to CharSequence (binary signature change)
        val updateResult = scenario.updateJavaAndRecompile(
            """
                package fantasy.oracle3;
                public class Oracle3 { public static CharSequence prophecy() { return "A"; } }
            """,
            preciseJavaTracking = true
        )
        assertEquals(CompilationResult.COMPILATION_SUCCESS, updateResult.compilationResult)

        val after = scenario.getKotlinClassState("fantasy/seeker3/Seeker3.class")
        assertTrue((after.lastModified ?: 0L) > (before.lastModified ?: 0L),
            "Consumer class should be recompiled when Java ABI changes, even with precise tracking enabled")
    }

    @Test
    @DisplayName("Kotlin body change: no dependent recompile")
    fun kotlinBodyChangeNoRecompile() {
        val builder = IncrementalCompilationTestBuilder(framework)
        
        // Set up single module with two source files
        builder.module("main")
            .kotlinSource("ManaSource.kt", """
                package fantasy.resources
                class ManaSource { fun mana(): Int = 1 }
            """)
            .kotlinSource("Spellcaster.kt", """
                package fantasy.casters
                import fantasy.resources.ManaSource
                class Spellcaster { fun use() = ManaSource().mana().toString().length }
            """)
        
        // Initial compilation
        val initialResults = builder.compileAll()
        assertTrue(initialResults.allSuccessful(), "Initial compilation should succeed")
        
        val useBeforeBytes = builder.readClassBytes("main", "fantasy/casters/Spellcaster.class")
        val aBeforeBytes = builder.readClassBytes("main", "fantasy/resources/ManaSource.class")
        assertTrue(useBeforeBytes != null && aBeforeBytes != null, "Classes should exist after initial compilation")
        
        // Non-ABI change in ManaSource: change method body only (return value 1 -> 2)
        val updateResults = builder.updateSourceAndRecompile("main", "ManaSource.kt", """
            package fantasy.resources
            class ManaSource { fun mana(): Int = 2 }
        """)
        assertTrue(updateResults.allSuccessful(), "Compilation after body-only change should succeed")
        
        val useAfterBytes = builder.readClassBytes("main", "fantasy/casters/Spellcaster.class")
        val aAfterBytes = builder.readClassBytes("main", "fantasy/resources/ManaSource.class")
        assertTrue(useAfterBytes != null && aAfterBytes != null, "Classes should still exist after change")
        
        // Dependent should not be recompiled for body-only change
        assertTrue(
            useBeforeBytes.contentEquals(useAfterBytes),
                  "Consumer class bytes should remain unchanged for a non-ABI change")
        // Producer should change for body-only change
        assertTrue(!aBeforeBytes.contentEquals(aAfterBytes),
                  "Producer class bytes should change after a body-only modification")
    }

    @Test
    @DisplayName("Kotlin signature change: recompiles dependent")
    fun kotlinSignatureChangeRecompiles() {
        val builder = IncrementalCompilationTestBuilder(framework)
        
        // Set up single module with two source files
        builder.module("main")
            .kotlinSource("Rune.kt", """
                package fantasy.runes
                class Rune { fun power(): Int = 1 }
            """)
            .kotlinSource("RuneReader.kt", """
                package fantasy.readers
                import fantasy.runes.Rune
                class RuneReader { fun use() = Rune().power().toString().length }
            """)
        
        // Initial compilation
        val initialResults = builder.compileAll()
        assertTrue(initialResults.allSuccessful(), "Initial compilation should succeed")
        
        val useBefore = builder.getClassOutputState("main", "fantasy/readers/RuneReader.class")
        val aBefore = builder.getClassOutputState("main", "fantasy/runes/Rune.class")
        assertTrue(useBefore.fileExists && aBefore.fileExists, "Classes should exist after initial compilation")
        
        // ABI change in Rune: change signature by adding a parameter (with default)
        // Old call site `Rune().power()` now relies on default param bridge -> requires recompilation of the consumer
        val updateResults = builder.updateSourceAndRecompile("main", "Rune.kt", """
            package fantasy.runes
            class Rune { fun power(x: String = "x"): Int = 1 }
        """)
        assertTrue(updateResults.allSuccessful(), "Compilation after ABI change should succeed")
        
        val useAfter = builder.getClassOutputState("main", "fantasy/readers/RuneReader.class")
        val aAfter = builder.getClassOutputState("main", "fantasy/runes/Rune.class")
        assertTrue((useAfter.lastModified ?: 0L) > (useBefore.lastModified ?: 0L), 
                  "Consumer class should be recompiled after producer ABI change")
        assertTrue((aAfter.lastModified ?: 0L) > (aBefore.lastModified ?: 0L), 
                  "Producer class should be recompiled due to its signature change")
    }

}


