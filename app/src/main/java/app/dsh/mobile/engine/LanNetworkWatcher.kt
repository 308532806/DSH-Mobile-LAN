package app.dsh.mobile.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 网卡变化监听：局域网模式下 IP 变了就重启引擎。
 *
 * 为什么必须重启，而不是「在 UI 上显示新 IP」了事：
 *
 * 引擎在**启动那一刻**做两件与网卡绑定的事，之后都不会再更新 ——
 *  1. `resolveLanTrust()` 把所有非环回 IPv4 拍进「浏览器信任名单」，/api 请求的
 *     Host 栅栏按这份名单放行（这是上游防 DNS rebinding 的机制，我们不去绕它）；
 *  2. 就绪日志里的局域网地址与 token 组合。
 * 所以切 Wi-Fi / 开关热点之后，如果只把新 IP 显示给用户，设备打开页面会
 * 「页面能加载但所有 /api 请求被打回」——比直接打不开更难排查。
 *
 * 重启是让监听面、信任名单、日志地址三者一起重新快照的唯一干净做法，
 * 也顺带覆盖了「先开 LAN 后连 Wi-Fi」的场景。重启由 superviseLoop 统一收敛，
 * 这里只负责在合适的时机触发一次。
 *
 * 去抖：网络回调常常成串到达（断开→连接→拿到 IP 分几次通知），
 * 且刚连接时网卡可能还没有地址，所以每次事件后等 [SETTLE_MS] 让网卡稳定再判定。
 */
class LanNetworkWatcher(
    private val ctx: Context,
    private val supervisor: EngineSupervisor,
    private val scope: CoroutineScope,
) {
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var pending: Job? = null

    fun start() {
        if (callback != null) return
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = schedule()
            override fun onLost(network: Network) = schedule()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = schedule()
        }
        // 只关心有网卡这件事本身（不按传输类型过滤：Wi-Fi / 热点 / USB 网络共享都算）
        runCatching { cm.registerDefaultNetworkCallback(cb) }
            .onSuccess {
                callback = cb
                Log.i(TAG, "network watcher started")
            }
            .onFailure { Log.w(TAG, "registerDefaultNetworkCallback failed: ${it.message}") }
    }

    fun stop() {
        pending?.cancel()
        pending = null
        callback?.let { cb ->
            runCatching { ctx.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
        callback = null
    }

    private fun schedule() {
        pending?.cancel()
        pending = scope.launch(Dispatchers.IO) {
            delay(SETTLE_MS)
            if (!isActive) return@launch
            checkAndRestart()
        }
    }

    /**
     * 判定是否需要重启：仅在「局域网模式开着 + 引擎已经就绪 + 当前网卡地址
     * 与引擎启动时用的那个不一致」时动手。否则网络抖动会变成无谓的引擎重启。
     */
    private suspend fun checkAndRestart() {
        if (!LanGateway.isEnabled(ctx)) return
        val liveUrl = supervisor.lanUrl() ?: return          // 引擎未就绪 / 上次启动没拿到地址
        val currentIp = LanGateway.localIpv4() ?: return      // 网卡还没起来，等下一次回调
        val startedIp = IP_IN_URL.find(liveUrl)?.groupValues?.get(1)
        if (startedIp == currentIp) return
        Log.i(TAG, "LAN address changed ($startedIp -> $currentIp), restarting engine to re-snapshot")
        supervisor.restart()
    }

    companion object {
        private const val TAG = "LanNetworkWatcher"

        /** 网络事件后等网卡稳定；手机切换 Wi-Fi 通常在 1-3 秒内完成 DHCP */
        private const val SETTLE_MS = 3_000L

        private val IP_IN_URL = Regex("http://([0-9.]+):\\d+/")
    }
}
