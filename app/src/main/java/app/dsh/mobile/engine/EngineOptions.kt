package app.dsh.mobile.engine

import android.content.Context
import android.util.Log

/**
 * 引擎行为开关（非局域网相关的那类）。
 *
 * 目前只有一项：默认开启思考强度。
 *
 * 背景：dsh 把「思考强度」当作**每个模型自己声明的能力**——
 *   · 官方适配器（deepseek）会给出档位，模型选择器里因此有思考强度可选；
 *   · 自定义 / 手工声明的模型走 pi-ai，而 pi-ai 对"没声明档位"的模型会报成
 *     只有 off 一档，界面干脆不显示这个控件；上游的模型设置页也没有声明
 *     reasoningEfforts 的表单 —— 结果是自定义模型的思考强度选择永远出不来。
 *
 * 打开这个开关后，引擎会给这类模型补一套通行档位（low/medium/high，
 * off = 不发送该参数即不思考），于是**所有模型**都能选思考强度。
 *
 * 为什么默认关闭：对不支持该参数的供应商，选了档位会被对方拒绝
 * （报错可见、改回 off 即恢复），所以这是"改变请求形态"的选择，
 * 应该由用户显式打开，而不是默认替所有人做。
 */
object EngineOptions {

    private const val TAG = "EngineOptions"
    private const val PREFS = "dsh_ui"
    private const val KEY_DEFAULT_REASONING = "default_reasoning"

    /** 是否给所有模型补上思考档位（注入 DSH_DEFAULT_REASONING=1） */
    fun isDefaultReasoning(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEFAULT_REASONING, false)

    fun setDefaultReasoning(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DEFAULT_REASONING, on).apply()
        Log.i(TAG, "default reasoning ${if (on) "enabled" else "disabled"}")
    }
}
