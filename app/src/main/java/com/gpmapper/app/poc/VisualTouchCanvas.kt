package com.gpmapper.app.poc

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
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
        val isInjected: Boolean,
        val eventTimeMs: Long
    )

    private val records = CopyOnWriteArrayList<TouchRecord>()
    private val maxRecords = 200
    private valdateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

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

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
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

        val isInjected = (event.flags and MotionEvent.FLAG_SOURCE_UNKNOWN) != 0 ||
                event.deviceId == 0

        val record = TouchRecord(
            pointerId = pointerId,
            action = actionType,
            x = x,
            y = y,
            pressure = pressure,
            timestampNs = event.eventTimeNanos,
            wallClockMs = System.currentTimeMillis(),
            isInjected = isInjected,
            eventTimeMs = event.eventTime
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
            val paint = if (pointerId >= 100) injectedPaint else physicalPaint
            for (i in 1 until trail.size) {
                canvas.drawLine(trail[i - 1].x, trail[i - 1].y, trail[i].x, trail[i].y, paint)
            }
        }

        for ((pointerId, point) in activePointers) {
            val fillPaint = if (pointerId >= 100) injectedFillPaint else physicalFillPaint
            val strokePaint = if (pointerId >= 100) injectedPaint else physicalPaint
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
            val injectTag = if (record.isInjected) "[INJ]" else "[PHYS]"
            val line = "$timeStr $injectTag P${record.pointerId} ${record.action} " +
                    "(%.1f, %.1f) p=%.2f".format(record.x, record.y, record.pressure)
            val linePaint = if (record.isInjected) injectedPaint else logPaint
            canvas.drawText(line, logX, logY, linePaint)
            logY += 26f
        }

        canvas.drawText(
            "Green = Physical touch | Red = Injected touch",
            logX, h - 30f, logHeaderPaint
        )
    }
}
