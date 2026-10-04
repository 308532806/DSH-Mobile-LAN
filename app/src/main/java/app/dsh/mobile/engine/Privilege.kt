package app.dsh.mobile.engine

import android.content.Context
import android.util.Log

/**
 * 运行权限模式与真机能力探测。
 *
 * 私有存储布局：
 *   filesDir/engine/       引擎运行时根（node + node_modules）
 *   filesDir/dsh-home/     用户资产（会话/凭证/配置）—— 提权模式下必须保护
 *
 * 三种模式：
 *  - NORMAL     默认。App 沙箱 uid 运行，引擎 node 以 App 权限执行，AI 命令仅限沙箱内。
 *  - SHIZUKU    AI 额外能以 adb 身份跑 shell（保活/杀进程），引擎本体仍 App 权限。
 *  - ROOT       引擎整体以 su(uid 0) 启动，AI 可全盘操作；必须两次警告 + 预备份 dsh-home。
 *
 * 注意：Shizuku 只能授予应用本体（binder/远程）能力，无法改变 fork 出的子进程 uid，
 *  因此 Shizuku 模式【不把引擎 node 提权】，而是给 AI 的执行工具注入 shizuku/adb 身份。
 */
enum class PrivMode { NORMAL, SHIZUKU, ROOT }

data class PrivCapability(
    val hasRoot: Boolean = false,     // 存在 su 且可执行（Magisk/KernelSU 等）
    val hasShizuku: Boolean = false,  // Shizuku 服务已授权（浅探测）
)

/** 权限模式存取 + 真机能力探测（纯检测，不做任何危险动作） */
object Privilege {

    private const val TAG = "Privilege"
    private const val PREFS = "dsh_priv"
    private const val KEY_MODE = "priv_mode"
    private const val KEY_ONBOARDED = "onboarded"

    // 常见 su 路径（存在其一即视为具备 Root 能力）
    //
    // 【v1.3.2 真机事故修复】原列表只盯 /system/bin 等传统位置，
    // 实测 OPPO/Android 12 + Magisk（alpha）上这些全都不存在：
    //   su 由 magic mount 挂到 /product/bin/su 与 /debug_ramdisk/su
    //   （都是指向 ./magisk 的符号链接），而 /system/bin/su 根本没有。
    // 于是"手机明明有 root，软件却检测不到"——纯路径列表缺口。
    //
    // 追加两个真实落点：/product/bin/su（新 Magisk 常见）、
    // /debug_ramdisk/su（Magisk 24+ 的真实二进制位置）、APatch 的 /data/adb/ap*。
    // 同时**移除两个根本不是 su 的条目**：
    //   · /system/bin/busybox —— 装了 busybox ≠ 有 root；更糟的是 findSu() 会把它
    //     当 su 可执行文件交给 Root 模式启动引擎（busybox -c ... 必然失败）；
    //   · /system/bin/su.d   —— 那是放脚本的目录，不是可执行文件。
    private val SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su",
        "/product/bin/su",                      // Magisk（新）实测落点
        "/debug_ramdisk/su",                    // Magisk 24+ 真实二进制
        "/system_ext/bin/su", "/odm/bin/su",
        "/sbin/su", "/vendor/bin/su", "/su/bin/su",
        "/data/adb/magisk/su",                  // 旧 Magisk（/data/adb 为 0700，应用 stat 不到，仅兜底）
        "/data/adb/ksu/bin/su",                 // KernelSU
        "/data/adb/ap/bin/su", "/data/adb/apd/bin/su",  // APatch
    )

    // 常见 Shizuku 服务名（存在授权即视为可用）
    private val SHIZUKU_PACKAGES = listOf(
        "moe.shizuku.privileged.api",          // Shizuku Server
        "moe.shizuku.manager",                 // Shizuku Manager
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getMode(ctx: Context): PrivMode = runCatching {
        PrivMode.valueOf(prefs(ctx).getString(KEY_MODE, PrivMode.NORMAL.name)!!)
    }.getOrDefault(PrivMode.NORMAL)

    fun setMode(ctx: Context, mode: PrivMode) {
        prefs(ctx).edit().putString(KEY_MODE, mode.name).apply()
        Log.i(TAG, "priv mode -> $mode")
    }

    fun isOnboarded(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ONBOARDED, false)

    fun markOnboarded(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_ONBOARDED, true).apply()
    }

    /** 浅探测 Shizuku：Manager/Server 包是否安装（不代表已授权） */
    private fun probeShizukuInstalled(ctx: Context): Boolean {
        val pm = ctx.packageManager
        return SHIZUKU_PACKAGES.any {
            runCatching { pm.getPackageInfo(it, 0) != null }.getOrDefault(false)
        }
    }

    fun probe(ctx: Context): PrivCapability = PrivCapability(
        hasRoot = rootAvailable(),
        hasShizuku = probeShizukuInstalled(ctx),
    )

    /**
     * 主动探测结果缓存：null = 本次进程内还没探测过。
     * 被动路径检查可能因为路径列表不全而误判"没有 root"，主动探测是权威结论，
     * 一旦成功就持久到进程结束，避免 UI 反复显示"未检测到"。
     */
    @Volatile private var activeProbeOk: Boolean? = null

    /**
     * UI 判定用（引导页置灰、设置页状态行）：被动路径检查 **或** 主动探测已成功。
     *
     * 只做被动检查时会有假阴性（su 挂在列表外的位置）—— 真机事故：
     * OPPO + Magisk 的 su 在 /product/bin/su，旧列表没有它 → "明明有 root 却检测不到"。
     */
    fun rootAvailable(): Boolean = rootAvailableMinimal() || activeProbeOk == true

    /**
     * 轻量 Root 可用性（UI 变灰用，m1.30）：仅 stat 常见 su 路径，不执行 su -c id，
     * 避免每次读 UI 都触发 Magisk 授权弹窗。用户显式动作时才用 [runSuCheckRoot] 真实探测。
     *
     * 注意这只代表"路径存在"，不代表 su 真能用（可能被策略拒绝/授权未给）。
     * 用户看到"未检测到"但确信有 root 时，用 [runSuCheckRoot] 做一次真探测。
     */
    fun rootAvailableMinimal(): Boolean = SU_PATHS.any { java.io.File(it).exists() }

    /**
     * 真探测：逐个候选 su 执行 `-c id`，能拿到 uid=0 才算有 root。
     *
     * 为什么必须用**绝对路径**，而不是裸 `su`：
     * Android 应用的 PATH 由 zygote 设定，未必包含 `/product/bin`
     * （本机实测 su 就挂在那里），此时 `ProcessBuilder("su", ...)` 直接
     * FileNotFoundException → 误判"设备没 root"。绝对路径不依赖 PATH。
     *
     * 副作用：会触发 Magisk/KernelSU 的授权弹窗，所以只在用户显式动作
     * （引导页点提示行、设置页点状态行）时调用，绝不放进会频繁重绘的路径。
     * 首个候选给足 [timeoutMs] 让用户从容点"允许"，后续兜底候选快速跳过。
     */
    fun runSuCheckRoot(timeoutMs: Long = 10_000): Boolean {
        val existing = SU_PATHS.filter {
            runCatching { java.io.File(it).exists() }.getOrDefault(false)
        }
        // 存在的候选优先（真正会执行的通常只有一个），裸 su 放最后兜底
        val candidates = (existing + listOf("su")).distinct()
        var first = true
        for (cmd in candidates) {
            val budget = if (first) timeoutMs else 1_500
            first = false
            val ok = runCatching {
                val p = ProcessBuilder(cmd, "-c", "id").redirectErrorStream(true).start()
                // 先等退出再读：`id` 的输出只有几十字节，不可能塞满管道，
                // 反过来（先 readText 再 waitFor）会一直阻塞到进程结束，超时形同虚设。
                val finished = p.waitFor(budget, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (!finished) {
                    p.destroy()
                    return@runCatching false
                }
                p.inputStream.bufferedReader().readText().contains("uid=0")
            }.getOrDefault(false)
            if (ok) {
                Log.i(TAG, "root probe ok via $cmd")
                activeProbeOk = true
                return true
            }
        }
        Log.i(TAG, "root probe failed; tried ${candidates.size} candidate(s)")
        activeProbeOk = false
        return false
    }

    // ---- Shizuku 真实集成（m1.25）----
    // 官方 API（dev.rikka.shizuku:api 13.1.5）：ShizukuProvider 在 Manifest 声明后，
    // Shizuku.bindProvider 会在进程启动时完成 binder 握手；此后 pingBinder /
    // checkSelfPermission / requestPermission / newProcess 才可用。
    // requestPermission 才会弹授权对话框（用户报障"不能主动弹窗"的根因就是缺这套）。

    /** Shizuku server 是否在运行（用户已通过无线调试/Root 启动过 server） */
    fun shizukuServerRunning(): Boolean = runCatching {
        rikka.shizuku.Shizuku.pingBinder()
    }.getOrDefault(false)

    /** 本 app 是否已获得 Shizuku 授权 */
    fun shizukuGranted(): Boolean = runCatching {
        rikka.shizuku.Shizuku.checkSelfPermission() ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Shizuku 总可用 = server 在跑 且 本 app 已授权 */
    fun shizukuUsable(): Boolean = shizukuServerRunning() && shizukuGranted()

    /**
     * 请求 Shizuku 授权（弹 Shizuku 的授权对话框）。
     * 调用方需先 Shizuku.addRequestPermissionResultListener 接收结果（见 Onboarding）。
     */
    fun requestShizukuPermission(requestCode: Int) {
        rikka.shizuku.Shizuku.requestPermission(requestCode)
    }

    /**
     * 以 Shizuku（adb uid 2000）身份执行 shell 命令——Shizuku 模式下 AI 的执行通道。
     * 官方链路：Shizuku.getBinder() → IShizukuService.newProcess(cmd, env, dir) →
     * IRemoteProcess 的 ParcelFileDescriptor 读输出（aidl 依赖提供接口）。
     * @return stdout+stderr 合并输出
     */
    fun shizukuExec(cmd: String): String {
        val svc = moe.shizuku.server.IShizukuService.Stub.asInterface(
            rikka.shizuku.Shizuku.getBinder()
        ) ?: throw IllegalStateException("Shizuku binder 不可用（server 未运行或未授权）")
        val rp = svc.newProcess(
            arrayOf("sh", "-c", cmd),
            arrayOf("PATH=/system/bin:/system/xbin"),
            null,
        )
        val out = rp.inputStream.let { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText()
        }
        val err = rp.errorStream.let { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText()
        }
        rp.waitFor()
        return out + err
    }

    /**
     * 已入 Root 模式时，对用户资产 dsh-home 做一次轻量备份（tar 到 filesDir/root-backup）。
     * 保护壳：即便 Root 引擎误改 dsh-home，用户仍可回滚。
     * @return 生成的备份文件绝对路径；失败返回 null
     */
    fun backupHome(ctx: Context): String? = runCatching {
        val home = EngineConfig.dshHome(ctx)
        if (!home.exists() || home.listFiles()?.isEmpty() == true) return null
        val outDir = java.io.File(ctx.filesDir, "root-backup").apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val out = java.io.File(outDir, "dsh-home.$stamp.tar")
        // 用系统 tar 打包（root 模式下 tar 也在），失败留空返回
        val env = listOf(
            "PATH=${EngineConfig.engineRoot(ctx).absolutePath}/bin:/system/bin:/system/xbin",
            "TMPDIR=${EngineConfig.tmpDir(ctx).absolutePath}",
        )
        val pb = ProcessBuilder(
            listOf("/system/bin/tar", "-cf", out.absolutePath, "-C", home.absolutePath, ".")
        ).also { pb ->
            val e = pb.environment()
            env.forEach { kv -> val idx = kv.indexOf('='); if (idx > 0) e[kv.substring(0, idx)] = kv.substring(idx + 1) }
        }
        pb.start().waitFor()
        out.takeIf { it.isFile && it.length() > 0 }?.absolutePath
        .also {
            // 滚动保留最近 2 份（v1.2.22 事故：无清理 → 204 份 × 295MB = 57.6GB 堆爆存储；
            // 本意「一次轻量备份」，实际每次切 Root 都全量重拍）
            outDir.listFiles()
                ?.filter { it.name.startsWith("dsh-home.") && it.name.endsWith(".tar") }
                ?.sortedBy { it.name }
                ?.dropLast(2)
                ?.forEach { it.delete() }
        }
    }.onFailure { Log.w(TAG, "backupHome failed: ${it.message}") }.getOrNull()

    /** 探测到的 su 可执行路径（首个存在的），供 Root 模式启动引擎用 */
    fun findSu(): String? = SU_PATHS.firstOrNull { java.io.File(it).exists() }

    /**
     * 自愈：把 dsh-home 递归 chown 回 app uid（Root 整体提权会new进程把 dsh-home 里新建/写过的
     * 文件变成 root 属主，之后任何非 Root 启动都会 EACCES 读不了——m1.26 引擎崩溃根因）。
     * 仅当设备有 su 时执行（su -c chown），否则跳过；失败不报错仅告警。
     * @return 是否需要 chown（即有文件非 app uid 属主）
     */
    fun dshHomeNeedsOwnershipFix(ctx: Context): Boolean {
        val home = EngineConfig.dshHome(ctx)
        return runCatching {
            val su = findSu() ?: return false
            val pb = ProcessBuilder(
                "su", "-c",
                "find " + shellQuote(home.absolutePath) + " -not -user " + android.os.Process.myUid() + " -print -quit"
            ).redirectErrorStream(true)
            val p = pb.start()
            val done = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            if (!done) { p.destroy(); return false }
            val out = p.inputStream.bufferedReader().readText()
            out.isNotBlank()
        }.getOrDefault(false)
    }

    /** 执行 chown（Root 模式下自愈），成功返回 true */
    fun fixHomeOwnership(ctx: Context): Boolean {
        val home = EngineConfig.dshHome(ctx)
        return runCatching {
            val su = findSu() ?: return false
            val uid = android.os.Process.myUid()
            val pb = ProcessBuilder(
                "su", "-c",
                "chown -R $uid:$uid " + shellQuote(home.absolutePath)
            ).redirectErrorStream(true)
            val p = pb.start()
            val done = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            if (!done) { p.destroy(); return false }
            p.inputStream.bufferedReader().readText()
            Log.i(TAG, "fixHomeOwnership: chown dsh-home to uid $uid")
            true
        }.getOrDefault(false)
    }

    /** POSIX sh 单引号转义（供 su -c 拼接） */
    private fun shellQuote(s: String): String {
        if (!s.contains(Regex("[\\s\"'\\\\]"))) return "'$s'"
        return "'" + s.replace("'", "'\\''") + "'"
    }
}
