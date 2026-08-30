package com.ousuan.smartbutler.ui.mascot

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.ousuan.smartbutler.model.MascotLook
import com.ousuan.smartbutler.util.MascotManager

/**
 * 吉祥物悬浮窗全局管理器。
 *
 * - 在 [MainActivity] 创建时 [attach]，销毁时 [detach]；
 * - 悬浮小鸥可拖拽，松手后位置按页面持久化（相对百分比），页面重建自动恢复；
 * - 跟随全局换装即时刷新，并可响应业务事件 [playEmotion]（如记账成功 → 开心）。
 */
object MascotOverlayManager {

    private const val PREFS_NAME = "mascot_overlay_prefs"
    private const val DEFAULT_X_PCT = 0.82f
    private const val DEFAULT_Y_PCT = 0.56f

    /** 底部安全区高度：避免悬浮小鸥遮挡底部导航栏（dp） */
    private const val BOTTOM_SAFE_DP = 84

    private var hostActivity: Activity? = null
    private var overlay: MascotOverlayView? = null
    private var prefs: SharedPreferences? = null
    private var observer: ((MascotLook) -> Unit)? = null

    /** 挂载到 Activity（通常 MainActivity） */
    fun attach(activity: Activity) {
        detach()
        hostActivity = activity
        prefs = activity.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val view = MascotOverlayView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                dp(activity, 96),
                dp(activity, 96),
                Gravity.TOP or Gravity.START
            )
            // 底部安全区：小鸥不允许拖到导航栏区域
            bottomInset = dp(activity, BOTTOM_SAFE_DP)
            onDragEnd = { savePosition() }
        }
        (activity.window.decorView as ViewGroup).addView(view)
        overlay = view
        restorePosition()

        // 跟随全局换装
        observer = { look -> view.render(look) }
        MascotManager.observe(observer!!)
    }

    /** 移除悬浮窗（Activity 销毁 / 切换时调用） */
    fun detach() {
        observer?.let { MascotManager.removeObserver(it) }
        observer = null
        overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        overlay = null
        hostActivity = null
    }

    /** 业务事件：切换小鸥表情（持久化，如用户手动选择表情） */
    fun playEmotion(facePartId: String) {
        if (overlay != null) MascotManager.switchFace(facePartId)
    }

    /** 业务事件：临时闪显表情（如记账成功 → face_happy），[durationMs] 后自动恢复当前形象 */
    fun flashEmotion(facePartId: String, durationMs: Long = 2500) {
        val view = overlay ?: return
        val original = MascotManager.currentLook()
        view.render(original.copy(faceId = facePartId))
        view.postDelayed({ view.render(MascotManager.currentLook()) }, durationMs)
    }

    /** 恢复为默认形象 */
    fun resetLook() {
        MascotManager.switchToDefault()
    }

    // ===================== 位置持久化 =====================

    private fun positionKey() = hostActivity?.javaClass?.simpleName ?: "global"

    private fun savePosition() {
        val view = overlay ?: return
        val parent = view.parent as? ViewGroup ?: return
        if (parent.width == 0 || parent.height == 0) return
        val maxX = (parent.width - view.width).coerceAtLeast(1)
        val maxY = (parent.height - view.height - view.bottomInset).coerceAtLeast(1)
        val xPct = view.translationX / maxX
        val yPct = view.translationY / maxY
        prefs?.edit()
            ?.putFloat("${positionKey()}_x", xPct)
            ?.putFloat("${positionKey()}_y", yPct)
            ?.apply()
    }

    private fun restorePosition() {
        val view = overlay ?: return
        val key = positionKey()
        val xPct = prefs?.getFloat("${key}_x", DEFAULT_X_PCT) ?: DEFAULT_X_PCT
        val yPct = prefs?.getFloat("${key}_y", DEFAULT_Y_PCT) ?: DEFAULT_Y_PCT
        view.post {
            val parent = view.parent as? ViewGroup ?: return@post
            if (parent.width == 0 || parent.height == 0) return@post
            val maxX = (parent.width - view.width).coerceAtLeast(1)
            val maxY = (parent.height - view.height - view.bottomInset).coerceAtLeast(1)
            view.translationX = maxX * xPct
            view.translationY = maxY * yPct
        }
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
