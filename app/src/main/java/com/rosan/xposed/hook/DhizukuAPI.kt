package com.ghostzuku.xposed.hook

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener
import com.ghostzuku.xposed.Hook
import com.ghostzuku.xposed.hook.api.AndroidM
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlin.concurrent.thread

class DhizukuAPI(lpparam: XC_LoadPackage.LoadPackageParam) : Hook(lpparam) {
    
    private lateinit var context: Context

    companion object {
        lateinit var serverComponentName: ComponentName
            private set

        val serverPackageName: String
            get() = serverComponentName.packageName

        private var requesting = false
        private val permissionCallbacks = mutableListOf<(Boolean) -> Unit>()

        fun whenDhizukuPermissionGranted(action: () -> Unit) {
            if (Dhizuku.isPermissionGranted()) {
                action.invoke()
                return
            }
            
            // Queue the action for when permission is granted
            permissionCallbacks.add { granted ->
                if (granted) action.invoke()
            }
            
            // Request permission if not already requesting
            if (!requesting) synchronized(this) {
                requesting = true
                thread {
                    Dhizuku.requestPermission(object : DhizukuRequestPermissionListener() {
                        override fun onRequestPermission(grantResult: Int) {
                            requesting = false
                            val granted = grantResult == PackageManager.PERMISSION_GRANTED
                            
                            // Fire all queued callbacks
                            val callbacks = permissionCallbacks.toList()
                            permissionCallbacks.clear()
                            callbacks.forEach { it.invoke(granted) }
                        }
                    })
                }
            }
        }
        
        fun isDhizukuReady(): Boolean {
            return runCatching { Dhizuku.isPermissionGranted() }.getOrDefault(false)
        }
    }

    override fun hooking() {
        XposedHelpers.findAndHookMethod(
            Application::class.java,
            "attach",
            Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    super.afterHookedMethod(param)
                    context = param.args[0] as Context
                    
                    if (!Dhizuku.init(context)) return
                    serverComponentName = Dhizuku.getOwnerComponent()
                    
                    // Start AndroidM hooks (Device Owner spoofing)
                    AndroidM(lpparam).start()
                }
            }
        )
    }
}
