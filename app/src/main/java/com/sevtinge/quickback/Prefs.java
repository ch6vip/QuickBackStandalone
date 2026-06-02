package com.sevtinge.quickback;

public final class Prefs {

    private Prefs() {
    }

    public static final String FILE_NAME = "quick_back";
    public static final String KEY_ENABLED = "home_navigation_quick_back";
    public static final String KEY_SENSITIVITY = "home_navigation_quick_back_sensitivity";

    public static final int SENSITIVITY_CONSERVATIVE = 0;
    public static final int SENSITIVITY_STANDARD = 1;
    public static final int SENSITIVITY_SENSITIVE = 2;
}
