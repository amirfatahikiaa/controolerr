package com.gpmapper.app.poc

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

class VisualTouchCanvas @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class TouchRecord(
        val pointerId: Int,
        val action: String,
        val x: Float,
        val y: Float,
        val pressure: Float,
        val timestampNs: Long,
        val wallClockMs: Long,
        val classification: EventClassification,
        val eventTimeMs: Long,
        val deviceId: Int,
        val source: Int,
        val flags: Int,
        val pointerCount: Int,
        val actionMasked: Int,
        val downTime: Long
    )

    enum class EventClassification {
        RAW_PHYSICAL,
        INJECTED_CANDIDATE,
        UNKNOWN
    }

    private val records = CopyOnWriteArrayList<TouchRecord>()
    private val maxRecords = 200
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val FLAG_INJECTED = 0x01000000

    private val physicalPaint = Paint().apply {
        color = Color.parseColor("#4CAF50")
        strokeWidth = 8f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val injectedPaint = Paint().apply {
        color = Color.parseColor("#FF5722")
        strokeWidth = 8f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val unknownPaint = Paint().apply {
        color = Color.parseColor("#FFEB3B")
        strokeWidth = 8f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val physicalFillPaint = Paint().apply {
        color = Color.parseColor("#4CAF50")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val injectedFillPaint = Paint().apply {
        color = Color.parseColor("#FF5722")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val unknownFillPaint = Paint().apply {
        color = Color.parseColor("#FFEB3B")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 28f
        isAntiAlias = true
    }

    private val logPaint = Paint().apply {
        color = Color.parseColor("#BBBBBB")
        textSize = 22f
        isAntiAlias = true
    }

    private val logHeaderPaint = Paint().apply {
        color = Color.parseColor("#90CAF9")
        textSize = 24f
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }

    private val crosshairPaint = Paint().apply {
        color = Color.parseColor("#33FFFFFF")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val activePointers = mutableMapOf<Int, PointF>()
    private val trails = mutableMapOf<Int, MutableList<PointF>>()

    var onTouchReceived: ((TouchRecord) -> Unit)? = null
    var onRawMotionEvent: ((MotionEvent) -> Unit)? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        Log.d(TAG, "onTouchEvent: action=0x${Integer.toHexString(event.actionMasked)} " +
                "deviceId=${event.deviceId} source=0x${Integer.toHexString(event.source)} " +
                "flags=0x${Integer.toHexString(event.getFlags())} " +
                "ptrCount=${event.pointerCount}")

        onRawMotionEvent?.invoke(event)

        val actionType = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> "DOWN"
            MotionEvent.ACTION_POINTER_DOWN -> "PDOWN"
            MotionEvent.ACTION_MOVE -> "MOVE"
            MotionEvent.ACTION_POINTER_UP -> "PUP"
            MotionEvent.ACTION_UP -> "UP"
            MotionEvent.ACTION_CANCEL -> "CANCEL"
            else -> "OTHER(${event.actionMasked})"
        }

        val pointerIndex = event.actionIndex
        val pointerId = if (event.actionMasked in listOf(
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP
            )
        ) {
            event.getPointerId(pointerIndex)
        } else {
            event.getPointerId(0)
        }

        val x = event.getX(pointerIndex.coerceIn(0, event.pointerCount - 1))
        val y = event.getY(pointerIndex.coerceIn(0, event.pointerCount - 1))
        val pressure = event.getPressure(pointerIndex.coerceIn(0, event.pointerCount - 1))

        val hasInjectedFlag = (event.getFlags() and FLAG_INJECTED) != 0
        val classification = when {
            hasInjectedFlag -> EventClassification.INJECTED_CANDIDATE
            event.deviceId == 0 && event.source == 0x00001002 -> EventClassification.INJECTED_CANDIDATE
            else -> EventClassification.UNKNOWN
        }

        val record = TouchRecord(
            pointerId = pointerId,
            action = actionType,
            x = x,
            y = y,
            pressure = pressure,
            timestampNs = event.eventTimeNanos,
            wallClockMs = System.currentTimeMillis(),
            classification = classification,
            eventTimeMs = event.eventTime,
            deviceId = event.deviceId,
            source = event.source,
            flags = event.getFlags(),
            pointerCount = event.pointerCount,
            actionMasked = event.actionMasked,
            downTime = event.downTime
        )

        synchronized(records) {
            records.add(record)
            if (records.size > maxRecords) {
                records.removeAt(0)
            }
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                activePointers[pointerId] = PointF(x, y)
                trails[pointerId] = mutableListOf(PointF(x, y))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    val px = event.getX(i)
                    val py = event.getY(i)
                    activePointers[pid] = PointF(px, py)
                    trails.getOrPut(pid) { mutableListOf() }.add(PointF(px, py))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                activePointers.remove(pointerId)
                trails.remove(pointerId)
            }
        }

        invalidate()
        onTouchReceived?.invoke(record)
        return true
    }

    fun getRecords(): List<TouchRecord> {
        synchronized(records) {
            return records.toList()
        }
    }

    fun clearRecords() {
        synchronized(records) {
            records.clear()
        }
        activePointers.clear()
        trails.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        for (i in 0..10) {
            val x = w * i / 10f
            canvas.drawLine(x, 0f, x, h, crosshairPaint)
        }
        for (i in 0..10) {
            val y = h * i / 10f
            canvas.drawLine(0f, y, w, y, crosshairPaint)
        }

        for ((pointerId, trail) in trails) {
            if (trail.size < 2) continue
            val paint = when {
                pointerId >= 100 -> injectedPaint
                else -> unknownPaint
            }
            for (i in 1 until trail.size) {
                canvas.drawLine(trail[i - 1].x, trail[i - 1].y, trail[i].x, trail[i].y, paint)
            }
        }

        for ((pointerId, point) in activePointers) {
            val fillPaint = when {
                pointerId >= 100 -> injectedFillPaint
                else -> unknownFillPaint
            }
            val strokePaint = when {
                pointerId >= 100 -> injectedPaint
                else -> unknownPaint
            }
            canvas.drawCircle(point.x, point.y, 20f, fillPaint)
            canvas.drawCircle(point.x, point.y, 20f, strokePaint)
            canvas.drawText("P$pointerId", point.x + 25f, point.y - 10f, textPaint)
        }

        val recentRecords = synchronized(records) {
            records.takeLast(15)
        }

        val logX = 20f
        var logY = 50f
        canvas.drawText("--- Event Log (last 15) ---", logX, logY, logHeaderPaint)
        logY += 35f

        for (record in recentRecords) {
            val timeStr = dateFormat.format(Date(record.wallClockMs))
            val classTag = when (record.classification) {
                EventClassification.RAW_PHYSICAL -> "[PHY]"
                EventClassification.INJECTED_CANDIDATE -> "[INJ?]"
                EventClassification.UNKNOWN -> "[UNK]"
            }
            val line = "$timeStr $classTag P${record.pointerId} ${record.action} " +
                    "(%.1f, %.1f) dev=${record.deviceId} src=0x${Integer.toHexString(record.source)} " +
                    "fl=0x${Integer.toHexString(record.flags)}".format(record.x, record.y)
            val linePaint = when (record.classification) {
                EventClassification.RAW_PHYSICAL -> physicalPaint
                EventClassification.INJECTED_CANDIDATE -> injectedPaint
                EventClassification.UNKNOWN -> unknownPaint
            }
            canvas.drawText(line, logX, logY, linePaint)
            logY += 26f
        }

        canvas.drawText(
            "Yellow=UNKNOWN (no reliable public SDK classifier)",
            logX, h - 30f, logHeaderPaint
        )
    }

    companion object {
        private const val TAG = "VisualTouchCanvas"
    }
}
