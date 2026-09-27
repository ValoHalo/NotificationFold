// SPDX-License-Identifier: GPL-3.0-only

package dev.oscope.notificationfold;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import io.github.libxposed.api.XposedModule;

/** Keeps vendor sections and restores the native ranking sections after them. */
public final class NotificationFold extends XposedModule {
    private boolean systemUiProcess;
    private static final String COORDINATORS =
        "com.android.systemui.statusbar.notification.collection.coordinator.NotifCoordinatorsImpl";
    private static final String RANKING =
        "com.android.systemui.statusbar.notification.collection.coordinator.RankingCoordinator";

    @Override public void onModuleLoaded(ModuleLoadedParam load) {
        systemUiProcess = "com.android.systemui".equals(load.getProcessName());
        Hooks.initialize(this);
    }

    @Override public void onPackageReady(PackageReadyParam load) {
        if (!systemUiProcess || !"com.android.systemui".equals(load.getPackageName())) return;
        ClassLoader loader = load.getClassLoader();
        Class<?> target = Reflect.findClassIfExists(COORDINATORS, loader);
        if (target == null) return;
        restoreRendering(loader);
        MixedGroups.install(loader);
        if (NativeSilentGroup.canStart()) NativeSilentGroup.install(loader);
        else SectionCollapse.install(loader);
        Hooks.hookAllConstructors(target, new Hooks.Callback() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable()) return;
                try {
                    Object ranking = null;
                    for (Object arg : param.args) {
                        if (arg != null && RANKING.equals(arg.getClass().getName())) ranking = arg;
                    }
                    if (ranking == null) return;
                    Object alerting = Reflect.callMethod(ranking, "getAlertingSectioner");
                    Object silent = Reflect.callMethod(ranking, "getSilentSectioner");
                    Object minimized = Reflect.callMethod(ranking, "getMinimizedSectioner");
                    if (alerting == null || silent == null || minimized == null) return;
                    @SuppressWarnings("unchecked")
                    List<Object> sections = (List<Object>) Reflect.getObjectField(
                        param.thisObject, "mOrderedSections");
                    // Never append a duplicate or replace a partially configured pipeline.
                    if (sections.contains(alerting) || sections.contains(silent)
                            || sections.contains(minimized)) return;
                    List<Object> restored = new ArrayList<>(sections);
                    restored.add(alerting);
                    restored.add(silent);
                    restored.add(minimized);
                    for (Object arg : param.args) {
                        if (arg != null && arg.getClass().getName().endsWith(".SectionStyleProvider")) {
                            // ColorOS group headers do not support the AOSP minimized layout.
                            // Collapse the section, while retaining ordinary vendor row layouts.
                            Reflect.callMethod(arg, "setMinimizedSections", Collections.emptySet());
                        }
                    }
                    Reflect.setObjectField(param.thisObject, "mOrderedSections", restored);
                } catch (Throwable ignored) {
                    // An unsupported layout leaves the original list untouched.
                }
            }
        });
    }

    private static void restoreRendering(ClassLoader loader) {
        Class<?> builder = Reflect.findClassIfExists(
            "com.oplus.systemui.statusbar.notification.collection.render.OplusNodeSpecBuilderExImpl", loader);
        if (builder != null) Hooks.hookAllMethods(builder, "buildNodeSpec", new Hooks.Callback() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable() || !Boolean.TRUE.equals(param.getResult())) return;
                try {
                    @SuppressWarnings("unchecked")
                    List<Object> nodes = (List<Object>) Reflect.callMethod(param.args[0], "getChildren");
                    int count = ((List<?>) param.args[1]).size() + ((List<?>) param.args[2]).size();
                    if (Boolean.TRUE.equals(Reflect.callMethod(param.args[4], "isMediaControlsEnabled"))) count++;
                    if (count > nodes.size()) return;
                    // Keep vendor custom cards; let the native builder add section headers,
                    // media, notification rows, and hidden rows in its normal lifecycle.
                    nodes.subList(nodes.size() - count, nodes.size()).clear();
                    param.setResult(false);
                } catch (Throwable ignored) { }
            }
        });
    }
}
