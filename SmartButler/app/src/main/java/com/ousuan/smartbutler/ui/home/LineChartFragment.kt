package com.ousuan.smartbutler.ui.home

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.ousuan.smartbutler.R
import com.ousuan.smartbutler.SmartButlerApp
import com.ousuan.smartbutler.databinding.FragmentLineBinding
import com.ousuan.smartbutler.util.ExpenseAnalyzer
import kotlinx.coroutines.launch

/** 折线图视图：每日支出趋势 */
class LineChartFragment : Fragment() {

    private var _binding: FragmentLineBinding? = null
    private val binding get() = _binding!!

    private val repository by lazy {
        (requireActivity().application as SmartButlerApp).repository
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLineBinding.inflate(inflater, container, false)

        binding.lineChart.description.isEnabled = false
        binding.lineChart.legend.isEnabled = false
        binding.lineChart.axisRight.isEnabled = false
        binding.lineChart.setTouchEnabled(true)

        viewLifecycleOwner.lifecycleScope.launch {
            repository.allTransactions.collect { records -> render(records) }
        }
        return binding.root
    }

    private fun render(records: List<com.ousuan.smartbutler.data.Transaction>) {
        val trend = ExpenseAnalyzer.dailyTrend(records)
        if (trend.isEmpty()) {
            binding.llEmpty.visibility = View.VISIBLE
            binding.lineChart.clear()
            return
        }
        binding.llEmpty.visibility = View.GONE

        val labels = trend.map { it.first.substring(5) } // MM-dd
        val entries = trend.mapIndexed { i, (_, v) -> Entry(i.toFloat(), v.toFloat()) }
        val brand = requireContext().getColor(R.color.primary)
        val dataSet = LineDataSet(entries, "每日支出").apply {
            color = brand
            lineWidth = 2.5f
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
            setDrawCircles(true)
            circleRadius = 3f
            setCircleColor(brand)
            setDrawFilled(true)
            fillDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                gradientType = GradientDrawable.LINEAR_GRADIENT
                setColors(intArrayOf(
                    Color.argb(77, Color.red(brand), Color.green(brand), Color.blue(brand)),
                    Color.argb(0, Color.red(brand), Color.green(brand), Color.blue(brand))
                ))
            }
        }

        binding.lineChart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            valueFormatter = IndexAxisValueFormatter(labels)
            granularity = 1f
            labelCount = minOf(7, labels.size)
            setDrawGridLines(false)
            textColor = requireContext().getColor(R.color.text_secondary)
        }
        binding.lineChart.axisLeft.apply {
            setDrawGridLines(false)
            axisMinimum = 0f
            textColor = requireContext().getColor(R.color.text_secondary)
        }
        binding.lineChart.data = LineData(dataSet)
        binding.lineChart.invalidate()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
