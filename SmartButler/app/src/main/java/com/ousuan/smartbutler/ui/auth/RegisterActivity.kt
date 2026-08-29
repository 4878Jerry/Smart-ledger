package com.ousuan.smartbutler.ui.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ousuan.smartbutler.SmartButlerApp
import com.ousuan.smartbutler.databinding.ActivityRegisterBinding
import com.ousuan.smartbutler.util.MascotManager
import kotlinx.coroutines.launch

/**
 * 注册页：校验用户名非空/不重复、密码长度、两次密码一致，
 * 注册成功跳回登录页（自动预填用户名）。
 */
class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private val userRepository by lazy { (application as SmartButlerApp).userRepository }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 注册页小鸥：应用全局当前形象
        binding.imgMascotRegister.setImageResource(MascotManager.current().drawableRes)

        binding.btnRegister.setOnClickListener { register() }
    }

    private fun register() {
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString()
        val confirm = binding.etConfirm.text.toString()

        if (username.isEmpty()) {
            binding.etUsername.error = "请输入用户名"
            return
        }
        if (password.length < 6) {
            binding.etPassword.error = "密码至少 6 位"
            return
        }
        if (password != confirm) {
            binding.etConfirm.error = "两次输入的密码不一致"
            return
        }

        binding.btnRegister.isEnabled = false
        lifecycleScope.launch {
            userRepository.register(username, password)
                .onSuccess {
                    Toast.makeText(this@RegisterActivity, "注册成功，请登录", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this@RegisterActivity, LoginActivity::class.java).apply {
                        putExtra(LoginActivity.EXTRA_USERNAME, username)
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    })
                    finish()
                }
                .onFailure { e ->
                    Toast.makeText(this@RegisterActivity, e.message ?: "注册失败，请重试", Toast.LENGTH_SHORT).show()
                }
            binding.btnRegister.isEnabled = true
        }
    }
}
