package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 内置插件安装器：把随 APK 打包的 dsh 插件铺进 profile 的 node_modules。
 *
 * ## 为什么要铺到 profile 而不是引擎目录
 *
 * 实测（本地起引擎验证过）：插件放引擎的 `lib/node_modules` 会加载失败
 * （`failed to import`）；**放 profile 自己的 `node_modules` 才行** ——
 * 那正是 dsh 用 pnpm 安装插件的位置，也是 profile 的行解析插件名时的基准目录。
 *
 * ## 为什么不用 pnpm 装
 *
 * 手机端跑 pnpm 要联网、要解析依赖树、还可能撞上 registry 不可达。
 * 内置插件只有几十 KB 且无依赖（宿主端只用 node 内置模块），
 * 直接从 assets 复制是最省事也最确定的做法 —— 装完即用，不需要网络。
 *
 * ## 幂等
 *
 * 以包内 package.json 的 version 作为标记：assets 版本与已装版本一致就跳过，
 * 升级 APK 后自动覆盖。
 *
 * ## 许可
 *
 * dsh-prompt-polish 是 AGPL-3.0（本项目 MIT）。这里只做"原样复制"，
 * 不修改其源码，包内 LICENSE 原样保留；App 的关于页/发布说明中标注了来源与许可。
 */
object PluginInstaller {

    private const val TAG = "PluginInstaller"

    /** assets 下的目录名 -> profile node_modules 里的包名（两者不同，别混） */
    private val PLUGINS = mapOf(
        "dsh-prompt-polish" to "@benrong/dsh-prompt-polish",
    )

    /** assets 里的插件根目录名（CI 会把 npm 包解到这里） */
    private const val ASSET_ROOT = "plugins"

    /** profile 目录（dsh 的 web profile，插件装它的 node_modules 里） */
    private fun profileDir(ctx: Context): File =
        File(EngineConfig.dshHome(ctx), "profiles/web")

    fun nodeModulesDir(ctx: Context): File = File(profileDir(ctx), "node_modules")

    /** 插件是否已就位（供 overlay 决定要不要插那一行） */
    fun isInstalled(ctx: Context, assetName: String): Boolean {
        val pkg = PLUGINS[assetName] ?: return false
        val dest = File(nodeModulesDir(ctx), pkg)
        return File(dest, "lib/client.js").isFile && File(dest, "package.json").isFile
    }

    /**
     * 铺好全部内置插件（幂等；在引擎启动前调用）。
     *
     * @return 本次实际写入/更新的插件数
     */
    fun ensure(ctx: Context): Int {
        var changed = 0
        for ((assetName, pkgName) in PLUGINS) {
            val r = runCatching { installOne(ctx, assetName, pkgName) }
                .onFailure { Log.w(TAG, "install $assetName failed: ${it.message}") }
                .getOrDefault(false)
            if (r) changed++
        }
        if (changed > 0) Log.i(TAG, "installed/updated $changed bundled plugin(s)")
        return changed
    }

    private fun installOne(ctx: Context, assetName: String, pkgName: String): Boolean {
        val assetPath = "$ASSET_ROOT/$assetName"
        // assets 里没有这个插件（例如本地构建未跑 CI 的铺包步骤）→ 跳过，
        // 不要建空目录，否则引擎会去加载一个残包
        // 文件清单由构建期生成（assets 里随包分发）。
        // **刻意不用 AssetManager.list() 递归** —— 真机实测它在目录形态下返回空数组，
        // 会让安装器误判"本构建未内置该插件"而静默跳过（我因此排查了一轮）。
        // 走清单 + assets.open() 逐个复制，与 priv-mode.mjs 用的是同一条已验证的路径。
        val listText = readAssetText(ctx, "$assetPath/FILELIST.txt")
        if (listText.isNullOrBlank()) {
            Log.i(TAG, "$assetName not bundled in this build; skipped")
            trace(ctx, "$assetName: no FILELIST.txt in assets -> skipped")
            return false
        }
        val entries = listText.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !it.contains("..") && !it.startsWith("/") }
            .toList()
        if (entries.isEmpty()) {
            Log.w(TAG, "$assetName has an empty FILELIST; skipped")
            return false
        }

        val assetVersion = readAssetText(ctx, "$assetPath/package.json")
            ?.let { Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
        val dest = File(nodeModulesDir(ctx), pkgName)

        val installedVersion = File(dest, "package.json").takeIf { it.isFile }
            ?.let { f ->
                runCatching {
                    Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(f.readText())?.groupValues?.get(1)
                }.getOrNull()
            }
        // 版本一致且入口文件在位 → 无需重装
        if (assetVersion != null && assetVersion == installedVersion &&
            File(dest, "lib/client.js").isFile
        ) {
            trace(ctx, "$assetName: already at $assetVersion -> no-op")
            return false
        }

        // 覆盖式铺开：先删旧包（避免残留旧文件影响加载）
        if (dest.exists()) dest.deleteRecursively()
        dest.mkdirs()
        var copied = 0
        for (rel in entries) {
            val out = File(dest, rel)
            out.parentFile?.mkdirs()
            runCatching {
                ctx.assets.open("$assetPath/$rel").use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
            }.onFailure { Log.w(TAG, "copy $rel failed: ${it.message}") }
            copied++
        }
        Log.i(TAG, "installed $pkgName: $copied file(s), assetVersion=$assetVersion, was=$installedVersion")
        trace(ctx, "$pkgName: installed $copied file(s) (asset=$assetVersion, was=$installedVersion) -> $dest")
        return true
    }

    private fun readAssetText(ctx: Context, assetPath: String): String? = runCatching {
        ctx.assets.open(assetPath).bufferedReader().use { it.readText() }
    }.getOrNull()

    /**
     * 落盘一行诊断（engine/plugin-install.log）。
     *
     * 为什么不只靠 Log.i：真机排查时 logcat 缓冲早已被冲掉（这台设备上 Minis 自身
     * 日志量很大），结果"安装器到底跑没跑"完全无从判断。写文件后可以直接 cat，
     * 与 engine.log 同一个目录、同样便于用户导出反馈。
     */
    private fun trace(ctx: Context, msg: String) {
        runCatching {
            val f = File(EngineConfig.engineRoot(ctx), "plugin-install.log")
            if (f.length() > 64 * 1024) f.writeText("")
            f.appendText("${System.currentTimeMillis()} $msg\n")
        }
    }
}
