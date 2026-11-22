package com.switchboard.gboard.util

import android.util.Log
import com.switchboard.gboard.Config
import com.switchboard.gboard.SwitchboardModule

/**
 * 双通道日志：android.util.Log（logcat -s Switchboard 可见）+ libxposed log()（LSPosed 管理器日志）。
 */
object MLog {
    private const val TAG = "Switchboard"

    fun init(module: SwitchboardModule) {
        host = module
    }

    @Volatile
    private var host: SwitchboardModule? = null

    fun i(group: String, msg: String) {
        Log.i(TAG, "[$group] $msg")
        runCatching { host?.log(Log.INFO, TAG, "[$group] $msg") }
    }

    fun w(group: String, msg: String, t: Throwable? = null) {
        Log.w(TAG, "[$group] $msg", t)
        runCatching {
            if (t != null) host?.log(Log.WARN, TAG, "[$group] $msg", t)
            else host?.log(Log.WARN, TAG, "[$group] $msg")
        }
    }

    fun e(group: String, msg: String, t: Throwable? = null) {
        Log.e(TAG, "[$group] $msg", t)
        runCatching { host?.log(Log.ERROR, TAG, "[$group] $msg", t) }
    }

    /** verbose_log 开启时才输出 */
    fun d(group: String, msg: String) {
        if (Config.verboseLog) i(group, msg)
    }
}
