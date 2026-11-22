package com.switchboard.gboard.hook

import android.content.ContentValues
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.text.InputType
import java.util.HashSet
import com.switchboard.gboard.Config
import com.switchboard.gboard.util.MLog
import io.github.libxposed.api.XposedModule
import java.util.Locale

/**
 * 剪贴板增强（源自 GboardHook + KeyFlux）：
 * 1. query：解除条数限制（sortOrder limit N）与时间限制（timestamp >= ?）
 * 2. retention：DexKit 定位过期时间解析方法，直接改返回值
 * 3. HashSet#size：绕过剪贴板条目判重
 * 4. insert：敏感文本（OTP/卡号/密码框内容）不入历史
 */
object ClipboardHook {
    const val GROUP = "Clipboard"

    /** 永久 = 100 年（毫秒） */
    private const val PERMANENT_MS = 36500L * 24L * 60L * 60L * 1000L

    // Gboard 未混淆的剪贴板 Provider（主要入口），KeyFlux 兜底候选
    private val PROVIDER_CANDIDATES = listOf(
        "com.google.android.apps.inputmethod.libs.clipboard.ClipboardContentProvider",
    )

    @Volatile
    private var currentInputType: Int = 0

    fun retentionMs(): Long {
        val days = Config.clipboardDays
        return if (days <= 0) PERMANENT_MS else days * 24L * 60L * 60L * 1000L
    }

    fun limit(): Int = Config.clipboardLimit

    fun install(module: XposedModule, cl: ClassLoader) {
        hookSecureFieldTracking(module)

        AppHooks.onContextReady { ctx ->
            hookProvider(module, cl, ctx)
            hookRetention(module, cl, ctx)
        }
        hookHashSetSize(module)
    }

    // ---------- Provider ----------
    private fun hookProvider(module: XposedModule, cl: ClassLoader, ctx: Context) {
        val providerClass = findProviderClass(module, cl, ctx) ?: run {
            MLog.w(GROUP, "clipboard provider not found")
            return
        }

        // query(Uri, String[], String, String[], String)
        val query = runCatching {
            providerClass.getDeclaredMethod(
                "query",
                Uri::class.java, Array<String>::class.java, String::class.java,
                Array<String>::class.java, String::class.java,
            )
        }.getOrNull()
        if (query != null) {
            module.hook(query).intercept { chain ->
                val newArgs = chain.getArgs().toTypedArray()
                // 时间限制
                val selection = newArgs.getOrNull(2) as? String
                val selArgs = newArgs.getOrNull(3) as? Array<String>
                if (selection != null && selArgs != null) {
                    val idx = selection.indexOf("timestamp >= ?")
                    if (idx != -1) {
                        var questionMarks = 0
                        for (i in 0 until idx) if (selection[i] == '?') questionMarks++
                        if (questionMarks < selArgs.size) {
                            val cutoff = System.currentTimeMillis() - retentionMs()
                            selArgs[questionMarks] = cutoff.toString()
                            newArgs[3] = selArgs
                            MLog.d(GROUP, "time limit rewritten")
                        }
                    }
                }
                // 条数限制
                val sortOrder = newArgs.getOrNull(4) as? String
                val limit = limit()
                if (sortOrder != null && limit > 0 &&
                    sortOrder.contains("timestamp DESC limit")
                ) {
                    newArgs[4] = "timestamp DESC limit $limit"
                    MLog.d(GROUP, "size limit rewritten to $limit")
                }
                chain.proceedWith(chain.thisObject, newArgs)
            }
            MLog.i(GROUP, "query hooked on ${providerClass.name}")
        } else {
            MLog.w(GROUP, "query method missing on ${providerClass.name}")
        }

        // insert(Uri, ContentValues) 敏感信息拦截
        if (Config.secureClipboardEnabled) {
            val insert = runCatching {
                providerClass.getDeclaredMethod("insert", Uri::class.java, ContentValues::class.java)
            }.getOrNull()
            if (insert != null) {
                module.hook(insert).intercept { chain ->
                    val values = chain.getArg(1) as? ContentValues ?: return@intercept chain.proceed()
                    if (isCurrentFieldSecure()) {
                        MLog.d(GROUP, "blocked: secure field")
                        return@intercept null
                    }
                    for (key in values.keySet()) {
                        // 只检查文本值：timestamp 等数值列 getAsString 后是 13 位纯数字，
                        // 会被卡号规则误判，导致所有剪贴板记录被拦截
                        val v = values.get(key) as? String ?: continue
                        if (isSensitiveText(v)) {
                            MLog.d(GROUP, "blocked: sensitive content (${v.length} chars)")
                            return@intercept null
                        }
                    }
                    chain.proceed()
                }
                MLog.i(GROUP, "insert hooked (secure clipboard on)")
            }
        }
    }

    private fun findProviderClass(module: XposedModule, cl: ClassLoader, ctx: Context): Class<*>? {
        for (name in PROVIDER_CANDIDATES) {
            runCatching { return cl.loadClass(name) }
        }
        // DexKit 兜底：ContentProvider 子类且使用 "timestamp" 字符串
        val bridge = DexKitSupport.ensureBridge(ctx) ?: return null
        return try {
            val result = bridge.findMethod(
                org.luckypray.dexkit.query.FindMethod()
                    .matcher(
                        org.luckypray.dexkit.query.matchers.MethodMatcher.create()
                            .addUsingString("timestamp")
                    )
            )
            if (result != null) {
                for (m in result) {
                    val dm = m.toDexMethod()
                    val c = runCatching { cl.loadClass(dm.className) }.getOrNull() ?: continue
                    if (android.content.ContentProvider::class.java.isAssignableFrom(c)) return c
                }
            }
            null
        } catch (t: Throwable) {
            MLog.w(GROUP, "dexkit provider discovery failed", t)
            null
        }
    }

    // ---------- retention ----------
    private fun hookRetention(module: XposedModule, cl: ClassLoader, ctx: Context) {
        val sp = ctx.getSharedPreferences("switchboard_cache", Context.MODE_PRIVATE)
        val method = DexKitSupport.cachedMethod(sp, ctx, "retention_method", cl) { b ->
            DexKitSupport.findRetentionMethod(b)
        } ?: return
        module.hook(method).intercept { chain ->
            retentionMs()
        }
        MLog.i(GROUP, "retention hooked: ${method.declaringClass.name}#${method.name}")
    }

    // ---------- HashSet#size ----------
    private fun hookHashSetSize(module: XposedModule) {
        val limit = limit()
        if (limit <= 0) return // 不限条数时不需要绕判重
        val size = runCatching {
            HashSet::class.java.getDeclaredMethod("size")
        }.getOrNull() ?: return
        module.hook(size).intercept { chain ->
            val set = chain.thisObject as? HashSet<*> ?: return@intercept chain.proceed()
            val first = set.firstOrNull()
            val cls = first?.javaClass?.name
            if (cls == "j\$.time.Instant" || cls == "java.time.Instant") {
                val result = chain.proceed()
                // 原始 size 超过配置时保持原值，否则伪装成小于限制以绕过判重
                return@intercept if (set.size <= limit) limit else result
            }
            chain.proceed()
        }
        MLog.i(GROUP, "HashSet#size hooked (limit=$limit)")
    }

    // ---------- 敏感字段跟踪 ----------
    private fun hookSecureFieldTracking(module: XposedModule) {
        val m = runCatching {
            InputMethodService::class.java.getDeclaredMethod(
                "onStartInput", android.view.inputmethod.EditorInfo::class.java, Boolean::class.javaPrimitiveType,
            )
        }.getOrNull() ?: return
        module.hook(m).intercept { chain ->
            (chain.getArg(0) as? android.view.inputmethod.EditorInfo)?.let {
                currentInputType = it.inputType
            }
            chain.proceed()
        }
    }

    private fun isCurrentFieldSecure(): Boolean {
        val t = currentInputType
        val cls = t and InputType.TYPE_MASK_CLASS
        val variation = t and InputType.TYPE_MASK_VARIATION
        return (cls == InputType.TYPE_CLASS_TEXT && variation in intArrayOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )) || (cls == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    // ---------- 敏感文本判定（KeyFlux 规则） ----------
    fun isSensitiveText(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        // 4-8 位纯数字 = OTP/PIN
        if (trimmed.length in 4..8 && trimmed.all { it.isDigit() }) return true
        // 去掉空格与连字符后 12-19 位纯数字 = 卡号/账号
        val digits = trimmed.replace(Regex("[\\s-]"), "")
        if (digits.length in 12..19 && digits.all { it.isDigit() }) return true
        val lower = trimmed.lowercase(Locale.ROOT)
        val keywords = listOf(
            "otp", "verification", "password", "pin", "token",
            "secret", "auth", "2fa", "mfa", "passcode",
            "验证码", "密码", "口令", "动态码",
        )
        return keywords.any { lower.contains(it) }
    }
}
