package com.ousuan.smartbutler

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.ousuan.smartbutler.data.AppDatabase
import com.ousuan.smartbutler.data.TransactionRepository
import com.ousuan.smartbutler.data.network.ApiConfig
import com.ousuan.smartbutler.data.network.NetworkChecker
import com.ousuan.smartbutler.data.network.NetworkMonitor
import com.ousuan.smartbutler.data.network.TokenManager
import com.ousuan.smartbutler.data.repository.BudgetRepository
import com.ousuan.smartbutler.data.repository.CommunityRepository
import com.ousuan.smartbutler.data.repository.UserRepository
import com.ousuan.smartbutler.data.sync.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 应用入口：持有全局单例数据库、仓库、云同步管理器，并监听网络恢复 */
class SmartButlerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 强制深色模式，保证界面在任意系统主题下均为深色可读
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        // 统一 token 管理器初始化（AuthInterceptor / 登录 / 登出共用同一 key）
        TokenManager.init(this)
        // 服务器地址配置初始化（读取用户保存的地址，未设置则用默认值）
        ApiConfig.init(this)
        // 注册系统级网络监听：实时感知连网/断网，供 NetworkChecker 与自动同步使用
        NetworkMonitor.init(this)
        // 异步检查服务器连通性：连通/离线仅打日志，不弹窗、不阻塞 UI
        applicationScope.launch {
            if (NetworkChecker.checkServerAvailable()) {
                Log.d("SmartButlerApp", "服务器已连接: ${ApiConfig.getBaseUrl()}")
            } else {
                Log.d("SmartButlerApp", "服务器不可用，运行在离线模式")
            }
        }
        // 社区仓库注入 Context 与当前登录用户名（帖子持久化 + 评论/发布时使用）
        CommunityRepository.init(this) { userRepository.getCurrentUser()?.username }
        // 新记录插入后立即触发同步（上传服务器 + 拉取最新）
        repository.onInserted = { syncManager.syncOnNetworkRestore() }
        // 登录成功后自动从服务器下载该用户数据（记录 + 我的帖子），实现重装恢复
        userRepository.onLoginSuccess = { user ->
            Log.d("Sync", "登录成功(${user.username})，开始下载服务器数据...")
            applicationScope.launch { syncManager.downloadAllData() }
        }
        // 启动即尝试同步一次（已登录且有未同步数据时生效）
        syncManager.syncOnNetworkRestore()
        // 监听网络状态变化：从离线恢复为在线时自动增量同步（替代旧的 NetworkCallback）
        syncManager.observeNetworkState()
    }

    override fun onTerminate() {
        super.onTerminate()
        // 注意：真实设备上进程由系统直接回收，onTerminate 仅在模拟器环境回调；
        // 此处调用确保在受支持的环境下释放网络监听
        NetworkMonitor.unregister()
    }

    val database: AppDatabase by lazy { AppDatabase.get(this) }

    val repository: TransactionRepository by lazy {
        // 注入当前登录用户：数据隔离与公开统计归属均以当前用户为准（未登录返回 null）
        TransactionRepository(database.transactionDao()) { userRepository.getCurrentUser() }
    }

    /** 账号仓库（登录/注册/token 管理） */
    val userRepository: UserRepository by lazy { UserRepository(this) }

    /** 预算仓库：本地 Room 缓存 + 服务器同步（users.budget_json） */
    val budgetRepository: BudgetRepository by lazy {
        BudgetRepository(this, database.budgetDao())
    }

    /** 云同步管理器：独立协程作用域，网络回调线程中也能安全触发 */
    val syncManager: SyncManager by lazy {
        SyncManager(applicationScope, repository, userRepository, budgetRepository)
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
