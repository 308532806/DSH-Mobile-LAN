package app.dsh.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.EngineConfig
import app.dsh.mobile.engine.ExtensionManager
import app.dsh.mobile.engine.LanGateway
import app.dsh.mobile.engine.PrivMode
import app.dsh.mobile.engine.Privilege
import app.dsh.mobile.engine.TtsManager

/**
 * 独立设置页（MIUI 分组卡片风格，替代原先的悬浮菜单）。
 *
 * 三个分组：
 *  - 显示：横屏模式、页面缩放
 *  - 权限中心（统一管理运行权限与无障碍）：运行权限模式、Root 能力状态、Shizuku 状态、屏幕点击（无障碍）
 *  - 其他：重启引擎、关于
 *
 * 权限切换与缩放均复用 MainActivity 的既有决策（Root 双警告、缩放 −/＋ 步进、
 * 无障碍跳系统设置），落库到同一组 SharedPreferences，MainActivity 在 onResume 重新读取生效。
 */
class SettingsActivity : Activity() {

    private var landscape = false
    private var pageScale = DEFAULT_PAGE_SCALE

    /** 当前打开的权限选择对话框（选完/切换中转时关闭） */
    private var privPickDialog: AlertDialog? = null

    /** Shizuku 授权结果监听（requestPermission 异步回调后刷新状态行） */
    private val shizukuPermListener =
        rikka.shizuku.Shizuku.OnRequestPermissionResultListener { _, _ ->
            refreshShizuku()
        }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 设置页固定竖屏：即使主界面开了横屏模式，设置页也不跟随旋转
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences(PREFS_UI, MODE_PRIVATE)
        landscape = prefs.getBoolean(KEY_LANDSCAPE, false)
        pageScale = prefs.getInt(KEY_PAGE_SCALE, DEFAULT_PAGE_SCALE)

        rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermListener)

        // —— 顶部返回 ——
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        // —— 显示：横屏模式 ——
        findViewById<LinearLayout>(R.id.rowLandscape).setOnClickListener {
            toggleLandscape()
        }

        // —— 显示：页面缩放 ——
        findViewById<LinearLayout>(R.id.rowScale).setOnClickListener {
            showScaleDialog()
        }

        // —— 权限中心：运行权限模式 ——
        findViewById<LinearLayout>(R.id.rowPriv).setOnClickListener {
            showPrivDialog()
        }

        // —— 权限中心：Root 能力（点击真跑一次 su 验证） ——
        // 纯路径检查会有假阴性（su 可能挂在列表外的位置，如 Magisk 的 /product/bin/su），
        // 所以给用户一个"真跑一次"的入口：会触发 Root 管理器的授权框，结果是权威的。
        findViewById<LinearLayout>(R.id.rowRootProbe).setOnClickListener {
            runRootProbe()
        }

        // —— 权限中心：屏幕点击（无障碍） ——
        findViewById<LinearLayout>(R.id.rowAccess).setOnClickListener {
            handleAccessibility()
        }

        // —— 权限中心：所有文件访问（AI 在共享存储建/读写文件） ——
        findViewById<LinearLayout>(R.id.rowStorage).setOnClickListener {
            val intent = if (Build.VERSION.SDK_INT >= 30)
                Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            else Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
            runCatching { startActivity(intent) }
                .onFailure {
                    Toast.makeText(this, getString(R.string.setting_access_open_failed), Toast.LENGTH_SHORT).show()
                }
        }
        refreshStorageRow()

        // —— 权限中心：语音合成（TTS）—— 点击播报测试 + 显示引擎状态
        // speak 含 TTS 引擎绑定等待（最长 20s），必须离开主线程（ANR 防护）
        findViewById<LinearLayout>(R.id.rowTts).setOnClickListener {
            Thread({
                val res = TtsManager.speak(this, getString(R.string.tts_test_text), true)
                runOnUiThread {
                    Toast.makeText(this, res, Toast.LENGTH_LONG).show()
                    refreshTtsRow()
                }
            }, "tts-test").apply { isDaemon = true; start() }
        }
        refreshTtsRow()

        // —— 权限中心：通知权限（Android 13+ 运行时授权，Agent 推送需要） ——
        findViewById<LinearLayout>(R.id.rowNotif).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                )
            }
        }
        refreshNotifRow()

        // —— 权限中心：局域网访问（LAN 模式） ——
        // 关闭状态点击 = 询问是否开启；开启状态点击 = 查看/复制访问地址
        findViewById<LinearLayout>(R.id.rowLan).setOnClickListener {
            if (LanGateway.isEnabled(this)) showLanAddress() else confirmEnableLan()
        }
        refreshLanRow()

        // —— 权限中心：免 token 访问（仅局域网开启时可见） ——
        findViewById<LinearLayout>(R.id.rowLanNoAuth).setOnClickListener {
            confirmToggleNoAuth()
        }
        refreshNoAuthRow()

        // —— 扩展中心 ——
        findViewById<LinearLayout>(R.id.rowExt).setOnClickListener {
            startActivity(Intent(this, ExtensionStoreActivity::class.java))
        }

        // —— 其他：重启引擎 ——
        // restart() 自身立即返回（内部串行 + 先置"启动中"），不需要再套线程；
        // 状态栏/主界面会随 state 流转自动刷新
        findViewById<LinearLayout>(R.id.rowRestart).setOnClickListener {
            (application as DshApp).supervisor.restart()
            Toast.makeText(this, getString(android.R.string.ok), Toast.LENGTH_SHORT).show()
        }

        // —— 其他：语言 Language（当前语言并入 sub 文案，避免右侧文字与说明连续）——
        val langCodes = listOf("system", "zh", "en")
        fun langLabel(code: String): String = when (code) {
            "zh" -> getString(R.string.lang_zh)
            "en" -> getString(R.string.lang_en)
            else -> getString(R.string.lang_system)
        }
        val langSub = findViewById<TextView>(R.id.appLanguageSub)
        fun refreshLangSub() {
            langSub.text = getString(
                R.string.app_language_sub_current,
                langLabel(LocaleHelper.get(this)),
            )
        }
        refreshLangSub()
        findViewById<LinearLayout>(R.id.rowLanguage).setOnClickListener {
            val choices = arrayOf(
                getString(R.string.lang_system),
                getString(R.string.lang_zh),
                getString(R.string.lang_en),
            )
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.app_language))
                .setSingleChoiceItems(choices, langCodes.indexOf(LocaleHelper.get(this))) { dlg, which ->
                    val code = langCodes[which]
                    if (code == LocaleHelper.get(this)) { dlg.dismiss(); return@setSingleChoiceItems }
                    LocaleHelper.set(this, code)
                    dlg.dismiss()
                    // 重启应用：清空任务栈后重新拉起 Launcher，全部界面按新语言重建
                    // （引擎由前台服务持有，不受影响）
                    val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    finishAffinity()
                }
                .showStyled()
        }

        // —— 其他：关于 ——
        findViewById<TextView>(R.id.subAbout).text =
            getString(R.string.setting_about_sub, versionName())
        findViewById<LinearLayout>(R.id.rowAbout).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    /** 从包管理器读取 versionName（AGP 8+ 默认关闭 BuildConfig，避免依赖它） */
    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull() ?: "?"

    override fun onResume() {
        super.onResume()
        // 从系统无障碍设置返回后刷新状态；Shizuku 授权结果回调也会刷新
        refreshAll()
    }

    override fun onDestroy() {
        rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuPermListener)
        super.onDestroy()
    }

    /** 全量刷新：横屏值 + 缩放值 + 权限状态行 + 无障碍 */
    private fun refreshAll() {
        val prefs = getSharedPreferences(PREFS_UI, MODE_PRIVATE)
        landscape = prefs.getBoolean(KEY_LANDSCAPE, false)
        pageScale = prefs.getInt(KEY_PAGE_SCALE, DEFAULT_PAGE_SCALE)

        findViewById<TextView>(R.id.valLandscape).text = getString(
            if (landscape) R.string.setting_orient_landscape else R.string.setting_orient_portrait
        )
        findViewById<TextView>(R.id.valScale).text = "$pageScale%"
        findViewById<TextView>(R.id.valPriv).text = privLabel(Privilege.getMode(this))
        findViewById<TextView>(R.id.valRootStatus).let {
            val rootOk = Privilege.rootAvailable()
            it.text = getString(
                if (rootOk) R.string.setting_root_status_yes else R.string.setting_root_status_no
            )
            it.setTextColor(if (rootOk) 0xFF6EE7B7.toInt() else 0xFF8A94A3.toInt())
            // 未检测到时把副标题换成操作提示 —— 被动检查会漏（su 可能挂在列表外），
            // 用户需要知道这里可以点一下真验证。
            findViewById<TextView>(R.id.subRootStatus).text = getString(
                if (rootOk) R.string.setting_root_sub else R.string.setting_root_sub_tap
            )
        }
        refreshShizuku()
        refreshAccess()
        refreshStorageRow()
        refreshTtsRow()
        refreshNotifRow()
        refreshLanRow()
        refreshNoAuthRow()
        refreshExt()
        // 缩放副标题文案无需变；图标着色按打开时状态由静态 XML 决定
    }

    /** 扩展中心入口：显示已激活扩展数（>0 变绿提示已并入引擎） */
    private fun refreshExt() {
        val count = ExtensionManager(this).activeCount()
        val v = findViewById<TextView>(R.id.valExt)
        v.text = getString(R.string.setting_ext_active_count, count)
        v.setTextColor(if (count > 0) 0xFF6EE7B7.toInt() else 0xFF8A94A3.toInt())
    }

    /** 权限中心：所有文件访问状态（MANAGE_EXTERNAL_STORAGE，Android 11+ 系统设置页授予；
     *  引擎进程与 App 同 uid，授权后引擎即可读写共享存储） */
    private fun refreshStorageRow() {
        val granted = if (Build.VERSION.SDK_INT >= 30)
            android.os.Environment.isExternalStorageManager()
        else checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val v = findViewById<TextView>(R.id.valStorage)
        v.text = getString(if (granted) R.string.storage_granted else R.string.storage_denied)
        v.setTextColor(if (granted) 0xFF6EE7B7.toInt() else 0xFFFFB74D.toInt())
    }

    /** 权限中心：TTS 引擎可用性（列出 TextToSpeechService 提供方，不触发绑定） */
    private fun refreshTtsRow() {
        val n = TtsManager.engineCount(this)
        val v = findViewById<TextView>(R.id.valTts)
        v.text = if (n == 0) getString(R.string.tts_missing) else getString(R.string.tts_engines, n)
        v.setTextColor(if (n > 0) 0xFF6EE7B7.toInt() else 0xFF8A94A3.toInt())
    }

    // ================= Root 主动验证 =================

    /**
     * 真跑一次 su 验证 Root（后台线程，会弹授权框）。
     *
     * 存在意义：被动检测只看几个写死的路径，su 挂在别处就会误判"设备没 root"
     * （真机事故：Magisk 的 su 在 /product/bin/su）。真执行一次是唯一权威的结论。
     * 结果由 Privilege 缓存，成功后再刷新 UI 就会显示"已检测到"。
     */
    private fun runRootProbe() {
        Toast.makeText(this, getString(R.string.setting_root_probing), Toast.LENGTH_SHORT).show()
        Thread({
            val ok = Privilege.runSuCheckRoot()
            runOnUiThread {
                Toast.makeText(
                    this,
                    getString(if (ok) R.string.setting_root_probe_ok else R.string.setting_root_probe_fail),
                    Toast.LENGTH_LONG,
                ).show()
                // 刷新状态行与权限模式对话框的可用性（都读 Privilege.rootAvailable()）
                refreshAll()
            }
        }, "root-probe").apply { isDaemon = true; start() }
    }

    /**
     * 免 token 开关：开启前必须过风险确认（等于撤掉唯一的访问凭据），
     * 关闭则直接生效（恢复更安全的状态不需要劝阻）。
     */
    private fun confirmToggleNoAuth() {
        val on = LanGateway.isNoAuthEnabled(this)
        if (on) {
            applyNoAuthChange(false)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.lan_no_auth_warn_title))
            .setMessage(getString(R.string.lan_no_auth_warn_msg, previewLanAddress()))
            .setNegativeButton(getString(android.R.string.cancel), null)
            .setPositiveButton(getString(R.string.lan_dialog_enable)) { _, _ -> applyNoAuthChange(true) }
            .showStyled()
    }

    private fun applyNoAuthChange(on: Boolean) {
        LanGateway.setNoAuthEnabled(this, on)
        // 认证是引擎启动时装配的（connect 层的 browserAuth），必须重启才生效
        (application as DshApp).supervisor.restart()
        refreshNoAuthRow()
        refreshLanRow()
        Toast.makeText(
            this,
            getString(
                R.string.lan_restart_hint,
                getString(if (on) R.string.lan_no_auth_on else R.string.lan_no_auth_off),
            ),
            Toast.LENGTH_LONG,
        ).show()
    }

    /** 免 token 行：仅在局域网开启时出现；开启时绿字并把干净地址写进副标题 */
    private fun refreshNoAuthRow() {
        val lanOn = LanGateway.isEnabled(this)
        val row = findViewById<LinearLayout>(R.id.rowLanNoAuth)
        row.visibility = if (lanOn) android.view.View.VISIBLE else android.view.View.GONE
        if (!lanOn) return

        val on = LanGateway.isNoAuthEnabled(this)
        val v = findViewById<TextView>(R.id.valLanNoAuth)
        val sub = findViewById<TextView>(R.id.subLanNoAuth)
        v.text = getString(if (on) R.string.lan_no_auth_on else R.string.lan_no_auth_off)
        v.setTextColor(if (on) 0xFF6EE7B7.toInt() else 0xFF8A94A3.toInt())
        val clean = (application as DshApp).supervisor.lanUrl()
        sub.text = if (on && clean != null) clean else getString(R.string.setting_lan_no_auth_sub)
    }

    // ================= 局域网访问（LAN 模式） =================

    /**
     * 开启局域网访问前的风险确认。
     *
     * 开启后引擎监听所有网卡，同一网络里拿到地址的人就能操作这个 Agent
     * （读写文件、执行命令、消耗 API 额度）—— 上游 CLI 硬拒 `--host 0.0.0.0`
     * 正是这个原因。这里把上游的告警语义搬到 UI 上，而不是悄悄放开。
     */
    private fun confirmEnableLan() {
        if (LanGateway.localIpv4() == null) {
            Toast.makeText(this, getString(R.string.lan_no_ip), Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.lan_dialog_title))
            .setMessage(getString(R.string.lan_dialog_msg, previewLanAddress()))
            .setNegativeButton(getString(android.R.string.cancel), null)
            .setPositiveButton(getString(R.string.lan_dialog_enable)) { _, _ -> applyLanChange(true) }
            .showStyled()
    }

    /** 落地开关并重启引擎让监听面生效；重启是异步的，这里只负责告知。 */
    private fun applyLanChange(on: Boolean) {
        LanGateway.setEnabled(this, on)
        (application as DshApp).supervisor.restart()
        refreshLanRow()
        Toast.makeText(
            this,
            getString(
                R.string.lan_restart_hint,
                getString(if (on) R.string.lan_on else R.string.lan_off),
            ),
            Toast.LENGTH_LONG,
        ).show()
    }

    /** 确认框里预先展示将要生效的地址（引擎还没重启，所以是预测值） */
    private fun previewLanAddress(): String {
        val ip = LanGateway.localIpv4() ?: return getString(R.string.lan_pending)
        return "http://$ip:${EngineConfig.DEFAULT_PORT}/"
    }

    /**
     * 局域网状态行。
     * - 关闭：灰字「已关闭」，副标题提示用途
     * - 开启且引擎就绪：绿字「已开启」，副标题直接给可点的完整地址（含 token），
     *   点整行可再次查看/复制地址
     * - 开启但引擎未就绪 / 没网卡：黄字，副标题说明原因
     */
    private fun refreshLanRow() {
        val on = LanGateway.isEnabled(this)
        val v = findViewById<TextView>(R.id.valLan)
        val sub = findViewById<TextView>(R.id.subLan)
        val live = (application as DshApp).supervisor.lanUrl()

        when {
            !on -> {
                v.text = getString(R.string.lan_off)
                v.setTextColor(0xFF8A94A3.toInt())
                sub.text = getString(R.string.setting_lan_sub)
            }
            live != null -> {
                v.text = getString(R.string.lan_on)
                v.setTextColor(0xFF6EE7B7.toInt())
                sub.text = live
            }
            else -> {
                v.text = getString(R.string.lan_no_ip)
                v.setTextColor(0xFFFFB74D.toInt())
                sub.text = getString(R.string.lan_pending)
            }
        }
    }

    /**
     * 已开启时点击状态行 → 弹出完整地址，并提供复制与关闭两个动作。
     * 「关闭」放在这里（而不是只藏在确认框里）是因为：开启后这一行就是用户
     * 唯一会去看的地方，退出通道必须和入口一样好找。
     */
    private fun showLanAddress() {
        val url = (application as DshApp).supervisor.lanUrl()
        if (url == null) {
            Toast.makeText(this, getString(R.string.lan_pending), Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.lan_share_title))
            .setMessage(getString(R.string.lan_share_msg, url))
            .setNeutralButton(
                if (LanGateway.isNoAuthEnabled(this)) getString(R.string.lan_no_auth_disable)
                else getString(R.string.lan_dialog_disable)
            ) { _, _ ->
                if (LanGateway.isNoAuthEnabled(this)) applyNoAuthChange(false) else applyLanChange(false)
            }
            .setNegativeButton(getString(R.string.lan_share_close), null)
            .setPositiveButton(getString(R.string.lan_share_copy)) { _, _ -> copyLanAddress(url) }
            .showStyled()
    }

    private fun copyLanAddress(url: String) {
        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("dsh-lan-url", url))
        Toast.makeText(this, getString(R.string.lan_share_copy) + " ✓", Toast.LENGTH_SHORT).show()
    }

    /** 权限中心：通知权限（Android 13+ 运行时授权；低版本默认持有） */
    private fun refreshNotifRow() {
        val granted = if (Build.VERSION.SDK_INT >= 33)
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        else true
        val v = findViewById<TextView>(R.id.valNotif)
        v.text = getString(if (granted) R.string.notif_granted else R.string.notif_denied)
        v.setTextColor(if (granted) 0xFF6EE7B7.toInt() else 0xFF8A94A3.toInt())
    }

    /** Shizuku 三态刷新（已授权绿 / 等待授权黄 / 未运行灰） */
    private fun refreshShizuku() {
        val v = findViewById<TextView>(R.id.valShizuku)
        when {
            Privilege.shizukuUsable() -> {
                v.text = getString(R.string.setting_shizuku_granted)
                v.setTextColor(0xFF6EE7B7.toInt())
            }
            Privilege.shizukuServerRunning() -> {
                v.text = getString(R.string.setting_shizuku_request)
                v.setTextColor(0xFFFFB74D.toInt())
                Privilege.requestShizukuPermission(SHIZUKU_REQ)
            }
            else -> {
                v.text = getString(R.string.setting_shizuku_absent)
                v.setTextColor(0xFF8A94A3.toInt())
            }
        }
    }

    /** 无障碍状态刷新 */
    private fun refreshAccess() {
        val on = DshAccessibilityService.isEnabled()
        findViewById<TextView>(R.id.subAccess).text = getString(
            if (on) R.string.setting_access_sub_on else R.string.setting_access_sub_off
        )
        findViewById<TextView>(R.id.valAccess).text =
            getString(if (on) R.string.state_on else R.string.state_off)
        findViewById<TextView>(R.id.valAccess).setTextColor(
            if (on) 0xFF6EE7B7.toInt() else 0xFF8A94A3.toInt()
        )
    }

    private fun privLabel(mode: PrivMode): String = when (mode) {
        PrivMode.ROOT -> getString(R.string.priv_root)
        PrivMode.SHIZUKU -> getString(R.string.priv_shizuku)
        PrivMode.NORMAL -> getString(R.string.priv_normal)
    }

    // ================= 显示：横屏 =================

    private fun toggleLandscape() {
        landscape = !landscape
        getSharedPreferences(PREFS_UI, MODE_PRIVATE)
            .edit().putBoolean(KEY_LANDSCAPE, landscape).apply()
        // 这里【不能】设 requestedOrientation：它作用于设置页自身，会把本应锁竖屏的
        // 设置页也转横。朝向由 MainActivity.onResume 检测偏好变化后 applyOrientation()
        // 统一应用（返回主界面才生效）。
        findViewById<TextView>(R.id.valLandscape).text = getString(
            if (landscape) R.string.setting_orient_landscape else R.string.setting_orient_portrait
        )
        Toast.makeText(
            this,
            getString(
                if (landscape) R.string.setting_orient_landscape
                else R.string.setting_orient_portrait
            ) + " · " + getString(R.string.setting_applies_on_return),
            Toast.LENGTH_SHORT,
        ).show()
    }

    // ================= 显示：页面缩放 =================

    /**
     * 页面缩放对话框：SeekBar 滑条连续拖动（范围 50–150，步长 5），实时显示百分比。
     * 落库后 MainActivity onResume 读取并 reload 生效。
     */
    private fun showScaleDialog() {
        val value = TextView(this).apply {
            textSize = 26f
            gravity = Gravity.CENTER
            text = "$pageScale%"
            setPadding(0, dp(8), 0, dp(4))
        }
        val bar = SeekBar(this).apply {
            max = (MAX_PAGE_SCALE - MIN_PAGE_SCALE) / SCALE_STEP   // 索引 0..20 → 50..150 步长5
            progress = (pageScale - MIN_PAGE_SCALE) / SCALE_STEP
            progressTintList = android.content.res.ColorStateList.valueOf(0xFF7DD3FC.toInt())
            thumbTintList = android.content.res.ColorStateList.valueOf(0xFF7DD3FC.toInt())
            setPadding(dp(24), 0, dp(24), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    value.text = "${MIN_PAGE_SCALE + progress * SCALE_STEP}%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
            addView(value)
            addView(bar)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.scale_title))
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                pageScale = MIN_PAGE_SCALE + bar.progress * SCALE_STEP
                getSharedPreferences(PREFS_UI, MODE_PRIVATE)
                    .edit().putInt(KEY_PAGE_SCALE, pageScale).apply()
                findViewById<TextView>(R.id.valScale).text = "$pageScale%"
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showStyled()
    }

    /** dp → px 小工具（对话框内边距用） */
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ================= 权限中心：运行权限模式 =================

    /**
     * 运行权限模式选择：自定义单选列表（MIUI 行式）。
     * 能力未就绪的选项直接置灰（alpha 0.35）且不可点击：
     *  - Shizuku：server 未运行 → 置灰（无从授权）
     *  - Root：未检测到 su → 置灰
     * Root 仍保留双警告；切换后自动重启引擎。
     */
    private fun showPrivDialog() {
        val current = Privilege.getMode(this)
        val shizukuOk = Privilege.shizukuServerRunning()
        val rootOk = Privilege.rootAvailable()

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        fun makeRow(
            label: String, sub: String, mode: PrivMode,
            enabled: Boolean, checked: Boolean,
        ): LinearLayout {
            val radio = android.widget.RadioButton(this).apply {
                isChecked = checked
                isEnabled = enabled
                isClickable = false   // 由整行接管点击
            }
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@SettingsActivity).apply {
                    text = label
                    textSize = 16f
                    setTextColor(0xFFFFFFFF.toInt())
                })
                if (sub.isNotEmpty()) addView(TextView(this@SettingsActivity).apply {
                    text = sub
                    textSize = 12f
                    setTextColor(0xFF8A94A3.toInt())
                })
            }
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                if (enabled) {
                    // 解析主题的 ripple 背景为真实 resId 再取 drawable（attr 不能直接 getDrawable）
                    val tv = android.util.TypedValue()
                    theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                    background = getDrawable(tv.resourceId)
                }
                addView(radio)
                addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(8)
                })
                alpha = if (enabled) 1f else 0.35f
                isEnabled = enabled
                if (enabled) {
                    setOnClickListener {
                        // 互斥：重画全部 radio
                        for (i in 0 until box.childCount) {
                            val row = box.getChildAt(i) as LinearLayout
                            (row.getChildAt(0) as android.widget.RadioButton).isChecked = row === this
                        }
                        when (mode) {
                            PrivMode.ROOT -> warnRootSwitch {
                                dismissPrivPick()
                                applyModeAndRestart(PrivMode.ROOT)
                            }
                            PrivMode.SHIZUKU -> {
                                // server 在跑但未授权 → 先请求授权（弹 Shizuku 框）
                                if (!Privilege.shizukuGranted()) {
                                    Privilege.requestShizukuPermission(SHIZUKU_REQ)
                                }
                                dismissPrivPick()
                                applyModeAndRestart(PrivMode.SHIZUKU)
                            }
                            PrivMode.NORMAL -> {
                                dismissPrivPick()
                                applyModeAndRestart(PrivMode.NORMAL)
                            }
                        }
                    }
                }
            }
        }

        box.addView(makeRow(
            getString(R.string.priv_normal), "", PrivMode.NORMAL, true, current == PrivMode.NORMAL))
        box.addView(makeRow(
            getString(R.string.priv_shizuku),
            if (shizukuOk) "" else getString(R.string.setting_shizuku_absent),
            PrivMode.SHIZUKU, shizukuOk, current == PrivMode.SHIZUKU))
        box.addView(makeRow(
            getString(R.string.priv_root),
            if (rootOk) "" else getString(R.string.setting_root_status_no),
            PrivMode.ROOT, rootOk, current == PrivMode.ROOT))

        privPickDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.priv_pick_title))
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        privPickDialog?.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
        privPickDialog?.show()
    }

    /** 关闭权限选择对话框（选完/警告链中转用；null 安全） */
    private fun dismissPrivPick() {
        privPickDialog?.dismiss()
        privPickDialog = null
    }

    /** 切换运行权限模式并自动重启引擎（模式未变则不重启） */
    private fun applyModeAndRestart(target: PrivMode) {
        if (Privilege.getMode(this) == target) return
        Privilege.setMode(this, target)
        findViewById<TextView>(R.id.valPriv).text = privLabel(target)
        (application as DshApp).supervisor.restart()
        Toast.makeText(this, getString(R.string.setting_priv_mode), Toast.LENGTH_SHORT).show()
    }

    private fun warnRootSwitch(next: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ob_priv_root_warn_title))
            .setMessage(getString(R.string.ob_priv_root_warn))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.ob_dialog_continue)) { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.ob_priv_root_warn2_title))
                    .setMessage(getString(R.string.ob_priv_root_warn2))
                    .setCancelable(false)
                    .setPositiveButton(getString(R.string.ob_dialog_continue)) { _, _ -> next() }
                    .setNegativeButton(getString(R.string.ob_dialog_cancel)) { _, _ -> }
                    .showStyled()
            }
            .setNegativeButton(getString(R.string.ob_dialog_cancel)) { _, _ -> }
            .showStyled()
    }

    // ================= 权限中心：无障碍 =================

    /** 无障碍：未开启则跳系统设置引导开启；已开启则提示已可用 */
    private fun handleAccessibility() {
        if (DshAccessibilityService.isEnabled()) {
            Toast.makeText(
                this, getString(R.string.access_enabled_toast),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, getString(R.string.setting_access_open_failed), Toast.LENGTH_SHORT).show() }
    }

    /** 统一弹窗样式：覆盖自绘圆角背景，贴近原生安卓对话框的圆润观感 */
    private fun AlertDialog.Builder.showStyled(): AlertDialog =
        create().apply {
            setOnShowListener {
                window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
            }
            show()
        }

    private companion object {
        const val SHIZUKU_REQ = 4202
        const val PREFS_UI = "dsh_ui"
        const val KEY_PAGE_SCALE = "page_scale"
        const val KEY_LANDSCAPE = "landscape"
        const val DEFAULT_PAGE_SCALE = 90
        const val MIN_PAGE_SCALE = 50
        const val MAX_PAGE_SCALE = 150
        const val SCALE_STEP = 5
    }
}
