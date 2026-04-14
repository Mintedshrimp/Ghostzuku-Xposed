package com.rosan.xposed

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener
import com.rosan.dhizuku.api.DhizukuUserServiceArgs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * ShizukuHook
 *
 * Extends the project's Hook base class. Intercepts calls made to
 * rikka.shizuku.Shizuku and transparently redirects them to Dhizuku
 * (Device Owner), eliminating the need to run both services simultaneously.
 */
class ShizukuHook(lpparam: XC_LoadPackage.LoadPackageParam) : Hook(lpparam) {

    companion object {
        private const val SHIZUKU_CLASS = "rikka.shizuku.Shizuku"
        private const val SHIZUKU_USER_SERVICE_ARGS = "rikka.shizuku.Shizuku\$UserServiceArgs"
    }

    private var shizukuClass: Class<*>? = null

    /** Only proceed if the package actually uses Shizuku */
    override fun beforeHook(): Boolean {
        shizukuClass = getClass(SHIZUKU_CLASS) ?: return false
        return true
    }

    override fun hooking() {
        val clazz = shizukuClass ?: return

        // Initialise Dhizuku once for this process
        runCatching { Dhizuku.init() }

        hookCheckSelfPermission(clazz)
        hookRequestPermission(clazz)
        hookShouldShowRationale(clazz)
        hookIsPreV11(clazz)
        hookPingBinder(clazz)
        hookGetBinder(clazz)
        hookBindUserService(clazz)
        hookUnbindUserService(clazz)
    }

    // ── 1. checkSelfPermission ───────────────────────────────────────────────
    private fun hookCheckSelfPermission(clazz: Class<*>) {
        XposedHelpers.findAndHookMethod(
            clazz, "checkSelfPermission",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // Dhizuku = Device Owner → always granted
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
                        Dhizuku.requestPermission(requestCode,
                            object : DhizukuRequestPermissionListener() {
                                override fun onRequestPermissionResult(
                                    reqCode: Int, grantResult: Int
                                ) {
                                    firePermissionResult(clazz, reqCode, grantResult)
                                }
                            }
                        )
                    }.onFailure {
                        // Dhizuku unavailable – fire DENIED so app doesn't hang
                        firePermissionResult(clazz, requestCode, PackageManager.PERMISSION_DENIED)
                    }
                    param.result = null // suppress original Shizuku call
                }
            }
        )
    }

    /**
     * Deliver a permission result to every listener the app registered on
     * the Shizuku class via addRequestPermissionResultListener().
     */
    private fun firePermissionResult(clazz: Class<*>, requestCode: Int, grantResult: Int) {
        runCatching {
            @Suppress("UNCHECKED_CAST")
            val listeners = XposedHelpers.getStaticObjectField(
                clazz, "requestPermissionResultListeners"
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
                        param.result = runCatching { Dhizuku.init() }.getOrDefault(false)
                    }
                }
            )
        }
    }

    // ── 6. getBinder ─────────────────────────────────────────────────────────
    private fun hookGetBinder(clazz: Class<*>) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                clazz, "getBinder",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val binder: IBinder? = runCatching {
                            Dhizuku.getDhizukuBinder()
                        }.getOrNull()
                        if (binder != null) param.result = binder
                    }
                }
            )
        }
    }

    // ── 7. bindUserService ───────────────────────────────────────────────────
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

                        val version = runCatching {
                            XposedHelpers.getIntField(args, "versionCode")
                        }.getOrDefault(1)

                        val tag = runCatching {
                            XposedHelpers.getObjectField(args, "tag") as? String
                        }.getOrNull()

                        val dhizukuArgs = DhizukuUserServiceArgs(component)
                            .version(version)
                            .let { if (tag != null) it.tag(tag) else it }

                        runCatching {
                            Dhizuku.bindUserService(dhizukuArgs, conn)
                            param.result = null // suppress original
                        }
                    }
                }
            )
        }
    }

    // ── 8. unbindUserService ─────────────────────────────────────────────────
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
}
