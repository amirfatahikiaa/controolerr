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
    private val onLatencySample: (LatencySample) -> Unit,
    private val onBinderLatency: (Long) -> Unit,
    private val getDisplayId: () -> Int = { 0 },
    private val getWindowInfo: () -> String = { "unknown" },
    private val getReceiverCount: () -> Int = { 0 }
) {

    companion object {
        private const val TAG = "InjectionTestRunner"
        private const val INJECT_DELAY_MS = 80L
        private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
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
        val e2eUs: Float,
        val e2eAvailable: Boolean
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

                val windowInfo = getWindowInfo()
                val displayId = getDisplayId()
                val receiverCount = getReceiverCount()

                val binderStep = StepResult(
                    name = "Stage A: Binder Acquisition",
                    success = acquireResult.success,
                    stage = "A_BINDER",
                    details = buildString {
                        append("Raw: ${acquireResult.binder != null}")
                        append(" | Wrapped: ${acquireResult.wrappedBinder != null}")
                        append(" | IInputManager: ${acquireResult.iInputManager != null}")
                        append(" | proxyClass: ${acquireResult.iInputManager?.javaClass?.name}")
                        append(" | injectMethod: ${acquireResult.injectMethod != null}")
                        append(" | displayId=$displayId")
                        append(" | window=$windowInfo")
                        append(" | receivers=$receiverCount")
                        if (!acquireResult.success) append(" | ERROR: ${acquireResult.error}")
                    }
                )

                if (!acquireResult.success) {
                    val result = TestResult(
                        testName = "Stage A: Binder Acquisition",
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
                    testName = "Stage A: Binder Acquisition",
                    timestamp = System.currentTimeMillis(),
                    steps = listOf(binderStep),
                    overallSuccess = true,
                    classification = Classification.UNVERIFIED
                ))

                Log.i(TAG, "Binder acquired. Running tests...")
                Thread.sleep(500)
                runTapTest()

                Thread.sleep(1000)
                Log.i(TAG, "Running swipe test...")
                runSwipeTest()

                Thread.sleep(1000)
                Log.i(TAG, "Running multi-touch sub-tests...")
                runMultiTouchSubTests()

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

    private fun injectEvent(
        event: MotionEvent,
        mode: Int = INJECT_INPUT_EVENT_MODE_ASYNC
    ): IInputManagerBinderHelper.InjectionResult {
        val mgr = iInputManager
        val method = injectMethod
        if (mgr == null || method == null) {
            return IInputManagerBinderHelper.InjectionResult(
                "A_NO_PROXY", false, 0, 0, null,
                "IInputManager proxy not available (mgr=$mgr, method=$method)"
            )
        }

        Log.i(TAG, MotionEventFactory.diagnoseEvent(event, "PRE-INJECT"))

        return binderHelper.injectViaProxy(mgr, method, event, mode)
    }

    private fun runTapTest() {
        val testStart = System.currentTimeMillis()
        val steps = mutableListOf<StepResult>()

        val receiverBefore = getReceiverCount()

        val downTime = SystemClock.uptimeMillis()
        val createTs = System.nanoTime()
        val downEvent = binderHelper.createMotionEvent(
            MotionEvent.ACTION_DOWN, 540f, 1200f,
            downTime, downTime, 0
        )

        steps.add(StepResult(
            "Stage B: Create DOWN event",
            true, "B_CREATE",
            MotionEventFactory.diagnoseEvent(downEvent, "DOWN").trim()
        ))

        val injectTs = System.nanoTime()
        val downResult = injectEvent(downEvent, INJECT_INPUT_EVENT_MODE_ASYNC)

        steps.add(StepResult(
            "Stage C: injectInputEvent returned",
            downResult.success,
            "C_INJECT",
            "mode=ASYNC(0) returned=${downResult.success} " +
                    "binderLatencyNs=${downResult.binderLatencyNs} " +
                    (downResult.errorMessage ?: ""),
            downResult.exception
        ))

        if (downResult.success) onBinderLatency(downResult.binderLatencyNs)

        Thread.sleep(INJECT_DELAY_MS)

        val upEvent = binderHelper.createMotionEvent(
            MotionEvent.ACTION_UP, 540f, 1200f,
            downTime, downTime + INJECT_DELAY_MS, 0
        )

        val upResult = injectEvent(upEvent, INJECT_INPUT_EVENT_MODE_ASYNC)

        steps.add(StepResult(
            "Stage C: inject UP returned",
            upResult.success,
            "C_INJECT_UP",
            "returned=${upResult.success} binderLatencyNs=${upResult.binderLatencyNs}",
            upResult.exception
        ))

        if (upResult.success) onBinderLatency(upResult.binderLatencyNs)

        Thread.sleep(200)

        val receiverAfter = getReceiverCount()
        val receiverEvents = canvas.getRecords().filter {
            it.wallClockMs >= testStart
        }

        steps.add(StepResult(
            "Stage D: Framework/receiver observation",
            receiverEvents.isNotEmpty(),
            "D_RECEIVE",
            "totalReceived=${receiverEvents.size} " +
                    "receiversBefore=$receiverBefore after=$receiverAfter"
        ))

        val allSuccess = steps.all { it.success }
        val receiverGotEvents = receiverEvents.isNotEmpty()

        val classification = when {
            allSuccess && receiverGotEvents -> Classification.VERIFIED
            allSuccess && !receiverGotEvents -> Classification.UNVERIFIED
            downResult.success && !receiverGotEvents -> Classification.PARTIALLY_VERIFIED
            downResult.errorMessage?.contains("SecurityException") == true -> Classification.BLOCKED
            else -> Classification.FAILED
        }

        if (downResult.success) {
            val binderReturnNs = downResult.binderLatencyNs
            val sample = LatencySample(
                testName = "tap",
                createdNs = createTs,
                injectInvokeNs = injectTs,
                injectReturnNs = downResult.binderReturnTimestampNs,
                receiverTimestampNs = if (receiverEvents.isNotEmpty()) receiverEvents.first().timestampNs else 0,
                binderReturnUs = binderReturnNs / 1000f,
                e2eUs = 0f,
                e2eAvailable = false
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

        steps.add(StepResult(
            "Create swipe events",
            true, "CREATE", "${events.size} events created"
        ))

        var allInjected = true
        var firstResult: IInputManagerBinderHelper.InjectionResult? = null
        var lastResult: IInputManagerBinderHelper.InjectionResult? = null

        if (events.isNotEmpty()) {
            val result = injectEvent(events[0], INJECT_INPUT_EVENT_MODE_ASYNC)
            firstResult = result
            if (!result.success) allInjected = false
            if (result.success) onBinderLatency(result.binderLatencyNs)

            for (i in 1 until events.size) {
                Thread.sleep(INJECT_DELAY_MS / events.size)
                val moveResult = injectEvent(events[i], INJECT_INPUT_EVENT_MODE_ASYNC)
                if (!moveResult.success) allInjected = false
                if (moveResult.success) onBinderLatency(moveResult.binderLatencyNs)
                lastResult = moveResult
            }
        }

        steps.add(StepResult(
            "Inject ${events.size} events",
            allInjected,
            "SWIPE_INJECT",
            "first=${firstResult?.success} last=${lastResult?.success}"
        ))

        Thread.sleep(200)

        val receiverEvents = canvas.getRecords().filter {
            it.wallClockMs >= testStart
        }

        steps.add(StepResult(
            "Receiver-side verification",
            receiverEvents.isNotEmpty(),
            "RECEIVE_CHECK",
            "Received ${receiverEvents.size} events"
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

    private fun runMultiTouchSubTests() {
        val downTime = SystemClock.uptimeMillis()

        runSubTest(
            "D1: DOWN only (single pointer)",
            listOf(
                binderHelper.createMotionEvent(
                    MotionEvent.ACTION_DOWN, 400f, 1000f,
                    downTime, downTime, 0
                )
            ),
            300
        )

        Thread.sleep(500)

        val dt2 = SystemClock.uptimeMillis()
        val events2 = mutableListOf<MotionEvent>()
        events2.add(binderHelper.createMotionEvent(
            MotionEvent.ACTION_DOWN, 400f, 1000f,
            dt2, dt2, 0
        ))
        events2.add(binderHelper.createMultiPointerMotionEvent(
            MotionEvent.ACTION_POINTER_DOWN, 680f, 1000f,
            dt2, dt2 + 10, 1, pointerCount = 2
        ))

        runSubTest(
            "D2: DOWN + POINTER_DOWN (2 pointers)",
            events2,
            300
        )

        Thread.sleep(500)

        val dt3 = SystemClock.uptimeMillis()
        val events3 = mutableListOf<MotionEvent>()
        events3.add(binderHelper.createMotionEvent(
            MotionEvent.ACTION_DOWN, 400f, 1000f,
            dt3, dt3, 0
        ))
        events3.add(binderHelper.createMultiPointerMotionEvent(
            MotionEvent.ACTION_POINTER_DOWN, 680f, 1000f,
            dt3, dt3 + 10, 1, pointerCount = 2
        ))
        events3.add(binderHelper.createMultiPointerMotionEvent(
            MotionEvent.ACTION_MOVE, 410f, 1010f,
            dt3, dt3 + 30, 0, pointerCount = 2
        ))

        runSubTest(
            "D3: DOWN + POINTER_DOWN + MOVE",
            events3,
            300
        )

        Thread.sleep(500)

        val dt4 = SystemClock.uptimeMillis()
        val events4 = mutableListOf<MotionEvent>()
        events4.add(binderHelper.createMotionEvent(
            MotionEvent.ACTION_DOWN, 400f, 1000f,
            dt4, dt4, 0
        ))
        events4.add(binderHelper.createMultiPointerMotionEvent(
            MotionEvent.ACTION_POINTER_DOWN, 680f, 1000f,
            dt4, dt4 + 10, 1, pointerCount = 2
        ))
        events4.add(binderHelper.createMultiPointerMotionEvent(
            MotionEvent.ACTION_MOVE, 410f, 1010f,
            dt4, dt4 + 30, 0, pointerCount = 2
        ))
        events4.add(binderHelper.createMultiPointerMotionEvent(
            MotionEvent.ACTION_POINTER_UP, 680f, 1000f,
            dt4, dt4 + 50, 1, pointerCount = 2
        ))
        events4.add(binderHelper.createMotionEvent(
            MotionEvent.ACTION_UP, 410f, 1010f,
            dt4, dt4 + 60, 0
        ))

        runSubTest(
            "D4: DOWN -> PDOWN -> MOVE -> PUP -> UP (full sequence)",
            events4,
            300
        )
    }

    private fun runSubTest(
        testName: String,
        events: List<MotionEvent>,
        waitAfterMs: Int
    ) {
        val testStart = System.currentTimeMillis()
        val steps = mutableListOf<StepResult>()
        val receiverBefore = getReceiverCount()

        steps.add(StepResult(
            "Create events",
            true, "CREATE",
            "${events.size} events: " + events.map {
                "0x${Integer.toHexString(it.actionMasked)}(ptrs=${it.pointerCount})"
            }.joinToString(" -> ")
        ))

        var allInjected = true
        var lastResult: IInputManagerBinderHelper.InjectionResult? = null
        val binderLatencies = mutableListOf<Long>()

        for ((idx, event) in events.withIndex()) {
            val result = injectEvent(event, INJECT_INPUT_EVENT_MODE_ASYNC)
            if (!result.success) allInjected = false
            if (result.success) {
                onBinderLatency(result.binderLatencyNs)
                binderLatencies.add(result.binderLatencyNs)
            }
            lastResult = result
            if (idx < events.size - 1) {
                Thread.sleep(10)
            }
        }

        steps.add(StepResult(
            "Inject ${events.size} events",
            allInjected,
            "INJECT",
            "allSuccess=$allInjected last=${lastResult?.success}"
        ))

        Thread.sleep(waitAfterMs.toLong())

        val receiverAfter = getReceiverCount()
        val receiverEvents = canvas.getRecords().filter {
            it.wallClockMs >= testStart
        }

        val eventSummary = events.joinToString(", ") { ev ->
            "0x${Integer.toHexString(ev.actionMasked)}(ptrs=${ev.pointerCount})"
        }
        val receivedSummary = receiverEvents.joinToString(", ") { rec ->
            "${rec.action}(ptrs=${rec.pointerCount})"
        }

        steps.add(StepResult(
            "Receiver-side verification",
            receiverEvents.size >= events.size,
            "RECEIVE_CHECK",
            "expected=${events.size} received=${receiverEvents.size} " +
                    "delta=${receiverEvents.size - events.size} " +
                    "events=[$eventSummary] received=[$receivedSummary]"
        ))

        val allSuccess = steps.all { it.success }
        val receiverGotEvents = receiverEvents.isNotEmpty()
        val receiverCount = receiverEvents.size

        val classification = when {
            receiverCount == events.size -> Classification.VERIFIED
            receiverCount > 0 && receiverCount < events.size -> Classification.PARTIALLY_VERIFIED
            allInjected && receiverCount == 0 -> Classification.UNVERIFIED
            !allInjected -> Classification.FAILED
            else -> Classification.FAILED
        }

        val testResult = TestResult(
            testName = testName,
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
