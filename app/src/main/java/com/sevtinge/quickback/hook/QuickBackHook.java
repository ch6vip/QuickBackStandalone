package com.sevtinge.quickback.hook;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import com.sevtinge.quickback.QuickBackSettingsProvider;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

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
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }

        try {
            mClassLoader = param.getClassLoader();
            initReadyStateValues();
            installHook("hookDisableQuickSwitch", findDeclaredMethod(CLASS_GESTURE_STUB_VIEW, "isDisableQuickSwitch"),
                chain -> false);
            installHook("hookLoadRecentTaskIcon", findDeclaredMethod(CLASS_GESTURE_BACK_ARROW_VIEW, "loadRecentTaskIcon"),
                chain -> {
                    Context context = (Context) invokeMethod(chain.getThisObject(), "getContext");
                    if (!isEnabled(context)) {
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
                    synchronized (mSwipeStartTimes) {
                        mSwipeStartTimes.put(chain.getThisObject(), SystemClock.uptimeMillis());
                    }
                    return chain.proceed();
                });
            installHook("hookOnSwipeStop", findDeclaredMethod(CLASS_GESTURE_STUB_CALLBACK, "onSwipeStop", boolean.class, float.class, boolean.class),
                chain -> {
                    boolean isFinish = (boolean) chain.getArg(0);
                    if (!isFinish) {
                        return chain.proceed();
                    }

                    try {
                        if (isLegacyRecentState(chain.getThisObject()) || isModernQuickBackGesture(chain)) {
                            handleRecentSwipeStop(chain);
                            return null;
                        }
                    } catch (Throwable e) {
                        log("onSwipeStop: failed " + e.getClass().getSimpleName());
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
            log(name + ": skipped, method missing");
            return;
        }
        try {
            hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(hooker);
            log(name + ": installed");
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
        } catch (Throwable e) {
            log("initReadyStateValues: failed " + e.getClass().getSimpleName());
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

    private boolean isModernQuickBackGesture(XposedInterface.Chain chain) throws Throwable {
        if (mReadyStateValues != null) {
            return false;
        }

        Long startTime;
        synchronized (mSwipeStartTimes) {
            startTime = mSwipeStartTimes.remove(chain.getThisObject());
        }
        if (startTime == null) {
            return false;
        }

        long duration = SystemClock.uptimeMillis() - startTime;
        float offset = (float) chain.getArg(1);
        boolean shouldHandle = duration >= MODERN_QUICK_BACK_HOLD_MS
            && offset >= MODERN_QUICK_BACK_MIN_OFFSET;
        if (shouldHandle) {
            log("modern quick back gesture: duration=" + duration + ", offset=" + offset);
        }
        return shouldHandle;
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
            log("findNextTask: runningTask is null");
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
            log("getNextTaskFromStack: running task has no next task");
        } else {
            log("getNextTaskFromStack: running task not found in stack");
        }

        if (runningTask.baseActivity != null && TARGET_PACKAGE.equals(runningTask.baseActivity.getPackageName())) {
            return stackTasks.get(0);
        }

        return null;
    }

    private void handleRecentSwipeStop(XposedInterface.Chain chain) throws Throwable {
        Object swipeCallback = chain.getThisObject();
        Object gestureStubView = getFieldValue(swipeCallback, "this$0");
        Object arrowView = getFieldValue(gestureStubView, "mGestureBackArrowView");
        Context context = (Context) getFieldValue(gestureStubView, "mContext");
        if (!isEnabled(context)) {
            return;
        }
        int gestureStubPos = (int) getFieldValue(gestureStubView, "mGestureStubPos");

        invokeMethod(gestureStubView, "onBackCancelled");

        if (isNextTaskSupported(gestureStubView)) {
            Object task = findNextTask(context);
            if (task != null && startTaskFromRecents(context, task, gestureStubPos)) {
                log("handleRecentSwipeStop: task started");
                finishSwipeStop(gestureStubView, arrowView, (float) chain.getArg(1));
                return;
            }
            log("handleRecentSwipeStop: no task started");
        }

        vibrateQuickBackFail(gestureStubView);
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

        int windowingMode = (int) getFieldValue(getFieldValue(task, "key"), "windowingMode");
        if (windowingMode == 3) {
            if (options == null) {
                options = ActivityOptions.makeBasic();
            }
            invokeMethod(options, "setLaunchWindowingMode", 4);
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
        if (!isEnabled(context)) {
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
        if (!isEnabled(context)) {
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
            invokeMethod(vibrator, "vibrate", 100L);
        }
        log("vibrateQuickBackFail");
    }

    private void finishSwipeStop(Object gestureStubView, Object arrowView, float offset) throws Throwable {
        setFieldValue(gestureStubView, "mIsGestureStarted", false);

        Object handler = getFieldValue(gestureStubView, "mHandler");
        Object resetMessage = invokeMethod(handler, "obtainMessage", 258);
        invokeMethod(handler, "sendMessageDelayed", resetMessage, 500L);
        invokeMethod(handler, "removeMessages", 261);

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

    private boolean isEnabled(Context context) {
        if (context == null) {
            return false;
        }
        try {
            Bundle result = context.getContentResolver().call(
                QuickBackSettingsProvider.URI,
                QuickBackSettingsProvider.METHOD_GET_ENABLED,
                null,
                null
            );
            if (result != null && result.containsKey(QuickBackSettingsProvider.EXTRA_ENABLED)) {
                boolean enabled = result.getBoolean(QuickBackSettingsProvider.EXTRA_ENABLED, false);
                log("isEnabled: provider=" + enabled);
                return enabled;
            }
        } catch (Throwable e) {
            log("isEnabled: provider read failed: " + e.getClass().getSimpleName());
        }
        return false;
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
        Method method = clazz.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
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
        Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private Method findCompatibleMethod(Class<?> clazz, String methodName, Object... args) {
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
                    return method;
                }
            }
        }
        return null;
    }

    private Object getFieldValue(Object target, String fieldName) throws Throwable {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private void setFieldValue(Object target, String fieldName, Object value) throws Throwable {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Object getStaticFieldValue(Class<?> clazz, String fieldName) throws Throwable {
        Field field = findField(clazz, fieldName);
        field.setAccessible(true);
        return field.get(null);
    }

    private Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        for (Class<?> current = clazz; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(clazz.getName() + "#" + fieldName);
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

}
