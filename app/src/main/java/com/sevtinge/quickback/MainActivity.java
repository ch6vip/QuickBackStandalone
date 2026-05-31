package com.sevtinge.quickback;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Switch;
import android.widget.TextView;

public class MainActivity extends Activity {

    private TextView mStatusView;
    private TextView mHintView;
    private Switch mEnableSwitch;
    private SharedPreferences mPrefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mPrefs = getSharedPreferences(Prefs.FILE_NAME, MODE_PRIVATE);
        mStatusView = findViewById(R.id.status_text);
        mHintView = findViewById(R.id.hint_text);
        mEnableSwitch = findViewById(R.id.enable_switch);

        boolean enabled = mPrefs.getBoolean(Prefs.KEY_ENABLED, false);
        mEnableSwitch.setChecked(enabled);
        renderStatus(enabled);

        mEnableSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            mPrefs.edit().putBoolean(Prefs.KEY_ENABLED, isChecked).apply();
            renderStatus(isChecked);
        });
    }

    private void renderStatus(boolean enabled) {
        mStatusView.setText(enabled ? "状态：已开启" : "状态：已关闭");
        mHintView.setText(R.string.hint_text);
    }
}
