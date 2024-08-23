package com.app.screensharing

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

class CropAreaView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val rect = HttpServer.Settings.cropRect
    private val linePaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private var lastDirection = 0
    private var lastX = 0f
    private var lastY = 0f

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.parseColor("#4D000000"))
        canvas.drawRect(rect, linePaint)
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        when (event?.action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                if (lastY >= rect.top && lastY <= rect.centerY()) {
                    if (lastX >= rect.left && lastX <= rect.centerX()) {
                        lastDirection = 1
                    } else if (lastX <= rect.right && lastX >= rect.centerX()) {
                        lastDirection = 2
                    }
                } else if (lastY <= rect.bottom && lastY >= rect.centerY()) {
                    if (lastX >= rect.left && lastX <= rect.centerX()) {
                        lastDirection = 3
                    } else if (lastX <= rect.right && lastX >= rect.centerX()) {
                        lastDirection = 4
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val x = event.x
                val y = event.y
                when (lastDirection) {
                    1 -> {
                        rect.left = max(0f, rect.left + (x - lastX))
                        rect.top = max(0f, rect.top + (y - lastY))
                    }
                    2 -> {
                        rect.right = min(width.toFloat(), rect.right + (x - lastX))
                        rect.top = max(0f, rect.top + (y - lastY))
                    }
                    3 -> {
                        rect.left = max(0f, rect.left + (x - lastX))
                        rect.bottom = min(height.toFloat(), rect.bottom + (y - lastY))
                    }
                    4 -> {
                        rect.right = min(width.toFloat(), rect.right + (x - lastX))
                        rect.bottom = min(height.toFloat(), rect.bottom + (y - lastY))
                    }
                }
                lastX = x
                lastY = y
                invalidate()
            }
        }
        return true
    }
}