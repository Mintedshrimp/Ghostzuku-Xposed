package com.ghostzuku.xposed

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener
import com.rosan.dhizuku.api.DhizukuUserServiceArgs
import com.ghostzuku.xposed.hook.DhizukuAPI
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * ShizukuHook
 *
 * Intercepts rikka.shizuku.Shizuku calls and redirects them to
 * Dhizuku (Device Owner), so apps work without Shizuku running at all.
 * 
 * Relies on DhizukuAPI for early initialization and permission handling.
 */
class ShizukuHook(lpparam: XC_LoadPackage.LoadPackageParam) : Hook(lpparam) {

    companion object {
        private const val SHIZUKU_CLASS = "rikka.shizuku.Shizuku"
        private const val SHIZUKU_USER_SERVICE_ARGS = "rikka.shizuku.Shizuku\$UserServiceArgs"
    }

    private var shizukuClass: Class<*>? = null
    private val pendingListeners = mutableListOf<Any>()

    override fun beforeHook(): Boolean {
        shizukuClass = getClass(SHIZUKU_CLASS) ?: return false
        return true
    }

    override fun hooking() {
        val clazz = shizukuClass ?: return
        
        hookCheckSelfPermission(clazz)
        hookRequestPermission(clazz)
        hookShouldShowRationale(clazz)
        hookIsPreV11(clazz)
        hookPingBinder(clazz)
        hookBindUserService(clazz)
        hookUnbindUserService(clazz)
        hookAddBinderReceivedListener(clazz)
    }

    // ── 1. checkSelfPermission ───────────────────────────────────────────────
    private fun hookCheckSelfPermission(clazz: Class<*>) {
        XposedHelpers.findAndHookMethod(
            clazz, "checkSelfPermission",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = PackageManager.PERMISSION_GRANTED
                }
            }
        )
    }

    // ── 2. requestPermission ─────────────────────────────────────────────────
    private fun hookRequestPermission(clazz: Class<*>) {
        XposedHelpers.findAndHookMethod(
            clazz, "requestPermission",
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val requestCode = param.args[0] as Int
                    
                    DhizukuAPI.whenDhizukuPermissionGranted {
                        // Permission already granted—fire callback immediately
                        firePermissionResult(clazz, requestCode, PackageManager.PERMISSION_GRANTED)
                    }
                    
                    param.result = null
                }
            }
        )
    }

    private fun firePermissionResult(clazz: Class<*>, requestCode: Int, grantResult: Int) {
        runCatching {
            @Suppress("UNCHECKED_CAST")
            val listeners = XposedHelpers.getStaticObjectField(
                clazz, "sRequestPermissionResultListeners"
            ) as? Collection<*> ?: return
            
            for (listener in listeners) {
                runCatching {
                    listener?.javaClass
                        ?.getMethod(
                            "onRequestPermissionResult",
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType
                        )
                        ?.invoke(listener, requestCode, grantResult)
                }
            }
        }
    }

    // ── 3. shouldShowRequestPermissionRationale ──────────────────────────────
    private fun hookShouldShowRationale(clazz: Class<*>) {
        XposedHelpers.findAndHookMethod(
            clazz, "shouldShowRequestPermissionRationale",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = false
                }
            }
        )
    }

    // ── 4. isPreV11 ──────────────────────────────────────────────────────────
    private fun hookIsPreV11(clazz: Class<*>) {
        XposedHelpers.findAndHookMethod(
            clazz, "isPreV11",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = false
                }
            }
        )
    }

    // ── 5. pingBinder ────────────────────────────────────────────────────────
    private fun hookPingBinder(clazz: Class<*>) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                clazz, "pingBinder",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = DhizukuAPI.isDhizukuReady()
                    }
                }
            )
        }
    }

    // ── 6. bindUserService ───────────────────────────────────────────────────
    private fun hookBindUserService(clazz: Class<*>) {
        val argsClass = getClass(SHIZUKU_USER_SERVICE_ARGS) ?: return
        runCatching {
            XposedHelpers.findAndHookMethod(
                clazz, "bindUserService",
                argsClass, ServiceConnection::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val args = param.args[0] ?: return
                        val conn = param.args[1] as? ServiceConnection ?: return

                        val component = runCatching {
                            XposedHelpers.getObjectField(args, "componentName") as? ComponentName
                        }.getOrNull() ?: return

                        DhizukuAPI.whenDhizukuPermissionGranted {
                            val dhizukuArgs = DhizukuUserServiceArgs(component)
                            Dhizuku.bindUserService(dhizukuArgs, conn)
                        }
                        
                        param.result = null
                    }
                }
            )
        }
    }

    // ── 7. unbindUserService ─────────────────────────────────────────────────
    private fun hookUnbindUserService(clazz: Class<*>) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                clazz, "unbindUserService",
                ServiceConnection::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val conn = param.args[0] as? ServiceConnection ?: return
                        DhizukuAPI.whenDhizukuPermissionGranted {
                            Dhizuku.unbindUserService(conn)
                        }
                        param.result = null
                    }
                }
            )
        }
    }

    // ── 8. addBinderReceivedListener (Sticky & Non-Sticky) ───────────────────
    private fun hookAddBinderReceivedListener(clazz: Class<*>) {
        // Sticky version
        runCatching {
            XposedHelpers.findAndHookMethod(
                clazz, "addBinderReceivedListenerSticky",
                "rikka.shizuku.Shizuku\$OnBinderReceivedListener",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val listener = param.args[0] ?: return
                        
                        if (DhizukuAPI.isDhizukuReady()) {
                            fireBinderReceived(listener)
                        } else {
                            // Queue for later—will be fired when permission granted
                            pendingListeners.add(listener)
                            DhizukuAPI.whenDhizukuPermissionGranted {
                                firePendingListeners()
                            }
                        }
                        
                        param.result = null
                    }
                }
            )
        }
        
        // Non-sticky version
        runCatching {
            XposedHelpers.findAndHookMethod(
                clazz, "addBinderReceivedListener",
                "rikka.shizuku.Shizuku\$OnBinderReceivedListener",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val listener = param.args[0] ?: return
                        
                        if (DhizukuAPI.isDhizukuReady()) {
                            fireBinderReceived(listener)
                        } else {
                            pendingListeners.add(listener)
                            DhizukuAPI.whenDhizukuPermissionGranted {
                                firePendingListeners()
                            }
                        }
                        
                        param.result = null
                    }
                }
            )
        }
    }
    
    private fun fireBinderReceived(listener: Any) {
        runCatching {
            listener.javaClass
                .getMethod("onBinderReceived")
                .invoke(listener)
        }
    }
    
    private fun firePendingListeners() {
        val listeners = pendingListeners.toList()
        pendingListeners.clear()
        listeners.forEach { listener ->
            runCatching {
                listener.javaClass
                    .getMethod("onBinderReceived")
                    .invoke(listener)
            }
        }
    }
}
