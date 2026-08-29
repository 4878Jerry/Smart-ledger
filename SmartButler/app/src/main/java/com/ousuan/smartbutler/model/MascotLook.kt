package com.ousuan.smartbutler.model

/**
 * 小鸥当前形象：由各部位部件组合而成。
 * 分层叠加顺序（底 → 顶）：body → neck → face → eye → hand → head。
 */
data class MascotLook(
    val bodyId: String = MascotLook.DEFAULT_BODY,
    val faceId: String = MascotLook.DEFAULT_FACE,
    val headId: String? = MascotLook.DEFAULT_HEAD,
    val neckId: String? = null,
    val eyeId: String? = null,
    val handId: String? = MascotLook.DEFAULT_HAND
) {
    companion object {
        const val DEFAULT_BODY = "body_white"
        const val DEFAULT_FACE = "face_default"
        const val DEFAULT_HEAD = "head_headphones"
        const val DEFAULT_HAND = "hand_coin"
    }
}
