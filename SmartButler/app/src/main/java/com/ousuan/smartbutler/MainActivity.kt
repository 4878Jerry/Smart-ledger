package com.ousuan.smartbutler

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.ousuan.smartbutler.databinding.ActivityMainBinding
import com.ousuan.smartbutler.ui.alert.AlertFragment
import com.ousuan.smartbutler.ui.budget.BudgetFragment
import com.ousuan.smartbutler.ui.community.CommunityFragment
import com.ousuan.smartbutler.ui.home.HomeFragment
import com.ousuan.smartbutler.ui.profile.ProfileFragment

/**
 * 主界面：底部导航五 Tab（首页 / 预算 / 预警 / 社区 / 我的）。
 *
 * 使用 add + show/hide 管理 Fragment（而非 replace）：
 * 首页内嵌 ViewPager2（FragmentStateAdapter），若用 replace 反复销毁重建，
 * ViewPager2 恢复已失效的 child fragment 状态会抛
 * IllegalStateException: Fragment no longer exists for key f#0。
 * show/hide 不会销毁 Fragment 及其 View，状态始终保留，避免该崩溃。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val homeFragment by lazy { HomeFragment() }
    private val budgetFragment by lazy { BudgetFragment() }
    private val alertFragment by lazy { AlertFragment() }
    private val communityFragment by lazy { CommunityFragment() }
    private val profileFragment by lazy { ProfileFragment() }

    private var currentTag: String = TAG_HOME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentTag = savedInstanceState?.getString(KEY_CURRENT_TAG) ?: TAG_HOME

        binding.bottomNav.setOnItemSelectedListener { item ->
            val tag = when (item.itemId) {
                R.id.nav_budget -> TAG_BUDGET
                R.id.nav_alert -> TAG_ALERT
                R.id.nav_community -> TAG_COMMUNITY
                R.id.nav_profile -> TAG_PROFILE
                else -> TAG_HOME
            }
            showFragment(tag)
            true
        }

        if (savedInstanceState == null) {
            // 首次创建：直接添加首页（不触发 listener）
            supportFragmentManager.beginTransaction()
                .add(R.id.fragment_container, homeFragment, TAG_HOME)
                .commit()
        } else {
            // 重建：恢复底部导航选中项；Fragment 及其隐藏状态由 FragmentManager 自动恢复
            binding.bottomNav.selectedItemId = when (currentTag) {
                TAG_BUDGET -> R.id.nav_budget
                TAG_ALERT -> R.id.nav_alert
                TAG_COMMUNITY -> R.id.nav_community
                TAG_PROFILE -> R.id.nav_profile
                else -> R.id.nav_home
            }
        }
    }

    private fun showFragment(tag: String) {
        if (tag == currentTag) return
        val fm = supportFragmentManager
        val ft = fm.beginTransaction()
        fm.findFragmentByTag(currentTag)?.let { ft.hide(it) }
        val target = fm.findFragmentByTag(tag)
        if (target == null) {
            val fragment = when (tag) {
                TAG_BUDGET -> budgetFragment
                TAG_ALERT -> alertFragment
                TAG_COMMUNITY -> communityFragment
                TAG_PROFILE -> profileFragment
                else -> homeFragment
            }
            ft.add(R.id.fragment_container, fragment, tag)
        } else {
            ft.show(target)
        }
        ft.commit()
        currentTag = tag
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_CURRENT_TAG, currentTag)
    }

    companion object {
        private const val TAG_HOME = "home"
        private const val TAG_BUDGET = "budget"
        private const val TAG_ALERT = "alert"
        private const val TAG_COMMUNITY = "community"
        private const val TAG_PROFILE = "profile"
        private const val KEY_CURRENT_TAG = "current_tag"
    }
}
