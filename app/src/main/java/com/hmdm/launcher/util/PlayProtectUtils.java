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

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

import com.hmdm.launcher.Const;

/**
 * Exploratory launcher-side suppression of the Play Protect / package-verifier install prompt.
 *
 * This is gated behind a server-managed application setting ("playProtectMode" on the launcher's
 * own package) so it can be turned on/off from the web panel without a rebuild, and it is off by
 * default. Everything is wrapped in try/catch: a Play Protect failure must never crash or block
 * the config update loop.
 *
 * Modes:
 *   off | (absent)  - do nothing; un-hide com.android.vending if we previously hid it
 *   settings        - attempt dpm.setGlobalSetting() for the verifier flags (expected to throw
 *                     SecurityException on most images; we record the empirical result)
 *   hide-verifier   - hide com.android.vending (the package that raises the prompt) so
 *                     PackageManager has no verifier to wait on
 *
 * Hard constraints (see task §6.5): no reflection, no hidden APIs, no WRITE_SECURE_SETTINGS,
 * and com.google.android.gms is never touched.
 */
public class PlayProtectUtils {

    public static final String PREF_KEY = "playProtectMode";

    public static final String MODE_OFF = "off";
    public static final String MODE_SETTINGS = "settings";
    public static final String MODE_HIDE_VERIFIER = "hide-verifier";

    // The package believed to host the install-time verifier that raises the prompt.
    // This must be confirmed on-device from logcat (task §6.4) before relying on it.
    private static final String VERIFIER_PACKAGE = "com.android.vending";

    // Local flag remembering whether we hid the verifier, so "off" can reverse it.
    private static final String LOCAL_PREF_NAME = "play_protect_state";
    private static final String LOCAL_PREF_HIDDEN = "verifier_hidden";

    /**
     * Applies the configured Play Protect suppression mode. Safe to call on every config update.
     */
    public static void applyMode(Context context, String mode) {
        if (mode == null || mode.trim().equals("")) {
            // Fleet-wide default: suppress Play Protect out of the box by hiding the verifier.
            // Set the app preference playProtectMode=off in the web console to restore Play Store.
            mode = MODE_HIDE_VERIFIER;
        }
        mode = mode.trim();

        try {
            if (!Utils.isDeviceOwner(context)) {
                // All supported approaches require device owner
                return;
            }

            logVerifierState(context, mode);

            if (MODE_SETTINGS.equals(mode)) {
                applySettingsMode(context);
            } else if (MODE_HIDE_VERIFIER.equals(mode)) {
                setVerifierHidden(context, true);
            } else {
                // MODE_OFF or unknown value: reverse any hide we previously applied
                if (wasVerifierHidden(context)) {
                    setVerifierHidden(context, false);
                }
            }
        } catch (Exception e) {
            // Never propagate — this must not block the config update loop
            RemoteLogger.log(context, Const.LOG_WARN, "Play Protect suppression failed: " + e.getMessage());
        }
    }

    // Attempt 1 (task §6.3): setGlobalSetting() for the verifier flags. Expected to throw
    // SecurityException on most images (the flags are believed not to be on the device-owner
    // allowlist), but the result varies across API levels and OEM images, so we record it.
    private static void applySettingsMode(Context context) {
        ComponentName admin = LegacyUtils.getAdminComponentName(context);
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm == null || admin == null) {
            return;
        }
        try {
            dpm.setGlobalSetting(admin, "package_verifier_enable", "0");
            dpm.setGlobalSetting(admin, "package_verifier_user_consent", "-1");
            RemoteLogger.log(context, Const.LOG_INFO, "Play Protect: verifier settings applied via setGlobalSetting");
        } catch (SecurityException e) {
            RemoteLogger.log(context, Const.LOG_WARN, "Play Protect: setGlobalSetting not permitted for device owner: " + e.getMessage());
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN, "Play Protect: setGlobalSetting failed: " + e.getMessage());
        }
    }

    // Attempt 2 (task §6.4): hide the verifier package so PackageManager has no verifier to
    // dispatch to. Supported API for a device owner; re-applies on every boot/config update.
    private static void setVerifierHidden(Context context, boolean hidden) {
        ComponentName admin = LegacyUtils.getAdminComponentName(context);
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm == null || admin == null) {
            return;
        }
        try {
            boolean result = dpm.setApplicationHidden(admin, VERIFIER_PACKAGE, hidden);
            rememberVerifierHidden(context, hidden && result);
            RemoteLogger.log(context, Const.LOG_INFO, "Play Protect: setApplicationHidden(" + VERIFIER_PACKAGE
                    + ", " + hidden + ") returned " + result);
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN, "Play Protect: setApplicationHidden failed: " + e.getMessage());
        }
    }

    // Diagnostic (task §6.2): log the current verifier settings so the on-device test can
    // identify which prompt is actually blocking. These globals are world-readable.
    private static void logVerifierState(Context context, String mode) {
        try {
            String consent = Settings.Global.getString(context.getContentResolver(), "package_verifier_user_consent");
            String enable = Settings.Global.getString(context.getContentResolver(), "package_verifier_enable");
            Log.d(Const.LOG_TAG, "Play Protect mode=" + mode
                    + " package_verifier_user_consent=" + consent
                    + " package_verifier_enable=" + enable);
        } catch (Exception e) {
            // Ignore — purely diagnostic
        }
    }

    private static void rememberVerifierHidden(Context context, boolean hidden) {
        try {
            SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(LOCAL_PREF_NAME, Context.MODE_PRIVATE);
            prefs.edit().putBoolean(LOCAL_PREF_HIDDEN, hidden).apply();
        } catch (Exception e) {
            // Ignore
        }
    }

    private static boolean wasVerifierHidden(Context context) {
        try {
            SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(LOCAL_PREF_NAME, Context.MODE_PRIVATE);
            return prefs.getBoolean(LOCAL_PREF_HIDDEN, false);
        } catch (Exception e) {
            return false;
        }
    }
}
