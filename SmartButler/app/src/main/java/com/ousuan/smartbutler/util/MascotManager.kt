package com.ousuan.smartbutler.util

import android.content.Context
import android.content.SharedPreferences
import android.widget.ImageView
import com.ousuan.smartbutler.R

/**
 * 吉祥物「小鸥」管理器。
 *
 * 职责：
 * 1. 持久化用户当前选择的吉祥物形象（SharedPreferences，按账号无关，全局生效）；
 * 2. 提供形象切换接口，供「我的 → 小鸥换装」使用；
 * 3. 通过监听器通知各页面 ImageView 即时刷新。
 *
 * 扩展预留：
 * - [MascotSkin] 已为「装扮」预留：后续可支持帽子 / 围巾 / 墨镜等配饰叠加，
 *   只需扩展枚举并在 [MascotManager.applySkin] 中实现叠加逻辑；
 * - [Mascot.STYLES] 为「形象图鉴」，后续增加新形象只需在 drawable 下新增
 *   矢量资源并在枚举中登记即可，无需改动调用方。
 */
object MascotManager {

    private const val PREFS_NAME = "mascot_prefs"
    private const val KEY_MASCOT_ID = "mascot_id"
    private const val KEY_MASCOT_SKIN = "mascot_skin"

    /** 形象风格标识 */
    const val STYLE_NONE = "none"
    const val STYLE_HAT = "hat"
    const val STYLE_SCARF = "scarf"

    /** 吉祥物形象列表（换装图鉴） */
    enum class Mascot(
        val id: String,
        val displayName: String,
        val drawableRes: Int
    ) {
        DEFAULT("default", "记账小鸥", R.drawable.ic_mascot),
        HAPPY("happy", "开心小鸥", R.drawable.ic_mascot_happy),
        ALERT("alert", "提醒小鸥", R.drawable.ic_mascot_alert),
        HAT("hat", "渔夫帽小鸥", R.drawable.ic_mascot_hat)
    }

    /** 装扮（预留：帽子 / 围巾 / 墨镜……），当前仅支持无装扮 */
    enum class MascotSkin(val id: String, val displayName: String) {
        NONE(STYLE_NONE, "无装扮")
    }

    private lateinit var prefs: SharedPreferences

    /** 形象切换监听器（用于刷新各页面 ImageView） */
    private val listeners = mutableSetOf<(Mascot) -> Unit>()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 当前形象（未设置时默认 DEFAULT） */
    fun current(): Mascot =
        Mascot.values().firstOrNull { it.id == prefs.getString(KEY_MASCOT_ID, null) } ?: Mascot.DEFAULT

    /** 当前装扮（预留） */
    fun currentSkin(): MascotSkin =
        MascotSkin.values().firstOrNull { it.id == prefs.getString(KEY_MASCOT_SKIN, null) } ?: MascotSkin.NONE

    /** 切换形象：持久化并通知所有监听页面刷新 */
    fun switchMascot(mascot: Mascot) {
        prefs.edit().putString(KEY_MASCOT_ID, mascot.id).apply()
        listeners.forEach { it(mascot) }
    }

    /** 设置装扮（预留接口：后续在此叠加装扮 drawable） */
    fun setSkin(skin: MascotSkin) {
        prefs.edit().putString(KEY_MASCOT_SKIN, skin.id).apply()
        listeners.forEach { it(current()) }
    }

    /** 注册监听，返回当前形象 */
    fun observe(listener: (Mascot) -> Unit): Mascot {
        listeners.add(listener)
        return current()
    }

    fun removeObserver(listener: (Mascot) -> Unit) {
        listeners.remove(listener)
    }

    /** 便捷：将当前形象应用到 ImageView，并按需注册监听 */
    fun applyTo(imageView: ImageView, followChanges: Boolean = true): Mascot {
        val mascot = observe { m -> imageView.setImageResource(m.drawableRes) }
        imageView.setImageResource(mascot.drawableRes)
        if (!followChanges) removeObserver { }
        return mascot
    }

    /** 预留给装扮叠加：根据形象与装扮计算最终显示资源（当前装扮仅无装扮） */
    fun resolveDrawable(mascot: Mascot, skin: MascotSkin = currentSkin()): Int {
        // TODO(装扮开发): 当 skin != NONE 时，返回叠加装扮后的组合 drawable
        return mascot.drawableRes
    }
}
