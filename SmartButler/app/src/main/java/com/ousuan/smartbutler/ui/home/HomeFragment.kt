package com.ousuan.smartbutler.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator
import com.ousuan.smartbutler.R
import com.ousuan.smartbutler.SmartButlerApp
import com.ousuan.smartbutler.databinding.FragmentHomeBinding
import com.ousuan.smartbutler.ui.ocr.OcrInputActivity
import com.ousuan.smartbutler.ui.voice.VoiceInputActivity
import com.ousuan.smartbutler.util.DateUtils
import com.ousuan.smartbutler.util.ExpenseAnalyzer
import com.ousuan.smartbutler.util.MascotManager
import com.ousuan.smartbutler.util.fmtMoney
import kotlinx.coroutines.launch

/**
 * 功能① 首页：基础记账 + 图表。
 * 顶部统计卡（本月收入/支出/结余 + 消费人格 + AI 建议），
 * 中部 ViewPager2 三个视图：饼图 / 折线图 / 列表，
 * 右下 FAB 弹出「手动输入 / 语音输入 / 图片识别」菜单。
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val repository by lazy {
        (requireActivity().application as SmartButlerApp).repository
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // TabLayout + ViewPager2：饼图 / 折线图 / 列表
        binding.viewPager.adapter = HomePagerAdapter(this)
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "饼图"
                1 -> "折线图"
                else -> "列表"
            }
        }.attach()

        binding.fabAdd.setOnClickListener { showAddMenu(it) }

        // 首页小鸥头像：跟随全局换装，随页面销毁自动注销监听
        MascotManager.observe(mascotListener)
        binding.imgMascotHome.setImageResource(MascotManager.current().drawableRes)

        observeSummary()
    }

    /** FAB 弹出菜单：手动输入 / 语音输入 / 图片识别 */
    private fun showAddMenu(anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            menuInflater.inflate(R.menu.fab_menu, menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_manual -> {
                        AddTransactionDialog().show(
                            requireContext(),
                            repository,
                            viewLifecycleOwner.lifecycleScope
                        )
                    }
                    R.id.action_voice -> {
                        startActivity(Intent(requireContext(), VoiceInputActivity::class.java))
                    }
                    R.id.action_ocr -> {
                        startActivity(Intent(requireContext(), OcrInputActivity::class.java))
                    }
                }
                true
            }
            show()
        }
    }

    /** 统计卡：本月概览（人格 / AI 建议已迁到「统计分析」页） */
    private fun observeSummary() {
        viewLifecycleOwner.lifecycleScope.launch {
            repository.allTransactions.collect { records ->
                val monthPrefix = DateUtils.nowMonthPrefix()
                val monthRecords = records.filter { it.date.startsWith(monthPrefix) }
                val s = ExpenseAnalyzer.summarize(monthRecords)
                binding.tvIncome.text = fmtMoney(s.income)
                binding.tvExpense.text = fmtMoney(s.expense)
                binding.tvBalance.text = fmtMoney(s.balance)
            }
        }
    }

    override fun onDestroyView() {
        MascotManager.removeObserver(mascotListener)
        super.onDestroyView()
        _binding = null
    }

    /** 小鸥换装监听（随页面销毁注销） */
    private val mascotListener: (MascotManager.Mascot) -> Unit = { m ->
        _binding?.imgMascotHome?.setImageResource(m.drawableRes)
    }
}
