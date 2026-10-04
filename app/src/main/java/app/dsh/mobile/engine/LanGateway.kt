package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 局域网访问（LAN 模式）的开关、网卡探测与访问地址解析。
 *
 * 背景：上游 dsh 的 WebUI 只监听 127.0.0.1，且 `dsh web --host 0.0.0.0`
 * 在 CLI 层被显式拒绝（上游认为那是"把 RCE 暴露给网络"）。本项目的定位是
 * **单用户局域网自托管**（同一个 Wi-Fi 下的手机/平板/电脑访问自己手机上的 Agent），
 * 因此提供一个显式开关 + 风险告知，由用户自己决定要不要对外开放，
 * 而不是让引擎毫无提示地监听全网卡，也不是把这个能力彻底锁死。
 *
 * 地址分两层，别混：
 *  - App 内的 WebView 永远加载环回地址（EngineSupervisor.webUrl），最稳；
 *  - [localIpv4] / [extractLanTokenUrl] 产出的是**给其它设备用**的地址，
 *    随 Wi-Fi / 热点切换而变，只在开关打开时有意义。
 *
 * token 由引擎生成（进程级随机值，落盘在 dsh-home 的凭据里），
 * App 从 engine.log 里捞（见 EngineSupervisor.extractTokenUrl）。
 */
object LanGateway {

    private const val TAG = "LanGateway"
    private const val PREFS = "dsh_ui"
    private const val KEY_LAN = "lan_access"

    /** 本机 LAN 监听端口（与引擎端口一致，引擎由 App 指定） */
    private fun port(enginePort: Int): Int = enginePort

    /** 是否已启用局域网访问。默认关闭 —— 对外暴露必须是用户显式选择。 */
    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LAN, false)

    /** 写入开关（调用方负责随后重启引擎让监听面生效） */
    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_LAN, on).apply()
        Log.i(TAG, "LAN access ${if (on) "enabled" else "disabled"}")
    }

    /**
     * 本机当前的局域网 IPv4 地址；取不到返回 null。
     *
     * 选择策略（按优先级）：
     *  1. 站点内私有段 192.168/16、10/8、172.16/12 —— 家用/办公路由器的典型网段，
     *     同一 Wi-Fi 下的设备都在这；
     *  2. 100.64/10 等其它非环回、非链路本地地址 —— 部分共享网络/运营商 CGNAT、
     *     以及 USB 网络共享（rndis 常是 192.168.42.x，已被 1 覆盖）；
     *  3. 兜底取第一个可用地址。
     *
     * 明确跳过：环回、链路本地 169.254（没 DHCP 时的自分配，不可用）、
     * 以及 IPV6 地址（局域网互访用 IPv4 字面量最省事，免去浏览器里写方括号）。
     */
    fun localIpv4(): String? = runCatching {
        val candidates = NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { nic -> nic.inetAddresses.toList().map { nic to it } }
            .filter { (_, addr) ->
                addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress
            }
            .map { (nic, addr) -> nic.name to addr.hostAddress.orEmpty() }
            .filter { (_, ip) -> ip.isNotEmpty() }

        val (_, ip) = candidates.firstOrNull { (_, ip) -> isSiteLocal(ip) }
            ?: candidates.firstOrNull()
            ?: return@runCatching null
        ip
    }.getOrNull()

    /** 站点内私有段判定：192.168/16、10/8、172.16-31/12 */
    private fun isSiteLocal(ip: String): Boolean {
        val p = ip.split('.')
        if (p.size != 4) return false
        val a = p[0].toIntOrNull() ?: return false
        val b = p[1].toIntOrNull() ?: return false
        return (a == 192 && b == 168) || a == 10 || (a == 172 && b in 16..31)
    }

    /**
     * 从引擎日志里捞「本次启动」的局域网完整地址（带 token）。
     *
     * 主通道：运行时补丁（scripts/patch-lan-access.py）在开关打开时打印的
     * `dsh web: lan url: http://<ip>:<port>/?token=xxx`。该行是权威值 ——
     * 引擎启动那一刻的网卡快照，也正是连接层信任名单快照的那一份。
     *
     * 回退通道：上游自带的 `dsh web: http://127.0.0.1:.../?token=xxx (LAN: ...)`，
     * 只取其中的 token，主机段换成当前实测网卡地址。仅在补丁行缺失时兜底
     *（例如引擎是旧版本、或运行时尚不支持该补丁）。
     *
     * @param logFile   engine.log
     * @param fromOffset 本次启动的日志起点（只看新写段落，避免复用上次已失效的 token）
     */
    fun extractLanTokenUrl(logFile: File, fromOffset: Long, enginePort: Int): String? =
        runCatching {
            if (!logFile.isFile) return@runCatching null
            val text = logFile.readText()
            val from = fromOffset.coerceIn(0L, text.length.toLong()).toInt()
            val seg = text.substring(from)

            // 主通道：运行时补丁打印的专用行
            Regex("dsh web: lan url: (http://\\d{1,3}(?:\\.\\d{1,3}){3}:\\d+/\\?token=\\S+)")
                .findAll(seg).lastOrNull()?.groupValues?.get(1)
                ?.let { return@runCatching it }

            // 次通道：上游就绪行的 (LAN: ...) 段落（绑定 0.0.0.0 时上游本来就会打印）
            Regex("\\(LAN: (http://\\d{1,3}(?:\\.\\d{1,3}){3}:\\d+/\\?token=[^)\\s]+)\\)")
                .findAll(seg).lastOrNull()?.groupValues?.get(1)
                ?.let { return@runCatching it }

            // 回退通道：只有环回地址行 → 取其 token，主机换成当前网卡
            val m = Regex("dsh web: (http://127\\.0\\.0\\.1:\\d+/\\?token=\\S+)").findAll(seg)
                .lastOrNull() ?: return@runCatching null
            val token = Regex("[?&]token=([^&\\s]+)").find(m.groupValues[1])
                ?.groupValues?.get(1) ?: return@runCatching null
            val ip = localIpv4() ?: return@runCatching null   // 没有网卡就没有局域网地址
            "http://$ip:${port(enginePort)}/?token=$token"
        }.getOrNull()
}
