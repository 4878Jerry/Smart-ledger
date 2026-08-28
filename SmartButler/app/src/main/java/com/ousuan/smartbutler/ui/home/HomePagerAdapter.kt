package com.ousuan.smartbutler.ui.home

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

/** ViewPager2 适配器：饼图 / 折线图 / 列表 三个视图 */
class HomePagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {

    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> PieChartFragment()
        1 -> LineChartFragment()
        else -> RecordListFragment()
    }
}
