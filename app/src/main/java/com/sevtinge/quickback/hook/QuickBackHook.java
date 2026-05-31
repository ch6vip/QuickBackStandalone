package com.sevtinge.quickback.hook;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import com.sevtinge.quickback.BuildConfig;
import com.sevtinge.quickback.Prefs;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.callStaticMethod;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.getStaticObjectField;
import static de.robv.android.xposed.XposedHelpers.setObjectField;

public final class QuickBackHook implements IXposedHookLoadPackage {

    private static final String TARGET_PACKAGE = "com.miui.home";
    private static final String CLASS_GESTURE_STUB_VIEW = "com.miui.home.recents.GestureStubView";
    private static final String CLASS_GESTURE_STUB_CALLBACK = "com.miui.home.recents.GestureStubView$3";
    private static final String CLASS_GESTURE_BACK_ARROW_VIEW = "com.miui.home.recents.GestureBackArrowView";
    private static final String CLASS_READY_STATE = "com.miui.home.recents.GestureBackArrowView$ReadyState";
    private static final String CLASS_RECENTS_MODEL = "com.miui.home.recents.RecentsModel";
    private static final String CLASS_ACTIVITY_MANAGER_WRAPPER = "com.android.systemui.shared.recents.system.ActivityManagerWrapper";
    private static final String CLASS_BACK_GESTURE_UTILS = "com.android.systemui.fsgesture.BackGestureUtils";
    private static final long MODERN_QUICK_BACK_HOLD_MS = 350L;
    private static final float MODERN_QUICK_BACK_MIN_OFFSET = 180.0f;

    private static final int STATE_BACK = 1;
    private static final int STATE_RECENT = 2;
    private static final int STATE_NONE = 3;

    private final Map<String, Integer> mAnimResCache = new HashMap<>();
    private final Map<Object, Long> mSwipeStartTimes = new WeakHashMap<>();
    private ClassLoader mClassLoader;
    private int[] mReadyStateValues;
    private Method mStartTaskFromRecentsByIdMethod;
    private Method mStartTaskFromRecentsByKeyMethod;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        boolean enabled = isEnabled();
        log("handleLoadPackage: loaded in " + lpparam.packageName + ", enabled=" + enabled);
        if (!enabled) {
            return;
        }

        mClassLoader = lpparam.classLoader;
        initReadyStateValues();
        installHook("hookDisableQuickSwitch", this::hookDisableQuickSwitch);
        installHook("hookLoadRecentTaskIcon", this::hookLoadRecentTaskIcon);
        installHook("hookOnSwipeStart", this::hookOnSwipeStart);
        installHook("hookOnSwipeStop", this::hookOnSwipeStop);
        log("handleLoadPackage: hooks installed");
    }

    private void installHook(String name, ThrowingRunnable installer) {
        try {
            installer.run();
        } catch (Throwable e) {
            log(name + ": failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            if ("hookDisableQuickSwitch".equals(name)) {
                logClassMethods(CLASS_GESTURE_STUB_VIEW, "quick|switch|task|support");
            } else if ("hookLoadRecentTaskIcon".equals(name)) {
                logClassMethods(CLASS_GESTURE_BACK_ARROW_VIEW, "recent|task|icon|state");
            } else if ("hookOnSwipeStart".equals(name)) {
                logClassMethods(CLASS_GESTURE_STUB_CALLBACK, "swipe|start|quick|task");
            } else if ("hookOnSwipeStop".equals(name)) {
                logClassMethods(CLASS_GESTURE_STUB_CALLBACK, "swipe|stop|finish|quick|task");
            }
        }
    }

    private boolean isEnabled() {
        try {
            XSharedPreferences prefs = new XSharedPreferences(BuildConfig.APPLICATION_ID, Prefs.FILE_NAME);
            prefs.reload();
            boolean canRead = prefs.getFile().canRead();
            log("isEnabled: fileCanRead=" + canRead);
            if (!canRead) {
                log("isEnabled: prefs unreadable, fallback enabled for compatibility test");
                return true;
            }
            return prefs.getBoolean(Prefs.KEY_ENABLED, false);
        } catch (Throwable ignored) {
            log("isEnabled: failed to read prefs");
            return true;
        }
    }

    private void initReadyStateValues() {
        try {
            Class<?> readyStateClass = findClass(CLASS_READY_STATE, mClassLoader);
            Object[] enumValues = (Object[]) callStaticMethod(readyStateClass, "values");
            mReadyStateValues = new int[enumValues.length];
            mReadyStateValues[getEnumOrdinal(readyStateClass, "READY_STATE_BACK")] = STATE_BACK;
            mReadyStateValues[getEnumOrdinal(readyStateClass, "READY_STATE_RECENT")] = STATE_RECENT;
            mReadyStateValues[getEnumOrdinal(readyStateClass, "READY_STATE_NONE")] = STATE_NONE;
        } catch (Throwable ignored) {
            log("initReadyStateValues: failed");
        }
    }

    private int getEnumOrdinal(Class<?> enumClass, String name) {
        Enum<?> value = (Enum<?>) getStaticObjectField(enumClass, name);
        return value.ordinal();
    }

    private void hookDisableQuickSwitch() {
        if (findDeclaredMethod(CLASS_GESTURE_STUB_VIEW, "isDisableQuickSwitch") == null) {
            log("hookDisableQuickSwitch: skipped, method missing");
            return;
        }
        findAndHookMethod(CLASS_GESTURE_STUB_VIEW, mClassLoader, "isDisableQuickSwitch",
            XC_MethodReplacement.returnConstant(false));
        log("hookDisableQuickSwitch: installed");
    }

    private void hookLoadRecentTaskIcon() {
        if (findDeclaredMethod(CLASS_GESTURE_BACK_ARROW_VIEW, "loadRecentTaskIcon") == null) {
            log("hookLoadRecentTaskIcon: skipped, method missing");
            return;
        }
        findAndHookMethod(CLASS_GESTURE_BACK_ARROW_VIEW, mClassLoader, "loadRecentTaskIcon",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object icon = loadRecentTaskIcon(param.thisObject);
                        if (icon != null) {
                            param.setResult(icon);
                        }
                    } catch (Throwable ignored) {
                        log("loadRecentTaskIcon: failed");
                    }
                }
            });
        log("hookLoadRecentTaskIcon: installed");
    }

    private void hookOnSwipeStart() {
        if (findDeclaredMethod(CLASS_GESTURE_STUB_CALLBACK, "onSwipeStart", float.class) == null) {
            log("hookOnSwipeStart: skipped, method missing");
            return;
        }
        findAndHookMethod(CLASS_GESTURE_STUB_CALLBACK, mClassLoader,
            "onSwipeStart", float.class,
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    synchronized (mSwipeStartTimes) {
                        mSwipeStartTimes.put(param.thisObject, SystemClock.uptimeMillis());
                    }
                }
            });
        log("hookOnSwipeStart: installed");
    }

    private void hookOnSwipeStop() {
        findAndHookMethod(CLASS_GESTURE_STUB_CALLBACK, mClassLoader,
            "onSwipeStop", boolean.class, float.class, boolean.class,
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    boolean isFinish = (boolean) param.args[0];
                    if (!isFinish) {
                        return;
                    }

                    try {
                        if (isLegacyRecentState(param.thisObject) || isModernQuickBackGesture(param)) {
                            handleRecentSwipeStop(param);
                        }
                    } catch (Throwable ignored) {
                        log("onSwipeStop: failed");
                    }
                }
            });
        log("hookOnSwipeStop: installed");
    }

    private boolean isLegacyRecentState(Object swipeCallback) throws Throwable {
        if (mReadyStateValues == null) {
            return false;
        }
        return mapOrdinalToState(getCurrentStateOrdinal(swipeCallback)) == STATE_RECENT;
    }

    private boolean isModernQuickBackGesture(XC_MethodHook.MethodHookParam param) {
        if (mReadyStateValues != null) {
            return false;
        }

        Long startTime;
        synchronized (mSwipeStartTimes) {
            startTime = mSwipeStartTimes.remove(param.thisObject);
        }
        if (startTime == null) {
            return false;
        }

        long duration = SystemClock.uptimeMillis() - startTime;
        float offset = (float) param.args[1];
        boolean shouldHandle = duration >= MODERN_QUICK_BACK_HOLD_MS
            && offset >= MODERN_QUICK_BACK_MIN_OFFSET;
        if (shouldHandle) {
            log("modern quick back gesture: duration=" + duration + ", offset=" + offset);
        }
        return shouldHandle;
    }

    private int getCurrentStateOrdinal(Object swipeCallback) throws Throwable {
        Object gestureStubView = getObjectField(swipeCallback, "this$0");
        Object arrowView = getObjectField(gestureStubView, "mGestureBackArrowView");
        Object currentState = callMethod(arrowView, "getCurrentState");
        return (int) callMethod(currentState, "ordinal");
    }

    private int mapOrdinalToState(int ordinal) {
        if (mReadyStateValues != null && ordinal >= 0 && ordinal < mReadyStateValues.length) {
            return mReadyStateValues[ordinal];
        }
        try {
            Class<?> switchMapClass = findSwitchMapClass();
            if (switchMapClass != null) {
                int[] switchMap = (int[]) getStaticObjectField(switchMapClass,
                    "$SwitchMap$com$miui$home$recents$GestureBackArrowView$ReadyState");
                if (ordinal >= 0 && ordinal < switchMap.length) {
                    return switchMap[ordinal];
                }
            }
        } catch (Throwable ignored) {
        }
        return STATE_NONE;
    }

    private Class<?> findSwitchMapClass() {
        try {
            return findClass("com.miui.home.recents.GestureStubView$4", mClassLoader);
        } catch (Throwable ignored) {
            try {
                return findClass("com.miui.home.recents.GestureStubView$5", mClassLoader);
            } catch (Throwable ignoredAgain) {
                return null;
            }
        }
    }

    private Object findNextTask(Context context) throws Throwable {
        Object recentsModel = callStaticMethod(findClass(CLASS_RECENTS_MODEL, mClassLoader), "getInstance", context);
        ActivityManager.RunningTaskInfo runningTask = getRunningTaskForQuickBack(recentsModel);
        if (runningTask == null) {
            log("findNextTask: runningTask is null");
            return null;
        }

        int runningTaskId = getRunningTaskId(runningTask);
        Object task = findNextTaskFromTaskList(recentsModel, runningTask, runningTaskId);
        if (task != null) {
            return task;
        }

        try {
            Object loadPlan = callMethod(recentsModel, "getSmartRecentsTaskLoadPlan", context, runningTaskId);
            Object taskStack = loadPlan != null ? callMethod(loadPlan, "getTaskStack") : null;
            if (taskStack == null || (int) callMethod(taskStack, "getTaskCount") == 0) {
                log("findNextTask: taskStack is empty");
                return null;
            }
            return getNextTaskFromStack(taskStack, runningTask, runningTaskId);
        } catch (Throwable e) {
            log("findNextTask: legacy load plan unavailable: " + e.getClass().getSimpleName());
            return null;
        }
    }

    private Object findNextTaskFromTaskList(Object recentsModel, ActivityManager.RunningTaskInfo runningTask,
                                           int runningTaskId) throws Throwable {
        ArrayList<?> tasks = getTaskList(recentsModel);
        if (tasks == null || tasks.isEmpty()) {
            log("findNextTaskFromTaskList: task list is empty");
            return null;
        }

        int runningTaskIndex = findTaskIndex(tasks, runningTaskId);
        if (runningTaskIndex >= 0 && runningTaskIndex + 1 < tasks.size()) {
            return tasks.get(runningTaskIndex + 1);
        }
        if (runningTaskIndex >= 0) {
            log("findNextTaskFromTaskList: running task has no next task");
        } else {
            log("findNextTaskFromTaskList: running task not found");
        }

        if (runningTask.baseActivity != null && TARGET_PACKAGE.equals(runningTask.baseActivity.getPackageName())) {
            return tasks.get(0);
        }

        return null;
    }

    private ArrayList<?> getTaskList(Object recentsModel) {
        try {
            return new ArrayList<>((java.util.List<?>) callMethod(recentsModel, "getTaskList", false));
        } catch (Throwable ignored) {
            try {
                return new ArrayList<>((java.util.List<?>) callMethod(recentsModel, "getTaskList", true));
            } catch (Throwable ignoredAgain) {
                return null;
            }
        }
    }

    private int findTaskIndex(ArrayList<?> tasks, int taskId) {
        for (int i = 0; i < tasks.size(); i++) {
            try {
                if ((boolean) callMethod(tasks.get(i), "isSameTaskFromId", taskId)) {
                    return i;
                }
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    private ActivityManager.RunningTaskInfo getRunningTaskForQuickBack(Object recentsModel) throws Throwable {
        try {
            return (ActivityManager.RunningTaskInfo) callMethod(recentsModel, "getRunningTaskForGesture", true);
        } catch (Throwable ignored) {
            return (ActivityManager.RunningTaskInfo) callMethod(recentsModel, "getRunningTask");
        }
    }

    private Object getNextTaskFromStack(Object taskStack, ActivityManager.RunningTaskInfo runningTask, int runningTaskId) throws Throwable {
        ArrayList<?> stackTasks = (ArrayList<?>) callMethod(taskStack, "getStackTasks");
        if (stackTasks == null || stackTasks.isEmpty()) {
            return null;
        }

        Object runningTaskInStack = callMethod(taskStack, "findTaskWithId", runningTaskId);
        if (runningTaskInStack != null) {
            int runningTaskIndex = (int) callMethod(taskStack, "indexOfStackTask", runningTaskInStack);
            if (runningTaskIndex >= 0 && runningTaskIndex + 1 < stackTasks.size()) {
                return stackTasks.get(runningTaskIndex + 1);
            }
            log("getNextTaskFromStack: running task has no next task");
        } else {
            log("getNextTaskFromStack: running task not found in stack");
        }

        if (runningTask.baseActivity != null && "com.miui.home".equals(runningTask.baseActivity.getPackageName())) {
            return stackTasks.get(0);
        }

        return null;
    }

    private void handleRecentSwipeStop(XC_MethodHook.MethodHookParam param) throws Throwable {
        Object swipeCallback = param.thisObject;
        Object gestureStubView = getObjectField(swipeCallback, "this$0");
        Object arrowView = getObjectField(gestureStubView, "mGestureBackArrowView");
        Context context = (Context) getObjectField(gestureStubView, "mContext");
        int gestureStubPos = (int) getObjectField(gestureStubView, "mGestureStubPos");

        callMethod(gestureStubView, "onBackCancelled");

        if (isNextTaskSupported(gestureStubView)) {
            Object task = findNextTask(context);
            if (task != null && startTaskFromRecents(context, task, gestureStubPos)) {
                log("handleRecentSwipeStop: task started");
                finishSwipeStop(gestureStubView, arrowView, (float) param.args[1]);
                param.setResult(null);
                return;
            }
            log("handleRecentSwipeStop: no task started");
        }

        vibrateQuickBackFail(gestureStubView);
        finishSwipeStop(gestureStubView, arrowView, (float) param.args[1]);
        param.setResult(null);
    }

    private Object loadRecentTaskIcon(Object arrowView) throws Throwable {
        if (!isNextTaskSupportedFromArrowView(arrowView)) {
            return getObjectField(arrowView, "mNoneTaskIcon");
        }

        Context context = (Context) callMethod(arrowView, "getContext");
        Object task = findNextTask(context);
        if (task == null) {
            return getObjectField(arrowView, "mNoneTaskIcon");
        }

        loadTaskIconIfNeeded(context, task);
        Object icon = getObjectField(task, "icon");
        return icon != null ? icon : getObjectField(arrowView, "mNoneTaskIcon");
    }

    private void loadTaskIconIfNeeded(Context context, Object task) throws Throwable {
        if (getObjectField(task, "icon") != null) {
            return;
        }

        Object recentsModel = callStaticMethod(findClass(CLASS_RECENTS_MODEL, mClassLoader), "getInstance", context);
        Object taskLoader = callMethod(recentsModel, "getTaskLoader");
        Object icon = callMethod(taskLoader, "getAndUpdateActivityIcon",
            getObjectField(task, "key"),
            getObjectField(task, "taskDescription"),
            context.getResources(),
            true);
        setObjectField(task, "icon", icon);
    }

    private boolean startTaskFromRecents(Context context, Object task, int gestureStubPos) throws Throwable {
        ActivityOptions options = createActivityOptions(context, task, gestureStubPos);
        Object taskKey = getObjectField(task, "key");
        int taskId = (int) getObjectField(taskKey, "id");
        Object wrapper = callStaticMethod(findClass(CLASS_ACTIVITY_MANAGER_WRAPPER, mClassLoader), "getInstance");
        if (wrapper == null) {
            return false;
        }

        try {
            if (mStartTaskFromRecentsByIdMethod == null) {
                mStartTaskFromRecentsByIdMethod = wrapper.getClass().getMethod(
                    "startActivityFromRecents", Integer.TYPE, ActivityOptions.class);
                mStartTaskFromRecentsByIdMethod.setAccessible(true);
            }
            Object started = mStartTaskFromRecentsByIdMethod.invoke(wrapper, taskId, options);
            return !(started instanceof Boolean) || (Boolean) started;
        } catch (Throwable ignored) {
            try {
                if (mStartTaskFromRecentsByKeyMethod == null) {
                    mStartTaskFromRecentsByKeyMethod = wrapper.getClass().getMethod(
                        "startActivityFromRecents", taskKey.getClass(), ActivityOptions.class);
                    mStartTaskFromRecentsByKeyMethod.setAccessible(true);
                }
                mStartTaskFromRecentsByKeyMethod.invoke(wrapper, taskKey, options);
                return true;
            } catch (Throwable ignoredAgain) {
                return false;
            }
        }
    }

    private ActivityOptions createActivityOptions(Context context, Object task, int gestureStubPos) throws Throwable {
        ActivityOptions options = null;
        if (gestureStubPos == 0) {
            options = ActivityOptions.makeCustomAnimation(context,
                getAnimResId(context, "recents_quick_switch_left_enter"),
                getAnimResId(context, "recents_quick_switch_left_exit"));
        } else if (gestureStubPos == 1) {
            options = ActivityOptions.makeCustomAnimation(context,
                getAnimResId(context, "recents_quick_switch_right_enter"),
                getAnimResId(context, "recents_quick_switch_right_exit"));
        }

        int windowingMode = (int) getObjectField(getObjectField(task, "key"), "windowingMode");
        if (windowingMode == 3) {
            if (options == null) {
                options = ActivityOptions.makeBasic();
            }
            callMethod(options, "setLaunchWindowingMode", 4);
        }

        return options;
    }

    private int getAnimResId(Context context, String animName) {
        Integer cached = mAnimResCache.get(animName);
        if (cached != null) {
            return cached;
        }

        int resId = context.getResources().getIdentifier(animName, "anim", context.getPackageName());
        mAnimResCache.put(animName, resId);
        return resId;
    }

    private int getRunningTaskId(ActivityManager.RunningTaskInfo runningTask) {
        try {
            Object wrapper = callStaticMethod(findClass(CLASS_ACTIVITY_MANAGER_WRAPPER, mClassLoader), "getInstance");
            if (wrapper != null) {
                return (int) callMethod(wrapper, "getTaskId", runningTask);
            }
        } catch (Throwable ignored) {
        }

        try {
            return (int) getObjectField(runningTask, "taskId");
        } catch (Throwable ignored) {
            return runningTask.id;
        }
    }

    private boolean isNextTaskSupported(Object gestureStubView) throws Throwable {
        Object contentResolver = getObjectField(gestureStubView, "mContentResolver");
        try {
            return (boolean) callStaticMethod(findClass(CLASS_GESTURE_STUB_VIEW, mClassLoader), "supportNextTask", contentResolver);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private boolean isNextTaskSupportedFromArrowView(Object arrowView) throws Throwable {
        Object contentResolver = getObjectField(arrowView, "mContentResolver");
        try {
            return (boolean) callStaticMethod(findClass(CLASS_GESTURE_STUB_VIEW, mClassLoader), "supportNextTask", contentResolver);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private void vibrateQuickBackFail(Object gestureStubView) throws Throwable {
        Object vibrator = getObjectField(gestureStubView, "mVibrator");
        if (vibrator != null) {
            callMethod(vibrator, "vibrate", 100L);
        }
        log("vibrateQuickBackFail");
    }

    private void finishSwipeStop(Object gestureStubView, Object arrowView, float offset) throws Throwable {
        setObjectField(gestureStubView, "mIsGestureStarted", false);

        Object handler = getObjectField(gestureStubView, "mHandler");
        Object resetMessage = callMethod(handler, "obtainMessage", 258);
        callMethod(handler, "sendMessageDelayed", resetMessage, 500L);
        callMethod(handler, "removeMessages", 261);

        Object animatorListener = getObjectField(gestureStubView, "mAnimatorListener");
        try {
            if (mReadyStateValues == null) {
                callMethod(arrowView, "onSwipeStop", offset, animatorListener);
            } else {
                Object convertedOffset = convertBackOffset(offset);
                callMethod(arrowView, "onSwipeStop", convertedOffset, animatorListener);
            }
        } catch (Throwable ignored) {
            Object convertedOffset = convertBackOffset(offset);
            callMethod(arrowView, "onActionUp", convertedOffset, animatorListener);
        }
    }

    private Object convertBackOffset(float offset) throws Throwable {
        Object backGestureUtils = getStaticObjectField(findClass(CLASS_BACK_GESTURE_UTILS, mClassLoader), "INSTANCE");
        return callMethod(backGestureUtils, "convertOffset", offset);
    }

    private Method findDeclaredMethod(String className, String methodName, Class<?>... parameterTypes) {
        try {
            Method method = findClass(className, mClassLoader).getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void log(String message) {
        XposedBridge.log("[QuickBack] " + message);
    }

    private void logClassMethods(String className, String keywordRegex) {
        try {
            Class<?> clazz = findClass(className, mClassLoader);
            String regex = "(?i).*(" + keywordRegex + ").*";
            for (Method method : clazz.getDeclaredMethods()) {
                if (method.getName().matches(regex)) {
                    log("candidate method: " + className + "#" + method);
                }
            }
        } catch (Throwable e) {
            log("logClassMethods failed for " + className + ": " + e.getMessage());
        }
    }

    private interface ThrowingRunnable {
        void run() throws Throwable;
    }
}
