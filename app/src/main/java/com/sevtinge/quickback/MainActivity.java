package com.sevtinge.quickback;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private TextView mStatusView;
    private Switch mEnableSwitch;
    private SharedPreferences mPrefs;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mPrefs = getSharedPreferences(Prefs.FILE_NAME, MODE_PRIVATE);
        mStatusView = findViewById(R.id.status_text);
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
        mStatusView.setText(enabled ? "已开启" : "已关闭");
    }
}
