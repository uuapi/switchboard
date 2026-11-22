package com.switchboard.gboard

import com.switchboard.gboard.hook.FeatureHub
import com.switchboard.gboard.util.MLog
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Switchboard 入口。基于 libxposed 新 API（102）。
 *
 * LSPosed 通过 META-INF/xposed/java_init.list 找到本类；
 * 作用域来自 META-INF/xposed/scope.list（staticScope=true）。
 */
class SwitchboardModule : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        super.onModuleLoaded(param)
        moduleInstance = this
        MLog.init(this)
        MLog.i("Entry", "Switchboard module loaded")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        super.onPackageLoaded(param)
        if (param.packageName != FeatureHub.GBOARD_PACKAGE) return
        moduleInstance = this
        MLog.init(this)
        // 模块自身 APK 路径（读 assets/dicts 词典用）
        moduleApkPath = runCatching { moduleApplicationInfo.sourceDir }.getOrNull()
        // 所有 Gboard 进程都安装（hook 按进程幂等）；
        // 仅首进程做 Toast 等用户可感操作
        if (!param.isFirstPackage) {
            MLog.d("Entry", "non-first package: ${param.packageName} (installing hooks anyway)")
        }
        FeatureHub.install(this, param.defaultClassLoader, param.isFirstPackage)
    }

    companion object {
        @Volatile
        lateinit var moduleInstance: SwitchboardModule
            private set

        @Volatile
        var moduleApkPath: String? = null
            private set
    }
}
