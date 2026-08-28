package com.ousuan.smartbutler.ui.community

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.ousuan.smartbutler.R
import com.ousuan.smartbutler.data.model.CommunityComment
import com.ousuan.smartbutler.data.model.CommunityPost
import com.ousuan.smartbutler.databinding.ItemBudgetRowBinding
import com.ousuan.smartbutler.databinding.ItemCategoryBarBinding
import com.ousuan.smartbutler.databinding.ItemCommentBinding
import com.ousuan.smartbutler.databinding.ItemCommunityPostBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 社区帖子列表 Adapter：
 * - 展示脱敏统计 + 分类占比柱状条；
 * - 支持点赞、点击展开评论区、发送评论。
 */
class CommunityAdapter(
    private val onLike: (String) -> Unit,
    private val onToggleExpand: (String) -> Unit,
    private val onSendComment: (postId: String, content: String) -> Unit,
    private val onToggleVisibility: ((CommunityPost) -> Unit)? = null,
    private val onToggleModuleVisibility: ((CommunityPost, String) -> Unit)? = null
) : RecyclerView.Adapter<CommunityAdapter.PostViewHolder>() {

    private val posts = mutableListOf<CommunityPost>()

    /** 是否显示可见度编辑行（仅「我的帖子」页开启，社区流隐藏） */
    var showVisibilityControl: Boolean = false

    /** 当前已点赞的帖子 ID（用于按钮变色） */
    val likedIds = mutableSetOf<String>()

    /** 已展开评论区的帖子 ID */
    val expandedIds = mutableSetOf<String>()

    private val timeFormat = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)

    @SuppressLint("NotifyDataSetChanged")
    fun setData(list: List<CommunityPost>) {
        posts.clear()
        posts.addAll(list)
        notifyDataSetChanged()
    }

    /** 只更新单条帖子的点赞数，不重排、不重建整个列表；找不到该帖时返回 false */
    fun updateLikes(postId: String, likes: Int): Boolean {
        val index = posts.indexOfFirst { it.postId == postId }
        if (index < 0) return false
        posts[index] = posts[index].copy(likes = likes)
        notifyItemChanged(index)
        return true
    }

    /** 只更新单条帖子的评论（追加），不重排、不重建整个列表；找不到该帖时返回 false */
    fun updateComments(postId: String, comment: CommunityComment): Boolean {
        val index = posts.indexOfFirst { it.postId == postId }
        if (index < 0) return false
        val p = posts[index]
        posts[index] = p.copy(comments = p.comments + comment)
        notifyItemChanged(index)
        return true
    }

    /** 仅重绘单条（展开/收起评论区时用），不重排、不重建整个列表；找不到该帖时返回 false */
    fun notifyItemChangedByPostId(postId: String): Boolean {
        val index = posts.indexOfFirst { it.postId == postId }
        if (index < 0) return false
        notifyItemChanged(index)
        return true
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PostViewHolder {
        val binding = ItemCommunityPostBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return PostViewHolder(binding)
    }

    override fun getItemCount(): Int = posts.size

    override fun onBindViewHolder(holder: PostViewHolder, position: Int) {
        holder.bind(posts[position])
    }

    inner class PostViewHolder(
        private val binding: ItemCommunityPostBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(post: CommunityPost) {
            val ctx = binding.root.context
            val likeIcon = ContextCompat.getDrawable(ctx, R.drawable.ic_thumb_up)!!.mutate()
            val commentIcon = ContextCompat.getDrawable(ctx, R.drawable.ic_comment)!!.mutate()

            // 头部：用户名 + 首次发布 / 最近更新时间
            binding.tvUsername.text = post.username
            binding.tvTime.text = "首次发布：" + timeFormat.format(Date(post.timestamp))
            binding.tvUpdated.text = "最近更新：" + timeFormat.format(Date(post.updatedAt))

            // 月份：2026-08 -> 2026年8月
            val parts = post.month.split("-")
            binding.tvMonth.text = if (parts.size == 2) {
                "${parts[0]}年${parts[1].toIntOrNull() ?: parts[1]}月"
            } else {
                post.month
            }

            // 模块一：消费数据（categoryBreakdown 为空 = 只发预算方案的帖子，
            // 或 dataVisibility=private = 模块设为私有/服务器已脱敏，均隐藏整卡）
            val hasData = post.categoryBreakdown.isNotEmpty() && post.dataVisibility != "private"
            binding.llDataCard.visibility = if (hasData) View.VISIBLE else View.GONE
            if (hasData) {
                binding.tvTotal.text = "月度总支出 ¥" + "%.2f".format(post.totalExpense)
                binding.tvTop.text = "Top 分类：${post.topCategory}"

                // 分类占比柱状条
                bindCategoryBars(binding.llCategories, post.categoryBreakdown)

                // 省钱建议（可选）
                if (post.savingTip.isNullOrEmpty()) {
                    binding.tvTip.visibility = View.GONE
                } else {
                    binding.tvTip.visibility = View.VISIBLE
                    binding.tvTip.text = post.savingTip
                }
            }

            // 模块二：预算方案（budgetBreakdown 为空 = 未发布预算，
            // 或 budgetVisibility=private = 模块设为私有/服务器已脱敏，均隐藏整卡）
            val hasBudget = post.budgetBreakdown.isNotEmpty() && post.budgetVisibility != "private"
            binding.llBudgetCard.visibility = if (hasBudget) View.VISIBLE else View.GONE
            if (hasBudget) {
                bindBudgetRows(binding.llBudgetItems, post.budgetBreakdown)
            }

            // 两个模块都隐藏：帖子本身仍可见，但内容已被设为私有
            binding.tvPrivateHint.visibility =
                if (!hasData && !hasBudget) View.VISIBLE else View.GONE

            // 点赞按钮
            val liked = post.postId in likedIds
            binding.tvLike.apply {
                text = "赞 ${post.likes}"
                setCompoundDrawablesWithIntrinsicBounds(likeIcon, null, null, null)
                compoundDrawablePadding = 6
                setTextColor(
                    ContextCompat.getColor(ctx, if (liked) R.color.liked else R.color.text_secondary)
                )
                likeIcon.setTint(
                    ContextCompat.getColor(ctx, if (liked) R.color.liked else R.color.text_secondary)
                )
                setOnClickListener { onLike(post.postId) }
            }

            // 评论数 + 点击卡片展开/收起评论区
            binding.tvCommentCount.apply {
                text = "评论 ${post.comments.size}"
                setCompoundDrawablesWithIntrinsicBounds(commentIcon, null, null, null)
                compoundDrawablePadding = 6
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                commentIcon.setTint(ContextCompat.getColor(ctx, R.color.text_secondary))
            }

            // 可见度编辑行（仅「我的帖子」页显示）：点击切换 公开 / 仅自己可见
            binding.llVisibility.visibility = if (showVisibilityControl) View.VISIBLE else View.GONE
            if (showVisibilityControl) {
                binding.tvVisibility.text =
                    if (post.visibility == "private") "仅自己可见" else "公开"
                binding.llVisibility.setOnClickListener { onToggleVisibility?.invoke(post) }
            }

            // 模块可见度编辑行（仅「我的帖子」页显示）：消费数据 / 预算方案 独立切换
            binding.llDataVisibility.visibility =
                if (showVisibilityControl) View.VISIBLE else View.GONE
            if (showVisibilityControl) {
                binding.tvDataVisibility.text =
                    if (post.dataVisibility == "private") "仅自己可见" else "公开"
                binding.llDataVisibility.setOnClickListener {
                    onToggleModuleVisibility?.invoke(post, "data")
                }
            }
            binding.llBudgetVisibility.visibility =
                if (showVisibilityControl) View.VISIBLE else View.GONE
            if (showVisibilityControl) {
                binding.tvBudgetVisibility.text =
                    if (post.budgetVisibility == "private") "仅自己可见" else "公开"
                binding.llBudgetVisibility.setOnClickListener {
                    onToggleModuleVisibility?.invoke(post, "budget")
                }
            }

            // 评论区展开状态
            val expanded = post.postId in expandedIds
            binding.llCommentSection.visibility = if (expanded) View.VISIBLE else View.GONE
            binding.root.setOnClickListener { onToggleExpand(post.postId) }

            // 评论列表
            bindComments(binding.llComments, post)

            // 发送评论
            binding.btnSend.setOnClickListener {
                val content = binding.etComment.text.toString()
                if (content.isBlank()) return@setOnClickListener
                onSendComment(post.postId, content)
                binding.etComment.setText("")
            }
        }
    }

    /** 填充分类占比柱状条：每行 = 分类名 + 比例条 + 金额(占比) */
    private fun bindCategoryBars(container: ViewGroup, breakdown: Map<String, Double>) {
        container.removeAllViews()
        val total = breakdown.values.sum()
        if (total <= 0) return
        val inflater = LayoutInflater.from(container.context)
        breakdown.forEach { (category, amount) ->
            val row = ItemCategoryBarBinding.inflate(inflater, container, false)
            row.tvCatName.text = category

            // 按占比分配 weight（比例 x 1000，取整）
            val ratioW = (amount / total * 1000).toInt().coerceAtLeast(1)
            (row.viewBar.layoutParams as LinearLayout.LayoutParams).weight = ratioW.toFloat()
            (row.viewBarSpacer.layoutParams as LinearLayout.LayoutParams).weight =
                (1000 - ratioW).toFloat()

            row.tvCatInfo.text = "¥" + "%.2f".format(amount) + " (" + (amount / total * 100).toInt() + "%)"
            container.addView(row.root)
        }
    }

    /** 填充预算方案：每行 = 分类名 + 预算金额 */
    private fun bindBudgetRows(container: ViewGroup, budgets: Map<String, Double>) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(container.context)
        budgets.forEach { (name, amount) ->
            val row = ItemBudgetRowBinding.inflate(inflater, container, false)
            row.tvBudgetCat.text = name
            row.tvBudgetAmount.text = "¥" + "%.2f".format(amount)
            container.addView(row.root)
        }
    }

    /** 填充评论列表 */
    private fun bindComments(container: ViewGroup, post: CommunityPost) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(container.context)
        post.comments.forEach { comment ->
            val item = ItemCommentBinding.inflate(inflater, container, false)
            item.tvCommentUser.text = comment.username
            item.tvCommentContent.text = comment.content
            item.tvCommentTime.text = timeFormat.format(Date(comment.timestamp))
            container.addView(item.root)
        }
    }
}
