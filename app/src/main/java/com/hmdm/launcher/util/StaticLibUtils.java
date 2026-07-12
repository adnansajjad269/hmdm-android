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

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.SharedLibraryInfo;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;

import com.hmdm.launcher.Const;

import java.util.List;

/**
 * Helper for detecting static shared libraries (e.g. com.google.android.trichromelibrary).
 *
 * Static shared libraries are installed under a mangled package name and are omitted from
 * PackageManager.getInstalledPackages() / getPackageInfo() unless the caller passes the
 * hidden MATCH_STATIC_SHARED_LIBRARIES flag, which is unavailable to a normal app. As a
 * result the launcher's usual getPackageInfo() lookup throws NameNotFoundException and
 * concludes the library is not installed, re-downloading and re-installing it forever.
 *
 * PackageManager.getSharedLibraries() is the public, permission-free API that DOES expose
 * static shared libraries. It is the in-app equivalent of "adb shell pm list libraries".
 */
public class StaticLibUtils {

    /**
     * Returns the installed static-shared-library version for the given library/package name,
     * or null if no such static library is installed.
     *
     * When several versions of the same static library are installed side by side (which
     * happens after an upgrade — the old and the new version coexist until the old one is
     * garbage-collected), the highest version is returned, so an upgrade is still detected.
     *
     * getSharedLibraries() is API 26+; on older releases this returns null (the fork's
     * minSdkVersion is 26, so this is defensive only).
     */
    @Nullable
    public static Long getInstalledStaticLibVersion(Context ctx, String pkg) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || pkg == null) {
            return null;
        }
        PackageManager pm = ctx.getPackageManager();
        try {
            Long best = null;
            List<SharedLibraryInfo> libs = pm.getSharedLibraries(0);
            if (libs == null) {
                return null;
            }
            for (SharedLibraryInfo lib : libs) {
                if (lib.getType() != SharedLibraryInfo.TYPE_STATIC) {
                    continue;
                }
                if (!matches(lib, pkg)) {
                    continue;
                }
                long version = versionOf(lib);
                if (best == null || version > best) {
                    best = version;
                }
            }
            return best;
        } catch (Exception e) {
            // Never let a detection failure crash or block the config update loop
            Log.w(Const.LOG_TAG, "getInstalledStaticLibVersion failed for " + pkg + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Matches a shared library entry against a configured package name.
     *
     * For Trichrome, getName() is the unmangled declared library name
     * (com.google.android.trichromelibrary) while getDeclaringPackage().getPackageName()
     * may be the mangled name (com.google.android.trichromelibrary_149...). We accept any of:
     * exact match on the library name, exact match on the declaring package name, or a
     * declaring package name that follows the "pkg_<version>" mangling convention. We do not
     * rely on the mangling convention alone.
     */
    private static boolean matches(SharedLibraryInfo lib, String pkg) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false;
        }
        try {
            String name = lib.getName();
            if (pkg.equals(name)) {
                return true;
            }
            if (lib.getDeclaringPackage() != null) {
                String declaring = lib.getDeclaringPackage().getPackageName();
                if (declaring != null) {
                    if (pkg.equals(declaring)) {
                        return true;
                    }
                    if (declaring.startsWith(pkg + "_")) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            // Ignore a malformed entry and keep scanning
        }
        return false;
    }

    /**
     * Reads the library version code as a long across API levels.
     * getLongVersion() is API 28+; getVersion() (int) covers API 26-27.
     */
    @SuppressWarnings("deprecation")
    private static long versionOf(SharedLibraryInfo lib) {
        return (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                ? lib.getLongVersion()
                : (long) lib.getVersion();
    }

    /**
     * Diagnostic dump of the shared-library table (name / type / declaring package / version)
     * to help cross-check on-device against "adb shell pm list libraries". Debug level only.
     */
    public static void dumpSharedLibraries(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        try {
            List<SharedLibraryInfo> libs = ctx.getPackageManager().getSharedLibraries(0);
            if (libs == null) {
                Log.d(Const.LOG_TAG, "SharedLibraries: null");
                return;
            }
            for (SharedLibraryInfo lib : libs) {
                String declaring = lib.getDeclaringPackage() != null
                        ? lib.getDeclaringPackage().getPackageName() : "null";
                Log.d(Const.LOG_TAG, "SharedLibrary: name=" + lib.getName()
                        + " type=" + lib.getType()
                        + " declaringPackage=" + declaring
                        + " version=" + versionOf(lib));
            }
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "dumpSharedLibraries failed: " + e.getMessage());
        }
    }
}
