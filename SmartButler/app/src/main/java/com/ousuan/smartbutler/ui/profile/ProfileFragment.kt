package com.ousuan.smartbutler.ui.profile

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.ousuan.smartbutler.R
import com.ousuan.smartbutler.SmartButlerApp
import com.ousuan.smartbutler.data.DataPublicPrefs
import com.ousuan.smartbutler.data.repository.CommunityRepository
import com.ousuan.smartbutler.databinding.FragmentProfileBinding
import com.ousuan.smartbutler.ui.auth.LoginActivity
import com.ousuan.smartbutler.ui.community.MyPostsActivity
import com.ousuan.smartbutler.ui.settings.SettingsActivity
import java.util.Calendar
import kotlinx.coroutines.launch

/** 「我的」页：展示当前登录用户、数据公开开关、切换账号、退出登录 */
class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    private val app get() = requireContext().applicationContext as SmartButlerApp
    private val userRepository get() = app.userRepository
    private val repository get() = app.repository

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.tvMascotName.text = getString(R.string.mascot_name)

        // 当前登录用户
        refreshUserInfo()
        binding.btnLogout.setOnClickListener { logout() }
        binding.btnMyPosts.setOnClickListener {
            startActivity(Intent(requireContext(), MyPostsActivity::class.java))
        }
        // 切换账号：弹窗输入用户名/密码 → 登录成功后直接切换，无需重启 App
        binding.btnSwitchAccount.setOnClickListener { showSwitchAccountDialog() }
        // 服务器设置入口（无需登录即可修改，方便队友连自己的后端）
        binding.btnServerSettings.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }

        // 数据公开开关（按当前账号加载状态并绑定监听）
        loadDataPublicState()
    }

    override fun onResume() {
        super.onResume()
        // 登录成功返回本页时，重新加载当前账号的开关状态
        loadDataPublicState()
    }

    /**
     * 按当前账号加载「数据公开」开关状态并绑定监听：
     * - 存储 key 为 `is_data_public_${userId}`，每个账号独立；
     * - 未设置过的账号默认关闭；
     * - 登录成功 / 切换账号 / 页面恢复时调用，确保状态跟随当前账号。
     */
    private fun loadDataPublicState() {
        val userId = userRepository.getCurrentUser()?.userId
        val checked = DataPublicPrefs.isPublic(requireContext(), userId)
        // 临时移除监听，避免 setChecked 触发确认弹窗或写入逻辑
        binding.swDataPublic.setOnCheckedChangeListener(null)
        binding.swDataPublic.isChecked = checked
        binding.swDataPublic.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) confirmEnablePublic() else applyPublic(false)
        }
    }

    /** 开启前弹窗确认：仅上传统计，不上传逐笔明细 */
    private fun confirmEnablePublic() {
        AlertDialog.Builder(requireContext())
            .setTitle("开启数据公开")
            .setMessage("你的消费统计数据将公开给社区其他用户查看。\n\n为保护隐私，仅上传「月度汇总统计」，不会上传逐笔明细。")
            .setPositiveButton("确认开启") { _, _ -> applyPublic(true) }
            .setNegativeButton("取消") { _, _ -> binding.swDataPublic.isChecked = false }
            .setOnCancelListener { binding.swDataPublic.isChecked = false }
            .show()
    }

    /**
     * 批量设置全部记录公开状态，并持久化当前账号的开关状态。
     * 开关状态同步到服务器（PUT /api/users/settings）：
     * - 在线：立即同步，其他设备的用户刷新公开流即生效；
     * - 离线 / 失败：本地已生效，标记待同步，网络恢复后由 SyncManager 自动补推。
     */
    private fun applyPublic(isPublic: Boolean) {
        lifecycleScope.launch {
            try {
                val currentUser = userRepository.getCurrentUser()
                    ?: throw IllegalStateException("请先登录")
                repository.updateAllPublicStatus(isPublic)
                DataPublicPrefs.setPublic(requireContext(), currentUser.userId, isPublic)
                userRepository.syncDataPublicSetting()
                val msg = if (isPublic) generatePublicToast() else "已停止公开消费数据"
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                binding.swDataPublic.isChecked = !isPublic
                Toast.makeText(requireContext(), "操作失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 生成当月脱敏统计提示（演示 getMonthlyStats 的输出） */
    private suspend fun generatePublicToast(): String {
        val cal = Calendar.getInstance()
        val stats = repository.getMonthlyStats(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
        return if (stats != null) {
            "已公开！${stats.month} 统计：总支出 ¥${"%.2f".format(stats.totalExpense)}，" +
                "最高分类「${stats.topCategory}」"
        } else {
            "已公开消费数据（本月暂无支出统计）"
        }
    }

    /** 刷新「我的」页当前用户信息（登录后 / 切换账号后调用） */
    private fun refreshUserInfo() {
        val user = userRepository.getCurrentUser()
        binding.tvUsername.text = if (user != null) {
            "当前用户：${user.nickname ?: user.username}"
        } else {
            "未登录"
        }
        binding.btnLogout.visibility = if (user != null) View.VISIBLE else View.GONE
        binding.btnMyPosts.visibility = if (user != null) View.VISIBLE else View.GONE
        binding.btnSwitchAccount.visibility = if (user != null) View.VISIBLE else View.GONE
    }

    /** 弹窗输入新账号用户名/密码 */
    private fun showSwitchAccountDialog() {
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val etUsername = EditText(requireContext()).apply {
            hint = "用户名"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val etPassword = EditText(requireContext()).apply {
            hint = "密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        container.addView(etUsername)
        container.addView(etPassword, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        AlertDialog.Builder(requireContext())
            .setTitle("切换账号")
            .setMessage("将退出当前账号，登录新账号（无需重启 App）")
            .setView(container)
            .setPositiveButton("登录") { _, _ ->
                val username = etUsername.text.toString().trim()
                val password = etPassword.text.toString()
                switchAccount(username, password)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 执行账号切换（复用登录验证，不重启 App）：
     * - 登录成功后自动触发 onLoginSuccess → 从服务器拉取新账号数据；
     * - 清空旧账号的交易/社区内存数据（首页 Flow 通过账号版本信号自动切换）；
     * - 刷新本页用户信息。
     */
    private fun switchAccount(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            Toast.makeText(requireContext(), "请输入用户名和密码", Toast.LENGTH_SHORT).show()
            return
        }
        val current = userRepository.getCurrentUser()
        if (current != null && current.username == username) {
            Toast.makeText(requireContext(), "当前已是 $username，无需切换", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                val user = userRepository.login(username, password).getOrThrow()
                // 1) 触发首页/列表/图表 Flow 按新账号重新订阅
                repository.notifyAccountSwitched()
                // 2) 清空旧账号的社区内存/缓存/待发布数据（保留预置模拟帖）
                CommunityRepository.resetForAccountSwitch()
                // 3) 刷新本页用户信息
                refreshUserInfo()
                // 4) 重新加载新账号的「数据公开」开关状态
                loadDataPublicState()
                Toast.makeText(requireContext(), "已切换到 ${user.nickname ?: user.username}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "切换失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun logout() {
        userRepository.logout()
        Toast.makeText(requireContext(), "已退出登录", Toast.LENGTH_SHORT).show()
        startActivity(Intent(requireContext(), LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        requireActivity().finish()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
