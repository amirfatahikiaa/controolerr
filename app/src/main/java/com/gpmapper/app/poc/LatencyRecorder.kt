package com.gpmapper.app.poc

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.sqrt

class LatencyRecorder {

    data class Sample(
        val testName: String,
        val createdNs: Long,
        val injectInvokeNs: Long,
        val injectReturnNs: Long,
        val receiverTimestampNs: Long,
        val binderReturnUs: Float,
        val e2eUs: Float
    )

    data class Stats(
        val testName: String,
        val count: Int,
        val avgBinderReturnUs: Float,
        val p50BinderReturnUs: Float,
        val p95BinderReturnUs: Float,
        val p99BinderReturnUs: Float,
        val minBinderReturnUs: Float,
        val maxBinderReturnUs: Float,
        val avgE2EUs: Float,
        val p50E2EUs: Float,
        val p95E2EUs: Float,
        val minE2EUs: Float,
        val maxE2EUs: Float
    ) {
        override fun toString(): String {
            return buildString {
                appendLine("=== Latency Stats: $testName ($count samples) ===")
                appendLine("Binder Return (us): avg=%.1f p50=%.1f p95=%.1f p99=%.1f min=%.1f max=%.1f".format(
                    avgBinderReturnUs, p50BinderReturnUs, p95BinderReturnUs, p99BinderReturnUs,
                    minBinderReturnUs, maxBinderReturnUs
                ))
                appendLine("End-to-End  (us): avg=%.1f p50=%.1f p95=%.1f min=%.1f max=%.1f".format(
                    avgE2EUs, p50E2EUs, p95E2EUs, minE2EUs, maxE2EUs
                ))
                appendLine("NOTE: Binder-return latency != end-to-end input-to-screen latency.")
                appendLine("Receiver timestamps depend on the canvas touch handler, not the input pipeline.")
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
            e2eUs = result.e2eUs
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
            val e2es = group.map { it.e2eUs }.sorted()

            Stats(
                testName = name,
                count = group.size,
                avgBinderReturnUs = binderReturns.average().toFloat(),
                p50BinderReturnUs = percentile(binderReturns, 50f),
                p95BinderReturnUs = percentile(binderReturns, 95f),
                p99BinderReturnUs = percentile(binderReturns, 99f),
                minBinderReturnUs = binderReturns.minOrNull() ?: 0f,
                maxBinderReturnUs = binderReturns.maxOrNull() ?: 0f,
                avgE2EUs = e2es.average().toFloat(),
                p50E2EUs = percentile(e2es, 50f),
                p95E2EUs = percentile(e2es, 95f),
                minE2EUs = e2es.minOrNull() ?: 0f,
                maxE2EUs = e2es.maxOrNull() ?: 0f
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
