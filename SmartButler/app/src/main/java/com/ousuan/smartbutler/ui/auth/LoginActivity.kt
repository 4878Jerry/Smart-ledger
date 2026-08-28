package com.ousuan.smartbutler.ui.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ousuan.smartbutler.MainActivity
import com.ousuan.smartbutler.SmartButlerApp
import com.ousuan.smartbutler.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch

/**
 * 登录页：应用启动入口。
 * - SharedPreferences 中已保存用户信息（已登录）则跳过登录页直接进入主界面（无需联网验证）；
 * - 登录成功跳转 MainActivity，失败 Toast 提示；
 * - 点击注册跳转 RegisterActivity。
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val userRepository by lazy { (application as SmartButlerApp).userRepository }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 已登录（本地保存过用户信息）则直接进入主界面，跳过登录页
        if (userRepository.isLoggedIn()) {
            goMain()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.etUsername.setText(intent.getStringExtra(EXTRA_USERNAME) ?: "")

        binding.btnLogin.setOnClickListener { login() }
        binding.btnRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    /** 注册页跳回本页（CLEAR_TOP 复用实例）时预填用户名 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (::binding.isInitialized) {
            binding.etUsername.setText(intent.getStringExtra(EXTRA_USERNAME) ?: "")
        }
    }

    private fun login() {
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString()
        if (username.isEmpty()) {
            binding.etUsername.error = "请输入用户名"
            return
        }
        if (password.isEmpty()) {
            binding.etPassword.error = "请输入密码"
            return
        }
        binding.btnLogin.isEnabled = false
        lifecycleScope.launch {
            userRepository.login(username, password)
                .onSuccess { user ->
                    Toast.makeText(this@LoginActivity, "欢迎回来，${user.nickname ?: user.username}", Toast.LENGTH_SHORT).show()
                    goMain()
                }
                .onFailure { e ->
                    Toast.makeText(this@LoginActivity, e.message ?: "登录失败，请重试", Toast.LENGTH_SHORT).show()
                }
            binding.btnLogin.isEnabled = true
        }
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    companion object {
        const val EXTRA_USERNAME = "extra_username"
    }
}
