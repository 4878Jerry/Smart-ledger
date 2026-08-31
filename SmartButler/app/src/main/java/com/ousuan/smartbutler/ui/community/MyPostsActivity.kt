package com.ousuan.smartbutler.ui.community

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.ousuan.smartbutler.SmartButlerApp
import com.ousuan.smartbutler.data.model.CommunityPost
import com.ousuan.smartbutler.data.repository.CommunityRepository
import com.ousuan.smartbutler.databinding.ActivityMyPostsBinding
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 我的帖子：展示当前登录用户发布过的所有帖子
 * （含「仅自己可见」的私有帖，数据来自 /api/posts/mine + 本地）。
 */
class MyPostsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMyPostsBinding
    private lateinit var adapter: CommunityAdapter

    /** 每个帖子的点赞互斥锁：串行化 toggle 请求，防止连点并发导致服务器状态错位 */
    private val likeMutexes = ConcurrentHashMap<String, Mutex>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMyPostsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = CommunityAdapter(
            onLike = { postId -> like(postId) },
            onToggleExpand = { postId -> toggleExpand(postId) },
            onSendComment = { postId, content -> sendComment(postId, content) },
            onToggleVisibility = { post -> toggleVisibility(post) },
            onToggleModuleVisibility = { post, module -> toggleModuleVisibility(post, module) }
        )
        adapter.showVisibilityControl = true // 我的帖子页显示可见度编辑行
        adapter.loadLikedIds(this, currentUserId()) // 恢复本账号点赞状态
        binding.rvPosts.layoutManager = LinearLayoutManager(this)
        binding.rvPosts.adapter = adapter
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val posts = CommunityRepository.fetchMyPosts()
            adapter.setData(posts)
            binding.tvEmpty.visibility = if (posts.isEmpty()) View.VISIBLE else View.GONE
            // 在线拉取成功：用服务器权威 liked 状态校准本地点赞记忆（自愈错位）
            if (!CommunityRepository.isOfflineMode) {
                adapter.reconcileLikedIds(this@MyPostsActivity, currentUserId(), posts)
            }
        }
    }

    /** 切换帖子可见度：公开 ⇄ 仅自己可见（在线实时同步服务器，离线提示） */
    private fun toggleVisibility(post: CommunityPost) {
        val newVisibility = if (post.visibility == "private") "public" else "private"
        lifecycleScope.launch {
            CommunityRepository.updatePostVisibility(post, newVisibility)
                .onSuccess {
                    val tip = if (newVisibility == "private") "已设为仅自己可见" else "已设为公开"
                    Toast.makeText(this@MyPostsActivity, tip, Toast.LENGTH_SHORT).show()
                    refresh()
                }
                .onFailure { e ->
                    Toast.makeText(this@MyPostsActivity, e.message ?: "修改可见度失败", Toast.LENGTH_SHORT).show()
                }
        }
    }

    /**
     * 切换模块可见度：消费数据（module="data"）/ 预算方案（module="budget"）独立 公开 ⇄ 仅自己可见。
     * 调用 PUT /api/posts/{id} 只更新对应模块字段（帖子级可见度保持不变）。
     */
    private fun toggleModuleVisibility(post: CommunityPost, module: String) {
        val newData = if (module == "data")
            if (post.dataVisibility == "private") "public" else "private"
            else post.dataVisibility
        val newBudget = if (module == "budget")
            if (post.budgetVisibility == "private") "public" else "private"
            else post.budgetVisibility
        val tip = when (module) {
            "data" -> if (newData == "private") "消费数据已设为仅自己可见" else "消费数据已设为公开"
            else -> if (newBudget == "private") "预算方案已设为仅自己可见" else "预算方案已设为公开"
        }
        lifecycleScope.launch {
            CommunityRepository.updatePostVisibility(post, post.visibility, newData, newBudget)
                .onSuccess {
                    Toast.makeText(this@MyPostsActivity, tip, Toast.LENGTH_SHORT).show()
                    refresh()
                }
                .onFailure { e ->
                    Toast.makeText(this@MyPostsActivity, e.message ?: "修改模块可见度失败", Toast.LENGTH_SHORT).show()
                }
        }
    }

    /** 当前登录用户 ID（点赞状态按账号隔离存储） */
    private fun currentUserId(): String? =
        (application as SmartButlerApp).userRepository.getCurrentUser()?.userId

    /** 点赞 / 取消：同一帖子串行执行（防连点并发 toggle 乱序），乐观更新 + 服务器权威校准 + 失败回滚 */
    private fun like(postId: String) {
        val mutex = likeMutexes.getOrPut(postId) { Mutex() }
        lifecycleScope.launch {
            mutex.withLock {
                val targetLiked = postId !in adapter.likedIds
                applyLikeUi(postId, targetLiked)
                CommunityRepository.likePost(postId, shouldLike = targetLiked)
                    .onSuccess { data ->
                        // 服务器权威状态写回，自愈历史错位
                        applyLikeUi(postId, data.liked)
                        if (!adapter.updateLikes(postId, data.likes)) refresh()
                    }
                    .onFailure { e ->
                        if ((postId in adapter.likedIds) == targetLiked) {
                            applyLikeUi(postId, !targetLiked)
                        }
                        Toast.makeText(this@MyPostsActivity, e.message ?: "点赞失败", Toast.LENGTH_SHORT).show()
                    }
            }
        }
    }

    /** 统一点赞状态更新：改 likedIds + 落盘 + 重绘单条 */
    private fun applyLikeUi(postId: String, liked: Boolean) {
        if (liked) adapter.likedIds.add(postId) else adapter.likedIds.remove(postId)
        adapter.persistLikedIds(this, currentUserId())
        adapter.notifyItemChangedByPostId(postId)
    }

    private fun toggleExpand(postId: String) {
        if (postId in adapter.expandedIds) {
            adapter.expandedIds.remove(postId)
        } else {
            adapter.expandedIds.add(postId)
        }
        refresh()
    }

    private fun sendComment(postId: String, content: String) {
        lifecycleScope.launch {
            CommunityRepository.addComment(postId, content)
                .onSuccess {
                    adapter.expandedIds.add(postId)
                    refresh()
                }
                .onFailure { e ->
                    Toast.makeText(this@MyPostsActivity, e.message ?: "评论失败", Toast.LENGTH_SHORT).show()
                }
        }
    }
}
