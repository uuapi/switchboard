package com.switchboard.gboard.ui

import androidx.annotation.StringRes
import com.switchboard.gboard.R

/**
 * 声明式设置项模型：一份列表同时驱动渲染、搜索、导出/导入。
 */
sealed interface Item {
    val key: String
    @get:StringRes val title: Int
    @get:StringRes val desc: Int

    data class Switch(
        override val key: String,
        @StringRes override val title: Int,
        @StringRes override val desc: Int = 0,
        val def: Boolean = false,
    ) : Item

    data class Num(
        override val key: String,
        @StringRes override val title: Int,
        @StringRes override val desc: Int = 0,
        val def: Int = 0,
    ) : Item

    data class Text(
        override val key: String,
        @StringRes override val title: Int,
        @StringRes override val desc: Int = 0,
        val def: String = "",
        val multiline: Boolean = false,
    ) : Item

    data class Choice(
        override val key: String,
        @StringRes override val title: Int,
        val options: List<Pair<String, Int>>,
        val def: String,
        @StringRes override val desc: Int = 0,
    ) : Item
}

data class Section(@StringRes val title: Int, val items: List<Item>)

object SettingsModel {

    val sections: List<Section> = listOf(
        Section(R.string.sec_general, listOf(
            Item.Switch("verbose_log", R.string.verbose_log_t, R.string.verbose_log_d),
            Item.Switch("startup_toast", R.string.startup_toast_t, R.string.startup_toast_d),
        )),
        Section(R.string.sec_clipboard, listOf(
            Item.Switch("clipboard_enabled", R.string.clipboard_enabled_t, R.string.clipboard_enabled_d, true),
            Item.Num("clipboard_limit", R.string.clipboard_limit_t, R.string.clipboard_limit_d),
            Item.Num("clipboard_days", R.string.clipboard_days_t, R.string.clipboard_days_d),
            Item.Switch("secure_clipboard_enabled", R.string.secure_clipboard_t, R.string.secure_clipboard_d, true),
        )),
        Section(R.string.sec_flags, listOf(
            Item.Switch("flag_engine_enabled", R.string.flag_engine_t, R.string.flag_engine_d, true),
            Item.Switch("flag_ai", R.string.flag_ai_t, R.string.flag_ai_d),
            Item.Switch("flag_grammar", R.string.flag_grammar_t, R.string.flag_grammar_d),
            Item.Switch("flag_writing_tools", R.string.flag_writing_tools_t, R.string.flag_writing_tools_d),
            Item.Switch("flag_writing_tools_v2", R.string.flag_writing_tools_v2_t, R.string.flag_writing_tools_v2_d, true),
            Item.Switch("flag_all_keyboards", R.string.flag_all_keyboards_t, R.string.flag_all_keyboards_d, true),
            Item.Choice("writing_backend", R.string.writing_backend_t, listOf(
                "cloud" to R.string.backend_cloud,
                "aicore" to R.string.backend_aicore,
                "astrea" to R.string.backend_astrea,
            ), "astrea"),
            Item.Switch("flag_multilingual", R.string.flag_multilingual_t, R.string.flag_multilingual_d),
            Item.Switch("flag_floating", R.string.flag_floating_t, R.string.flag_floating_d),
            Item.Switch("flag_emoji_kitchen", R.string.flag_emoji_kitchen_t, R.string.flag_emoji_kitchen_d),
            Item.Switch("flag_access_point", R.string.flag_access_point_t, R.string.flag_access_point_d),
            Item.Switch("flag_metered_downloads", R.string.flag_metered_t, R.string.flag_metered_d),
            Item.Switch("flag_privacy", R.string.flag_privacy_t, R.string.flag_privacy_d),
            Item.Switch("flag_rambler", R.string.flag_rambler_t, R.string.flag_rambler_d),
            Item.Switch("flag_rounded_keys", R.string.flag_rounded_t, R.string.flag_rounded_d),
            Item.Switch("flag_word_learning", R.string.flag_word_learning_t, R.string.flag_word_learning_d),
            Item.Switch("flag_inline_suggestions", R.string.flag_inline_t, R.string.flag_inline_d),
            Item.Switch("flag_tflite", R.string.flag_tflite_t, R.string.flag_tflite_d),
            Item.Switch("flag_clipboard_chips", R.string.flag_chips_t, R.string.flag_chips_d),
            Item.Text("custom_flags_json", R.string.custom_flags_t, R.string.custom_flags_d, "", multiline = true),
        )),
        Section(R.string.sec_appearance, listOf(
            Item.Switch("amoled_enabled", R.string.amoled_t, R.string.amoled_d),
            Item.Switch("emoji_alpha_enabled", R.string.emoji_alpha_t, R.string.emoji_alpha_d, true),
        )),
        Section(R.string.sec_input, listOf(
            Item.Switch("password_keyboard_enabled", R.string.password_keyboard_t, R.string.password_keyboard_d),
            Item.Switch("punctuation_enabled", R.string.punctuation_t, R.string.punctuation_d, true),
        )),
        Section(R.string.sec_toolbar, listOf(
            Item.Switch("toolbar_enabled", R.string.toolbar_t, R.string.toolbar_d),
            Item.Text("toolbar_text_1", R.string.toolbar_text1_t, R.string.toolbar_text1_d),
            Item.Text("toolbar_text_2", R.string.toolbar_text2_t, R.string.toolbar_text2_d),
            Item.Switch("shot_capture_enabled", R.string.shot_capture_t, R.string.shot_capture_d),
            Item.Switch("shot_to_clipboard", R.string.shot_to_clipboard_t, R.string.shot_to_clipboard_d),
        )),
        Section(R.string.sec_gloss, listOf(
            Item.Switch("gloss_enabled", R.string.gloss_t, R.string.gloss_d),
            Item.Text("gloss_target_lang", R.string.gloss_target_t, R.string.gloss_target_d, "zh"),
            Item.Num("gloss_size", R.string.gloss_size_t, R.string.gloss_size_d, 11),
            Item.Switch("gloss_banner", R.string.gloss_banner_t, R.string.gloss_banner_d),
        )),
        Section(R.string.sec_shuangpin, listOf(
            Item.Switch("shuangpin_enabled", R.string.shuangpin_t, R.string.shuangpin_d),
            Item.Num("shuangpin_size", R.string.shuangpin_size_t, R.string.shuangpin_size_d, 10),
            Item.Switch("shuangpin_dynamic", R.string.shuangpin_dynamic_t, R.string.shuangpin_dynamic_d, true),
            Item.Switch("shuangpin_blank_letters", R.string.shuangpin_blank_t, R.string.shuangpin_blank_d),
            Item.Num("shuangpin_letter_size", R.string.shuangpin_letter_size_t, R.string.shuangpin_letter_size_d),
            Item.Switch("shuangpin_semicolon", R.string.shuangpin_semicolon_t, R.string.shuangpin_semicolon_d),
            Item.Switch("emoji_suggest_enabled", R.string.emoji_suggest_t, R.string.emoji_suggest_d),
        )),
        Section(R.string.sec_misc, listOf(
            Item.Switch("rgb_enabled", R.string.rgb_t, R.string.rgb_d),
            Item.Choice("rgb_effect", R.string.rgb_effect_t, listOf(
                "ripple" to R.string.rgb_effect_ripple,
                "water" to R.string.rgb_effect_water,
                "neon" to R.string.rgb_effect_neon,
                "supernova" to R.string.rgb_effect_supernova,
                "magma" to R.string.rgb_effect_magma,
                "sonic" to R.string.rgb_effect_sonic,
                "blackhole" to R.string.rgb_effect_blackhole,
                "rain" to R.string.rgb_effect_rain,
                "chroma" to R.string.rgb_effect_chroma,
            ), "ripple"),
            Item.Num("rgb_wave_speed", R.string.rgb_wave_speed_t, R.string.rgb_wave_speed_d, 100),
            Item.Switch("rgb_trail_enabled", R.string.rgb_trail_t, R.string.rgb_trail_d),
            Item.Switch("rgb_turbo_enabled", R.string.rgb_turbo_t, R.string.rgb_turbo_d),
            Item.Switch("rgb_key_flow_enabled", R.string.rgb_key_flow_t, R.string.rgb_key_flow_d),
            Item.Switch("rgb_key_border_only", R.string.rgb_border_t, R.string.rgb_border_d),
            Item.Num("rgb_key_flow_speed", R.string.rgb_key_speed_t, R.string.rgb_key_speed_d, 100),
            Item.Switch("rgb_glow_enabled", R.string.rgb_glow_t, R.string.rgb_glow_d, true),
            Item.Text("rgb_color_primary", R.string.rgb_color1_t, 0, "#00FFF5"),
            Item.Text("rgb_color_secondary", R.string.rgb_color2_t, 0, "#FF00AA"),
            Item.Text("user_glossary", R.string.user_glossary_t, R.string.user_glossary_d, "", multiline = true),
        )),
    )

    val allItems: List<Item> = sections.flatMap { it.items }

    val switchCount: Int = allItems.count { it is Item.Switch }
}
