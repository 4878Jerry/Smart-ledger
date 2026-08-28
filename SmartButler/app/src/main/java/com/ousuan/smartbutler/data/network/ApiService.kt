package com.ousuan.smartbutler.data.network

import com.ousuan.smartbutler.data.model.AddCommentResponse
import com.ousuan.smartbutler.data.model.ApiResponse
import com.ousuan.smartbutler.data.model.BudgetData
import com.ousuan.smartbutler.data.model.BudgetUpdateRequest
import com.ousuan.smartbutler.data.model.CommentRequest
import com.ousuan.smartbutler.data.model.CommentsResponse
import com.ousuan.smartbutler.data.model.CreatePostResponse
import com.ousuan.smartbutler.data.model.LikeResponse
import com.ousuan.smartbutler.data.model.LoginRequest
import com.ousuan.smartbutler.data.model.LoginResponse
import com.ousuan.smartbutler.data.model.PostRequest
import com.ousuan.smartbutler.data.model.PostUpdateRequest
import com.ousuan.smartbutler.data.model.PublicStatsResponse
import com.ousuan.smartbutler.data.model.RegisterRequest
import com.ousuan.smartbutler.data.model.RegisterResponse
import com.ousuan.smartbutler.data.model.SyncRequest
import com.ousuan.smartbutler.data.model.SyncResponse
import com.ousuan.smartbutler.data.model.TransactionOut
import com.ousuan.smartbutler.data.model.UserOut
import com.ousuan.smartbutler.data.model.UserSettingsRequest
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/**
 * 后端 API 接口定义（Base URL 统一使用 [ApiConfig.BASE_URL]）。
 * 所有接口均返回统一格式 { code, data, msg }。
 */
interface ApiService {

    // ---------- 认证 ----------

    @POST(ApiConfig.LOGIN)
    suspend fun login(@Body body: LoginRequest): LoginResponse

    @POST(ApiConfig.REGISTER)
    suspend fun register(@Body body: RegisterRequest): RegisterResponse

    /** 更新当前用户设置（需 token）：同步「数据公开」全局开关到服务器 */
    @PUT(ApiConfig.USER_SETTINGS)
    suspend fun updateUserSettings(@Body body: UserSettingsRequest): ApiResponse<UserOut>

    /** 获取当前用户预算（需 token）：{budget: {分类: 金额}} */
    @GET(ApiConfig.USER_BUDGET)
    suspend fun getBudget(): ApiResponse<BudgetData>

    /** 整体覆盖保存当前用户预算（需 token） */
    @PUT(ApiConfig.USER_BUDGET)
    suspend fun putBudget(@Body body: BudgetUpdateRequest): ApiResponse<BudgetData>

    // ---------- 交易记录（需 token） ----------

    @GET(ApiConfig.TRANSACTIONS)
    suspend fun getTransactions(): ApiResponse<List<TransactionOut>>

    @POST(ApiConfig.SYNC)
    suspend fun syncTransactions(@Body body: SyncRequest): SyncResponse

    @DELETE("${ApiConfig.TRANSACTIONS}/{id}")
    suspend fun deleteTransaction(@Path("id") id: Long): ApiResponse<Unit>

    // ---------- 社区 ----------

    /** 所有用户公开统计数据（无需 token） */
    @GET(ApiConfig.PUBLIC_STATS)
    suspend fun getPublicStats(): ApiResponse<List<PublicStatsResponse>>

    /** 当前用户发布的帖子（需 token；登录后用于恢复本地数据） */
    @GET("${ApiConfig.POSTS}/mine")
    suspend fun getMyPosts(): ApiResponse<List<PublicStatsResponse>>

    /** 发布月度统计（需 token） */
    @POST(ApiConfig.POSTS)
    suspend fun createPost(@Body body: PostRequest): CreatePostResponse

    /** 更新帖子可见度（需 token，仅本人）：帖子级 + 两个模块级 */
    @PUT("${ApiConfig.POSTS}/{postId}")
    suspend fun updatePost(@Path("postId") postId: Long, @Body body: PostUpdateRequest): ApiResponse<PublicStatsResponse>

    /** 发表评论（需 token） */
    @POST(ApiConfig.COMMENTS)
    suspend fun addComment(@Body body: CommentRequest): AddCommentResponse

    /** 获取评论列表（无需 token） */
    @GET("${ApiConfig.COMMENTS}/{postId}")
    suspend fun getComments(@Path("postId") postId: Long): CommentsResponse

    /** 点赞 / 取消点赞（需 token） */
    @POST("${ApiConfig.LIKE}/{postId}")
    suspend fun likePost(@Path("postId") postId: Long): LikeResponse
}
