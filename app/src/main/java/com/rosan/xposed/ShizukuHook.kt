package com.ghostzuku.xposed

import android.app.AndroidAppHelper
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener
import com.rosan.dhizuku.api.DhizukuUserServiceArgs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * ShizukuHook
 *
 * Intercepts rikka.shizuku.Shizuku calls and redirects them to
 * Dhizuku (Device Owner), so apps work without Shizuku running at all.
 */
class ShizukuHook(lpparam: XC_LoadPackage.LoadPackageParam) : Hook(lpparam) {

    companion object {
        private const val SHIZUKU_CLASS = "rikka.shizuku.Shizuku"
        private const val SHIZUKU_USER_SERVICE_ARGS = "rikka.shizuku.Shizuku\$UserServiceArgs"
        private const val STARTUP_DELAY_MS = 3000L  // 3 seconds
    }

    private var shizukuClass: Class<*>? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isDhizukuReady = false
    private val pendingListeners = mutableListOf<Any>()

    override fun beforeHook(): Boolean {
        shizukuClass = getClass(SHIZUKU_CLASS) ?: return false
        
        // Schedule Dhizuku init and permission request after delay
        mainHandler.postDelayed({
            initializeDhizukuAndRequestPermission()
        }, STARTUP_DELAY_MS)
        
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

    private fun initializeDhizukuAndRequestPermission() {
        runCatching {
            val ctx = AndroidAppHelper.currentApplication()
            if (ctx != null) {
                Dhizuku.init(ctx)
                
                if (!Dhizuku.isPermissionGranted()) {
                    Dhizuku.requestPermission(object : DhizukuRequestPermissionListener() {
                        override fun onRequestPermission(grantResult: Int) {
                            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                                isDhizukuReady = true
                                firePendingListeners()
                            }
                        }
                    })
                } else {
                    isDhizukuReady = true
                    firePendingListeners()
                }
            }
        }.onFailure {
            // Log error - Dhizuku init failed
        }
    }

    private fun firePendingListeners() {
        pendingListeners.forEach { listener ->
            runCatching {
                listener.javaClass
                    .getMethod("onBinderReceived")
                    .invoke(listener)
            }
        }
        pendingListeners.clear()
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
                    runCatching {
                        Dhizuku.requestPermission(object : DhizukuRequestPermissionListener() {
                            override fun onRequestPermission(grantResult: Int) {
                                firePermissionResult(clazz, requestCode, grantResult)
                            }
                        })
                    }.onFailure {
                        firePermissionResult(clazz, requestCode, PackageManager.PERMISSION_DENIED)
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
            for (l in listeners) {
                runCatching {
                    l?.javaClass
                        ?.getMethod(
                            "onRequestPermissionResult",
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType
                        )
                        ?.invoke(l, requestCode, grantResult)
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
                        param.result = isDhizukuReady && runCatching { 
                            Dhizuku.isPermissionGranted() 
                        }.getOrDefault(false)
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

                        val dhizukuArgs = DhizukuUserServiceArgs(component)

                        runCatching {
                            Dhizuku.bindUserService(dhizukuArgs, conn)
                            param.result = null
                        }
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
                        runCatching {
                            Dhizuku.unbindUserService(conn)
                            param.result = null
                        }
                    }
                }
            )
        }
    }

    // ── 8. addBinderReceivedListener (Sticky & Non-Sticky) ───────────────────
    private fun hookAddBinderReceivedListener(clazz: Class<*>) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                clazz, "addBinderReceivedListenerSticky",
                "rikka.shizuku.Shizuku\$OnBinderReceivedListener",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val listener = param.args[0] ?: return
                        
                        if (isDhizukuReady) {
                            runCatching {
                                listener.javaClass
                                    .getMethod("onBinderReceived")
                                    .invoke(listener)
                            }
                        } else {
                            pendingListeners.add(listener)
                        }
                        
                        param.result = null
                    }
                }
            )
        }
        
        runCatching {
            XposedHelpers.findAndHookMethod(
                clazz, "addBinderReceivedListener",
                "rikka.shizuku.Shizuku\$OnBinderReceivedListener",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val listener = param.args[0] ?: return
                        
                        if (isDhizukuReady) {
                            runCatching {
                                listener.javaClass
                                    .getMethod("onBinderReceived")
                                    .invoke(listener)
                            }
                        } else {
                            pendingListeners.add(listener)
                        }
                        
                        param.result = null
                    }
                }
            )
        }
    }
}
