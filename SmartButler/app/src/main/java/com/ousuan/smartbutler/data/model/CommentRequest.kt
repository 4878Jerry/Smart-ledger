package com.ousuan.smartbutler.data.model

import com.google.gson.annotations.SerializedName

/** 发表评论请求 */
data class CommentRequest(
    @SerializedName("post_id") val postId: Long,
    @SerializedName("content") val content: String
)

/** 服务器评论 */
data class CommentOut(
    @SerializedName("id") val id: Long,
    @SerializedName("username") val username: String,
    @SerializedName("content") val content: String,
    @SerializedName("created_at") val createdAt: String
)

/** 评论列表响应（GET /api/comments/{post_id}） */
data class CommentsResponse(
    @SerializedName("code") val code: Int,
    @SerializedName("data") val data: List<CommentOut>?,
    @SerializedName("msg") val msg: String?
)

/** 发表评论响应 */
data class AddCommentResponse(
    @SerializedName("code") val code: Int,
    @SerializedName("data") val data: CommentOut?,
    @SerializedName("msg") val msg: String?
)
