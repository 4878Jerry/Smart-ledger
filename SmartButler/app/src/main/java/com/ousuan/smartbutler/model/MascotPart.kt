package com.ousuan.smartbutler.model

import androidx.annotation.DrawableRes

/**
 * 吉祥物部件分类。
 */
enum class PartCategory {
    BODY, FACE, HEAD, NECK, EYE, HAND
}

/**
 * 部件解锁方式。
 */
enum class UnlockType {
    DEFAULT,    // 默认解锁
    ACHIEVEMENT // 成就解锁
}

/**
 * 小鸥单个装扮部件。
 *
 * @param id 唯一标识，如 "head_headphones"
 * @param displayName 展示名
 * @param category 所属部位层
 * @param drawableRes 矢量资源 ID
 * @param unlockType 解锁方式
 */
data class MascotPart(
    val id: String,
    val displayName: String,
    val category: PartCategory,
    @DrawableRes val drawableRes: Int,
    val unlockType: UnlockType = UnlockType.DEFAULT
) {
    /** 空部件（用于某一层不穿戴任何装饰）。 */
    companion object {
        fun none(category: PartCategory) = MascotPart(
            id = "${category.name.lowercase()}_none",
            displayName = "无",
            category = category,
            drawableRes = 0
        )
    }
}
