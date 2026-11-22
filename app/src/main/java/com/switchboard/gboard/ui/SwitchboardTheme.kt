package com.switchboard.gboard.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Material 3 主题：Android 12+ 取动态取色（壁纸配色），低版本回退 Switchboard 蓝。
 */
@Composable
fun SwitchboardTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        val primary = Color(0xFF1A73E8)
        if (dark) {
            darkColorScheme(primary = primary, background = Color(0xFF101418))
        } else {
            lightColorScheme(primary = primary, background = Color(0xFFFAF9FD))
        }
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
