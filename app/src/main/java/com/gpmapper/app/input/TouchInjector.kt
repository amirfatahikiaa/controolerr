package com.gpmapper.app.input

import android.util.Log
import com.gpmapper.app.model.SwipeConfig
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class TouchInjector(private val backend: InjectionBackend) {

    companion object {
        private const val TAG = "TouchInjector"
    }

    private val running = AtomicBoolean(false)
    private val eventQueue = ConcurrentLinkedQueue<TouchAction>()
    private var injectionJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    sealed class TouchAction {
        data class Tap(val x: Float, val y: Float, val durationMs: Int, val pointerId: Int) : TouchAction()
        data class TouchDown(val pointerId: Int, val x: Float, val y: Float) : TouchAction()
        data class TouchMove(val pointerId: Int, val x: Float, val y: Float) : TouchAction()
        data class TouchUp(val pointerId: Int) : TouchAction()
        data class Swipe(val config: SwipeConfig, val pointerId: Int) : TouchAction()
    }

    fun start() {
        if (running.compareAndSet(false, true)) {
            injectionJob = scope.launch {
                processQueue()
            }
            Log.i(TAG, "Touch injector started on backend: ${backend.name}")
        }
    }

    fun stop() {
        if (running.compareAndSet(true, false)) {
            injectionJob?.cancel()
            injectionJob = null
            eventQueue.clear()
            Log.i(TAG, "Touch injector stopped")
        }
    }

    private suspend fun processQueue() {
        while (running.get() && coroutineContext.isActive) {
            val action = eventQueue.poll()
            if (action != null) {
                executeAction(action)
            } else {
                delay(1)
            }
        }
    }

    private fun executeAction(action: TouchAction) {
        when (action) {
            is TouchAction.Tap -> {
                backend.injectTouchDown(action.pointerId, action.x, action.y, 0.9f)
                Thread.sleep(action.durationMs.toLong())
                backend.injectTouchUp(action.pointerId)
            }
            is TouchAction.TouchDown -> {
                backend.injectTouchDown(action.pointerId, action.x, action.y, 0.8f)
            }
            is TouchAction.TouchMove -> {
                backend.injectTouchMove(action.pointerId, action.x, action.y, 0.8f)
            }
            is TouchAction.TouchUp -> {
                backend.injectTouchUp(action.pointerId)
            }
            is TouchAction.Swipe -> {
                executeSwipe(action.config, action.pointerId)
            }
        }
    }

    private fun executeSwipe(config: SwipeConfig, pointerId: Int) {
        val steps = (config.durationMs / 2).coerceAtLeast(5)
        val delayPerStep = config.durationMs.toLong() / steps

        backend.injectTouchDown(pointerId, config.startX, config.startY, 0.7f)
        Thread.sleep(5)

        for (i in 0..steps) {
            val t = i.toFloat() / steps.toFloat()
            val cx = config.startX + (config.endX - config.startX) * t
            val cy = config.startY + (config.endY - config.startY) * t
            backend.injectTouchMove(pointerId, cx, cy, 0.8f)
            Thread.sleep(delayPerStep)
        }

        backend.injectTouchMove(pointerId, config.endX, config.endY, 0.8f)
        Thread.sleep(5)
        backend.injectTouchUp(pointerId)
    }

    fun tap(x: Float, y: Float, durationMs: Int = 50, pointerId: Int = 99) {
        eventQueue.offer(TouchAction.Tap(x, y, durationMs, pointerId))
    }

    fun touchDown(pointerId: Int, x: Float, y: Float) {
        eventQueue.offer(TouchAction.TouchDown(pointerId, x, y))
    }

    fun touchMove(pointerId: Int, x: Float, y: Float) {
        eventQueue.offer(TouchAction.TouchMove(pointerId, x, y))
    }

    fun touchUp(pointerId: Int) {
        eventQueue.offer(TouchAction.TouchUp(pointerId))
    }

    fun swipe(config: SwipeConfig, pointerId: Int) {
        eventQueue.offer(TouchAction.Swipe(config, pointerId))
    }

    fun smoothSwipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Int,
        pointerId: Int
    ) {
        swipe(SwipeConfig(startX, startY, endX, endY, durationMs), pointerId)
    }

    fun destroy() {
        stop()
        scope.cancel()
    }
}
