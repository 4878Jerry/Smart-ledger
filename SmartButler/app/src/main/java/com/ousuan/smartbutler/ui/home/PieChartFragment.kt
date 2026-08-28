package com.ousuan.smartbutler.ui.home

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.ousuan.smartbutler.R
import com.ousuan.smartbutler.SmartButlerApp
import com.ousuan.smartbutler.databinding.FragmentPieBinding
import com.ousuan.smartbutler.util.Categories
import com.ousuan.smartbutler.util.ExpenseAnalyzer
import kotlinx.coroutines.launch

/** 饼图视图：支出分类占比 */
class PieChartFragment : Fragment() {

    private var _binding: FragmentPieBinding? = null
    private val binding get() = _binding!!

    private val repository by lazy {
        (requireActivity().application as SmartButlerApp).repository
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPieBinding.inflate(inflater, container, false)

        binding.pieChart.description.isEnabled = false
        binding.pieChart.legend.isEnabled = false
        binding.pieChart.setUsePercentValues(true)
        binding.pieChart.setEntryLabelColor(Color.parseColor("#E0E0E0"))
        binding.pieChart.setEntryLabelTextSize(11f)
        binding.pieChart.holeRadius = 45f

        viewLifecycleOwner.lifecycleScope.launch {
            repository.allTransactions.collect { records ->
                render(records)
            }
        }
        return binding.root
    }

    private fun render(records: List<com.ousuan.smartbutler.data.Transaction>) {
        val cats = ExpenseAnalyzer.categoryAmounts(records)
        if (cats.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.pieChart.clear()
            return
        }
        binding.tvEmpty.visibility = View.GONE
        val entries = cats.entries.map { PieEntry(it.value.toFloat(), it.key) }
        val dataSet = PieDataSet(entries, "分类占比").apply {
            colors = cats.keys.map { Categories.color(it) }
            valueTextSize = 11f
            valueTextColor = requireContext().getColor(R.color.text_primary)
            sliceSpace = 2f
        }
        binding.pieChart.data = PieData(dataSet)
        binding.pieChart.invalidate()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
