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

package com.hmdm.launcher.service;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.hmdm.launcher.Const;
import com.hmdm.launcher.R;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.pro.ProUtils;
import com.hmdm.launcher.util.DeviceInfoProvider;
import com.hmdm.launcher.util.RemoteLogger;

public class LocationService extends Service {
    private LocationManager locationManager;

    private static final int NOTIFICATION_ID = 112;
    public static String CHANNEL_ID = LocationService.class.getName();

    public static final String ACTION_UPDATE_GPS = "gps";
    public static final String ACTION_UPDATE_NETWORK = "network";
    public static final String ACTION_STOP = "stop";

    boolean updateViaGps = false;
    boolean started = false;

    // Location fix interval (provider minTime). Tunable per-config via the "locationUpdateIntervalSec"
    // app preference, clamped to [MIN, MAX]. Default raised to 900 s (15 min) to cut location wakeups.
    private static final long DEFAULT_LOCATION_UPDATE_INTERVAL_MS = 900000;
    private static final long MIN_LOCATION_UPDATE_INTERVAL_MS = 60000;    // 1 min floor
    private static final long MAX_LOCATION_UPDATE_INTERVAL_MS = 3600000;  // 1 hour ceiling

    // Use different location listeners for GPS and Network
    // Not sure what happens if we share the same listener for both providers
    private LocationListener gpsLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            RemoteLogger.log(LocationService.this, Const.LOG_INFO, "GPS location update: lat="
                    + location.getLatitude() + ", lon=" + location.getLongitude());
            // Capture the fix so it is retained even if getLastKnownLocation() later returns null.
            DeviceInfoProvider.storeLastLocation(LocationService.this, location);
            ProUtils.processLocation(LocationService.this, location, LocationManager.GPS_PROVIDER);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
            // A provider coming online (e.g. enabling High Accuracy) must re-register so fixes flow.
            requestLocationUpdates();
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };
    private LocationListener networkLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            RemoteLogger.log(LocationService.this, Const.LOG_INFO, "Network location update: lat="
                    + location.getLatitude() + ", lon=" + location.getLongitude());
            DeviceInfoProvider.storeLastLocation(LocationService.this, location);
            ProUtils.processLocation(LocationService.this, location, LocationManager.NETWORK_PROVIDER);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
            requestLocationUpdates();
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };
    // Passive provider: receives fixes produced for other apps — an extra source indoors.
    private LocationListener passiveLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            DeviceInfoProvider.storeLastLocation(LocationService.this, location);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };

    private Handler handler = new Handler();
    private GnssStatus.Callback gnssStatusCallback = null;

    // Primary location source: Fused (Wi-Fi/cell/GPS, works indoors, doesn't suffer the silent
    // stalls of the legacy NETWORK_PROVIDER). Falls back to the LocationManager path above when
    // Google Play Services is unavailable on the device.
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback fusedLocationCallback;
    private boolean usingFused = false;

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager)this.getSystemService(LOCATION_SERVICE);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        fusedLocationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                for (Location location : result.getLocations()) {
                    String provider = location.getProvider() != null ? location.getProvider() : "fused";
                    RemoteLogger.log(LocationService.this, Const.LOG_INFO, "Fused location update: lat="
                            + location.getLatitude() + ", lon=" + location.getLongitude() + ", provider=" + provider);
                    DeviceInfoProvider.storeLastLocation(LocationService.this, location);
                    ProUtils.processLocation(LocationService.this, location, provider);
                }
            }
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            gnssStatusCallback = new GnssStatus.Callback() {
                @Override
                public void onSatelliteStatusChanged(@NonNull GnssStatus status) {
                    super.onSatelliteStatusChanged(status);
                    try {
                        Log.d(Const.LOG_TAG, "Satellite status changed, count: " + status.getSatelliteCount());
                        SettingsHelper.getInstance(LocationService.this.getApplicationContext()).setSatelliteCount(status.getSatelliteCount());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            };
        }
    }

    @SuppressLint("WrongConstant")
    private void startAsForeground() {
        NotificationCompat.Builder builder;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // IMPORTANCE_LOW keeps the mandatory foreground-service notification silent (no sound,
            // no heads-up) and low in the shade, reducing battery/attention cost.
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Notification Channel", NotificationManager.IMPORTANCE_LOW);
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.createNotificationChannel(channel);
            builder = new NotificationCompat.Builder(this, CHANNEL_ID);
        } else {
            builder = new NotificationCompat.Builder( this );
        }
        Notification notification = builder
                .setContentTitle(ProUtils.getAppName(this))
                .setTicker(ProUtils.getAppName(this))
                .setContentText( getString( R.string.location_service_text ) )
                .setSmallIcon( R.drawable.ic_location_service ).build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    // Resolve the location fix interval: default DEFAULT_LOCATION_UPDATE_INTERVAL_MS, overridable
    // per-config via the "locationUpdateIntervalSec" app preference, clamped to [MIN, MAX].
    private long resolveLocationUpdateInterval() {
        long intervalMs = DEFAULT_LOCATION_UPDATE_INTERVAL_MS;
        try {
            String pref = SettingsHelper.getInstance(this).getAppPreference(getPackageName(), "locationUpdateIntervalSec");
            if (pref != null && !pref.trim().isEmpty()) {
                long candidate = Long.parseLong(pref.trim()) * 1000L;
                if (candidate < MIN_LOCATION_UPDATE_INTERVAL_MS) {
                    candidate = MIN_LOCATION_UPDATE_INTERVAL_MS;
                } else if (candidate > MAX_LOCATION_UPDATE_INTERVAL_MS) {
                    candidate = MAX_LOCATION_UPDATE_INTERVAL_MS;
                }
                intervalMs = candidate;
            }
        } catch (Exception e) {
            // Bad preference value: keep the default
        }
        return intervalMs;
    }

    private boolean requestLocationUpdates() {
        boolean fineGranted = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean coarseGranted = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;

        if (updateViaGps && (!fineGranted || !locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))) {
            // Diagnostic: the GPS source was requested but can't be used
            RemoteLogger.log(this, Const.LOG_WARN, "Location: GPS source unavailable (fineGranted=" + fineGranted
                    + ", gpsEnabled=" + locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    + ") — using network/passive instead");
            updateViaGps = false;
        }
        if (!coarseGranted) {
            // No permission, so give up! (logged so this isn't a silent early return)
            RemoteLogger.log(this, Const.LOG_WARN, "Location: ACCESS_COARSE_LOCATION not granted — cannot request updates");
            return false;
        }

        boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        boolean networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        boolean passiveEnabled = locationManager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER);
        RemoteLogger.log(this, Const.LOG_INFO,
                "Request location updates. gps=" + gpsEnabled + ", network=" + networkEnabled + ", passive=" + passiveEnabled
                        + ", fineGranted=" + fineGranted + ", coarseGranted=" + coarseGranted);

        long updateInterval = resolveLocationUpdateInterval();
        RemoteLogger.log(this, Const.LOG_INFO, "Location update interval = " + updateInterval + " ms");

        boolean playServicesAvailable = GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS;

        if (playServicesAvailable) {
            if (requestFusedLocationUpdates(updateInterval)) {
                // Fused is the primary source: make sure the legacy path isn't also registered
                // (avoids duplicate wakeups/logs).
                usingFused = true;
                locationManager.removeUpdates(networkLocationListener);
                locationManager.removeUpdates(gpsLocationListener);
                locationManager.removeUpdates(passiveLocationListener);
                return true;
            }
            // Fused registration itself failed unexpectedly: fall through to the legacy path below.
            RemoteLogger.log(this, Const.LOG_WARN, "Location: Fused registration failed, falling back to LocationManager");
        } else {
            RemoteLogger.log(this, Const.LOG_INFO, "Location: Google Play Services unavailable, using LocationManager");
        }

        usingFused = false;
        if (fusedLocationClient != null) {
            try {
                fusedLocationClient.removeLocationUpdates(fusedLocationCallback);
            } catch (Exception e) {
                // Ignore
            }
        }

        locationManager.removeUpdates(networkLocationListener);
        locationManager.removeUpdates(gpsLocationListener);
        locationManager.removeUpdates(passiveLocationListener);
        try {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, updateInterval, 0, networkLocationListener);
            // Passive provider costs nothing extra (it only delivers fixes other apps requested)
            // and can supply a location indoors when GPS cannot.
            try {
                locationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, updateInterval, 0, passiveLocationListener);
            } catch (Exception e) {
                // Passive provider may be unavailable — ignore
            }
            if (updateViaGps) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, updateInterval, 0, gpsLocationListener);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    locationManager.registerGnssStatusCallback(gnssStatusCallback, handler);
                }
            }
        } catch (Exception e) {
            // Provider may not exist, so process it friendly
            e.printStackTrace();
            return false;
        }

        return true;
    }

    // Registers Fused location updates. Priority.PRIORITY_HIGH_ACCURACY prefers GPS when the
    // configured source is GPS; PRIORITY_BALANCED_POWER_ACCURACY uses Wi-Fi/cell fusion, which
    // (unlike the legacy NETWORK_PROVIDER) works reliably indoors and doesn't silently stall.
    @SuppressLint("MissingPermission")
    private boolean requestFusedLocationUpdates(long updateInterval) {
        try {
            fusedLocationClient.removeLocationUpdates(fusedLocationCallback);
            int priority = updateViaGps ? Priority.PRIORITY_HIGH_ACCURACY : Priority.PRIORITY_BALANCED_POWER_ACCURACY;
            LocationRequest request = new LocationRequest.Builder(updateInterval)
                    .setPriority(priority)
                    .build();
            fusedLocationClient.requestLocationUpdates(request, fusedLocationCallback, Looper.getMainLooper());
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }


    @Override
    public void onDestroy() {
        locationManager.removeUpdates(networkLocationListener);
        locationManager.removeUpdates(gpsLocationListener);
        locationManager.removeUpdates(passiveLocationListener);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            locationManager.unregisterGnssStatusCallback(gnssStatusCallback);
        }
        if (fusedLocationClient != null) {
            try {
                fusedLocationClient.removeLocationUpdates(fusedLocationCallback);
            } catch (Exception e) {
                // Ignore
            }
        }
        started = false;

        super.onDestroy();
    }


    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent inputIntent, int flags, int startId) {
        if (inputIntent != null && inputIntent.getAction() != null) {
            if (inputIntent.getAction().equals(ACTION_STOP)) {
                // Stop service
                started = false;
                stopForeground(true);
                stopSelf();
                return Service.START_NOT_STICKY;
            } else if (inputIntent.getAction().equals(ACTION_UPDATE_GPS)) {
                updateViaGps = true;
            } else {
                updateViaGps = false;
            }
        } else {
            updateViaGps = false;
        }
        RemoteLogger.log(this, Const.LOG_INFO, "LocationService onStartCommand: action="
                + (inputIntent != null ? inputIntent.getAction() : "null") + ", updateViaGps=" + updateViaGps);

        // Always (re)register: this picks up provider changes (e.g. enabling High Accuracy) and
        // config-driven restarts, which the previous "only when !started" gate missed.
        if (!requestLocationUpdates()) {
            // No permissions!
            started = false;
            stopForeground(true);
            stopSelf();
            return Service.START_NOT_STICKY;
        }
        if (!started) {
            startAsForeground();
            started = true;
        }
        return START_STICKY;
    }
}
