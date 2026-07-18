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

import com.hmdm.launcher.Const;

/**
 * Exploratory launcher-side suppression of the Play Protect / package-verifier install prompt.
 *
 * This is gated behind a server-managed application setting ("playProtectMode" on the launcher's
 * own package) so it can be tuned from the web panel without a rebuild. Everything is wrapped in
 * try/catch: a Play Protect failure must never crash or block the config update loop, and the real
 * work runs at most once per resolved mode per process (no per-config-poll repetition).
 *
 * Modes:
 *   (absent)        - MODE_DEFAULT: best-effort, try both supported approaches once (settings, hide)
 *   off             - do nothing; un-hide com.android.vending if we previously hid it
 *   settings        - attempt dpm.setGlobalSetting() for the verifier flags (may throw
 *                     SecurityException depending on API level / OEM image; we record the result)
 *   hide-verifier   - hide com.android.vending as device owner. NOTE: some images (e.g. Kyocera)
 *                     refuse this and setApplicationHidden returns false — then it cannot work and
 *                     the settings approach or the ADB provisioning workaround must be used instead.
 *
 * Hard constraints (see task §6.5): no reflection, no hidden APIs, no WRITE_SECURE_SETTINGS,
 * and com.google.android.gms is never touched.
 */
public class PlayProtectUtils {

    public static final String PREF_KEY = "playProtectMode";

    public static final String MODE_OFF = "off";
    public static final String MODE_SETTINGS = "settings";
    public static final String MODE_HIDE_VERIFIER = "hide-verifier";
    // Internal value the unset preference maps to: try both supported approaches once.
    public static final String MODE_DEFAULT = "default";

    // The package believed to host the install-time verifier that raises the prompt.
    // This must be confirmed on-device from logcat (task §6.4) before relying on it.
    private static final String VERIFIER_PACKAGE = "com.android.vending";

    // Local flag remembering whether we hid the verifier, so "off" can reverse it.
    private static final String LOCAL_PREF_NAME = "play_protect_state";
    private static final String LOCAL_PREF_HIDDEN = "verifier_hidden";

    // Loop guard: the resolved mode last applied in THIS process. applyMode() is called on every
    // config poll, but the actual work (and its log lines) only needs to run when the mode changes
    // or the process restarts (a reboot re-applies once). This stops the per-poll log spam seen
    // when e.g. hide-verifier keeps returning false on an image that won't let us hide the package.
    private static volatile String lastAppliedMode = null;

    /**
     * Applies the configured Play Protect suppression mode. Safe to call on every config update:
     * repeated calls with an unchanged mode are no-ops within the same process.
     *
     * Unset default: attempt BOTH supported approaches once (verifier settings, then hiding the
     * verifier package). On some OEM images (e.g. Kyocera) hiding com.android.vending is refused
     * by the device owner and setApplicationHidden returns false; the settings attempt is the more
     * likely lever, so we try it too rather than relying on the hide alone.
     */
    public static void applyMode(Context context, String mode) {
        if (mode == null || mode.trim().equals("")) {
            mode = MODE_DEFAULT;
        }
        mode = mode.trim();

        // Only do real work (and log) when the resolved mode changes within this process.
        if (mode.equals(lastAppliedMode)) {
            return;
        }
        lastAppliedMode = mode;

        try {
            if (!Utils.isDeviceOwner(context)) {
                // All supported approaches require device owner
                return;
            }

            logVerifierState(context, mode);

            if (MODE_OFF.equals(mode)) {
                // Reverse any hide we previously applied
                if (wasVerifierHidden(context)) {
                    setVerifierHidden(context, false);
                }
            } else if (MODE_SETTINGS.equals(mode)) {
                applySettingsMode(context);
            } else if (MODE_HIDE_VERIFIER.equals(mode)) {
                setVerifierHidden(context, true);
            } else {
                // MODE_DEFAULT or any unknown value: best-effort, try both supported approaches once
                applySettingsMode(context);
                setVerifierHidden(context, true);
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
            // Re-read to prove whether the write actually stuck (values now 0/-1) or was silently
            // ignored (still 1) — the empirical answer we otherwise can't see.
            String enableAfter = Settings.Global.getString(context.getContentResolver(), "package_verifier_enable");
            String consentAfter = Settings.Global.getString(context.getContentResolver(), "package_verifier_user_consent");
            RemoteLogger.log(context, Const.LOG_INFO, "Play Protect: verifier settings applied via setGlobalSetting; "
                    + "package_verifier_enable now=" + enableAfter + " package_verifier_user_consent now=" + consentAfter);
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
            // Already in the desired state? Nothing to do (and nothing to log).
            if (dpm.isApplicationHidden(admin, VERIFIER_PACKAGE) == hidden) {
                rememberVerifierHidden(context, hidden);
                return;
            }
            boolean result = dpm.setApplicationHidden(admin, VERIFIER_PACKAGE, hidden);
            rememberVerifierHidden(context, hidden && result);
            if (hidden && !result) {
                // This image (e.g. Kyocera) refuses to let the device owner hide Play Store.
                // hide-verifier cannot work here; use playProtectMode=settings or the ADB workaround.
                RemoteLogger.log(context, Const.LOG_WARN, "Play Protect: cannot hide " + VERIFIER_PACKAGE
                        + " on this device (setApplicationHidden returned false) — hide-verifier unsupported here");
            } else {
                RemoteLogger.log(context, Const.LOG_INFO, "Play Protect: setApplicationHidden(" + VERIFIER_PACKAGE
                        + ", " + hidden + ") returned " + result);
            }
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN, "Play Protect: setApplicationHidden failed: " + e.getMessage());
        }
    }

    // Diagnostic (task §6.2): log the resolved mode and the current verifier settings to the
    // SERVER (RemoteLogger, not just logcat) so we can confirm the preference reached the launcher
    // and see the before/after verifier state. These globals are world-readable. Runs once per
    // mode change (loop guard), so not spammy.
    private static void logVerifierState(Context context, String mode) {
        try {
            String consent = Settings.Global.getString(context.getContentResolver(), "package_verifier_user_consent");
            String enable = Settings.Global.getString(context.getContentResolver(), "package_verifier_enable");
            RemoteLogger.log(context, Const.LOG_INFO, "Play Protect mode=" + mode
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
