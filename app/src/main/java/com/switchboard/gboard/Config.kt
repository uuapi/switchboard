package com.switchboard.gboard

import android.content.Context
import android.content.SharedPreferences
import com.switchboard.gboard.hook.AppHooks
import io.github.libxposed.api.XposedModule
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hook 侧配置中心。
 *
 * 主数据源是 libxposed 新 API 的 remote preferences（group = "switchboard"）：
 * 模块 App 通过 XposedServiceHelper 写入，Gboard 进程内只读。
 *
 * 本地缓存兜底：Vector 2.2 的 remote preferences 是内存态（探针实验确认无落盘），
 * 设备重启后 daemon 丢失全部键、所有开关回默认。因此这里把最近读到的每个键
 * 镜像到 Gboard 私有目录的缓存文件；remote prefs 缺键（重启后）时自动回退缓存，
 * 用户无感知。用户在 App 中改任意开关都会重新覆盖缓存。
 */
object Config {
    const val PREF_GROUP = "switchboard"

    // Gboard 包名（scope 唯一目标）
    const val GBOARD_PACKAGE = "com.google.android.inputmethod.latin"

    @Volatile
    private var prefs: SharedPreferences? = null

    private val mem = ConcurrentHashMap<String, String>()
    private val flushing = AtomicBoolean(false)
    @Volatile
    private var cacheFile: File? = null

    fun bind(module: XposedModule) {
        synchronized(this) {
            prefs = module.getRemotePreferences(PREF_GROUP)
        }
        AppHooks.onContextReady { ctx -> initCache(ctx) }
    }

    private fun initCache(ctx: Context) {
        try {
            val f = File(ctx.filesDir, "switchboard_config.cache")
            cacheFile = f
            if (f.exists()) {
                f.readLines().forEach { line ->
                    val i = line.indexOf('|')
                    if (i > 0) mem[line.substring(0, i)] = line.substring(i + 1)
                }
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * daemon 是否持有真实配置：App 每次 write 都会打 pref_sync_time 戳，
     * daemon 重启丢失后该键归零（getLong 缺省 0），此时必须走缓存而不是
     * 信任 remote prefs——Vector 的 contains() 对未设置键也会返回 true，
     * 直接读会把默认值污染进缓存。
     */
    @Volatile
    private var daemonSyncAt = 0L
    @Volatile
    private var daemonCheckAt = 0L

    private fun daemonHasData(): Boolean {
        val now = System.currentTimeMillis()
        if (now - daemonCheckAt < 2000) return daemonSyncAt > 0
        daemonCheckAt = now
        daemonSyncAt = try {
            prefs?.getLong("pref_sync_time", 0L) ?: 0L
        } catch (_: Throwable) {
            0L
        }
        return daemonSyncAt > 0
    }

    private fun remember(key: String, kind: Char, v: Any) {
        val s = when (kind) {
            'b' -> if (v as Boolean) "1" else "0"
            'i', 'l' -> v.toString()
            else -> v as String
        }
        if (mem.put(key, s) == s) return // 无变化，不落盘
        val f = cacheFile ?: return
        if (!flushing.compareAndSet(false, true)) return
        Thread {
            try {
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.bufferedWriter().use { w -> mem.forEach { (k, v) -> w.write("$k|$v\n") } }
                if (!tmp.renameTo(f)) {
                    f.delete()
                    tmp.renameTo(f)
                }
            } catch (_: Throwable) {
            } finally {
                flushing.set(false)
            }
        }.start()
    }

    fun bool(key: String, def: Boolean): Boolean {
        val sp = prefs
        if (sp != null && daemonHasData()) try {
            if (sp.contains(key)) {
                val v = sp.getBoolean(key, def)
                remember(key, 'b', v)
                return v
            }
        } catch (_: Throwable) {
        }
        mem[key]?.let { return it == "1" }
        return def
    }

    fun int(key: String, def: Int): Int {
        val sp = prefs
        if (sp != null && daemonHasData()) try {
            if (sp.contains(key)) {
                val v = sp.getInt(key, def)
                remember(key, 'i', v)
                return v
            }
        } catch (_: Throwable) {
        }
        mem[key]?.let { it.toIntOrNull()?.let { v -> return v } }
        return def
    }

    fun long(key: String, def: Long): Long {
        val sp = prefs
        if (sp != null && daemonHasData()) try {
            if (sp.contains(key)) {
                val v = sp.getLong(key, def)
                remember(key, 'l', v)
                return v
            }
        } catch (_: Throwable) {
        }
        mem[key]?.let { it.toLongOrNull()?.let { v -> return v } }
        return def
    }

    fun str(key: String, def: String): String {
        val sp = prefs
        if (sp != null && daemonHasData()) try {
            if (sp.contains(key)) {
                val v = sp.getString(key, def) ?: def
                remember(key, 's', v)
                return v
            }
        } catch (_: Throwable) {
        }
        mem[key]?.let { return it }
        return def
    }

    // ---------- 功能开关（键名与 App 侧保持一致） ----------
    val flagEngineEnabled get() = bool("flag_engine_enabled", true)
    val clipboardEnabled get() = bool("clipboard_enabled", true)
    val clipboardLimit get() = int("clipboard_limit", 0)          // 0 = 不限制
    val clipboardDays get() = int("clipboard_days", 0)            // 0 = 永久
    val secureClipboardEnabled get() = bool("secure_clipboard_enabled", true)
    val amoledEnabled get() = bool("amoled_enabled", false)
    val emojiAlphaEnabled get() = bool("emoji_alpha_enabled", true)
    val passwordKeyboardEnabled get() = bool("password_keyboard_enabled", false)
    val punctuationEnabled get() = bool("punctuation_enabled", true)
    val toolbarEnabled get() = bool("toolbar_enabled", false)
    val glossEnabled get() = bool("gloss_enabled", false)
    val shuangpinEnabled get() = bool("shuangpin_enabled", false)
    val rgbEnabled get() = bool("rgb_enabled", false)
    val startupToast get() = bool("startup_toast", false)
    val verboseLog get() = bool("verbose_log", false)

    // Flag 引擎预设组
    val flagAi get() = bool("flag_ai", false)
    val flagGrammar get() = bool("flag_grammar", false)
    val flagMultilingual get() = bool("flag_multilingual", false)
    val flagFloating get() = bool("flag_floating", false)
    val flagEmojiKitchen get() = bool("flag_emoji_kitchen", false)
    val flagAccessPoint get() = bool("flag_access_point", false)
    val flagMeteredDownloads get() = bool("flag_metered_downloads", false)
    val flagPrivacy get() = bool("flag_privacy", false)
    val flagRambler get() = bool("flag_rambler", false)
    val flagWritingTools get() = bool("flag_writing_tools", false)
    val flagWritingToolsV2 get() = bool("flag_writing_tools_v2", false)
    val flagAllKeyboards get() = bool("flag_all_keyboards", true)
    val flagRoundedKeys get() = bool("flag_rounded_keys", false)
    val flagWordLearning get() = bool("flag_word_learning", false)
    val flagInlineSuggestions get() = bool("flag_inline_suggestions", false)
    val flagTflite get() = bool("flag_tflite", false)
    val flagClipboardChips get() = bool("flag_clipboard_chips", false)
    val writingBackend get() = str("writing_backend", "astrea") // cloud | aicore | astrea

    // 自定义 flag：JSON 数组 [{name,type,value}]
    val customFlagsJson get() = str("custom_flags_json", "")

    // 译词（纯离线词典，不联网）
    val glossTargetLang get() = str("gloss_target_lang", "zh")
    val glossSize get() = int("gloss_size", 11)

    // 双拼
    val shuangpinSize get() = int("shuangpin_size", 10)
    val shuangpinDynamic get() = bool("shuangpin_dynamic", true)
    val shuangpinInitialsBelow get() = bool("shuangpin_initials_below", false)
    val shuangpinBlankLetters get() = bool("shuangpin_blank_letters", false)
    val shuangpinLetterSize get() = int("shuangpin_letter_size", 0) // 0 = 不改
    val shuangpinSemicolon get() = bool("shuangpin_semicolon", false)
    val emojiSuggestEnabled get() = bool("emoji_suggest_enabled", false)

    // 工具栏扩展
    val shotCaptureEnabled get() = bool("shot_capture_enabled", false)
    val shotToClipboard get() = bool("shot_to_clipboard", false)

    // RGB 扩展
    val rgbEffect get() = str("rgb_effect", "ripple")
    val rgbWaveSpeed get() = int("rgb_wave_speed", 100) // 百分比
    val rgbTrailEnabled get() = bool("rgb_trail_enabled", false)
    val rgbTurboEnabled get() = bool("rgb_turbo_enabled", false)
    val rgbKeyFlowEnabled get() = bool("rgb_key_flow_enabled", false)
    val rgbKeyBorderOnly get() = bool("rgb_key_border_only", false)
    val rgbKeyFlowSpeed get() = int("rgb_key_flow_speed", 100)
    val rgbColorPrimary get() = str("rgb_color_primary", "#00FFF5")
    val rgbColorSecondary get() = str("rgb_color_secondary", "#FF00AA")

    // 译词扩展
    val glossBanner get() = bool("gloss_banner", false)
}
