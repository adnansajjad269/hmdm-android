/*
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hmdm.launcher.util;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.LocationManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;

import com.hmdm.launcher.Const;

/**
 * Detects and logs the connected Wi-Fi BSSID (access-point MAC), including roaming between APs on
 * the same SSID. Uses a unified "WIFI_BSSID" marker prefix so a single server log rule filtered to
 * "WIFI_BSSID" captures every message this class emits.
 *
 * The last BSSID is persisted in SharedPreferences so a process restart compares against the last
 * logged AP instead of silently re-priming. The single synchronized entry point plus the shared
 * persisted value deduplicates across all call sites (the Wi-Fi state receiver, the connectivity
 * receiver on the persistent home activity, and the Doze-exempt config-update wake).
 *
 * Three states are handled:
 *  - valid BSSID different from the last logged one -> WIFI_BSSID_CHANGED (this also covers the
 *    first observation after connect/install, so "first connected" is visible);
 *  - connected but BSSID redacted (02:00:00:00:00:00 — returned when location services are off or
 *    the location permission is missing) -> WIFI_BSSID_UNAVAILABLE, logged once per transition so
 *    the reason for the silence is visible without spamming;
 *  - not connected -> nothing (keep the last known BSSID).
 */
public class WifiBssidTracker {

    private static final String PREF_NAME = "wifi_bssid_state";
    private static final String KEY_LAST_BSSID = "last_bssid";
    private static final String KEY_UNAVAILABLE_REPORTED = "unavailable_reported";
    private static final String KEY_BOOT_TIME = "boot_time";
    private static final String KEY_BOOT_LOGGED = "boot_logged";

    // Approximate boot instant (currentTimeMillis - elapsedRealtime) shifts on reboot; small
    // tolerance absorbs clock drift/NTP adjustments so we don't false-detect a reboot.
    private static final long BOOT_TOLERANCE_MS = 15000;

    // The redacted BSSID Android returns when location permission/services are unavailable.
    private static final String REDACTED_BSSID = "02:00:00:00:00:00";

    /**
     * Reads the current BSSID and logs a change (or the first observation), or a one-time
     * "unavailable" diagnostic when the BSSID is redacted.
     *
     * @param live true when observed in real time (receiver); false when reconciled on a wake
     *             (a change is then marked "(detected on wake)").
     */
    public static synchronized void checkAndLog(Context ctx, boolean live) {
        try {
            SharedPreferences prefs = ctx.getApplicationContext()
                    .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

            // Detect a reboot: on a new boot, force the connected BSSID to be logged again (even
            // if it is the same AP), per the "log connected AP on every reboot" requirement.
            long bootTime = System.currentTimeMillis() - SystemClock.elapsedRealtime();
            long lastBootTime = prefs.getLong(KEY_BOOT_TIME, 0);
            if (Math.abs(bootTime - lastBootTime) > BOOT_TOLERANCE_MS) {
                prefs.edit()
                        .putLong(KEY_BOOT_TIME, bootTime)
                        .putBoolean(KEY_BOOT_LOGGED, false)
                        .putBoolean(KEY_UNAVAILABLE_REPORTED, false)
                        .apply();
            }

            String rawBssid = readRawBssid(ctx);

            if (rawBssid == null || rawBssid.isEmpty()) {
                // Not connected to Wi-Fi: keep the last known BSSID, nothing to log.
                return;
            }

            if (REDACTED_BSSID.equals(rawBssid)) {
                // Connected but the BSSID is hidden. Log once per transition into this state.
                if (!prefs.getBoolean(KEY_UNAVAILABLE_REPORTED, false)) {
                    prefs.edit().putBoolean(KEY_UNAVAILABLE_REPORTED, true).apply();
                    RemoteLogger.log(ctx, Const.LOG_INFO, "WIFI_BSSID_UNAVAILABLE redacted "
                            + "(location services off or permission missing); locationEnabled=" + isLocationEnabled(ctx));
                }
                return;
            }

            // Valid BSSID: clear the unavailable dedup flag so a later redaction re-logs once.
            prefs.edit().putBoolean(KEY_UNAVAILABLE_REPORTED, false).apply();

            String last = prefs.getString(KEY_LAST_BSSID, null);
            String ssid = readCurrentSsid(ctx);

            if (!prefs.getBoolean(KEY_BOOT_LOGGED, false)) {
                // First valid BSSID after a boot: log the connected AP regardless of change.
                prefs.edit()
                        .putString(KEY_LAST_BSSID, rawBssid)
                        .putBoolean(KEY_BOOT_LOGGED, true)
                        .apply();
                RemoteLogger.log(ctx, Const.LOG_INFO, "WIFI_BSSID_CONNECTED " + rawBssid
                        + (TextUtils.isEmpty(ssid) ? "" : " ssid=" + ssid)
                        + (live ? "" : " (detected on wake)"));
                return;
            }

            if (rawBssid.equals(last)) {
                // Same AP (e.g. a Doze reconnect to the same AP): no change, no log.
                return;
            }

            prefs.edit().putString(KEY_LAST_BSSID, rawBssid).apply();
            RemoteLogger.log(ctx, Const.LOG_INFO, "WIFI_BSSID_CHANGED " + rawBssid
                    + (TextUtils.isEmpty(ssid) ? "" : " ssid=" + ssid)
                    + (live ? "" : " (detected on wake)"));
        } catch (Exception e) {
            // A Wi-Fi/permission error must never crash the caller (service, activity, or loop)
            e.printStackTrace();
        }
    }

    // Raw getBSSID() value (may be null when not connected, or the redacted value when hidden).
    @SuppressLint("MissingPermission")
    private static String readRawBssid(Context ctx) {
        try {
            WifiManager wifiManager = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null) {
                return null;
            }
            WifiInfo info = wifiManager.getConnectionInfo();
            return info != null ? info.getBSSID() : null;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressLint("MissingPermission")
    private static String readCurrentSsid(Context ctx) {
        try {
            WifiManager wifiManager = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wifiManager != null ? wifiManager.getConnectionInfo() : null;
            return info != null ? info.getSSID() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // Whether location services are enabled (required for a non-redacted BSSID on Android 8+).
    private static boolean isLocationEnabled(Context ctx) {
        try {
            LocationManager lm = (LocationManager) ctx.getApplicationContext()
                    .getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) {
                return false;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return lm.isLocationEnabled();
            }
            return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            return false;
        }
    }
}
