package com.ghostzuku.xposed

import com.ghostzuku.xposed.hook.DhizukuAPI
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookInit : IXposedHookLoadPackage {
    
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Skip Ghostzuku's own process
        if (lpparam.packageName == "com.ghostzuku.xposed") return
        
        // Skip system packages for performance (optional)
        if (lpparam.packageName.startsWith("com.android.")) return
        if (lpparam.packageName == "android") return
        
        XposedBridge.log("Ghostzuku: Hooking ${lpparam.packageName}")
        
        // 1. DhizukuAPI - Ultra-early Dhizuku init + Device Owner spoofing
        DhizukuAPI(lpparam).start()
        
        // 2. ShizukuHook - Shizuku → Dhizuku translation
        ShizukuHook(lpparam).start()
        
        // 3. RootHook - Uncomment when ready for su translation
        // RootHook(lpparam).start()
    }
}
