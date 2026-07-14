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
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.text.TextUtils;

import com.hmdm.launcher.Const;

/**
 * Detects and logs changes of the connected Wi-Fi BSSID (access-point MAC), including roaming
 * between APs on the same SSID.
 *
 * The last BSSID is persisted in SharedPreferences (not kept only in memory), so a process
 * restart compares the current AP against the last logged one and reports a real change instead
 * of silently re-adopting it as a fresh baseline. The single synchronized entry point plus the
 * shared persisted value deduplicates across all call sites (the Wi-Fi state receiver, the
 * connectivity receiver on the persistent home activity, and the Doze-exempt config-update wake),
 * so a given switch is logged exactly once regardless of which path observes it first.
 */
public class WifiBssidTracker {

    private static final String PREF_NAME = "wifi_bssid_state";
    private static final String KEY_LAST_BSSID = "last_bssid";

    // The redacted BSSID Android returns when location permission/services are unavailable.
    private static final String REDACTED_BSSID = "02:00:00:00:00:00";
    // Marker prefix so a single narrow server log rule can capture only these roam events.
    private static final String LOG_MARKER = "WIFI_BSSID_CHANGED";

    /**
     * Reads the current BSSID and, if it changed to a valid AP MAC since the last logged value,
     * logs one timestamped INFO event and persists the new value.
     *
     * @param live true when observed in real time (receiver); false when reconciled on a wake
     *             (the log is then marked "(detected on wake)" since the switch may have happened
     *             earlier, while the process was asleep).
     */
    public static synchronized void checkAndLog(Context ctx, boolean live) {
        try {
            String bssid = readCurrentBssid(ctx);
            if (bssid == null) {
                // Not connected, or BSSID redacted (no location permission/services). Keep the
                // last known BSSID so a transient disconnect isn't logged as a roam.
                return;
            }

            SharedPreferences prefs = ctx.getApplicationContext()
                    .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            String last = prefs.getString(KEY_LAST_BSSID, null);

            if (last == null) {
                // First observation ever: establish the baseline silently (no spurious "changed").
                prefs.edit().putString(KEY_LAST_BSSID, bssid).apply();
                return;
            }

            if (bssid.equals(last)) {
                return;
            }

            prefs.edit().putString(KEY_LAST_BSSID, bssid).apply();
            String ssid = readCurrentSsid(ctx);
            RemoteLogger.log(ctx, Const.LOG_INFO, LOG_MARKER + " " + bssid
                    + (TextUtils.isEmpty(ssid) ? "" : " ssid=" + ssid)
                    + (live ? "" : " (detected on wake)"));
        } catch (Exception e) {
            // A Wi-Fi/permission error must never crash the caller (service, activity, or loop)
            e.printStackTrace();
        }
    }

    @SuppressLint("MissingPermission")
    private static String readCurrentBssid(Context ctx) {
        try {
            WifiManager wifiManager = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null) {
                return null;
            }
            WifiInfo info = wifiManager.getConnectionInfo();
            String bssid = info != null ? info.getBSSID() : null;
            if (bssid == null || bssid.isEmpty() || REDACTED_BSSID.equals(bssid)) {
                return null;
            }
            return bssid;
        } catch (Exception e) {
            e.printStackTrace();
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
}
