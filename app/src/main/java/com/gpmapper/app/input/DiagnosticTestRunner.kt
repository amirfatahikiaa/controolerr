package com.gpmapper.app.input

import android.util.Log
import kotlinx.coroutines.*

class DiagnosticTestRunner(private val backend: InjectionBackend) {

    companion object {
        private const val TAG = "DiagnosticTestRunner"
    }

    data class TestResult(
        val testName: String,
        val passed: Boolean,
        val backendUsed: String,
        val timestampMs: Long,
        val pointerId: Int,
        val coordinates: String,
        val durationMs: Long,
        val errorCode: Int,
        val errorMessage: String?,
        val nativeExecuted: Boolean,
        val androidReceived: Boolean
    )

    data class FullDiagnosticReport(
        val results: List<TestResult>,
        val totalTests: Int,
        val passedTests: Int,
        val failedTests: Int,
        val backendDiagnostics: InjectionBackend.BackendDiagnostics,
        val timestampMs: Long
    )

    suspend fun runFullDiagnostic(): FullDiagnosticReport = withContext(Dispatchers.Default) {
        val results = mutableListOf<TestResult>()

        results.add(testSingleTap(0.5f, 0.5f))
        delay(200)
        results.add(testTouchDownMoveUp(0.3f, 0.3f, 0.7f, 0.7f))
        delay(200)
        results.add(testSmoothSwipe(0.2f, 0.5f, 0.8f, 0.5f, 100))
        delay(200)
        results.add(testTwoSimultaneousPointers())
        delay(200)
        results.add(testFiveSimultaneousPointers())
        delay(200)
        results.add(testTenSimultaneousPointers())
        delay(200)
        results.add(testRapidFireTaps(10))
        delay(200)
        results.add(testAnalogStickMovement())
        delay(200)
        results.add(testDPadDirections())
        delay(200)
        results.add(testModifierCombo())

        val passed = results.count { it.passed }
        val diagnostics = backend.getDiagnostics()

        FullDiagnosticReport(
            results = results,
            totalTests = results.size,
            passedTests = passed,
            failedTests = results.size - passed,
            backendDiagnostics = diagnostics,
            timestampMs = System.currentTimeMillis()
        )
    }

    private fun testSingleTap(x: Float, y: Float): TestResult {
        val start = System.currentTimeMillis()
        val pointerId = 50
        val success = backend.injectTouchDown(pointerId, x, y, 0.9f)
        Thread.sleep(30)
        val upSuccess = backend.injectTouchUp(pointerId)
        val duration = System.currentTimeMillis() - start

        return TestResult(
            testName = "Single Tap",
            passed = success && upSuccess,
            backendUsed = backend.name,
            timestampMs = start,
            pointerId = pointerId,
            coordinates = "(%.3f, %.3f)".format(x, y),
            durationMs = duration,
            errorCode = if (success && upSuccess) 0 else -1,
            errorMessage = if (!success) "TouchDown failed" else if (!upSuccess) "TouchUp failed" else null,
            nativeExecuted = success,
            androidReceived = success
        )
    }

    private fun testTouchDownMoveUp(startX: Float, startY: Float, endX: Float, endY: Float): TestResult {
        val start = System.currentTimeMillis()
        val pointerId = 51
        val downOk = backend.injectTouchDown(pointerId, startX, startY, 0.8f)
        Thread.sleep(10)

        var moveOk = true
        val steps = 10
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            val cx = startX + (endX - startX) * t
            val cy = startY + (endY - startY) * t
            if (!backend.injectTouchMove(pointerId, cx, cy, 0.8f)) {
                moveOk = false
                break
            }
            Thread.sleep(5)
        }

        val upOk = backend.injectTouchUp(pointerId)
        val duration = System.currentTimeMillis() - start

        return TestResult(
            testName = "Touch Down/Move/Up",
            passed = downOk && moveOk && upOk,
            backendUsed = backend.name,
            timestampMs = start,
            pointerId = pointerId,
            coordinates = "(%.3f,%.3f)->(%.3f,%.3f)".format(startX, startY, endX, endY),
            durationMs = duration,
            errorCode = if (downOk && moveOk && upOk) 0 else -1,
            errorMessage = when {
                !downOk -> "TouchDown failed"
                !moveOk -> "TouchMove failed"
                !upOk -> "TouchUp failed"
                else -> null
            },
            nativeExecuted = downOk,
            androidReceived = downOk
        )
    }

    private fun testSmoothSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Int): TestResult {
        val start = System.currentTimeMillis()
        val pointerId = 52
        val steps = (durationMs / 5).coerceAtLeast(5)
        val delayPerStep = durationMs.toLong() / steps

        val downOk = backend.injectTouchDown(pointerId, startX, startY, 0.7f)

        var moveOk = true
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            val cx = startX + (endX - startX) * t
            val cy = startY + (endY - startY) * t
            if (!backend.injectTouchMove(pointerId, cx, cy, 0.8f)) {
                moveOk = false
                break
            }
            Thread.sleep(delayPerStep)
        }

        val upOk = backend.injectTouchUp(pointerId)
        val duration = System.currentTimeMillis() - start

        return TestResult(
            testName = "Smooth Swipe",
            passed = downOk && moveOk && upOk,
            backendUsed = backend.name,
            timestampMs = start,
            pointerId = pointerId,
            coordinates = "(%.3f,%.3f)->(%.3f,%.3f) %dms".format(startX, startY, endX, endY, durationMs),
            durationMs = duration,
            errorCode = 0,
            errorMessage = null,
            nativeExecuted = downOk,
            androidReceived = downOk
        )
    }

    private fun testTwoSimultaneousPointers(): TestResult {
        val start = System.currentTimeMillis()
        val ok1 = backend.injectTouchDown(60, 0.3f, 0.3f, 0.8f)
        val ok2 = backend.injectTouchDown(61, 0.7f, 0.7f, 0.8f)
        Thread.sleep(50)
        val up1 = backend.injectTouchUp(60)
        val up2 = backend.injectTouchUp(61)
        val duration = System.currentTimeMillis() - start

        return TestResult(
            testName = "Two Simultaneous Pointers",
            passed = ok1 && ok2 && up1 && up2,
            backendUsed = backend.name,
            timestampMs = start,
            pointerId = 60,
            coordinates = "(0.3,0.3)+(0.7,0.7)",
            durationMs = duration,
            errorCode = 0,
            errorMessage = null,
            nativeExecuted = ok1,
            androidReceived = ok1
        )
    }

    private fun testFiveSimultaneousPointers(): TestResult {
        val start = System.currentTimeMillis()
        val pointerIds = (70..74).toList()
        val coords = listOf(
            0.2f to 0.2f, 0.4f to 0.3f, 0.5f to 0.5f, 0.6f to 0.3f, 0.8f to 0.2f
        )

        val downsOk = pointerIds.zip(coords).map { (id, coord) ->
            backend.injectTouchDown(id, coord.first, coord.second, 0.8f)
        }

        Thread.sleep(50)

        val upsOk = pointerIds.map { backend.injectTouchUp(it) }
        val duration = System.currentTimeMillis() - start

        val allOk = downsOk.all { it } && upsOk.all { it }

        return TestResult(
            testName = "Five Simultaneous Pointers",
            passed = allOk,
            backendUsed = backend.name,
            timestampMs = start,
            pointerId = 70,
            coordinates = "5 pointers",
            durationMs = duration,
            errorCode = 0,
            errorMessage = if (!allOk) "Some pointers failed" else null,
            nativeExecuted = downsOk.first(),
            androidReceived = downsOk.first()
        )
    }

    private fun testTenSimultaneousPointers(): TestResult {
        val start = System.currentTimeMillis()
        val pointerIds = (80..89).toList()
        val coords = pointerIds.map { id ->
            val x = 0.1f + (id - 80) * 0.08f
            val y = 0.5f + ((id - 80) % 3) * 0.1f
            x to y
        }

        val downsOk = pointerIds.zip(coords).map { (id, coord) ->
            backend.injectTouchDown(id, coord.first, coord.second, 0.8f)
        }

        Thread.sleep(50)

        val upsOk = pointerIds.map { backend.injectTouchUp(it) }
        val duration = System.currentTimeMillis() - start

        val allOk = downsOk.all { it } && upsOk.all { it }

        return TestResult(
            testName = "Ten Simultaneous Pointers",
            passed = allOk,
            backendUsed = backend.name,
            timestampMs = start,
            pointerId = 80,
            coordinates = "10 pointers",
            durationMs = duration,
            errorCode = 0,
            errorMessage = if (!allOk) "${downsOk.count { !it }} downs failed" else null,
            nativeExecuted = downsOk.first(),
            androidReceived = downsOk.first()
        )
    }

    private fun testRapidFireTaps(count: Int): TestResult {
        val start = System.currentTimeMillis()
        var successCount = 0

        for (i in 0 until count) {
            val pointerId = 90 + (i % 10)
            val x = 0.3f + (i % 5) * 0.1f
            val y = 0.5f
            if (backend.injectTouchDown(pointerId, x, y, 0.9f)) {
                Thread.sleep(10)
                if (backend.injectTouchUp(pointerId)) {
                    successCount++
                }
            }
            Thread.sleep(10)
        }

        val duration = System.currentTimeMillis() - start

        return TestResult(
            testName = "Rapid Fire Taps ($count)",
            passed = successCount == count,
            backendUsed = backend.name,
            timestampMs = start,
            pointerId = 90,
            coordinates = "rapid fire",
            durationMs = duration,
            errorCode = count - successCount,
            errorMessage = if (successCount < count) "${count - successCount}/$count taps failed" else null,
            nativeExecuted = successCount > 0,
            androidReceived = successCount > 0
        )
    }

    private fun testAnalogStickMovement(): TestResult {
        val start = System.currentTimeMillis()
        val pointerId = 95
        val centerX = 0.15f
        val centerY = 0.75f

        val downOk = backend.injectTouchDown(pointerId, centerX, centerY, 0.7f)

        var moveOk = true
        val angles = listOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f, 360f)
        for (angle in angles) {
            val rad = Math.toRadians(angle.toDouble())
            val offsetX = (Math.cos(rad) * 0.05f).toFloat()
            val offsetY = (Math.sin(rad) * 0.05f).toFloat()
            if (!backend.injectTouchMove(pointerId, centerX + offsetX, centerY + offsetY, 0.7f)) {
                moveOk = false
                break
            }
            Thread.sleep(5)
        }

        val upOk = backend.injectTouchUp(pointerId)
        val duration = System.currentTimeMillis() - start

        return TestResult(
            testName = "Analog Stick Circle",
            passed = downOk && moveOk && upOk,
            backendUsed = backend.name,
            timestampMs = start,
            pointerId = pointerId,
            coordinates = "circle around (0.15, 0.75)",
            durationMs = duration,
            errorCode = 0,
            errorMessage = null,
            nativeExecuted = downOk,
            androidReceived = downOk
        )
    }

    private fun testDPadDirections(): TestResult {
        val start = System.currentTimeMillis()
        val directions = listOf(
            "up" to (0.5f to 0.3f),
            "right" to (0.7f to 0.5f),
            "down" to (0.5f to 0.7f),
            "left" to (0.3f to 0.5f)
        )

        var allOk = true
        for ((name, coord) in directions) {
            val pointerId = 96
            val down = backend.injectTouchDown(pointerId, coord.first, coord.second, 0.8f)
            Thread.sleep(30)
            val up = backend.injectTouchUp(pointerId)
            if (!down || !up) {
                allOk = false
                break
            }
            Thread.sleep(50)
        }

        val duration = System.currentTimeMillis() - start

        return TestResult(
            testName = "D-Pad Directions",
            passed = allOk,
            backendUsed = backend.name,
            timestampMs = start,
            pointerId = 96,
            coordinates = "up/right/down/left",
            durationMs = duration,
            errorCode = 0,
            errorMessage = null,
            nativeExecuted = allOk,
            androidReceived = allOk
        )
    }

    private fun testModifierCombo(): TestResult {
        val start = System.currentTimeMillis()

        val l1Down = backend.injectTouchDown(97, 0.08f, 0.60f, 0.8f)
        Thread.sleep(20)
        val circleDown = backend.injectTouchDown(98, 0.90f, 0.75f, 0.8f)
        Thread.sleep(30)

        val swipeOk = backend.injectTouchMove(98, 0.90f, 0.65f, 0.8f)
        Thread.sleep(20)

        val circleUp = backend.injectTouchUp(98)
        val l1Up = backend.injectTouchUp(97)
        val duration = System.currentTimeMillis() - start

        return TestResult(
            testName = "Modifier Combo (L1+Circle Swipe)",
            passed = l1Down && circleDown && swipeOk && circleUp && l1Up,
            backendUsed = backend.name,
            timestampMs = start,
            pointerId = 97,
            coordinates = "L1@0.08,0.60 + Circle@0.90,0.75->0.90,0.65",
            durationMs = duration,
            errorCode = 0,
            errorMessage = null,
            nativeExecuted = l1Down,
            androidReceived = l1Down
        )
    }
}
