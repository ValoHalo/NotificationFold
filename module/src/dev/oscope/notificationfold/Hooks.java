// SPDX-License-Identifier: GPL-3.0-only

package dev.oscope.notificationfold;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import io.github.libxposed.api.XposedInterface;

/** Runs paired callbacks within one libxposed interception. */
final class Hooks {
    private static XposedInterface api;

    static void initialize(XposedInterface framework) { api = framework; }

    static void hookAllMethods(Class<?> type, String name, Callback callback) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name)) api.hook(method).intercept(callback);
        }
    }

    static void hookAllConstructors(Class<?> type, Callback callback) {
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            api.hook(constructor).intercept(callback);
        }
    }

    abstract static class Callback implements XposedInterface.Hooker {
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable { }
        protected void afterHookedMethod(MethodHookParam param) throws Throwable { }

        @Override public final Object intercept(XposedInterface.Chain chain) throws Throwable {
            MethodHookParam param = new MethodHookParam(chain.getThisObject(), chain.getArgs().toArray());
            try {
                beforeHookedMethod(param);
            } catch (Throwable error) {
                report(error);
                param.result = null;
                param.throwable = null;
                param.returnEarly = false;
            }
            if (!param.returnEarly) {
                try { param.result = chain.proceed(param.args); }
                catch (Throwable error) { param.throwable = error; }
            }
            Object result = param.result;
            Throwable throwable = param.throwable;
            try {
                afterHookedMethod(param);
            } catch (Throwable error) {
                report(error);
                param.result = result;
                param.throwable = throwable;
            }
            if (param.throwable != null) throw param.throwable;
            return param.result;
        }

        static final class MethodHookParam {
            final Object thisObject;
            final Object[] args;
            private Object result;
            private Throwable throwable;
            private boolean returnEarly;
            private Map<String, Object> extras;

            MethodHookParam(Object receiver, Object[] arguments) {
                thisObject = receiver;
                args = arguments;
            }

            Object getResult() { return result; }
            boolean hasThrowable() { return throwable != null; }
            void setResult(Object value) {
                result = value;
                throwable = null;
                returnEarly = true;
            }
            void setObjectExtra(String key, Object value) {
                if (extras == null) extras = new HashMap<>();
                extras.put(key, value);
            }
            Object getObjectExtra(String key) { return extras == null ? null : extras.get(key); }
        }
    }

    private static void report(Throwable error) {
        api.log(6, "NotificationFold", "Notification hook failed", error);
    }
}
