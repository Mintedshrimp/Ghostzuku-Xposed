package com.rosan.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookInit : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Skip our own process
        if (lpparam.packageName == "com.rosan.dhizuku.api.xposed") return

        ShizukuHook(lpparam).start()
    }
}
