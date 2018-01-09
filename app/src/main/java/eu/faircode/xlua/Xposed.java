/*
    This file is part of XPrivacy/Lua.

    XPrivacy/Lua is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    XPrivacy/Lua is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with XPrivacy/Lua.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2017-2018 Marcel Bokhorst (M66B)
 */

package eu.faircode.xlua;

import android.content.Context;
import android.os.Bundle;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaClosure;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Prototype;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Xposed implements IXposedHookZygoteInit, IXposedHookLoadPackage {
    private static final String TAG = "XLua.Xposed";

    private XService service = null;

    public void initZygote(final IXposedHookZygoteInit.StartupParam startupParam) throws Throwable {
        // Hook activity manager constructor
        Class<?> at = Class.forName("android.app.ActivityThread");
        XposedBridge.hookAllMethods(at, "systemMain", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    final ClassLoader loader = Thread.currentThread().getContextClassLoader();
                    Class<?> clsAM = Class.forName("com.android.server.am.ActivityManagerService", false, loader);

                    try {
                        Constructor<?> ctorAM = clsAM.getConstructor(Context.class);
                        XposedBridge.hookMethod(ctorAM, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                try {
                                    // Create service, hook android
                                    List<XHook> hooks = XHook.readHooks(startupParam.modulePath);
                                    service = new XService(param.thisObject, (Context) param.args[0], hooks, loader);
                                } catch (Throwable ex) {
                                    Log.e(TAG, Log.getStackTraceString(ex));
                                    XposedBridge.log(ex);
                                }
                            }
                        });
                    } catch (NoSuchMethodException ignored) {
                        Log.i(TAG, "Falling back to hooking all am ctors");
                        XposedBridge.hookAllConstructors(clsAM, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                try {
                                    // Create service, hook android
                                    List<XHook> hooks = XHook.readHooks(startupParam.modulePath);
                                    service = new XService(param.thisObject, null, hooks, loader);
                                } catch (Throwable ex) {
                                    Log.e(TAG, Log.getStackTraceString(ex));
                                    XposedBridge.log(ex);
                                }
                            }
                        });
                    }

                    XposedBridge.hookAllMethods(clsAM, "systemReady", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                // Initialize service
                                if (service != null) {
                                    service.systemReady();
                                    hookPackage("android", loader);
                                }
                            } catch (Throwable ex) {
                                Log.e(TAG, Log.getStackTraceString(ex));
                                XposedBridge.log(ex);
                            }
                        }
                    });

                } catch (Throwable ex) {
                    Log.e(TAG, Log.getStackTraceString(ex));
                    XposedBridge.log(ex);
                }
            }
        });
    }

    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if ("android".equals(lpparam.packageName)) {
            Log.i(TAG, "Loaded " + lpparam.packageName);
            return;
        }
        hookPackage(lpparam.packageName, lpparam.classLoader);
    }

    private void hookPackage(final String packageName, ClassLoader loader) {
        try {
            final int uid = Process.myUid();
            final IService client = XService.getClient();
            if (client == null) {
                int userid = Util.getUserId(uid);
                int start = Util.getUserUid(userid, 99000);
                int end = Util.getUserUid(userid, 99999);
                boolean isolated = (uid >= start && uid <= end);
                Log.w(TAG, "Service not accessible from " + packageName + ":" + uid +
                        " pid=" + Process.myPid() + " isolated=" + isolated);
                return;
            }

            List<XHook> hooks = client.getAssignedHooks(packageName, uid);
            for (final XHook hook : hooks)
                try {
                    // Compile script
                    InputStream is = new ByteArrayInputStream(hook.getLuaScript().getBytes());
                    final Prototype script = LuaC.instance.compile(is, "script");

                    // Get class
                    Class<?> cls = Class.forName(hook.getClassName(), false, loader);
                    String[] m = hook.getMethodName().split(":");
                    if (m.length > 1) {
                        Field field = cls.getField(m[0]);
                        Object obj = field.get(null);
                        cls = obj.getClass();
                    }

                    // Get parameter types
                    String[] p = hook.getParameterTypes();
                    Class<?>[] params = new Class[p.length];
                    for (int i = 0; i < p.length; i++)
                        params[i] = resolveClass(p[i], loader);

                    // Get return type
                    Class<?> ret = resolveClass(hook.getReturnType(), loader);

                    // Get method
                    Method method = resolveMethod(cls, m[m.length - 1], params);

                    // Check return type
                    if (!method.getReturnType().equals(ret))
                        throw new Throwable("Invalid return type got " + method.getReturnType() + " expected " + ret);

                    // Hook method
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            execute(param, "before");
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            execute(param, "after");
                        }

                        // Execute hook
                        private void execute(MethodHookParam param, String function) {
                            try {
                                // Initialize LUA runtime
                                Globals globals = JsePlatform.standardGlobals();
                                LuaClosure closure = new LuaClosure(script, globals);
                                closure.call();

                                // Check if function exists
                                LuaValue func = globals.get(function);
                                if (!func.isnil()) {
                                    // Setup globals
                                    globals.set("log", new OneArgFunction() {
                                        @Override
                                        public LuaValue call(LuaValue arg) {
                                            Log.i(TAG, packageName + ":" + uid + " " + arg.checkjstring());
                                            return LuaValue.NIL;
                                        }
                                    });

                                    // Run function
                                    Varargs result = func.invoke(
                                            CoerceJavaToLua.coerce(hook),
                                            CoerceJavaToLua.coerce(new XParam(packageName, uid, param))
                                    );

                                    // Report use
                                    Bundle data = new Bundle();
                                    data.putString("function", function);
                                    data.putInt("restricted", result.arg1().checkboolean() ? 1 : 0);
                                    client.report(hook.getId(), packageName, uid, "use", data);
                                }
                            } catch (Throwable ex) {
                                Log.e(TAG, Log.getStackTraceString(ex));

                                // Report use error
                                try {
                                    Bundle data = new Bundle();
                                    data.putString("function", function);
                                    data.putString("exception", ex.toString());
                                    data.putString("stacktrace", Log.getStackTraceString(ex));
                                    client.report(hook.getId(), packageName, uid, "use", data);
                                } catch (RemoteException ignored) {
                                }
                            }
                        }
                    });

                    // Report install
                    client.report(hook.getId(), packageName, uid, "install", new Bundle());
                } catch (Throwable ex) {
                    Log.e(TAG, Log.getStackTraceString(ex));

                    // Report install error
                    try {
                        Bundle data = new Bundle();
                        data.putString("exception", ex.toString());
                        data.putString("stacktrace", Log.getStackTraceString(ex));
                        client.report(hook.getId(), packageName, uid, "install", data);
                    } catch (RemoteException ignored) {
                    }
                }

            Log.i(TAG, "Loaded " + packageName + ":" + uid + " hooks=" + hooks.size());

        } catch (Throwable ex) {
            Log.e(TAG, Log.getStackTraceString(ex));
            XposedBridge.log(ex);
        }
    }

    private static Class<?> resolveClass(String name, ClassLoader loader) throws ClassNotFoundException {
        if ("int".equals(name))
            return int.class;
        else if ("long".equals(name))
            return long.class;
        else if ("void".equals(name))
            return Void.TYPE;
        else
            return Class.forName(name, false, loader);
    }

    private static Method resolveMethod(Class<?> cls, String name, Class<?>[] params) throws NoSuchMethodException {
        while (cls != null)
            try {
                return cls.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ex) {
                cls = cls.getSuperclass();
                if (cls == null)
                    throw ex;
            }
        throw new NoSuchMethodException(name);
    }
}
