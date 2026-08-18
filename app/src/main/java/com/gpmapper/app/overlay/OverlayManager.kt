package com.gpmapper.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.Build
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.gpmapper.app.model.MappingProfile
import com.gpmapper.app.model.ProfileManager
import java.util.concurrent.ConcurrentHashMap

class OverlayManager(private val context: Context) {

    companion object {
        private const val TAG = "OverlayManager"
    }

    private var windowManager: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private var configView: View? = null
    private var isVisible = false
    private var isDragging = false
    private var isConfigMode = false

    private val touchTargets = ConcurrentHashMap<String, TouchTarget>()
    private val profileManager = ProfileManager(context)

    data class TouchTarget(
        val id: String,
        var x: Float,
        var y: Float,
        var width: Float,
        var height: Float,
        var label: String,
        var color: Int = Color.argb(120, 255, 255, 255)
    )

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (isVisible) return

        val inflater = LayoutInflater.from(context)
        overlayView = createOverlayView()

        val params = createLayoutParams()

        try {
            windowManager.addView(overlayView, params)
            isVisible = true
            loadTargetsFromProfile()
            Log.i(TAG, "Overlay view added")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view", e)
        }
    }

    fun hide() {
        if (!isVisible) return

        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove overlay view", e)
            }
        }
        overlayView = null
        configView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {}
        }
        configView = null
        isVisible = false
        Log.i(TAG, "Overlay view removed")
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlayView(): View {
        val layout = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.TRANSPARENT)
        }

        val controlBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            )
            setPadding(16, 8, 16, 8)
            background = createRoundedBackground(Color.argb(200, 30, 30, 46))
        }

        val toggleBtn = createControlButton("Configure") {
            isConfigMode = !isConfigMode
            updateConfigMode()
        }

        val closeBtn = createControlButton("X") {
            hide()
        }

        controlBar.addView(toggleBtn)
        controlBar.addView(closeBtn)
        layout.addView(controlBar)

        setupDragListener(layout)

        return layout
    }

    private fun createControlButton(text: String, onClick: () -> Unit): View {
        return TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(24, 12, 24, 12)
            background = createRoundedBackground(Color.argb(100, 26, 115, 232))
            setOnClickListener { onClick() }
        }.also {
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 8 }
            it.layoutParams = params
        }
    }

    private fun createRoundedBackground(color: Int): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = 24f
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragListener(view: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val params = view.layoutParams
                    initialX = if (params is WindowManager.LayoutParams) params.x else 0
                    initialY = if (params is WindowManager.LayoutParams) params.y else 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (kotlin.math.abs(dx) > 5 || kotlin.math.abs(dy) > 5) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val params = view.layoutParams
                        if (params is WindowManager.LayoutParams) {
                            params.x = (initialX + dx).toInt()
                            params.y = (initialY + dy).toInt()
                            try {
                                windowManager.updateViewLayout(view, params)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to update layout", e)
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val flags = if (isConfigMode) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        )
    }

    private fun updateConfigMode() {
        val layout = overlayView as? FrameLayout ?: return

        if (isConfigMode) {
            showTouchTargets(layout)
            overlayView?.let { view ->
                val params = view.layoutParams as? WindowManager.LayoutParams
                if (params != null) {
                    params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to update layout flags", e)
                    }
                }
            }
        } else {
            hideTouchTargets(layout)
            overlayView?.let { view ->
                val params = view.layoutParams as? WindowManager.LayoutParams
                if (params != null) {
                    params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to update layout flags", e)
                    }
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showTouchTargets(layout: FrameLayout) {
        val profile = profileManager.getActiveProfileId()?.let { profileManager.loadProfile(it) }
            ?: MappingProfile.createDefault()

        for (mapping in profile.mappings) {
            if (mapping.actionType == "tap" || mapping.actionType == "hold") {
                val target = TouchTarget(
                    id = mapping.sourceButton,
                    x = mapping.target.normalizedX,
                    y = mapping.target.normalizedY,
                    width = mapping.target.widthFraction.coerceAtLeast(0.04f),
                    height = mapping.target.heightFraction.coerceAtLeast(0.04f),
                    label = mapping.sourceButton.uppercase()
                )

                val targetView = createTargetView(target)
                touchTargets[mapping.sourceButton] = target
                layout.addView(targetView)
            }
        }
    }

    private fun hideTouchTargets(layout: FrameLayout) {
        for ((id, _) in touchTargets) {
            val view = layout.findViewWithTag<View>("target_$id")
            if (view != null) {
                layout.removeView(view)
            }
        }
        touchTargets.clear()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createTargetView(target: TouchTarget): View {
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels.toFloat()
        val screenHeight = displayMetrics.heightPixels.toFloat()

        val px = (target.x * screenWidth).toInt()
        val py = (target.y * screenHeight).toInt()
        val pw = (target.width * screenWidth).toInt()
        val ph = (target.height * screenHeight).toInt()

        val container = FrameLayout(context).apply {
            tag = "target_${target.id}"
            layoutParams = FrameLayout.LayoutParams(pw, ph).apply {
                leftMargin = px - pw / 2
                topMargin = py - ph / 2
            }
        }

        val circleView = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            background = createCircleBackground(target.color)
        }

        val labelView = TextView(context).apply {
            text = target.label
            setTextColor(Color.WHITE)
            textSize = 9f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        container.addView(circleView)
        container.addView(labelView)

        var lastX = 0f
        var lastY = 0f

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX
                    lastY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY
                    lastX = event.rawX
                    lastY = event.rawY

                    container.translationX += dx
                    container.translationY += dy
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val newPx = (px + container.translationX) / screenWidth
                    val newPy = (py + container.translationY) / screenHeight
                    target.x = newPx
                    target.y = newPy
                    true
                }
                else -> false
            }
        }

        return container
    }

    private fun createCircleBackground(color: Int): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(color)
            setStroke(2, Color.WHITE)
        }
    }

    private fun loadTargetsFromProfile() {
        val profile = profileManager.getActiveProfileId()?.let { profileManager.loadProfile(it) }
            ?: MappingProfile.createDefault()

        Log.i(TAG, "Loaded ${profile.mappings.size} mappings from profile: ${profile.name}")
    }
}
