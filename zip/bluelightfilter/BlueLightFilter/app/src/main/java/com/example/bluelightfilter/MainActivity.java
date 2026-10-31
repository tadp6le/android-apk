package com.example.bluelightfilter;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

public class MainActivity extends AppCompatActivity {

    /** Preferences file shared by this activity and the overlay service. */
    public static final String PREFS_NAME = "bluelight_filter_prefs";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_INTENSITY = "intensity"; // 0..100
    public static final String KEY_WARMTH = "warmth";       // 0..100
    public static final int DEFAULT_INTENSITY = 40;
    public static final int DEFAULT_WARMTH = 60;

    private SharedPreferences prefs;
    private MaterialSwitch filterSwitch;
    private MaterialButton grantPermissionButton;
    private TextView permissionStatusText;
    private TextView intensityValueText;
    private TextView warmthValueText;
    private SeekBar intensitySeekBar;
    private SeekBar warmthSeekBar;

    /** True while the switch state is restored from preferences in onCreate(). */
    private boolean restoringState = false;
    /** Set when the user tried to enable the filter without the overlay permission. */
    private boolean pendingEnableAfterPermission = false;

    private final ActivityResultLauncher<Intent> overlayPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> updatePermissionUi());

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    isGranted -> {
                        if (!isGranted) {
                            Toast.makeText(this, R.string.notification_permission_denied,
                                    Toast.LENGTH_SHORT).show();
                        }
                    });

    private final CompoundButton.OnCheckedChangeListener switchListener =
            (buttonView, isChecked) -> {
                if (isChecked) {
                    if (!Settings.canDrawOverlays(this)) {
                        if (!restoringState) {
                            pendingEnableAfterPermission = true;
                            Toast.makeText(this, R.string.permission_toast,
                                    Toast.LENGTH_LONG).show();
                            requestOverlayPermission();
                        }
                        setSwitchCheckedSilently(false);
                        prefs.edit().putBoolean(KEY_ENABLED, false).apply();
                        return;
                    }
                    prefs.edit().putBoolean(KEY_ENABLED, true).apply();
                    requestNotificationPermissionIfNeeded();
                    FilterService.start(this);
                } else {
                    prefs.edit().putBoolean(KEY_ENABLED, false).apply();
                    FilterService.stop(this);
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        filterSwitch = findViewById(R.id.filter_switch);
        grantPermissionButton = findViewById(R.id.grant_permission_button);
        permissionStatusText = findViewById(R.id.permission_status);
        intensityValueText = findViewById(R.id.intensity_value);
        warmthValueText = findViewById(R.id.warmth_value);
        intensitySeekBar = findViewById(R.id.intensity_seek_bar);
        warmthSeekBar = findViewById(R.id.warmth_seek_bar);

        grantPermissionButton.setOnClickListener(v -> requestOverlayPermission());

        intensitySeekBar.setMax(100);
        warmthSeekBar.setMax(100);
        intensitySeekBar.setProgress(prefs.getInt(KEY_INTENSITY, DEFAULT_INTENSITY));
        warmthSeekBar.setProgress(prefs.getInt(KEY_WARMTH, DEFAULT_WARMTH));
        updateIntensityLabel();
        updateWarmthLabel();

        intensitySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                prefs.edit().putInt(KEY_INTENSITY, progress).apply();
                updateIntensityLabel();
                pushUpdateToService();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        warmthSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                prefs.edit().putInt(KEY_WARMTH, progress).apply();
                updateWarmthLabel();
                pushUpdateToService();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        filterSwitch.setOnCheckedChangeListener(switchListener);

        // Restore the last state. If the filter was on, this also (re)starts the
        // service, which is effectively a no-op if it is already running.
        restoringState = true;
        filterSwitch.setChecked(prefs.getBoolean(KEY_ENABLED, false));
        restoringState = false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Also catches the user coming back from the system settings screen.
        updatePermissionUi();
    }

    private void updatePermissionUi() {
        boolean granted = Settings.canDrawOverlays(this);
        if (granted) {
            permissionStatusText.setText(R.string.permission_status_granted);
            permissionStatusText.setTextColor(
                    ContextCompat.getColor(this, R.color.status_green));
        } else {
            permissionStatusText.setText(R.string.permission_status_missing);
            permissionStatusText.setTextColor(
                    ContextCompat.getColor(this, R.color.status_red));
        }
        grantPermissionButton.setVisibility(granted ? View.GONE : View.VISIBLE);

        if (granted && pendingEnableAfterPermission) {
            pendingEnableAfterPermission = false;
            filterSwitch.setChecked(true);
        }
    }

    private void requestOverlayPermission() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        try {
            overlayPermissionLauncher.launch(intent);
        } catch (ActivityNotFoundException e) {
            // Very rare: the device has no dedicated overlay-permission screen.
            // Fall back to the normal app details screen.
            Intent fallback = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            try {
                overlayPermissionLauncher.launch(fallback);
            } catch (ActivityNotFoundException e2) {
                Toast.makeText(this, R.string.permission_toast, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
    }

    private void setSwitchCheckedSilently(boolean checked) {
        filterSwitch.setOnCheckedChangeListener(null);
        filterSwitch.setChecked(checked);
        filterSwitch.setOnCheckedChangeListener(switchListener);
    }

    private void pushUpdateToService() {
        if (prefs.getBoolean(KEY_ENABLED, false) && Settings.canDrawOverlays(this)) {
            FilterService.pushUpdate(this);
        }
    }

    private void updateIntensityLabel() {
        intensityValueText.setText(
                getString(R.string.percent_format, intensitySeekBar.getProgress()));
    }

    private void updateWarmthLabel() {
        int progress = warmthSeekBar.getProgress();
        String level;
        if (progress < 34) {
            level = getString(R.string.warmth_soft);
        } else if (progress < 67) {
            level = getString(R.string.warmth_medium);
        } else {
            level = getString(R.string.warmth_deep);
        }
        warmthValueText.setText(getString(R.string.warmth_format, progress, level));
    }
}
