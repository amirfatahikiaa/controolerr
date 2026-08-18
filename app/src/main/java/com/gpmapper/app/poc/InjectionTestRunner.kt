package com.gpmapper.app.poc

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class InjectionTestRunner(
    private val canvas: VisualTouchCanvas,
    private val onResult: (TestResult) -> Unit,
    private val onLatencySample: (LatencySample) -> Unit
) {

    companion object {
        private const val TAG = "InjectionTestRunner"
        private const val INJECT_DELAY_MS = 80L
    }

    data class TestResult(
        val testName: String,
        val timestamp: Long,
        val steps: List<StepResult>,
        val overallSuccess: Boolean,
        val classification: Classification
    )

    data class StepResult(
        val name: String,
        val success: Boolean,
        val stage: String,
        val details: String,
        val exception: Exception? = null
    )

    enum class Classification {
        VERIFIED,
        PARTIALLY_VERIFIED,
        UNVERIFIED,
        FAILED,
        BLOCKED
    }

    data class LatencySample(
        val testName: String,
        val createdNs: Long,
        val injectInvokeNs: Long,
        val injectReturnNs: Long,
        val receiverTimestampNs: Long,
        val binderReturnUs: Float,
        val e2eUs: Float
    )

    private val handler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val results = CopyOnWriteArrayList<TestResult>()
    private var binderHelper = IInputManagerBinderHelper

    private var iInputManager: Any? = null
    private var injectMethod: java.lang.reflect.Method? = null

    fun isRunning(): Boolean = running.get()

    fun getResults(): List<TestResult> = results.toList()

    fun runAllTests() {
        if (running.compareAndSet(false, true)) {
            results.clear()
            handler.post { runTestSequence() }
        }
    }

    private fun runTestSequence() {
        Thread {
            try {
                Log.i(TAG, "=== Starting Injection Test Sequence ===")

                val acquireResult = binderHelper.acquireInputManagerBinder()
                iInputManager = acquireResult.iInputManager
                injectMethod = acquireResult.injectMethod

                val binderStep = StepResult(
                    name = "Binder Acquisition",
                    success = acquireResult.success,
                    stage = "BINDER_ACQUIRE",
                    details = buildString {
                        append("Raw: ${acquireResult.binder != null}")
                        append(" | Wrapped: ${acquireResult.wrappedBinder != null}")
                        append(" | IInputManager: ${acquireResult.iInputManager != null}")
                        append(" | IInputManager class: ${acquireResult.iInputManager?.javaClass?.name}")
                        append(" | injectMethod: ${acquireResult.injectMethod != null}")
                        if (!acquireResult.success) append(" | ERROR: ${acquireResult.error}")
                    }
                )

                if (!acquireResult.success) {
                    val result = TestResult(
                        testName = "Binder Acquisition",
                        timestamp = System.currentTimeMillis(),
                        steps = listOf(binderStep),
                        overallSuccess = false,
                        classification = Classification.BLOCKED
                    )
                    results.add(result)
                    onResult(result)
                    return@Thread
                }

                onResult(TestResult(
                    testName = "Binder Acquisition",
                    timestamp = System.currentTimeMillis(),
                    steps = listOf(binderStep),
                    overallSuccess = true,
                    classification = Classification.UNVERIFIED
                ))

                Log.i(TAG, "Binder acquired. IInputManager=${iInputManager?.javaClass?.name}")
                Thread.sleep(500)
                runTapTest()

                Thread.sleep(1000)
                Log.i(TAG, "Running swipe test...")
                runSwipeTest()

                Thread.sleep(1000)
                Log.i(TAG, "Running two-pointer test...")
                runTwoPointerTest()

                Log.i(TAG, "=== Test Sequence Complete ===")
            } catch (e: Exception) {
                Log.e(TAG, "Test sequence crashed", e)
                val crashResult = TestResult(
                    testName = "TEST_SEQUENCE_CRASH",
                    timestamp = System.currentTimeMillis(),
                    steps = listOf(StepResult(
                        name = "Unhandled exception",
                        success = false,
                        stage = "CRASH",
                        details = "${e.javaClass.name}: ${e.message}",
                        exception = e
                    )),
                    overallSuccess = false,
                    classification = Classification.FAILED
                )
                results.add(crashResult)
                onResult(crashResult)
            } finally {
                running.set(false)
            }
        }.start()
    }

    private fun injectEvent(event: MotionEvent): IInputManagerBinderHelper.InjectionResult {
        val mgr = iInputManager
        val method = injectMethod
        if (mgr == null || method == null) {
            return IInputManagerBinderHelper.InjectionResult(
                "NO_PROXY", false, 0, null,
                "IInputManager proxy not available (mgr=$mgr, method=$method)"
            )
        }
        return binderHelper.injectViaProxy(mgr, method, event)
    }

    private fun runTapTest() {
        val testStart = System.currentTimeMillis()
        val steps = mutableListOf<StepResult>()

        val downTime = SystemClock.uptimeMillis()
        val createTs = System.nanoTime()
        val downEvent = binderHelper.createMotionEvent(
            MotionEvent.ACTION_DOWN, 540f, 1200f,
            downTime, downTime, 0
        )

        steps.add(StepResult("Create DOWN event", true, "CREATE", "action=0x${Integer.toHexString(downEvent.actionMasked)}"))

        val injectTs = System.nanoTime()
        val downResult = injectEvent(downEvent)

        steps.add(StepResult(
            "Inject DOWN via IInputManager proxy",
            downResult.success,
            downResult.stage,
            downResult.errorMessage ?: "returned ${downResult.success}",
            downResult.exception
        ))

        Thread.sleep(INJECT_DELAY_MS)

        val upEvent = binderHelper.createMotionEvent(
            MotionEvent.ACTION_UP, 540f, 1200f,
            downTime, downTime + INJECT_DELAY_MS, 0
        )

        val upResult = injectEvent(upEvent)

        steps.add(StepResult(
            "Inject UP via IInputManager proxy",
            upResult.success,
            upResult.stage,
            upResult.errorMessage ?: "returned ${upResult.success}",
            upResult.exception
        ))

        val receiverEvents = canvas.getRecords().filter {
            it.wallClockMs >= testStart && it.isInjected
        }

        val receiverStep = StepResult(
            "Receiver-side verification",
            receiverEvents.isNotEmpty(),
            "RECEIVE_CHECK",
            "Received ${receiverEvents.size} injected events on canvas"
        )
        steps.add(receiverStep)

        val allSuccess = steps.all { it.success }
        val receiverGotEvents = receiverEvents.isNotEmpty()

        val classification = when {
            allSuccess && receiverGotEvents -> Classification.VERIFIED
            allSuccess && !receiverGotEvents -> Classification.UNVERIFIED
            downResult.success && !receiverGotEvents -> Classification.PARTIALLY_VERIFIED
            downResult.errorMessage?.contains("SecurityException") == true -> Classification.BLOCKED
            downResult.errorMessage?.contains("SELinux") == true -> Classification.BLOCKED
            else -> Classification.FAILED
        }

        if (downResult.success && receiverEvents.isNotEmpty()) {
            val sample = LatencySample(
                testName = "tap",
                createdNs = createTs,
                injectInvokeNs = injectTs,
                injectReturnNs = downResult.binderReturnTimestampNs,
                receiverTimestampNs = receiverEvents.first().timestampNs,
                binderReturnUs = (downResult.binderReturnTimestampNs - injectTs) / 1000f,
                e2eUs = (receiverEvents.first().timestampNs - createTs) / 1000f
            )
            onLatencySample(sample)
        }

        val testResult = TestResult(
            testName = "A: Single Tap (DOWN -> UP)",
            timestamp = testStart,
            steps = steps,
            overallSuccess = allSuccess,
            classification = classification
        )

        results.add(testResult)
        onResult(testResult)

        downEvent.recycle()
        upEvent.recycle()
    }

    private fun runSwipeTest() {
        val testStart = System.currentTimeMillis()
        val steps = mutableListOf<StepResult>()

        val events = binderHelper.createSwipeMotionEvents(
            300f, 800f, 780f, 800f,
            durationMs = 50, stepCount = 5
        )

        steps.add(StepResult("Create swipe events", true, "CREATE", "${events.size} events created"))

        var allInjected = true
        val firstResult: IInputManagerBinderHelper.InjectionResult?
        var lastResult: IInputManagerBinderHelper.InjectionResult? = null

        val createTs = System.nanoTime()
        val injectTs = System.nanoTime()

        if (events.isNotEmpty()) {
            val firstEvent = events[0]
            val result = injectEvent(firstEvent)
            firstResult = result
            if (!result.success) allInjected = false

            for (i in 1 until events.size) {
                Thread.sleep(INJECT_DELAY_MS / events.size)
                val moveResult = injectEvent(events[i])
                if (!moveResult.success) allInjected = false
                lastResult = moveResult
            }
        } else {
            firstResult = null
        }

        steps.add(StepResult(
            "Inject ${events.size} events",
            allInjected,
            "SWIPE_INJECT",
            "first=${firstResult?.success} last=${lastResult?.success}"
        ))

        Thread.sleep(200)

        val receiverEvents = canvas.getRecords().filter {
            it.wallClockMs >= testStart && it.isInjected
        }

        steps.add(StepResult(
            "Receiver-side verification",
            receiverEvents.isNotEmpty(),
            "RECEIVE_CHECK",
            "Received ${receiverEvents.size} injected events"
        ))

        val allSuccess = steps.all { it.success }
        val receiverGotEvents = receiverEvents.isNotEmpty()

        val classification = when {
            allSuccess && receiverGotEvents -> Classification.VERIFIED
            allSuccess && !receiverGotEvents -> Classification.UNVERIFIED
            firstResult?.success == true && !receiverGotEvents -> Classification.PARTIALLY_VERIFIED
            firstResult?.errorMessage?.contains("SecurityException") == true -> Classification.BLOCKED
            else -> Classification.FAILED
        }

        if (firstResult?.success == true && receiverEvents.isNotEmpty()) {
            val sample = LatencySample(
                testName = "swipe",
                createdNs = createTs,
                injectInvokeNs = injectTs,
                injectReturnNs = lastResult?.binderReturnTimestampNs ?: 0,
                receiverTimestampNs = receiverEvents.last().timestampNs,
                binderReturnUs = ((lastResult?.binderReturnTimestampNs ?: 0) - injectTs) / 1000f,
                e2eUs = (receiverEvents.last().timestampNs - createTs) / 1000f
            )
            onLatencySample(sample)
        }

        val testResult = TestResult(
            testName = "B: Swipe (DOWN -> 3x MOVE -> UP)",
            timestamp = testStart,
            steps = steps,
            overallSuccess = allSuccess,
            classification = classification
        )

        results.add(testResult)
        onResult(testResult)

        events.forEach { it.recycle() }
    }

    private fun runTwoPointerTest() {
        val testStart = System.currentTimeMillis()
        val steps = mutableListOf<StepResult>()

        val events = binderHelper.createTwoPointerMotionEvents(
            400f, 1000f, 680f, 1000f,
            durationMs = 50
        )

        steps.add(StepResult("Create two-pointer events", true, "CREATE", "${events.size} events"))

        var allInjected = true
        val firstResult: IInputManagerBinderHelper.InjectionResult?

        val injectTs = System.nanoTime()

        if (events.isNotEmpty()) {
            val firstEvent = events[0]
            val result = injectEvent(firstEvent)
            firstResult = result
            if (!result.success) allInjected = false

            for (i in 1 until events.size) {
                Thread.sleep(INJECT_DELAY_MS / events.size)
                val r = injectEvent(events[i])
                if (!r.success) allInjected = false
            }
        } else {
            firstResult = null
        }

        steps.add(StepResult(
            "Inject ${events.size} events",
            allInjected,
            "MULTI_INJECT",
            "first=${firstResult?.success}"
        ))

        Thread.sleep(200)

        val receiverEvents = canvas.getRecords().filter {
            it.wallClockMs >= testStart && it.isInjected
        }

        steps.add(StepResult(
            "Receiver-side verification",
            receiverEvents.isNotEmpty(),
            "RECEIVE_CHECK",
            "Received ${receiverEvents.size} injected events"
        ))

        val allSuccess = steps.all { it.success }
        val receiverGotEvents = receiverEvents.isNotEmpty()

        val classification = when {
            allSuccess && receiverGotEvents -> Classification.VERIFIED
            allSuccess && !receiverGotEvents -> Classification.UNVERIFIED
            firstResult?.success == true && !receiverGotEvents -> Classification.PARTIALLY_VERIFIED
            firstResult?.errorMessage?.contains("SecurityException") == true -> Classification.BLOCKED
            else -> Classification.FAILED
        }

        val testResult = TestResult(
            testName = "C: Two-Pointer (DOWN -> PDOWN -> MOVE -> PUP -> UP)",
            timestamp = testStart,
            steps = steps,
            overallSuccess = allSuccess,
            classification = classification
        )

        results.add(testResult)
        onResult(testResult)

        events.forEach { it.recycle() }
    }
}
