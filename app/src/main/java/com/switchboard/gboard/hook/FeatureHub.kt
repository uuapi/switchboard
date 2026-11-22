package com.switchboard.gboard.hook

import com.switchboard.gboard.Config
import com.switchboard.gboard.util.MLog
import io.github.libxposed.api.XposedModule

/**
 * 功能调度中心：Gboard 进程加载后依次安装各子系统。
 * 每个子系统独立 try/catch，任何一个失败不影响其余功能。
 */
object FeatureHub {
    const val GBOARD_PACKAGE = Config.GBOARD_PACKAGE

    fun install(module: XposedModule, cl: ClassLoader, isFirstPackage: Boolean = true) {
        MLog.i("Hub", "install begin, gboard=${cl.loadClass("android.app.Application") != null}")
        Config.bind(module)

        // Application.attach / onCreate：拿 Context + 启动提示（仅首进程）
        safe("app") { AppHooks.install(module, cl, isFirstPackage) }

        safe("flag") { if (Config.flagEngineEnabled) FlagEngine.install(module, cl) }
        safe("clipboard") { if (Config.clipboardEnabled) ClipboardHook.install(module, cl) }
        safe("amoled") { if (Config.amoledEnabled) AmoledHook.install(module) }
        safe("inputtype") { if (Config.passwordKeyboardEnabled) InputTypeHook.install(module) }
        safe("emojialpha") { if (Config.emojiAlphaEnabled) EmojiAlphaHook.install(module, cl) }
        safe("punctuation") { if (Config.punctuationEnabled) PunctuationHook.install(module, cl) }
        safe("shuangpin") { if (Config.shuangpinEnabled) ShuangpinHook.install(module, cl) }
        safe("gloss") { if (Config.glossEnabled) GlossHook.install(module, cl) }
        safe("toolbar") { if (Config.toolbarEnabled) ToolbarHook.install(module, cl) }
        safe("rgb") { if (Config.rgbEnabled) RgbHook.install(module, cl) }

        MLog.i("Hub", "install end")
    }

    private inline fun safe(group: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            MLog.e("Hub", "[$group] install failed", t)
        }
    }
}
