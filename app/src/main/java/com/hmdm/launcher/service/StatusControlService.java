package com.hmdm.launcher.service;

import android.annotation.SuppressLint;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.json.ServerConfig;
import com.hmdm.launcher.util.Utils;
import com.hmdm.launcher.util.WifiBssidTracker;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class StatusControlService extends Service {

    private SettingsHelper settingsHelper;
    private ScheduledThreadPoolExecutor threadPoolExecutor = new ScheduledThreadPoolExecutor( 1 );
    private boolean controlDisabled = false;
    private Timer disableControlTimer;

    private final long ENABLE_CONTROL_DELAY = 60;

    // Interval for the policy re-assertion tick (Bluetooth/WiFi enforce, GPS/mobile-data
    // violation broadcasts). This is enforcement latency only; a longer interval saves battery.
    // Overridable per-config via the "statusCheckIntervalSec" app preference, clamped to
    // [MIN, MAX]. Default raised from 10s to 30s to cut always-on wakeups to ~1/3.
    private final long DEFAULT_STATUS_CHECK_INTERVAL_MS = 30000;
    private final long MIN_STATUS_CHECK_INTERVAL_MS = 10000;
    private final long MAX_STATUS_CHECK_INTERVAL_MS = 600000;

    private BroadcastReceiver wifiStateReceiver;

    private static class PackageInfo {
        public String packageName;
        public String className;

        public PackageInfo(String packageName, String className) {
            this.packageName = packageName;
            this.className = className;
        }
    }

    private BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive( Context context, Intent intent ) {
            switch ( intent.getAction() ) {
                case Const.ACTION_SERVICE_STOP:
                    stopSelf();
                    break;
                case Const.ACTION_STOP_CONTROL:
                    disableControl();
                    break;
            }
        }
    };

    @Override
    public void onDestroy() {
        LocalBroadcastManager.getInstance( this ).unregisterReceiver( receiver );

        unregisterWifiStateReceiver();

        threadPoolExecutor.shutdownNow();
        threadPoolExecutor = new ScheduledThreadPoolExecutor( 1 );

        Log.i(Const.LOG_TAG, "StatusControlService: service stopped");

        super.onDestroy();
    }

    // Battery-friendly WiFi BSSID-change detection: event-driven (no polling). The OS only
    // delivers NETWORK_STATE_CHANGED on real association/roam events, and a runtime-registered
    // receiver is exempt from the Android 8+ manifest implicit-broadcast limits.
    private void registerWifiStateReceiver() {
        if (wifiStateReceiver != null) {
            return;
        }
        wifiStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // Observed live while the service is alive (catches same-SSID roams immediately)
                WifiBssidTracker.checkAndLog(getApplicationContext(), true);
            }
        };
        try {
            IntentFilter filter = new IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                registerReceiver(wifiStateReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(wifiStateReceiver, filter);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void unregisterWifiStateReceiver() {
        if (wifiStateReceiver != null) {
            try {
                unregisterReceiver(wifiStateReceiver);
            } catch (Exception e) {
                // Already unregistered
            }
            wifiStateReceiver = null;
        }
    }

    @Override
    public int onStartCommand( Intent intent, int flags, int startId) {
        settingsHelper = SettingsHelper.getInstance(this);

        Log.i(Const.LOG_TAG, "StatusControlService: service started.");

        LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver);

        IntentFilter intentFilter = new IntentFilter(Const.ACTION_SERVICE_STOP);
        intentFilter.addAction(Const.ACTION_STOP_CONTROL);
        LocalBroadcastManager.getInstance(this).registerReceiver(receiver, intentFilter);

        threadPoolExecutor.shutdownNow();

        long intervalMs = resolveStatusCheckInterval();
        threadPoolExecutor = new ScheduledThreadPoolExecutor(1);
        threadPoolExecutor.scheduleWithFixedDelay(() -> controlStatus(),
                intervalMs, intervalMs, TimeUnit.MILLISECONDS);

        registerWifiStateReceiver();

        return Service.START_STICKY;
    }

    // Resolve the tick interval: default DEFAULT_STATUS_CHECK_INTERVAL_MS, overridable per-config
    // via the "statusCheckIntervalSec" app preference, clamped to [MIN, MAX]. Any bad value falls
    // back to the default.
    private long resolveStatusCheckInterval() {
        long intervalMs = DEFAULT_STATUS_CHECK_INTERVAL_MS;
        try {
            String pref = settingsHelper.getAppPreference(getPackageName(), "statusCheckIntervalSec");
            if (pref != null && !pref.trim().isEmpty()) {
                long candidate = Long.parseLong(pref.trim()) * 1000L;
                if (candidate < MIN_STATUS_CHECK_INTERVAL_MS) {
                    candidate = MIN_STATUS_CHECK_INTERVAL_MS;
                } else if (candidate > MAX_STATUS_CHECK_INTERVAL_MS) {
                    candidate = MAX_STATUS_CHECK_INTERVAL_MS;
                }
                intervalMs = candidate;
            }
        } catch (Exception e) {
            // Bad preference value: keep the default
        }
        Log.i(Const.LOG_TAG, "StatusControlService: status check interval = " + intervalMs + " ms");
        return intervalMs;
    }


    private void disableControl() {
        Log.i(Const.LOG_TAG, "StatusControlService: request to disable control");

        if (disableControlTimer != null) {
            try {
                disableControlTimer.cancel();
            } catch (Exception e) {
            }
            disableControlTimer = null;
        }
        controlDisabled = true;
        disableControlTimer = new Timer();
        disableControlTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                controlDisabled = false;
                Log.i(Const.LOG_TAG, "StatusControlService: control enabled");
            }
        }, ENABLE_CONTROL_DELAY * 1000);
        Log.i(Const.LOG_TAG, "StatusControlService: control disabled for 60 sec");
    }

    @SuppressLint("MissingPermission")
    private void controlStatus() {
        ServerConfig config = settingsHelper.getConfig();
        if (config == null || controlDisabled) {
            return;
        }

        if (config.getBluetooth() != null) {
            try {
                BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
                if (bluetoothAdapter != null) {
                    boolean enabled = bluetoothAdapter.isEnabled();
                    if (config.getBluetooth() && !enabled) {
                        bluetoothAdapter.enable();
                    } else if (!config.getBluetooth() && enabled) {
                        bluetoothAdapter.disable();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // Note: SecurityException here on Mediatek
        // Looks like com.mediatek.permission.CTA_ENABLE_WIFI needs to be explicitly granted
        // or even available to system apps only
        // By now, let's just ignore this issue
        if (config.getWifi() != null) {
            try {
                WifiManager wifiManager = (WifiManager) this.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wifiManager != null) {
                    boolean enabled = wifiManager.isWifiEnabled();
                    if (config.getWifi() && !enabled) {
                        wifiManager.setWifiEnabled(true);
                    } else if (!config.getWifi() && enabled) {
                        wifiManager.setWifiEnabled(false);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (config.getGps() != null) {
            LocationManager lm = (LocationManager)getSystemService(Context.LOCATION_SERVICE);
            if (lm != null) {
                boolean enabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
                if (config.getGps() && !enabled) {
                    notifyStatusViolation(Const.GPS_ON_REQUIRED);
                    return;
                } else if (!config.getGps() && enabled) {
                    notifyStatusViolation(Const.GPS_OFF_REQUIRED);
                    return;
                }
            }
        }

        if (config.getMobileData() != null) {
            ConnectivityManager cm = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                try {
                    boolean enabled = Utils.isMobileDataEnabled(this);
                    if (config.getMobileData() && !enabled) {
                        notifyStatusViolation(Const.MOBILE_DATA_ON_REQUIRED);
                    } else if (!config.getMobileData() && enabled) {
                        notifyStatusViolation(Const.MOBILE_DATA_OFF_REQUIRED);
                    }
                } catch (Exception e) {
                    // Some problem access private API
                }
            }
        }
    }

    private void notifyStatusViolation(int cause) {
        Intent intent = new Intent(Const.ACTION_POLICY_VIOLATION);
        intent.putExtra(Const.POLICY_VIOLATION_CAUSE, cause);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

}
