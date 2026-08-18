package com.gpmapper.app.poc

import android.annotation.SuppressLint
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper

object IInputManagerBinderHelper {

    private const val TAG = "IInputMgrBinder"

    private const val SERVICE_NAME = "input"
    private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
    private const val INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH = 1

    private const val FIRST_CALL_TRANSACTION = 1

    data class BinderAcquisitionResult(
        val success: Boolean,
        val binder: IBinder?,
        val wrappedBinder: IBinder?,
        val error: String?
    )

    data class InjectionResult(
        val stage: String,
        val success: Boolean,
        val binderReturnTimestampNs: Long,
        val exception: Exception?,
        val errorMessage: String?
    )

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    fun acquireInputManagerBinder(): BinderAcquisitionResult {
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = smClass.getDeclaredMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, SERVICE_NAME) as? IBinder

            if (binder == null) {
                return BinderAcquisitionResult(
                    success = false, binder = null, wrappedBinder = null,
                    error = "ServiceManager.getService('input') returned null"
                )
            }

            Log.i(TAG, "Acquired raw IInputManager binder: ${binder.javaClass.name}")
            Log.i(TAG, "Binder alive: ${binder.isBinderAlive}")

            val wrappedBinder = ShizukuBinderWrapper(binder)
            Log.i(TAG, "Wrapped binder obtained: ${wrappedBinder?.javaClass?.name}")

            BinderAcquisitionResult(
                success = true,
                binder = binder,
                wrappedBinder = wrappedBinder,
                error = null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire IInputManager binder", e)
            BinderAcquisitionResult(
                success = false, binder = null, wrappedBinder = null,
                error = "${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    @SuppressLint("PrivateApi")
    fun getInjectInputEventMethod(): java.lang.reflect.Method? {
        return try {
            val imStubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            val method = imStubClass.getDeclaredMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            method.isAccessible = true
            Log.i(TAG, "Found injectInputEvent method: ${method}")
            method
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get injectInputEvent method via Stub", e)
            try {
                val imClass = Class.forName("android.hardware.input.InputManager")
                val method = imClass.getDeclaredMethod(
                    "injectInputEvent",
                    android.view.InputEvent::class.java,
                    Int::class.javaPrimitiveType
                )
                method.isAccessible = true
                Log.i(TAG, "Found injectInputEvent via InputManager class: ${method}")
                method
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to get injectInputEvent via InputManager class", e2)
                null
            }
        }
    }

    fun injectViaWrappedBinder(
        wrappedBinder: IBinder,
        event: android.view.InputEvent,
        mode: Int = INJECT_INPUT_EVENT_MODE_ASYNC
    ): InjectionResult {
        val createTs = System.nanoTime()

        return try {
            val method = getInjectInputEventMethod()
                ?: return InjectionResult(
                    stage = "METHOD_LOOKUP",
                    success = false,
                    binderReturnTimestampNs = 0,
                    exception = null,
                    errorMessage = "Could not find injectInputEvent method"
                )

            Log.i(TAG, "Invoking injectInputEvent via wrapped binder...")
            Log.i(TAG, "Event class: ${event.javaClass.name}")
            Log.i(TAG, "Event: deviceId=${event.getDeviceId()} eventTime=${event.getEventTime()}")

            val invokeTs = System.nanoTime()
            val result = method.invoke(wrappedBinder, event, mode)
            val returnTs = System.nanoTime()

            val success = result as? Boolean ?: false
            Log.i(TAG, "injectInputEvent returned: $success (took ${returnTs - invokeTs}ns)")

            InjectionResult(
                stage = "INJECT_SUCCESS",
                success = success,
                binderReturnTimestampNs = returnTs,
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
                exception = if (cause is Exception) cause else RuntimeException(cause),
                errorMessage = "${cause.javaClass.name}: ${cause.message}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "injectInputEvent failed: ${e.javaClass.name}: ${e.message}", e)
            InjectionResult(
                stage = "INVOKE_EXCEPTION",
                success = false,
                binderReturnTimestampNs = System.nanoTime(),
                exception = e,
                errorMessage = "${e.javaClass.name}: ${e.message}"
            )
        }
    }

    fun injectViaRawParcel(
        binder: IBinder,
        event: android.view.InputEvent,
        mode: Int = INJECT_INPUT_EVENT_MODE_ASYNC
    ): InjectionResult {
        val createTs = System.nanoTime()

        return try {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()

            try {
                data.writeInterfaceToken("android.hardware.input.IInputManager")
                data.writeStrongBinder(android.os.Binder()) // caller identity
                event.writeToParcel(data, 0)
                data.writeInt(mode)

                val invokeTs = System.nanoTime()
                val transactResult = binder.transact(FIRST_CALL_TRANSACTION, data, reply, 0)
                val returnTs = System.nanoTime()

                reply.readException()
                val success = reply.readInt() != 0

                Log.i(TAG, "Raw parcel transact: success=$success transactResult=$transactResult " +
                        "took ${returnTs - invokeTs}ns")

                InjectionResult(
                    stage = "RAW_PARCEL",
                    success = success && transactResult,
                    binderReturnTimestampNs = returnTs,
                    exception = null,
                    errorMessage = if (!success) "Parcel returned false" else null
                )
            } finally {
                data.recycle()
                reply.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Raw parcel injection failed: ${e.javaClass.name}: ${e.message}", e)
            InjectionResult(
                stage = "RAW_PARCEL_EXCEPTION",
                success = false,
                binderReturnTimestampNs = System.nanoTime(),
                exception = e,
                errorMessage = "${e.javaClass.name}: ${e.message}"
            )
        }
    }

    private val obtainMethod: java.lang.reflect.Method? by lazy {
        try {
            val methods = android.view.MotionEvent::class.java.declaredMethods
            methods.firstOrNull { m ->
                m.name == "obtain" &&
                java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                m.parameterTypes.size == 10 &&
                m.parameterTypes[4].isArray &&
                m.parameterTypes[4].componentType?.name?.contains("PointerProperties") == true
            }?.also { it.isAccessible = true }
                ?: methods.firstOrNull { m ->
                    m.name == "obtain" &&
                    java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                    m.parameterTypes.size == 14
                }?.also {
                    it.isAccessible = true
                    Log.w(TAG, "Fell back to 14-param MotionEvent.obtain overload")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve MotionEvent.obtain overload", e)
            null
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
        source: Int = 0x00001002 // SOURCE_TOUCHSCREEN
    ): android.view.MotionEvent {
        val method = obtainMethod
            ?: throw IllegalStateException("MotionEvent.obtain method not resolved — obtainMethod is null")

        val paramCount = method.parameterTypes.size
        if (paramCount == 10) {
            val pp = android.view.MotionEvent.PointerProperties()
            pp.id = pointerId
            pp.toolType = android.view.MotionEvent.TOOL_TYPE_FINGER
            val pc = android.view.MotionEvent.PointerCoords()
            pc.x = x
            pc.y = y
            pc.pressure = pressure
            pc.size = size
            val ppArray = arrayOf(pp)
            val pcArray = arrayOf(pc)
            @Suppress("UNCHECKED_CAST")
            return method.invoke(
                null, downTime, eventTime, action, 1,
                ppArray, pcArray,
                0, source, 0, 0
            ) as android.view.MotionEvent
        } else {
            @Suppress("UNCHECKED_CAST")
            return method.invoke(
                null, downTime, eventTime, action,
                x, y, pressure, size,
                0, 1.0f, 1.0f, 0, 0, source, 0
            ) as android.view.MotionEvent
        }
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

        val pdownAction = (android.view.MotionEvent.ACTION_POINTER_DOWN or (1 shl 8))
        events.add(createMotionEvent(
            pdownAction,
            x2, y2,
            downTime, downTime + 10,
            pointerId = 1
        ))

        events.add(createMotionEvent(
            android.view.MotionEvent.ACTION_MOVE,
            x1 + 10f, y1 + 10f,
            downTime, downTime + durationMs / 2,
            pointerId = 0
        ))

        val pupAction = (android.view.MotionEvent.ACTION_POINTER_UP or (1 shl 8))
        events.add(createMotionEvent(
            pupAction,
            x2, y2,
            downTime, downTime + durationMs - 10,
            pointerId = 1
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
