package com.sevtinge.quickback.hook;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import com.sevtinge.quickback.BuildConfig;
import com.sevtinge.quickback.Prefs;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

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

    private static final int STATE_BACK = 1;
    private static final int STATE_RECENT = 2;
    private static final int STATE_NONE = 3;

    private final Map<String, Integer> mAnimResCache = new HashMap<>();
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
        hookDisableQuickSwitch();
        hookLoadRecentTaskIcon();
        hookOnSwipeStop();
        log("handleLoadPackage: hooks installed");
    }

    private boolean isEnabled() {
        try {
            XSharedPreferences prefs = new XSharedPreferences(BuildConfig.APPLICATION_ID, Prefs.FILE_NAME);
            prefs.reload();
            log("isEnabled: fileCanRead=" + prefs.getFile().canRead());
            return prefs.getBoolean(Prefs.KEY_ENABLED, false);
        } catch (Throwable ignored) {
            log("isEnabled: failed to read prefs");
            return false;
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
        findAndHookMethod(CLASS_GESTURE_STUB_VIEW, mClassLoader, "isDisableQuickSwitch",
            XC_MethodReplacement.returnConstant(false));
        log("hookDisableQuickSwitch: installed");
    }

    private void hookLoadRecentTaskIcon() {
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
                        int state = mapOrdinalToState(getCurrentStateOrdinal(param.thisObject));
                        if (state == STATE_RECENT) {
                            handleRecentSwipeStop(param);
                        }
                    } catch (Throwable ignored) {
                        log("onSwipeStop: failed");
                    }
                }
            });
        log("hookOnSwipeStop: installed");
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
        Object loadPlan = callMethod(recentsModel, "getSmartRecentsTaskLoadPlan", context, runningTaskId);
        Object taskStack = loadPlan != null ? callMethod(loadPlan, "getTaskStack") : null;
        if (taskStack == null || (int) callMethod(taskStack, "getTaskCount") == 0) {
            log("findNextTask: taskStack is empty");
            return null;
        }

        return getNextTaskFromStack(taskStack, runningTask, runningTaskId);
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
        return (boolean) callStaticMethod(findClass(CLASS_GESTURE_STUB_VIEW, mClassLoader), "supportNextTask", contentResolver);
    }

    private boolean isNextTaskSupportedFromArrowView(Object arrowView) throws Throwable {
        Object contentResolver = getObjectField(arrowView, "mContentResolver");
        return (boolean) callStaticMethod(findClass(CLASS_GESTURE_STUB_VIEW, mClassLoader), "supportNextTask", contentResolver);
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
        Object backGestureUtils = getStaticObjectField(findClass(CLASS_BACK_GESTURE_UTILS, mClassLoader), "INSTANCE");
        Object convertedOffset = callMethod(backGestureUtils, "convertOffset", offset);
        try {
            callMethod(arrowView, "onSwipeStop", convertedOffset, animatorListener);
        } catch (Throwable ignored) {
            callMethod(arrowView, "onActionUp", convertedOffset, animatorListener);
        }
    }

    private void log(String message) {
        XposedBridge.log("[QuickBack] " + message);
    }
}
