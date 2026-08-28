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
import kotlinx.coroutines.launch

/**
 * 我的帖子：展示当前登录用户发布过的所有帖子
 * （含「仅自己可见」的私有帖，数据来自 /api/posts/mine + 本地）。
 */
class MyPostsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMyPostsBinding
    private lateinit var adapter: CommunityAdapter

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
        binding.rvPosts.layoutManager = LinearLayoutManager(this)
        binding.rvPosts.adapter = adapter
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val posts = CommunityRepository.fetchMyPosts()
            adapter.setData(posts)
            binding.tvEmpty.visibility = if (posts.isEmpty()) View.VISIBLE else View.GONE
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

    private fun like(postId: String) {
        lifecycleScope.launch {
            CommunityRepository.likePost(postId)
                .onSuccess {
                    adapter.likedIds.add(postId)
                    refresh()
                }
                .onFailure { e ->
                    Toast.makeText(this@MyPostsActivity, e.message ?: "点赞失败", Toast.LENGTH_SHORT).show()
                }
        }
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
