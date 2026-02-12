package support

import BtaTestFacade
import org.jetbrains.kotlin.buildtools.api.ExecutionPolicy
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.junit.jupiter.api.Named
import org.junit.jupiter.api.Named.named
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.ArgumentsProvider
import org.junit.jupiter.params.support.ParameterDeclarations
import java.util.stream.Stream

/**
 * Provides execution policy variations (in-process and daemon) for parameterized tests.
 *
 * This allows tests to verify behavior across different execution strategies without code duplication.
 * Each test receives a pair of (KotlinToolchains, ExecutionPolicy) with a descriptive name.
 */
@OptIn(ExperimentalBuildToolsApi::class)
class ExecutionPolicyArgumentProvider : ArgumentsProvider {

    override fun provideArguments(parameters: ParameterDeclarations, context: ExtensionContext): Stream<out Arguments> {
        return namedExecutionPolicyArguments().map { Arguments.of(it) }.stream()
    }

    companion object {
        fun namedExecutionPolicyArguments(): List<Named<Pair<KotlinToolchains, ExecutionPolicy>>> {
            val framework = BtaTestFacade()
            val toolchain = framework.loadToolchain()

            return listOf(
                named(
                    "IN-PROCESS",
                    toolchain to toolchain.createInProcessExecutionPolicy()
                ),
                named(
                    "DAEMON",
                    toolchain to toolchain.daemonExecutionPolicyBuilder().build()
                )
            )
        }
    }
}
