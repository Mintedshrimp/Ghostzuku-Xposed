package com.rosan.dhizuku.api.xposed

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener
import com.rosan.dhizuku.api.DhizukuUserServiceArgs
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * ShizukuHook — core of Ghostzuku-Xposed
 *
 * Goal: intercept every meaningful call an app makes into the Shizuku API
 * (rikka.shizuku.Shizuku) and transparently redirect it to the Dhizuku
 * (Device Owner) backend. The hooked app sees exactly the same interface it
 * expects from Shizuku, so zero app-side changes are required.
 *
 * Hooks implemented
 * ─────────────────
 * 1. checkSelfPermission()      → always return PERMISSION_GRANTED (Dhizuku
 *                                  already holds Device Owner; no separate
 *                                  runtime permission grant is needed).
 * 2. requestPermission()        → no-op; immediately fires the app's registered
 *                                  OnRequestPermissionResultListener with GRANTED
 *                                  so apps that gate further work behind the
 *                                  callback proceed without blocking.
 * 3. shouldShowRequestPermissionRationale() → always false (permission is
 *                                  considered permanently granted).
 * 4. isPreV11()                 → always false; we only support the modern API.
 * 5. pingBinder() / getBinder() → redirect to Dhizuku's binder so any app that
 *                                  calls ShizukuBinderWrapper receives a valid
 *                                  Device Owner binder instead of a Shizuku one.
 * 6. bindUserService()          → translate UserServiceArgs → DhizukuUserServiceArgs
 *                                  and delegate to Dhizuku.bindUserService().
 * 7. unbindUserService()        → delegate to Dhizuku.unbindUserService().
 *
 * Package scope
 * ─────────────
 * The module hooks ALL packages (scope is configured in the LSPosed/LSPatch
 * module scope UI). Packages that never call rikka.shizuku.Shizuku are
 * unaffected because XposedHelpers.findClass will simply not find the class in
 * their classloader and the try/catch swallows the error silently.
 */
class ShizukuHook : IXposedHookLoadPackage {

    companion object {
        private const val SHIZUKU_CLASS = "rikka.shizuku.Shizuku"

        // Permission constant mirrors PackageManager.PERMISSION_GRANTED (0)
        private const val PERMISSION_GRANTED = PackageManager.PERMISSION_GRANTED

        /**
         * Lazily initialised Dhizuku reference. Dhizuku.init() must have been
         * called before any delegation happens; we call it inside every hook
         * body with a guard so it is only initialised once per process.
         */
        @Volatile
        private var dhizukuReady = false

        private fun ensureDhizuku(): Boolean {
            if (dhizukuReady) return true
            return try {
                dhizukuReady = Dhizuku.init()
                dhizukuReady
            } catch (t: Throwable) {
                false
            }
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Skip our own process to avoid re-entrant hooks
        if (lpparam.packageName == "com.rosan.dhizuku.api.xposed") return

        val shizukuClass = try {
            XposedHelpers.findClass(SHIZUKU_CLASS, lpparam.classLoader)
        } catch (e: XposedHelpers.ClassNotFoundError) {
            // Package does not use Shizuku – nothing to do
            return
        }

        hookCheckSelfPermission(shizukuClass)
        hookRequestPermission(shizukuClass)
        hookShouldShowRationale(shizukuClass)
        hookIsPreV11(shizukuClass)
        hookPingBinder(shizukuClass)
        hookGetBinder(shizukuClass)
        hookBindUserService(shizukuClass)
        hookUnbindUserService(shizukuClass)
    }

    // ─── 1. checkSelfPermission ──────────────────────────────────────────────

    private fun hookCheckSelfPermission(clazz: Class<*>) {
        XposedHelpers.findAndHookMethod(
            clazz,
            "checkSelfPermission",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // Short-circuit: Dhizuku = Device Owner = always granted
                    param.result = PERMISSION_GRANTED
                }
            }
        )
    }

    // ─── 2. requestPermission ────────────────────────────────────────────────

    private fun hookRequestPermission(clazz: Class<*>) {
        XposedHelpers.findAndHookMethod(
            clazz,
            "requestPermission",
            Int::class.javaPrimitiveType,   // requestCode
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val requestCode = param.args[0] as Int

                    if (!ensureDhizuku()) {
                        // Dhizuku not available — fire DENIED so the app knows
                        firePermissionResult(clazz, requestCode, PackageManager.PERMISSION_DENIED)
                        param.result = null
                        return
                    }

                    // Request via Dhizuku then forward result to Shizuku listeners
                    Dhizuku.requestPermission(requestCode,
                        object : DhizukuRequestPermissionListener() {
                            override fun onRequestPermissionResult(
                                reqCode: Int,
                                grantResult: Int
                            ) {
                                firePermissionResult(clazz, reqCode, grantResult)
                            }
                        }
                    )

                    // Suppress the original Shizuku requestPermission call
                    param.result = null
                }
            }
        )
    }

    /**
     * Walks the static list of OnRequestPermissionResultListeners registered
     * on the (hooked) Shizuku class and delivers the result to each one.
     */
    private fun firePermissionResult(clazz: Class<*>, requestCode: Int, grantResult: Int) {
        try {
            @Suppress("UNCHECKED_CAST")
            val listeners = XposedHelpers.getStaticObjectField(
                clazz, "requestPermissionResultListeners"
            ) as? List<*> ?: return

            for (listener in listeners) {
                if (listener == null) continue
                try {
                    listener.javaClass
                        .getMethod("onRequestPermissionResult", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                        .invoke(listener, requestCode, grantResult)
                } catch (_: Exception) { /* listener interface mismatch – skip */ }
            }
        } catch (_: Exception) { /* field name may differ across versions */ }
    }

    // ─── 3. shouldShowRequestPermissionRationale ─────────────────────────────

    private fun hookShouldShowRationale(clazz: Class<*>) {
        XposedHelpers.findAndHookMethod(
            clazz,
            "shouldShowRequestPermissionRationale",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = false
                }
            }
        )
    }

    // ─── 4. isPreV11 ─────────────────────────────────────────────────────────

    private fun hookIsPreV11(clazz: Class<*>) {
        XposedHelpers.findAndHookMethod(
            clazz,
            "isPreV11",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = false
                }
            }
        )
    }

    // ─── 5. pingBinder ───────────────────────────────────────────────────────

    private fun hookPingBinder(clazz: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                clazz,
                "pingBinder",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = ensureDhizuku()
                    }
                }
            )
        } catch (_: NoSuchMethodError) { /* optional method */ }
    }

    // ─── 6. getBinder ────────────────────────────────────────────────────────

    private fun hookGetBinder(clazz: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                clazz,
                "getBinder",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!ensureDhizuku()) return
                        val dhizukuBinder: IBinder? = try {
                            Dhizuku.getDhizukuBinder()
                        } catch (_: Exception) { null }
                        if (dhizukuBinder != null) param.result = dhizukuBinder
                    }
                }
            )
        } catch (_: NoSuchMethodError) { /* optional */ }
    }

    // ─── 7. bindUserService ──────────────────────────────────────────────────

    /**
     * Shizuku.bindUserService(UserServiceArgs, ServiceConnection) →
     * Dhizuku.bindUserService(DhizukuUserServiceArgs, ServiceConnection)
     *
     * UserServiceArgs carries: component (ComponentName), daemon, processNameSuffix,
     * tag, version.  We copy these into a DhizukuUserServiceArgs.
     */
    private fun hookBindUserService(clazz: Class<*>) {
        val userServiceArgsClass = try {
            XposedHelpers.findClass("rikka.shizuku.Shizuku\$UserServiceArgs", clazz.classLoader)
        } catch (_: XposedHelpers.ClassNotFoundError) { return }

        XposedHelpers.findAndHookMethod(
            clazz,
            "bindUserService",
            userServiceArgsClass,
            ServiceConnection::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!ensureDhizuku()) return

                    val args = param.args[0] ?: return
                    val conn = param.args[1] as? ServiceConnection ?: return

                    val component = try {
                        XposedHelpers.getObjectField(args, "componentName") as? ComponentName
                    } catch (_: Exception) { null } ?: return

                    val version = try {
                        XposedHelpers.getIntField(args, "versionCode")
                    } catch (_: Exception) { 1 }

                    val tag = try {
                        XposedHelpers.getObjectField(args, "tag") as? String
                    } catch (_: Exception) { null }

                    val dhizukuArgs = DhizukuUserServiceArgs(component)
                        .version(version)
                        .let { if (tag != null) it.tag(tag) else it }

                    try {
                        Dhizuku.bindUserService(dhizukuArgs, conn)
                        param.result = null          // suppress original call
                    } catch (t: Throwable) {
                        // Let original attempt run if Dhizuku binding failed
                    }
                }
            }
        )
    }

    // ─── 8. unbindUserService ────────────────────────────────────────────────

    private fun hookUnbindUserService(clazz: Class<*>) {
        XposedHelpers.findAndHookMethod(
            clazz,
            "unbindUserService",
            ServiceConnection::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!ensureDhizuku()) return
                    val conn = param.args[0] as? ServiceConnection ?: return
                    try {
                        Dhizuku.unbindUserService(conn)
                        param.result = null
                    } catch (_: Exception) { /* fall through to original */ }
                }
            }
        )
    }
}
