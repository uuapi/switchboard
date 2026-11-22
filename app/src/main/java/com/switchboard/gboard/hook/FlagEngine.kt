package com.switchboard.gboard.hook

import android.content.Context
import com.switchboard.gboard.Config
import com.switchboard.gboard.SwitchboardModule
import com.switchboard.gboard.util.MLog
import io.github.libxposed.api.XposedModule
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Flag 覆写引擎。
 *
 * Gboard 的 Phenotype flag 经由一个混淆的泛型读取器（返回 Object，内部字符串 "Invalid flag: "）。
 * DexKit 定位该读取器后，按 flag 名改写返回值。
 *
 * 预设表外置于 assets/flag_presets.json（group → flag → value），
 * 社区可直接以数据 PR 更新而不改代码；特殊 Long/字符串 flag 与跨功能条件仍在本类处理。
 */
object FlagEngine {
    const val GROUP = "Flag"

    // FuckGboard 已知混淆 flag 类（多版本候选，动态兜底前的最后防线）
    private val KNOWN_FLAG_CLASSES = listOf("adbx", "odx", "obw", "acps", "nxw", "ajoh")

    // 剪贴板限制解除的强制关闭项（跨功能条件，不进 JSON）
    private val CLIPBOARD_FORCED_FALSE = setOf(
        "enable_clipboard_entity_extraction", "enable_clipboard_query_refactoring"
    )

    //FuckGboard 写作工具全语言 tag（字符串值，配合后端三选）
    private val WRITING_ALL_LANGUAGE_TAGS = setOf(
        "writing_helper_supported_language_tags", "llm_pc_supported_language_tags",
        "proofread_supported_apps",
    )

    // flag 名字段缓存
    private val nameFieldCache = ConcurrentHashMap<Class<*>, Field?>()
    @Volatile
    private var customFlagsCache: Pair<String, List<CustomFlag>>? = null

    data class CustomFlag(val name: String, val type: String, val value: String)

    // ---------- 预设加载（assets/flag_presets.json） ----------
    // name → [(groupKey, true|false|"*"), ...]
    @Volatile
    private var presetsCache: Map<String, List<Pair<String, Any>>>? = null

    private fun presets(): Map<String, List<Pair<String, Any>>> {
        presetsCache?.let { return it }
        return synchronized(this) {
            presetsCache ?: run {
                val map = runCatching { loadPresets() }
                    .getOrElse { MLog.e(GROUP, "presets load failed", it); emptyMap() }
                presetsCache = map
                map
            }
        }
    }

    private fun loadPresets(): Map<String, List<Pair<String, Any>>> {
        val zip = SwitchboardModule.moduleApkPath?.let { ZipFile(it) }
            ?: run { MLog.w(GROUP, "module apk unavailable for presets"); return emptyMap() }
        zip.use { z ->
            val entry = z.getEntry("assets/flag_presets.json")
                ?: run { MLog.w(GROUP, "flag_presets.json missing"); return emptyMap() }
            val text = z.getInputStream(entry).bufferedReader().readText()
            val groups: JSONArray = JSONObject(text).getJSONArray("groups")
            val map = HashMap<String, MutableList<Pair<String, Any>>>()
            for (g in 0 until groups.length()) {
                val group = groups.getJSONObject(g)
                val key = group.getString("key")
                val flags: JSONObject = group.getJSONObject("flags")
                for (name in flags.keys()) {
                    val raw = flags.get(name)
                    val value: Any = if (raw is Boolean) raw else raw.toString()
                    map.getOrPut(name) { mutableListOf() }.add(key to value)
                }
            }
            MLog.i(GROUP, "flag presets loaded: ${map.size} flags from ${groups.length()} groups")
            return map
        }
    }

    /** 组开关；writing_v2 依赖 writing_core */
    private fun groupEnabled(key: String): Boolean = when (key) {
        "writing_core" -> Config.flagWritingTools
        "writing_v2" -> Config.flagWritingTools && Config.flagWritingToolsV2
        else -> Config.bool("flag_$key", false)
    }

    fun install(module: XposedModule, cl: ClassLoader) {
        // 预热（首进程才有意义，但读取本身幂等）
        AppHooks.onContextReady { presets() }

        val spContextReady = AppHooks.appContext
        if (spContextReady != null) {
            startHooks(module, cl, spContextReady)
        } else {
            AppHooks.onContextReady { ctx -> startHooks(module, cl, ctx) }
        }
    }

    private fun startHooks(module: XposedModule, cl: ClassLoader, ctx: Context) {
        val sp = ctx.getSharedPreferences("switchboard_cache", Context.MODE_PRIVATE)
        val method = DexKitSupport.cachedMethod(sp, ctx, "flag_method", cl) { b ->
            DexKitSupport.findFlagMethod(b)
        }
        if (method != null) {
            hookGenericReader(module, method)
        } else {
            MLog.w(GROUP, "generic reader not found, trying known obfuscated classes")
            hookKnownClasses(module, cl)
        }
    }

    /** DexKit 找到的泛型读取器：返回 Object，所有 flag 共用 */
    private fun hookGenericReader(module: XposedModule, method: Method) {
        module.hook(method).intercept { chain ->
            val result = chain.proceed()
            val name = flagName(chain.thisObject) ?: return@intercept result
            val override = evaluate(name, result) ?: return@intercept result
            if (typeMatches(result, override)) override else result
        }
        MLog.i(GROUP, "generic flag reader hooked: ${method.declaringClass.name}#${method.name}")
    }

    /** FuckGboard 路线：多版本混淆类候选 + g() getter + 动态扫描兜底 */
    private fun hookKnownClasses(module: XposedModule, cl: ClassLoader) {
        var hooked = false
        for (name in KNOWN_FLAG_CLASSES) {
            val clazz = runCatching { cl.loadClass(name) }.getOrNull() ?: continue
            val g = runCatching { clazz.getDeclaredMethod("g") }.getOrNull() ?: continue
            g.isAccessible = true
            module.hook(g).intercept { chain ->
                val result = chain.proceed()
                val flagName = flagName(chain.thisObject) ?: return@intercept result
                val override = evaluate(flagName, result) ?: return@intercept result
                if (typeMatches(result, override)) override else result
            }
            MLog.i(GROUP, "known flag class hooked: $name")
            hooked = true
            break
        }
        if (!hooked) {
            DexKitSupport.dynamicFlagClass(cl)?.let { dyn ->
                runCatching {
                    val g = dyn.getDeclaredMethod("g")
                    g.isAccessible = true
                    module.hook(g).intercept { chain ->
                        val result = chain.proceed()
                        val flagName = flagName(chain.thisObject) ?: return@intercept result
                        val override = evaluate(flagName, result) ?: return@intercept result
                        if (typeMatches(result, override)) override else result
                    }
                    MLog.i(GROUP, "dynamic flag class hooked: ${dyn.name}")
                    hooked = true
                }
            }
        }
        if (!hooked) MLog.w(GROUP, "no flag class found in this Gboard version")
    }

    private fun typeMatches(original: Any?, override: Any?): Boolean {
        if (original == null) return true
        return original.javaClass == override?.javaClass
    }

    /** 从混淆 flag 对象读名字：字段 a → 首个 String 字段（沿继承链） */
    private fun flagName(obj: Any?): String? {
        if (obj == null) return null
        val field = nameFieldCache.computeIfAbsent(obj.javaClass) { clazz ->
            runCatching {
                val f = clazz.getDeclaredField("a")
                if (f.type == String::class.java) {
                    f.isAccessible = true
                    return@computeIfAbsent f
                }
                null
            }.getOrNull() ?: run {
                var c: Class<*>? = clazz
                while (c != null && c != Any::class.java) {
                    for (f in c.declaredFields) {
                        if (f.type == String::class.java) {
                            f.isAccessible = true
                            return@run f
                        }
                    }
                    c = c.superclass
                }
                null
            }
        } ?: return null
        return runCatching { field.get(obj) as? String }.getOrNull()
    }

    /**
     * 核心：按 flag 名 + 当前配置决定覆写值。null = 不覆写。
     * 语言类后缀（*_language_tags/_locales/_countries）的布尔目标自动映射 "*" / ""。
     */
    fun evaluate(name: String, original: Any?): Any? {
        val isLangTag = name.endsWith("_language_tags") ||
            name.endsWith("_locales") || name.endsWith("_countries")

        fun boolTarget(v: Boolean): Any = if (isLangTag) {
            if (v) "*" else ""
        } else {
            v
        }

        // 1) 剪贴板：query 重构 flag 必须写死 false，否则 limit 恒为 100
        if (Config.clipboardEnabled && !Config.flagClipboardChips && name in CLIPBOARD_FORCED_FALSE) {
            return boolTarget(false)
        }

        // 2) 特殊 Long flag
        if (Config.flagWordLearning && name == "intended_oov_min_count") return 1L
        if (Config.flagRambler && name == "ad_activation_type") return 2L

        // 3) AI 写作后端（三选 + 非 AICore 强制关闭端侧证明）
        if (Config.flagWritingTools) {
            if (name == "writing_tools_v2_backend_type") {
                return when (Config.writingBackend) {
                    "cloud" -> 1L
                    "aicore" -> 2L
                    else -> 3L
                }
            }
            if (Config.writingBackend != "aicore" &&
                (name == "writing_tools_enable_hybrid" || name == "enable_on_device_proofread")
            ) {
                return boolTarget(false)
            }
            if (Config.flagAllKeyboards && name in WRITING_ALL_LANGUAGE_TAGS) return "*"
            when (name) {
                "writing_helper_model_version" ->
                    return "202408051448_prod_sd_config"
                "writing_helper_text_stylization_model_version" ->
                    return "202504090000_writing_tools_config"
                "nga_lab_modeless_smartedit_regex_version" -> return "v3"
            }
        }

        // 4) JSON 预设组（类型与原始返回值匹配才覆写）
        val pairs = presets()[name]
        if (pairs != null) {
            for ((group, value) in pairs) {
                if (!groupEnabled(group)) continue
                when {
                    value is Boolean && original is Boolean -> return boolTarget(value)
                    value is String && original is String -> return value
                }
            }
        }

        // 5) 用户自定义 flag
        for (cf in customFlags()) {
            if (cf.name == name) {
                return when (cf.type) {
                    "bool" -> cf.value.toBooleanStrictOrNull()
                    "long" -> cf.value.toLongOrNull()
                    "string" -> cf.value
                    else -> coerceLike(original, cf.value)
                }
            }
        }
        return null
    }

    private fun coerceLike(original: Any?, value: String): Any? = when (original) {
        is Boolean -> value.toBooleanStrictOrNull()
        is Long -> value.toLongOrNull()
        is Int -> value.toIntOrNull()
        is String -> value
        else -> null
    }

    private fun customFlags(): List<CustomFlag> {
        val json = Config.customFlagsJson
        if (json.isBlank()) return emptyList()
        customFlagsCache?.let { (j, list) -> if (j == json) return list }
        val list = try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val n = o.optString("name")
                if (n.isBlank()) null
                else CustomFlag(n, o.optString("type", "auto"), o.optString("value", ""))
            }
        } catch (t: Throwable) {
            MLog.w(GROUP, "custom_flags_json parse failed", t)
            emptyList()
        }
        customFlagsCache = json to list
        return list
    }
}
