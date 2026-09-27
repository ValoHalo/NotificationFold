// SPDX-License-Identifier: GPL-3.0-only

package dev.oscope.notificationfold;

import java.util.ArrayList;
import java.util.List;

/** Separate low-importance children before native section assignment and pruning. */
final class MixedGroups {
    static void install(ClassLoader loader) {
        Class<?> builder = Reflect.findClass(
            "com.android.systemui.statusbar.notification.collection.ShadeListBuilder", loader);
        Class<?> group = Reflect.findClass(
            "com.android.systemui.statusbar.notification.collection.GroupEntry", loader);
        Hooks.hookAllMethods(builder, "pruneIncompleteGroups", new Hooks.Callback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    Object state = Reflect.getObjectField(p.thisObject, "mPipelineState");
                    if (((Number) Reflect.callMethod(state, "getState")).intValue() != 5) return;
                    @SuppressWarnings("unchecked") List<Object> entries = (List<Object>) p.args[0];
                    Object root = Reflect.getStaticObjectField(group, "ROOT_ENTRY");
                    for (Object entry : new ArrayList<>(entries)) {
                        if (!group.isInstance(entry)) continue;
                        @SuppressWarnings("unchecked") List<Object> children =
                            (List<Object>) Reflect.callMethod(entry, "getRawChildren");
                        boolean alerting = false;
                        for (Object child : children) if (!isSilent(child)) alerting = true;
                        if (!alerting) continue;
                        for (Object child : new ArrayList<>(children)) {
                            if (!isSilent(child)) continue;
                            Reflect.callMethod(child, "setParent", root);
                            children.remove(child);
                            entries.add(child);
                        }
                    }
                } catch (Throwable ignored) { }
            }
        });
    }

    private static boolean isSilent(Object entry) {
        Object ranking = Reflect.callMethod(entry, "getRanking");
        int importance = ((Number) Reflect.callMethod(ranking, "getImportance")).intValue();
        return importance > 0 && importance < 3;
    }
}
