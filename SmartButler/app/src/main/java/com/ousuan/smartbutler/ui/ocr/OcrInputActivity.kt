package com.ousuan.smartbutler.ui.ocr

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.ousuan.smartbutler.R
import com.ousuan.smartbutler.SmartButlerApp
import com.ousuan.smartbutler.data.Transaction
import com.ousuan.smartbutler.databinding.ActivityOcrInputBinding
import com.ousuan.smartbutler.util.Categories
import com.ousuan.smartbutler.util.DateUtils
import com.ousuan.smartbutler.util.ParseUtils
import com.ousuan.smartbutler.util.fmtMoney
import kotlinx.coroutines.launch

/**
 * 图片记账：选图 → ML Kit 识别中文 → 正则提取金额与分类 → 预览可修改 → 保存。
 */
class OcrInputActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOcrInputBinding
    private val repository by lazy { (application as SmartButlerApp).repository }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) runOcr(uri)
            else Toast.makeText(this, "未选择图片", Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOcrInputBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.title = getString(R.string.ocr_title)

        binding.btnPick.setOnClickListener {
            pickImage.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
        binding.btnSave.setOnClickListener { save() }

        // 「识别原文」折叠区：点击标题展开/收起
        binding.llRecognizedHeader.setOnClickListener { toggleRecognized() }

        binding.spCategory.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            Categories.EXPENSE
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private fun runOcr(uri: Uri) {
        binding.ivImage.setImageURI(uri)
        binding.tvRecognized.text = "正在识别图片中的文字…"
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        lifecycleScope.launch {
            try {
                // InputImage.fromFilePath 是挂起函数，需在协程中调用
                val image = InputImage.fromFilePath(this@OcrInputActivity, uri)
                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        val text = visionText.text
                        binding.tvRecognized.text = text.ifBlank { "未识别到文字" }
                        // 识别完成后自动展开「识别原文」区域
                        binding.tvRecognized.visibility = View.VISIBLE
                        binding.tvRecognizedArrow.text = "▴"
                        if (text.isNotBlank()) fillPreview(text)
                    }
                    .addOnFailureListener { e ->
                        binding.tvRecognized.text = "识别失败：${e.message}"
                    }
            } catch (e: Exception) {
                binding.tvRecognized.text = "读取图片失败：${e.message}"
            }
        }
    }

    /**
     * 提取识别文本中的所有金额候选，展示为 Chip 供用户选择；
     * 仅有一个金额时自动填入，没有金额时提示手动输入。
     */
    private fun fillPreview(text: String) {
        val amounts = ParseUtils.extractAmounts(text)
        val chipGroup = binding.chipGroup
        chipGroup.removeAllViews()
        binding.tvAmountLabel.visibility = if (amounts.isEmpty()) View.GONE else View.VISIBLE

        when {
            amounts.isEmpty() -> {
                Toast.makeText(this, "未识别到金额，请手动输入", Toast.LENGTH_SHORT).show()
            }
            amounts.size == 1 -> {
                // 只有一个金额：自动填入并高亮
                binding.etAmount.setText(fmtAmount(amounts[0]))
                addAmountChip(chipGroup, amounts[0], checked = true)
            }
            else -> {
                // 多个金额：全部展示，提示用户点击确认（最大数字可能是折扣前/小计等）
                Toast.makeText(this, "识别到 ${amounts.size} 个金额，请点击选择", Toast.LENGTH_SHORT).show()
                amounts.forEach { addAmountChip(chipGroup, it, checked = false) }
            }
        }

        val cat = ParseUtils.extractCategory(text)
        binding.spCategory.setSelection(Categories.EXPENSE.indexOf(cat).coerceAtLeast(0))
        binding.etNote.setText(text)
    }

    /** 添加一个金额候选 Chip，点击后填入金额框（选中时主题色高亮） */
    private fun addAmountChip(group: ChipGroup, amount: Double, checked: Boolean) {
        val chip = Chip(this).apply {
            text = fmtAmount(amount)
            isCheckedIconVisible = false
            isChecked = checked
            setOnClickListener { binding.etAmount.setText(fmtAmount(amount)) }
        }
        group.addView(chip)
    }

    /** 金额紧凑显示：整数不带小数位，否则保留两位 */
    private fun fmtAmount(v: Double): String =
        if (v % 1.0 == 0.0) v.toLong().toString() else fmtMoney(v)

    /** 「识别原文」折叠区展开/收起切换 */
    private fun toggleRecognized() {
        val show = binding.tvRecognized.visibility != View.VISIBLE
        binding.tvRecognized.visibility = if (show) View.VISIBLE else View.GONE
        binding.tvRecognizedArrow.text = if (show) "▴" else "▾"
    }

    private fun save() {
        val amount = binding.etAmount.text.toString().trim().toDoubleOrNull()
        if (amount == null || amount <= 0) {
            binding.etAmount.error = "请输入有效金额"
            return
        }
        val transaction = Transaction(
            date = DateUtils.today(),
            type = "支出",
            category = binding.spCategory.selectedItem?.toString() ?: "其他",
            amount = amount,
            payee = "",
            note = binding.etNote.text.toString().trim()
        )
        lifecycleScope.launch {
            try {
                // userId 由 Repository 自动填入当前登录用户
                repository.insert(transaction)
                Toast.makeText(this@OcrInputActivity, "图片记账已保存", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@OcrInputActivity, e.message ?: "保存失败", Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }
}
