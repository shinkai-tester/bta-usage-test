package framework

import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.trackers.BuildMetricsCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

/**
 * Thread-safe metrics collector for tests.
 */
@OptIn(ExperimentalBuildToolsApi::class)
class TestBuildMetricsCollector : BuildMetricsCollector {
    data class Entry(
        val name: String,
        val type: BuildMetricsCollector.ValueType,
        val value: Long,
    )

    private data class Key(val name: String, val type: BuildMetricsCollector.ValueType)

    private val counters = ConcurrentHashMap<Key, LongAdder>()

    override fun collectMetric(
        name: String,
        type: BuildMetricsCollector.ValueType,
        value: Long,
    ) {
        counters.computeIfAbsent(Key(name, type)) { LongAdder() }.add(value)
    }

    fun all(): List<Entry> = counters.entries
        .map { (key, adder) -> Entry(key.name, key.type, adder.sum()) }
        .sortedWith(compareBy<Entry> { it.name }.thenBy { it.type.toString() })
}