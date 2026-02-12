import support.ExecutionPolicyArgumentProvider
import support.TestBase
import framework.TestBuildMetricsCollector
import org.jetbrains.kotlin.buildtools.api.BuildOperation.Companion.METRICS_COLLECTOR
import org.jetbrains.kotlin.buildtools.api.ExecutionPolicy
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ArgumentsSource
import utils.CompilationTestUtils
import utils.IncrementalCompilationUtils
import kotlin.test.assertTrue

@OptIn(ExperimentalBuildToolsApi::class)
class CompilationMetricsTest : TestBase() {

    @ParameterizedTest(name = "{0}: {displayName}")
    @ArgumentsSource(ExecutionPolicyArgumentProvider::class)
    @DisplayName("Non-incremental compilation collects metrics")
    fun nonIncrementalCompilationMetrics(execution: Pair<KotlinToolchains, ExecutionPolicy>) {
        val (toolchain, policy) = execution
        val setup = createTestSetup()
        val source = framework.createKotlinSource(setup.workspace, "Hello.kt", $$"""
            fun hello(name: String) = "Hello, $name"
        """
        )

        val baseOp = framework.createJvmCompilationOperation(toolchain, listOf(source), setup.outputDirectory)
        val metricsCollector = TestBuildMetricsCollector()
        val opWithMetrics = baseOp.toBuilder().apply {
            this[METRICS_COLLECTOR] = metricsCollector
        }.build()

        val result = CompilationTestUtils.runCompile(toolchain, opWithMetrics, policy)

        assertCompilationSuccessful(result)
        assertHasCompilationMetrics(metricsCollector)
    }

    @ParameterizedTest(name = "{0}: {displayName}")
    @ArgumentsSource(ExecutionPolicyArgumentProvider::class)
    @DisplayName("Incremental compilation collects metrics")
    fun incrementalCompilationMetrics(execution: Pair<KotlinToolchains, ExecutionPolicy>) {
        val (toolchain, policy) = execution
        val setup = createTestSetup()

        val source = framework.createKotlinSource(setup.workspace, "Counter.kt", """
            class Counter { fun next(x: Int) = x + 1 }
        """)

        val baseOp = IncrementalCompilationUtils.createIncrementalJvmOperation(
            toolchain,
            listOf(source),
            setup.outputDirectory,
            setup.icDirectory,
            setup.workspace,
            framework
        )
        val metricsCollector = TestBuildMetricsCollector()
        val opWithMetrics = baseOp.toBuilder().apply {
            this[METRICS_COLLECTOR] = metricsCollector
        }.build()

        val result = CompilationTestUtils.runCompile(toolchain, opWithMetrics, policy)

        assertCompilationSuccessful(result)
        assertHasCompilationMetrics(metricsCollector)
    }


    private fun assertHasCompilationMetrics(metricsCollector: TestBuildMetricsCollector) {
        val metricNames = metricsCollector.all().map { it.name }.toSet()
        assertTrue(metricNames.isNotEmpty(), "Expected metrics to be collected, but got none")
        val missingNames = baseRequiredNames - metricNames
        val missingPrefixes = baseRequiredPrefixes.filter { prefix ->
            metricNames.none { it == prefix || it.startsWith("$prefix ->") }
        }
        assertTrue(
            missingNames.isEmpty() && missingPrefixes.isEmpty(),
            "Missing required metrics: $missingNames. Missing required prefixes: $missingPrefixes. " +
                "Actual metrics: $metricNames"
        )
    }

    companion object {
        private val baseRequiredPrefixes = setOf(
            "Run compilation",
            "Run compilation -> Sources compilation round",
            "Run compilation -> Sources compilation round -> Compiler time",
        )

        private val baseRequiredNames = setOf(
            "Run compilation -> Sources compilation round -> Compiler time -> Compiler code analysis",
            "Run compilation -> Sources compilation round -> Compiler time -> Compiler code generation",
            "Run compilation -> Sources compilation round -> Compiler time -> Compiler code generation -> Compiler IR lowering",
            "Run compilation -> Sources compilation round -> Compiler time -> Compiler code generation -> Compiler backend",
            "Run compilation -> Sources compilation round -> Compiler time -> Compiler initialization time",
            "Run compilation -> Sources compilation round -> Compiler time -> Compiler translation to IR",
            "Total compiler iteration",
            "Total compiler iteration -> Analysis lines per second",
            "Total compiler iteration -> Code generation lines per second",
            "Total compiler iteration -> Number of lines analyzed",
        )
    }
}
