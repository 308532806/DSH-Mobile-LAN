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
 * ## 两个真机踩过的坑（都在下面留了注释）
 *
 * 1. **不能用 `AssetManager.list()` 递归**：实测它在目录形态下返回空数组，
 *    会让安装器误判"本构建未内置该插件"而静默跳过。改为按构建期生成的
 *    FILELIST.txt 逐个 `assets.open()`。
 * 2. **profile 目录可能是 root 属主**：引擎以 Root 模式运行时，它在 dsh-home 下
 *    写的文件属主是 root、App（普通 uid）**没有写权限** → 直接复制会全部失败。
 *    因此先暂存到 App 自己的目录，再按情况直接复制或用 `su -c` 兜底。
 *
 * ## 成败判定
 *
 * **以最终产物为准**（入口文件真的落地了才算成功），不是"循环跑完就算成功"——
 * 之前的版本把复制失败也计入成功数，日志显示"装了 13 个文件"而目录根本不存在，
 * 白排查了一轮。
 *
 * ## 许可
 *
 * dsh-prompt-polish 是 AGPL-3.0、dsh-web-mobile 是 MIT（本项目 MIT）。这里只做
 * "原样复制"，不修改其源码，包内 LICENSE 原样保留。
 */
object PluginInstaller {

    private const val TAG = "PluginInstaller"

    /** assets 下的目录名 -> profile node_modules 里的包名（两者不同，别混） */
    private val PLUGINS = mapOf(
        "dsh-prompt-polish" to "@benrong/dsh-prompt-polish",
        // 移动端适配（设置等弹窗底部 sheet 化）——实测 3.0.4 与宿主 0.2.0-rc.2 配合正常
        "dsh-web-mobile" to "dsh-web-mobile",
    )

    private const val ASSET_ROOT = "plugins"

    private fun profileDir(ctx: Context): File =
        File(EngineConfig.dshHome(ctx), "profiles/web")

    fun nodeModulesDir(ctx: Context): File = File(profileDir(ctx), "node_modules")

    /** 插件是否已就位（供 overlay 决定要不要插那一行） */
    fun isInstalled(ctx: Context, assetName: String): Boolean {
        val pkg = PLUGINS[assetName] ?: return false
        val dest = File(nodeModulesDir(ctx), pkg)
        return File(dest, "lib/client.js").isFile && File(dest, "package.json").isFile
    }

    /** 铺好全部内置插件（幂等；在引擎启动前调用）。@return 本次实际安装的插件数 */
    fun ensure(ctx: Context): Int {
        var changed = 0
        for ((assetName, pkgName) in PLUGINS) {
            val ok = runCatching { installOne(ctx, assetName, pkgName) }
                .onFailure { Log.w(TAG, "install $assetName failed: ${it.message}") }
                .getOrDefault(false)
            if (ok) changed++
        }
        return changed
    }

    private fun installOne(ctx: Context, assetName: String, pkgName: String): Boolean {
        val assetPath = "$ASSET_ROOT/$assetName"
        val listText = readAssetText(ctx, "$assetPath/FILELIST.txt")
        if (listText.isNullOrBlank()) {
            Log.i(TAG, "$assetName not bundled in this build; skipped")
            trace(ctx, "$assetName: no FILELIST.txt -> skipped")
            return false
        }
        val entries = listText.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !it.contains("..") && !it.startsWith("/") }
            .toList()
        if (entries.isEmpty()) {
            trace(ctx, "$assetName: empty FILELIST -> skipped")
            return false
        }

        val assetVersion = readAssetText(ctx, "$assetPath/package.json")
            ?.let { Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
        val dest = File(nodeModulesDir(ctx), pkgName)

        if (assetVersion != null && assetVersion == installedVersion(dest) &&
            File(dest, "lib/client.js").isFile
        ) {
            trace(ctx, "$assetName: already at $assetVersion -> no-op")
            return false
        }

        // 1) 先把文件从 assets 落到 **App 自己的目录**（一定可写）
        val staged = File(ctx.filesDir, "bundled-plugins/$assetName")
        if (staged.exists()) staged.deleteRecursively()
        staged.mkdirs()
        var stagedCount = 0
        for (rel in entries) {
            val out = File(staged, rel)
            out.parentFile?.mkdirs()
            val ok = runCatching {
                ctx.assets.open("$assetPath/$rel").use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
            }.isSuccess
            if (ok) stagedCount++
        }
        if (stagedCount != entries.size) {
            trace(ctx, "$assetName: staged only $stagedCount/${entries.size} file(s) -> abort")
            return false
        }

        // 2) 落地到 profile。直接复制失败很常见：**Root 模式下引擎会把 dsh-home 写成
        //    root 属主，普通 uid 没有写权限**（实测：13 个文件全部复制失败）。
        var mode = "direct"
        var ok = runCatching { replaceTree(staged, dest) }.isSuccess
        if (!ok) {
            val su = Privilege.findSu()
            if (su == null) {
                trace(ctx, "$assetName: direct copy failed and no su available -> abort")
                return false
            }
            mode = "su"
            val uid = android.os.Process.myUid()
            // 复制后必须把属主/权限改回 App：su 是以 root 跑的，复制出来的文件是
            // root:root 0600 —— 引擎（root）读得到，但**App 自己连 stat 都做不了**，
            // isInstalled() 就会误判"没装上"、覆盖层也就不加那一行（实测踩过）。
            val cmd = "rm -rf ${shq(dest.absolutePath)}" +
                " && mkdir -p ${shq(dest.parentFile!!.absolutePath)}" +
                " && cp -r ${shq(staged.absolutePath)} ${shq(dest.absolutePath)}" +
                " && chown -R $uid:$uid ${shq(dest.parentFile!!.absolutePath)}" +
                " && chmod -R u+rwX ${shq(dest.parentFile!!.absolutePath)}"
            ok = runCatching { ProcessBuilder(su, "-c", cmd).start().waitFor() == 0 }
                .getOrDefault(false)
        }

        // 3) **以最终产物为准**判定成败（不是"循环跑完"）
        val landed = isInstalled(ctx, assetName)
        trace(ctx, "$assetName: mode=$mode ok=$ok landed=$landed asset=$assetVersion")
        if (!landed) {
            Log.w(TAG, "install $pkgName did not land (mode=$mode)")
            return false
        }
        Log.i(TAG, "installed $pkgName ($mode, ${entries.size} files, asset=$assetVersion)")
        return true
    }

    private fun installedVersion(dest: File): String? =
        File(dest, "package.json").takeIf { it.isFile }?.let { f ->
            runCatching {
                Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(f.readText())?.groupValues?.get(1)
            }.getOrNull()
        }

    /** 原子替换目录：先复制到临时目录，再改名换入（避免留下半份包） */
    private fun replaceTree(src: File, dest: File) {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        if (tmp.exists()) tmp.deleteRecursively()
        src.copyRecursively(tmp, overwrite = true)
        if (dest.exists()) dest.deleteRecursively()
        dest.parentFile?.mkdirs()
        if (!tmp.renameTo(dest)) {
            tmp.copyRecursively(dest, overwrite = true)
            tmp.deleteRecursively()
        }
    }

    /** POSIX sh 单引号转义 */
    private fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun readAssetText(ctx: Context, assetPath: String): String? = runCatching {
        ctx.assets.open(assetPath).bufferedReader().use { it.readText() }
    }.getOrNull()

    /**
     * 落盘一行诊断（engine/plugin-install.log）。
     *
     * 为什么不只靠 Log.i：真机排查时 logcat 缓冲早已被冲掉（这台设备上 Minis 自身
     * 日志量很大），结果"安装器到底跑没跑、为什么没装上"完全无从判断。
     */
    private fun trace(ctx: Context, msg: String) {
        runCatching {
            val f = File(EngineConfig.engineRoot(ctx), "plugin-install.log")
            if (f.length() > 64 * 1024) f.writeText("")
            f.appendText("${System.currentTimeMillis()} $msg\n")
        }
    }
}
