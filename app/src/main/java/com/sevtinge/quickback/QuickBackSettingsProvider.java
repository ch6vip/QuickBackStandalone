package com.sevtinge.quickback;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

public final class QuickBackSettingsProvider extends ContentProvider {

    public static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".settings";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY);
    public static final String METHOD_GET_ENABLED = "get_enabled";
    public static final String EXTRA_ENABLED = "enabled";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        SharedPreferences prefs = getContext().getSharedPreferences(Prefs.FILE_NAME, android.content.Context.MODE_PRIVATE);
        Bundle result = new Bundle();
        if (METHOD_GET_ENABLED.equals(method)) {
            result.putBoolean(EXTRA_ENABLED, prefs.getBoolean(Prefs.KEY_ENABLED, false));
            return result;
        }
        return result;
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
