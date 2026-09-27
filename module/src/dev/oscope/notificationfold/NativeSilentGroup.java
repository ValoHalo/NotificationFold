// SPDX-License-Identifier: GPL-3.0-only

package dev.oscope.notificationfold;

import android.app.Notification;
import android.content.Context;
import android.os.SystemClock;
import java.io.File;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;

/** A local pipeline group; all movement and expansion belong to the vendor group renderer. */
final class NativeSilentGroup {
    private static final String P = "com.android.systemui.statusbar.notification.";
    private static final String KEY = "dev.oscope.notificationfold:group";
    private static ClassLoader loader;
    private static Object listBuilder, preparation, bindPipeline, summary, group, stateController, expansionManager;
    private static Context context;
    private static int count;
    private static final Map<View, CharSequence[]> styledLines = new IdentityHashMap<>();
    private static final File GUARD = new File("/data/user_de/0/com.android.systemui/files/notificationfold-native-pending");

    static boolean canStart() { return !GUARD.exists(); }

    static void install(ClassLoader classLoader) {
        loader = classLoader;
        capture(P + "collection.ShadeListBuilder", value -> listBuilder = value);
        capture(P + "collection.coordinator.PreparationCoordinator", value -> preparation = value);
        capture(P + "row.NotifBindPipeline", value -> bindPipeline = value);
        capture(P + "collection.render.GroupExpansionManagerImpl", value -> expansionManager = value);
        capture(P + "collection.coordinator.RankingCoordinator", value ->
            stateController = field(value, "mStatusBarStateController"));
        hook(P + "collection.coordinator.GroupCountCoordinator", "access$onBeforeFinalizeFilter", new Hooks.Callback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try { compose(); } catch (Throwable error) { diagnostic(error); }
            }
        });
        hook(P + "collection.render.RenderStageManager", "access$onRenderList", new Hooks.Callback() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!p.hasThrowable()) {
                    GUARD.delete();
                    if (summary != null) {
                        Object row = call(summary, "getRow");
                        if (row != null && ownRow(row)) styleGroup(row);
                    }
                }
            }
        });
        hook(P + "collection.coordinator.PreparationCoordinator$$ExternalSyntheticLambda1", "onBeforeFinalizeFilter", new Hooks.Callback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                p.setObjectExtra("cutoff", Reflect.getIntField(preparation, "mChildBindCutoff"));
                Reflect.setIntField(preparation, "mChildBindCutoff", Math.max(9, count));
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Reflect.setIntField(preparation, "mChildBindCutoff", (Integer)p.getObjectExtra("cutoff"));
            }
        });
        hook(P + "collection.render.NodeSpecBuilder", "buildNodeSpec", new Hooks.Callback() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.hasThrowable() || group == null || p.getResult() == null) return;
                if (!((List<?>) p.args[1]).contains(group)) return;
                Object section = call(group, "getSection");
                Object header = call(section, "getHeaderController");
                children(p.getResult()).removeIf(n -> call(n, "getController") == header);
            }
        });
        hook(P + "stack.NotificationChildrenContainer", "getMaxAllowedVisibleChildren", new Hooks.Callback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args.length != 1 || !ownContainer(p.thisObject)) return;
                boolean collapsedLimit = (Boolean) p.args[0];
                if (collapsedLimit) p.setResult(1);
                else if (Reflect.getBooleanField(p.thisObject, "mChildrenExpanded")
                        || (Boolean) call(field(call(p.thisObject, "getContainingNotification"), "mRowEx"), "isChildrenExpandedAnimating"))
                    p.setResult(Math.max(1, count));
                else p.setResult(1);
            }
        });
        hook(P + "stack.NotificationChildrenContainer", "getCollapsedHeight", new Hooks.Callback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (ownContainer(p.thisObject)) p.setResult(dp((View)p.thisObject, 56));
            }
        });
        hook(P + "stack.NotificationChildrenContainer", "getIntrinsicHeight", new Hooks.Callback() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (ownContainer(p.thisObject) && !Reflect.getBooleanField(p.thisObject, "mChildrenExpanded")
                        && !Reflect.getBooleanField(p.thisObject, "mUserLocked")) p.setResult(dp((View)p.thisObject, 56));
            }
        });
        hook(P + "stack.NotificationChildrenContainer", "getMinHeight", new Hooks.Callback() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (ownContainer(p.thisObject)) p.setResult(dp((View)p.thisObject, 56));
            }
        });
        hook("com.oplus.systemui.statusbar.notification.stack.NotificationChildrenContainerExtImp", "getGroupTopPaddingWhenCollapsed", new Hooks.Callback() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (ownContainer(p.args[2])) p.setResult(dp((View)p.args[2], 18));
            }
        });
        hook(P + "collection.coordinator.GroupCountCoordinator", "access$onAfterRenderGroup", new Hooks.Callback() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.args[1] == group && summary != null) styleGroup(call(summary, "getRow"));
            }
        });
        String overlay = "com.oplus.systemui.statusbar.notification.stack.OplusNotificationChildrenContainerOverlayExImpl";
        hook(overlay, "refreshOverlayContent", new Hooks.Callback() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (ownRow(p.args[0])) styleOverlay(p.thisObject);
            }
        });
        hook(overlay, "onOplusNotificationHeaderCreated", new Hooks.Callback() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (ownContainer(call(p.thisObject, "getChildrenContainer"))) styleHeaders(p.thisObject);
            }
        });
        hook(P + "collection.render.GroupExpansionManagerImpl", "setGroupExpanded", new Hooks.Callback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (summary == null || p.args[0] != summary || (Boolean)p.args[1]) return;
                Object row = call(summary,"getRow");
                if (row == null || list(call(row,"getAttachedChildren")).size() != 1) return;
                // Native manager declines one-child groups; retain its listener path for this local group.
                if (((java.util.Set<?>)field(p.thisObject,"mExpandedGroups")).remove(summary)) {
                    for (Object listener : new ArrayList<>((java.util.Set<?>)field(p.thisObject,"mOnGroupChangeListeners")))
                        call(listener,"onGroupExpansionChange",row,false);
                }
                p.setResult(null);
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.args[0] != summary || context == null) return;
                context.getSharedPreferences("dev.oscope.notificationfold", 0).edit()
                    .putBoolean("collapsed", !(Boolean)p.args[1]).apply();
            }
        });
        hook(P + "collection.ShadeListBuilder", "pruneIncompleteGroups", new Hooks.Callback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (group == null) return;
                List<Object> entries = list(p.args[0]);
                int index = entries.indexOf(group);
                if (index < 0 || list(call(group,"getChildren")).size() != 1) return;
                p.setObjectExtra("singleIndex",index);
                entries.remove(index);
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Integer index=(Integer)p.getObjectExtra("singleIndex");
                if (index != null) list(p.args[0]).add(Math.min(index,list(p.args[0]).size()),group);
            }
        });
        hook(overlay,"access$requestRebuildIfSingleChild",new Hooks.Callback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (ownContainer(call(p.args[0],"getChildrenContainer"))) p.setResult(null);
            }
        });
        String containerExt="com.oplus.systemui.statusbar.notification.stack.NotificationChildrenContainerExtImp";
        hook(containerExt,"updateIntrinsicHeight",new Hooks.Callback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (ownContainer(p.args[0]) && (Integer)p.args[4]==1) p.args[4]=2;
            }
        });
        hook(containerExt,"getGroupTopMargin",new Hooks.Callback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (ownContainer(p.args[6])) p.args[4]=false;
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (ownContainer(p.args[6]) && !(Boolean)p.args[0] && !(Boolean)p.args[1])
                    p.setResult(dp((View)p.args[6],18));
            }
        });
        hook("com.oplus.systemui.notification.row.oplusgroup.OplusNotificationGroupExtImpl","updateSingleLinePadding",new Hooks.Callback() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if ((Integer)p.args[1] == 0 && ownRow(call(p.args[0],"getNotificationParent"))) {
                    View line=(View)call(p.args[0],"getSingleLineView");
                    if (line != null) styleLine(line);
                }
            }
        });
        hook(P+"row.ExpandableNotificationRow","updateChildrenStates",new Hooks.Callback() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!ownRow(p.thisObject)) return;
                List<Object> attached=list(call(p.thisObject,"getAttachedChildren"));
                if (attached.size()==1 && Reflect.getBooleanField(p.thisObject,"mChildrenExpanded")) {
                    Object child=attached.get(0), container=call(p.thisObject,"getChildrenContainer");
                    int headerHeight=((View)field(container,"mGroupHeader")).getMeasuredHeight();
                    call(call(child,"getViewState"),"setYTranslation",(float)(headerHeight+Reflect.getIntField(container,"mDividerHeight")));
                }
            }
        });
    }

    private static void compose() {
        if (listBuilder == null || preparation == null || bindPipeline == null || stateController == null) return;
        restoreLines();
        if (((Number)call(stateController, "getState")).intValue() != 0) return;
        List<Object> list = list(field(listBuilder, "mNotifList"));
        List<Object> originals = new ArrayList<>();
        List<Object> leaves = new ArrayList<>();
        Object section = null;
        for (Object item : list) {
            Object s = call(item, "getSection");
            if (s == null) continue;
            String name = (String) call(call(s, "getSectioner"), "getName");
            if (!"Silent".equals(name) && !"Minimized".equals(name)) continue;
            if (section == null) section = s;
            originals.add(item);
            if (item.getClass().getName().endsWith(".GroupEntry")) leaves.addAll(list(call(item, "getChildren")));
            else leaves.add(item);
        }
        if (leaves.isEmpty()) return;
        ensureSummary(leaves.get(0));
        try { GUARD.createNewFile(); } catch (java.io.IOException error) { throw new IllegalStateException(error); }
        count = leaves.size();
        Object root = Reflect.getStaticObjectField(cls(P + "collection.GroupEntry"), "ROOT_ENTRY");
        if (group == null) {
            group = Reflect.newInstance(cls(P + "collection.GroupEntry"), KEY, SystemClock.uptimeMillis());
            if (!context.getSharedPreferences("dev.oscope.notificationfold",0).getBoolean("collapsed",true) && expansionManager != null) {
                @SuppressWarnings("unchecked") java.util.Set<Object> expanded=(java.util.Set<Object>)field(expansionManager,"mExpandedGroups");
                expanded.add(summary);
            }
        }
        call(field(group, "mPreviousAttachState"), "clone", field(group, "mAttachState"));
        call(field(summary, "mPreviousAttachState"), "clone", field(summary, "mAttachState"));
        call(field(group, "mAttachState"), "reset");
        call(field(summary, "mAttachState"), "reset");
        call(group, "setParent", root);
        call(group, "setSummary", summary);
        call(summary, "setParent", group);
        call(call(group, "getAttachState"), "setSection", section);
        call(call(summary, "getAttachState"), "setSection", section);
        List<Object> groupChildren = list(call(group, "getRawChildren"));
        groupChildren.clear();
        for (Object leaf : leaves) {
            call(leaf, "setParent", group);
            call(call(leaf, "getAttachState"), "setSection", section);
            groupChildren.add(leaf);
        }
        int position = list.indexOf(originals.get(0));
        list.removeAll(originals);
        list.add(position, group);
    }

    private static void ensureSummary(Object donor) {
        if (summary != null) return;
        Object dependency = Reflect.getStaticObjectField(cls("com.android.systemui.DependencyEx"), "sDependency");
        context = (Context) call(dependency, "getDependency", Context.class);
        Notification notification = new Notification.Builder(context, "notificationfold_local")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("更多通知")
            .setShowWhen(false).setGroup(KEY).setGroupSummary(true).setOngoing(true)
            .setOnlyAlertOnce(true).build();
        notification.extras.putString("android.substName", "更多通知");
        StatusBarNotification source = (StatusBarNotification)call(donor, "getSbn");
        StatusBarNotification sbn = (StatusBarNotification) Reflect.newInstance(StatusBarNotification.class,
            context.getPackageName(), context.getPackageName(), -130013, KEY, android.os.Process.myUid(), 0,
            notification, source.getUser(), null, System.currentTimeMillis());
        Ranking ranking = new Ranking();
        call(ranking, "populate", call(donor, "getRanking"));
        Reflect.setObjectField(ranking, "mKey", sbn.getKey());
        Object entry = Reflect.newInstance(cls(P + "collection.NotificationEntry"), sbn, ranking, SystemClock.uptimeMillis());
        call(field(bindPipeline, "mCollectionListener"), "onEntryInit", entry);
        call(field(preparation, "mNotifCollectionListener"), "onEntryInit", entry);
        summary = entry;
    }

    private static void styleOverlay(Object owner) {
        try {
            ViewGroup overlay = (ViewGroup)field(owner, "overlayContainer");
            if (overlay == null) return;
            for (int i = 0; i < overlay.getChildCount(); i++) {
                View child = overlay.getChildAt(i);
                styleLine(child);
            }
            styleHeaders(owner);
        } catch (Throwable error) { diagnostic(error); }
    }

    private static void styleGroup(Object row) {
        if (row == null) return;
        try {
            Object container = call(row, "getChildrenContainer");
            if (container == null) return;
            List<Object> attached = list(call(container, "getAttachedChildren"));
            if (!attached.isEmpty()) {
                View line = (View)call(attached.get(0), "getSingleLineView");
                if (line != null) styleLine(line);
            }
            styleOverlay(field(container, "mOverlayEx"));
            View collapsedHeader = (View)field(field(container, "mOverlayEx"), "opusCollapsedGroupHeader");
            if (collapsedHeader != null) {
                int timeId = collapsedHeader.getResources().getIdentifier("time", "id", "android");
                View time = collapsedHeader.findViewById(timeId);
                if (time != null) time.setVisibility(View.GONE);
            }
        } catch (Throwable error) { diagnostic(error); }
    }

    private static void styleLine(View child) {
        TextView title = child.findViewById(id(child, "notification_title"));
        TextView text = child.findViewById(id(child, "notification_text"));
        if (title == null) return;
        styledLines.putIfAbsent(child, new CharSequence[]{title.getText(), text == null ? "" : text.getText(),
            Integer.toString(child.getPaddingStart())});
        title.setText("另外 " + count + " 个通知");
        if (text != null) { text.setText(""); text.setVisibility(View.GONE); }
        child.setPaddingRelative(dp(child,14),child.getPaddingTop(),child.getPaddingEnd(),child.getPaddingBottom());
    }

    private static void restoreLines() {
        for (Map.Entry<View,CharSequence[]> entry : styledLines.entrySet()) {
            View line = entry.getKey();
            TextView title = line.findViewById(id(line,"notification_title"));
            TextView text = line.findViewById(id(line,"notification_text"));
            if (title != null && title.getText().toString().startsWith("另外 ")) title.setText(entry.getValue()[0]);
            if (text != null && text.getText().length()==0) {text.setText(entry.getValue()[1]);text.setVisibility(View.VISIBLE);}
            line.setPaddingRelative(Integer.parseInt(entry.getValue()[2].toString()),line.getPaddingTop(),line.getPaddingEnd(),line.getPaddingBottom());
        }
        styledLines.clear();
    }

    private static void styleHeaders(Object owner) {
        try {
            ViewGroup collapsedHeader = (ViewGroup)field(owner, "opusCollapsedGroupHeader");
            if (collapsedHeader != null) {
                View icons = collapsedHeader.findViewById(id(collapsedHeader, "icon_container"));
                if (icons != null) icons.setVisibility(View.INVISIBLE);
                View expand = collapsedHeader.findViewById(collapsedHeader.getResources().getIdentifier("expand_button","id","android"));
                if (expand != null) expand.setTranslationY(dp(collapsedHeader,8));
            }
            View expandedHeader = (View)field(owner, "expandGroupHeader");
            if (expandedHeader != null) {
                TextView title = expandedHeader.findViewById(id(expandedHeader, "oplus_notification_expand_group_header_title"));
                if (title != null) title.setText("更多通知");
            }
        } catch (Throwable error) { diagnostic(error); }
    }

    private static boolean ownContainer(Object container) {
        return container != null && ownRow(call(container, "getContainingNotification"));
    }
    private static boolean ownRow(Object row) { return row != null && summary != null && call(row, "getEntry") == summary; }
    private static int id(View v, String name) { return v.getResources().getIdentifier(name, "id", "com.android.systemui"); }
    private static int dp(View v, int value) { return Math.round(value * v.getResources().getDisplayMetrics().density); }
    private static Class<?> cls(String name) { return Reflect.findClass(name, loader); }
    private static Object field(Object o, String name) { return Reflect.getObjectField(o, name); }
    private static Object call(Object o, String name, Object... args) { return Reflect.callMethod(o, name, args); }
    @SuppressWarnings("unchecked") private static List<Object> list(Object o) { return (List<Object>) o; }
    private static List<Object> children(Object o) { return list(call(o, "getChildren")); }
    private static void hook(String name, String method, Hooks.Callback hook) { Hooks.hookAllMethods(cls(name), method, hook); }
    private interface Capture { void set(Object value); }
    private static void capture(String name, Capture capture) {
        Hooks.hookAllConstructors(cls(name), new Hooks.Callback() {
            @Override protected void afterHookedMethod(MethodHookParam p) { if (!p.hasThrowable()) capture.set(p.thisObject); }
        });
    }
    private static void diagnostic(Throwable error) {
        if (context == null) return;
        try (java.io.FileOutputStream out = context.openFileOutput("notificationfold-native-error.txt", 0)) {
            out.write(android.util.Log.getStackTraceString(error).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable ignored) { }
    }
}
