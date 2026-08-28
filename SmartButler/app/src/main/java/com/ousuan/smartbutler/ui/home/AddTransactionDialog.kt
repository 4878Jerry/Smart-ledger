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
import com.ousuan.smartbutler.util.Categories
import com.ousuan.smartbutler.util.DateUtils
import kotlinx.coroutines.launch

/** 手动记账弹窗：类型联动分类，保存后插入数据库 */
class AddTransactionDialog {

    fun show(context: Context, repository: TransactionRepository, scope: LifecycleCoroutineScope) {
        // 未登录不允许记账（数据隔离：必须归属某个用户）
        if ((context.applicationContext as SmartButlerApp).userRepository.getCurrentUser() == null) {
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

        etDate.setText(DateUtils.today())

        val typeAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, arrayOf("支出", "收入"))
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spType.adapter = typeAdapter

        fun refreshCategory(type: String) {
            val cats = if (type == "收入") Categories.INCOME else Categories.EXPENSE
            val catAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, cats)
            catAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spCategory.adapter = catAdapter
        }
        refreshCategory("支出")

        spType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                refreshCategory(if (pos == 0) "支出" else "收入")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("手动记账")
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
            val transaction = Transaction(
                date = etDate.text.toString().trim().ifEmpty { DateUtils.today() },
                type = if (spType.selectedItemPosition == 0) "支出" else "收入",
                category = spCategory.selectedItem?.toString() ?: "其他",
                amount = amount,
                payee = etPayee.text.toString().trim(),
                note = etNote.text.toString().trim()
            )
            scope.launch {
                try {
                    // userId 由 Repository 自动填入当前登录用户
                    repository.insert(transaction)
                    Toast.makeText(context, "已保存记录", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, e.message ?: "保存失败", Toast.LENGTH_SHORT).show()
                }
            }
            dialog.dismiss()
        }
    }
}
