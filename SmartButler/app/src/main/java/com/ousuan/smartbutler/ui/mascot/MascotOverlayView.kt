package com.ousuan.smartbutler.ui.mascot

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.ousuan.smartbutler.model.MascotLook
import com.ousuan.smartbutler.model.PartCategory
import com.ousuan.smartbutler.util.MascotManager

/**
 * 可拖拽的吉祥物悬浮视图。
 *
 * - 内部按分层顺序渲染小鸥（body → neck → face → eye → hand → head）；
 * - 支持手指拖拽（超过阈值后进入拖拽模式）、松手自动吸附左右边缘；
 * - 位置由 [MascotOverlayManager] 按页面持久化，重建后自动恢复；
 * - 待机时做轻微上下浮动呼吸动画，点击时抖动反馈。
 */
class MascotOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val layers = mutableMapOf<PartCategory, ImageView>()
    private val content = FrameLayout(context).apply {
        layoutParams = LayoutParams(dp(88), dp(88), Gravity.CENTER)
    }

    // 拖拽状态
    private var downX = 0f
    private var downY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var downTime = 0L
    private var isDragging = false
    private var viewToParentX = 0f
    private var viewToParentY = 0f
    private var touchSlop = 0

    /** 拖拽结束回调（由 Manager 持久化位置） */
    var onDragEnd: (() -> Unit)? = null

    /** 底部安全区高度（像素），防止小鸥遮挡底部导航栏 */
    var bottomInset = 0
        set(value) {
            field = value
            if (parent != null && !isDragging) {
                // 当安全区变化时，自动把当前位置限制到新范围内
                translationY = translationY.coerceIn(0f, computeMaxY())
            }
        }

    private fun computeMaxX(): Float {
        val parentView = parent as? View
        val raw = (parentView?.width ?: 0) - width.toFloat()
        return raw.coerceAtLeast(0f)
    }

    private fun computeMaxY(): Float {
        val parentView = parent as? View
        val raw = (parentView?.height ?: 0) - height.toFloat() - bottomInset
        return raw.coerceAtLeast(0f)
    }

    init {
        touchSlop = ViewConfigurationCompat.touchSlop(context)
        isClickable = true
        addView(content)

        // 预创建各层 ImageView
        for (category in LAYER_ORDER) {
            val iv = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            content.addView(
                iv,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            )
            layers[category] = iv
        }
        render(MascotManager.currentLook())
        startIdleAnimation()
    }

    // ===================== 渲染 =====================

    fun render(look: MascotLook) {
        val parts = MascotManager.resolveParts(look)
        for (category in LAYER_ORDER) {
            val part = parts[category] ?: continue
            val iv = layers[category] ?: continue
            if (part.drawableRes == 0) {
                iv.setImageDrawable(null)
            } else {
                iv.setImageResource(part.drawableRes)
            }
        }
    }

    /** 刷新（跟随全局形象变化） */
    fun refresh() = render(MascotManager.currentLook())

    // ===================== 拖拽 =====================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastRawX = event.rawX
                lastRawY = event.rawY
                downTime = System.currentTimeMillis()
                isDragging = false
                stopIdleAnimation()
                // 同步拖拽基准为当前位置，避免按下瞬间小鸥「瞬移」到左上角
                viewToParentX = translationX
                viewToParentY = translationY
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastRawX
                val dy = event.rawY - lastRawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                if (!isDragging) {
                    val dist = Math.hypot((event.x - downX).toDouble(), (event.y - downY).toDouble())
                    if (dist > touchSlop * 2) isDragging = true
                }
                if (isDragging) {
                    viewToParentX += dx
                    viewToParentY += dy
                    translationX = viewToParentX.coerceIn(0f, computeMaxX())
                    translationY = viewToParentY.coerceIn(0f, computeMaxY())
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (isDragging) {
                    isDragging = false
                    snapToEdge()
                    onDragEnd?.invoke()
                } else {
                    onTap()
                }
                startIdleAnimation()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 松手后吸附到左右最近边缘（y 保持在不遮挡导航栏的范围内） */
    private fun snapToEdge() {
        val parentView = parent as? View
        val parentWidth = parentView?.width ?: width
        val targetX = if (translationX + width / 2f > parentWidth / 2f) {
            computeMaxX()
        } else {
            0f
        }
        animate()
            .translationX(targetX)
            .translationY(translationY.coerceIn(0f, computeMaxY()))
            .setDuration(160)
            .start()
    }

    /** 点击反馈：快速抖动 */
    private fun onTap() {
        animate()
            .scaleX(0.9f).scaleY(0.9f).setDuration(80)
            .withEndAction {
                animate().scaleX(1f).scaleY(1f).setDuration(100).start()
            }
            .start()
    }

    // ===================== 待机呼吸动画 =====================

    private var idleAnimator: ValueAnimator? = null

    private fun startIdleAnimation() {
        if (idleAnimator != null) return
        idleAnimator = ObjectAnimator.ofFloat(content, "translationY", 0f, -dp(5).toFloat()).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopIdleAnimation() {
        idleAnimator?.cancel()
        idleAnimator = null
        content.translationY = 0f
    }

    // ===================== 工具 =====================

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        /** 分层渲染顺序（底 → 顶） */
        val LAYER_ORDER = listOf(
            PartCategory.BODY, PartCategory.NECK, PartCategory.FACE,
            PartCategory.EYE, PartCategory.HAND, PartCategory.HEAD
        )
    }
}

/** touchSlop 兼容封装 */
private object ViewConfigurationCompat {
    fun touchSlop(context: Context): Int =
        android.view.ViewConfiguration.get(context).scaledTouchSlop
}
