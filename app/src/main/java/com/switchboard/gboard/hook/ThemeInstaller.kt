package com.switchboard.gboard.hook

import com.switchboard.gboard.Config
import com.switchboard.gboard.util.MLog

/**
 * 恢复 Gboard 原厂主题（移植自 m4mental/gboard-RGB-studio ThemeInstaller，需 root）。
 * 删除 Gboard 偏好中的 additional_keyboard_theme（带 .bak 备份）后强停重载。
 */
object ThemeInstaller {
    const val GROUP = "Theme"

    /** 返回 null = 成功，否则为错误信息 */
    fun restoreDefaultTheme(): String? {
        val prefs = "/data/user_de/0/${Config.GBOARD_PACKAGE}/shared_prefs/${Config.GBOARD_PACKAGE}_preferences.xml"
        val backup = "$prefs.switchboard_bak"
        val commands = listOf(
            "su -c 'test -f $prefs || echo __NO_PREFS__'",
            "su -c 'cp -f $prefs $backup'",
            "su -c \"sed -i '/name=\\\"additional_keyboard_theme\\\"/d' $prefs\"",
            "su -c 'chmod 660 $prefs'",
            "su -c \"chown \$(stat -c '%u:%g' $backup) $prefs\"",
            "su -c 'restorecon $prefs'",
            "su -c 'am force-stop ${Config.GBOARD_PACKAGE}'",
        )
        return try {
            for ((i, cmd) in commands.withIndex()) {
                val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                val out = p.inputStream.bufferedReader().readText()
                val err = p.errorStream.bufferedReader().readText()
                p.waitFor()
                if (i == 0 && out.contains("__NO_PREFS__")) {
                    return "未找到 Gboard 偏好文件（路径可能不同）"
                }
                if (p.exitValue() != 0 && i != 0) {
                    MLog.w(GROUP, "step $i failed: $err")
                }
            }
            MLog.i(GROUP, "theme restored, backup at $backup")
            null
        } catch (t: Throwable) {
            "恢复失败: ${t.message}"
        }
    }
}
