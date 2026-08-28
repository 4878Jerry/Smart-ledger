package com.ousuan.smartbutler.ui.settings

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ousuan.smartbutler.data.network.ApiConfig
import com.ousuan.smartbutler.databinding.ActivitySettingsBinding

/**
 * 服务器设置页：
 * - 输入框默认显示当前保存的服务器地址；
 * - 保存后写入 SharedPreferences 并立即重建 Retrofit 实例，无需重启应用即可生效。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 输入框默认显示当前保存的服务器地址（未设置过则显示默认值）
        binding.etServerUrl.setText(ApiConfig.getBaseUrl().trimEnd('/'))
        binding.etServerUrl.setSelection(binding.etServerUrl.text?.length ?: 0)

        binding.btnSave.setOnClickListener { save() }
        binding.btnReset.setOnClickListener { reset() }
    }

    /** 保存服务器地址并立即生效 */
    private fun save() {
        val input = binding.etServerUrl.text?.toString()?.trim().orEmpty()
        if (input.isEmpty()) {
            Toast.makeText(this, "请输入服务器地址", Toast.LENGTH_SHORT).show()
            return
        }
        if (!input.startsWith("http://") && !input.startsWith("https://")) {
            Toast.makeText(this, "地址需以 http:// 或 https:// 开头", Toast.LENGTH_SHORT).show()
            return
        }
        ApiConfig.saveBaseUrl(input)
        Toast.makeText(this, "已保存，设置已生效（无需重启）", Toast.LENGTH_SHORT).show()
        finish()
    }

    /** 恢复默认地址 */
    private fun reset() {
        ApiConfig.resetBaseUrl()
        binding.etServerUrl.setText(ApiConfig.getBaseUrl().trimEnd('/'))
        binding.etServerUrl.setSelection(binding.etServerUrl.text?.length ?: 0)
        Toast.makeText(this, "已恢复默认地址，设置已生效（无需重启）", Toast.LENGTH_SHORT).show()
    }
}
