package com.ousuan.smartbutler.ui.mascot

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.ousuan.smartbutler.R
import com.ousuan.smartbutler.model.PartCategory
import com.ousuan.smartbutler.util.MascotManager

/**
 * 小鸥换装弹窗：按部位（表情 / 头饰 / 颈饰 / 手持）分 Tab 选择部件，
 * 顶部实时预览分层组合效果，选择后立即生效并持久化。
 */
class MascotDressRoomDialog {

    private lateinit var dialog: AlertDialog
    private lateinit var context: Context

    private lateinit var previewContainer: FrameLayout
    private val categoryTabs = mutableMapOf<PartCategory, TextView>()
    private lateinit var partGrid: GridLayout

    /** 当前展示的部位 Tab */
    private var activeCategory = PartCategory.FACE

    fun show(context: Context) {
        this.context = context
        val root = buildContent()
        dialog = AlertDialog.Builder(context)
            .setTitle("小鸥换装")
            .setView(root)
            .setNegativeButton("完成", null)
            .show()
        // 初始选中表情 Tab 并刷新
        selectCategory(PartCategory.FACE)
    }

    private fun buildContent(): View {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        // ===== 顶部预览区 =====
        previewContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(140), dp(140)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            setBackgroundResource(R.drawable.bg_mascot_circle)
        }
        root.addView(previewContainer)

        // ===== 部位 Tab 行 =====
        val tabRow = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
        }
        val tabContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val categories = listOf(
            PartCategory.FACE to "表情",
            PartCategory.HEAD to "头饰",
            PartCategory.NECK to "颈饰",
            PartCategory.HAND to "手持"
        )
        for ((category, label) in categories) {
            val tab = TextView(context).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#5F7185"))
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener { selectCategory(category) }
            }
            tabContainer.addView(tab)
            categoryTabs[category] = tab
        }
        tabRow.addView(tabContainer)
        root.addView(tabRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })

        // ===== 部件网格 =====
        partGrid = GridLayout(context).apply {
            columnCount = 4
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(partGrid)

        return root
    }

    private fun selectCategory(category: PartCategory) {
        activeCategory = category
        // 刷新 Tab 高亮
        for ((cat, tab) in categoryTabs) {
            val selected = cat == category
            tab.setBackgroundResource(
                if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected
            )
            tab.setTextColor(
                if (selected) Color.WHITE else Color.parseColor("#5F7185")
            )
        }
        renderPreview()
        renderParts()
    }

    private fun renderPreview() {
        // 以「当前形象 + 当前 Tab 选中的部件」预览
        MascotManager.applyLookTo(previewContainer, MascotManager.currentLook())
    }

    private fun renderParts() {
        partGrid.removeAllViews()
        val parts = MascotManager.partsOf(activeCategory)
        val current = MascotManager.currentLook()
        val currentId = when (activeCategory) {
            PartCategory.FACE -> current.faceId
            PartCategory.HEAD -> current.headId
            PartCategory.NECK -> current.neckId
            PartCategory.EYE -> current.eyeId
            PartCategory.HAND -> current.handId
            else -> current.bodyId
        }
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        parts.forEachIndexed { index, part ->
            // 当前选中状态：该层为「无」（currentId == null）时，选中 id 以 _none 结尾的部件
            val isSelected = if (currentId == null) {
                part.id.endsWith("_none")
            } else {
                part.id == currentId
            }
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(8), dp(4), dp(8))
                background = if (isSelected) {
                    context.getDrawable(R.drawable.bg_cell_selected)
                } else {
                    context.getDrawable(R.drawable.bg_cell_normal)
                }
                setOnClickListener {
                    // drawableRes == 0 表示「无」部件 → 传 null 清除该层
                    MascotManager.setPart(activeCategory, part.id.takeIf { part.drawableRes != 0 })
                    Toast.makeText(
                        context,
                        "已换「${part.displayName}」",
                        Toast.LENGTH_SHORT
                    ).show()
                    renderParts()
                    renderPreview()
                }
            }
            val img = ImageView(context).apply {
                if (part.drawableRes == 0) {
                    // 「无」部件：显示空线框图标，表示"不穿戴"
                    setImageResource(R.drawable.ic_mascot_none)
                    alpha = 0.55f
                } else {
                    setImageResource(part.drawableRes)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                layoutParams = ViewGroup.LayoutParams(dp(56), dp(56))
                contentDescription = part.displayName
            }
            val label = TextView(context).apply {
                text = part.displayName
                setTextColor(context.getColor(R.color.text_secondary))
                textSize = 11f
                setPadding(0, dp(4), 0, 0)
            }
            cell.addView(img)
            cell.addView(label)

            val lp = GridLayout.LayoutParams(
                GridLayout.spec(index / 4, 1f),
                GridLayout.spec(index % 4, 1f)
            ).apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
            partGrid.addView(cell, lp)
        }
    }
}
