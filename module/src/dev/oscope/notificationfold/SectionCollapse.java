// SPDX-License-Identifier: GPL-3.0-only

package dev.oscope.notificationfold;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.content.Context;
import android.content.SharedPreferences;
import android.service.notification.StatusBarNotification;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.util.ArrayMap;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;

/** Presentation-only grouping, with SystemUI's own templates and stack animations. */
final class SectionCollapse {
    private static final String MARKER = "dev.oscope.notificationfold.header";
    private static final String PREFIX = "com.android.systemui.statusbar.notification.";
    private static Object pipeline;
    private static SharedPreferences preferences;
    private static boolean collapsed = true;
    private static boolean userTransition;
    private static boolean transitionPending;
    private static int transitionGeneration;
    private static int layoutHeightQueryDepth;
    private static HeaderBinding currentBinding;
    private static final Set<View> sectionRows = Collections.newSetFromMap(new IdentityHashMap<>());
    private static Object nativeGroupAnimator;
    private static Object nativeExpandProperties;
    private static Object nativeCollapseProperties;
    private static final String NATURAL_HEIGHT = "dev.oscope.notificationfold.naturalHeight";

    static void install(ClassLoader loader) {
        // Only the stack's layout calculation sees collapsed rows as zero-height.
        // NotificationContentView must continue to see the row's real content height.
        Hooks.Callback layoutQuery = new Hooks.Callback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) { layoutHeightQueryDepth++; }
            @Override protected void afterHookedMethod(MethodHookParam p) { layoutHeightQueryDepth--; }
        };
        Class<?> updater = Reflect.findClassIfExists(PREFIX + "stack.NotificationStackScrollLayout$1", loader);
        if (updater != null) Hooks.hookAllMethods(updater, "onPreDraw", layoutQuery);
        Class<?> stackClass = Reflect.findClass(PREFIX + "stack.NotificationStackScrollLayout", loader);
        Hooks.hookAllMethods(stackClass, "updateContentHeight", layoutQuery);
        Hooks.Callback contentQuery = new Hooks.Callback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                p.setObjectExtra("heightQueryDepth", layoutHeightQueryDepth);
                layoutHeightQueryDepth = 0;
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                layoutHeightQueryDepth = (Integer) p.getObjectExtra("heightQueryDepth");
            }
        };
        Hooks.hookAllMethods(stackClass, "applyCurrentState$2", contentQuery);
        Hooks.hookAllMethods(stackClass, "startAnimationToState$1", contentQuery);
        Class<?> pipe = Reflect.findClassIfExists(PREFIX + "collection.NotifPipeline", loader);
        if (pipe != null) Hooks.hookAllConstructors(pipe, new Hooks.Callback() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!p.hasThrowable()) pipeline = p.thisObject;
            }
        });
        Class<?> builder = Reflect.findClassIfExists(PREFIX + "collection.render.NodeSpecBuilder", loader);
        if (builder != null) Hooks.hookAllMethods(builder, "buildNodeSpec", new Hooks.Callback() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.hasThrowable() || p.getResult() == null) return;
                try { updateSection(p.thisObject, p.getResult(), (List<?>) p.args[1]); }
                catch (Throwable ignored) { /* Unsupported layouts retain the original tree. */ }
            }
        });
        Class<?> header = Reflect.findClassIfExists(PREFIX + "stack.SectionHeaderView", loader);
        if (header != null) Hooks.hookAllMethods(header, "setClearSectionButtonEnabled", new Hooks.Callback() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (Reflect.getAdditionalInstanceField(p.thisObject, MARKER) != null) {
                    ((View) Reflect.getObjectField(p.thisObject, "mClearAllButton")).setVisibility(View.GONE);
                }
            }
        });
        Class<?> row = Reflect.findClassIfExists(PREFIX + "row.ExpandableNotificationRow", loader);
        if (row != null) {
        Hooks.hookAllMethods(row, "updateChildrenStates", contentQuery);
        Hooks.hookAllMethods(row, "getIntrinsicHeight", new Hooks.Callback() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!sectionRows.contains(p.thisObject) || p.hasThrowable()) return;
                if (p.getResult() instanceof Integer && ((Integer) p.getResult()) > 0)
                    Reflect.setAdditionalInstanceField(p.thisObject, NATURAL_HEIGHT, p.getResult());
                if (collapsed && layoutHeightQueryDepth > 0) p.setResult(0);
            }
        });
        }
        Class<?> state = Reflect.findClassIfExists(PREFIX + "stack.ExpandableViewState", loader);
        Class<?> viewState = Reflect.findClass(PREFIX + "stack.ViewState", loader);
        Hooks.hookAllMethods(viewState, "startAlphaAnimation", new Hooks.Callback() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (userTransition && sectionRows.contains(p.args[0])) {
                    // The vendor blur renders separately from an AOSP hardware layer.
                    // Keep content and material in the live view tree during this fade.
                    ((View) p.args[0]).setLayerType(View.LAYER_TYPE_NONE, null);
                }
            }
        });
        if (state != null) {
            Hooks.Callback targetState = new Hooks.Callback() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (currentBinding == null) return;
                    boolean sectionRow = sectionRows.contains(p.args[0]);
                    if (!sectionRow && (!userTransition || ((View) p.args[0]).getParent() != currentBinding.header.getParent())) return;
                    try {
                        if (userTransition && p.args[0] == currentBinding.header) {
                            int height = Math.max(currentBinding.summary.getMeasuredHeight(), currentBinding.expandedHeader.getMeasuredHeight());
                            Reflect.setIntField(p.thisObject, "height", height);
                            Reflect.setIntField(p.thisObject, "clipTopAmount", 0);
                            Reflect.setIntField(p.thisObject, "clipBottomAmount", 0);
                            Reflect.callMethod(currentBinding.header, "setActualHeight", height, false);
                        }
                        if (userTransition && p.args.length == 1 && sectionRow
                                && ValueAnimator.areAnimatorsEnabled()
                                && Reflect.getBooleanField(currentBinding.header.getParent(), "mAnimationsEnabled")) {
                            Object animator = Reflect.getObjectField(currentBinding.header.getParent(), "mStateAnimator");
                            Reflect.callMethod(p.thisObject, "animateTo", p.args[0],
                                Reflect.getObjectField(animator, "mAnimationProperties"));
                            p.setResult(null);
                            return;
                        }
                        if (sectionRow && collapsed) {
                            Integer natural = (Integer) Reflect.getAdditionalInstanceField(p.args[0], NATURAL_HEIGHT);
                            if (natural != null) Reflect.setIntField(p.thisObject, "height", natural);
                            Reflect.callMethod(p.thisObject, "setAlpha", 0f);
                            Reflect.callMethod(p.thisObject, "setYTranslation", currentBinding.header.getTranslationY());
                            if (userTransition) {
                                Reflect.setBooleanField(p.thisObject, "hidden", false);
                                Reflect.setBooleanField(p.thisObject, "gone", false);
                                Reflect.setIntField(p.thisObject, "clipTopAmount", 0);
                                Reflect.setIntField(p.thisObject, "clipBottomAmount", 0);
                            }
                        }
                        if (userTransition && p.args.length == 2) {
                            Object properties = p.args[1];
                            Object oldMap = Reflect.getObjectField(properties, "mInterpolatorMap");
                            p.setObjectExtra("nativeAnimationBackup", new Object[] {
                                Reflect.getLongField(properties, "duration"),
                                Reflect.getLongField(properties, "delay"), oldMap });
                            Object filter = Reflect.callMethod(properties, "getAnimationFilter");
                            p.setObjectExtra("nativeFilterBackup", new Object[] { filter,
                                Reflect.getBooleanField(filter, "animateAlpha"),
                                Reflect.getBooleanField(filter, "animateY"),
                                Reflect.getBooleanField(filter, "animateHeight") });
                            Reflect.setBooleanField(filter, "animateAlpha", true);
                            Reflect.setBooleanField(filter, "animateY", true);
                            Reflect.setBooleanField(filter, "animateHeight", true);
                            ArrayMap<Object, Object> map = new ArrayMap<>();
                            if (oldMap instanceof Map<?, ?>) map.putAll((Map<?, ?>) oldMap);
                            Reflect.setObjectField(properties, "mInterpolatorMap", map);
                            Reflect.callMethod(getNativeGroupAnimator(), "notificationGroupExpansionInterpolator", properties, !collapsed, false);
                            Reflect.setLongField(properties, "delay", 0L);
                            Reflect.callMethod(properties, "setCustomInterpolator", View.ALPHA,
                                nativeInterpolator("groupExpandContentAlpha"));
                        }
                    } catch (Throwable ignored) { }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object[] backup = (Object[]) p.getObjectExtra("nativeAnimationBackup");
                    if (backup == null) return;
                    Reflect.setLongField(p.args[1], "duration", (Long) backup[0]);
                    Reflect.setLongField(p.args[1], "delay", (Long) backup[1]);
                    Reflect.setObjectField(p.args[1], "mInterpolatorMap", backup[2]);
                    Object[] filter = (Object[]) p.getObjectExtra("nativeFilterBackup");
                    if (filter != null) {
                        Reflect.setBooleanField(filter[0], "animateAlpha", (Boolean) filter[1]);
                        Reflect.setBooleanField(filter[0], "animateY", (Boolean) filter[2]);
                        Reflect.setBooleanField(filter[0], "animateHeight", (Boolean) filter[3]);
                    }
                }
            };
            Hooks.hookAllMethods(state, "animateTo", targetState);
            Hooks.hookAllMethods(state, "applyToView", targetState);
        }
        Class<?> differ = Reflect.findClassIfExists(PREFIX + "collection.render.ShadeViewDiffer", loader);
        if (differ != null) Hooks.hookAllMethods(differ, "applySpec", new Hooks.Callback() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!transitionPending || currentBinding == null) return;
                transitionPending = false;
                finishWhenNativeAnimationEnds(currentBinding);
            }
        });
    }

    private static Object getNativeGroupAnimator() {
        if (nativeGroupAnimator == null) {
            ClassLoader loader = currentBinding.header.getClass().getClassLoader();
            Class<?> dependency = Reflect.findClass("com.android.systemui.DependencyEx", loader);
            nativeGroupAnimator = Reflect.callMethod(Reflect.getStaticObjectField(dependency, "sDependency"),
                "getDependency", Reflect.findClass("com.android.systemui.notification.stackednotification.NotificationGroupSplitEx", loader));
        }
        return nativeGroupAnimator;
    }

    private static long nativeDuration() {
        return Reflect.getLongField(nativeProperties(), "duration");
    }

    private static Object nativeProperties() {
        Object properties = collapsed ? nativeCollapseProperties : nativeExpandProperties;
        if (properties == null) {
            properties = Reflect.newInstance(Reflect.findClass(PREFIX + "stack.AnimationProperties",
                currentBinding.header.getClass().getClassLoader()));
            Reflect.callMethod(getNativeGroupAnimator(), "notificationGroupExpansionInterpolator", properties, !collapsed, false);
            if (collapsed) nativeCollapseProperties = properties;
            else nativeExpandProperties = properties;
        }
        return properties;
    }

    private static Interpolator nativeInterpolator(String name) {
        Map<?, ?> map = (Map<?, ?>) Reflect.getObjectField(nativeProperties(), "mInterpolatorMap");
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (name.equals(((android.util.Property<?, ?>) entry.getKey()).getName())) return (Interpolator) entry.getValue();
        }
        throw new IllegalStateException("Missing animation property");
    }

    private static void updateSection(Object builder, Object root, List<?> entries) {
        Object barn = Reflect.getObjectField(builder, "viewBarn");
        Object headerController = null;
        StatusBarNotification firstNotification = null;
        View firstRow = null;
        int count = 0;
        Set<Object> controllers = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<View> rows = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object entry : entries) {
            Object section = Reflect.callMethod(entry, "getSection");
            if (section == null) continue;
            String name = (String) Reflect.callMethod(
                Reflect.callMethod(section, "getSectioner"), "getName");
            if (!"Silent".equals(name) && !"Minimized".equals(name)) continue;
            headerController = Reflect.callMethod(section, "getHeaderController");
            Object representative = Reflect.callMethod(entry, "getRepresentativeEntry");
            if (representative == null) continue;
            Object controller = Reflect.callMethod(barn, "requireNodeController", representative);
            controllers.add(controller);
            View row = (View) Reflect.callMethod(controller, "getView");
            rows.add(row);
            if (firstRow == null) firstRow = row;
            if (firstNotification == null) firstNotification = (StatusBarNotification)
                Reflect.callMethod(representative, "getSbn");
            count += entry.getClass().getName().endsWith(".GroupEntry")
                ? ((List<?>) Reflect.callMethod(entry, "getChildren")).size() : 1;
        }
        if (headerController == null || count == 0) {
            releasePresentation();
            return;
        }
        @SuppressWarnings("unchecked") List<Object> nodes = (List<Object>) Reflect.callMethod(root, "getChildren");
        boolean hasHeader = false;
        for (Object node : nodes) if (Reflect.callMethod(node, "getController") == headerController) hasHeader = true;
        if (!hasHeader) {
            releasePresentation(); // Preserve the lock screen's privacy/visibility policy.
            return;
        }
        View header = (View) Reflect.callMethod(headerController, "getView");
        if (preferences == null) {
            preferences = header.getContext().getSharedPreferences("dev.oscope.notificationfold", 0);
            collapsed = preferences.getBoolean("collapsed", true);
        }
        HeaderBinding binding = (HeaderBinding) Reflect.getAdditionalInstanceField(header, MARKER);
        if (binding == null) {
            binding = new HeaderBinding(header);
            Reflect.setAdditionalInstanceField(header, MARKER, binding);
        }
        binding.bind(count, firstNotification, firstRow, userTransition);
        currentBinding = binding;
        sectionRows.clear();
        sectionRows.addAll(rows);
        // Retain rows and their group hierarchy. Hide only after the native resize ends.
        if (collapsed && !userTransition) for (Object node : nodes) {
            if (controllers.contains(Reflect.callMethod(node, "getController")))
                Reflect.callMethod(node, "setKeepGone", true);
        }
    }

    private static void releasePresentation() {
        sectionRows.clear();
        transitionGeneration++;
        userTransition = false;
        transitionPending = false;
        if (currentBinding != null) {
            currentBinding.summary.animate().cancel();
            currentBinding.expandedHeader.animate().cancel();
            currentBinding.headerAnimating = false;
            currentBinding.initialized = false;
        }
        currentBinding = null;
    }

    private static void toggle(HeaderBinding binding) {
        if (pipeline == null) return;
        boolean wasAnimating = userTransition;
        collapsed = !collapsed;
        transitionGeneration++;
        userTransition = true;
        transitionPending = true;
        try {
            Object stack = binding.header.getParent();
            if (!collapsed && !wasAnimating) for (View row : sectionRows) {
                Integer natural = (Integer) Reflect.getAdditionalInstanceField(row, NATURAL_HEIGHT);
                if (natural != null) Reflect.callMethod(row, "setActualHeight", natural, false);
                row.setAlpha(0f);
                row.setTranslationY(binding.header.getTranslationY());
            }
            if (stack != null) {
                Reflect.setBooleanField(stack, "mEverythingNeedsAnimation", true);
                Reflect.setBooleanField(stack, "mNeedsAnimation", true);
            }
            Reflect.callMethod(pipeline, "requestListRebuild", "NotificationFold");
            preferences.edit().putBoolean("collapsed", collapsed).apply();
        } catch (Throwable ignored) {
            collapsed = !collapsed;
            userTransition = false;
            transitionPending = false;
        }
    }

    private static void finishWhenNativeAnimationEnds(HeaderBinding binding) {
        View header = binding.header;
        int generation = transitionGeneration;
        header.postOnAnimation(() -> {
            Object parent = header.getParent();
            Runnable done = new Runnable() { @Override public void run() {
                if (!userTransition || generation != transitionGeneration) return;
                if (binding.headerAnimating && header.isAttachedToWindow()) {
                    header.postOnAnimation(this);
                    return;
                }
                // Commit final visibility before releasing animation constraints. Otherwise
                // resetViewState can restore full height/alpha for one frame before keepGone.
                if (collapsed) for (View row : sectionRows) {
                    row.setAlpha(0f);
                    row.setVisibility(View.GONE);
                }
                userTransition = false;
                Reflect.callMethod(pipeline, "requestListRebuild", "NotificationFold");
            }};
            try {
                if (parent != null && ValueAnimator.areAnimatorsEnabled()
                        && Reflect.getBooleanField(parent, "mAnimationsEnabled")) {
                    Reflect.callMethod(parent, "runAfterAnimationFinished$1", done);
                    header.postDelayed(done, nativeDuration() + 250L);
                } else done.run();
            } catch (Throwable ignored) { done.run(); }
        });
    }

    private static int resource(Context context, String type, String name, String pkg) {
        return context.getResources().getIdentifier(name, type, pkg);
    }

    private static int dimension(Context context, String name, String pkg, int fallbackDp) {
        int id = resource(context, "dimen", name, pkg);
        return id == 0 ? dp(context, fallbackDp) : context.getResources().getDimensionPixelSize(id);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static final class HeaderBinding {
        final View header;
        final FrameLayout content;
        final TextView label;
        final View button;
        final View expandedHeader;
        View summary;
        View summaryTemplate;
        View summaryBackground;
        Object blurManager;
        boolean lastCollapsed;
        boolean initialized;
        boolean headerAnimating;
        String summaryKey = "";

        HeaderBinding(View header) {
            this.header = header;
            Context context = header.getContext();
            content = (FrameLayout) Reflect.getObjectField(header, "mContents");
            TextView originalLabel = (TextView) Reflect.getObjectField(header, "mLabelView");
            originalLabel.setVisibility(View.GONE);
            ((View) Reflect.getObjectField(header, "mClearAllButton")).setVisibility(View.GONE);
            expandedHeader = LayoutInflater.from(context).inflate(resource(context, "layout",
                "oplus_notification_expand_group_header", "com.android.systemui"), content, false);
            content.addView(expandedHeader);
            label = expandedHeader.findViewById(resource(context, "id",
                "oplus_notification_expand_group_header_title", "com.android.systemui"));
            button = expandedHeader.findViewById(resource(context, "id",
                "oplus_notification_expand_group_header_button", "com.android.systemui"));
            label.setText("更多通知");
            label.setTextColor(originalLabel.getCurrentTextColor());
            label.setEnabled(true);
            label.setAccessibilityDelegate(null);
            label.setContentDescription("收起更多通知");
            content.setPadding(0, 0, 0, 0);
            header.setPadding(0, 0, 0, 0);
            View.OnClickListener listener = v -> toggle(this);
            label.setOnClickListener(listener);
            button.setOnClickListener(listener);
            expandedHeader.setOnClickListener(listener);
            button.setContentDescription("收起更多通知");
            header.setOnClickListener(listener);
            header.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
                if (b-t != ob-ot) {
                    try { Reflect.callMethod(header, "notifyHeightChanged", userTransition); }
                    catch (Throwable ignored) { }
                }
            });
        }

        void bind(int count, StatusBarNotification first, View firstRow, boolean animate) {
            Context context = header.getContext();
            String key = count + ":" + (first == null ? "" : first.getPackageName());
            boolean replaced = !key.equals(summaryKey);
            if (replaced) {
                Notification.Builder builder = new Notification.Builder(context, "notificationfold_template")
                    .setContentTitle("另外 " + count + " 个通知")
                    .setShowWhen(false).setOnlyAlertOnce(true);
                builder.setSmallIcon(android.R.drawable.ic_dialog_info);
                // A local RemoteViews template, never a posted notification.
                View replacement = builder.createContentView().apply(context, content);
                replacement.setOnClickListener(v -> toggle(this));
                replacement.setContentDescription("展开另外 " + count + " 个通知");
                View templateIcon = replacement.findViewById(android.R.id.icon);
                if (templateIcon != null) templateIcon.setVisibility(View.GONE);
                View textRow = replacement.findViewById(resource(context, "id", "notification_headerless_view_row", "android"));
                if (textRow != null && textRow.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams textParams = (ViewGroup.MarginLayoutParams) textRow.getLayoutParams();
                    textParams.setMarginStart(dimension(context, "notification_icon_circle_start", "android", 14));
                    textRow.setLayoutParams(textParams);
                }
                View templateButton = replacement.findViewById(resource(context, "id", "expand_button", "android"));
                if (templateButton != null) templateButton.setOnClickListener(v -> toggle(this));
                if (templateButton != null && templateButton.getParent() instanceof FrameLayout) {
                    View container = (View) templateButton.getParent();
                    container.setPaddingRelative(0, 0, container.getPaddingEnd(), 0);
                    ViewGroup.LayoutParams cp = container.getLayoutParams();
                    cp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                    container.setLayoutParams(cp);
                }
                ClassLoader loader = header.getClass().getClassLoader();
                Class<?> dependency = Reflect.findClass("com.android.systemui.DependencyEx", loader);
                Object manager = Reflect.callMethod(Reflect.getStaticObjectField(dependency, "sDependency"),
                    "getDependency", Reflect.findClass("com.oplus.systemui.notification.blur.ViewBlurManager", loader));
                FrameLayout card = new FrameLayout(context);
                View background = (View) Reflect.newInstance(
                    Reflect.findClass(PREFIX + "row.NotificationBackgroundView", loader), context, null);
                background.setTag("NotificationFoldSummaryBackground");
                Reflect.callMethod(background, "setCustomBackground",
                    resource(context, "drawable", "notification_material_bg", "com.android.systemui"));
                Object ext = Reflect.getObjectField(background, "mExt");
                // Decorators use the normal notification material and are included in
                // ViewBlurManager's theme, wallpaper and lock-screen update lifecycle.
                Object cardType = Reflect.getStaticObjectField(
                    Reflect.findClass("com.oplus.systemui.notification.blur.ViewBlurManager$CardType", loader), "DECORATORS");
                float radius = dimension(context, "notification_corner_radius", "com.android.systemui", 16);
                Runnable prepareBackground = () -> {
                    Reflect.callMethod(manager, "requireBlurProxyForView", background, cardType, 1.1f);
                    Reflect.callMethod(ext, "init");
                    Reflect.callMethod(manager, "refreshBlurType", background);
                    Reflect.callMethod(background, "setRadius", radius, radius, false);
                };
                prepareBackground.run();
                card.addView(background, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                card.addView(replacement, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                background.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
                    Reflect.callMethod(background, "setActualWidth", r-l);
                    Reflect.callMethod(background, "setActualHeight", b-t);
                    Reflect.callMethod(ext, "setClipLeft", 0);
                    Reflect.callMethod(ext, "setClipRight", r-l);
                    Reflect.callMethod(ext, "setClipTop", 0);
                    Reflect.callMethod(ext, "setClipBottom", b-t);
                    background.invalidate();
                });
                background.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    @Override public void onViewAttachedToWindow(View v) { prepareBackground.run(); }
                    @Override public void onViewDetachedFromWindow(View v) {
                        Reflect.callMethod(manager, "releaseBlurProxyForView", v, true, "section detached");
                    }
                });
                if (summary != null) {
                    card.setAlpha(summary.getAlpha());
                    card.setVisibility(summary.getVisibility());
                    summary.animate().cancel();
                    content.removeView(summary);
                }
                summary = card;
                summaryTemplate = replacement;
                summaryBackground = background;
                blurManager = manager;
                content.addView(summary, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                summaryKey = key;
            }
            boolean changed = initialized && lastCollapsed != collapsed;
            boolean motion = changed && animate && ValueAnimator.areAnimatorsEnabled();
            if (!motion && !headerAnimating) {
                expandedHeader.setVisibility(collapsed ? View.INVISIBLE : View.VISIBLE);
                summary.setVisibility(collapsed ? View.VISIBLE : View.INVISIBLE);
            }
            int width = header.getWidth();
            if (width <= 0) width = context.getResources().getDisplayMetrics().widthPixels - dp(context, 32);
            View activeHeader = collapsed ? summary : expandedHeader;
            activeHeader.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int height = activeHeader.getMeasuredHeight();
            ViewGroup.LayoutParams params = content.getLayoutParams();
            if (params.height != height) { params.height = height; content.setLayoutParams(params); }
            if (animate) {
                Reflect.callMethod(header, "setActualHeight",
                    Math.max(summary.getMeasuredHeight(), expandedHeader.getMeasuredHeight()), false);
                Reflect.callMethod(header, "setClipBottomAmount", 0);
                Reflect.callMethod(header, "setClipTopAmount", 0);
            }
            if (motion || (replaced && headerAnimating)) {
                Interpolator curve = nativeInterpolator("groupOverlayAlpha");
                View incoming = collapsed ? summary : expandedHeader;
                View outgoing = collapsed ? expandedHeader : summary;
                incoming.animate().cancel();
                outgoing.animate().cancel();
                incoming.setVisibility(View.VISIBLE);
                outgoing.setVisibility(View.VISIBLE);
                if (!headerAnimating) incoming.setAlpha(0f);
                headerAnimating = true;
                incoming.animate().alpha(1f).setStartDelay(0).setDuration(nativeDuration()).setInterpolator(curve).withEndAction(null);
                int generation = transitionGeneration;
                outgoing.animate().alpha(0f).setStartDelay(0).setDuration(nativeDuration()).setInterpolator(curve)
                    .withEndAction(() -> {
                        if (generation != transitionGeneration) return;
                        outgoing.setVisibility(View.INVISIBLE);
                        headerAnimating = false;
                    });
            } else if (!headerAnimating) {
                summary.setAlpha(1f);
                expandedHeader.setAlpha(1f);
            }
            lastCollapsed = collapsed;
            initialized = true;
        }

    }
}
