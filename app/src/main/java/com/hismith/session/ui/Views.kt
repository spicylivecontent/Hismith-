package com.hismith.session.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.hismith.session.core.Challenge
import com.hismith.session.core.DayStatus

/** The last two minutes of output speed as a filled line. */
class SpeedGraphView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var data = IntArray(0)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = 0xFFD4AF37.toInt()
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val grid = Paint().apply { color = 0xFF2E2618.toInt(); strokeWidth = 1f }
    private val path = Path()
    private val area = Path()

    fun setData(values: IntArray) {
        data = values
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fill.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0x66D4AF37, 0x00D4AF37, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in 1..3) canvas.drawLine(0f, h * i / 4, w, h * i / 4, grid)
        if (data.size < 2) return
        path.reset()
        area.reset()
        val step = w / (data.size - 1)
        for (i in data.indices) {
            val x = i * step
            val y = h - (data[i].coerceIn(0, 100) / 100f) * (h - 2) - 1
            if (i == 0) { path.moveTo(x, y); area.moveTo(x, h); area.lineTo(x, y) } else { path.lineTo(x, y); area.lineTo(x, y) }
        }
        area.lineTo(w, h)
        area.close()
        canvas.drawPath(area, fill)
        canvas.drawPath(path, line)
    }
}

/** Challenge calendar: one cell per day, coloured by status. */
class ChallengeGridView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var challenge: Challenge? = null
    private var today: Int? = null
    var onDayClick: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val cell = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0xFFF3E5AB.toInt()
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
    }
    private val rect = RectF()
    private val columns = 7

    fun setChallenge(c: Challenge?, todayDay: Int?) {
        challenge = c
        today = todayDay
        requestLayout()
        invalidate()
    }

    private fun cellSize(w: Int): Float = w / columns.toFloat()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val days = challenge?.days ?: 0
        val rows = (days + columns - 1) / columns
        setMeasuredDimension(w, (rows * cellSize(w) * 0.8f).toInt().coerceAtLeast(1))
    }

    override fun onDraw(canvas: Canvas) {
        val c = challenge ?: return
        val size = cellSize(width)
        val rowH = size * 0.8f
        val pad = 3f * density
        for (day in 1..c.days) {
            val i = day - 1
            val x = (i % columns) * size
            val y = (i / columns) * rowH
            rect.set(x + pad, y + pad, x + size - pad, y + rowH - pad)
            val status = c.status(day)
            cell.color = when (status) {
                DayStatus.DONE -> 0xFFD4AF37.toInt()
                DayStatus.PARTIAL -> 0xFFE0A030.toInt()
                DayStatus.MISSED -> 0xFF8A1C1C.toInt()
                DayStatus.PENDING -> if (c.isNonstopDay(day)) 0xFF3D2A12.toInt() else 0xFF1C1A22.toInt()
            }
            canvas.drawRoundRect(rect, 8f * density, 8f * density, cell)
            if (day == today) canvas.drawRoundRect(rect, 8f * density, 8f * density, ring)
            text.color = if (status == DayStatus.DONE || status == DayStatus.PARTIAL) 0xFF08080A.toInt() else 0xFFA8A296.toInt()
            canvas.drawText(day.toString(), rect.centerX(), rect.centerY() - (text.descent() + text.ascent()) / 2, text)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val c = challenge ?: return false
        if (event.action == MotionEvent.ACTION_UP) {
            val size = cellSize(width)
            val col = (event.x / size).toInt().coerceIn(0, columns - 1)
            val row = (event.y / (size * 0.8f)).toInt()
            val day = row * columns + col + 1
            if (day in 1..c.days) {
                performClick()
                onDayClick?.invoke(day)
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
