package com.gpmapper.app.poc

import java.util.concurrent.CopyOnWriteArrayList

class LatencyRecorder {

    data class Sample(
        val testName: String,
        val createdNs: Long,
        val injectInvokeNs: Long,
        val injectReturnNs: Long,
        val receiverTimestampNs: Long,
        val binderReturnUs: Float,
        val e2eUs: Float,
        val e2eAvailable: Boolean
    )

    data class Stats(
        val testName: String,
        val count: Int,
        val avgBinderReturnUs: Float,
        val p50BinderReturnUs: Float,
        val p95BinderReturnUs: Float,
        val p99BinderReturnUs: Float,
        val minBinderReturnUs: Float,
        val maxBinderReturnUs: Float
    ) {
        override fun toString(): String {
            return buildString {
                appendLine("=== Binder Latency: $testName ($count samples) ===")
                appendLine("avg=%.1f us p50=%.1f us p95=%.1f us p99=%.1f us min=%.1f us max=%.1f us".format(
                    avgBinderReturnUs, p50BinderReturnUs, p95BinderReturnUs, p99BinderReturnUs,
                    minBinderReturnUs, maxBinderReturnUs
                ))
            }
        }
    }

    private val samples = CopyOnWriteArrayList<Sample>()

    fun record(sample: Sample) {
        samples.add(sample)
    }

    fun recordFromTestResult(result: InjectionTestRunner.LatencySample) {
        samples.add(Sample(
            testName = result.testName,
            createdNs = result.createdNs,
            injectInvokeNs = result.injectInvokeNs,
            injectReturnNs = result.injectReturnNs,
            receiverTimestampNs = result.receiverTimestampNs,
            binderReturnUs = result.binderReturnUs,
            e2eUs = result.e2eUs,
            e2eAvailable = result.e2eAvailable
        ))
    }

    fun getStats(testName: String? = null): List<Stats> {
        val filtered = if (testName != null) {
            samples.filter { it.testName == testName }
        } else {
            samples.toList()
        }

        val grouped = filtered.groupBy { it.testName }

        return grouped.map { (name, group) ->
            val binderReturns = group.map { it.binderReturnUs }.sorted()

            Stats(
                testName = name,
                count = group.size,
                avgBinderReturnUs = binderReturns.average().toFloat(),
                p50BinderReturnUs = percentile(binderReturns, 50f),
                p95BinderReturnUs = percentile(binderReturns, 95f),
                p99BinderReturnUs = percentile(binderReturns, 99f),
                minBinderReturnUs = binderReturns.minOrNull() ?: 0f,
                maxBinderReturnUs = binderReturns.maxOrNull() ?: 0f
            )
        }
    }

    fun clear() {
        samples.clear()
    }

    fun getAllSamples(): List<Sample> = samples.toList()

    private fun percentile(sorted: List<Float>, p: Float): Float {
        if (sorted.isEmpty()) return 0f
        val index = (p / 100f * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }
}
