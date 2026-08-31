package com.ousuan.smartbutler.ui.home

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ousuan.smartbutler.R
import com.ousuan.smartbutler.SmartButlerApp
import com.ousuan.smartbutler.data.Transaction
import com.ousuan.smartbutler.data.TransactionRepository
import com.ousuan.smartbutler.ui.mascot.MascotOverlayManager
import com.ousuan.smartbutler.util.Categories
import com.ousuan.smartbutler.util.DateUtils
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * 记账弹窗：类型联动分类。
 * - existing == null：新增模式（手动记账），保存后插入数据库；
 * - existing != null：编辑模式（首页点击列表项），预填原数据，保存后更新原记录，
 *   保留 id / localId / userId / isPublic 不变，日期变化时重算 timestamp（服务器以
 *   timestamp 推导日期），并把 synced 置为 false + 触发 onInserted 重新同步到服务器。
 */
class AddTransactionDialog {

    fun show(
        context: Context,
        repository: TransactionRepository,
        scope: LifecycleCoroutineScope,
        existing: Transaction? = null
    ) {
        // 新增模式需登录（数据隔离：必须归属某个用户）；编辑模式数据已在本地，无需再校验
        if (existing == null &&
            (context.applicationContext as SmartButlerApp).userRepository.getCurrentUser() == null
        ) {
            Toast.makeText(context, "请先登录后再记账", Toast.LENGTH_SHORT).show()
            return
        }
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_add_transaction, null)
        val spType = view.findViewById<Spinner>(R.id.sp_type)
        val spCategory = view.findViewById<Spinner>(R.id.sp_category)
        val etDate = view.findViewById<EditText>(R.id.et_date)
        val etAmount = view.findViewById<EditText>(R.id.et_amount)
        val etPayee = view.findViewById<EditText>(R.id.et_payee)
        val etNote = view.findViewById<EditText>(R.id.et_note)

        etDate.setText(existing?.date ?: DateUtils.today())
        if (existing != null) {
            etAmount.setText(if (existing.amount % 1.0 == 0.0) existing.amount.toLong().toString() else existing.amount.toString())
            etPayee.setText(existing.payee)
            etNote.setText(existing.note)
        }

        val typeAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, arrayOf("支出", "收入"))
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spType.adapter = typeAdapter

        fun refreshCategory(type: String) {
            val cats = if (type == "收入") Categories.INCOME else Categories.EXPENSE
            val catAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, cats)
            catAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spCategory.adapter = catAdapter
        }
        refreshCategory(existing?.type ?: "支出")
        // 编辑模式：预选原类型与分类
        if (existing != null) {
            spType.setSelection(if (existing.type == "收入") 1 else 0)
            val cats = if (existing.type == "收入") Categories.INCOME else Categories.EXPENSE
            val catIdx = cats.indexOf(existing.category)
            if (catIdx >= 0) spCategory.setSelection(catIdx)
        }

        spType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                refreshCategory(if (pos == 0) "支出" else "收入")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(if (existing == null) "手动记账" else "编辑记录")
            .setView(view)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val amount = etAmount.text.toString().trim().toDoubleOrNull()
            if (amount == null || amount <= 0) {
                etAmount.error = "请输入有效金额"
                return@setOnClickListener
            }
            val date = etDate.text.toString().trim().ifEmpty { DateUtils.today() }
            val type = if (spType.selectedItemPosition == 0) "支出" else "收入"
            val category = spCategory.selectedItem?.toString() ?: "其他"
            scope.launch {
                try {
                    if (existing == null) {
                        // 新增：userId 由 Repository 自动填入当前登录用户
                        repository.insert(
                            Transaction(
                                date = date,
                                type = type,
                                category = category,
                                amount = amount,
                                payee = etPayee.text.toString().trim(),
                                note = etNote.text.toString().trim()
                            )
                        )
                        Toast.makeText(context, "已保存记录，小鸥给你点赞~", Toast.LENGTH_SHORT).show()
                    } else {
                        // 编辑：保留 id/localId/userId/isPublic 与未修改的时间戳；
                        // 日期变化时重算 timestamp（服务器以 timestamp 推导日期，保持两端一致）；
                        // 置 synced=false 并触发 onInserted，使修改尽快推送到服务器（按 localId 幂等更新）
                        val newTimestamp = if (date != existing.date) {
                            LocalDate.parse(date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        } else {
                            existing.timestamp
                        }
                        repository.update(
                            existing.copy(
                                date = date,
                                type = type,
                                category = category,
                                amount = amount,
                                payee = etPayee.text.toString().trim(),
                                note = etNote.text.toString().trim(),
                                timestamp = newTimestamp,
                                synced = false
                            )
                        )
                        repository.onInserted?.invoke()
                        Toast.makeText(context, "记录已更新", Toast.LENGTH_SHORT).show()
                    }
                    // 小鸥开心闪显（不改变用户当前换装选择）
                    MascotOverlayManager.flashEmotion("face_happy")
                } catch (e: Exception) {
                    Toast.makeText(context, e.message ?: "保存失败", Toast.LENGTH_SHORT).show()
                }
            }
            dialog.dismiss()
        }
    }
}
