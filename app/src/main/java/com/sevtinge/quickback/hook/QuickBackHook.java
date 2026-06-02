package com.sevtinge.quickback.hook;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import com.sevtinge.quickback.Prefs;
import com.sevtinge.quickback.QuickBackSettingsProvider;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

public final class QuickBackHook extends XposedModule {

    private static final String TAG = "QuickBack";
    private static final String TARGET_PACKAGE = "com.miui.home";
    private static final String CLASS_GESTURE_STUB_VIEW = "com.miui.home.recents.GestureStubView";
    private static final String CLASS_GESTURE_STUB_CALLBACK = "com.miui.home.recents.GestureStubView$3";
    private static final String CLASS_GESTURE_BACK_ARROW_VIEW = "com.miui.home.recents.GestureBackArrowView";
    private static final String CLASS_READY_STATE = "com.miui.home.recents.GestureBackArrowView$ReadyState";
    private static final String CLASS_RECENTS_MODEL = "com.miui.home.recents.RecentsModel";
    private static final String CLASS_ACTIVITY_MANAGER_WRAPPER = "com.android.systemui.shared.recents.system.ActivityManagerWrapper";
    private static final String CLASS_BACK_GESTURE_UTILS = "com.android.systemui.fsgesture.BackGestureUtils";
    private static final long MODERN_QUICK_BACK_CONSERVATIVE_HOLD_MS = 850L;
    private static final long MODERN_QUICK_BACK_STANDARD_HOLD_MS = 700L;
    private static final long MODERN_QUICK_BACK_SENSITIVE_HOLD_MS = 600L;
    private static final long MODERN_QUICK_BACK_MAX_HOLD_MS = 1800L;
    private static final float MODERN_QUICK_BACK_CONSERVATIVE_MIN_OFFSET = 340.0f;
    private static final float MODERN_QUICK_BACK_STANDARD_MIN_OFFSET = 300.0f;
    private static final float MODERN_QUICK_BACK_SENSITIVE_MIN_OFFSET = 280.0f;
    private static final long SETTINGS_CACHE_TTL_MS = 1000L;
    private static final long DEBUG_LOG_INTERVAL_MS = 5000L;
    private static final long QUICK_BACK_FAIL_VIBRATE_MS = 100L;
    private static final long GESTURE_RESET_DELAY_MS = 500L;
    private static final int GESTURE_POS_LEFT = 0;
    private static final int GESTURE_POS_RIGHT = 1;
    private static final int MSG_RESET_GESTURE = 258;
    private static final int MSG_CANCEL_ANIMATION = 261;
    private static final int MIUI_FLOATING_WINDOW_MODE = 3;
    private static final int MIUI_QUICK_BACK_LAUNCH_WINDOWING_MODE = 4;

    private static final int STATE_BACK = 1;
    private static final int STATE_RECENT = 2;
    private static final int STATE_NONE = 3;

    private final Map<String, Integer> mAnimResCache = new ConcurrentHashMap<>();
    private final Map<String, Method> mMethodCache = new ConcurrentHashMap<>();
    private final Map<String, Field> mFieldCache = new ConcurrentHashMap<>();
    private final Map<Object, Long> mSwipeStartTimes = new WeakHashMap<>();
    private ClassLoader mClassLoader;
    private int[] mReadyStateValues;
    private Method mStartTaskFromRecentsByIdMethod;
    private Method mStartTaskFromRecentsByKeyMethod;
    private long mSettingsCacheTime;
    private QuickBackSettings mSettingsCache = QuickBackSettings.DISABLED;
    private final Map<String, Long> mLastDebugLogTimes = new ConcurrentHashMap<>();

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }

        try {
            mClassLoader = param.getClassLoader();
            initReadyStateValues();
            installHook("hookDisableQuickSwitch", findDeclaredMethod(CLASS_GESTURE_STUB_VIEW, "isDisableQuickSwitch"),
                chain -> {
                    Context context = (Context) invokeMethod(chain.getThisObject(), "getContext");
                    return getSettings(context).enabled ? false : chain.proceed();
                });
            installHook("hookLoadRecentTaskIcon", findDeclaredMethod(CLASS_GESTURE_BACK_ARROW_VIEW, "loadRecentTaskIcon"),
                chain -> {
                    Context context = (Context) invokeMethod(chain.getThisObject(), "getContext");
                    if (!getSettings(context).enabled) {
                        return chain.proceed();
                    }

                    if (!isNextTaskSupportedFromArrowView(chain.getThisObject())) {
                        return getFieldValue(chain.getThisObject(), "mNoneTaskIcon");
                    }

                    Object task = findNextTask(context);
                    if (task == null) {
                        return getFieldValue(chain.getThisObject(), "mNoneTaskIcon");
                    }

                    loadTaskIconIfNeeded(context, task);
                    Object icon = getFieldValue(task, "icon");
                    return icon != null ? icon : getFieldValue(chain.getThisObject(), "mNoneTaskIcon");
                });
            installHook("hookOnSwipeStart", findDeclaredMethod(CLASS_GESTURE_STUB_CALLBACK, "onSwipeStart", float.class),
                chain -> {
                    if (mReadyStateValues == null) {
                        synchronized (mSwipeStartTimes) {
                            mSwipeStartTimes.put(chain.getThisObject(), SystemClock.uptimeMillis());
                        }
                    }
                    return chain.proceed();
                });
            installHook("hookOnSwipeStop", findDeclaredMethod(CLASS_GESTURE_STUB_CALLBACK, "onSwipeStop", boolean.class, float.class, boolean.class),
                chain -> {
                    boolean isFinish = (boolean) chain.getArg(0);
                    Long swipeStartTime = consumeSwipeStartTime(chain.getThisObject());
                    if (!isFinish) {
                        return chain.proceed();
                    }

                    try {
                        Context context = getContextFromSwipeCallback(chain.getThisObject());
                        QuickBackSettings settings = getSettings(context);
                        if (!settings.enabled) {
                            return chain.proceed();
                        }
                        if (isLegacyRecentState(chain.getThisObject()) || isModernQuickBackGesture(chain, swipeStartTime, settings)) {
                            handleRecentSwipeStop(chain, settings);
                            return null;
                        }
                    } catch (Throwable e) {
                        logDebugLimited("hookOnSwipeStop: detection failed: " + simpleError(e));
                    }
                    return chain.proceed();
                });
            log("handleLoadPackage: hooks installed");
        } catch (Throwable e) {
            log("handleLoadPackage: failed " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void installHook(String name, Method method, XposedInterface.Hooker hooker) {
        if (method == null) {
            return;
        }
        try {
            hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(hooker);
        } catch (Throwable e) {
            log(name + ": failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void initReadyStateValues() {
        try {
            Class<?> readyStateClass = findClass(CLASS_READY_STATE);
            Object[] enumValues = readyStateClass.getEnumConstants();
            mReadyStateValues = new int[enumValues.length];
            mReadyStateValues[getEnumOrdinal(readyStateClass, "READY_STATE_BACK")] = STATE_BACK;
            mReadyStateValues[getEnumOrdinal(readyStateClass, "READY_STATE_RECENT")] = STATE_RECENT;
            mReadyStateValues[getEnumOrdinal(readyStateClass, "READY_STATE_NONE")] = STATE_NONE;
        } catch (Throwable ignored) {
        }
    }

    private int getEnumOrdinal(Class<?> enumClass, String name) throws Throwable {
        Enum<?> value = (Enum<?>) getStaticFieldValue(enumClass, name);
        return value.ordinal();
    }

    private boolean isLegacyRecentState(Object swipeCallback) throws Throwable {
        if (mReadyStateValues == null) {
            return false;
        }
        return mapOrdinalToState(getCurrentStateOrdinal(swipeCallback)) == STATE_RECENT;
    }

    private Context getContextFromSwipeCallback(Object swipeCallback) throws Throwable {
        Object gestureStubView = getFieldValue(swipeCallback, "this$0");
        return (Context) getFieldValue(gestureStubView, "mContext");
    }

    private Long consumeSwipeStartTime(Object swipeCallback) {
        synchronized (mSwipeStartTimes) {
            return mSwipeStartTimes.remove(swipeCallback);
        }
    }

    private boolean isModernQuickBackGesture(XposedInterface.Chain chain, Long startTime,
                                             QuickBackSettings settings) throws Throwable {
        if (mReadyStateValues != null) {
            return false;
        }
        if (startTime == null) {
            return false;
        }

        long duration = SystemClock.uptimeMillis() - startTime;
        float offset = (float) chain.getArg(1);
        return duration >= settings.holdMs
            && duration <= MODERN_QUICK_BACK_MAX_HOLD_MS
            && offset >= settings.minOffset;
    }

    private int getCurrentStateOrdinal(Object swipeCallback) throws Throwable {
        Object gestureStubView = getFieldValue(swipeCallback, "this$0");
        Object arrowView = getFieldValue(gestureStubView, "mGestureBackArrowView");
        Object currentState = invokeMethod(arrowView, "getCurrentState");
        return (int) invokeMethod(currentState, "ordinal");
    }

    private int mapOrdinalToState(int ordinal) {
        if (mReadyStateValues != null && ordinal >= 0 && ordinal < mReadyStateValues.length) {
            return mReadyStateValues[ordinal];
        }
        try {
            Class<?> switchMapClass = findSwitchMapClass();
            if (switchMapClass != null) {
                int[] switchMap = (int[]) getStaticFieldValue(switchMapClass,
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
            return findClass("com.miui.home.recents.GestureStubView$4");
        } catch (Throwable ignored) {
            try {
                return findClass("com.miui.home.recents.GestureStubView$5");
            } catch (Throwable ignoredAgain) {
                return null;
            }
        }
    }

    private Object findNextTask(Context context) throws Throwable {
        Object recentsModel = invokeStaticMethod(findClass(CLASS_RECENTS_MODEL), "getInstance", context);
        ActivityManager.RunningTaskInfo runningTask = getRunningTaskForQuickBack(recentsModel);
        if (runningTask == null) {
            return null;
        }

        int runningTaskId = getRunningTaskId(runningTask);
        Object task = findNextTaskFromTaskList(recentsModel, runningTask, runningTaskId);
        if (task != null) {
            return task;
        }

        try {
            Object loadPlan = invokeMethod(recentsModel, "getSmartRecentsTaskLoadPlan", context, runningTaskId);
            Object taskStack = loadPlan != null ? invokeMethod(loadPlan, "getTaskStack") : null;
            if (taskStack == null || (int) invokeMethod(taskStack, "getTaskCount") == 0) {
                return null;
            }
            return getNextTaskFromStack(taskStack, runningTask, runningTaskId);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object findNextTaskFromTaskList(Object recentsModel, ActivityManager.RunningTaskInfo runningTask,
                                            int runningTaskId) throws Throwable {
        ArrayList<?> tasks = getTaskList(recentsModel);
        if (tasks == null || tasks.isEmpty()) {
            return null;
        }

        int runningTaskIndex = findTaskIndex(tasks, runningTaskId);
        if (runningTaskIndex >= 0 && runningTaskIndex + 1 < tasks.size()) {
            return tasks.get(runningTaskIndex + 1);
        }

        if (runningTask.baseActivity != null && TARGET_PACKAGE.equals(runningTask.baseActivity.getPackageName())) {
            return tasks.get(0);
        }

        return null;
    }

    private ArrayList<?> getTaskList(Object recentsModel) throws Throwable {
        try {
            return new ArrayList<>((List<?>) invokeMethod(recentsModel, "getTaskList", false));
        } catch (Throwable ignored) {
            try {
                return new ArrayList<>((List<?>) invokeMethod(recentsModel, "getTaskList", true));
            } catch (Throwable ignoredAgain) {
                return null;
            }
        }
    }

    private int findTaskIndex(ArrayList<?> tasks, int taskId) {
        for (int i = 0; i < tasks.size(); i++) {
            try {
                if ((boolean) invokeMethod(tasks.get(i), "isSameTaskFromId", taskId)) {
                    return i;
                }
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    private ActivityManager.RunningTaskInfo getRunningTaskForQuickBack(Object recentsModel) throws Throwable {
        try {
            return (ActivityManager.RunningTaskInfo) invokeMethod(recentsModel, "getRunningTaskForGesture", true);
        } catch (Throwable ignored) {
            return (ActivityManager.RunningTaskInfo) invokeMethod(recentsModel, "getRunningTask");
        }
    }

    private Object getNextTaskFromStack(Object taskStack, ActivityManager.RunningTaskInfo runningTask, int runningTaskId) throws Throwable {
        ArrayList<?> stackTasks = (ArrayList<?>) invokeMethod(taskStack, "getStackTasks");
        if (stackTasks == null || stackTasks.isEmpty()) {
            return null;
        }

        Object runningTaskInStack = invokeMethod(taskStack, "findTaskWithId", runningTaskId);
        if (runningTaskInStack != null) {
            int runningTaskIndex = (int) invokeMethod(taskStack, "indexOfStackTask", runningTaskInStack);
            if (runningTaskIndex >= 0 && runningTaskIndex + 1 < stackTasks.size()) {
                return stackTasks.get(runningTaskIndex + 1);
            }
        }

        if (runningTask.baseActivity != null && TARGET_PACKAGE.equals(runningTask.baseActivity.getPackageName())) {
            return stackTasks.get(0);
        }

        return null;
    }

    private void handleRecentSwipeStop(XposedInterface.Chain chain, QuickBackSettings settings) throws Throwable {
        Object swipeCallback = chain.getThisObject();
        Object gestureStubView = getFieldValue(swipeCallback, "this$0");
        Object arrowView = getFieldValue(gestureStubView, "mGestureBackArrowView");
        Context context = (Context) getFieldValue(gestureStubView, "mContext");
        if (!settings.enabled) {
            return;
        }
        int gestureStubPos = (int) getFieldValue(gestureStubView, "mGestureStubPos");

        invokeMethod(gestureStubView, "onBackCancelled");

        boolean shouldVibrateOnFail = false;
        if (isNextTaskSupported(gestureStubView)) {
            Object task = findNextTask(context);
            if (task == null) {
                logDebugLimited("handleRecentSwipeStop: no next task");
            } else if (startTaskFromRecents(context, task, gestureStubPos)) {
                log("handleRecentSwipeStop: task started");
                finishSwipeStop(gestureStubView, arrowView, (float) chain.getArg(1));
                return;
            } else {
                shouldVibrateOnFail = true;
            }
        } else {
            logDebugLimited("handleRecentSwipeStop: next task unsupported");
        }

        if (shouldVibrateOnFail) {
            vibrateQuickBackFail(gestureStubView);
        }
        finishSwipeStop(gestureStubView, arrowView, (float) chain.getArg(1));
    }

    private void loadTaskIconIfNeeded(Context context, Object task) throws Throwable {
        if (getFieldValue(task, "icon") != null) {
            return;
        }

        Object recentsModel = invokeStaticMethod(findClass(CLASS_RECENTS_MODEL), "getInstance", context);
        Object taskLoader = invokeMethod(recentsModel, "getTaskLoader");
        Object taskKey = getFieldValue(task, "key");
        Object taskDescription = getFieldValue(task, "taskDescription");
        Object icon = invokeMethod(taskLoader, "getAndUpdateActivityIcon",
            taskKey,
            taskDescription,
            context.getResources(),
            true);
        setFieldValue(task, "icon", icon);
    }

    private boolean startTaskFromRecents(Context context, Object task, int gestureStubPos) throws Throwable {
        ActivityOptions options = createActivityOptions(context, task, gestureStubPos);
        Object taskKey = getFieldValue(task, "key");
        int taskId = (int) getFieldValue(taskKey, "id");
        Object wrapper = invokeStaticMethod(findClass(CLASS_ACTIVITY_MANAGER_WRAPPER), "getInstance");
        if (wrapper == null) {
            logDebugLimited("startTaskFromRecents: ActivityManagerWrapper is null");
            return false;
        }

        try {
            if (mStartTaskFromRecentsByIdMethod == null) {
                mStartTaskFromRecentsByIdMethod = wrapper.getClass().getMethod(
                    "startActivityFromRecents", Integer.TYPE, ActivityOptions.class);
                mStartTaskFromRecentsByIdMethod.setAccessible(true);
            }
            Object started = mStartTaskFromRecentsByIdMethod.invoke(wrapper, taskId, options);
            if (!(started instanceof Boolean) || (Boolean) started) {
                return true;
            }
            logDebugLimited("startTaskFromRecents: taskId launch returned false, taskId=" + taskId);
        } catch (Throwable e) {
            logDebugLimited("startTaskFromRecents: taskId launch failed: " + simpleError(e));
        }

        try {
            if (mStartTaskFromRecentsByKeyMethod == null) {
                mStartTaskFromRecentsByKeyMethod = wrapper.getClass().getMethod(
                    "startActivityFromRecents", taskKey.getClass(), ActivityOptions.class);
                mStartTaskFromRecentsByKeyMethod.setAccessible(true);
            }
            mStartTaskFromRecentsByKeyMethod.invoke(wrapper, taskKey, options);
            return true;
        } catch (Throwable keyError) {
            logDebugLimited("startTaskFromRecents: taskKey launch failed: " + simpleError(keyError));
            return false;
        }
    }

    private ActivityOptions createActivityOptions(Context context, Object task, int gestureStubPos) throws Throwable {
        ActivityOptions options = null;
        if (gestureStubPos == GESTURE_POS_LEFT) {
            options = createCustomActivityOptions(context,
                "recents_quick_switch_left_enter",
                "recents_quick_switch_left_exit");
        } else if (gestureStubPos == GESTURE_POS_RIGHT) {
            options = createCustomActivityOptions(context,
                "recents_quick_switch_right_enter",
                "recents_quick_switch_right_exit");
        }

        int windowingMode = (int) getFieldValue(getFieldValue(task, "key"), "windowingMode");
        if (windowingMode == MIUI_FLOATING_WINDOW_MODE) {
            if (options == null) {
                options = ActivityOptions.makeBasic();
            }
            invokeMethod(options, "setLaunchWindowingMode", MIUI_QUICK_BACK_LAUNCH_WINDOWING_MODE);
        }

        return options;
    }

    private ActivityOptions createCustomActivityOptions(Context context, String enterAnimName, String exitAnimName) {
        int enterAnim = getAnimResId(context, enterAnimName);
        int exitAnim = getAnimResId(context, exitAnimName);
        if (enterAnim != 0 && exitAnim != 0) {
            return ActivityOptions.makeCustomAnimation(context, enterAnim, exitAnim);
        }

        logDebugLimited("createActivityOptions: missing animation resources: "
            + enterAnimName + "=" + enterAnim + ", " + exitAnimName + "=" + exitAnim);
        return ActivityOptions.makeBasic();
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

    private int getRunningTaskId(ActivityManager.RunningTaskInfo runningTask) throws Throwable {
        try {
            Object wrapper = invokeStaticMethod(findClass(CLASS_ACTIVITY_MANAGER_WRAPPER), "getInstance");
            if (wrapper != null) {
                Object result = invokeMethod(wrapper, "getTaskId", runningTask);
                if (result instanceof Integer) {
                    return (Integer) result;
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            return (int) getFieldValue(runningTask, "taskId");
        } catch (Throwable ignored) {
            return runningTask.id;
        }
    }

    private boolean isNextTaskSupported(Object gestureStubView) throws Throwable {
        Context context = (Context) getFieldValue(gestureStubView, "mContext");
        if (!getSettings(context).enabled) {
            return false;
        }
        Object contentResolver = getFieldValue(gestureStubView, "mContentResolver");
        try {
            return (boolean) invokeStaticMethod(findClass(CLASS_GESTURE_STUB_VIEW), "supportNextTask", contentResolver);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private boolean isNextTaskSupportedFromArrowView(Object arrowView) throws Throwable {
        Context context = (Context) invokeMethod(arrowView, "getContext");
        if (!getSettings(context).enabled) {
            return false;
        }
        Object contentResolver = getFieldValue(arrowView, "mContentResolver");
        try {
            return (boolean) invokeStaticMethod(findClass(CLASS_GESTURE_STUB_VIEW), "supportNextTask", contentResolver);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private void vibrateQuickBackFail(Object gestureStubView) throws Throwable {
        Object vibrator = getFieldValue(gestureStubView, "mVibrator");
        if (vibrator != null) {
            invokeMethod(vibrator, "vibrate", QUICK_BACK_FAIL_VIBRATE_MS);
        }
        log("vibrateQuickBackFail");
    }

    private void finishSwipeStop(Object gestureStubView, Object arrowView, float offset) throws Throwable {
        setFieldValue(gestureStubView, "mIsGestureStarted", false);

        Object handler = getFieldValue(gestureStubView, "mHandler");
        Object resetMessage = invokeMethod(handler, "obtainMessage", MSG_RESET_GESTURE);
        invokeMethod(handler, "sendMessageDelayed", resetMessage, GESTURE_RESET_DELAY_MS);
        invokeMethod(handler, "removeMessages", MSG_CANCEL_ANIMATION);

        Object animatorListener = getFieldValue(gestureStubView, "mAnimatorListener");
        try {
            if (mReadyStateValues == null) {
                invokeMethod(arrowView, "onSwipeStop", offset, animatorListener);
            } else {
                Object convertedOffset = convertBackOffset(offset);
                invokeMethod(arrowView, "onSwipeStop", convertedOffset, animatorListener);
            }
        } catch (Throwable ignored) {
            Object convertedOffset = convertBackOffset(offset);
            invokeMethod(arrowView, "onActionUp", convertedOffset, animatorListener);
        }
    }

    private Object convertBackOffset(float offset) throws Throwable {
        Object backGestureUtils = getStaticFieldValue(findClass(CLASS_BACK_GESTURE_UTILS), "INSTANCE");
        return invokeMethod(backGestureUtils, "convertOffset", offset);
    }

    private QuickBackSettings getSettings(Context context) {
        if (context == null) {
            return QuickBackSettings.DISABLED;
        }

        long now = SystemClock.uptimeMillis();
        if (now - mSettingsCacheTime < SETTINGS_CACHE_TTL_MS) {
            return mSettingsCache;
        }

        try {
            Bundle result = context.getContentResolver().call(
                QuickBackSettingsProvider.URI,
                QuickBackSettingsProvider.METHOD_GET_ENABLED,
                null,
                null
            );
            if (result != null && result.containsKey(QuickBackSettingsProvider.EXTRA_ENABLED)) {
                mSettingsCache = QuickBackSettings.from(
                    result.getBoolean(QuickBackSettingsProvider.EXTRA_ENABLED, false),
                    result.getInt(QuickBackSettingsProvider.EXTRA_SENSITIVITY, Prefs.SENSITIVITY_STANDARD));
                mSettingsCacheTime = now;
                return mSettingsCache;
            }
        } catch (Throwable e) {
            logDebugLimited("getSettings: provider read failed: " + simpleError(e));
        }
        mSettingsCache = QuickBackSettings.DISABLED;
        mSettingsCacheTime = now;
        return mSettingsCache;
    }

    private Class<?> findClass(String name) throws ClassNotFoundException {
        return Class.forName(name, false, mClassLoader);
    }

    private Method findDeclaredMethod(String className, String methodName, Class<?>... parameterTypes) {
        try {
            Method method = findClass(className).getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object invokeStaticMethod(Class<?> clazz, String methodName, Class<?>[] parameterTypes, Object... args) throws Throwable {
        Method method = findDeclaredMethod(clazz, methodName, parameterTypes);
        return method.invoke(null, args);
    }

    private Object invokeStaticMethod(Class<?> clazz, String methodName, Object... args) throws Throwable {
        Method method = findCompatibleMethod(clazz, methodName, args);
        if (method == null) {
            throw new NoSuchMethodException(clazz.getName() + "#" + methodName);
        }
        method.setAccessible(true);
        return method.invoke(null, args);
    }

    private Object invokeMethod(Object target, String methodName, Object... args) throws Throwable {
        Method method = findCompatibleMethod(target.getClass(), methodName, args);
        if (method == null) {
            throw new NoSuchMethodException(target.getClass().getName() + "#" + methodName);
        }
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private Object invokeMethod(Object target, String methodName, Class<?>[] parameterTypes, Object... args) throws Throwable {
        Method method = findDeclaredMethod(target.getClass(), methodName, parameterTypes);
        return method.invoke(target, args);
    }

    private Method findDeclaredMethod(Class<?> clazz, String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        String cacheKey = buildDeclaredMethodKey(clazz, methodName, parameterTypes);
        Method cached = mMethodCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        Method method = clazz.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        mMethodCache.put(cacheKey, method);
        return method;
    }

    private Method findCompatibleMethod(Class<?> clazz, String methodName, Object... args) {
        String cacheKey = buildCompatibleMethodKey(clazz, methodName, args);
        Method cached = mMethodCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        for (Class<?> current = clazz; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(methodName) || method.getParameterCount() != args.length) {
                    continue;
                }
                Class<?>[] types = method.getParameterTypes();
                boolean match = true;
                for (int i = 0; i < types.length; i++) {
                    if (args[i] != null && !wrap(types[i]).isAssignableFrom(args[i].getClass())) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    method.setAccessible(true);
                    mMethodCache.put(cacheKey, method);
                    return method;
                }
            }
        }
        return null;
    }

    private Object getFieldValue(Object target, String fieldName) throws Throwable {
        Field field = findField(target.getClass(), fieldName);
        return field.get(target);
    }

    private void setFieldValue(Object target, String fieldName, Object value) throws Throwable {
        Field field = findField(target.getClass(), fieldName);
        field.set(target, value);
    }

    private Object getStaticFieldValue(Class<?> clazz, String fieldName) throws Throwable {
        Field field = findField(clazz, fieldName);
        return field.get(null);
    }

    private Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        String cacheKey = clazz.getName() + "#" + fieldName;
        Field cached = mFieldCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        for (Class<?> current = clazz; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                mFieldCache.put(cacheKey, field);
                return field;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(clazz.getName() + "#" + fieldName);
    }

    private String buildDeclaredMethodKey(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        StringBuilder builder = new StringBuilder(clazz.getName())
            .append('#')
            .append(methodName)
            .append('(');
        for (Class<?> parameterType : parameterTypes) {
            builder.append(parameterType.getName()).append(',');
        }
        return builder.append(')').toString();
    }

    private String buildCompatibleMethodKey(Class<?> clazz, String methodName, Object... args) {
        StringBuilder builder = new StringBuilder(clazz.getName())
            .append('#')
            .append(methodName)
            .append('(');
        for (Object arg : args) {
            builder.append(arg == null ? "null" : arg.getClass().getName()).append(',');
        }
        return builder.append(')').toString();
    }

    private Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) return Integer.class;
        if (type == boolean.class) return Boolean.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return type;
    }

    private void log(String message) {
        log(Log.INFO, TAG, message);
    }

    private void logDebugLimited(String message) {
        long now = SystemClock.uptimeMillis();
        Long lastLogTime = mLastDebugLogTimes.get(message);
        if (lastLogTime != null && now - lastLogTime < DEBUG_LOG_INTERVAL_MS) {
            return;
        }
        mLastDebugLogTimes.put(message, now);
        log(message);
    }

    private String simpleError(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.isEmpty()) {
            return e.getClass().getSimpleName();
        }
        return e.getClass().getSimpleName() + ": " + message;
    }

    private static final class QuickBackSettings {

        private static final QuickBackSettings DISABLED = new QuickBackSettings(
            false,
            MODERN_QUICK_BACK_STANDARD_HOLD_MS,
            MODERN_QUICK_BACK_STANDARD_MIN_OFFSET);

        final boolean enabled;
        final long holdMs;
        final float minOffset;

        private QuickBackSettings(boolean enabled, long holdMs, float minOffset) {
            this.enabled = enabled;
            this.holdMs = holdMs;
            this.minOffset = minOffset;
        }

        static QuickBackSettings from(boolean enabled, int sensitivity) {
            if (!enabled) {
                return DISABLED;
            }
            if (sensitivity == Prefs.SENSITIVITY_CONSERVATIVE) {
                return new QuickBackSettings(
                    true,
                    MODERN_QUICK_BACK_CONSERVATIVE_HOLD_MS,
                    MODERN_QUICK_BACK_CONSERVATIVE_MIN_OFFSET);
            }
            if (sensitivity == Prefs.SENSITIVITY_SENSITIVE) {
                return new QuickBackSettings(
                    true,
                    MODERN_QUICK_BACK_SENSITIVE_HOLD_MS,
                    MODERN_QUICK_BACK_SENSITIVE_MIN_OFFSET);
            }
            return new QuickBackSettings(
                true,
                MODERN_QUICK_BACK_STANDARD_HOLD_MS,
                MODERN_QUICK_BACK_STANDARD_MIN_OFFSET);
        }
    }

}
