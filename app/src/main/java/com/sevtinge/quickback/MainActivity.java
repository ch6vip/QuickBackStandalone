package com.sevtinge.quickback;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;

public class MainActivity extends Activity {

    private TextView mStatusView;
    private TextView mHintView;
    private Switch mEnableSwitch;
    private RadioGroup mSensitivityGroup;
    private SharedPreferences mPrefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mPrefs = openPrefs();
        mStatusView = findViewById(R.id.status_text);
        mHintView = findViewById(R.id.hint_text);
        mEnableSwitch = findViewById(R.id.enable_switch);
        mSensitivityGroup = findViewById(R.id.sensitivity_group);

        boolean enabled = mPrefs.getBoolean(Prefs.KEY_ENABLED, false);
        mEnableSwitch.setChecked(enabled);
        mSensitivityGroup.check(getSensitivityButtonId(
            mPrefs.getInt(Prefs.KEY_SENSITIVITY, Prefs.SENSITIVITY_STANDARD)));
        renderStatus(enabled);

        mEnableSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            mPrefs.edit().putBoolean(Prefs.KEY_ENABLED, isChecked).apply();
            renderStatus(isChecked);
        });
        mSensitivityGroup.setOnCheckedChangeListener((group, checkedId) ->
            mPrefs.edit().putInt(Prefs.KEY_SENSITIVITY, getSensitivityValue(checkedId)).apply());
    }

    private SharedPreferences openPrefs() {
        return getSharedPreferences(Prefs.FILE_NAME, MODE_PRIVATE);
    }

    private void renderStatus(boolean enabled) {
        mStatusView.setText(enabled ? "状态：已开启" : "状态：已关闭");
        mHintView.setText(R.string.hint_text);
    }

    private int getSensitivityButtonId(int sensitivity) {
        if (sensitivity == Prefs.SENSITIVITY_CONSERVATIVE) {
            return R.id.sensitivity_conservative;
        }
        if (sensitivity == Prefs.SENSITIVITY_SENSITIVE) {
            return R.id.sensitivity_sensitive;
        }
        return R.id.sensitivity_standard;
    }

    private int getSensitivityValue(int checkedId) {
        if (checkedId == R.id.sensitivity_conservative) {
            return Prefs.SENSITIVITY_CONSERVATIVE;
        }
        if (checkedId == R.id.sensitivity_sensitive) {
            return Prefs.SENSITIVITY_SENSITIVE;
        }
        return Prefs.SENSITIVITY_STANDARD;
    }
}
