package com.ousuan.smartbutler.ui.auth

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ousuan.smartbutler.MainActivity
import com.ousuan.smartbutler.R
import com.ousuan.smartbutler.SmartButlerApp
import com.ousuan.smartbutler.data.network.ApiConfig
import com.ousuan.smartbutler.data.network.NetworkChecker
import com.ousuan.smartbutler.databinding.ActivityLoginBinding
import com.ousuan.smartbutler.util.MascotManager
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

    /** 输入防抖 Handler：停止输入 600ms 后自动保存服务器地址 */
    private val urlSaveHandler = Handler(Looper.getMainLooper())

    /** 上次检测过的服务器地址（复用检测结果，避免登录时重复等待） */
    private var lastCheckedUrl: String? = null

    /** 上次服务器连通性检测结果 */
    private var lastServerOk = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 已登录（本地保存过用户信息）则直接进入主界面，跳过登录页
        if (userRepository.isLoggedIn()) {
            goMain()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 登录页小鸥：分层渲染应用全局当前形象
        MascotManager.applyLookTo(binding.imgMascotLogin)

        binding.etUsername.setText(intent.getStringExtra(EXTRA_USERNAME) ?: "")

        // 服务器地址：默认显示已保存地址（未设置过则显示默认值），修改后实时保存立即生效
        binding.etServerUrl.setText(ApiConfig.getBaseUrl().trimEnd('/'))
        binding.etServerUrl.setSelection(binding.etServerUrl.text?.length ?: 0)
        binding.btnSaveServer.setOnClickListener { saveAndCheckServer() }
        binding.etServerUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // 输入停止 600ms 后自动保存（格式完整才生效），无需点击「保存」
                urlSaveHandler.removeCallbacksAndMessages(null)
                urlSaveHandler.postDelayed({ autoSaveServerUrl() }, 600)
            }
        })
        // 打开页面即检测一次服务器连接状态
        checkServerStatus()

        binding.btnLogin.setOnClickListener { login() }
        binding.btnRegister.setOnClickListener {
            // 先保存当前地址（后续注册请求使用新地址），再进入注册页
            saveCurrentServerUrl()
            startActivity(Intent(this, RegisterActivity::class.java))
            // 非阻塞检测：服务器不可达时提示离线注册本地账号（不阻断进入注册页）
            checkServerStatus { serverOk ->
                if (!serverOk) {
                    Toast.makeText(this@LoginActivity, "服务器不可达，将以离线模式注册本地账号", Toast.LENGTH_SHORT).show()
                }
            }
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
        // 先保存当前地址：本次登录请求立即使用新地址（无需重启）
        saveCurrentServerUrl()

        binding.btnLogin.isEnabled = false
        // 检测服务器连通性：不可达时明确提示离线降级；不阻断登录，UserRepository 内部自动降级本地账号
        checkServerStatus { serverOk ->
            if (!serverOk) {
                Toast.makeText(this@LoginActivity, "服务器不可达，将以离线模式登录", Toast.LENGTH_SHORT).show()
            }
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

    // ===================== 服务器地址：保存 / 校验 / 连通性检测 =====================

    /** 保存当前输入框地址到 ApiConfig（仅格式有效时保存，立即生效无需重启） */
    private fun saveCurrentServerUrl() {
        val text = binding.etServerUrl.text.toString().trim()
        if (isUrlValid(text) && text != ApiConfig.getBaseUrl().trimEnd('/')) {
            ApiConfig.saveBaseUrl(text)
            lastCheckedUrl = null // 地址已变，之前检测结果作废
        }
    }

    /** 输入停止后的防抖自动保存（与手动保存同链路，不重复探测服务器） */
    private fun autoSaveServerUrl() {
        saveCurrentServerUrl()
    }

    /** 点击「保存」：校验并保存地址，然后检测服务器连通性 */
    private fun saveAndCheckServer() {
        val text = binding.etServerUrl.text.toString().trim()
        if (text.isEmpty() || !isUrlValid(text)) {
            showStatus("⚠️ 地址格式错误", R.color.expense)
            showOfflineHint("地址格式错误，请以 http:// 或 https:// 开头；当前将使用本地账号登录")
            return
        }
        ApiConfig.saveBaseUrl(text)
        lastCheckedUrl = null
        Toast.makeText(this, "已保存，设置已生效（无需重启）", Toast.LENGTH_SHORT).show()
        checkServerStatus()
    }

    /** 地址格式校验：必须 http:// 或 https:// 开头 */
    private fun isUrlValid(text: String): Boolean =
        text.trim().startsWith("http://") || text.trim().startsWith("https://")

    /**
     * 检测服务器连通性并更新状态提示（绿=在线 / 黄=离线 / 红=地址格式错误）。
     * 复用最近一次成功检测结果避免重复等待；
     * onResult 为挂起回调（suspend），在协程中调用，回调内可直接执行挂起函数（如登录）。
     */
    private fun checkServerStatus(onResult: (suspend (Boolean) -> Unit)? = null) {
        val text = binding.etServerUrl.text.toString().trim()
        if (text.isEmpty() || !isUrlValid(text)) {
            showStatus("⚠️ 地址格式错误", R.color.expense)
            showOfflineHint("地址格式错误，将使用本地账号登录；请检查地址是否以 http:// 或 https:// 开头")
            lifecycleScope.launch { onResult?.invoke(false) }
            return
        }
        // 地址与上次检测一致且连接成功：直接复用结果，避免登录时重复等待 3 秒
        if (lastCheckedUrl == ApiConfig.getBaseUrl() && lastServerOk) {
            showStatus("✅ 已连接服务器", R.color.income)
            hideOfflineHint()
            lifecycleScope.launch { onResult?.invoke(true) }
            return
        }
        showStatus("⏳ 正在检测服务器连接…", R.color.text_secondary)
        lifecycleScope.launch {
            val ok = NetworkChecker.checkServerAvailable(3000)
            lastCheckedUrl = ApiConfig.getBaseUrl()
            lastServerOk = ok
            if (ok) {
                showStatus("✅ 已连接服务器", R.color.income)
                hideOfflineHint()
            } else {
                showStatus("📡 离线模式（无法连接服务器）", R.color.banner_text)
                showOfflineHint("当前处于离线模式，将使用本地账号登录；建议检查服务器地址，或先注册本地账号后离线登录")
            }
            onResult?.invoke(ok)
        }
    }

    private fun showStatus(text: String, colorRes: Int) {
        binding.tvServerStatus.text = text
        binding.tvServerStatus.setTextColor(ContextCompat.getColor(this, colorRes))
        binding.tvServerStatus.visibility = View.VISIBLE
    }

    private fun showOfflineHint(msg: String) {
        binding.tvOfflineHint.text = msg
        binding.tvOfflineHint.visibility = View.VISIBLE
    }

    private fun hideOfflineHint() {
        binding.tvOfflineHint.visibility = View.GONE
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
