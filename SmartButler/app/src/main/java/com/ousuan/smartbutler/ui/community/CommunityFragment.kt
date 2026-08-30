package com.ousuan.smartbutler.ui.community

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.ousuan.smartbutler.R
import com.ousuan.smartbutler.SmartButlerApp
import com.ousuan.smartbutler.data.BudgetPrefs
import com.ousuan.smartbutler.data.DataPublicPrefs
import com.ousuan.smartbutler.data.network.NetworkMonitor
import com.ousuan.smartbutler.data.repository.CommunityRepository
import com.ousuan.smartbutler.databinding.FragmentCommunityBinding
import com.ousuan.smartbutler.util.MascotManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 社区页：浏览他人公开的消费统计（脱敏汇总）并发表评论。
 * 数据来自 [CommunityRepository]（内存，含预置模拟帖子）。
 */
class CommunityFragment : Fragment() {

    private var _binding: FragmentCommunityBinding? = null
    private val binding get() = _binding!!

    private val app get() = requireContext().applicationContext as SmartButlerApp
    private val userRepository get() = app.userRepository
    private val repository get() = app.repository

    private lateinit var adapter: CommunityAdapter

    /** 「包含预算方案」默认勾选只执行一次（有预算时），之后保留用户手动选择 */
    private var budgetOptionInitialized = false

    private val dataPrefs by lazy {
        requireContext().getSharedPreferences(DataPublicPrefs.PREFS, Context.MODE_PRIVATE)
    }

    /**
     * 数据公开开关监听：「我的」页开关变化时立即回调，
     * 刷新发布按钮与提示文字，实现跨页面状态同步（无需重启 App）。
     * MainActivity 用 show/hide 切 Tab 不触发 onResume，必须靠此监听兜底。
     * 开关 key 现按账号隔离（is_data_public_${userId}），按前缀过滤即可。
     */
    private val publicPrefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (DataPublicPrefs.isDataPublicKey(key)) {
                updatePublishButton()
                refresh() // 全局开关变化时同步刷新社区列表（关闭 → 自己的帖子立即消失）
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCommunityBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 空状态小鸥：分层渲染当前形象
        MascotManager.applyLookTo(binding.imgMascotCommunity)
        adapter = CommunityAdapter(
            onLike = { postId -> like(postId) },
            onToggleExpand = { postId -> toggleExpand(postId) },
            onSendComment = { postId, content -> sendComment(postId, content) }
        )
        binding.rvPosts.layoutManager = LinearLayoutManager(requireContext())
        binding.rvPosts.adapter = adapter
        binding.btnPublish.setOnClickListener { publish() }
        // 顶部 tab chips：纯视觉占位（关注/同城/话题敬请期待）；推荐为默认
        setupCommunityTabs()
        // 模块可见度行跟随「包含…」复选框显隐（仅在勾选时展示对应可见度选项）
        binding.cbIncludeData.setOnCheckedChangeListener { _, checked ->
            binding.llDataVisibilityRow.visibility = if (checked) View.VISIBLE else View.GONE
        }
        binding.cbIncludeBudget.setOnCheckedChangeListener { _, checked ->
            binding.llBudgetVisibilityRow.visibility = if (checked) View.VISIBLE else View.GONE
        }
        // 注册 SharedPreferences 监听，跨页面同步「数据公开」开关状态
        dataPrefs.registerOnSharedPreferenceChangeListener(publicPrefsListener)
        // 首次进入立即按当前网络状态刷新提示条（直接同步检查，不依赖观察者回调）
        updateOfflineTipVisibility(!NetworkMonitor.isConnected())
        refresh()
    }

    override fun onResume() {
        super.onResume()
        updatePublishButton()
        // 每次回到本页都主动检查网络并加载数据：联网拉服务器、断网读缓存
        checkNetworkAndRefresh()
    }

    /**
     * MainActivity 用 show/hide 切 Tab：从社区页切走再切回时只会回调本方法（不触发 onResume）。
     * 此时若网络已变化，必须在此主动检查并刷新，否则提示条与数据都不会更新。
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            updatePublishButton()
            checkNetworkAndRefresh()
        }
    }

    /**
     * 每次切换到社区页时调用（onResume / onHiddenChanged 切回 Tab）：
     * 1. 主动检查当前网络状态（NetworkMonitor.isConnected()），更新离线提示条；
     * 2. 触发一次数据加载：联网 → getAllPosts() 从服务器拉取最新数据；
     *    断网 → getAllPosts() 走本地缓存读取（内部不发起网络请求），顶部显示离线提示条。
     */
    private fun checkNetworkAndRefresh() {
        val isConnected = NetworkMonitor.isConnected()
        Log.d("CommunityTip", "onResume: 网络状态=$isConnected, 提示条=${if (isConnected) "隐藏" else "显示"}")
        updateOfflineTipVisibility(!isConnected)
        refresh()
    }

    /** 发布按钮可用性：需已登录且数据公开开关打开；文案按当月是否已发布动态切换 */
    private fun updatePublishButton() {
        val currentUser = userRepository.getCurrentUser()
        val loggedIn = currentUser != null
        val dataPublic = DataPublicPrefs.isPublic(requireContext(), currentUser?.userId)
        binding.btnPublish.isEnabled = loggedIn && dataPublic
        binding.tvPublishTip.text = when {
            !loggedIn -> "请先登录"
            !dataPublic -> "请先在「我的」页开启数据公开"
            else -> "公开我的月度消费统计（仅汇总，不含逐笔明细）"
        }
        // 每次进入发布区时查询当月是否已发布，动态决定按钮文案
        viewLifecycleOwner.lifecycleScope.launch {
            val currentUser = userRepository.getCurrentUser() ?: return@launch
            val month = SimpleDateFormat("yyyy-MM", Locale.CHINA).format(Date())
            val has = CommunityRepository.hasPublishedPostForMonth(month, currentUser.username)
            if (_binding == null) return@launch
            binding.btnPublish.text = if (has) "更新当月数据" else "发布我的数据"
        }

        // 预算方案模块选项：当月未设置预算时隐藏；已设置时首次进入默认勾选（之后保留手动选择）
        val hasBudget = BudgetPrefs.hasBudget(requireContext())
        binding.cbIncludeBudget.visibility = if (hasBudget) View.VISIBLE else View.GONE
        if (hasBudget && !budgetOptionInitialized) {
            binding.cbIncludeBudget.isChecked = true
            budgetOptionInitialized = true
        }
        // 模块可见度行初始显隐跟随复选框
        binding.llDataVisibilityRow.visibility =
            if (binding.cbIncludeData.isChecked) View.VISIBLE else View.GONE
        binding.llBudgetVisibilityRow.visibility =
            if (binding.cbIncludeBudget.isChecked) View.VISIBLE else View.GONE
    }

    /** 刷新帖子：在线拉取服务器并写缓存，离线读取缓存（顶部显示离线提示条） */
    private fun refresh() {
        viewLifecycleOwner.lifecycleScope.launch {
            val data = CommunityRepository.getAllPosts()
            if (_binding == null) return@launch
            // 社区列表只显示 visibility='public' 的帖子；
            // 当前用户关闭全局公开开关时，其帖子（无论可见度）也不在社区展示
            val currentUser = userRepository.getCurrentUser()
            val globalPublic = DataPublicPrefs.isPublic(requireContext(), currentUser?.userId)
            val visible = data.filter { post ->
                post.visibility == "public" &&
                    (globalPublic || post.username != currentUser?.username)
            }
            Log.d(TAG, "刷新列表，数据 ${data.size} 条，可见 ${visible.size} 条")
            Log.d(TAG, "最新帖子ID: ${visible.firstOrNull()?.postId}")
            adapter.setData(visible)
            binding.tvEmpty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
            // 离线提示条只由网络接口状态控制（与公开开关、服务器探测结果无关）：
            // 无网络时显示，有网络时隐藏
            updateOfflineTipVisibility(!NetworkMonitor.isConnected())
        }
    }

    /**
     * 直接控制顶部离线提示条显隐（由调用方传入网络状态，不依赖观察者回调）：
     * show=true  → 断网/离线：显示「当前离线，显示缓存数据」（View.VISIBLE）
     * show=false → 联网：隐藏提示条（View.GONE）
     * 只更新 UI，不触发列表重新加载。
     */
    private fun updateOfflineTipVisibility(show: Boolean) {
        val tipView = binding?.llOfflineBanner ?: return
        tipView.visibility = if (show) View.VISIBLE else View.GONE
        Log.d("CommunityTip", "提示条可见性: ${if (show) "显示" else "隐藏"}")
    }

    private fun like(postId: String) {
        // 1. 乐观更新：先在 UI 端 toggle 点赞状态（后端接口就是 toggle，
        //    已点赞 → 取消；未点赞 → 点赞），立刻重绘单条以反映颜色变化
        val wasLiked = adapter.likedIds.contains(postId)
        if (wasLiked) adapter.likedIds.remove(postId) else adapter.likedIds.add(postId)
        adapter.notifyItemChangedByPostId(postId)

        viewLifecycleOwner.lifecycleScope.launch {
            CommunityRepository.likePost(postId)
                .onSuccess { likes ->
                    // 服务器返回最新 likes 数，单独更新这一条（不重排列表）
                    if (!adapter.updateLikes(postId, likes)) {
                        Log.d(TAG, "点赞后找不到帖子 $postId，触发全量刷新")
                        refresh()
                    }
                }
                .onFailure { e ->
                    // 失败回滚：把 UI 状态改回原值并重绘
                    if (wasLiked) adapter.likedIds.add(postId)
                    else adapter.likedIds.remove(postId)
                    adapter.notifyItemChangedByPostId(postId)
                    Toast.makeText(requireContext(), e.message ?: "操作失败", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun toggleExpand(postId: String) {
        if (postId in adapter.expandedIds) {
            adapter.expandedIds.remove(postId)
        } else {
            adapter.expandedIds.add(postId)
        }
        // 只重绘当前条目，不整表刷新（避免展开/收起评论区时列表跳动）
        if (!adapter.notifyItemChangedByPostId(postId)) refresh()
    }

    private fun sendComment(postId: String, content: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            CommunityRepository.addComment(postId, content)
                .onSuccess { comment ->
                    adapter.expandedIds.add(postId)
                    // 只更新单条帖子的评论，避免整表重排导致列表跳位
                    if (!adapter.updateComments(postId, comment)) {
                        Log.d(TAG, "评论后找不到帖子 $postId，触发全量刷新")
                        refresh()
                    }
                }
                .onFailure { e ->
                    Toast.makeText(requireContext(), e.message ?: "评论失败", Toast.LENGTH_SHORT).show()
                }
        }
    }

    /** 发布我的月度统计：真实数据来自 Transaction 表，发布到社区 */
    private fun publish() {
        val currentUser = userRepository.getCurrentUser()
        if (currentUser == null) {
            Toast.makeText(requireContext(), "请先登录", Toast.LENGTH_SHORT).show()
            updatePublishButton()
            return
        }
        val token = userRepository.getToken()
        Log.d(TAG, "发布前检查: 已登录用户=${currentUser.username}, token存在=${!token.isNullOrEmpty()}")
        if (token.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "登录已过期，请重新登录", Toast.LENGTH_SHORT).show()
            return
        }
        if (!DataPublicPrefs.isPublic(requireContext(), currentUser.userId)) {
            Toast.makeText(requireContext(), "请先在「我的」页开启数据公开", Toast.LENGTH_SHORT).show()
            updatePublishButton()
            return
        }
        lifecycleScope.launch {
            val cal = Calendar.getInstance()
            val month = SimpleDateFormat("yyyy-MM", Locale.CHINA).format(Date())

            // 预算模块校验：勾选了「包含预算方案」但当月未设置预算 → 提示并取消勾选
            if (binding.cbIncludeBudget.isChecked && !BudgetPrefs.hasBudget(requireContext())) {
                Toast.makeText(requireContext(), "请先在预算页面设置本月预算", Toast.LENGTH_SHORT).show()
                binding.cbIncludeBudget.isChecked = false
                return@launch
            }

            // 模块选择：至少勾选一个
            val includeData = binding.cbIncludeData.isChecked
            val includeBudget = binding.cbIncludeBudget.isChecked
            if (!includeData && !includeBudget) {
                Toast.makeText(requireContext(), "至少选择一个模块", Toast.LENGTH_SHORT).show()
                return@launch
            }

            // 消费数据模块：勾选时读取本月统计；无支出数据时降级处理
            val stats = if (includeData) repository.getMonthlyStats(
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1
            ) else null
            if (includeData && stats == null) {
                if (includeBudget) {
                    Toast.makeText(requireContext(), "本月暂无支出数据，仅发布预算方案", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "本月暂无支出数据，无法发布", Toast.LENGTH_SHORT).show()
                    return@launch
                }
            }

            // 预算方案模块：勾选时读取用户设置的各分类预算
            val budget = if (includeBudget) BudgetPrefs.allCategoryBudgets(requireContext()) else emptyMap()
            // 帖子可见度：公开 / 仅自己可见
            val visibility = if (binding.toggleVisibility.checkedButtonId == R.id.btn_visibility_private)
                "private" else "public"
            // 模块可见度：消费数据 / 预算方案 各自独立（默认公开）
            val dataVisibility = if (binding.toggleDataVisibility.checkedButtonId == R.id.btn_data_visibility_private)
                "private" else "public"
            val budgetVisibility = if (binding.toggleBudgetVisibility.checkedButtonId == R.id.btn_budget_visibility_private)
                "private" else "public"
            CommunityRepository.publishMyStats(
                month, stats, budget, visibility, dataVisibility, budgetVisibility
            ).onSuccess { post ->
                    Log.d(TAG, "发布成功，服务器帖子 ID: ${post.postId}, visibility=$visibility, data=$dataVisibility, budget=$budgetVisibility")
                    val tip = if (visibility == "private") "已发布（仅自己可见）" else "发布成功（公开）"
                    Toast.makeText(requireContext(), tip, Toast.LENGTH_SHORT).show()
                    refresh() // 发布成功后刷新列表，立即可见
                    updatePublishButton() // 刷新按钮文案为「更新当月数据」
                }
                .onFailure { e ->
                    Toast.makeText(requireContext(), e.message ?: "发布失败", Toast.LENGTH_SHORT).show()
                }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 取消注册监听，避免内存泄漏
        dataPrefs.unregisterOnSharedPreferenceChangeListener(publicPrefsListener)
        _binding = null
    }

    /**
     * 顶部 tab chips 切换：纯视觉占位（当前仅推荐展示真实数据，其他 tab 敬请期待）。
     * 通过切换 drawable + 文本颜色模拟 chip 选中态。
     */
    private fun setupCommunityTabs() {
        val chips = listOf(
            binding.chipRecommend,
            binding.chipFollowing,
            binding.chipSameCity,
            binding.chipTopic
        )
        chips.forEach { chip ->
            chip.setOnClickListener {
                chips.forEach { updateChipState(it, it == chip) }
                if (chip != binding.chipRecommend) {
                    Toast.makeText(requireContext(), R.string.community_coming_soon, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateChipState(chip: TextView, selected: Boolean) {
        chip.background = ContextCompat.getDrawable(
            requireContext(),
            if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected
        )
        chip.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (selected) R.color.white else R.color.text_secondary
            )
        )
        chip.setTypeface(chip.typeface, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    companion object {
        private const val TAG = "CommunityFragment"
    }
}
