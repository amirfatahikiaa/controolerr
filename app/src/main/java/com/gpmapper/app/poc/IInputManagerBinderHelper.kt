package com.gpmapper.app.poc

import android.annotation.SuppressLint
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper

object IInputManagerBinderHelper {

    private const val TAG = "IInputMgrBinder"

    private const val SERVICE_NAME = "input"
    private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
    private const val INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH = 1

    data class BinderAcquisitionResult(
        val success: Boolean,
        val binder: IBinder?,
        val wrappedBinder: IBinder?,
        val iInputManager: Any?,
        val injectMethod: java.lang.reflect.Method?,
        val error: String?
    )

    data class InjectionResult(
        val stage: String,
        val success: Boolean,
        val binderReturnTimestampNs: Long,
        val binderLatencyNs: Long,
        val exception: Exception?,
        val errorMessage: String?
    )

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    fun acquireInputManagerBinder(): BinderAcquisitionResult {
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = smClass.getDeclaredMethod("getService", String::class.java)
            val rawBinder = getServiceMethod.invoke(null, SERVICE_NAME) as? IBinder

            if (rawBinder == null) {
                return BinderAcquisitionResult(
                    success = false, binder = null, wrappedBinder = null,
                    iInputManager = null, injectMethod = null,
                    error = "ServiceManager.getService('input') returned null"
                )
            }
            Log.i(TAG, "Raw binder: ${rawBinder.javaClass.name} alive=${rawBinder.isBinderAlive}")

            val wrappedBinder = ShizukuBinderWrapper(rawBinder)
            Log.i(TAG, "ShizukuBinderWrapper: ${wrappedBinder.javaClass.name}")

            val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterfaceMethod = stubClass.getDeclaredMethod("asInterface", IBinder::class.java)
            val iInputManager = asInterfaceMethod.invoke(null, wrappedBinder)
            Log.i(TAG, "IInputManager proxy: ${iInputManager?.javaClass?.name}")

            if (iInputManager == null) {
                return BinderAcquisitionResult(
                    success = false, binder = rawBinder, wrappedBinder = wrappedBinder,
                    iInputManager = null, injectMethod = null,
                    error = "IInputManager.Stub.asInterface returned null"
                )
            }

            val iInputManagerClass = Class.forName("android.hardware.input.IInputManager")
            val injectMethod = iInputManagerClass.getDeclaredMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            injectMethod.isAccessible = true
            Log.i(TAG, "injectInputEvent method: $injectMethod")

            return BinderAcquisitionResult(
                success = true,
                binder = rawBinder,
                wrappedBinder = wrappedBinder,
                iInputManager = iInputManager,
                injectMethod = injectMethod,
                error = null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire IInputManager binder", e)
            return BinderAcquisitionResult(
                success = false, binder = null, wrappedBinder = null,
                iInputManager = null, injectMethod = null,
                error = "${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    fun injectViaProxy(
        iInputManager: Any,
        injectMethod: java.lang.reflect.Method,
        event: android.view.InputEvent,
        mode: Int = INJECT_INPUT_EVENT_MODE_ASYNC
    ): InjectionResult {
        return try {
            Log.i(TAG, "Invoking injectInputEvent on IInputManager proxy...")
            Log.i(TAG, "Proxy class: ${iInputManager.javaClass.name}")
            Log.i(TAG, "Event: deviceId=${event.getDeviceId()} eventTime=${event.getEventTime()}")

            val invokeTs = System.nanoTime()
            val result = injectMethod.invoke(iInputManager, event, mode)
            val returnTs = System.nanoTime()

            val success = result as? Boolean ?: false
            val latencyNs = returnTs - invokeTs
            Log.i(TAG, "injectInputEvent returned: $success (took ${latencyNs}ns)")

            InjectionResult(
                stage = "INJECT_SUCCESS",
                success = success,
                binderReturnTimestampNs = returnTs,
                binderLatencyNs = latencyNs,
                exception = null,
                errorMessage = if (!success) "injectInputEvent returned false" else null
            )
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.cause ?: e
            Log.e(TAG, "injectInputEvent threw: ${cause.javaClass.name}: ${cause.message}", cause)
            InjectionResult(
                stage = "INJECT_EXCEPTION",
                success = false,
                binderReturnTimestampNs = System.nanoTime(),
                binderLatencyNs = 0,
                exception = if (cause is Exception) cause else RuntimeException(cause),
                errorMessage = "${cause.javaClass.name}: ${cause.message}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "injectInputEvent failed: ${e.javaClass.name}: ${e.message}", e)
            InjectionResult(
                stage = "INVOKE_EXCEPTION",
                success = false,
                binderReturnTimestampNs = System.nanoTime(),
                binderLatencyNs = 0,
                exception = e,
                errorMessage = "${e.javaClass.name}: ${e.message}"
            )
        }
    }

    fun createMotionEvent(
        action: Int,
        x: Float,
        y: Float,
        downTime: Long = SystemClock.uptimeMillis(),
        eventTime: Long = SystemClock.uptimeMillis(),
        pointerId: Int = 0,
        pressure: Float = 1.0f,
        size: Float = 1.0f,
        source: Int = 0x00001002
    ): android.view.MotionEvent {
        return MotionEventFactory.create(action, x, y, downTime, eventTime, pointerId, pressure, size, source)
    }

    fun createMultiPointerMotionEvent(
        action: Int,
        x: Float,
        y: Float,
        downTime: Long,
        eventTime: Long,
        pointerId: Int,
        pressure: Float = 1.0f,
        size: Float = 1.0f,
        source: Int = 0x00001002,
        pointerCount: Int = 2
    ): android.view.MotionEvent {
        return MotionEventFactory.createMultiPointer(
            action, x, y, downTime, eventTime, pointerId, pressure, size, source, pointerCount
        )
    }

    fun createTapMotionEvents(
        x: Float,
        y: Float,
        tapDurationMs: Long = 50
    ): Pair<android.view.MotionEvent, android.view.MotionEvent> {
        val downTime = SystemClock.uptimeMillis()
        val down = createMotionEvent(
            android.view.MotionEvent.ACTION_DOWN,
            x, y,
            downTime, downTime,
            pointerId = 0
        )
        val up = createMotionEvent(
            android.view.MotionEvent.ACTION_UP,
            x, y,
            downTime, downTime + tapDurationMs,
            pointerId = 0
        )
        return Pair(down, up)
    }

    fun createSwipeMotionEvents(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long = 50,
        stepCount: Int = 5
    ): List<android.view.MotionEvent> {
        val events = mutableListOf<android.view.MotionEvent>()
        val downTime = SystemClock.uptimeMillis()
        val stepDuration = durationMs / stepCount

        events.add(createMotionEvent(
            android.view.MotionEvent.ACTION_DOWN,
            startX, startY,
            downTime, downTime,
            pointerId = 0
        ))

        for (i in 1 until stepCount) {
            val t = i.toFloat() / stepCount
            val cx = startX + (endX - startX) * t
            val cy = startY + (endY - startY) * t
            events.add(createMotionEvent(
                android.view.MotionEvent.ACTION_MOVE,
                cx, cy,
                downTime, downTime + stepDuration * i,
                pointerId = 0
            ))
        }

        events.add(createMotionEvent(
            android.view.MotionEvent.ACTION_UP,
            endX, endY,
            downTime, downTime + durationMs,
            pointerId = 0
        ))

        return events
    }

    fun createTwoPointerMotionEvents(
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        durationMs: Long = 50
    ): List<android.view.MotionEvent> {
        val events = mutableListOf<android.view.MotionEvent>()
        val downTime = SystemClock.uptimeMillis()

        events.add(createMotionEvent(
            android.view.MotionEvent.ACTION_DOWN,
            x1, y1,
            downTime, downTime,
            pointerId = 0
        ))

        events.add(createMultiPointerMotionEvent(
            android.view.MotionEvent.ACTION_POINTER_DOWN,
            x2, y2,
            downTime, downTime + 10,
            pointerId = 1,
            pointerCount = 2
        ))

        events.add(createMultiPointerMotionEvent(
            android.view.MotionEvent.ACTION_MOVE,
            x1 + 10f, y1 + 10f,
            downTime, downTime + durationMs / 2,
            pointerId = 0,
            pointerCount = 2
        ))

        events.add(createMultiPointerMotionEvent(
            android.view.MotionEvent.ACTION_POINTER_UP,
            x2, y2,
            downTime, downTime + durationMs - 10,
            pointerId = 1,
            pointerCount = 2
        ))

        events.add(createMotionEvent(
            android.view.MotionEvent.ACTION_UP,
            x1 + 10f, y1 + 10f,
            downTime, downTime + durationMs,
            pointerId = 0
        ))

        return events
    }
}
