// SPDX-License-Identifier: GPL-3.0-only

package dev.oscope.notificationfold;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/** Cached access to SystemUI internals without a framework helper dependency. */
final class Reflect {
    private record FieldKey(Class<?> owner, String name) { }
    private record CallKey(Class<?> owner, String name, List<Class<?>> arguments) { }
    private static final Map<FieldKey, Field> fields = new ConcurrentHashMap<>();
    private static final Map<CallKey, Executable> calls = new ConcurrentHashMap<>();
    private static final Map<Object, Map<String, Object>> extras = new WeakHashMap<>();

    static Class<?> findClass(String name, ClassLoader loader) {
        try { return Class.forName(name, false, loader); }
        catch (ClassNotFoundException error) { throw new IllegalStateException(name, error); }
    }

    static Class<?> findClassIfExists(String name, ClassLoader loader) {
        try { return Class.forName(name, false, loader); }
        catch (ClassNotFoundException error) { return null; }
    }

    private static Field field(Class<?> owner, String name) {
        return fields.computeIfAbsent(new FieldKey(owner, name), key -> {
            for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
                try {
                    Field value = type.getDeclaredField(name);
                    value.setAccessible(true);
                    return value;
                } catch (NoSuchFieldException ignored) { }
            }
            throw new IllegalStateException(owner.getName() + "." + name);
        });
    }

    static Object getObjectField(Object object, String name) {
        try { return field(object.getClass(), name).get(object); }
        catch (IllegalAccessException error) { throw new IllegalStateException(error); }
    }

    static Object getStaticObjectField(Class<?> type, String name) {
        try { return field(type, name).get(null); }
        catch (IllegalAccessException error) { throw new IllegalStateException(error); }
    }

    static void setObjectField(Object object, String name, Object value) {
        try { field(object.getClass(), name).set(object, value); }
        catch (IllegalAccessException error) { throw new IllegalStateException(error); }
    }

    static int getIntField(Object object, String name) { return ((Number) getObjectField(object, name)).intValue(); }
    static long getLongField(Object object, String name) { return ((Number) getObjectField(object, name)).longValue(); }
    static boolean getBooleanField(Object object, String name) { return (Boolean) getObjectField(object, name); }
    static void setIntField(Object object, String name, int value) { setObjectField(object, name, value); }
    static void setLongField(Object object, String name, long value) { setObjectField(object, name, value); }
    static void setBooleanField(Object object, String name, boolean value) { setObjectField(object, name, value); }

    static synchronized Object getAdditionalInstanceField(Object object, String name) {
        Map<String, Object> values = extras.get(object);
        return values == null ? null : values.get(name);
    }

    static synchronized void setAdditionalInstanceField(Object object, String name, Object value) {
        extras.computeIfAbsent(object, ignored -> new HashMap<>()).put(name, value);
    }

    static Object callMethod(Object object, String name, Object... arguments) {
        try { return ((Method) resolve(object.getClass(), name, arguments)).invoke(object, arguments); }
        catch (InvocationTargetException error) { throw propagate(error.getCause()); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    static Object newInstance(Class<?> type, Object... arguments) {
        try { return ((Constructor<?>) resolve(type, "<init>", arguments)).newInstance(arguments); }
        catch (InvocationTargetException error) { throw propagate(error.getCause()); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    private static RuntimeException propagate(Throwable error) {
        if (error instanceof Error fatal) throw fatal;
        return error instanceof RuntimeException runtime ? runtime : new IllegalStateException(error);
    }

    private static Executable resolve(Class<?> owner, String name, Object[] arguments) {
        List<Class<?>> types = new ArrayList<>(arguments.length);
        for (Object argument : arguments) types.add(argument == null ? null : argument.getClass());
        return calls.computeIfAbsent(new CallKey(owner, name, types), key -> {
            List<Executable> candidates = new ArrayList<>();
            if (name.equals("<init>")) candidates.addAll(Arrays.asList(owner.getDeclaredConstructors()));
            else {
                for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
                    for (Method method : type.getDeclaredMethods()) {
                        if (method.getName().equals(name)) candidates.add(method);
                    }
                }
                for (Method method : owner.getMethods()) {
                    if (method.getName().equals(name) && !candidates.contains(method)) candidates.add(method);
                }
            }
            Executable best = null;
            int bestScore = Integer.MAX_VALUE;
            for (Executable candidate : candidates) {
                Class<?>[] parameters = candidate.getParameterTypes();
                if (parameters.length != types.size()) continue;
                int score = 0;
                for (int i = 0; i < parameters.length; i++) {
                    int cost = conversionCost(types.get(i), parameters[i]);
                    if (cost < 0) { score = Integer.MAX_VALUE; break; }
                    score += cost;
                }
                if (score < bestScore || (score == bestScore && best != null
                        && moreSpecific(parameters, best.getParameterTypes()))) {
                    best = candidate;
                    bestScore = score;
                }
            }
            if (best == null) throw new IllegalStateException(owner.getName() + "." + name + types);
            best.setAccessible(true);
            return best;
        });
    }

    private static boolean moreSpecific(Class<?>[] first, Class<?>[] second) {
        boolean narrower = false;
        for (int i = 0; i < first.length; i++) {
            if (first[i] == second[i]) continue;
            if (!second[i].isAssignableFrom(first[i])) return false;
            narrower = true;
        }
        return narrower;
    }

    private static int conversionCost(Class<?> source, Class<?> target) {
        if (source == null) return target.isPrimitive() ? -1 : 20;
        if (source == target) return 0;
        if (target.isPrimitive()) {
            Class<?> primitive = unbox(source);
            if (primitive == target) return 1;
            String widening = primitive == byte.class ? "short,int,long,float,double"
                : primitive == short.class || primitive == char.class ? "int,long,float,double"
                : primitive == int.class ? "long,float,double"
                : primitive == long.class ? "float,double"
                : primitive == float.class ? "double" : "";
            List<String> choices = Arrays.asList(widening.split(","));
            int index = choices.indexOf(target.getName());
            return index < 0 ? -1 : 2 + index;
        }
        if (!target.isAssignableFrom(source)) return -1;
        if (target.isInterface()) return 2;
        int distance = 1;
        for (Class<?> type = source; type != target; type = type.getSuperclass()) distance++;
        return distance;
    }

    private static Class<?> unbox(Class<?> type) {
        if (type == Boolean.class) return boolean.class;
        if (type == Byte.class) return byte.class;
        if (type == Short.class) return short.class;
        if (type == Character.class) return char.class;
        if (type == Integer.class) return int.class;
        if (type == Long.class) return long.class;
        if (type == Float.class) return float.class;
        if (type == Double.class) return double.class;
        return type;
    }
}
