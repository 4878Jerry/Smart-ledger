package com.ousuan.smartbutler.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 系统级网络状态监听（单例）：
 * 通过 [ConnectivityManager.NetworkCallback] 实时感知 Wi-Fi / 移动数据等网络接口的
 * 连接与断开，维护 [isConnectedFlow] 供业务方观察，并提供同步判断方法 [isConnected]。
 *
 * 注意：这里只表示「存在可用网络接口」，不代表能访问业务服务器；
 * 服务器连通性仍需 [NetworkChecker.checkServerAvailable] 做二次探测。
 */
object NetworkMonitor {

    private const val TAG = "NetworkMonitor"

    private val _isConnected = MutableStateFlow(false)

    /** 实时网络状态：true 表示当前存在可用网络，false 表示完全断网 */
    val isConnectedFlow: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var context: Context? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var registered = false

    /** 在 Application.onCreate 中调用：初始化当前状态并注册网络变化监听（幂等） */
    fun init(context: Context) {
        if (registered) return
        this.context = context.applicationContext
        val cm = this.context!!.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // 以当前默认网络初始化状态，避免 App 启动时误判为断网
        _isConnected.value = hasInternet(cm)

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                updateState(hasInternet(cm))
            }

            override fun onLost(network: Network) {
                // 单个网络丢失时可能仍有其它默认网络，需重新校验整体状态
                updateState(hasInternet(cm))
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                updateState(hasInternet(cm))
            }
        }
        callback = cb
        cm.registerDefaultNetworkCallback(cb)
        registered = true
        Log.d(TAG, "网络监听已注册，当前网络状态: ${_isConnected.value}")
    }

    /** 同步判断当前是否有可用网络（供 NetworkChecker 等场景直接调用） */
    fun isConnected(): Boolean = _isConnected.value

    /** 在 Application.onTerminate 中调用：取消网络监听（真实设备进程被杀前通常不会回调） */
    fun unregister() {
        if (!registered) return
        try {
            val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val cb = callback
            if (cm != null && cb != null) {
                cm.unregisterNetworkCallback(cb)
            }
        } catch (e: Exception) {
            Log.w(TAG, "取消网络监听失败: ${e.message}")
        }
        registered = false
        callback = null
        context = null
        Log.d(TAG, "网络监听已取消注册")
    }

    /**
     * 校验当前默认网络是否有可用接口。
     * 注意：不要求 NET_CAPABILITY_VALIDATED（系统外网验证）——
     * 后端服务器常部署在局域网 / Tailscale 内网，此时 Wi-Fi 无外网但可直连服务器，
     * 若强制要求 VALIDATED 会把「仅内网」误判为断网，导致登录降级为本地账号、
     * 预算 / 记录 / 社区全部云同步被短路。真正的服务器连通性由
     * [NetworkChecker.checkServerAvailable] 做二次探测兜底。
     */
    private fun hasInternet(cm: ConnectivityManager): Boolean {
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun updateState(connected: Boolean) {
        if (_isConnected.value != connected) {
            _isConnected.value = connected
            Log.d(TAG, "网络状态变化: $connected")
        }
    }
}
