package com.switchboard.gboard.hook

import android.content.Context
import com.switchboard.gboard.util.MLog
import io.github.libxposed.api.XposedModule
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.wrap.DexMethod
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * DexKit 支撑层：负责桥创建、特征查询、结果按 Gboard versionCode 缓存、
 * DexMethod 反解为 java.lang.reflect.Method。
 */
object DexKitSupport {
    const val GROUP = "DexKit"

    @Volatile
    private var bridge: DexKitBridge? = null

    fun ensureBridge(ctx: Context): DexKitBridge? {
        bridge?.let { return it }
        return synchronized(this) {
            bridge?.let { return it }
            try {
                System.loadLibrary("dexkit")
                val apkPath = ctx.applicationInfo.sourceDir
                DexKitBridge.create(apkPath).also {
                    MLog.i(GROUP, "bridge created: $apkPath")
                }
            } catch (t: Throwable) {
                MLog.e(GROUP, "bridge create failed", t)
                null
            }
        }
    }

    /** 按特征串查询单个方法（多路 fallback，见 KeyFlux 四路查询） */
    fun findFlagMethod(b: DexKitBridge): DexMethod? {
        val patterns = listOf(
            arrayOf("Invalid flag: "),
            arrayOf("Invalid flag:"),
            arrayOf("HermeticFileOverrides"),
            arrayOf("PhenotypeFlag"),
        )
        for (strings in patterns) {
            try {
                val result = b.findMethod(
                    FindMethod()
                        .matcher(
                            MethodMatcher.create()
                                .usingStrings(*strings)
                                .returnType("java.lang.Object")
                        )
                )
                if (result != null && result.size > 0) {
                    if (result.size > 1) {
                        MLog.w(GROUP, "flag method ambiguous (${result.size}), take first of ${strings[0]}")
                    }
                    return result.first().toDexMethod()
                }
            } catch (t: Throwable) {
                MLog.w(GROUP, "flag query failed for ${strings[0]}: ${t.message}")
            }
        }
        return null
    }

    fun findRetentionMethod(b: DexKitBridge): DexMethod? {
        return try {
            val result = b.findMethod(
                FindMethod()
                    .matcher(MethodMatcher.create().usingStrings("Failed to parse retention hours"))
            )
            if (result != null && result.size > 0) result.first().toDexMethod() else null
        } catch (t: Throwable) {
            MLog.w(GROUP, "retention query failed: ${t.message}")
            null
        }
    }

    /**
     * 缓存感知查询：同 versionCode 直接复用上次序列化结果。
     * sp 为 Gboard 进程侧的普通 SharedPreferences。
     */
    fun cachedMethod(
        sp: android.content.SharedPreferences,
        ctx: Context,
        key: String,
        cl: ClassLoader,
        finder: (DexKitBridge) -> DexMethod?,
    ): Method? {
        val version = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
        } catch (t: Throwable) {
            -1L
        }
        val cachedVer = sp.getLong("${key}_version", -1L)
        val cachedStr = sp.getString(key, null)
        val dexMethod: DexMethod? = if (cachedVer == version && cachedStr != null) {
            try {
                DexMethod(cachedStr)
            } catch (t: Throwable) {
                MLog.w(GROUP, "$key cache decode failed, rescan", t)
                null
            }
        } else {
            null
        }

        val found = dexMethod ?: ensureBridge(ctx)?.let { finder(it) } ?: run {
            MLog.w(GROUP, "$key not found")
            return null
        }
        if (dexMethod == null) {
            sp.edit().putString(key, found.serialize()).putLong("${key}_version", version).apply()
            MLog.i(GROUP, "$key discovered: ${found.className}#${found.name}")
        }
        return resolve(found, cl)
    }

    /** DexMethod → java.lang.reflect.Method（优先 DexKit 自带解析，失败走手工匹配） */
    fun resolve(dm: DexMethod, cl: ClassLoader): Method? {
        return try {
            dm.getMethodInstance(cl, true)?.also { it.isAccessible = true }
        } catch (t: Throwable) {
            try {
                val clazz = cl.loadClass(dm.className)
                val paramTypes = dm.paramTypeNames.mapNotNull { nameToClass(it, cl) }
                clazz.declaredMethods.firstOrNull { m ->
                    m.name == dm.name &&
                        m.parameterTypes.size == paramTypes.size &&
                        m.parameterTypes.zip(paramTypes).all { (a, b) -> a == b } &&
                        !m.isSynthetic
                }?.also { it.isAccessible = true }
            } catch (t2: Throwable) {
                MLog.w(GROUP, "resolve ${dm.className}#${dm.name} failed: ${t2.message}")
                null
            }
        }
    }

    private fun nameToClass(name: String, cl: ClassLoader): Class<*>? {
        return when (name) {
            "boolean" -> java.lang.Boolean.TYPE
            "int" -> Integer.TYPE
            "long" -> java.lang.Long.TYPE
            "short" -> java.lang.Short.TYPE
            "byte" -> java.lang.Byte.TYPE
            "char" -> Character.TYPE
            "float" -> java.lang.Float.TYPE
            "double" -> java.lang.Double.TYPE
            "void" -> Void.TYPE
            else -> runCatching { cl.loadClass(name.removePrefix("L").removeSuffix(";")) }.getOrNull()
        }
    }

    /** FuckGboard 兜底：LatinPrimeKeyboard 静态字段扫描识别 flag 类 */
    fun dynamicFlagClass(cl: ClassLoader): Class<*>? {
        return try {
            val lpk = cl.loadClass(
                "com.google.android.apps.inputmethod.latin.keyboard.LatinPrimeKeyboard"
            )
            for (field in lpk.declaredFields) {
                if (!Modifier.isStatic(field.modifiers)) continue
                field.isAccessible = true
                val obj = field.get(null) ?: continue
                val objClass = obj.javaClass
                val hasG = runCatching { objClass.getDeclaredMethod("g") }.isSuccess
                val hasStrA = runCatching {
                    val f = objClass.getDeclaredField("a")
                    f.type == String::class.java
                }.getOrDefault(false)
                if (hasG && hasStrA) return objClass
            }
            null
        } catch (t: Throwable) {
            null
        }
    }
}
