package com.ousuan.smartbutler.util

import android.content.Context
import android.content.SharedPreferences
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import com.google.gson.Gson
import com.ousuan.smartbutler.R
import com.ousuan.smartbutler.model.MascotLook
import com.ousuan.smartbutler.model.MascotPart
import com.ousuan.smartbutler.model.PartCategory
import com.ousuan.smartbutler.model.UnlockType

/**
 * 吉祥物「小鸥」管理器（分层换装版）。
 *
 * 形象 = [MascotLook]（body / face / head / neck / eye / hand 各部位部件 ID 组合），
 * 持久化到 SharedPreferences（Gson JSON 序列化），通过观察者通知各页面刷新。
 *
 * 渲染顺序（底 → 顶）：BODY → NECK → FACE → EYE → HAND → HEAD
 * 分层叠加能力由 [MascotOverlayView] / [applyLookTo] 提供。
 */
object MascotManager {

    private const val PREFS_NAME = "mascot_prefs"
    private const val KEY_LOOK_JSON = "mascot_look_json"

    private lateinit var prefs: SharedPreferences
    private val gson = Gson()

    /** 当前形象的内存缓存，避免 SharedPreferences apply() 异步导致读取到旧值 */
    private var cachedLook: MascotLook? = null

    /** 观察者：形象（含任意部位）变化时回调 */
    private val listeners = mutableSetOf<(MascotLook) -> Unit>()

    // ===================== 部件库 =====================

    /** 身体基底：仅白色团子一种 */
    val bodyParts = listOf(
        MascotPart("body_white", "小鸥本鸥", PartCategory.BODY, R.drawable.mascot_body_white)
    )

    /** 表情层 */
    val faceParts = listOf(
        MascotPart("face_default", "默认", PartCategory.FACE, R.drawable.mascot_face_default),
        MascotPart("face_happy", "开心", PartCategory.FACE, R.drawable.mascot_face_happy),
        MascotPart("face_alert", "提醒", PartCategory.FACE, R.drawable.mascot_face_alert)
    )

    /** 头饰层 */
    val headParts = listOf(
        MascotPart.none(PartCategory.HEAD),
        MascotPart("head_headphones", "耳机", PartCategory.HEAD, R.drawable.mascot_head_headphones),
        MascotPart("head_hat", "渔夫帽", PartCategory.HEAD, R.drawable.mascot_head_hat),
        MascotPart("head_bow", "蝴蝶结", PartCategory.HEAD, R.drawable.mascot_head_bow, UnlockType.ACHIEVEMENT)
    )

    /** 颈饰层 */
    val neckParts = listOf(
        MascotPart.none(PartCategory.NECK),
        MascotPart("neck_scarf", "红围巾", PartCategory.NECK, R.drawable.mascot_neck_scarf)
    )

    /** 眼饰层（预留） */
    val eyeParts = listOf(MascotPart.none(PartCategory.EYE))

    /** 手持层 */
    val handParts = listOf(
        MascotPart.none(PartCategory.HAND),
        MascotPart("hand_coin", "小金币", PartCategory.HAND, R.drawable.mascot_hand_coin)
    )

    /** 按部位取部件列表 */
    fun partsOf(category: PartCategory): List<MascotPart> = when (category) {
        PartCategory.BODY -> bodyParts
        PartCategory.FACE -> faceParts
        PartCategory.HEAD -> headParts
        PartCategory.NECK -> neckParts
        PartCategory.EYE -> eyeParts
        PartCategory.HAND -> handParts
    }

    /** 全库部件（用于换装弹窗） */
    val allParts: List<MascotPart> by lazy {
        bodyParts + faceParts + headParts + neckParts + eyeParts + handParts
    }

    /** 按 ID 查部件 */
    fun findPart(id: String?): MascotPart? =
        if (id == null) null else allParts.firstOrNull { it.id == id }

    // ===================== 初始化 =====================

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ===================== 当前形象 =====================

    /** 当前形象组合 */
    fun currentLook(): MascotLook {
        cachedLook?.let { return it }
        val json = prefs.getString(KEY_LOOK_JSON, null)
            ?: return MascotLook().also { cachedLook = it }
        return try {
            (gson.fromJson(json, MascotLook::class.java) ?: MascotLook())
                .also { cachedLook = it }
        } catch (e: Exception) {
            MascotLook().also { cachedLook = it }
        }
    }

    /** 按当前 look 解析出各部位部件（含隐藏层的 none） */
    fun currentParts(): Map<PartCategory, MascotPart> = resolveParts(currentLook())

    /** 将 [MascotLook] 解析为各部位部件映射 */
    fun resolveParts(look: MascotLook): Map<PartCategory, MascotPart> {
        val map = mutableMapOf<PartCategory, MascotPart>()
        map[PartCategory.BODY] = findPart(look.bodyId) ?: bodyParts.first()
        map[PartCategory.FACE] = findPart(look.faceId) ?: faceParts.first()
        map[PartCategory.HEAD] = findPart(look.headId) ?: MascotPart.none(PartCategory.HEAD)
        map[PartCategory.NECK] = findPart(look.neckId) ?: MascotPart.none(PartCategory.NECK)
        map[PartCategory.EYE] = findPart(look.eyeId) ?: MascotPart.none(PartCategory.EYE)
        map[PartCategory.HAND] = findPart(look.handId) ?: MascotPart.none(PartCategory.HAND)
        return map
    }

    /** 切换整体形象 */
    fun switchLook(look: MascotLook) {
        cachedLook = look
        prefs.edit().putString(KEY_LOOK_JSON, gson.toJson(look)).apply()
        listeners.forEach { it(look) }
    }

    /** 更新单个部位（如只换表情 / 只换头饰） */
    fun setPart(category: PartCategory, partId: String?) {
        val current = currentLook()
        val next = when (category) {
            PartCategory.BODY -> current.copy(bodyId = partId ?: MascotLook.DEFAULT_BODY)
            PartCategory.FACE -> current.copy(faceId = partId ?: MascotLook.DEFAULT_FACE)
            PartCategory.HEAD -> current.copy(headId = partId)
            PartCategory.NECK -> current.copy(neckId = partId)
            PartCategory.EYE -> current.copy(eyeId = partId)
            PartCategory.HAND -> current.copy(handId = partId)
        }
        switchLook(next)
    }

    /** 快捷：切换表情状态 */
    fun switchFace(facePartId: String) = setPart(PartCategory.FACE, facePartId)

    /** 快捷：默认形象 */
    fun switchToDefault() = switchLook(MascotLook())

    // ===================== 观察者 =====================

    /** 注册监听并立即回调一次当前形象 */
    fun observe(listener: (MascotLook) -> Unit): MascotLook {
        listeners.add(listener)
        listener(currentLook())
        return currentLook()
    }

    fun removeObserver(listener: (MascotLook) -> Unit) {
        listeners.remove(listener)
    }

    // ===================== 渲染 =====================

    /**
     * 将当前形象按分层顺序渲染进 [container]（清空旧子视图后叠加各层 ImageView）。
     * 供首页头像、我的页大头像、换装预览等静态展示复用。
     */
    fun applyLookTo(container: ViewGroup, look: MascotLook = currentLook()) {
        container.removeAllViews()
        val parts = resolveParts(look)
        // 渲染顺序（底 → 顶）
        val order = listOf(
            PartCategory.BODY, PartCategory.NECK, PartCategory.FACE,
            PartCategory.EYE, PartCategory.HAND, PartCategory.HEAD
        )
        for (category in order) {
            val part = parts[category] ?: continue
            if (part.drawableRes == 0) continue // 该层为「无」
            val iv = ImageView(container.context).apply {
                setImageResource(part.drawableRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            container.addView(
                iv,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
    }
}
