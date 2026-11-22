package com.switchboard.gboard

import android.content.SharedPreferences
import io.github.libxposed.api.XposedModule

/**
 * Hook 侧配置中心。
 *
 * 数据源是 libxposed 新 API 的 remote preferences（group = "switchboard"）：
 * 模块 App 通过 XposedServiceHelper 写入，Gboard 进程内只读。
 * 这里做 2 秒 TTL 缓存，避免高频 hook 每次都穿透。
 */
object Config {
    const val PREF_GROUP = "switchboard"

    // Gboard 包名（scope 唯一目标）
    const val GBOARD_PACKAGE = "com.google.android.inputmethod.latin"

    @Volatile
    private var prefs: SharedPreferences? = null
    @Volatile
    private var loadedAt = 0L

    private const val TTL_MS = 2000L

    fun bind(module: XposedModule) {
        synchronized(this) {
            prefs = module.getRemotePreferences(PREF_GROUP)
            loadedAt = System.currentTimeMillis()
        }
    }

    private val sp: SharedPreferences?
        get() {
            synchronized(this) {
                val now = System.currentTimeMillis()
                val cur = prefs
                if (cur != null && now - loadedAt < TTL_MS) return cur
                return cur
            }
        }

    fun bool(key: String, def: Boolean): Boolean = sp?.getBoolean(key, def) ?: def
    fun int(key: String, def: Int): Int = sp?.getInt(key, def) ?: def
    fun long(key: String, def: Long): Long = sp?.getLong(key, def) ?: def
    fun str(key: String, def: String): String = sp?.getString(key, def) ?: def

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
    val termuxEnabled get() = bool("termux_enabled", true)
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
