package com.sevtinge.quickback;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Binder;
import android.os.Process;

public final class QuickBackSettingsProvider extends ContentProvider {

    public static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".settings";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY);
    public static final String METHOD_GET_ENABLED = "get_enabled";
    public static final String EXTRA_ENABLED = "enabled";
    public static final String EXTRA_SENSITIVITY = "sensitivity";
    private static final String TARGET_PACKAGE = "com.miui.home";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle result = new Bundle();
        if (METHOD_GET_ENABLED.equals(method)) {
            if (!isAllowedCaller()) {
                throw new SecurityException("Caller is not allowed to read QuickBack settings");
            }
            SharedPreferences prefs = requireProviderContext().getSharedPreferences(Prefs.FILE_NAME, Context.MODE_PRIVATE);
            result.putBoolean(EXTRA_ENABLED, prefs.getBoolean(Prefs.KEY_ENABLED, false));
            result.putInt(EXTRA_SENSITIVITY,
                prefs.getInt(Prefs.KEY_SENSITIVITY, Prefs.SENSITIVITY_STANDARD));
            return result;
        }
        return result;
    }

    private boolean isAllowedCaller() {
        Context context = getContext();
        if (context == null) {
            return false;
        }

        int callingUid = Binder.getCallingUid();
        if (callingUid == Process.myUid()) {
            return true;
        }

        String callingPackage = getCallingPackage();
        if (TARGET_PACKAGE.equals(callingPackage)) {
            return true;
        }

        PackageManager packageManager = context.getPackageManager();
        String[] packages = packageManager.getPackagesForUid(callingUid);
        if (packages == null) {
            return false;
        }
        for (String packageName : packages) {
            if (TARGET_PACKAGE.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    private Context requireProviderContext() {
        Context context = getContext();
        if (context == null) {
            throw new IllegalStateException("Provider context is null");
        }
        return context;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
