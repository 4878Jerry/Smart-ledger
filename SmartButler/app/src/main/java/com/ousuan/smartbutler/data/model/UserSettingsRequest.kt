package com.ousuan.smartbutler.data.model

import com.google.gson.annotations.SerializedName

/**
 * 用户设置更新请求：目前仅「数据公开」全局开关。
 * is_data_public = 1 公开（默认），0 不公开（该用户帖子不再出现在公开流）。
 */
data class UserSettingsRequest(
    @SerializedName("is_data_public") val isDataPublic: Int
)
