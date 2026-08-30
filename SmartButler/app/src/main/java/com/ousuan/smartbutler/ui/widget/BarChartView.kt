package com.ousuan.smartbutler.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * 极简柱状图：自绘 Canvas，避免引入第三方图表库。
 *
 * 特性：
 * - 自动按最大值归一化（最高柱填满 80% 高度，留出顶部数字空间）
 * - 每根柱顶部绘制数值（黑色加粗）
 * - 每根柱下方绘制横轴标签（如：一/二/三/四/五/六/日）
 * - 柱子颜色支持分段：中等值青绿、偏低绿、最高值红（峰值警示）
 *
 * 用法：见 [PieChartFragment] 中的「近 7 天支出趋势」。
 */
class BarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    data class Bar(val label: String, val value: Float, val color: Int = COLOR_NORMAL)

    private val bars = mutableListOf<Bar>()
    private var maxValue: Float = 0f

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(11f)
        color = Color.parseColor("#90A4AE")
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(12f)
        isFakeBoldText = true
        color = Color.parseColor("#37474F")
    }
    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E6EA")
        strokeWidth = dp(1f)
    }

    private val tmpRect = RectF()

    /** 设置数据；传入 null / 空数组清空 */
    fun setData(list: List<Bar>?) {
        bars.clear()
        if (list != null) bars.addAll(list)
        maxValue = bars.maxOfOrNull { it.value } ?: 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bars.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()
        // 顶部留 28dp 画数字，底部留 28dp 画横轴
        val topReserve = dp(28f)
        val bottomReserve = dp(28f)
        val drawableHeight = h - topReserve - bottomReserve

        // 底部基线
        canvas.drawLine(0f, h - bottomReserve, w, h - bottomReserve, baselinePaint)

        val count = bars.size
        // 等宽分布：每柱宽度 = 总宽 / 数量（柱间留小缝隙）
        val slot = w / count
        val barWidth = slot * 0.6f
        val gap = (slot - barWidth) / 2f

        val ratio = if (maxValue > 0f) (drawableHeight * 0.85f) / maxValue else 0f

        bars.forEachIndexed { idx, bar ->
            val left = idx * slot + gap
            val centerX = left + barWidth / 2f
            val barH = bar.value * ratio
            val top = h - bottomReserve - barH
            val right = left + barWidth
            val bottom = h - bottomReserve

            barPaint.color = bar.color
            tmpRect.set(left, top, right, bottom)
            // 圆角柱（顶部圆角）
            val radius = barWidth / 4f
            canvas.drawRoundRect(tmpRect, radius, radius, barPaint)

            // 数值（柱顶上方）
            val valueText = if (bar.value % 1f == 0f) bar.value.toInt().toString() else "%.1f".format(bar.value)
            canvas.drawText(valueText, centerX, top - dp(6f), valuePaint)

            // 横轴标签
            canvas.drawText(bar.label, centerX, h - dp(8f), labelPaint)
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    @Suppress("DEPRECATION")
    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity

    companion object {
        // 默认色系
        val COLOR_NORMAL = Color.parseColor("#26A69A")     // 青绿
        val COLOR_ACCENT = Color.parseColor("#FF7043")     // 橙
        val COLOR_PEAK = Color.parseColor("#EF5350")       // 红（峰值）
    }
}